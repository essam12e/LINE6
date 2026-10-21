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
        // Fixed test key (committed on purpose, see app/signing/README.md) so every CI build can update the
        // installed app. A private key from the environment (NITAAQ_KEYSTORE) takes precedence.
        create("nitaaq") {
            val ks = System.getenv("NITAAQ_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("NITAAQ_KEYSTORE_PASS")
                keyAlias = System.getenv("NITAAQ_KEY_ALIAS") ?: "nitaaq"
                keyPassword = System.getenv("NITAAQ_KEYSTORE_PASS")
            } else {
                storeFile = file("signing/nitaaq-test.p12")
                storeType = "PKCS12"
                storePassword = "nitaaq-test"
                keyAlias = "nitaaq"
                keyPassword = "nitaaq-test"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("nitaaq")
        }
        debug {
            signingConfig = signingConfigs.getByName("nitaaq")
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }

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
