@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
for %%I in ("%~dp0..") do set "ROOT=%%~fI"
pushd "%ROOT%" || exit /b 1
if not exist "tmp" mkdir "tmp"
set "TASK_TMP_OWNED="
set "TMP_ATTEMPTS=0"
:createTaskTmp
set /a TMP_ATTEMPTS+=1 >nul
if %TMP_ATTEMPTS% gtr 32 (
    echo ERROR: cannot create an exclusive build temporary directory. 1>&2
    goto failure
)
set "TASK_TMP=%ROOT%\tmp\java-course-build-%RANDOM%-%RANDOM%"
mkdir "%TASK_TMP%" >nul 2>&1
if errorlevel 1 goto createTaskTmp
set "TASK_TMP_OWNED=1"
set "MODE=%~1"
if not "%MODE%"=="" if not "%MODE%"=="--test" if not "%MODE%"=="--java" (
    echo ERROR: unknown build option %MODE% 1>&2
    goto failure
)
if defined JAVA_HOME (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    set "JAR=%JAVA_HOME%\bin\jar.exe"
    call :verifyJdk
    if not errorlevel 1 goto jdkReady
)
set "JAVA=java.exe"
set "JAVAC=javac.exe"
set "JAR=jar.exe"
call :verifyJdk
if not errorlevel 1 goto jdkReady
set "JAVA=C:\Program Files\Java\jdk-17.0.11-full\bin\java.exe"
set "JAVAC=C:\Program Files\Java\jdk-17.0.11-full\bin\javac.exe"
set "JAR=C:\Program Files\Java\jdk-17.0.11-full\bin\jar.exe"
call :verifyJdk
if errorlevel 1 (
    echo ERROR: JDK 17 was not found. Set JAVA_HOME in this command session. 1>&2
    goto failure
)
:jdkReady
echo JDK 17: %JAVA%
if "%MODE%"=="--java" goto success
if not exist "build\main" mkdir "build\main"
if not exist "dist" mkdir "dist"
type nul > "%TASK_TMP%\sources.args"
for /r "%ROOT%\src\main\java" %%F in (*.java) do (
    set "SOURCE=%%~fF"
    >> "%TASK_TMP%\sources.args" echo "!SOURCE:\=/!"
)
"%JAVAC%" -J-Dfile.encoding=UTF-8 --release 17 -encoding UTF-8 -d "build\main" @"%TASK_TMP%\sources.args"
if errorlevel 1 goto failure
"%JAR%" --create --file "dist\chat.jar" --main-class cn.edu.course.chat.Main -C "build\main" .
if errorlevel 1 goto failure
echo Build succeeded: %ROOT%\dist\chat.jar
if "%MODE%"=="--test" (
    if not exist "build\test" mkdir "build\test"
    type nul > "%TASK_TMP%\tests.args"
    for /r "%ROOT%\src\test\java" %%F in (*.java) do (
        set "SOURCE=%%~fF"
        >> "%TASK_TMP%\tests.args" echo "!SOURCE:\=/!"
    )
    "%JAVAC%" -J-Dfile.encoding=UTF-8 --release 17 -encoding UTF-8 -cp "build\main" -d "build\test" @"%TASK_TMP%\tests.args"
    if errorlevel 1 goto failure
    call :cleanup
    "%JAVA%" -ea -Dfile.encoding=UTF-8 -cp "build\main;build\test" cn.edu.course.chat.AllTests
    set "RESULT=!errorlevel!"
    popd
    exit /b !RESULT!
)
:success
call :cleanup
popd
endlocal & set "CHAT_JAVA=%JAVA%" & exit /b 0

:verifyJdk
"%JAVA%" --version > "%TASK_TMP%\java-version.txt" 2>&1
if errorlevel 1 exit /b 1
findstr /r /c:"^openjdk 17\." /c:"^java 17\." "%TASK_TMP%\java-version.txt" >nul
if errorlevel 1 exit /b 1
"%JAVAC%" -version > "%TASK_TMP%\javac-version.txt" 2>&1
if errorlevel 1 exit /b 1
findstr /r /c:"^javac 17\." "%TASK_TMP%\javac-version.txt" >nul
if errorlevel 1 exit /b 1
"%JAR%" --version > "%TASK_TMP%\jar-version.txt" 2>&1
if errorlevel 1 exit /b 1
findstr /r /c:"^jar 17\." "%TASK_TMP%\jar-version.txt" >nul
if errorlevel 1 exit /b 1
exit /b 0

:cleanup
if defined TASK_TMP_OWNED if exist "%TASK_TMP%" (
    del /q "%TASK_TMP%\sources.args" "%TASK_TMP%\tests.args" "%TASK_TMP%\java-version.txt" "%TASK_TMP%\javac-version.txt" "%TASK_TMP%\jar-version.txt" 2>nul
    rd "%TASK_TMP%" 2>nul
)
exit /b 0

:failure
call :cleanup
popd
exit /b 1
