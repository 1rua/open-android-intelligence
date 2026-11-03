plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openandroidintelligence.screen"
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
    api(project(":control-ports"))
    // 无障碍按需截图的系统壳实现 capability-ports 里的 AccessibilityScreenshotRuntime。
    // 依赖方向是 screen-control → capability-ports，反向不存在，因此不会形成循环。
    implementation(project(":capability-ports"))
    implementation(project(":gateway-client"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
