@echo off
setlocal EnableExtensions
call "%~dp0build.cmd" --java
if errorlevel 1 exit /b 1
if not exist "%~dp0..\dist\chat.jar" (
    call "%~dp0build.cmd"
    if errorlevel 1 exit /b 1
)
"%CHAT_JAVA%" -Dfile.encoding=UTF-8 -jar "%~dp0..\dist\chat.jar" server %*
exit /b %errorlevel%
