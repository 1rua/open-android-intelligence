plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// 裁决 D6：实现装配模块。桥接 policy-engine 的策略权威与
// notification-collector 的采集运行时，并通过库内 ContentProvider 在
// app 进程启动时向 NotificationControlRegistry 自注册。
android {
    namespace = "com.openandroidintelligence.notification.host"
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
    api(project(":notification-control"))
    implementation(project(":core-model"))
    implementation(project(":policy-engine"))
    implementation(project(":notification-collector"))
    // NotificationOutboxStore（任务书要求的真实 outbox 组合）位于 encrypted-store。
    implementation(project(":encrypted-store"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
