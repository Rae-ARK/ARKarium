Right now there's no explicit `debug` block in `signingConfigs`/`buildTypes` at all — every machine (your dev box, your friend's, each GitHub Actions runner) falls back to AGP's implicit default, which auto-generates `~/.android/debug.keystore` locally if one doesn't exist, with a different key every time. That's why debug APKs built in different places can't be `adb install -r` over each other (Android refuses an install over a different signature — probably bit you already, given your friend's been testing a sideloaded debug build). It's a one-time fix, same shape as the `release` signing block already in the file, just for `debug` instead.

**1. Generate one keystore, once:**
```bash
keytool -genkey -v -keystore debug.keystore \
  -storepass android -alias androiddebugkey -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000
```
(`android`/`androiddebugkey` are the conventional debug credentials AGP itself uses — nothing to keep secret here, see point 3.)

**2. Add it to `app/build.gradle.kts`**, right alongside the existing `release` block:
```kotlin
signingConfigs {
    create("debug") {
        storeFile = file("$rootDir/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
    if (hasReleaseSigning) {
        create("release") { /* unchanged */ }
    }
}

buildTypes {
    getByName("debug") {
        signingConfig = signingConfigs.getByName("debug")
    }
    getByName("release") { /* unchanged */ }
}
```

**3. Narrow the `.gitignore` exception** — your current `*.keystore` / `*.jks` rules are correctly blanket-blocking the *release* key (its own comment says so: "the actual keystore must never be committed"). A debug keystore is the opposite case: it's never used to sign anything shipped to Play, carries no App Signing trust, and committing it is the standard way projects solve exactly this problem. Carve out just this one file so the release-key rule stays intact for everything else:
```gitignore
*.keystore
*.jks
!debug.keystore
arkarium-release.keystore.b64
arkarium-release.keystore
```

**4. Commit `debug.keystore`** at the repo root (or wherever `$rootDir/debug.keystore` points) so it ships with the clone, same as any other project source.

That's it — no CI change needed, since `build.yml`'s `assembleDebug` now picks up the explicit `signingConfig` automatically. Every debug APK, from your machine, your friend's, or the CI artifact, gets the same signature from here on; a fresh install over an older debug build no longer needs an uninstall first. One caveat: this doesn't retroactively fix *already-installed* debug builds signed with the old per-machine keys — your friend's existing install still needs one uninstall to switch onto the new shared-key lineage, after which every future debug build matches it.