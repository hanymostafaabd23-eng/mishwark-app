
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

val keystorePath = System.getenv("MISHWARK_KEYSTORE_PATH")
val keystorePassword = System.getenv("MISHWARK_STORE_PASSWORD")
val keyPasswordValue = System.getenv("MISHWARK_KEY_PASSWORD")
val keyAliasValue = System.getenv("MISHWARK_KEY_ALIAS")

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

    signingConfigs {
        create("mishwark") {
            if (
                !keystorePath.isNullOrBlank() &&
                !keystorePassword.isNullOrBlank() &&
                !keyAliasValue.isNullOrBlank()
            ) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue ?: keystorePassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (
                !keystorePath.isNullOrBlank() &&
                !keystorePassword.isNullOrBlank() &&
                !keyAliasValue.isNullOrBlank()
            ) {
                signingConfig = signingConfigs.getByName("mishwark")
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
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.5.0"))
    implementation("com.google.firebase:firebase-appcheck-playintegrity")

    // Firebase
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-database")

    // AndroidX
    implementation("androidx.core:core:1.15.0")

    // Google Credential Manager
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // MapLibre
    implementation("org.maplibre.gl:android-sdk:11.11.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
