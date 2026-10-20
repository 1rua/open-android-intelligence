plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openandroidintelligence.gateway.client"
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    testOptions.unitTests.all {
        val testHome = rootProject.layout.projectDirectory.dir(".gradle/robolectric-home").asFile
        it.systemProperty("user.home", testHome.absolutePath)
        it.doFirst { testHome.mkdirs() }
    }
}

dependencies {
    implementation("com.bloxbean.cardano:net-i2p-crypto-eddsa:0.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
