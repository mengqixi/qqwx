@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ANDROID_HOME=C:\Users\Administrator\AppData\Local\Android\Sdk
cd /d C:\Users\Administrator\Documents\trae_projects\qqwx\android-client
call gradlew.bat assembleDebug --no-daemon
pause
