"""Launcher updates from public GitHub Releases, preferring native installers."""
import hashlib
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import zipfile
from pathlib import Path

import requests

from . import VERSION
from .config import DATA, validate_config

MAX_UPDATE = 700 * 1024 * 1024


def version_tuple(value):
    match = re.fullmatch(r"(?:launcher-v|v)?(\d+)\.(\d+)\.(\d+)", str(value).strip())
    if not match:
        return None
    return tuple(int(part) for part in match.groups())


def platform_name():
    return {"win32": "windows", "darwin": "macos"}.get(sys.platform, "linux")


def current_install_path():
    executable = Path(sys.executable).resolve()
    if sys.platform == "darwin":
        for parent in executable.parents:
            if parent.suffix == ".app":
                return parent
        raise RuntimeError("Bundle macOS del launcher non trovato.")
    return executable.parent


def _machine_aliases():
    machine = platform.machine().lower()
    aliases = {machine}
    if machine in ("amd64", "x86_64"):
        aliases.update({"amd64", "x86_64"})
    elif machine in ("arm64", "aarch64"):
        aliases.update({"arm64", "aarch64"})
    return aliases


def _pick(candidates):
    if not candidates:
        return None
    aliases = _machine_aliases()
    for asset in candidates:
        lower = asset.get("name", "").lower()
        if any(alias in lower for alias in aliases):
            return asset
    return candidates[0]


def _installer_asset_for_release(release):
    system = platform_name()
    suffix = {"windows": ".exe", "macos": ".pkg", "linux": ".deb"}[system]
    prefix = f"NuovoOrdine-Setup-{system}-"
    return _pick([
        asset for asset in release.get("assets", [])
        if asset.get("name", "").startswith(prefix) and asset.get("name", "").endswith(suffix)
    ])


def _legacy_asset_for_release(release):
    system = platform_name()
    suffix = ".zip" if system in ("windows", "macos") else ".tar.gz"
    return _pick([
        asset for asset in release.get("assets", [])
        if asset.get("name", "").startswith(f"NuovoOrdine-{system}-")
        and asset.get("name", "").endswith(suffix)
    ])


def _asset_for_release(release):
    return _installer_asset_for_release(release) or _legacy_asset_for_release(release)


def _safe_zip_extract(archive, destination):
    destination = Path(destination).resolve()
    with zipfile.ZipFile(archive) as bundle:
        for member in bundle.infolist():
            target = (destination / member.filename).resolve()
            if not target.is_relative_to(destination):
                raise ValueError("Archivio aggiornamento non valido.")
        bundle.extractall(destination)


def _safe_tar_extract(archive, destination):
    with tarfile.open(archive, "r:gz") as bundle:
        bundle.extractall(destination, filter="data")


def _download(asset, target, report):
    size = int(asset.get("size") or 0)
    if size <= 0 or size > MAX_UPDATE:
        raise ValueError("Dimensione aggiornamento launcher non valida.")
    digest_header = asset.get("digest") or ""
    expected_hash = digest_header.removeprefix("sha256:") if digest_header.startswith("sha256:") else None
    downloaded = 0
    hasher = hashlib.sha256()
    with requests.get(
        asset["browser_download_url"], timeout=(15, 90), stream=True,
        headers={"Accept": "application/octet-stream", "User-Agent": f"NuovoOrdine/{VERSION}"},
    ) as response:
        response.raise_for_status()
        with target.open("wb") as output:
            for chunk in response.iter_content(1024 * 512):
                if not chunk:
                    continue
                downloaded += len(chunk)
                if downloaded > size or downloaded > MAX_UPDATE:
                    raise ValueError("Aggiornamento launcher più grande del previsto.")
                hasher.update(chunk)
                output.write(chunk)
                report(f"Download launcher • {downloaded // 1048576} / {max(1, size // 1048576)} MiB")
    if downloaded != size:
        raise ValueError("Download aggiornamento launcher incompleto.")
    if expected_hash and hasher.hexdigest() != expected_hash:
        raise ValueError("Integrità aggiornamento launcher non valida.")


