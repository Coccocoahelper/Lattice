@echo off
rem Start a Lattice server on WindSpigot.
rem
rem   start-server.bat           use the jars already built in build\libs and modules\*\build\libs
rem   start-server.bat build     run the Gradle build first, then start
rem   start-server.bat clean     drop run\plugins\*.jar before installing, so no stale jar can load
rem
rem The plugin is five jars now: core, bots, Bed Wars, SkyWars and replay. They are copied into
rem run\plugins, and the server data lives in run\plugins\Lattice (an old Hypixel2020 folder is
rem migrated by the plugin on first boot).
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "ROOT=%CD%"
set "RUN=%ROOT%\run"
set "DATA=%RUN%\plugins\Lattice"
set "WINDSPIGOT_URL=https://github.com/Wind-Development/WindSpigot/releases/latest/download/WindSpigot-2.1.4.jar"
set "BUILD=0"
if not defined LATTICE_XMS set "LATTICE_XMS=2G"
if not defined LATTICE_XMX set "LATTICE_XMX=4G"
set "CLEAN=0"
if /i "%~1"=="build" set "BUILD=1"
if /i "%~1"=="clean" set "CLEAN=1"

rem --- Java 21+ -----------------------------------------------------------------------------------
set "JAVA="
for /d %%D in ("%ROOT%\.tools\jdk*") do if exist "%%~fD\bin\java.exe" set "JAVA=%%~fD\bin\java.exe"
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-21*" "%ProgramFiles%\Eclipse Adoptium\jdk-25*" "%ProgramFiles%\Microsoft\jdk-21*" "%ProgramFiles%\Microsoft\jdk-25*" "%ProgramFiles%\Java\jdk-21*" "%ProgramFiles%\Java\jdk-25*") do if exist "%%~fD\bin\java.exe" set "JAVA=%%~fD\bin\java.exe"
if not defined JAVA (
  where java >nul 2>&1 && for /f "delims=" %%J in ('where java') do if not defined JAVA set "JAVA=%%J"
)
if not defined JAVA (
  echo No JDK 21+ found. Install Temurin 21 from https://adoptium.net/temurin/releases/ and set JAVA_HOME, then rerun.
  pause
  exit /b 1
)
for /f "tokens=3" %%V in ('"%JAVA%" -version 2^>^&1 ^| findstr /r "version"') do set "JAVAVER=%%~V"
echo Java: "%JAVA%" (!JAVAVER!)

rem --- build --------------------------------------------------------------------------------------
if "%BUILD%"=="1" (
  echo Building...
  call "%ROOT%\gradlew.bat" --no-daemon jar :bots:jar :bedwars:jar :skywars:jar :replay:jar
  if errorlevel 1 ( pause & exit /b 1 )
)

rem --- the five plugin jars -----------------------------------------------------------------------
if not exist "%RUN%\plugins" mkdir "%RUN%\plugins"
if "%CLEAN%"=="1" del /q "%RUN%\plugins\*.jar" >nul 2>&1

rem the old single jar and the h2020-* modules would load a second copy of every command
if exist "%RUN%\plugins\Hypixel2020.jar" (
  if not exist "%RUN%\plugins\disabled" mkdir "%RUN%\plugins\disabled"
  move /y "%RUN%\plugins\Hypixel2020.jar" "%RUN%\plugins\disabled\" >nul
  echo Moved stale Hypixel2020.jar to plugins\disabled\
)
for %%F in ("%RUN%\plugins\h2020-*.jar") do (
  if exist "%%~fF" (
    if not exist "%RUN%\plugins\disabled" mkdir "%RUN%\plugins\disabled"
    move /y "%%~fF" "%RUN%\plugins\disabled\" >nul
    echo Moved stale %%~nxF to plugins\disabled\
  )
)

call :installjar "Lattice.jar" "%ROOT%\build\libs\Lattice.jar"
if errorlevel 1 ( pause & exit /b 1 )
for %%M in (bots bedwars skywars replay) do (
  call :installjar "lattice-%%M.jar" "%ROOT%\modules\%%M\build\libs\lattice-%%M.jar"
  if errorlevel 1 ( pause & exit /b 1 )
)

rem --- maps: one folder per family -----------------------------------------------------------------
if not exist "%DATA%\maps\bedwars" mkdir "%DATA%\maps\bedwars"
if not exist "%DATA%\maps\skywars" mkdir "%DATA%\maps\skywars"
call :installmaps "%ROOT%\maps\bedwars" bedwars
call :installmaps "%ROOT%\maps\skywars" skywars
call :installmaps "%ROOT%\import\ready\bedwars" bedwars
call :installmaps "%ROOT%\import\ready\skywars" skywars
rem a map dropped straight into import\ready\ is filed on boot using the family in its map.yml
for /d %%M in ("%ROOT%\import\ready\*") do (
  if not exist "%DATA%\maps\%%~nxM" if not exist "%DATA%\maps\bedwars\%%~nxM" if not exist "%DATA%\maps\skywars\%%~nxM" (
    xcopy /e /i /q "%%~fM" "%DATA%\maps\%%~nxM" >nul
  )
)

rem --- the block data for the maps: one file per world, they go straight into the data source -------------------
if exist "%ROOT%\slime_worlds" (
  if not exist "%RUN%\slime_worlds" mkdir "%RUN%\slime_worlds"
  for %%F in ("%ROOT%\slime_worlds\*.swofty") do (
    if not exist "%RUN%\slime_worlds\%%~nxF" copy /y "%%~fF" "%RUN%\slime_worlds\" >nul
  )
)

