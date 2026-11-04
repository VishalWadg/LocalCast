@rem Self-bootstrapping Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off
setlocal enabledelayedexpansion

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if defined JAVA_HOME (
    set "JAVA_HOME=%JAVA_HOME:"=%"
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java.exe"
)

if exist "%WRAPPER_JAR%" (
    "%JAVA_EXE%" -Xmx64m -Xms64m -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
    exit /b !ERRORLEVEL!
)

set GRADLE_VERSION=9.3.1
if defined GRADLE_USER_HOME (
    set "DIST_BASE=%GRADLE_USER_HOME%"
) else (
    set "DIST_BASE=%USERPROFILE%\.gradle"
)
set "DIST_DIR=%DIST_BASE%\wrapper\dists\gradle-%GRADLE_VERSION%-bin\localcast"
set "GRADLE_BAT=%DIST_DIR%\gradle-%GRADLE_VERSION%\bin\gradle.bat"

if not exist "%GRADLE_BAT%" (
    echo Bootstrapping Gradle %GRADLE_VERSION%...
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "$ErrorActionPreference = 'Stop'; " ^
        "New-Item -ItemType Directory -Force -Path '%DIST_DIR%' | Out-Null; " ^
        "$zip = '%DIST_DIR%\gradle.zip'; " ^
        "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $zip; " ^
        "Expand-Archive -Path $zip -DestinationPath '%DIST_DIR%' -Force; " ^
        "Remove-Item $zip -Force"
)

call "%GRADLE_BAT%" %*
exit /b %ERRORLEVEL%
