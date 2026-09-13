plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.openandroidintelligence.mobile"
    defaultConfig {
        applicationId = "com.openandroidintelligence.mobile"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_RUNTIME_PLUGINS", "true")
            buildConfigField("boolean", "ALLOW_DEVELOPER_TRUST_MODE", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_RUNTIME_PLUGINS", "false")
            buildConfigField("boolean", "ALLOW_DEVELOPER_TRUST_MODE", "false")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":gateway-client"))
    implementation(project(":platform-kernel"))
    implementation(project(":plugin-package"))
    implementation(project(":plugin-runtime-wasm"))
    implementation(project(":plugin-ui"))
    implementation(project(":companion-bridge"))
    implementation(project(":encrypted-store"))
    implementation(project(":conversation-domain"))
    implementation(project(":conversation-data"))
    implementation(project(":conversation-ui"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
