plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 固定调试签名：仓库内 app/keystore/debug.keystore（alias androiddebugkey / 口令 android，
// 详见同目录 README.md）。若不显式指定，AGP 会退回到每台机器、每次 CI 构建各自随机生成的
// ~/.android/debug.keystore，于是同一份代码打出的调试包签名互不相同，覆盖安装会报
// INSTALL_FAILED_UPDATE_INCOMPATIBLE。该密钥仅用于调试，禁止用于正式发布。
val debugKeystore = layout.projectDirectory.file("keystore/debug.keystore")
check(debugKeystore.asFile.isFile) {
    "缺少固定调试密钥：${debugKeystore.asFile}（生成方式见 apps/android/app/keystore/README.md）"
}
val debugKeystoreAlias = "androiddebugkey"
val debugKeystorePassword = "android"

android {
    namespace = "com.openandroidintelligence.mobile"
    defaultConfig {
        applicationId = "com.openandroidintelligence.mobile"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 覆盖 AGP 预置的 debug 签名配置，统一指向仓库内固定密钥。
        getByName("debug").apply {
            storeFile = debugKeystore.asFile
            storePassword = debugKeystorePassword
            keyAlias = debugKeystoreAlias
            keyPassword = debugKeystorePassword
            // v2/v3 是现代设备实际校验的方案；保留 v1(JAR) 签名是为了让只有 JDK 的环境
            // （apps/android/tools/verify-debug-signing.sh 的 keytool 路径）也能读出签名者证书。
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("debug").apply {
            // 显式绑定固定调试签名，避免回落到机器本地随机调试密钥。
            signingConfig = signingConfigs.getByName("debug")
        }
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
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
