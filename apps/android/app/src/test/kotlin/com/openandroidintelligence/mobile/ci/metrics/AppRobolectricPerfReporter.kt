package com.openandroidintelligence.mobile.ci.metrics

import java.io.File
import org.robolectric.pluginapi.perf.Metadata
import org.robolectric.pluginapi.perf.Metric
import org.robolectric.pluginapi.perf.PerfStatsReporter

/** 仅使用 Robolectric 公开统计接口，保留嵌套指标，不把它们相加冒充测试墙钟。 */
class AppRobolectricPerfReporter : PerfStatsReporter {
    init {
        record("reporter-created", emptyList())
    }

    override fun report(metadata: Metadata, metrics: Collection<Metric>) {
        record("robolectric", metrics)
    }

    private fun record(event: String, metrics: Collection<Metric>) {
        val directory = System.getProperty("oai.testPerformanceDir")?.takeIf(String::isNotBlank) ?: return
        // Android 编译桩没有 java.management；在宿主 JVM 通过公开接口读取，
        // 避免把仅供测试的 JDK 管理依赖混入 Android 运行时或业务代码。
        val factory = Class.forName("java.lang.management.ManagementFactory")
        val runtime = factory.getMethod("getRuntimeMXBean").invoke(null)
        val operatingSystem = factory.getMethod("getOperatingSystemMXBean").invoke(null)
        val collectors = factory.getMethod("getGarbageCollectorMXBeans").invoke(null) as List<*>
        val runtimeType = Class.forName("java.lang.management.RuntimeMXBean")
        val collectorType = Class.forName("java.lang.management.GarbageCollectorMXBean")
        val pid = runtimeType.getMethod("getName").invoke(runtime).toString().substringBefore('@').toLong()
        val uptime = runtimeType.getMethod("getUptime").invoke(runtime) as Long
        val cpu = runCatching {
            Class.forName("com.sun.management.OperatingSystemMXBean")
                .getMethod("getProcessCpuTime").invoke(operatingSystem) as Long
        }.getOrDefault(-1L)
        val gcCount = collectors.sumOf { (collectorType.getMethod("getCollectionCount").invoke(it) as Long).coerceAtLeast(0) }
        val gcTime = collectors.sumOf { (collectorType.getMethod("getCollectionTime").invoke(it) as Long).coerceAtLeast(0) }
        val measurements = metrics.joinToString(",") { metric ->
            "{\"name\":${quoted(metric.name)},\"elapsed_ns\":${metric.elapsedNs}," +
                "\"count\":${metric.count},\"success\":${metric.isSuccess}}"
        }
        val line = "{\"event\":${quoted(event)},\"pid\":$pid,\"uptime_ms\":$uptime," +
            "\"cpu_ns\":$cpu,\"gc_count\":$gcCount,\"gc_ms\":$gcTime," +
            "\"metrics\":[$measurements]}\n"
        File(directory).mkdirs()
        File(directory, "robolectric-$pid.jsonl").appendText(line)
    }

    private fun quoted(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 32) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
