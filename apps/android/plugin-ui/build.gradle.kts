plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.openandroidintelligence.plugin.ui"
    buildFeatures {
        compose = true
    }
    testOptions.unitTests.apply {
        isIncludeAndroidResources = true
        all {
            // 与 conversation-ui 相同的 Robolectric 缓存位置，离线环境下复用已下载的 android-all。
            val testHome = rootProject.layout.projectDirectory.dir(".gradle/robolectric-home").asFile
            it.systemProperty("user.home", testHome.absolutePath)
            it.doFirst { testHome.mkdirs() }
        }
    }
}

dependencies {
    // 只读复用 conversation-ui 的 SettingsComponents 与 Dimensions/AppRadius 设计令牌。
    implementation(project(":conversation-ui"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-manifest")
}
