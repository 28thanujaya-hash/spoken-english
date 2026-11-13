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
        // Every GitHub build gets a higher number, so the app can tell when a newer one exists.
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionCode = 100 + run
        versionName = "1.2 (build $run)"
        // The free Gemini key comes from the GitHub secret GEMINI_API_KEY at build time.
        // It can also be typed in the app (gear button), which overrides this one.
        val key = System.getenv("GEMINI_API_KEY") ?: ""
        buildConfigField("String", "GEMINI_API_KEY", "\"$key\"")
        buildConfigField("String", "UPDATE_REPO", "\"28thanujaya-hash/spoken-english-updates\"")
        // Reports go to the private repo as Issues; the token (GitHub secret REPORTS_TOKEN) may only write Issues.
        val reports = System.getenv("REPORTS_TOKEN") ?: ""
        buildConfigField("String", "REPORTS_TOKEN", "\"$reports\"")
        buildConfigField("String", "REPORTS_REPO", "\"28thanujaya-hash/spoken-english\"")
        // The student's A34 and the mentor's X9c are both 64-bit ARM; only that is built (keeps the APK small).
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Paradee voice: misaki-g2p + our bridge (app/src/main/cpp). ONNX Runtime itself is downloaded, not built in.
    ndkVersion = "27.2.12479018"
    // Keep the native library compressed inside the APK: every update is a full APK download.
    packaging { jniLibs { useLegacyPackaging = true } }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
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
