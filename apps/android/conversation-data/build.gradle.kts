plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openandroidintelligence.conversation.data"
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
}

dependencies {
    implementation(project(":conversation-domain"))
    implementation(project(":gateway-client"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
}

// The Hermes interop test starts the real Gateway from the plugin checkout and
// drives it over HTTP. The variables are forwarded explicitly rather than left
// to ambient inheritance: a reused Gradle daemon can carry the environment of
// an earlier run, which would make the test fail for a reason unrelated to it.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    listOf("HERMES_PLUGIN_ROOT", "OPEN_ANDROID_GATEWAY_CONTRACT_ROOT").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
    // Without this a failing test reaches the CI log as a bare FAILED with no
    // message, which makes an integration failure indistinguishable from a
    // broken fixture or a missing checkout.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        // The Gateway fixture diagnostics are written to the test streams;
        // without this they are captured but never shown.
        showStandardStreams = true
    }
}
