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

    // 校验多语言配置前置约束，防止 resources.properties 缺失或 localeFilters 语法错误
    if (buildFile.exists()) {
        val projPath = path
        val projDirPath = projectDir.absolutePath
        val bFilePath = buildFile.absolutePath

        val verifyTask = tasks.register("verifyLocaleConfigPrerequisites") {
            group = "verification"
            description = "前置校验 generateLocaleConfig 的 resources.properties 存在性及 localeFilters 合法性"
            doLast {
                val bFile = File(bFilePath)
                if (bFile.exists()) {
                    val buildContent = bFile.readText()
                    if (buildContent.contains("generateLocaleConfig = true")) {
                        val resProps = File(projDirPath, "src/main/res/resources.properties")
                        if (!resProps.exists()) {
                            throw GradleException(
                                "【国际化门禁阻断】模块 '$projPath' 声明了 generateLocaleConfig = true，但缺少 'src/main/res/resources.properties'！" +
                                "AGP 必须依据该文件中的 'unqualifiedResLocale=zh-CN' 将 values/ 识别为基准语言。"
                            )
                        }
                        if (!resProps.readText().contains("unqualifiedResLocale")) {
                            throw GradleException(
                                "【国际化门禁阻断】模块 '$projPath' 的 'src/main/res/resources.properties' 缺少 'unqualifiedResLocale' 配置！"
                            )
                        }
                    }
                    // 拦截形如 "zh-CN" 的非法 AAPT2 限定符
                    val invalidRegex = Regex("""localeFilters\s*\+?=\s*listOf\([^)]*\"([a-z]{2,3}-[A-Z]{2})\"""")
                    val match = invalidRegex.find(buildContent)
                    if (match != null) {
                        val badLocale = match.groupValues[1]
                        val suggested = badLocale.replace("-", "-r")
                        throw GradleException(
                            "【国际化门禁阻断】模块 '$projPath' 的 build.gradle.kts 中配置了非法的 localeFilters '$badLocale'！" +
                            "AAPT2 '-c' 选项要求资源限定符必须带区域前缀 'r' (如 '$suggested') 或采用 BCP-47 标签 (如 'b+${badLocale.replace("-", "+")}')。"
                        )
                    }
                }
            }
        }
        tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach {
            dependsOn(verifyTask)
        }
    }
}
