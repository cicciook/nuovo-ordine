import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

from minecraft_launcher_lib import command, install, mod_loader, runtime
from minecraft_launcher_lib.exceptions import InvalidRefreshToken

from . import VERSION, auth
from .config import DATA, INSTANCE, manifest_url, validate_config
from .updater import Updater, fetch_manifest


def hidden_process_kwargs():
    if sys.platform == "win32":
        return {"creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0)}
    return {}


def get_pack(cfg, report):
    report("Controllo aggiornamenti su GitHub…")
    manifest = fetch_manifest(manifest_url(cfg))
    Updater(INSTANCE, report).sync(manifest)
    return manifest


def ensure_game(manifest, cfg, report, progress):
    callback = {
        "setStatus": report,
        "setMax": lambda n: progress(0, n),
        "setProgress": lambda n: progress(n, -1),
    }
    INSTANCE.mkdir(parents=True, exist_ok=True)
    report("Verifica Minecraft 1.20.1 e Java…")
    install.install_minecraft_version("1.20.1", str(INSTANCE), callback=callback)
    info = json.loads((INSTANCE / "versions/1.20.1/1.20.1.json").read_text("utf-8"))
    java = cfg.get("java_path", "").strip()
    if not java:
        component = info["javaVersion"]["component"]
        java = runtime.get_executable_path(component, str(INSTANCE))
        if not java:
            raise RuntimeError(
                "Java automatico non disponibile su questa piattaforma. "
                "Installa Java 17 e selezionalo nelle impostazioni."
            )
    result = subprocess.run(
        [java, "-version"],
        capture_output=True,
        text=True,
        timeout=20,
        **hidden_process_kwargs(),
    )
    if result.returncode or not re.search(r'version "17[.\"]', result.stderr + result.stdout):
        raise RuntimeError(
            "Minecraft 1.20.1 richiede Java 17. Seleziona un eseguibile Java 17 valido."
        )
    loader = mod_loader.get_mod_loader("forge")
    version = loader.get_installed_version("1.20.1", manifest["forge"])
    ready = DATA / ("forge-" + manifest["forge"] + ".ready")
    if not ready.exists():
        report("Installazione Forge " + manifest["forge"] + "…")
        version = loader.install(
            "1.20.1",
            str(INSTANCE),
            loader_version=manifest["forge"],
            callback=callback,
            java=java,
        )
        ready.write_text("ok", encoding="utf-8")
    else:
        install.install_minecraft_version(version, str(INSTANCE), callback=callback)
    return version, java


def offline_uuid(name):
    digest = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return bytes(digest).hex()


def play(cfg, session, report, progress, account_ready, offline_name=None):
    validate_config(cfg)
    client_id = cfg.get("microsoft_client_id", "").strip()
    refresh_token = (session or {}).get("refresh_token")
    if not refresh_token and client_id:
        refresh_token = auth.saved_token(client_id)

    online = bool(refresh_token)
    if online:
        validate_config(cfg, require_login=True)

        # Se l'utente ha appena completato il login, la sessione Minecraft è
        # già valida: non forziamo immediatamente un secondo refresh OAuth.
        if auth.session_is_valid(session):
            report("Account Microsoft verificato • avvio Minecraft…")
            account_ready(session)
        else:
            report("Rinnovo accesso Microsoft e licenza Minecraft…")
            try:
                session = auth.refresh(client_id, refresh_token, report)
            except InvalidRefreshToken:
                # auth.refresh elimina il token non valido dal portachiavi.
                # Svuotiamo anche la sessione UI così il pulsante Accedi torna
                # subito disponibile senza dover riavviare il launcher.
                account_ready({})
                raise
            account_ready(session)
    else:
        session = None
        if not re.fullmatch(r"[A-Za-z0-9_]{3,16}", offline_name or ""):
            raise ValueError("Scegli un nome offline valido da 3 a 16 caratteri.")
        report(
            f"Modalità offline come {offline_name}. "
            "Il server Nuovo Ordine non verrà aperto automaticamente."
        )

    manifest = get_pack(cfg, report)
    version, java = ensure_game(manifest, cfg, report, progress)

    if online:
        options = {
            "username": session["name"],
            "uuid": session["id"],
            "token": session["access_token"],
            "executablePath": java,
            "gameDirectory": str(INSTANCE),
            "jvmArguments": ["-Xms1024M", f'-Xmx{int(cfg["ram_mb"])}M'],
            "launcherName": "NuovoOrdine",
            "launcherVersion": VERSION,
            "enableLoggingConfig": True,
        }
        server = manifest.get("server") or cfg.get("server")
        if server:
            options["quickPlayMultiplayer"] = server
            options["quickPlayPath"] = str(INSTANCE / "quickplay.json")
        secrets = [session["access_token"], session["refresh_token"]]
        report("Minecraft è in esecuzione in modalità online.")
    else:
        options = {
            "username": offline_name,
            "uuid": offline_uuid(offline_name),
            "token": "0",
            "executablePath": java,
            "gameDirectory": str(INSTANCE),
            "jvmArguments": ["-Xms1024M", f'-Xmx{int(cfg["ram_mb"])}M'],
            "launcherName": "NuovoOrdine",
            "launcherVersion": VERSION,
            "enableLoggingConfig": True,
        }
        secrets = []
        report(
            f"Minecraft è in esecuzione in modalità offline come {offline_name}. "
            "Il collegamento automatico al server è disattivato."
        )

    args = command.get_minecraft_command(version, str(INSTANCE), options)
    log_path = DATA / "game-output.log"
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            args,
            cwd=INSTANCE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            **hidden_process_kwargs(),
        )
        for line in process.stdout:
            for secret in secrets:
                line = line.replace(secret, "[REDACTED]")
            log.write(line)
        code = process.wait()

    if code:
        raise RuntimeError(
            f"Minecraft si è chiuso con codice {code}. Apri la cartella del launcher "
            "e consulta game-output.log e minecraft/logs/latest.log."
        )
    report("Minecraft chiuso. Puoi giocare di nuovo.")
    return manifest
