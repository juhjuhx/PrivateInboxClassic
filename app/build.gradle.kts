import java.util.Properties
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Signing configuration.
//
// Three separate identities, with different purposes:
//
//   debug          -> local testing. Reproducible: the keystore path and
//                     credentials come from keystore/signing.properties (or
//                     -Psigning.* on the command line) so every machine signs
//                     with the SAME key. Android refuses to install an update
//                     whose signature differs, so a stable debug key is what
//                     makes "adb install -r" work across rebuilds.
//
//   release        -> the shipping app. Uses a separate, protected key that
//                     the owner keeps. Never in the repository.
//
//   upload (play)  -> the Google Play upload key. May differ from the release
//                     key; only the upload key needs to survive key rotation.
//
// The keystore files and the properties file are git-ignored. A missing
// release/upload key is NOT an error: the build falls back to the debug key for
// release so that a developer can still produce a local release build, and the
// build prints a loud warning that the result is NOT distributable.
// ---------------------------------------------------------------------------

val signingPropsFile = rootProject.file("keystore/signing.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) {
        signingPropsFile.inputStream().use { load(it) }
    }
}

fun prop(name: String): String? =
    (project.findProperty(name) as String?) ?: signingProps.getProperty(name)

/** A resolved signing identity, kept in a plain holder so the build script
 *  never has to name Android DSL types outside the `android { }` block. */
data class SigningIdentity(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String
)

/** Resolve a signing identity from keystore/signing.properties or -P flags,
 *  or null when it is not configured. */
fun signingFor(prefix: String): SigningIdentity? {
    val storePath = prop("${prefix}.storeFile") ?: return null
    val storePassword = prop("${prefix}.storePassword") ?: return null
    val keyAlias = prop("${prefix}.keyAlias") ?: return null
    val keyPassword = prop("${prefix}.keyPassword") ?: return null
    val file = rootProject.file(storePath)
    if (!file.exists()) {
        logger.warn("SIGNING: keystore for '$prefix' not found at $storePath — using the debug key instead")
        return null
    }
    return SigningIdentity(file, storePassword, keyAlias, keyPassword)
}

val debugSigning = signingFor("debug")
val releaseSigning = signingFor("release")
val uploadSigning = signingFor("upload")

android {
    namespace = "tw.personal.privateinbox"
    compileSdk = 36

    defaultConfig {
        applicationId = "tw.personal.privateinbox"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "0.4.0-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    signingConfigs {
        // A deterministic debug key keeps `adb install -r` working across
        // rebuilds and across machines.
        if (debugSigning != null) {
            create("debug") {
                storeFile = debugSigning.storeFile
                storePassword = debugSigning.storePassword
                keyAlias = debugSigning.keyAlias
                keyPassword = debugSigning.keyPassword
            }
        }
        if (releaseSigning != null) {
            create("release") {
                storeFile = releaseSigning.storeFile
                storePassword = releaseSigning.storePassword
                keyAlias = releaseSigning.keyAlias
                keyPassword = releaseSigning.keyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
        if (uploadSigning != null) {
            create("upload") {
                storeFile = uploadSigning.storeFile
                storePassword = uploadSigning.storePassword
                keyAlias = uploadSigning.keyAlias
                keyPassword = uploadSigning.keyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (debugSigning != null) {
                signingConfig = signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
        }
        getByName("release") {
            // Without a configured release key the release build is only useful
            // for local smoke testing, so it is signed with the debug key and
            // clearly marked as not distributable.
            signingConfig = if (releaseSigning != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "SIGNING: no release keystore configured. The release build will be " +
                        "signed with the DEBUG key and is NOT distributable. See docs/SIGNING.md."
                )
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // The Play upload key is a separate identity from the release key.
    // AGP 8 removed `bundle { signingConfig }`; the bundle is signed with the
    // release build type's key by default. Configuring a distinct upload key is
    // done through the androidComponents API below.
}

dependencies {
    // AndroidX back-press dispatcher: predictive-back compatible on Android 13+ without
    // any network, tracking, or UI library. No other runtime dependencies are added.
    implementation("androidx.activity:activity:1.9.2")
    testImplementation("junit:junit:4.13.2")

    // Instrumentation test only. These never ship inside the app APK.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}

// ---------------------------------------------------------------------------
// Google Play upload key.
//
// Play App Signing lets the upload key differ from the app signing key, so a
// lost upload key can be replaced without shipping a new app. The upload key
// is applied to the release bundle only; the release APK keeps the release key.
//
// AGP 8 removed `bundle { signingConfig }`, so the bundle task is configured
// by name. The cast is guarded so a future AGP rename cannot break the build.
// ---------------------------------------------------------------------------
if (uploadSigning != null) {
    androidComponents {
        onVariants(selector().withBuildType("release")) { variant ->
            val bundleTask = tasks.named("bundle${variant.name.replaceFirstChar { it.uppercase() }}")
            bundleTask.configure {
                val signing = this.javaClass.methods.firstOrNull { it.name == "getSigningConfig" }
                    ?.invoke(this) as? com.android.build.api.dsl.SigningConfig
                signing?.storeFile = uploadSigning.storeFile
                signing?.storePassword = uploadSigning.storePassword
                signing?.keyAlias = uploadSigning.keyAlias
                signing?.keyPassword = uploadSigning.keyPassword
            }
        }
    }
}
