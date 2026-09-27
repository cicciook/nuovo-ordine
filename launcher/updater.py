"""Verified downloads, tracked-file updates and recoverable transactions."""
import hashlib
import json
import os
import re
import shutil
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from urllib.parse import urlsplit
import requests
from .config import atomic_json

ROOTS = {"mods", "config", "defaultconfigs", "kubejs", "resourcepacks", "shaderpacks", "scripts", "tacz", "customnpcs"}
MAX_FILE = 2 * 1024**3
MAX_PACK = 20 * 1024**3


def safe_path(root, name):
    if not isinstance(name, str) or not name or "\\" in name:
        raise ValueError("Percorso del modpack non valido.")
    parts = name.split("/")
    if (len(parts) < 2 or parts[0] not in ROOTS or
        any(p in ("", ".", "..") or p[-1:] in (".", " ") or
            re.search(r'[<>:"|?*\x00-\x1f]', p) or
            re.fullmatch(r"(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?", p)
            for p in parts)):
        raise ValueError(f"Percorso del modpack non consentito: {name}")
    root = Path(root).absolute()
    target = root.joinpath(*parts)
    current = root
    for part in parts:
        current = current / part
        if current.is_symlink():
            raise ValueError(f"Collegamento simbolico non consentito: {name}")
    if not target.resolve().is_relative_to(root.resolve()):
        raise ValueError("Percorso esterno al modpack.")
    return target


def https_url(url):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("I download richiedono un URL HTTPS senza credenziali.")
    return url


