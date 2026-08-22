/*
 * The conventional build: Android Studio and CI use this, `build.sh` is the
 * same sources without Gradle. Both compile from src/, res/ and the one
 * AndroidManifest.xml, so there is no second copy of anything.
 *
 * The app has no dependencies at all -- no AndroidX, no libraries. That is a
 * deliberate choice for something that sits in the path of every DNS lookup on
 * the device: the whole of what ships is in this repository.
 */

plugins {
    id("com.android.application")
}

/** The blocklists live at the repository root; the APK bundles a copy. */
val bundleLists by tasks.registering(Copy::class) {
    description = "Copies the repository's lists into the APK's assets."
    from(rootProject.file("../lists")) {
        include("blocklist.txt", "allowlist.txt")
    }
    into(layout.buildDirectory.dir("generated/assets"))
}

/**
 * AGP 8 takes the package name from `namespace` below and rejects the
 * `package` attribute in the manifest -- but aapt2 requires that attribute
 * when `build.sh` runs without Gradle. Rather than keep two manifests, strip
 * the attribute on the way in.
 */
val gradleManifest by tasks.registering(Copy::class) {
    description = "Rewrites the manifest for AGP, which supplies the package itself."
    from("AndroidManifest.xml")
    into(layout.buildDirectory.dir("generated/manifest"))
    filter { line -> if (line.trim().startsWith("package=")) "" else line }
}

android {
    namespace = "io.github.hakansilsupur.adblock"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.hakansilsupur.adblock"
        minSdk = 24
        // Stays at 33 so the VPN service does not need Android 14's
        // foreground-service types, none of which describe a DNS filter.
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets["main"].apply {
        // Plain paths: buildDir is android/build, and these are read at configure time.
        manifest.srcFile("build/generated/manifest/AndroidManifest.xml")
        java.setSrcDirs(listOf("src"))
        res.setSrcDirs(listOf("res"))
        assets.setSrcDirs(listOf("build/generated/assets"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        release {
            // No shrinking: there is nothing to shrink, and an unobfuscated
            // build is easier to audit for something handling your DNS.
            isMinifyEnabled = false
        }
    }

    lint {
        // The blocklist is loaded on a worker thread; lint cannot see that.
        abortOnError = false
    }
}

tasks.named("preBuild") {
    dependsOn(bundleLists, gradleManifest)
}

/** Runs the same JVM tests as `run-tests.sh`, so `gradle check` covers them. */
val coreLogicTest by tasks.registering(Exec::class) {
    description = "Runs the packet and blocklist logic on a plain JVM."
    group = "verification"
    workingDir = projectDir
    commandLine("./run-tests.sh")
}

tasks.named("check") {
    dependsOn(coreLogicTest)
}
