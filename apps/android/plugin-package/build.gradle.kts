plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.openandroidintelligence.plugin.pkg" }

dependencies {
    implementation("com.bloxbean.cardano:net-i2p-crypto-eddsa:0.3.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
