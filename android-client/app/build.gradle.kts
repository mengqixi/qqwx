plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.huawei.agconnect")
}

android {
    namespace = "com.crossnotify"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.crossnotify"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "WS_URL", "\"ws://47.110.65.93:9527/?type=android\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // HMS Push Kit
    implementation("com.huawei.hms:push:6.11.0.300")

    // UI
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")
}
