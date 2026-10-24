import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 原件回取使用生产 HTTP 与固定 SDK，源码变动必须使测试失效，不能复用旧互通缓存。
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val repositoryRoot = rootProject.projectDir.resolve("../..").canonicalFile
    val pluginRoot = providers.environmentVariable("HERMES_PLUGIN_ROOT").orNull
        ?.takeIf { it.isNotBlank() }?.let { repositoryRoot.resolve(it).canonicalFile }
        ?: repositoryRoot.resolve(".hermes-gateway-plugin")
    val contractRoot = providers.environmentVariable("OPEN_ANDROID_GATEWAY_CONTRACT_ROOT").orNull
        ?.takeIf { it.isNotBlank() }?.let { repositoryRoot.resolve(it).canonicalFile }
        ?: repositoryRoot.resolve("gateway-contract")
    val sdkRoot = providers.environmentVariable("HERMES_SDK_ROOT").orNull
        ?.takeIf { it.isNotBlank() }?.let { repositoryRoot.resolve(it).canonicalFile }
    environment("HERMES_PLUGIN_ROOT", pluginRoot.path)
    environment("OPEN_ANDROID_GATEWAY_CONTRACT_ROOT", contractRoot.path)
    environment("HERMES_SDK_ROOT", sdkRoot?.path ?: "")
    val historyPython = providers.environmentVariable("OAI_HERMES_PYTHON").orNull?.takeIf { it.isNotBlank() } ?: "python3"
    environment("OAI_HERMES_PYTHON", historyPython)
    inputs.property("historyMediaPythonExecutable", historyPython)
    if (historyPython == "python3") {
        inputs.property("historyMediaPythonSearchPath", providers.environmentVariable("PATH").orNull.orEmpty())
    } else {
        val pythonFile = repositoryRoot.resolve(historyPython)
        if (pythonFile.isFile) inputs.file(pythonFile).withPropertyName("historyMediaPythonBinary")
            .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.NONE)
    }
    inputs.files(fileTree(pluginRoot) {
        include("open_android_intelligence_gateway/**/*.py", "tests/**/*.py", "plugin.yaml", "pyproject.toml", "contract-pin.json")
    }).withPropertyName("historyMediaGatewaySources").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(fileTree(contractRoot) {
        include("schemas/**/*.json", "vectors/**/*.json", "src/**/*.ts", "core-dispatched-schemas.json")
    }).withPropertyName("historyMediaContract").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    if (sdkRoot != null) inputs.files(fileTree(sdkRoot) {
        include("**/*.py", "pyproject.toml", "requirements*.txt")
        exclude(".git/**", "**/__pycache__/**", "tests/**", "docs/**", "**/.venv/**", "**/node_modules/**")
    }).withPropertyName("historyMediaNativeSources").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}

tasks.withType<Test>().configureEach {
    // 参考插件的真实编译产物与清单：测试用它们构造与发布同构的签名包，
    // 因此这些输入变动必须让测试失效，不能复用旧结果。
    for (spec in listOf(
        "sms" to "sms.wasm",
        "notifications" to "notifications.wasm",
        "call-log" to "call_log.wasm",
    )) {
        inputs.files(rootProject.projectDir.resolve("../../plugins/target/wasm32-unknown-unknown/release/${spec.second}"))
            .withPropertyName("compiled${spec.first.replace("-", "")}Reference".replaceFirstChar(Char::uppercase))
        inputs.file(rootProject.projectDir.resolve("../../plugins/${spec.first}/manifest.json"))
            .withPropertyName("${spec.first.replace("-", "")}ReferenceManifest")
    }
    inputs.dir(rootProject.projectDir.resolve("../../plugins/schemas")).withPropertyName("referenceSchemas")
}

// Full releases carry the same signed .alp installed through the ordinary verifier.
// An absent optional local input must clear yesterday's assets, never reuse them.
//
// 三个官方插件各自独立：缺失某一项只让它自己的入口如实说明「本包未附带」，
// 不会连带其他插件。只有显式要求（-Poai.requireBundledPlugins=true）时，
// 任意一项缺失才让构建失败——发布流程正是用它保证不会漏包。
internal data class BundledPluginSpec(val name: String, val pluginId: String, val wasm: String)

internal val bundledPlugins = listOf(
    BundledPluginSpec("notifications", "org.openandroidintelligence.notifications", "notifications.wasm"),
    BundledPluginSpec("sms", "org.openandroidintelligence.sms", "sms.wasm"),
    BundledPluginSpec("call-log", "org.openandroidintelligence.call-log", "call_log.wasm"),
)
val bundledPluginsDirectory = providers.environmentVariable("OAI_BUNDLED_PLUGINS_DIR").orElse("")
val requireBundledPlugins = providers.gradleProperty("oai.requireBundledPlugins").map(String::toBoolean).orElse(false)

abstract class StageBundledPlugins : DefaultTask() {
    /** 目录内按插件名取 `<name>.alp`；未设置时为空。 */
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val signedPackages: DirectoryProperty

    /** 逗号分隔的 `<name>=<pluginId>`，供任务自证资产与插件身份对应。 */
    @get:Input
    abstract val pluginIds: Property<String>

