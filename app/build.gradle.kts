plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "org.offlinemesh.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.offlinemesh.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 23
        versionName = "0.7.12-dev"
    }

    buildTypes {
        release {
            // Enabled in Pass 18 — was off since the project's initial commit, which is the real
            // answer to "why did the APK bloat": nothing was ever shrunk. See CHANGELOG for the
            // measured before/after and proguard-rules.pro for the (deliberately small) keep list.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        // DiagnosticsLog / HomeScreen's export row gate on BuildConfig.DEBUG so the on-device event
        // log exists in debug builds only — AGP 8 no longer generates BuildConfig unless asked.
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true // needed for Robolectric-backed tests
            isReturnDefaultValues = true
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    // Two rule tweaks (Compose's PascalCase convention for @Composable functions; the guard-clause
    // early-return shape this codebase's BLE layer uses deliberately) that used to be fought
    // function-by-function with ~15 inline @Suppress annotations — see the config file's own
    // comments for why each exists. Everything else is still the plain default ruleset.
    config.setFrom(files("config/detekt/detekt.yml"))
    // Baselined rather than tuned rule-by-rule: passes 1-17 predate this rig, so a baseline lets
    // detekt gate *new* issues going forward without a one-time cleanup sweep blocking adoption.
    // Regenerate via `./gradlew detektBaseline` after a deliberate cleanup, not to silence a new one.
    baseline = file("detekt-baseline.xml")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Declared explicitly (Phase 4, sender identity) rather than relying on it arriving
    // transitively via security-crypto above — RelayEngine/RelayResponder now call Tink's Ed25519
    // classes directly, so the version actually in use should be pinned by us, not whatever
    // security-crypto happens to pull in. Only the `subtle.Ed25519Sign`/`Ed25519Verify` classes are
    // used (see crypto/SenderIdentity.kt) — deliberately not Tink's KeysetHandle/registry API,
    // which needs its own key-management/serialization story this app has no use for.
    implementation("com.google.crypto.tink:tink-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // zxing:core does both generation AND, as of the in-app QR scanner, decoding — one QR library
    // for both jobs rather than adding a second one. Not zxing-android-embedded's full scanning
    // library; the CameraX pieces below supply the camera preview/frame pipeline, zxing:core just
    // reads a QR out of a frame we hand it (see ui/QrScannerScreen.kt).
    implementation("com.google.zxing:core:3.5.3")

    // Camera preview + frame analysis for the in-app QR scanner (Add group > Join > camera icon).
    // Opt-in only: CAMERA permission is requested on first tap of that icon, not at launch, and
    // nothing else in the app touches the camera — see AddGroupScreen.kt / QrScannerScreen.kt.
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Tier 1 of the test rig: pure-JVM unit tests, no device/emulator needed — crypto, wire codec,
    // hop-count math, radar bearing/distance math, connection-attempt and delivery-dedup state
    // machines. `./gradlew test` runs all of it in seconds; see TESTING.md.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Robolectric: only the couple of tests that need a real android.location.Location.distanceBetween
    // (the radar bearing/distance math) pull this in — everything else above is plain JVM/JUnit.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")

    // LeakCanary: debug-only, auto-installs itself (no code wiring needed) and watches real manual
    // testing sessions for leaked Activities/Views/objects — a runtime complement to Lint's static
    // leak-pattern checks, not a replacement for them.
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}
