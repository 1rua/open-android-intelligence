/**
 * 打包前扫描源码与合并后的 Manifest，拒绝系统 VPN 和通用监听面。
 * Android 宿主通过明确登记的网络 owner 连接 Gateway 或查询公开发布，
 * 不拥有系统隧道、路由、DNS、代理或通用网络监听能力。
 */
import java.io.File
import org.gradle.api.tasks.PathSensitivity

/**
 * Banned in every module, owners included: the product owns no system tunnel,
 * route, DNS, proxy or generic listener/dial surface.
 */
val alwaysForbidden = listOf(
    Regex("VpnService", RegexOption.IGNORE_CASE),
    Regex("BIND_VPN_SERVICE", RegexOption.IGNORE_CASE),
    Regex("TunInterface", RegexOption.IGNORE_CASE),
    Regex("\\bTUN\\b", RegexOption.IGNORE_CASE),
    Regex("addRoute", RegexOption.IGNORE_CASE),
    Regex("setHttpProxy", RegexOption.IGNORE_CASE),
    Regex("ProxyInfo", RegexOption.IGNORE_CASE),
    Regex("LocalAPI", RegexOption.IGNORE_CASE),
    Regex("\\bListen\\s*\\(", RegexOption.IGNORE_CASE),
    Regex("\\bDial\\s*\\(", RegexOption.IGNORE_CASE),
)

/**
 * 出站 HTTP 与传输接口由 Gateway 客户端、受审查的 Companion 适配器，
 * 或精确登记的公开发布查询文件负责；其他源码必须通过这些 owner 访问网络。
 */
val forbiddenOutsideOwners = listOf(
    Regex("\\b(?:URLConnection|WebSocket|HttpClient|OkHttpClient|ServerSocket|DatagramSocket|Socket)\\b", RegexOption.IGNORE_CASE),
    Regex("\\b(?:URL|openConnection|createSocket)\\s*\\(", RegexOption.IGNORE_CASE),
)

val networkOwnerModules = setOf("gateway-client")

/**
 * App 更新仅查询项目的公开 GitHub 发布，不携带账号凭据、不复用 Gateway
 * 客户端，并在实现内校验官方 HTTPS URL。只登记这一生产文件；同目录及
 * app 模块的其他源码仍禁止直接联网，全部 owner 仍受 alwaysForbidden 检查。
 */
val networkOwnerFiles = setOf(
    "app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateChecker.kt",
)

/**
 * Generated output is not source: scanning it makes the gate depend on build
 * leftovers (stale androidTest result XML previously produced violations that
 * disappeared on a clean build).
 */
val generatedDirectoryNames = setOf("build", ".gradle", ".cxx", "generated")

/**
 * These files name the forbidden surfaces only to assert their absence on a
 * real device; they are the audits that enforce this gate. Granting them an
 * explicit, path-narrow exemption keeps every other module under the ban.
 */
val absenceAuditFiles = setOf(
    "app/src/androidTest/kotlin/com/openandroidintelligence/mobile/P0tAppNoVpnSurfaceInstrumentedTest.kt",
)

/**
 * 仅存在于 JVM 单元测试里的回环测试夹具：它在 127.0.0.1 上临时开一个
 * ServerSocket 充当契约形状的网关替身，用来断言「请求真的发到了契约地址、
 * 响应真的从那里回来」。这是测试技术，不是产品监听面——进程退出即关闭，
 * 不发布端口、不对外提供服务。按路径窄豁免，其余任何文件仍受禁令约束。
 */
val testOnlyLoopbackFixtures = setOf(
    "app/src/test/kotlin/com/openandroidintelligence/mobile/LoopbackGatewayStub.kt",
)

/**
 * 这些 JVM 回归核对实际 HTTP 类、事件通道和请求取消，需要提及出站网络
 * 关键字，更新检测回归还使用真实回环连接。仅按完整测试路径允许这些断言；
 * 生产源码仍由网络 owner 管理，VPN、路由、代理及通用监听/拨号禁令仍检查。
 */
