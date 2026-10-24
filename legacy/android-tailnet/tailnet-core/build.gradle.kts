plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openandroidintelligence.tailnet.core"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Fail-closed connected tests consume the authoritative ALTSNET1 blob
        // provisioned by tools/p0t-device/provision-failclosed-bundle.sh on the
        // attached device. The path is device-local and the blob is non-secret.
        testInstrumentationRunnerArguments["p0tFailClosedBundle"] =
            "/data/local/tmp/openandroidintelligence-p0t/failclosed.bundle"
    }
}

val verifyTsnetAar by tasks.registering {
    group = "verification"
    description = "Verify the tracked tsnet AAR and supply-chain sidecars before any build."
    inputs.files(
        file("libs/tsnet-android-1.98.10.aar"),
        file("libs/tsnet-android-1.98.10.aar.sha256"),
        file("libs/tsnet-android-1.98.10.provenance.json"),
        file("libs/tsnet-android-1.98.10.sbom.json"),
        file("native/tsnetbridge/THIRD_PARTY_NOTICES.md"),
        file("native/tsnetbridge/tsnet-aar.lock.json"),
    )
    outputs.upToDateWhen { false }
    doLast {
        exec {
            workingDir = rootProject.projectDir.parentFile.parentFile
            commandLine(
                "python3",
                "apps/android/tailnet-core/tools/verify-tsnet-aar.py",
                "--lock", "apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json",
                "--aar", "apps/android/tailnet-core/libs/tsnet-android-1.98.10.aar",
                "--provenance", "apps/android/tailnet-core/libs/tsnet-android-1.98.10.provenance.json",
                "--sbom", "apps/android/tailnet-core/libs/tsnet-android-1.98.10.sbom.json",
                "--notices", "apps/android/tailnet-core/native/tsnetbridge/THIRD_PARTY_NOTICES.md",
            )
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyTsnetAar) }
tasks.named("check") { dependsOn(verifyTsnetAar) }

dependencies {
    implementation(project(":core-model"))
    implementation(files("libs/tsnet-android-1.98.10.aar"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
