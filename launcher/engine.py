import json
import re
import subprocess
from pathlib import Path
from minecraft_launcher_lib import install, mod_loader, command, runtime
from . import VERSION
from .config import DATA, INSTANCE, manifest_url, validate_config
from .updater import Updater, fetch_manifest
from . import auth


def get_pack(cfg, report):
    report("Controllo aggiornamenti su GitHub…")
    manifest = fetch_manifest(manifest_url(cfg))
    Updater(INSTANCE, report).sync(manifest)
    return manifest


def ensure_game(manifest, cfg, report, progress):
    callback = {"setStatus": report, "setMax": lambda n: progress(0, n), "setProgress": lambda n: progress(n, -1)}
    INSTANCE.mkdir(parents=True, exist_ok=True)
    # The library verifies and repairs downloaded official game assets and libraries.
    report("Verifica Minecraft 1.20.1 e Java…")
    install.install_minecraft_version("1.20.1", str(INSTANCE), callback=callback)
    info = json.loads((INSTANCE / "versions/1.20.1/1.20.1.json").read_text("utf-8"))
    java = cfg.get("java_path", "").strip()
    if not java:
        component = info["javaVersion"]["component"]
        java = runtime.get_executable_path(component, str(INSTANCE))
        if not java:
            raise RuntimeError("Java automatico non disponibile su questa piattaforma. Installa Java 17 e selezionalo nelle impostazioni.")
    result = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=20)
    if result.returncode or not re.search(r'version "17[.\"]', result.stderr + result.stdout):
        raise RuntimeError("Minecraft 1.20.1 richiede Java 17. Seleziona un eseguibile Java 17 valido.")
    loader = mod_loader.get_mod_loader("forge")
    version = loader.get_installed_version("1.20.1", manifest["forge"])
    ready = DATA / ("forge-" + manifest["forge"] + ".ready")
    if not ready.exists():
        report("Installazione Forge " + manifest["forge"] + "…")
        version = loader.install("1.20.1", str(INSTANCE), loader_version=manifest["forge"], callback=callback, java=java)
        ready.write_text("ok", encoding="utf-8")
    else:
        install.install_minecraft_version(version, str(INSTANCE), callback=callback)
    return version, java


def play(cfg, session, report, progress, account_ready):
    validate_config(cfg, require_login=True)
    refresh_token = (session or {}).get("refresh_token") or auth.saved_token(cfg["microsoft_client_id"])
    if not refresh_token:
        raise RuntimeError("Accedi con Microsoft prima di giocare.")
    report("Verifica account e licenza Minecraft…")
    session = auth.refresh(cfg["microsoft_client_id"], refresh_token, report)
    account_ready(session)
    manifest = get_pack(cfg, report)
    version, java = ensure_game(manifest, cfg, report, progress)
    options = {
        "username": session["name"], "uuid": session["id"], "token": session["access_token"],
        "executablePath": java, "gameDirectory": str(INSTANCE),
        "jvmArguments": ["-Xms1024M", f'-Xmx{int(cfg["ram_mb"])}M'],
        "launcherName": "NuovoOrdine", "launcherVersion": VERSION,
        "enableLoggingConfig": True,
    }
    server = manifest.get("server") or cfg.get("server")
    if server:
        options["quickPlayMultiplayer"] = server
        options["quickPlayPath"] = str(INSTANCE / "quickplay.json")
    args = command.get_minecraft_command(version, str(INSTANCE), options)
    report("Minecraft è in esecuzione. Buon divertimento!")
    log_path = DATA / "game-output.log"
    secrets = [session["access_token"], session["refresh_token"]]
    # Never log the command line; redact known tokens from game output too.
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(args, cwd=INSTANCE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding="utf-8", errors="replace")
        for line in process.stdout:
            for secret in secrets:
                line = line.replace(secret, "[REDACTED]")
            log.write(line)
        code = process.wait()
    if code:
        raise RuntimeError(f"Minecraft si è chiuso con codice {code}. Apri la cartella del launcher e consulta game-output.log e minecraft/logs/latest.log.")
    report("Minecraft chiuso. Puoi giocare di nuovo.")
    return manifest
