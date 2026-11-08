plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes -PversionCode=<n> (auto-incremented per build) and -PgithubRepo=<owner/repo>.
val ciVersionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
val githubRepo = (project.findProperty("githubRepo") as String?) ?: ""
// "Send log to Claude": the box signs in to the owner's GitHub App with a device code; only the App's
// public Client ID ships in the app (gradle.properties: taycanLogClientId). No secret is built in.
val logClientId = (project.findProperty("taycanLogClientId") as String?)?.trim() ?: ""
// Spotify App Remote: public Client ID of the owner's Spotify developer app (gradle.properties).
val spotifyClientId = (project.findProperty("spotifyClientId") as String?)?.trim() ?: ""
val logRepo = if (githubRepo.contains("/")) githubRepo.substringBefore("/") + "/taycan-logs" else ""

// Release signing comes from environment variables set by the GitHub Actions workflow
// (decoded from repository secrets). The same key must sign every build so updates
// install over the existing app.
val signingStoreFile = System.getenv("SIGNING_STORE_FILE")

android {
    namespace = "com.taycanscreen.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.taycanscreen.app"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = "0.1.$ciVersionCode"
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
        buildConfigField("String", "LOG_REPO", "\"$logRepo\"")
        buildConfigField("String", "LOG_CLIENT_ID", "\"$logClientId\"")
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"taycanscreen://spotify-callback\"")
    }

    signingConfigs {
        if (signingStoreFile != null) {
            create("release") {
                storeFile = file(signingStoreFile)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Spotify App Remote SDK 0.8.0 (github.com/spotify/android-sdk, Apache 2.0; see libs/SPOTIFY-SDK-*).
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
}
