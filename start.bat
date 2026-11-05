@echo off
REM Double-click this file to start Ashore Prospect.
REM Requires Docker Desktop to be installed and running.
cd /d "%~dp0"
docker compose up -d --build
if errorlevel 1 (
  echo.
  echo Something went wrong. Is Docker Desktop running?
  pause
  exit /b 1
)
echo.
echo Ashore Prospect is running at http://localhost:3456
start "" http://localhost:3456
pause
