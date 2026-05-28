@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ANDROID_HOME=C:\Users\Administrator\AppData\Local\Android\Sdk
set PATH=%JAVA_HOME%\bin;%PATH%
C:
cd \Users\Administrator\Documents\trae_projects\qqwx\android-client
echo Starting Gradle build...
call gradlew.bat assembleDebug --no-daemon
echo Exit code: %ERRORLEVEL%
pause
