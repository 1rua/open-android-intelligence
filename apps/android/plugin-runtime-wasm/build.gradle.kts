plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openandroidintelligence.plugin.runtime.wasm"
    defaultConfig {
        // Chicory is a pure Java interpreter: no JNI and no bundled `.so`, so
        // the plugin runtime stays inside the host process sandbox.
        consumerProguardFiles.clear()
    }
}

// The fixture builder is plain byte assembly with no Android dependency, so the
// instrumented tests reuse the unit-test source rather than keeping a second
// copy that could drift out of sync.
android {
    sourceSets {
        getByName("test").kotlin.srcDir("src/jvmTest/kotlin")
        getByName("androidTest").kotlin.srcDirs("src/test/kotlin")
    }
}

tasks.withType<Test>().configureEach {
    inputs.files(rootProject.projectDir.resolve("../../plugins/target/wasm32-unknown-unknown/release").listFiles()
        .orEmpty().filter { it.extension == "wasm" }).withPropertyName("compiledReferenceWasm")
}

dependencies {
    implementation(project(":platform-kernel"))

    implementation("com.dylibso.chicory:runtime:1.7.5")
    implementation("com.dylibso.chicory:wasm:1.7.5")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
