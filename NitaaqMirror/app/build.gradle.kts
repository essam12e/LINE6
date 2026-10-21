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
        versionCode = 5
        versionName = "0.1.4"
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
        // Stable key so every build installs/updates over the last one with the same signature.
        // Default: a committed TEST key (app/signing/nitaaq-test.jks, password below) — protects nothing secret.
        // For a private distribution build, set NITAAQ_KEYSTORE / NITAAQ_KEYSTORE_PASS / NITAAQ_KEY_ALIAS.
        create("app") {
            val ks = System.getenv("NITAAQ_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storeType = "PKCS12"
                storePassword = System.getenv("NITAAQ_KEYSTORE_PASS")
                keyAlias = System.getenv("NITAAQ_KEY_ALIAS") ?: "nitaaq"
                keyPassword = System.getenv("NITAAQ_KEYSTORE_PASS")
            } else {
                storeFile = file("signing/nitaaq-test.jks")
                storeType = "PKCS12"
                storePassword = "nitaaqmirror"
                keyAlias = "nitaaq"
                keyPassword = "nitaaqmirror"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("app")
        }
        debug {
            signingConfig = signingConfigs.getByName("app")
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
