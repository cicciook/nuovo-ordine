"""More robust launcher replacement helper used by packaged builds.

Windows updates are installed in-place with robocopy instead of renaming the
whole running installation directory.  A backup lives under the launcher data
directory and is automatically restored if the new executable cannot start.
"""
import os
import shutil
import subprocess
import sys
from pathlib import Path

from .config import DATA


def _ps_quote(value):
    return str(value).replace("'", "''")


def _sh_quote(value):
    return "'" + str(value).replace("'", "'\"'\"'") + "'"


def apply_update(update):
    payload = Path(update["payload"]).resolve()
    target = Path(update["target"]).resolve()
    if not payload.is_dir() or not target.is_dir():
        raise RuntimeError("Aggiornamento preparato non valido.")

    DATA.mkdir(parents=True, exist_ok=True)
    pid = os.getpid()
    log = DATA / "launcher-update.log"

    if sys.platform == "win32":
        script = DATA / "apply-launcher-update.ps1"
        backup = DATA / "launcher-backup"
        exe = target / "NuovoOrdine.exe"
        body = f"""$ErrorActionPreference = 'Stop'
$log = '{_ps_quote(log)}'
$src = '{_ps_quote(payload)}'
$dst = '{_ps_quote(target)}'
$backup = '{_ps_quote(backup)}'
$exe = '{_ps_quote(exe)}'

function Write-UpdateLog([string]$message) {{
    try {{ Add-Content -LiteralPath $log -Value ((Get-Date -Format o) + ' ' + $message) }} catch {{ }}
}}

function Mirror-Directory([string]$source, [string]$destination, [string]$label) {{
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    & robocopy.exe $source $destination /MIR /R:20 /W:1 /COPY:DAT /DCOPY:DAT /NFL /NDL /NJH /NJS /NP | Out-Null
    $code = $LASTEXITCODE
    if ($code -ge 8) {{ throw "$label fallito (robocopy exit code $code)" }}
}}

function Start-LauncherAndVerify() {{
    if (-not (Test-Path -LiteralPath $exe)) {{ throw 'NuovoOrdine.exe non trovato.' }}
    $process = Start-Process -FilePath $exe -WorkingDirectory $dst -PassThru
    Start-Sleep -Seconds 4
    if ($process.HasExited) {{
        throw "Il nuovo launcher si e' chiuso subito dopo l'avvio (exit code $($process.ExitCode))."
    }}
}}

Write-UpdateLog 'Updater v2 avviato.'
try {{
    $pidToWait = {pid}
    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Process -Id $pidToWait -ErrorAction SilentlyContinue) -and ((Get-Date) -lt $deadline)) {{
        Start-Sleep -Milliseconds 250
    }}
    if (Get-Process -Id $pidToWait -ErrorAction SilentlyContinue) {{
        throw 'Il vecchio launcher non si e'' chiuso entro 45 secondi.'
    }}
    Start-Sleep -Milliseconds 750

    if (Test-Path -LiteralPath $backup) {{ Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue }}
    Write-UpdateLog 'Creo backup della versione attuale.'
    Mirror-Directory $dst $backup 'backup launcher'

    try {{
        Write-UpdateLog 'Installo la nuova versione in-place.'
        Mirror-Directory $src $dst 'installazione launcher'
        Start-LauncherAndVerify
        Write-UpdateLog 'Nuovo launcher avviato correttamente.'
        Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue
        Write-UpdateLog 'Aggiornamento completato.'
    }} catch {{
        Write-UpdateLog ('Installazione/avvio fallito: ' + $_)
        Write-UpdateLog 'Ripristino automaticamente la versione precedente.'
        Mirror-Directory $backup $dst 'rollback launcher'
        if (Test-Path -LiteralPath $exe) {{
            Start-Process -FilePath $exe -WorkingDirectory $dst | Out-Null
        }}
        throw
    }}
}} catch {{
    Write-UpdateLog ('ERRORE: ' + $_)
    exit 1
}} finally {{
    Start-Sleep -Milliseconds 300
    Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue
}}
"""
        script.write_text(body, encoding="utf-8")
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

    # macOS/Linux keep the same principle: backup outside the installation,
    # replace only after the current process exits, rollback on any failure.
    script = DATA / "apply-launcher-update.sh"
    backup = DATA / "launcher-backup"
    if sys.platform == "darwin":
        relaunch = f"open {_sh_quote(target)}"
    else:
        relaunch = f"nohup {_sh_quote(target / 'NuovoOrdine')} >/dev/null 2>&1 &"
    body = f"""#!/bin/sh
set -eu
log={_sh_quote(log)}
src={_sh_quote(payload)}
dst={_sh_quote(target)}
backup={_sh_quote(backup)}
echo "$(date -Iseconds 2>/dev/null || date) Updater v2 avviato." >> "$log"
while kill -0 {pid} 2>/dev/null; do sleep 0.25; done
sleep 0.75
rm -rf "$backup"
cp -a "$dst" "$backup"
if rm -rf "$dst" && cp -a "$src" "$dst"; then
  if [ -e "$dst" ]; then
    {relaunch}
    sleep 3
    rm -rf "$backup"
    echo "$(date -Iseconds 2>/dev/null || date) Aggiornamento completato." >> "$log"
  else
    false
  fi
else
  echo "$(date -Iseconds 2>/dev/null || date) Aggiornamento fallito; rollback." >> "$log"
  rm -rf "$dst" || true
  cp -a "$backup" "$dst" || true
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
