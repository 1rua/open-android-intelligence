plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.openandroidintelligence.assistant"
    buildFeatures {
        compose = true
    }
    testOptions.unitTests.apply {
        isIncludeAndroidResources = true
        all {
            val testHome = rootProject.layout.projectDirectory.dir(".gradle/robolectric-home").asFile
            it.systemProperty("user.home", testHome.absolutePath)
            it.doFirst { testHome.mkdirs() }
        }
    }
}

dependencies {
    implementation(project(":core-model"))
    // 复用同一套设计令牌与助理表面：助理进程只组合 UI，不初始化 Gateway。
    implementation(project(":conversation-ui"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")
    implementation("androidx.savedstate:savedstate:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
