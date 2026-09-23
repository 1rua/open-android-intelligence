plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.openandroidintelligence.encrypted.store" }
dependencies {
    implementation(project(":core-model"))
    implementation(project(":conversation-domain"))
    testImplementation("junit:junit:4.13.2")
}
