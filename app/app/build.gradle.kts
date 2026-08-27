import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Load keystore credentials from local.properties if present so that a keystore
// is NOT required to build/sync/assembleDebug. Release stays unsigned when absent.
//
// Add these keys to local.properties (kept out of git) to enable release signing:
//   RELEASE_STORE_FILE=hook-upload.jks   (relative to this repo's app/ dir, or absolute)
//   RELEASE_STORE_PASSWORD=your-store-password
//   RELEASE_KEY_ALIAS=your-key-alias
//   RELEASE_KEY_PASSWORD=your-key-password
val keystoreProperties = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}

// java.util.Properties keeps quotes verbatim, so RELEASE_STORE_PASSWORD="hunter2"
// would be read as the 9-character string including the quotes and the keystore
// would refuse to open. Trim whitespace and one matching pair of surrounding
// quotes so both quoted and unquoted values in local.properties behave the same.
fun secret(name: String): String {
    val raw = keystoreProperties.getProperty(name)?.trim().orEmpty()
    val unquoted = if (raw.length >= 2 &&
        ((raw.startsWith("\"") && raw.endsWith("\"")) || (raw.startsWith("'") && raw.endsWith("'")))
    ) {
        raw.substring(1, raw.length - 1)
    } else {
        raw
    }
    return unquoted
}

val hasReleaseSigning = listOf(
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD",
).all { secret(it).isNotBlank() }

// Client secrets come from the same (gitignored) local.properties file and are
// exposed as BuildConfig constants. Both default to "" so a fresh clone still
// builds and runs — the app degrades gracefully instead of crashing.
//
// Add these keys to local.properties (one per line, no quotes, no spaces
// around the "=") to wire the app up for real:
//   REVENUECAT_PUBLIC_SDK_KEY=goog_XXXXXXXXXXXXXXXXXXXXXXXXXXX
//   REVENUECAT_TEST_SDK_KEY=test_XXXXXXXXXXXXXXXXXXXXXXXXXXX   (optional)
//   APP_API_KEY=the-shared-key-the-Hook-server-expects
//
// The key prefix decides which store the SDK talks to, and the SDK works it out
// on its own — `goog_` drives real Google Play Billing, `test_` drives
// RevenueCat's Test Store (sandbox purchases, no Play account needed). The app
// code never inspects the prefix, so either kind of key just works.
//
// The RevenueCat *public* SDK key is designed to ship inside the app. Never put
// the RevenueCat secret key (or any Supabase key) here — those are server-only.
fun quoteLiteral(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

fun secretLiteral(name: String): String = quoteLiteral(secret(name))

// The build types deliberately read *different* properties for the SDK key.
//
// The SDK refuses to run a `test_` key in a build that isn't debuggable: it
// shows a "Wrong API Key" dialog and closes the app. One key shared by both
// build types therefore means whichever store you last developed against is the
// one that ships — and a Test Store key inside an upload is an app that dies on
// launch for every tester, discovered only after the upload.
//
// So debug may carry its own Test Store key, and release always takes the
// production one.
val revenueCatReleaseKey = secret("REVENUECAT_PUBLIC_SDK_KEY")
val revenueCatDebugKey = secret("REVENUECAT_TEST_SDK_KEY").ifBlank { revenueCatReleaseKey }

// A *blank* key stays fine — a fresh clone with no local.properties has to
// build, and the app disables purchases when there is no key. A wrong-*store*
// key is the one thing that must never ship, so that alone fails the build, and
// only when a release build is what was actually asked for. Debug builds and
// Android Studio's project sync are untouched.
val releaseBuildRequested = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}
if (releaseBuildRequested && revenueCatReleaseKey.startsWith("test_")) {
    error(
        "REVENUECAT_PUBLIC_SDK_KEY in local.properties is a RevenueCat Test " +
            "Store key (test_…). Shipped, the SDK closes the app on launch with " +
            "a \"Wrong API Key\" dialog. Set it to the Google Play key (goog_…) " +
            "from RevenueCat > Project settings > API keys, and move the test " +
            "key to REVENUECAT_TEST_SDK_KEY to keep using it in debug builds."
    )
}

// Every Play upload needs a versionCode higher than the last one that reached
// the console. Override at build time instead of editing this file:
//   ./gradlew bundleRelease -PhookVersionCode=2 -PhookVersionName=1.0.1
val hookVersionCode = (findProperty("hookVersionCode") as String?)?.toInt() ?: 1
val hookVersionName = (findProperty("hookVersionName") as String?) ?: "1.0"

android {
    namespace = "com.tomfricks.hook"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tom7.hook"
        minSdk = 29
        targetSdk = 36
        versionCode = hookVersionCode
        versionName = hookVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Overridden per build type below; this is the fallback for any build
        // type that doesn't set one.
        buildConfigField("String", "REVENUECAT_PUBLIC_SDK_KEY", quoteLiteral(revenueCatReleaseKey))
        buildConfigField("String", "APP_API_KEY", secretLiteral("APP_API_KEY"))
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // Resolved against the root project so a bare file name such as
                // RELEASE_STORE_FILE=hook-upload.jks points at the keystore next
                // to local.properties rather than inside app/.
                storeFile = rootProject.file(secret("RELEASE_STORE_FILE"))
                storePassword = secret("RELEASE_STORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Sandbox purchases with no Play account, when a test key is set.
            buildConfigField(
                "String",
                "REVENUECAT_PUBLIC_SDK_KEY",
                quoteLiteral(revenueCatDebugKey)
            )
        }

        release {
            // Always the production key — guarded above, so a `test_` value
            // fails the build rather than reaching a tester's phone.
            buildConfigField(
                "String",
                "REVENUECAT_PUBLIC_SDK_KEY",
                quoteLiteral(revenueCatReleaseKey)
            )
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the release signing config when the keystore
            // credentials are provided; otherwise leave the build unsigned
            // so configuration/assembleRelease degrades gracefully.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // Without this Kotlin targets whatever JVM the Gradle daemon runs on, which
    // fails the JVM-target consistency check against the Java 11 above.
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.gson)
    implementation(libs.revenuecat.purchases)
    // RevenueCat's dashboard-configured Paywalls + Customer Center (Compose).
    implementation(libs.revenuecat.purchases.ui)
    // Looping muted demo clips on the pre-onboarding welcome slides.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}