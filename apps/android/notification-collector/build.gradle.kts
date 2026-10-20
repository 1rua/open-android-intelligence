plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openandroidintelligence.notifications"
    testOptions.unitTests.apply {
        isIncludeAndroidResources = true
        all {
            // 与 conversation-ui/plugin-ui 相同的 Robolectric 缓存位置，离线环境复用 android-all。
            val testHome = rootProject.layout.projectDirectory.dir(".gradle/robolectric-home").asFile
            it.systemProperty("user.home", testHome.absolutePath)
            it.doFirst { testHome.mkdirs() }
        }
    }
}
dependencies {
    implementation(project(":core-model"))
    implementation(project(":policy-engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(project(":encrypted-store"))
    testImplementation("junit:junit:4.13.2")
    // Manifest 合并证据测试：Robolectric 读合并后的 Manifest 验证监听服务声明。
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