def _is_installer(path):
    return Path(path).suffix.lower() in {".exe", ".pkg", ".deb"}


def prepare_update(cfg, report=lambda text: None):
    if not getattr(sys, "frozen", False):
        report("Modalità sviluppo: aggiornamento launcher non applicato.")
        return None
    try:
        validate_config(cfg)
        repo = cfg["repository"]
        api = f"https://api.github.com/repos/{repo}/releases?per_page=30"
        report("Controllo aggiornamenti del launcher…")
        with requests.get(
            api, timeout=(10, 30),
            headers={"Accept": "application/vnd.github+json", "User-Agent": f"NuovoOrdine/{VERSION}"},
        ) as response:
            response.raise_for_status()
            releases = response.json()

        available = []
        for release in releases if isinstance(releases, list) else []:
            tag = str(release.get("tag_name", ""))
            parsed = version_tuple(tag)
            if parsed and tag.startswith("launcher-v"):
                available.append((parsed, release))
        current = version_tuple(VERSION)
        if not available or not current:
            report(f"Launcher {VERSION} aggiornato.")
            return None
        latest, release = max(available, key=lambda item: item[0])
        if latest <= current:
            report(f"Launcher {VERSION} aggiornato.")
            return None

        asset = _asset_for_release(release)
        if not asset:
            report("Nuova versione trovata, ma non c'è un pacchetto per questo sistema.")
            return None

        update_root = DATA / "launcher-update" / ".".join(map(str, latest))
        if update_root.exists():
            shutil.rmtree(update_root, ignore_errors=True)
        update_root.mkdir(parents=True, exist_ok=True)
        archive = update_root / asset["name"]
        report(f"Nuovo launcher {'.'.join(map(str, latest))} disponibile. Download…")
        _download(asset, archive, report)

        if _is_installer(archive):
            report("Installer aggiornamento pronto.")
            return {"version": ".".join(map(str, latest)), "installer": str(archive)}

        stage = update_root / "stage"
        stage.mkdir()
        if sys.platform == "darwin":
            subprocess.run(["ditto", "-x", "-k", str(archive), str(stage)], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            payload = stage / "NuovoOrdine.app"
        elif asset["name"].endswith(".zip"):
            _safe_zip_extract(archive, stage)
            payload = stage / "NuovoOrdine"
        else:
            _safe_tar_extract(archive, stage)
            payload = stage / "NuovoOrdine"
        if not payload.exists():
            raise RuntimeError("Il pacchetto dell'aggiornamento non contiene il launcher atteso.")
        return {"version": ".".join(map(str, latest)), "payload": str(payload), "target": str(current_install_path())}
    except Exception as exc:
        report(f"Aggiornamento launcher non disponibile ({type(exc).__name__}); continuo con la versione attuale.")
        return None


def _ps_quote(value):
    return str(value).replace("'", "''")


def _sh_quote(value):
    return "'" + str(value).replace("'", "'\"'\"'") + "'"


def _apply_installer(update):
    installer = Path(update["installer"]).resolve()
    if not installer.is_file():
        raise RuntimeError("Installer aggiornamento non valido.")
    DATA.mkdir(parents=True, exist_ok=True)
    pid = os.getpid()

    if sys.platform == "win32":
        script = DATA / "apply-launcher-installer.ps1"
        log = DATA / "launcher-update.log"
        local = Path(os.environ.get("LOCALAPPDATA", DATA))
        installed_exe = local / "Programs" / "NuovoOrdine" / "NuovoOrdine.exe"
        old_exe = Path(sys.executable).resolve()
        body = f"""$ErrorActionPreference = 'Stop'
$installer = '{_ps_quote(installer)}'
$installedExe = '{_ps_quote(installed_exe)}'
$oldExe = '{_ps_quote(old_exe)}'
$log = '{_ps_quote(log)}'
function Log([string]$m) {{ Add-Content -LiteralPath $log -Value ((Get-Date -Format o) + ' ' + $m) }}
try {{
  while (Get-Process -Id {pid} -ErrorAction SilentlyContinue) {{ Start-Sleep -Milliseconds 250 }}
  Start-Sleep -Milliseconds 400
  Log 'Avvio installer Nuovo Ordine.'
  $args = @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/CLOSEAPPLICATIONS','/SP-')
  $p = Start-Process -FilePath $installer -ArgumentList $args -Wait -PassThru
  if ($p.ExitCode -ne 0) {{ throw ('Installer exit code ' + $p.ExitCode) }}
  if (Test-Path -LiteralPath $installedExe) {{
    Start-Process -FilePath $installedExe -WorkingDirectory (Split-Path $installedExe) | Out-Null
  }} elseif (Test-Path -LiteralPath $oldExe) {{
    Start-Process -FilePath $oldExe -WorkingDirectory (Split-Path $oldExe) | Out-Null
  }}
  Log 'Aggiornamento tramite installer completato.'
}} catch {{
  Log ('ERROR: ' + $_)
  if (Test-Path -LiteralPath $oldExe) {{ Start-Process -FilePath $oldExe -WorkingDirectory (Split-Path $oldExe) | Out-Null }}
  exit 1
}}
Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue
"""
        script.write_text(body, encoding="utf-8-sig")
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) | getattr(subprocess, "DETACHED_PROCESS", 0)
        subprocess.Popen(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(script)],
            cwd=str(DATA), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            creationflags=flags, close_fds=True,
        )
        return

    if sys.platform == "darwin":
        subprocess.Popen(["open", str(installer)], stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=True, close_fds=True)
        return

    opener = shutil.which("xdg-open")
    if not opener:
        raise RuntimeError("Impossibile aprire il pacchetto .deb: xdg-open non disponibile.")
    subprocess.Popen([opener, str(installer)], stdin=subprocess.DEVNULL,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True, close_fds=True)


