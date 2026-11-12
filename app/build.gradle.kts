plugins {
    id("com.android.application")
    kotlin("android")
    id("com.google.devtools.ksp")
}

// Room's exported schema JSON (one file per database version) goes to app/schemas and is
// committed. MigrationTest replays real upgrades from those files, and a schema change
// without a matching new file shows up in review as a diff. See data-safety.md F1/F2.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.arkarium.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arkarium.app"
        minSdk = 24
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Single source of truth for the app's version, anywhere it needs to be shown.
        // Everything in-app (Settings' About section, both crash screens, the
        // persisted last-crash trace) reads this back via the generated
        // BuildConfig.VERSION_NAME/VERSION_CODE rather than a hardcoded string, so
        // there's exactly one place to bump per release. Static docs (README.md,
        // app/README.md) can't read a Gradle value at doc-render time and are kept in
        // sync by hand - bump those alongside this when you bump here.
        versionCode = 7
        versionName = "0.84"
    }

    buildFeatures {
        compose = true
        // Needed to generate BuildConfig.VERSION_NAME/VERSION_CODE from the values
        // above - AGP 8+ no longer turns this on implicitly just because compose is.
        buildConfig = true
    }

    // Release signing is sourced from env vars so the keystore itself never has to
    // live in the repo. CI (see .github/workflows/release.yml) decodes a base64
    // secret to a keystore file and exports these four vars before invoking
    // assembleRelease. Locally, export the same four vars yourself if you want a
    // signed release build; if they're absent, `release` silently falls back to
    // AGP's default (unsigned) release build type instead of failing the build.
    val releaseStoreFile = System.getenv("ARKARIUM_RELEASE_STORE_FILE")
    val releaseStorePassword = System.getenv("ARKARIUM_RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = System.getenv("ARKARIUM_RELEASE_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("ARKARIUM_RELEASE_KEY_PASSWORD")
    val hasReleaseSigning = listOf(
        releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
    ).all { !it.isNullOrBlank() }

    signingConfigs {
        getByName("debug") {
            storeFile = file("$rootDir/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Explicit rather than relying on AGP's default, since this APK is
            // handed directly to sideloaders rather than going through Play's
            // App Bundle pipeline - keep the build predictable and skip R8/
            // resource-shrinking risk (stripped Room/Compose reflection, etc.)
            // until proguard rules are actually written and tested.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }


    // MigrationTestHelper reads the exported schema JSON from the androidTest APK's
    // assets, so ship app/schemas there.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Sideloaders download a single APK straight from the GitHub Actions
    // artifact (see .github/workflows/release.yml) rather than through a Play
    // Store listing, so give it a name that identifies itself instead of the
    // generic "app-release.apk" - handy once more than one release is floating
    // around in someone's Downloads folder. `com.android.build.gradle.api.
    // ApkVariantOutput` is the officially documented public API for this on
    // AGP 8.x (see Android Gradle recipes); the newer androidComponents/
    // Variant API's outputFileName doesn't resolve reliably until AGP 9.
    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.api.ApkVariantOutput
            output.outputFileName = "ARKarium-${variant.versionName}-${variant.name}.apk"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.0")

    // Icons.Default.* used throughout the UI (ArrowBack, Sort, FilterList, Brightness4/7,
    // KeyboardArrowUp/Down, Star, etc.). Declared explicitly rather than relying on
    // material3 to pull material-icons-core in transitively, since that transitive
    // dependency was removed in newer material3 releases and some of the icons used
    // here (Sort, FilterList, Brightness4/7, KeyboardArrowUp/Down) live in the
    // extended icon set, not the core one.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.12.0")

    // MediaStyle for the read-aloud notification's transport buttons (previous / play-pause /
    // next / stop) - androidx.media.app.NotificationCompat.MediaStyle and
    // MediaSessionCompat.Token live here, not in core. See TtsPlaybackService.buildNotification
    // and docs/arkarium/implementation/tts-playback-controls.md, rung 4.
    implementation("androidx.media:media:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    // collectAsStateWithLifecycle() for Compose - lifecycle-aware Flow collection used by
    // rememberChapterTts() (ui/Tts.kt) in place of plain collectAsState() so collection
    // stops while the screen is backgrounded (engineering-review.md F14). Not pulled in
    // transitively by lifecycle-runtime-ktx above.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    // ViewModel + viewModelScope, used by SettingsViewModel (Stage 2.2 of Phase 2 -
    // see docs/arkarium/foundational/design-notes.md) and the ViewModels planned for the rest of
    // that phase. Not pulled in transitively by lifecycle-runtime-ktx above, which
    // only covers Lifecycle/LifecycleOwner, not ViewModel itself.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    // WorkManager - backs the periodic background "new chapter" check (see
    // docs/arkarium/foundational/architecture.md, Sync: entry points and data/NewChapterCheckWorker.kt). ktx artifact
    // for the CoroutineWorker base class and suspend-friendly WorkManager APIs, rather
    // than plain work-runtime + a manual ListenableFuture bridge.
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Coil for images. coil-compose (not the bare coil artifact) is needed for
    // AsyncImage, used to render remote cover thumbnails in the metadata match picker.
    implementation("io.coil-kt:coil-compose:2.4.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // DocumentFile
    implementation("androidx.documentfile:documentfile:1.0.1")

    // AndroidX Webkit - used by WebViewScreen (Settings > About Me) to render
    // the author's site in-app via WebViewClientCompat instead of bouncing
    // out to a browser.
    implementation("androidx.webkit:webkit:1.10.0")

    // Unit test infra (JVM, src/test - no device/emulator needed). Kept deliberately
    // minimal: JUnit4 for assertions/runner, kotlinx-coroutines-test for suspend-fun
    // and Flow testing. See docs/arkarium/foundational/design-notes.md for the testing strategy
    // this is meant to grow into as more logic moves out of MainActivity and into
    // plain-Kotlin/ViewModel classes that don't need Robolectric or instrumentation.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // LibraryViewModel (and MetadataViewModel) take concrete AppDatabase/ScannerImpl
    // rather than an interface - see design-notes.md, "ViewModels take interfaces
    // only where a seam exists". A function on one of those ViewModels can still be
    // unit-tested if it never actually calls into db/scanner (LibraryViewModelTest's
    // reportScanSetupError, rung 4.2 of test-coverage.md), but constructing the
    // ViewModel at all still needs *some* instance of each - and ScannerImpl's own
    // constructor needs a real android.content.Context, which this module has no way
    // to hand-build without Robolectric (deliberately not adopted, see design-notes.md
    // Testing strategy). Mockito's mock(...) sidesteps that: it generates a throwaway
    // instance of the class without running its constructor or any of its methods, no
    // Context required. This is a mocking library in name only here - no test stubs a
    // return value or verifies a call *happened*; every test that uses it instead
    // calls verifyNoInteractions to assert db/scanner were never touched at all.
    testImplementation("org.mockito:mockito-core:5.11.0")

    // Instrumented (device/emulator) tests - src/androidTest. Only the Room migration
    // test lives there today; it needs a real SQLite, which the JVM tests above don't have.
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}