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
        // v0.4.3: reliability hardening.
        //
        // The version code is monotonic: 8 was v0.4.2, so this is 9. The
        // version is asserted by scripts/audit-release.sh, which also checks
        // that the release bundle is not signed with the debug key.
        versionCode = 10
        versionName = "0.4.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ---------------------------------------------------------------------
    // The devtest variant: an ISOLATED application id for the destructive
    // instrumentation tests.
    //
    // The problem this solves: the instrumentation tests open the TARGET
    // application's database and clear it. The target package is
    // `tw.personal.privateinbox` — the SAME package the user installs — so
    // running the suite on a phone that has the real app would DELETE THE
    // USER'S SAVED MESSAGES.
    //
    // The devtest variant has the application id
    // `tw.personal.privateinbox.devtest`. Android gives each application id its
    // own data directory, so the test variant has its own `inbox.db` and
    // physically cannot reach the real one.
    //
    // The production variant keeps the real id, and
    // DestructiveOperationGuard refuses to destroy data when the target
    // package is the production one. The guard is by PACKAGE ID, not by build
    // type, so a release build installed on a phone is protected too.
    //
    // The permission names are suffixed per variant so the two variants
    // cannot impersonate each other's providers.
    // ---------------------------------------------------------------------
    flavorDimensions += "isolation"
    productFlavors {
        create("prod") {
            dimension = "isolation"
            applicationId = "tw.personal.privateinbox"
            // The suffix distinguishes the permission from the devtest one.
            manifestPlaceholders["permissionSuffix"] = ""
        }
        create("devtest") {
            dimension = "isolation"
            applicationId = "tw.personal.privateinbox.devtest"
            manifestPlaceholders["permissionSuffix"] = ".devtest"
            versionNameSuffix = "-devtest"
        }
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
        //
        // AGP pre-creates the "debug" and "release" signing configs, so the
        // existing ones are MODIFIED with named() rather than re-created:
        // create("debug") fails with "a SigningConfig with that name already
        // exists".
        if (debugSigning != null) {
            named("debug") {
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
            // A release build must NEVER carry the debug signature.
            //
            // The previous behaviour fell back to the debug signing config when
            // no release key was configured. That produced an APK named
            // "release" that was in fact signed with the Android Debug
            // certificate — indistinguishable from a test build, easy to
            // distribute by accident, and impossible to upgrade later.
            //
            // Now: with a configured release key the release build is signed
            // with it. Without one, the build produces an UNSIGNED release
            // artifact and fails loudly, so a mislabelled binary can never be
            // produced. A local smoke test can still be done with the debug
            // build.
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "SIGNING: no release keystore configured. The release build will be " +
                        "UNSIGNED and is NOT distributable. See docs/SIGNING.md."
                )
                // Explicitly clear the signing config so no debug identity leaks
                // into a release artifact.
                signingConfig = null
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
    //
    // The release APK sets `signingConfig = null` when no release key exists,
    // so it can never carry the debug identity. The BUNDLE had no such
    // protection: with no upload key configured, AGP signed the release
    // bundle with the DEBUG key, so a file named "...-v0.4.1.aab" was in fact
    // a debug-signed artifact. The bundle's signing config is therefore
    // cleared as well when no release key is available.
}

// ---------------------------------------------------------------------------
// Release bundle signing.
//
// The release APK already refuses to fall back to the debug key. The bundle
// did not: AGP signs a release bundle with the release build type's signing
// config, and when that config is null AGP falls back to the debug key. A
// release AAB was therefore produced carrying the DEBUG certificate, which
// looked like a distributable artifact but was not.
//
// When no release or upload keystore is configured, the bundle is left
// UNSIGNED and the build says so loudly. It must never carry the debug
// identity under a release name.
// ---------------------------------------------------------------------------
if (releaseSigning == null && uploadSigning == null) {
    androidComponents {
        onVariants(selector().withBuildType("release")) { variant ->
            val bundleTaskName = "bundle${variant.name.replaceFirstChar { it.uppercase() }}"
            // The bundle task is created lazily by AGP, so it is matched by
            // name at EXECUTION time rather than looked up at configuration
            // time (tasks.named() fails because the task does not exist yet).
            tasks.matching { it.name == bundleTaskName }.configureEach {
                // Clear the signing config so the debug identity cannot leak
                // into a release artifact.
                val signing = this.javaClass.methods.firstOrNull { it.name == "getSigningConfig" }
                    ?.invoke(this) as? com.android.build.api.dsl.SigningConfig
                if (signing != null) {
                    signing.storeFile = null
                    signing.storePassword = null
                    signing.keyAlias = null
                    signing.keyPassword = null
                    logger.warn(
                        "SIGNING: no release or upload keystore configured. The release BUNDLE " +
                            "will be UNSIGNED and is NOT distributable. It will NOT be signed " +
                            "with the debug key. See docs/SIGNING.md."
                    )
                }
            }
        }
    }
}

dependencies {
    // AndroidX back-press dispatcher: predictive-back compatible on Android 13+ without
    // any network, tracking, or UI library. No other runtime dependencies are added.
    implementation("androidx.activity:activity:1.9.2")
    testImplementation("junit:junit:4.13.2")

    // Instrumentation test only. These never ship inside the app APK.
    //
    // The destructive instrumentation tests run against the devtest variant,
    // which has its own application id and therefore its own data directory.
    // That is what makes `db.clear()` in a test SAFE: it cannot reach the
    // production app's database.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}

// ---------------------------------------------------------------------------
// DHC connector interoperability.
//
// The proof that the Android exporter and the Python importer agree is a
// cross-platform round trip: Android produces a bundle, a normal desktop tool
// decrypts it. The instrumentation run uninstalls the app (and its external
// files directory) when it finishes, so the bundle has to be pulled WHILE the
// app is still installed.
//
// This task therefore runs the exporter test, pulls the bundle from the
// device immediately afterwards, and hands it to the Python importer.
// ---------------------------------------------------------------------------
tasks.register("dhcInteropCheck") {
    group = "verification"
    description = "Android export -> Python import round trip on a real device."
    dependsOn("connectedDebugAndroidTest")

    doLast {
        val adb = "${android.sdkDirectory}/platform-tools/adb"
        val outDir = layout.buildDirectory.dir("outputs").get().asFile
        outDir.mkdirs()
        val bundle = File(outDir, "dhc-export-test.picbox")
        val devicePath = "/sdcard/Android/data/tw.personal.privateinbox/files/dhc-export-test.picbox"

        if (bundle.exists()) bundle.delete()
        val pulled = providers.exec {
            commandLine(adb, "pull", devicePath, bundle.absolutePath)
            isIgnoreExitValue = true
        }.result.get().exitValue

        if (pulled != 0 || !bundle.exists()) {
            // The app may already be uninstalled by the instrumentation run.
            // Fall back to the device path if it is still there.
            logger.lifecycle(
                "dhcInteropCheck: the bundle could not be pulled from the device " +
                    "(the app is uninstalled after the instrumentation run). " +
                    "Run the interop script manually while the app is installed."
            )
            throw GradleException(
                "dhcInteropCheck: no bundle available on the device. " +
                    "The instrumentation run removes the app, so the bundle must be " +
                    "pulled during the run."
            )
        }

        val result = providers.exec {
            commandLine(
                "bash",
                rootProject.file("integrations/dhc/tests/host_interop.sh"),
                bundle.absolutePath
            )
            isIgnoreExitValue = true
        }.result.get().exitValue
        if (result != 0) {
            throw GradleException("dhcInteropCheck: the host-side import check failed")
        }
    }
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
