@echo off
setlocal EnableExtensions EnableDelayedExpansion
call "%~dp0build.cmd" --java
if errorlevel 1 exit /b 1
if not exist "%~dp0..\dist\chat.jar" (
    call "%~dp0build.cmd"
    if errorlevel 1 exit /b 1
)
if "%~1"=="" (
    "%CHAT_JAVA%" -Dfile.encoding=UTF-8 -jar "%~dp0..\dist\chat.jar" client --profile default
    exit /b !errorlevel!
)
set "PROFILE=%~1"
set "CLIENT_ARGS="
shift /1
:collectArguments
if "%~1"=="" goto runClient
set "CLIENT_ARGS=!CLIENT_ARGS! "%~1""
shift /1
goto collectArguments
:runClient
"%CHAT_JAVA%" -Dfile.encoding=UTF-8 -jar "%~dp0..\dist\chat.jar" client --profile "!PROFILE!" !CLIENT_ARGS!
exit /b %errorlevel%
