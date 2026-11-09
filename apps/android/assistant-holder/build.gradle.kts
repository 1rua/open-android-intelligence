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

tasks.withType<Test>().configureEach {
    // Compose 的 idling resource 与 Android 组件注册表都是进程全局状态：同一 JVM 里
    // 先跑过的测试类会把未释放的 idler 留给后面的类，后者在 `waitForIdle` 里永远等不到
    // 静止（表现为 AppNotIdleException 60 秒超时）。App 模块用同样的理由配了 forkEvery=1，
    // 本模块既有 Compose 行为测试、也有真实 Activity/VoiceInteractionSession 生命周期
    // 测试，隔离要求相同。只隔离测试 JVM，不关闭 idling 检查。
    forkEvery = 1
    maxHeapSize = providers.gradleProperty("oai.testMaxHeap").orNull ?: "1g"
}

dependencies {
    implementation(project(":core-model"))
    // 复用同一套设计令牌与助理表面：助理进程只组合 UI，不初始化 Gateway。
    implementation(project(":conversation-ui"))
    // 时间线渲染直接读取领域类型（TimelineEntry / TimelineAttachment / ApprovalChoice）：
    // conversation-ui 对 conversation-domain 是 implementation，不会传递到本模块。
    implementation(project(":conversation-domain"))
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
    // 录音状态机用虚拟时间验证计时、防重复提交与会话切换，不依赖真实等待。
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // 助理界面的 Compose 行为回归（输入栏、录音态、无障碍标签）。
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
}
