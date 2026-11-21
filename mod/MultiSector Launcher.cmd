@echo off
rem Opens the MultiSector launcher: host or join a game, share saves, start Starsector.
rem Uses the Java that comes with Starsector, so nothing else needs installing.
set "JAVAW=%~dp0..\..\jre\bin\javaw.exe"
if not exist "%JAVAW%" (
    echo Can't find Starsector's Java at %JAVAW%
    echo This file must stay in Starsector\mods\MultiSector.
    pause
    exit /b 1
)
start "" "%JAVAW%" -cp "%~dp0jars\MultiSector.jar" multisector.launcher.Launcher