def digest(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def validate_manifest(data):
    if not isinstance(data, dict) or data.get("schema") != 1:
        raise ValueError("Formato pack.json non supportato.")
    if data.get("minecraft") != "1.20.1" or not re.fullmatch(r"47\.\d+\.\d+", data.get("forge", "")):
        raise ValueError("Questo launcher richiede Minecraft 1.20.1 e Forge 47.x.x.")
    if not isinstance(data.get("version"), str) or not data["version"]:
        raise ValueError("Versione del modpack mancante.")
    if not isinstance(data.get("files"), list) or len(data["files"]) > 20000:
        raise ValueError("Elenco file non valido.")
    archives = data.get("archives", {})
    if not isinstance(archives, dict) or len(archives) > 100:
        raise ValueError("Elenco archivi non valido.")
    for key, archive in archives.items():
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", key):
            raise ValueError("Identificativo archivio non valido.")
        https_url(archive["url"])
        if not re.fullmatch("[a-f0-9]{64}", archive.get("sha256", "")) or type(archive.get("size")) is not int or not 0 < archive["size"] < MAX_FILE:
            raise ValueError("Integrità archivio non valida.")
    names = set()
    total = 0
    for item in data["files"]:
        name = item["path"]
        safe_path(Path(tempfile.gettempdir()) / "nuovo-ordine-validation", name)
        if name.casefold() in names:
            raise ValueError(f"Percorso duplicato: {name}")
        names.add(name.casefold())
        if not re.fullmatch("[a-f0-9]{64}", item.get("sha256", "")):
            raise ValueError(f"SHA-256 non valido: {name}")
        if type(item.get("size")) is not int or not 0 <= item["size"] <= MAX_FILE:
            raise ValueError(f"Dimensione non valida: {name}")
        if item.get("mode", "replace") not in ("replace", "preserve"):
            raise ValueError(f"Modalità non valida: {name}")
        if name.startswith("mods/") and (not name.endswith(".jar") or item.get("mode", "replace") != "replace"):
            raise ValueError("La cartella mods accetta solo JAR gestiti con replace.")
        if "archive" in item:
            if item["archive"] not in archives or "url" in item:
                raise ValueError("Riferimento archivio non valido.")
        else:
            https_url(item["url"])
        total += item["size"]
    if total > MAX_PACK:
        raise ValueError("Modpack troppo grande (limite 20 GiB).")
    for name in names:
        if any(str(parent) in names for parent in PurePosixPath(name).parents):
            raise ValueError("Conflitto tra file e cartella nel manifest.")
    if not isinstance(data.get("server", ""), str) or len(data.get("server", "")) > 255:
        raise ValueError("Indirizzo server non valido.")
    return data


def fetch_manifest(url):
    with requests.get(https_url(url), timeout=(15, 45), stream=True) as response:
        response.raise_for_status()
        https_url(response.url)
        body = bytearray()
        for chunk in response.iter_content(65536):
            body.extend(chunk)
            if len(body) > 8 * 1024**2:
                raise ValueError("Manifest troppo grande.")
    return validate_manifest(json.loads(body))


def download(item, target, report):
    for attempt in range(3):
        try:
            h = hashlib.sha256()
            count = 0
            with requests.get(https_url(item["url"]), timeout=(15, 60), stream=True) as response:
                response.raise_for_status()
                https_url(response.url)
                with target.open("wb") as output:
                    for chunk in response.iter_content(1024 * 256):
                        count += len(chunk)
                        if count > item["size"]:
                            raise ValueError("Download più grande del previsto.")
                        h.update(chunk)
                        output.write(chunk)
                        report(f'Download {item["path"]} • {count // 1024} / {item["size"] // 1024} KiB')
            if count != item["size"] or h.hexdigest() != item["sha256"]:
                raise ValueError(f'Integrità non valida: {item["path"]}')
            return
        except (requests.RequestException, ValueError):
            target.unlink(missing_ok=True)
            if attempt == 2:
                raise


class Updater:
    def __init__(self, root, report=lambda message: None, downloader=download):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.report = report
        self.downloader = downloader
        self.state = self.root / ".nuovo-ordine-state.json"
        self.tx = self.root / ".nuovo-ordine-transaction"

    def recover(self):
        journal = self.tx / "journal.json"
        if journal.exists():
            data = json.loads(journal.read_text("utf-8"))
            # Backups are copies and remain intact until the entire recovery succeeds.
            for op in reversed(data["operations"]):
                target = safe_path(self.root, op["path"])
                if op["existed"]:
                    backup = self.tx / "backup" / str(op["index"])
                    target.parent.mkdir(parents=True, exist_ok=True)
                    temp = target.with_name(target.name + ".no-recover")
                    shutil.copy2(backup, temp)
                    os.replace(temp, target)
                else:
                    target.unlink(missing_ok=True)
            if data["old_state"] is None:
                self.state.unlink(missing_ok=True)
            else:
                atomic_json(self.state, data["old_state"])
            journal.unlink()
            self.report("Aggiornamento interrotto ripristinato; riprovo.")
        if self.tx.exists():
            shutil.rmtree(self.tx)

    def sync(self, manifest):
        manifest = validate_manifest(manifest)
        self.recover()
        old = json.loads(self.state.read_text("utf-8")) if self.state.exists() else None
        previous = {x["path"]: x for x in (old or {}).get("files", [])}
        current = {x["path"]: x for x in manifest["files"]}
        changes = []
        for name, item in current.items():
            target = safe_path(self.root, name)
            if target.exists() and not target.is_file():
                raise ValueError(f"Il percorso è una cartella: {name}")
            if target.exists() and item.get("mode") == "preserve":
                continue
            if target.exists() and target.stat().st_size == item["size"] and digest(target) == item["sha256"]:
                continue
            changes.append((name, item))
        for name, item in previous.items():
            if name not in current and item.get("mode", "replace") == "replace":
                target = safe_path(self.root, name)
                if target.exists():
                    changes.append((name, None))
        self.tx.mkdir()
        (self.tx / "backup").mkdir()
        (self.tx / "stage").mkdir()
        (self.tx / "archives").mkdir()
        ops = []
        try:
            # Download and verify ALL files before modifying the current installation.
            for index, (name, item) in enumerate(changes):
                target = safe_path(self.root, name)
                if item:
                    staged = self.tx / "stage" / str(index)
                    if "archive" in item:
                        key = item["archive"]
                        cached = self.tx / "archives" / key
                        if not cached.exists():
                            archive = {**manifest["archives"][key], "path": "pacchetto " + key}
                            self.downloader(archive, cached, self.report)
                            if cached.stat().st_size != archive["size"] or digest(cached) != archive["sha256"]:
                                raise ValueError("Archivio scaricato non valido.")
                        with zipfile.ZipFile(cached) as bundle:
                            member = bundle.getinfo(name)
                            if member.file_size != item["size"]:
                                raise ValueError("Dimensione del file nell'archivio non valida.")
                            with bundle.open(member) as source, staged.open("wb") as output:
                                shutil.copyfileobj(source, output)
                    else:
                        self.downloader(item, staged, self.report)
                    staged = self.tx / "stage" / str(index)
                    if staged.stat().st_size != item["size"] or digest(staged) != item["sha256"]:
                        raise ValueError(f"Download non valido: {name}")
                exists = target.is_file()
                if exists:
                    shutil.copy2(target, self.tx / "backup" / str(index))
                ops.append({"path": name, "existed": exists, "index": index, "remove": item is None})
            atomic_json(self.tx / "journal.json", {"old_state": old, "operations": ops})
            for op in ops:
                target = safe_path(self.root, op["path"])
                if op["remove"]:
                    target.unlink(missing_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    os.replace(self.tx / "stage" / str(op["index"]), target)
            atomic_json(self.state, manifest)
            (self.tx / "journal.json").unlink()
        except BaseException:
            self.recover()
            raise
        finally:
            if self.tx.exists() and not (self.tx / "journal.json").exists():
                shutil.rmtree(self.tx)
        # Additional player mods are preserved, but reported for diagnostics.
        extras = [p.name for p in (self.root / "mods").glob("*.jar") if "mods/" + p.name not in current]
        if extras:
            self.report("Mod aggiunte manualmente (possibili incompatibilità): " + ", ".join(extras))
        self.report(f'Modpack {manifest["version"]} pronto • {len(changes)} file aggiornati')
        return len(changes)
