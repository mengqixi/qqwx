@if "%DEBUG%"=="" @echo off
@rem Gradle wrapper
@if "%OS%"=="Windows_NT" setlocal
set DIR=%~dp0
"%JAVA_HOME%/bin/java" -jar "%DIR%gradle\wrapper\gradle-wrapper.jar" %*
