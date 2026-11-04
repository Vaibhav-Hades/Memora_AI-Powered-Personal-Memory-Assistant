plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.memora.app"
    compileSdk = 37 // required by the Compose 2026.08 libraries; targetSdk stays 36

    defaultConfig {
        applicationId = "com.memora.app"
        minSdk = 28 // Android 9+: ImageDecoder handles HEIC and EXIF rotation when preparing uploads
        targetSdk = 36
        versionCode = 1
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK can be installed for testing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("androidx.exifinterface:exifinterface:1.4.1") // capture time and location stored inside photos
    // On-device AI: LiteRT-LM runs .litertlm models in-process (no server). ML Kit's bundled Latin text recognizer
    // reads photos and scanned PDFs offline (the model ships inside the app).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0") // background sync that survives restarts and respects battery/network

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20250517")
}
