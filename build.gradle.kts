plugins {
    kotlin("android") version "1.9.10" apply false
    // KSP replaces kapt for Room. The leading Kotlin version must match the Kotlin plugin
    // above (1.9.10); bump the two together.
    id("com.google.devtools.ksp") version "1.9.10-1.0.13" apply false
    id("com.android.application") version "8.1.1" apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
}
