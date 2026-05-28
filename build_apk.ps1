$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\Administrator\AppData\Local\Android\Sdk'
$env:GRADLE_OPTS = '-Djava.net.preferIPv4Stack=true'
Set-Location 'C:\Users\Administrator\Documents\trae_projects\qqwx\android-client'
& .\gradlew.bat assembleDebug --no-daemon 2>&1