def _apply_legacy(update):
    payload = Path(update["payload"]).resolve()
    target = Path(update["target"]).resolve()
    if not payload.exists() or not target.exists():
        raise RuntimeError("Aggiornamento preparato non valido.")
    DATA.mkdir(parents=True, exist_ok=True)
    pid = os.getpid()
    if sys.platform == "win32":
        script = DATA / "apply-launcher-update.ps1"
        exe = target / "NuovoOrdine.exe"
        body = f"""$ErrorActionPreference='Stop'
while (Get-Process -Id {pid} -ErrorAction SilentlyContinue) {{ Start-Sleep -Milliseconds 250 }}
& robocopy.exe '{_ps_quote(payload)}' '{_ps_quote(target)}' /MIR /R:8 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) {{ exit 1 }}
Start-Process -FilePath '{_ps_quote(exe)}' -WorkingDirectory '{_ps_quote(target)}' | Out-Null
Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue
"""
        script.write_text(body, encoding="utf-8-sig")
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) | getattr(subprocess, "DETACHED_PROCESS", 0)
        subprocess.Popen(["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(script)],
                         cwd=str(DATA), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         creationflags=flags, close_fds=True)
        return
    script = DATA / "apply-launcher-update.sh"
    relaunch = f"open {_sh_quote(target)}" if sys.platform == "darwin" else f"nohup {_sh_quote(target / 'NuovoOrdine')} >/dev/null 2>&1 &"
    body = f"""#!/bin/sh
set -eu
while kill -0 {pid} 2>/dev/null; do sleep 0.25; done
src={_sh_quote(payload)}
dst={_sh_quote(target)}
old={_sh_quote(target.with_name(target.name + '.old'))}
rm -rf "$old"
mv "$dst" "$old"
if mv "$src" "$dst"; then
  {relaunch}
  sleep 2
  rm -rf "$old"
else
  rm -rf "$dst" || true
  mv "$old" "$dst" || true
  {relaunch} || true
  exit 1
fi
rm -f "$0"
"""
    script.write_text(body, encoding="utf-8")
    script.chmod(0o700)
    subprocess.Popen(["/bin/sh", str(script)], cwd=str(DATA), stdin=subprocess.DEVNULL,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True, close_fds=True)


def apply_update(update):
    if update.get("installer"):
        return _apply_installer(update)
    return _apply_legacy(update)
