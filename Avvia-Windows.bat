@echo off
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (
  py -3.12 -m venv .venv
  if errorlevel 1 goto fail
)
.venv\Scripts\python.exe -m pip install -r requirements.txt
if errorlevel 1 goto fail
.venv\Scripts\python.exe main.py
if errorlevel 1 goto fail
exit /b 0
:fail
echo Avvio non riuscito. Installa Python 3.12 oppure usa la versione compilata da GitHub Actions.
pause
exit /b 1
