plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.openandroidintelligence.platform.kernel" }

dependencies {
    // The kernel orchestrates verified packages, so it reuses the packaging
    // model rather than keeping a second, drifting copy of plugin identity.
    // Exported as `api` because plugin identity appears in the kernel's own
    // signatures and runtimes implementing `PluginRuntime` must see it.
    api(project(":plugin-package"))
    implementation(project(":control-ports"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
