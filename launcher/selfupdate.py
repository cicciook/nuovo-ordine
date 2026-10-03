"""Launcher self-update from public GitHub Releases."""
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

MAX_UPDATE = 500 * 1024 * 1024


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


def _asset_for_release(release):
    system = platform_name()
    machine = platform.machine().lower()

    def pick(candidates):
        if not candidates:
            return None
        aliases = {machine}
        if machine in ("amd64", "x86_64"):
            aliases.update({"amd64", "x86_64"})
        elif machine in ("arm64", "aarch64"):
            aliases.update({"arm64", "aarch64"})
        for asset in candidates:
            lower = asset["name"].lower()
            if any(alias in lower for alias in aliases):
                return asset
        return candidates[0]

    assets = release.get("assets", [])
    if system == "windows":
        installers = [
            asset for asset in assets
            if asset.get("name", "").startswith("NuovoOrdine-Setup-windows-")
            and asset.get("name", "").endswith(".exe")
        ]
        selected = pick(installers)
        if selected:
            return selected

    suffix = ".zip" if system in ("windows", "macos") else ".tar.gz"
    archives = [
        asset for asset in assets
        if asset.get("name", "").startswith(f"NuovoOrdine-{system}-")
        and asset.get("name", "").endswith(suffix)
    ]
    return pick(archives)

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
        asset["browser_download_url"],
        timeout=(15, 90),
        stream=True,
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


