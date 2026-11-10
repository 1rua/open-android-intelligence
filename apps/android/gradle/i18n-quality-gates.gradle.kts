/**
 * 国际化与文案资源化静态门禁 Gradle 插件。
 * 将源码级硬编码扫描与 XML 完整性校验注册为标准 Gradle 任务并挂载到 check 生命周期。
 */
import java.io.File

val repoRootDir = rootDir.parentFile.parentFile

tasks.register<Exec>("checkHardcodedChineseStrings") {
    group = "verification"
    description = "扫描 Kotlin 与 Compose 源码中的未豁免硬编码中文 (tools/check_hardcoded_chinese.py)"
    workingDir = repoRootDir
    commandLine = listOf("python3", "tools/check_hardcoded_chinese.py")
}

tasks.register<Exec>("checkStringResourcesIntegrity") {
    group = "verification"
    description = "验证 values/*.xml 语法、占位符索引与全局 ID 唯一性 (tools/test_string_resources_integrity.py)"
    workingDir = repoRootDir
    commandLine = listOf("python3", "tools/test_string_resources_integrity.py")
}

// 挂载至主工程 check 任务
tasks.named("check") {
    dependsOn("checkHardcodedChineseStrings", "checkStringResourcesIntegrity")
}

// 确保子模块在执行 check 时联动执行国际化质量门禁
subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("checkHardcodedChineseStrings"))
        dependsOn(rootProject.tasks.named("checkStringResourcesIntegrity"))
    }
}
