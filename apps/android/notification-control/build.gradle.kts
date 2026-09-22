plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// 裁决 D6：纯接口模块。只有 port 契约与 deny-first 默认实现，
// 没有采集、没有权限请求、没有持久化；Android 侧仅剩 android.jar 的
// Context 类型（NotificationBindingPort.openSystemListenerSettings 需要）。
android { namespace = "com.openandroidintelligence.notification.control" }

dependencies {
    // 快照复用 core-model 的闭集枚举（字段访问 / 投递模式），避免第二套平行定义。
    api(project(":core-model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
