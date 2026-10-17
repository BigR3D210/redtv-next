@echo off
set "REDTV_PYTHON=%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
if exist "%REDTV_PYTHON%" (
  "%REDTV_PYTHON%" "%~dp0companion\redtv_receiver.py"
) else (
  py -3 "%~dp0companion\redtv_receiver.py"
)
if errorlevel 1 pause