    @get:Input
    abstract val bundleRequired: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        val destination = outputDirectory.get().asFile
        check(!destination.exists() || destination.deleteRecursively()) { "BUNDLED_PLUGINS_CLEANUP_FAILED" }
        check(destination.mkdirs()) { "BUNDLED_PLUGINS_DIRECTORY_FAILED" }
        val source = signedPackages.orNull?.asFile
        val expected = pluginIds.get().split(',').filter(String::isNotBlank)
        val missing = mutableListOf<String>()
        for (entry in expected) {
            val name = entry.substringBefore('=')
            val packageFile = source?.resolve("$name.alp")
            if (packageFile?.isFile != true) { missing += name; continue }
            check(packageFile.length() in 1..(32L * 1024 * 1024)) { "BUNDLED_PLUGINS_INVALID:$name" }
            val asset = destination.resolve("bundled-plugins/$name.alp")
            asset.parentFile.mkdirs()
            packageFile.copyTo(asset, overwrite = true)
        }
        check(missing.isEmpty() || !bundleRequired.get()) {
            "BUNDLED_PLUGINS_REQUIRED: set OAI_BUNDLED_PLUGINS_DIR to a directory containing " +
                missing.joinToString { "$it.alp" }
        }
    }
}

val stageBundledPlugins by tasks.registering(StageBundledPlugins::class) {
    bundleRequired.set(requireBundledPlugins)
    pluginIds.set(bundledPlugins.joinToString(",") { "${it.name}=${it.pluginId}" })
    bundledPluginsDirectory.get().takeIf(String::isNotBlank)?.let { signedPackages.set(file(it)) }
    outputDirectory.set(layout.buildDirectory.dir("generated/bundled-plugins/assets"))
}

// 固定调试签名（app/keystore/debug.keystore，见同目录 README.md）在根工程
// build.gradle.kts 里对所有 APK 模块统一配置，此处无需重复声明。

val appBaseVersion = Properties().apply {
    file("version.properties").inputStream().use(::load)
}
val appRepositoryRoot = rootProject.projectDir.resolve("../..")
fun appGitValue(vararg arguments: String): String? = runCatching {
    providers.exec {
        workingDir(appRepositoryRoot)
        commandLine("git", *arguments)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
}.getOrNull()

val appCommit = providers.environmentVariable("OAI_GIT_COMMIT").orNull
    ?: appGitValue("rev-parse", "HEAD").orEmpty()
val appCommitCount = if (appGitValue("rev-parse", "--is-shallow-repository") == "false") {
    appGitValue("rev-list", "--count", "HEAD")?.toIntOrNull()
} else null
val appReleaseChannel = providers.environmentVariable("OAI_UPDATE_CHANNEL").orElse("development").get()
// 每次源码提交预留十个渠道阶段：开发为 0、编号测试版为 1、正式版为 2。
val appVersionCodeChannelScale = 10
val appVersionCode = providers.environmentVariable("OAI_VERSION_CODE").orNull?.let {
    checkNotNull(it.toIntOrNull()) { "App versionCode 必须是有效整数" }
}
    ?: appCommitCount?.let { Math.multiplyExact(it, appVersionCodeChannelScale) }
    ?: appBaseVersion.getProperty("versionCode").toInt()
val appVersionName = providers.environmentVariable("OAI_VERSION_NAME").orNull
    ?: if (appCommitCount != null && appCommit.isNotEmpty()) {
        "${appBaseVersion.getProperty("versionName")}-dev.$appCommitCount+${appCommit.take(12)}"
    } else appBaseVersion.getProperty("versionName")

check(appReleaseChannel in setOf("development", "preview", "stable")) { "未知的 App 更新渠道" }
check(appVersionCode in 1..2_100_000_000) { "App versionCode 超出 Android 发行范围" }
check(appVersionName.matches(Regex("[0-9A-Za-z.+-]+"))) { "App versionName 格式无效" }
check(appCommit.isEmpty() || appCommit.matches(Regex("[0-9a-f]{40}"))) { "App 提交信息必须是完整的 Git SHA" }

android {
    namespace = "com.openandroidintelligence.mobile"
    defaultConfig {
        applicationId = "com.openandroidintelligence.mobile"
        versionName = appVersionName
        versionCode = appVersionCode
        buildConfigField("String", "UPDATE_CHANNEL", "\"$appReleaseChannel\"")
        buildConfigField("String", "GIT_COMMIT", "\"$appCommit\"")
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

// Let AGP own the generated asset and its producer instead of exposing a raw
// sourceSets directory that Lint models read without a dependency. Play has no bundle.
androidComponents {
    onVariants(selector().withFlavor("distribution" to "full")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(stageBundledPlugins, StageBundledPlugins::outputDirectory)
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
    implementation(project(":assistant-holder"))
    implementation(project(":capability-ports"))
    implementation(project(":notification-control"))
    implementation(project(":gateway-client"))
    implementation(project(":platform-kernel"))
    implementation(project(":plugin-package"))
    implementation(project(":plugin-runtime-wasm"))
    implementation(project(":plugin-ui"))
    implementation(project(":encrypted-store"))
    implementation(project(":conversation-domain"))
    implementation(project(":conversation-data"))
    implementation(project(":conversation-ui"))
    // 通知采集宿主装配：ContentProvider 自注册 + Manifest 合并只需运行期在场，
    // app 代码不得 import host 符号（裁决 D6：采集器不进 app 编译期可见面）。
    runtimeOnly(project(":notification-host"))
    runtimeOnly(project(":device-primitives"))

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
    // 应用更新使用独立客户端；Call.cancel 可立即中止正在读取的响应。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    // API 36 tests require JDK 21; application bytecode remains Java 17.
    testImplementation("org.robolectric:robolectric:4.16")
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