val testOnlyOutboundAssertions = setOf(
    "app/src/test/kotlin/com/openandroidintelligence/mobile/AndroidAttachmentContentTypeTest.kt",
    "app/src/test/kotlin/com/openandroidintelligence/mobile/AppUpdateCheckerTest.kt",
    "app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/NotificationCapabilityHttpTest.kt",
    "conversation-data/src/test/kotlin/com/openandroidintelligence/conversation/data/HermesHttpInteropTest.kt",
)

fun sourceFilesUnder(root: File): List<File> =
    root.walkTopDown()
        .onEnter { it.name !in generatedDirectoryNames }
        .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
        .filter { it.relativeTo(rootDir).path !in absenceAuditFiles }
        .filter { it.relativeTo(rootDir).path !in testOnlyLoopbackFixtures }
        .toList()

/**
 * Unchecked-owner surfaces must be rejected before packaging.
 *
 * File lists and pattern text are resolved at configuration time and declared as
 * task inputs. The scan body is deliberately self-contained: a `doLast` closure
 * that calls script-level functions captures the Kotlin script instance, which
 * Gradle cannot serialize, and that made the whole build configuration-cache
 * incompatible. Keeping the closure free of script references lets CI reuse both
 * the configuration cache and the task output cache.
 *
 * The previous implementation additionally read `rootProject.subprojects` inside
 * `doLast`, which captured live project state for the same reason.
 */
