plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.openandroidintelligence.sync" }

dependencies {
    implementation(project(":core-model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
