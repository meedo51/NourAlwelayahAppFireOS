import java.time.LocalDate
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release key: checks external Keys folder first (survives Google AI Studio exports),
// then falls back to project-root keystore.properties (not in git).
val keystoreFile = listOf(
    file("C:/Dev/NoorAlwelayahProject/Keys/keystore.properties"),
    rootProject.file("keystore.properties"),
).firstOrNull { it.isFile }

val keystore = Properties().apply {
    if (keystoreFile != null) {
        keystoreFile.inputStream().use { load(it) }
    }
}

val hasReleaseSigning = keystore.containsKey("storeFile") &&
    keystore.containsKey("storePassword") &&
    keystore.containsKey("keyAlias") &&
    keystore.containsKey("keyPassword")

val otaVersionCode = (project.findProperty("otaVersionCode") as String?)?.toIntOrNull() ?: 10011
val otaVersionName = (project.findProperty("otaVersionName") as String?) ?: "1.1.1"
val otaReleaseDate = (project.findProperty("otaReleaseDate") as String?)?.takeIf { it.isNotBlank() }
    ?: LocalDate.now().toString()
val defaultReleaseChangelog =
    "• Resumable HTTP Range OTA updater with startup chunk integrity health check & HMAC-SHA256 telemetry\n" +
    "• Dedicated zero-restart Knowledge Base OTA updater & remote Voice Assistant controls via Nour Alwelayah Connect\n" +
    "• In-app YouTube Search & Kiosk-safe embedded player, About Nour Alwelayah modal, and Fire OS 7/8 Direct Boot hardening"
val otaReleaseChangelog = ((project.findProperty("otaReleaseNotes") as String?)?.takeIf { it.isNotBlank() }
    ?: defaultReleaseChangelog)
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r\n", "\\n")
    .replace("\n", "\\n")

val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.isFile) {
        runCatching { localPropsFile.inputStream().use { load(it) } }
    }
}
val rawOtaTelemetrySecret = (
    (project.findProperty("OTA_TELEMETRY_SECRET") as String?)?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty("OTA_TELEMETRY_SECRET")?.takeIf { it.isNotBlank() }
        ?: System.getenv("OTA_TELEMETRY_SECRET")?.takeIf { it.isNotBlank() }
        ?: ""
)
    .trim()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

val sherpaOnnxAar = file("libs/sherpa-onnx-2.0.2.aar")

android {
    namespace = "com.nouralwelayah.clock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nouralwelayah.clock"
        // minSdk = 22 supports Fire OS 5 (API 22), Fire OS 7 (API 28), Fire OS 8 (API 30), and modern Android 8-15
        minSdk = 22
        targetSdk = 35
        versionCode = otaVersionCode
        versionName = otaVersionName
        buildConfigField("String", "OTA_BASE_URL", "\"https://alwelayah.xus.me/ota\"")
        buildConfigField("String", "RELEASE_DATE", "\"$otaReleaseDate\"")
        buildConfigField("String", "RELEASE_CHANGELOG", "\"$otaReleaseChangelog\"")
        buildConfigField("String", "OTA_TELEMETRY_SECRET", "\"$rawOtaTelemetrySecret\"")
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debugConfig")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true   // java.time on Android < 8 (Fire OS 5)
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
    androidResources {
        noCompress += listOf("onnx", "db", "txt")
    }
}

// The clock remote shows the clock's own web page. It is copied from the firmware project on
// every build when present on the developer machine.
val clockPage = file("C:/Users/salih/Documents/SquareLine_Project/PrayerTimesEsp32P4_10_V1_3/PTclock.html")
val clockPageDir = layout.buildDirectory.dir("generated/clockPage")
val copyClockPage = tasks.register<Copy>("copyClockPage") {
    onlyIf { clockPage.isFile }
    from(clockPage)
    into(clockPageDir)
}
android.sourceSets["main"].assets.srcDir(clockPageDir)
if (!sherpaOnnxAar.isFile) {
    android.sourceSets["main"].java.srcDir("src/FallbackSherpa/java")
}
tasks.named("preBuild") { dependsOn(copyClockPage) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.zxing.core)          // QR code of the tablet's own Wi-Fi (no-router mode)
    implementation(libs.requery.sqlite.android) // Standalone SQLite with FTS5 enabled on all Android ROMs
    implementation(libs.okhttp)              // Zero-key YouTube Innertube HTTP client
    implementation(libs.androidx.work.runtime.ktx) // WorkManager for OTA telemetry & periodic background jobs
    if (sherpaOnnxAar.isFile) {
        implementation(files("libs/sherpa-onnx-2.0.2.aar")) // Official sherpa-onnx native Android AAR
    }
    debugImplementation(libs.androidx.ui.tooling)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
}