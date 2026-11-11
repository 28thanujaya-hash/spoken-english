plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "lk.spokenenglish.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "lk.spokenenglish.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2-test"
        // The free Gemini key comes from the GitHub secret GEMINI_API_KEY at build time.
        // It can also be typed in the app (gear button), which overrides this one.
        val key = System.getenv("GEMINI_API_KEY") ?: ""
        buildConfigField("String", "GEMINI_API_KEY", "\"$key\"")
    }

    signingConfigs {
        getByName("debug") {
            // Fixed test key, so each new APK installs over the old one.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
