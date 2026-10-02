import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 固定调试签名（app/keystore/debug.keystore，见同目录 README.md）在根工程
// build.gradle.kts 里对所有 APK 模块统一配置，此处无需重复声明。

android {
    namespace = "com.openandroidintelligence.mobile"
    defaultConfig {
        applicationId = "com.openandroidintelligence.mobile"
        versionName = "2.1.0"
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
    signingConfigs {
        create("release") {
            storeFile = providers.environmentVariable("OAI_RELEASE_KEYSTORE").orNull?.let(::file)
            storePassword = providers.environmentVariable("OAI_RELEASE_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("OAI_RELEASE_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("OAI_RELEASE_KEY_PASSWORD").orNull
        }
    }
    buildTypes.getByName("release") {
        isDebuggable = false
        signingConfig = signingConfigs.getByName("release")
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

// Fail before producing any release APK/AAB, including direct Gradle builds.
tasks.matching { it.name.matches(Regex("(package|bundle).*Release|sign.*ReleaseBundle")) }.configureEach {
    doFirst {
        val signing = android.signingConfigs.getByName("release")
        val keystoreFile = signing.storeFile
        check(keystoreFile?.isFile == true && !signing.storePassword.isNullOrBlank() &&
            !signing.keyAlias.isNullOrBlank() && !signing.keyPassword.isNullOrBlank()) {
            "Release signing requires the four OAI_RELEASE_* environment variables; see apps/android/README.md"
        }
        val keystore = KeyStore.getInstance(KeyStore.getDefaultType())
        keystoreFile!!.inputStream().use { keystore.load(it, signing.storePassword!!.toCharArray()) }
        val certificate = checkNotNull(keystore.getCertificate(signing.keyAlias)) { "Release signing certificate missing" }
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02X".format(it) }
        check(digest != "37D9445FAB11D827B032B84B0739ACB2C33AA8ACE1F319ED20A4CC612624CE33") {
            "The public debug identity must never sign a release build"
        }
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":capability-ports"))
    implementation(project(":notification-control"))
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
    // 通知采集宿主装配：ContentProvider 自注册 + Manifest 合并只需运行期在场，
    // app 代码不得 import host 符号（裁决 D6：采集器不进 app 编译期可见面）。
    runtimeOnly(project(":notification-host"))

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
    // Process-wide visibility: the app has to act on coming back to the
    // foreground, not on one Activity's lifecycle.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    // Compose 界面语义测试：禁用态条目的点击门控只在组件层保证，
    // 需要在界面层用语义树验证「点了没反应」。
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
