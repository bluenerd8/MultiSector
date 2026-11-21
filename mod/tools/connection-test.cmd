@echo off
rem MultiSector connection tester, using the Java that comes with Starsector.
rem Double-click it, or run:  connection-test.cmd ip | host | join <address>
setlocal
set "JAVA=%~dp0..\..\..\jre\bin\java.exe"
set "JAR=%~dp0..\jars\MultiSector.jar"
if not exist "%JAVA%" (
    echo Can't find Starsector's Java at:
    echo   %JAVA%
    echo This file must stay in Starsector\mods\MultiSector\tools.
    pause
    exit /b 1
)

if not "%~1"=="" (
    "%JAVA%" -cp "%JAR%" multisector.net.NetTest %*
    exit /b %ERRORLEVEL%
)

"%JAVA%" -cp "%JAR%" multisector.net.NetTest ip
echo.
echo To test hosting, just press Enter. Keep this window open while your friend tests.
set /p "ADDR=To test joining, type the host's address (e.g. 25.12.34.56) and press Enter: "
echo.
if "%ADDR%"=="" (
    "%JAVA%" -cp "%JAR%" multisector.net.NetTest host
) else (
    "%JAVA%" -cp "%JAR%" multisector.net.NetTest join %ADDR%
)
echo.
pause
