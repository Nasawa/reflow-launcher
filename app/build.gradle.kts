// AGP 9 has built-in Kotlin support, so org.jetbrains.kotlin.android is not applied.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// #255: versionName / versionCode from the git tag. See scripts/version.py and AGENTS.md.
val appVersionParsed = providers.exec {
    commandLine(
        "python3",
        rootProject.layout.projectDirectory.file("scripts/version.py").asFile.absolutePath,
        "--from-git",
    )
    workingDir = rootProject.layout.projectDirectory.asFile
}.standardOutput.asText.get().trim().lineSequence().toList()
val appVersionName = appVersionParsed.first { it.startsWith("VERSION_NAME=") }.substringAfter("=")
val appVersionCode = appVersionParsed.first { it.startsWith("VERSION_CODE=") }.substringAfter("=").toInt()

// #254: the release signing key comes only from these four env vars (set by .github/workflows/release.yml from the
// `release` environment secrets). All four set: assembleRelease is signed with it (v2 + v3). None set: the release
// APK is unsigned (app-release-unsigned.apk), so local builds and ordinary CI keep working. Some but not all set:
// the build fails, so a half-configured run can't quietly ship an unsigned APK. Never falls back to the debug key.
val releaseSigningEnv = listOf("RELEASE_KEYSTORE_PATH", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
val releaseSigningValues = releaseSigningEnv.associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotEmpty) }
val releaseSigningMissing = releaseSigningValues.filterValues { it == null }.keys
val releaseSigningEnabled = releaseSigningMissing.isEmpty()
if (!releaseSigningEnabled && releaseSigningMissing.size != releaseSigningEnv.size) {
    throw GradleException("Release signing is half-configured: missing ${releaseSigningMissing.joinToString()} (#254). Set all four or none.")
}

android {
    namespace = "dev.nasawa.configlauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.anigeek.reflowlauncher"
        minSdk = 29 // ARCHITECTURE.md §18 ruling (Archimedes, 2026-10-18)
        targetSdk = 35
        // #255: versionName and versionCode come from the git tag (scripts/version.py).
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // #73: one stable, committed DEBUG-ONLY key, so every dev build (any machine or CI runner) installs over the last.
    // Publicly known passwords; it proves nothing about who built the APK. Never use it for release: the release
    // build type uses only the env-var release key below (#254), and a real release key must never be committed (see README).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // #254: only when all four RELEASE_* env vars are set (see the top of this file and README "Release builds").
        if (releaseSigningEnabled) {
            create("release") {
                storeFile = file(releaseSigningValues.getValue("RELEASE_KEYSTORE_PATH")!!)
                storePassword = releaseSigningValues.getValue("RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("RELEASE_KEY_PASSWORD")
                storeType = "pkcs12"
                // minSdk 29: v1 (JAR) is only for Android < 7. v3 allows a later key rotation (Android 9+).
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    // §20.1 templates/config-folder/AGENTS.md → asset "config-folder/AGENTS.md"; §20.2 schema/launcher-config.v1.schema.json → asset (with schema/README.md and examples/, tiny).
    sourceSets { getByName("main") { assets.srcDir("../templates"); assets.srcDir("../schema") } }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // #257: debug keeps consent and the live-card target so S3 work can continue. 1.1 removes
            // this flag when #140 and #141 land.
            buildConfigField("boolean", "CARDS_LIVE", "true")
        }
        release {
            // R8 for release builds (shrink + optimize); matters most on the older wall-display devices.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "CARDS_LIVE", "false")
            // #254: the private release key when its env vars are set; otherwise unsigned. Never the debug key.
            signingConfig = if (releaseSigningEnabled) signingConfigs.getByName("release") else null
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Full lint runs in CI; any error fails the build. A missed SDK gate must fail too.
        error += "NewApi"
        abortOnError = true
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        // #264 review: VersionMenuTest checks the ⋮ `Version …` line against the name version.py stamped.
        unitTests.all { it.systemProperty("reflow.versionName", appVersionName) }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.window)
    testImplementation(libs.junit)
    testImplementation(libs.networknt.json.schema)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.window.testing)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.window.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}


// Schema-agreement tests read the repo's schema/ folder.
tasks.withType<Test>().configureEach {
    systemProperty("repoRoot", rootDir.absolutePath)
    inputs.dir(rootDir.resolve("schema"))
}
