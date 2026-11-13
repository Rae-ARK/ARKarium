plugins {
    // D4 (docs/proposal/performance.md F12, docs/proposal/engineering-review.md D4):
    // step 1 of the staged Kotlin/Compose/Navigation upgrade. Kotlin 1.9.10 -> 2.0.21
    // plus the Compose compiler Gradle plugin below (the Compose compiler moved out of
    // AGP's composeOptions.kotlinCompilerExtensionVersion and into the Kotlin repo as
    // its own plugin, versioned alongside Kotlin itself, starting with Kotlin 2.0 - see
    // app/build.gradle.kts). This unlocks strong-skipping-by-default (F14's remaining
    // piece) and is a prerequisite for the Compose BOM bump (step 2) and the Navigation
    // 2.8+ bump for predictive back (step 3, F17) - see performance.md F12 for the full
    // staged plan and why it's staged rather than one combined bump.
    kotlin("android") version "2.0.21" apply false
    // The Compose compiler Gradle plugin. Its version always matches the Kotlin version
    // above - bump the two together, the same rule that applied to KSP's leading version
    // before this.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // KSP replaces kapt for Room. The leading Kotlin version must match the Kotlin plugin
    // above (2.0.21); bump the two together.
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
    id("com.android.application") version "8.1.1" apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
}