rem --- the slime world manager (optional, but the default: maps load from one file and every game gets a copy) -------
if not defined SWM_VERSION set "SWM_VERSION=5.1.0"
set "SWM_BASE=https://github.com/Swofty-Developments/Continued-Slime-World-Manager/releases/download/%SWM_VERSION%"
if not exist "%ROOT%\libs\swm" mkdir "%ROOT%\libs\swm"
call :download "%SWM_BASE%/swoftyworldmanager-plugin-%SWM_VERSION%.jar" "%ROOT%\libs\swm\swoftyworldmanager-plugin-%SWM_VERSION%.jar"
if exist "%ROOT%\libs\swm\swoftyworldmanager-plugin-%SWM_VERSION%.jar" (
  copy /y "%ROOT%\libs\swm\swoftyworldmanager-plugin-%SWM_VERSION%.jar" "%RUN%\plugins\" >nul
  call :download "%SWM_BASE%/swoftyworldmanager-classmodifier-%SWM_VERSION%.jar" "%ROOT%\libs\swm\swoftyworldmanager-classmodifier-%SWM_VERSION%.jar"
  if exist "%ROOT%\libs\swm\swoftyworldmanager-classmodifier-%SWM_VERSION%.jar" copy /y "%ROOT%\libs\swm\swoftyworldmanager-classmodifier-%SWM_VERSION%.jar" "%RUN%\" >nul
) else (
  echo Continuing without the slime world manager: worlds stay as folders.
)

rem --- server files ---------------------------------------------------------------------------------
if not exist "%RUN%\WindSpigot.jar" (
  echo Downloading WindSpigot...
  call :download "%WINDSPIGOT_URL%" "%RUN%\WindSpigot.jar"
  if errorlevel 1 ( pause & exit /b 1 )
)

if not exist "%RUN%\server.properties" (
  powershell -NoProfile -Command "$p=-join((48..57)+(97..122)|Get-Random -Count 20|%%{[char]$_}); (Get-Content '%ROOT%\server-defaults\server.properties') -replace 'CHANGE_ME',$p | Set-Content -Path '%RUN%\server.properties' -Encoding UTF8"
)
if not exist "%RUN%\spigot.yml" copy /y "%ROOT%\server-defaults\spigot.yml" "%RUN%\spigot.yml" >nul
if not exist "%RUN%\windspigot.yml" copy /y "%ROOT%\server-defaults\windspigot.yml" "%RUN%\windspigot.yml" >nul~
if not exist "%RUN%\knockback.yml" copy /y "%ROOT%\server-defaults\knockback.yml" "%RUN%\knockback.yml" >nul
rem the End is a world nobody here plays in; the air world in server.properties is the only Minecraft-generator world
if exist "%RUN%\bukkit.yml" (
  powershell -NoProfile -Command "$p='%RUN%\bukkit.yml'; (Get-Content $p) -replace 'allow-end: true','allow-end: false' | Set-Content $p"
)

findstr /c:"eula=true" "%RUN%\eula.txt" >nul 2>&1
if errorlevel 1 (
  echo Minecraft servers need you to accept the Minecraft EULA: https://aka.ms/MinecraftEULA
  set /p "EULA=Do you accept it? (y/n) "
  if /i not "!EULA!"=="y" (
    echo The server can't start without accepting the EULA.
    pause
    exit /b 1
  )
  > "%RUN%\eula.txt" echo eula=true
)

set "AGENT="
for %%A in ("%RUN%\swoftyworldmanager-classmodifier-*.jar") do if exist "%%~fA" set "AGENT=-javaagent:%%~fA"
if defined AGENT echo Agent: %AGENT%

cd /d "%RUN%"
echo Starting server. Join "localhost" from Minecraft 1.8.9. Type "stop" here to shut it down.
"%JAVA%" %AGENT% -Xms%LATTICE_XMS% -Xmx%LATTICE_XMX% -XX:+UseG1GC %LATTICE_JVM_OPTS% -jar WindSpigot.jar nogui
pause
exit /b %ERRORLEVEL%

:installjar
set "JAR=%~1"
set "SRC=%~2"
if defined PLUGIN_SRC if exist "%PLUGIN_SRC%\%JAR%" set "SRC=%PLUGIN_SRC%\%JAR%"
if not exist "%SRC%" (
  if exist "%RUN%\plugins\%JAR%" (
    echo   %JAR% (installed)
    exit /b 0
  )
  echo Missing %JAR%. Run "start-server.bat build", or build with gradlew jar, or set PLUGIN_SRC.
  exit /b 1
)
copy /y "%SRC%" "%RUN%\plugins\%JAR%" >nul
echo   %JAR%
exit /b 0

:installmaps
if not exist "%~1" exit /b 0
for /d %%M in ("%~1\*") do (
  if not exist "%DATA%\maps\%~2\%%~nxM" if not exist "%DATA%\maps\%%~nxM" (
    xcopy /e /i /q "%%~fM" "%DATA%\maps\%~2\%%~nxM" >nul
    echo Installed %~2 map %%~nxM
  )
)
exit /b 0

:download
set "URL=%~1"
set "OUT=%~2"
if exist "%OUT%" exit /b 0
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%URL%' -OutFile '%OUT%'"
exit /b %ERRORLEVEL%
