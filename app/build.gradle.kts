plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.ytnoads"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.ytnoads"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
