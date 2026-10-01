import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing config lives outside the repo (secrets), in ~/.config/rd2mac/.
// Absent → release builds fall back to unsigned (debug sideload still works).
val keystorePropsFile = file("${System.getProperty("user.home")}/.config/rd2mac/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "net.simioni.rd2mac"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.simioni.rd2mac"
        minSdk = 33          // Android 13: READ_MEDIA_IMAGES, POST_NOTIFICATIONS
        targetSdk = 35
        versionCode = 6
        versionName = "0.6"   // alinhado com o Mac: auto-update + vídeo grande sem derrubar o app
        // Self-update feed (01/10/2026) — the Mac reads the same release (Updates.swift).
        // A test build points at a local feed: -Prd2macUpdateFeed=http://<mac-ip>:8000/latest.json
        val updateFeed = project.findProperty("rd2macUpdateFeed")
            ?: "https://api.github.com/repos/esimioni/rd2mac/releases/latest"
        buildConfigField("String", "UPDATE_FEED_URL", "\"$updateFeed\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        // A small heap, closer to the phone's app heap: the "bigger than the heap"
        // streaming tests size their bodies from it, and stay fast.
        unitTests.all { it.maxHeapSize = "128m" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    // The real org.json on the JVM: android.jar only carries stubs that throw.
    testImplementation("org.json:json:20250517")
}
