plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openandroidintelligence.conversation.data"
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
}

dependencies {
    implementation(project(":conversation-domain"))
    implementation(project(":gateway-client"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
}

// HTTP 互通会启动独立插件及真实 Hermes SDK，必须使用本次调用指定的源码。
// 除提交身份外也跟踪文件内容，未提交修复不能从旧的测试缓存取得通过证据。
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val repositoryRoot = rootProject.projectDir.resolve("../..").canonicalFile
    val pluginRoot = providers.environmentVariable("HERMES_PLUGIN_ROOT").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { repositoryRoot.resolve(it).canonicalFile }
        ?: repositoryRoot.resolve(".hermes-gateway-plugin")
    val contractRoot = providers.environmentVariable("OPEN_ANDROID_GATEWAY_CONTRACT_ROOT").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { repositoryRoot.resolve(it).canonicalFile }
        ?: repositoryRoot.resolve("gateway-contract")
    val sdkRoot = providers.environmentVariable("HERMES_SDK_ROOT").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { repositoryRoot.resolve(it).canonicalFile }

    environment("HERMES_PLUGIN_ROOT", pluginRoot.path)
    environment("OPEN_ANDROID_GATEWAY_CONTRACT_ROOT", contractRoot.path)
    environment("HERMES_SDK_ROOT", sdkRoot?.path ?: "")

    inputs.files(fileTree(pluginRoot) {
        include("open_android_intelligence_gateway/**/*.py", "tests/**/*.py")
        include("plugin.yaml", "pyproject.toml", "contract-pin.json")
    }).withPropertyName("hermesGatewaySources")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(fileTree(contractRoot) {
        include("**/*.json", "src/**/*.ts")
        exclude(".git/**", ".toolchains/**", "tmp/**", "node_modules/**")
    }).withPropertyName("hermesGatewayContract")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)

    // 未指定 SDK 时仍可独立执行解码等单元测试；历史互通用例负责明确拒绝缺失来源。
    if (sdkRoot != null) {
        inputs.files(fileTree(sdkRoot) {
            include("**/*.py", "pyproject.toml", "requirements*.txt")
            exclude(".git/**", "venv/**", ".venv/**", ".toolchains/**", "tmp/**")
            exclude("**/venv/**", "**/.venv/**", "**/node_modules/**", "**/.git/**")
            exclude("tests/**", "test/**", "docs/**", "**/__pycache__/**")
        }).withPropertyName("hermesNativeSources")
            .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    }
    for ((label, sourceRoot) in listOf("gateway" to pluginRoot, "native" to sdkRoot)) {
        if (sourceRoot != null && sourceRoot.isDirectory) {
            val revision = providers.exec {
                workingDir(sourceRoot)
                commandLine("git", "-c", "core.filemode=false", "rev-parse", "--verify", "HEAD")
            }.standardOutput.asText.map { output ->
                output.trim().also {
                    check(it.matches(Regex("[0-9a-f]{40}"))) { "Hermes $label 源码缺少可核对的提交身份" }
                }
            }
            inputs.property("hermes${label.replaceFirstChar { it.uppercaseChar() }}Revision", revision)
        }
    }
    // 将失败原因与隔离夹具的阶段日志交给 CI，区分产品回归、夹具和依赖缺失。
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showStandardStreams = true
    }
}
