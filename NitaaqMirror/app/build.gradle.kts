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
        versionCode = 3
        versionName = "0.1.2"
    }

    buildFeatures { buildConfig = true }

    // Two editions of the same app (same package, same protocol):
    //  - controller: everything except the touch-control accessibility service (for the phone you hold;
    //    can also share its screen view-only).
    //  - full: adds the accessibility service, needed only on the phone being controlled.
    flavorDimensions += "edition"
    productFlavors {
        create("controller") {
            dimension = "edition"
            buildConfigField("boolean", "TOUCH_CONTROL", "false")
        }
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "TOUCH_CONTROL", "true")
        }
    }

    signingConfigs {
        // The release key comes only from the environment (GitHub Actions secrets); nothing is stored in the repo.
        create("release") {
            val ks = System.getenv("NITAAQ_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storeType = "PKCS12"
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

    testOptions { unitTests.isReturnDefaultValues = true }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
