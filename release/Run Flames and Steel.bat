@echo off
rem Flames and Steel playtest release: checks Java, installs the map, starts the game.
rem Keep this window open while you play; closing it closes the game.
setlocal EnableDelayedExpansion
title Flames and Steel - keep this window open while playing
cd /d "%~dp0"
set NEED=25

rem --- 1. Java ---------------------------------------------------------------
rem Tries JAVA_HOME, then the Temurin install folders, then "java" on the PATH; the first that is
rem new enough wins (an older Java that other programs need may come first on the PATH).
set "JAVA="
set "OLDVER="
if defined JAVA_HOME call :try "%JAVA_HOME%\bin\java.exe"
if not defined JAVA for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\*") do if not defined JAVA call :try "%%~D\bin\java.exe"
if not defined JAVA call :try java
if not defined JAVA goto nojava
echo Java %JVER% found: %JAVA%

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

:try
rem Sets JAVA and JVER if %1 runs and is Java %NEED% or newer; remembers an older version found.
set "TV="
set "JTMP=%TEMP%\flames_and_steel_java.txt"
"%~1" -version > "%JTMP%" 2>&1
for /f "usebackq tokens=3" %%v in (`findstr /i "version" "%JTMP%"`) do if not defined TV set "TV=%%~v"
del "%JTMP%" >nul 2>&1
if not defined TV exit /b 0
set "TM="
for /f "tokens=1 delims=.-+" %%m in ("%TV%") do set "TM=%%m"
if "%TM%"=="1" (set "OLDVER=%TV%" & exit /b 0)
if %TM% LSS %NEED% (set "OLDVER=%TV%" & exit /b 0)
set "JAVA=%~1"
set "JVER=%TV%"
exit /b 0

:nojava
echo.
echo  Flames and Steel needs Java %NEED% or newer.
if defined OLDVER (echo  This computer has Java %OLDVER%.) else (echo  Java was not found on this computer.)
echo.
echo  Install "Temurin %NEED%" from https://adoptium.net (Windows: the .msi installer,
echo  accept the defaults), then start this file again.
echo.
start "" "https://adoptium.net/temurin/releases/?version=%NEED%"
pause
exit /b 1
