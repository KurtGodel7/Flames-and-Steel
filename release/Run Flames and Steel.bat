@echo off
rem Flames and Steel playtest release: checks Java, installs the map, starts the game.
rem Keep this window open while you play; closing it closes the game.
setlocal EnableDelayedExpansion
title Flames and Steel - keep this window open while playing
cd /d "%~dp0"
set NEED=25

rem --- 1. Java ---------------------------------------------------------------
set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
set JVER=
set "JTMP=%TEMP%\flames_and_steel_java.txt"
"%JAVA%" -version > "%JTMP%" 2>&1
for /f "usebackq tokens=3" %%v in (`findstr /i "version" "%JTMP%"`) do if not defined JVER set "JVER=%%~v"
del "%JTMP%" >nul 2>&1
if not defined JVER goto nojava
for /f "tokens=1 delims=." %%m in ("%JVER%") do set "JMAJOR=%%m"
if "%JMAJOR%"=="1" goto nojava
if %JMAJOR% LSS %NEED% goto nojava
echo Java %JVER% found.

rem --- 2. Map ----------------------------------------------------------------
rem The game reads maps from Documents\triplea if that folder exists, otherwise from %USERPROFILE%\triplea.
set "ROOT=%USERPROFILE%\triplea"
if exist "%USERPROFILE%\Documents\triplea" set "ROOT=%USERPROFILE%\Documents\triplea"
set "MAPS=%ROOT%\downloadedMaps"
if not exist "%MAPS%" mkdir "%MAPS%"
echo Installing the Flames and Steel map into %MAPS%\flames_and_steel ...
rem /MIR replaces only the flames_and_steel folder; other maps and saved games are not touched.
robocopy "%~dp0flames_and_steel" "%MAPS%\flames_and_steel" /MIR /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
  echo Could not copy the map. Close the game if it is running, then try again.
  pause
  exit /b 1
)

rem --- 3. Start --------------------------------------------------------------
cd /d "%~dp0game"
set GAMEJAR=
for %%J in (bin\game-headed-*.jar) do set "GAMEJAR=%%J"
if not defined GAMEJAR (
  echo The game file is missing from the "game\bin" folder. Unzip the whole release again.
  pause
  exit /b 1
)
echo Starting Flames and Steel. Errors are written to "%~dp0game\console.log".
"%JAVA%" -jar "%GAMEJAR%" > console.log 2>&1
exit /b 0

:nojava
echo.
echo  Flames and Steel needs Java %NEED% or newer.
if defined JVER (echo  This computer has Java %JVER%.) else (echo  Java was not found on this computer.)
echo.
echo  Install "Temurin %NEED%" from https://adoptium.net (Windows: the .msi installer,
echo  accept the defaults), then start this file again.
echo.
start "" "https://adoptium.net/temurin/releases/?version=%NEED%"
pause
exit /b 1
