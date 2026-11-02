@echo off
setlocal EnableExtensions
call "%~dp0build.cmd" --test
exit /b %errorlevel%
