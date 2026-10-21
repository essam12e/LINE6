plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "sa.nitaaq.mirror"
    compileSdk = 35

    defaultConfig {
        applicationId = "sa.nitaaq.mirror"
        minSdk = 26          // StrokeDescription.continueStroke (live gestures) needs API 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        // Release signing only from the environment; no key material in the repository.
        create("release") {
            val ks = System.getenv("NITAAQ_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("NITAAQ_KEYSTORE_PASS")
                keyAlias = System.getenv("NITAAQ_KEY_ALIAS") ?: "nitaaq"
                keyPassword = System.getenv("NITAAQ_KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("NITAAQ_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
