@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem 510K local development: stop old process, compile, and start backend in background.
cd /d "%~dp0"
set "PORT=18080"
set "JAR_NAME=springboot-wxcloudrun-1.0.jar"
set "MAVEN_CMD="

rem Local development database credentials (do not commit to public repos).
set "MYSQL_PASSWORD=UfbHW26H"

rem WeChat minigame login credentials (do not commit to public repos).
set "WX_APPID=wx5f1eb569b5de318d"
set "WX_SECRET=2d40580e5a9277f93bb8030602f535a6"

rem Detect a local JDK so the script works from a normal CMD/double-click.
if defined JAVA_HOME if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME="
if not defined JAVA_HOME if exist "%USERPROFILE%\.jdks\corretto-1.8.0_452\bin\java.exe" set "JAVA_HOME=%USERPROFILE%\.jdks\corretto-1.8.0_452"
if not defined JAVA_HOME if exist "%USERPROFILE%\.jdks\ms-21.0.13.0.2\bin\java.exe" set "JAVA_HOME=%USERPROFILE%\.jdks\ms-21.0.13.0.2"
if not defined JAVA_HOME (
    for /d %%J in ("%USERPROFILE%\.jdks\*") do if not defined JAVA_HOME if exist "%%~fJ\bin\java.exe" set "JAVA_HOME=%%~fJ"
)
if not defined JAVA_HOME (
    echo [ERROR] A JDK was not found.
    echo         Install JDK 8+ or set JAVA_HOME before running this script.
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "target" mkdir "target"
if not exist "logs" mkdir "logs"

echo [1/3] Stopping backend processes on port %PORT%...
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%PORT% .*LISTENING"') do (
    if not "%%P"=="0" (
        echo        stopping PID %%P
        taskkill /PID %%P /T /F >nul 2>&1
    )
)

rem Prefer Maven already installed on the machine.
for /f "delims=" %%M in ('where mvn.cmd 2^>nul') do if not defined MAVEN_CMD set "MAVEN_CMD=%%M"

rem Fall back to a Maven distribution already downloaded by Maven Wrapper.
if not defined MAVEN_CMD if exist "%USERPROFILE%\.m2\wrapper\dists" (
    for /f "delims=" %%M in ('dir /s /b "%USERPROFILE%\.m2\wrapper\dists\apache-maven-*\bin\mvn.cmd" 2^>nul') do if not defined MAVEN_CMD set "MAVEN_CMD=%%M"
)

rem Also check the Maven Wrapper cache when this script is run from Git Bash/CMD
rem and the cache is not present in the Windows PATH.
if not defined MAVEN_CMD if exist "%USERPROFILE%\.m2\wrapper\dists" (
    for /f "delims=" %%M in ('where /r "%USERPROFILE%\.m2\wrapper\dists" mvn.cmd 2^>nul') do if not defined MAVEN_CMD set "MAVEN_CMD=%%M"
)

rem Last resort: use the project wrapper. It may need network access on first run.
if not defined MAVEN_CMD if exist "%~dp0mvnw.cmd" set "MAVEN_CMD=%~dp0mvnw.cmd"

if not defined MAVEN_CMD (
    echo [ERROR] Maven was not found.
    echo         Install Maven or make mvn.cmd available in PATH.
    exit /b 1
)

echo [2/3] Compiling with %MAVEN_CMD%...
call "%MAVEN_CMD%" -q -DskipTests package
if errorlevel 1 (
    echo [ERROR] Build failed. Backend was not started.
    exit /b 1
)

if not exist "target\%JAR_NAME%" (
    echo [ERROR] Build succeeded but target\%JAR_NAME% was not found.
    exit /b 1
)

echo [3/3] Starting backend on http://127.0.0.1:%PORT%/ ...
rem Launch in a separate minimized console so the backend survives this script exiting.
start "510K Backend" /min cmd /c "java -Dserver.port=%PORT% -jar target\%JAR_NAME% > %~dp0logs\backend.log 2>&1"

rem Wait until the port is actually listening before reporting success.
set /a TRIES=0
:waitloop
ping -n 2 127.0.0.1 >nul
netstat -ano | findstr /R /C:":%PORT% .*LISTENING" >nul 2>&1
if errorlevel 1 (
    set /a TRIES+=1
    if %TRIES% lss 15 goto waitloop
    echo [ERROR] Backend did not start within 15 seconds. Check logs\backend.log
    exit /b 1
)

echo.
echo Done. Open http://127.0.0.1:%PORT%/
echo Log file: %~dp0logs\backend.log
exit /b 0
