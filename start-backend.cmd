@echo off
rem =============================================================
rem  510K backend launcher
rem  Starts the already-built jar in a SEPARATE process and returns
rem  immediately, so the current terminal is never blocked.
rem  (Build first with run-dev.cmd, or: mvn -q -DskipTests package)
rem =============================================================
setlocal EnableExtensions
cd /d "%~dp0"

set "PORT=18080"
set "JAR_NAME=springboot-wxcloudrun-1.0.jar"

set "MYSQL_PASSWORD=UfbHW26H"
set "WX_APPID=wx5f1eb569b5de318d"
set "WX_SECRET=2d40580e5a9277f93bb8030602f535a6"

if not exist "target\%JAR_NAME%" (
    echo [ERROR] target\%JAR_NAME% not found. Run run-dev.cmd to build it first.
    exit /b 1
)
if not exist "logs" mkdir "logs"

set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE if exist "%USERPROFILE%\.jdks\corretto-1.8.0_452\bin\java.exe" set "JAVA_EXE=%USERPROFILE%\.jdks\corretto-1.8.0_452\bin\java.exe"
if not defined JAVA_EXE if exist "%USERPROFILE%\.jdks\ms-21.0.13.0.2\bin\java.exe" set "JAVA_EXE=%USERPROFILE%\.jdks\ms-21.0.13.0.2\bin\java.exe"
if not defined JAVA_EXE (
    for /d %%J in ("%USERPROFILE%\.jdks\*") do if not defined JAVA_EXE if exist "%%~fJ\bin\java.exe" set "JAVA_EXE=%%~fJ\bin\java.exe"
)
if not defined JAVA_EXE (
    echo [ERROR] A JDK was not found. Set JAVA_HOME and retry.
    exit /b 1
)

rem /min + redirection keeps java off this terminal's stdio, so `start`
rem returns at once and the caller can continue with other commands.
start "510K Backend" /min "%JAVA_EXE%" -Dserver.port=%PORT% -jar "target\%JAR_NAME%" > "logs\backend.log" 2>&1

echo Backend started in a separate process on http://127.0.0.1:%PORT%/
echo Log: %~dp0logs\backend.log
exit /b 0