def prepare_update(cfg, report=lambda text: None):
    """Return a staged update dict, or None when already current/unavailable.

    Network failures never block launching the game; they are reported and ignored.
    """
    if not getattr(sys, "frozen", False):
        report("Modalità sviluppo: aggiornamento automatico launcher non applicato.")
        return None
    try:
        validate_config(cfg)
        repo = cfg["repository"]
        api = f"https://api.github.com/repos/{repo}/releases?per_page=30"
        report("Controllo aggiornamenti del launcher…")
        with requests.get(
            api,
            timeout=(10, 30),
            headers={"Accept": "application/vnd.github+json", "User-Agent": f"NuovoOrdine/{VERSION}"},
        ) as response:
            response.raise_for_status()
            releases = response.json()

        launcher_releases = []
        for release in releases if isinstance(releases, list) else []:
            parsed = version_tuple(release.get("tag_name", ""))
            if parsed and str(release.get("tag_name", "")).startswith("launcher-v"):
                launcher_releases.append((parsed, release))
        current = version_tuple(VERSION)
        if not launcher_releases or not current:
            report(f"Launcher {VERSION} aggiornato.")
            return None
        latest, release = max(launcher_releases, key=lambda item: item[0])
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
        report(f"Nuovo launcher {'.'.join(map(str, latest))} disponibile. Download automatico…")
        _download(asset, archive, report)

        if sys.platform == "win32" and asset["name"].lower().endswith(".exe"):
            return {
                "version": ".".join(map(str, latest)),
                "installer": str(archive),
                "target": str(current_install_path()),
            }

        stage = update_root / "stage"
        stage.mkdir()
        if sys.platform == "darwin":
            subprocess.run(
                ["ditto", "-x", "-k", str(archive), str(stage)],
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            payload = stage / "NuovoOrdine.app"
        elif asset["name"].endswith(".zip"):
            _safe_zip_extract(archive, stage)
            payload = stage / "NuovoOrdine"
        else:
            _safe_tar_extract(archive, stage)
            payload = stage / "NuovoOrdine"

        if not payload.exists():
            raise RuntimeError("Il pacchetto dell'aggiornamento non contiene il launcher atteso.")

        return {
            "version": ".".join(map(str, latest)),
            "payload": str(payload),
            "target": str(current_install_path()),
        }
    except Exception as exc:
        report(f"Aggiornamento launcher non disponibile ({type(exc).__name__}); continuo con la versione attuale.")
        return None


def _ps_quote(value):
    return str(value).replace("'", "''")


def _sh_quote(value):
    return "'" + str(value).replace("'", "'\"'\"'") + "'"


def apply_update(update):
    """Spawn a detached helper that applies the staged launcher after this process exits.

    Windows deliberately updates files in-place instead of renaming the running
    installation directory. Renaming the whole PyInstaller folder is unreliable on
    Windows because loaded DLLs and antivirus/indexers can keep directory handles open.
    """
    if "installer" in update:
        installer = Path(update["installer"]).resolve()
        if sys.platform != "win32" or not installer.is_file() or installer.suffix.lower() != ".exe":
            raise RuntimeError("Installer aggiornamento non valido.")

        DATA.mkdir(parents=True, exist_ok=True)
        pid = os.getpid()
        script = DATA / "apply-launcher-installer.ps1"
        log = DATA / "launcher-update.log"
        local_app_data = Path(os.environ.get("LOCALAPPDATA", DATA))
        installed_exe = local_app_data / "Programs" / "NuovoOrdine" / "NuovoOrdine.exe"
        body = f"""$ErrorActionPreference = 'Stop'
$log = '{_ps_quote(log)}'
$setup = '{_ps_quote(installer)}'
$exe = '{_ps_quote(installed_exe)}'
function Write-UpdateLog([string]$message) {{
    try {{ Add-Content -LiteralPath $log -Value ((Get-Date -Format o) + ' ' + $message) }} catch {{ }}
}}
try {{
    Write-UpdateLog 'Installer updater avviato.'
    $pidToWait = {pid}
    while (Get-Process -Id $pidToWait -ErrorAction SilentlyContinue) {{ Start-Sleep -Milliseconds 250 }}
    Start-Sleep -Milliseconds 500
    $process = Start-Process -FilePath $setup -ArgumentList '/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/CLOSEAPPLICATIONS' -Wait -PassThru
    if ($process.ExitCode -ne 0) {{ throw ('Installer exit code ' + $process.ExitCode) }}
    if (-not (Test-Path -LiteralPath $exe)) {{ throw 'NuovoOrdine.exe non trovato dopo installazione.' }}
    Write-UpdateLog 'Installazione completata; riavvio launcher.'
    Start-Process -FilePath $exe -WorkingDirectory (Split-Path -Parent $exe) | Out-Null
}} catch {{
    Write-UpdateLog ('ERRORE installer: ' + $_)
    exit 1
}}
Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue
"""
        script.write_text(body, encoding="utf-8-sig")
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) | getattr(subprocess, "DETACHED_PROCESS", 0)
        subprocess.Popen(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(script)],
            cwd=str(DATA),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=flags,
            close_fds=True,
        )
        return

    payload = Path(update["payload"]).resolve()
    target = Path(update["target"]).resolve()
    if not payload.exists() or not target.exists():
        raise RuntimeError("Aggiornamento preparato non valido.")

    DATA.mkdir(parents=True, exist_ok=True)
    pid = os.getpid()

    if sys.platform == "win32":
        script = DATA / "apply-launcher-update.ps1"
        log = DATA / "launcher-update.log"
        backup = DATA / "launcher-update-backup"
        exe = target / "NuovoOrdine.exe"
        body = f"""$ErrorActionPreference = 'Stop'
$log = '{_ps_quote(log)}'
$src = '{_ps_quote(payload)}'
$dst = '{_ps_quote(target)}'
$backup = '{_ps_quote(backup)}'
$exe = '{_ps_quote(exe)}'
function Write-UpdateLog([string]$message) {{
    Add-Content -LiteralPath $log -Value ((Get-Date -Format o) + ' ' + $message)
}}
function Invoke-Robocopy([string]$from, [string]$to, [string]$name) {{
    New-Item -ItemType Directory -Force -Path $to | Out-Null
    & robocopy.exe $from $to /MIR /COPY:DAT /DCOPY:DAT /R:8 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
    $code = $LASTEXITCODE
    if ($code -ge 8) {{ throw "$name robocopy failed with exit code $code" }}
}}
function Restore-Backup() {{
    if (Test-Path -LiteralPath $backup) {{
        Write-UpdateLog 'Restoring previous launcher.'
        Invoke-Robocopy $backup $dst 'rollback'
    }}
}}
try {{
    Write-UpdateLog 'Updater started.'
    $pidToWait = {pid}
    while (Get-Process -Id $pidToWait -ErrorAction SilentlyContinue) {{ Start-Sleep -Milliseconds 250 }}
    Start-Sleep -Milliseconds 750

    if (-not (Test-Path -LiteralPath $src)) {{ throw 'Staged launcher is missing.' }}
    if (Test-Path -LiteralPath $backup) {{ Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction Stop }}

    Write-UpdateLog 'Creating backup.'
    Invoke-Robocopy $dst $backup 'backup'

    Write-UpdateLog 'Installing new launcher in-place.'
    Invoke-Robocopy $src $dst 'install'
    if (-not (Test-Path -LiteralPath $exe)) {{ throw 'New executable missing after update.' }}

    Write-UpdateLog 'Starting updated launcher.'
    $newProcess = Start-Process -FilePath $exe -WorkingDirectory $dst -PassThru
    Start-Sleep -Seconds 6
    $newProcess.Refresh()
    if ($newProcess.HasExited) {{
        Write-UpdateLog ('New launcher exited early with code ' + $newProcess.ExitCode + '. Rolling back.')
        Restore-Backup
        if (-not (Test-Path -LiteralPath $exe)) {{ throw 'Executable missing after rollback.' }}
        Start-Process -FilePath $exe -WorkingDirectory $dst | Out-Null
        throw 'Updated launcher exited during startup; rollback completed.'
    }}

    Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue
    Write-UpdateLog 'Update completed successfully.'
}} catch {{
    Write-UpdateLog ('ERROR: ' + $_)
    try {{
        if (-not (Test-Path -LiteralPath $exe) -and (Test-Path -LiteralPath $backup)) {{ Restore-Backup }}
        if (Test-Path -LiteralPath $exe) {{
            $already = Get-Process -Name 'NuovoOrdine' -ErrorAction SilentlyContinue
            if (-not $already) {{ Start-Process -FilePath $exe -WorkingDirectory $dst | Out-Null }}
        }}
    }} catch {{ Write-UpdateLog ('Rollback/restart failed: ' + $_) }}
    exit 1
}}
Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue
"""
        # Windows PowerShell 5.1 reliably detects UTF-8 when a BOM is present.
        script.write_text(body, encoding="utf-8-sig")
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) | getattr(subprocess, "DETACHED_PROCESS", 0)
        subprocess.Popen(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(script)],
            cwd=str(DATA),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=flags,
            close_fds=True,
        )
        return

    script = DATA / "apply-launcher-update.sh"
    log = DATA / "launcher-update.log"
    backup = target.with_name(target.name + ".old")
    if sys.platform == "darwin":
        relaunch = f"open {_sh_quote(target)}"
    else:
        relaunch = f"nohup {_sh_quote(target / 'NuovoOrdine')} >/dev/null 2>&1 &"
    body = f"""#!/bin/sh
set -eu
log={_sh_quote(log)}
echo "$(date -Iseconds) Updater avviato." >> "$log"
while kill -0 {pid} 2>/dev/null; do sleep 0.25; done
sleep 0.5
src={_sh_quote(payload)}
dst={_sh_quote(target)}
old={_sh_quote(backup)}
rm -rf "$old"
if mv "$dst" "$old" && mv "$src" "$dst"; then
  echo "$(date -Iseconds) Aggiornamento installato, riavvio launcher." >> "$log"
  {relaunch}
  sleep 3
  rm -rf "$old"
  echo "$(date -Iseconds) Aggiornamento completato." >> "$log"
else
  echo "$(date -Iseconds) Aggiornamento fallito; tento rollback." >> "$log"
  rm -rf "$dst" || true
  if [ -e "$old" ]; then mv "$old" "$dst" || true; fi
  {relaunch} || true
  exit 1
fi
rm -f "$0"
"""
    script.write_text(body, encoding="utf-8")
    script.chmod(0o700)
    subprocess.Popen(
        ["/bin/sh", str(script)],
        cwd=str(DATA),
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
        close_fds=True,
    )
