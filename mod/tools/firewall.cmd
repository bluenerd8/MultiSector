@echo off
rem Double-click to let MultiSector players connect to you (only the HOST needs this).
rem Opens TCP+UDP port 7777 in Windows Firewall. Asks for admin rights.
rem "firewall.cmd remove" removes the rules again.
set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=open"
powershell -NoProfile -Command "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','\"%~dp0firewall.ps1\"','-Action','%ACTION%'"
