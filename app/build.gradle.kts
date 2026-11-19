
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.mishwark.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mishwark.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.5.0"))

    // Firebase Authentication
    implementation("com.google.firebase:firebase-auth")

    // Firebase Firestore
    implementation("com.google.firebase:firebase-firestore")

    // Firebase Realtime Database
    implementation("com.google.firebase:firebase-database")

    // AndroidX
    implementation("androidx.core:core:1.15.0")

    // MapLibre Maps
    implementation("org.maplibre.gl:android-sdk:11.11.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
