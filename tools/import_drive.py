"""Import only distributable modpack files from a Drive ZIP; never execute its code."""
import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from launcher.updater import ROOTS, safe_path
from tools.publish_pack import build_pack, publish

# Local access credentials must not be distributed with a public modpack.
EXCLUDE = {"config/resourceful-config-web.json", "config/watermedia.toml"}


def import_zip(archive, destination):
    count = 0
    mods = 0
    total = 0
    seen = set()
    with zipfile.ZipFile(archive) as bundle:
        for member in bundle.infolist():
            if member.is_dir() or not member.filename.startswith("minecraft/"):
                continue
            name = member.filename.removeprefix("minecraft/")
            parts = name.split("/")
            if len(parts) < 2 or parts[0] not in ROOTS or any(p.startswith(".") for p in parts):
                continue
            if name in EXCLUDE:
                continue
            if name.startswith('mods/voicechat-') and name.endswith('.jar'):
                continue
            if parts[0] == "mods" and (len(parts) != 2 or not name.endswith(".jar")):
                continue
            if stat.S_ISLNK(member.external_attr >> 16):
                raise ValueError("Collegamento simbolico non consentito: " + name)
            target = safe_path(destination,name)
            if name.casefold() in seen:
                raise ValueError("File duplicato: " + name)
            seen.add(name.casefold())
            total += member.file_size
            if total > 20 * 1024**3 or member.file_size > 2 * 1024**3:
                raise ValueError("Dimensione massima del modpack superata.")
            target.parent.mkdir(parents=True,exist_ok=True)
            with bundle.open(member) as inp, target.open("wb") as out:
                shutil.copyfileobj(inp,out)
            # Stop on recognizable credentials, without printing their values.
            if parts[0] in {"config","kubejs","defaultconfigs"} and member.file_size < 2*1024**2:
                text = target.read_bytes().decode("utf-8",errors="replace")
                if re.search(r"gh[pousr]_[A-Za-z0-9]{20,}|-----BEGIN .*PRIVATE KEY-----",text):
                    raise ValueError("Possibile credenziale in " + name + "; importazione fermata.")
            count += 1
            mods += parts[0] == "mods"
    if not mods:
        raise ValueError("Nessuna mod trovata nella cartella minecraft/mods dello ZIP.")
    return {"files":count,"mods":mods,"bytes":total,"excluded_credentials":sorted(EXCLUDE)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive",type=Path)
    parser.add_argument("--publish",action="store_true")
    args = parser.parse_args()
    cfg = json.loads((ROOT/"import-drive.json").read_text("utf-8"))
    version = os.environ.get("PACK_VERSION") or cfg["version"]
    drive_url = os.environ.get("MODPACK_DRIVE_URL", "")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}",version):
        raise ValueError("Versione non valida")
    if not args.archive and not re.fullmatch(r"https://drive\.google\.com/file/d/[A-Za-z0-9_-]+/view(?:\?[^\s]*)?",drive_url):
        raise ValueError("Configura il segreto GitHub MODPACK_DRIVE_URL con il link di condivisione Drive.")
    work = ROOT/"release-pack"/("import-"+version)
    work.mkdir(parents=True,exist_ok=False)
    archive = args.archive or work/"download.zip"
    if not args.archive:
        import gdown
        try:
            result = gdown.download(url=drive_url,output=str(archive),fuzzy=True,use_cookies=False,quiet=True)
        except Exception:
            raise RuntimeError("Download da Drive non riuscito. Verifica il segreto e i permessi di condivisione.") from None
        if not result:
            raise RuntimeError("Download da Drive non riuscito: verifica la condivisione del file.")
    if version == cfg["version"]:
        with archive.open("rb") as f:
            sha = hashlib.file_digest(f,"sha256").hexdigest()
        if sha != cfg["sha256"]:
            raise ValueError("Lo ZIP su Drive è cambiato. Aggiorna il link per una nuova importazione.")
    source = work/"pack"
    report = import_zip(archive,source)
    settings = json.loads((ROOT/"pack-settings.json").read_text("utf-8"))
    output = work/"release"
    build_pack(cfg["repository"],version,source,settings,output)
    print(json.dumps(report,indent=2))
    if args.publish:
        publish(cfg["repository"],cfg.get("branch","main"),version,output)


if __name__ == "__main__":
    main()
