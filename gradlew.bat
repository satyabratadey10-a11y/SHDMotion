@echo off
where gradle >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
  echo Gradle is not installed or on PATH. Install Gradle or use the GitHub workflow to build the AAR.
  exit /b 1
)
call gradle %*
