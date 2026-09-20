plugins {
    base
    id("com.android.application") version "8.9.2" apply false
    id("com.android.library") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

// Keep the userspace/no-VPN surface gate attached to the root check task so
// every APK/library build evaluates the same forbidden-surface policy.
apply(from = "$rootDir/gradle/mvp-forbidden-surfaces.gradle.kts")

// 固定调试签名，统一作用于本工程下所有 APK 模块（app、assistant-holder）。
// 密钥仓库内自带：app/keystore/debug.keystore（alias androiddebugkey / 口令 android，
// 详见 app/keystore/README.md）。若不显式指定，AGP 会让每个调试包使用本机随机生成的
// ~/.android/debug.keystore，于是同一次提交在不同机器、以及每次 CI runner 上打出的
// APK 签名都不同，覆盖安装会报 INSTALL_FAILED_UPDATE_INCOMPATIBLE，必须先卸载。
// 该密钥仅用于调试，禁止用于正式发布。
val debugKeystoreFile = rootProject.layout.projectDirectory.file("app/keystore/debug.keystore")
check(debugKeystoreFile.asFile.isFile) {
    "缺少固定调试密钥：${debugKeystoreFile.asFile}（生成方式见 apps/android/app/keystore/README.md）"
}
val debugKeystoreAlias = "androiddebugkey"
val debugKeystorePassword = "android"

allprojects {
    group = "com.openandroidintelligence"
    version = "0.1.0-mvp"
}

subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            compileSdk = 35
            defaultConfig {
                minSdk = 34
                targetSdk = 35
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            // 覆盖 AGP 预置的 debug 签名配置，统一指向仓库内固定密钥
            // （真正闭合"回落到机器本地随机密钥"的是下面四项赋值）。
            signingConfigs {
                getByName("debug").apply {
                    storeFile = debugKeystoreFile.asFile
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
                    signingConfig = signingConfigs.getByName("debug")
                }
            }
        }
    }
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            compileSdk = 35
            defaultConfig {
                minSdk = 34
                // Without this a library test APK silently runs under the legacy
                // `android.test.InstrumentationTestRunner`, which ignores JUnit4
                // @Test methods: the build reports SUCCESSFUL while executing
                // zero tests.
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
            compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}
