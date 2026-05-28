@if "%DEBUG%"=="" @echo off
@rem Gradle wrapper
@if "%OS%"=="Windows_NT" setlocal
set DIR=%~dp0
"%JAVA_HOME%/bin/java" -classpath "%DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