tasks.register("noVpnSurfaceCheck") {
    group = "verification"
    description = "Reject system VPN, route/DNS, proxy/listener and generic dial surfaces."

    // Derived from the registered modules so a newly included module is covered
    // by this gate the moment it is added, not by a manual list edit.
    val ownerFiles = mutableListOf<File>()
    val strictFiles = mutableListOf<File>()
    rootProject.subprojects.forEach { project ->
        val moduleOwnsTransport = project.name in networkOwnerModules
        sourceFilesUnder(project.projectDir).forEach { file ->
            val relative = file.relativeTo(rootDir).path
            if (moduleOwnsTransport || relative in networkOwnerFiles || relative in testOnlyOutboundAssertions) {
                ownerFiles.add(file)
            } else {
                strictFiles.add(file)
            }
        }
    }
    val ownerSnapshot = ownerFiles.toList()
    val strictSnapshot = strictFiles.toList()
    val alwaysPatterns = alwaysForbidden.map { it.pattern }
    val ownerOnlyPatterns = forbiddenOutsideOwners.map { it.pattern }
    val reportedRoot = rootDir.absolutePath

    inputs.files(ownerSnapshot).withPropertyName("outboundOwnerSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(strictSnapshot).withPropertyName("strictSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("alwaysBannedPatterns", alwaysPatterns)
    inputs.property("ownerOnlyBannedPatterns", ownerOnlyPatterns)
    // A marker output lets the task be up-to-date and cacheable. Without an
    // output the scan re-reads every source file on each invocation.
    val evidenceFile = layout.buildDirectory.file("reports/mvp-forbidden-surfaces/clean.txt")
    outputs.file(evidenceFile)

    doLast {
        val root = File(reportedRoot)
        val alwaysBanned = alwaysPatterns.map { Regex(it, RegexOption.IGNORE_CASE) }
        val ownerOnlyBanned = ownerOnlyPatterns.map { Regex(it, RegexOption.IGNORE_CASE) }

        fun stripComments(text: String, isXml: Boolean): List<String> {
            val out = StringBuilder(text.length)
            var i = 0
            val n = text.length
            if (isXml) {
                var inXmlComment = false
                while (i < n) {
                    if (!inXmlComment && i + 3 < n && text[i] == '<' && text[i + 1] == '!' && text[i + 2] == '-' && text[i + 3] == '-') {
                        inXmlComment = true
                        out.append("    ")
                        i += 4
                    } else if (inXmlComment && i + 2 < n && text[i] == '-' && text[i + 1] == '-' && text[i + 2] == '>') {
                        inXmlComment = false
                        out.append("   ")
                        i += 3
                    } else {
                        out.append(if (inXmlComment && text[i] != '\n' && text[i] != '\r') ' ' else text[i])
                        i++
                    }
                }
                return out.toString().lines()
            }

            var inBlockComment = 0
            var inLineComment = false
            var inString = false
            var inRawString = false
            var inChar = false
            while (i < n) {
                val c = text[i]
                val next = if (i + 1 < n) text[i + 1] else '\u0000'
                val next2 = if (i + 2 < n) text[i + 2] else '\u0000'
                when {
                    inLineComment -> {
                        if (c == '\n') {
                            inLineComment = false
                            out.append('\n')
                        } else if (c == '\r') {
                            out.append('\r')
                        } else {
                            out.append(' ')
                        }
                        i++
                    }
                    inBlockComment > 0 -> {
                        if (c == '/' && next == '*') {
                            inBlockComment++
                            out.append("  ")
                            i += 2
                        } else if (c == '*' && next == '/') {
                            inBlockComment--
                            out.append("  ")
                            i += 2
                        } else {
                            out.append(if (c == '\n' || c == '\r') c else ' ')
                            i++
                        }
                    }
                    inRawString -> {
                        if (c == '"' && next == '"' && next2 == '"') {
                            inRawString = false
                            out.append("\"\"\"")
                            i += 3
                        } else {
                            out.append(c)
                            i++
                        }
                    }
                    inString -> {
                        if (c == '\\' && i + 1 < n) {
                            out.append(c).append(next)
                            i += 2
                        } else if (c == '"') {
                            inString = false
                            out.append(c)
                            i++
                        } else {
                            out.append(c)
                            i++
                        }
                    }
                    inChar -> {
                        if (c == '\\' && i + 1 < n) {
                            out.append(c).append(next)
                            i += 2
                        } else if (c == '\'') {
                            inChar = false
                            out.append(c)
                            i++
                        } else {
                            out.append(c)
                            i++
                        }
                    }
                    else -> when {
                        c == '/' && next == '/' -> {
                            inLineComment = true
                            out.append("  ")
                            i += 2
                        }
                        c == '/' && next == '*' -> {
                            inBlockComment = 1
                            out.append("  ")
                            i += 2
                        }
                        c == '"' && next == '"' && next2 == '"' -> {
                            inRawString = true
                            out.append("\"\"\"")
                            i += 3
                        }
                        c == '"' -> {
                            inString = true
                            out.append(c)
                            i++
                        }
                        c == '\'' -> {
                            inChar = true
                            out.append(c)
                            i++
                        }
                        else -> {
                            out.append(c)
                            i++
                        }
                    }
                }
            }
            return out.toString().lines()
        }

        fun scan(files: Iterable<File>, banned: List<Regex>): List<String> = buildList {
            files.filter { it.isFile && it.extension in setOf("kt", "java", "xml") }.forEach { file ->
                val lines = stripComments(file.readText(), file.extension.equals("xml", ignoreCase = true))
                lines.forEachIndexed { index, line ->
                    if (banned.any { it.containsMatchIn(line) }) {
                        add("${file.relativeTo(root).path}:${index + 1}: forbidden surface")
                    }
                }
            }
        }

        val violations = buildList {
            addAll(scan(ownerSnapshot, alwaysBanned))
            addAll(scan(strictSnapshot, alwaysBanned + ownerOnlyBanned))
        }
        check(violations.isEmpty()) { violations.sorted().joinToString("\n") }
        val evidence = evidenceFile.get().asFile
        evidence.parentFile.mkdirs()
        evidence.writeText("checked=${ownerSnapshot.size + strictSnapshot.size}\n")
    }
}

tasks.named("check") { dependsOn("noVpnSurfaceCheck") }

// Module-scoped checks must not bypass the same root-wide source scan.
//
// The dependency target must be the root project's task path. Inside a
// `subprojects {}` block a bare "noVpnSurfaceCheck" resolves against the
// subproject, where the task does not exist, and `:app:check` fails with
// "Task with path 'noVpnSurfaceCheck' not found".
subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("noVpnSurfaceCheck"))
    }
}
