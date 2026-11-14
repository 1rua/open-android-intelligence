import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val remoteAppVersion = providers.fileContents(rootProject.layout.projectDirectory.file("app/version.properties")).asText.map { text ->
    Properties().apply { load(text.reader()) }.getProperty("versionName")
}

android {
    buildFeatures { buildConfig = true }
    defaultConfig { buildConfigField("String", "OAI_REMOTE_CLIENT_VERSION", "\"${remoteAppVersion.get()}+oai.android\"") }
    namespace = "com.openandroidintelligence.gateway.client"
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    testOptions.unitTests.all {
        val testHome = rootProject.layout.projectDirectory.dir(".gradle/robolectric-home").asFile
        it.systemProperty("user.home", testHome.absolutePath)
        it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        it.testLogging.showCauses = true
        it.testLogging.showStackTraces = true
        it.doFirst { testHome.mkdirs() }
    }
}

dependencies {
    implementation(project(":control-ports"))
    implementation("com.bloxbean.cardano:net-i2p-crypto-eddsa:0.3.1")
    implementation("org.signal.forks:noise-java:0.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
