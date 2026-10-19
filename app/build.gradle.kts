plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tw.personal.privateinbox"
    compileSdk = 36

    defaultConfig {
        applicationId = "tw.personal.privateinbox"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-source-candidate"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // AndroidX back-press dispatcher: predictive-back compatible on Android 13+ without
    // any network, tracking, or UI library. No other runtime dependencies are added.
    implementation("androidx.activity:activity:1.9.2")
    testImplementation("junit:junit:4.13.2")
}
