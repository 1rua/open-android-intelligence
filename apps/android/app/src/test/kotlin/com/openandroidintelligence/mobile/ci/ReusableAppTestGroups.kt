package com.openandroidintelligence.mobile.ci

import java.lang.reflect.Modifier
import java.util.Random
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runner.manipulation.Ordering
import org.junit.runners.Suite
import org.junit.runners.model.RunnerBuilder

/** 入口固定分组，随机种子只改变组内顺序，不改变每个测试所属的进程。 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ReusableAppGroup(val value: Int)

@RunWith(ReusableAppSuite::class)
@ReusableAppGroup(0)
class ReusableAppTestGroup0

@RunWith(ReusableAppSuite::class)
@ReusableAppGroup(1)
class ReusableAppTestGroup1

@RunWith(ReusableAppSuite::class)
@ReusableAppGroup(2)
class ReusableAppTestGroup2

@RunWith(ReusableAppSuite::class)
@ReusableAppGroup(3)
class ReusableAppTestGroup3

/**
 * 仅把静态审计清单与本次实际选择的交集装入同一个 JUnit Suite。
 *
 * Gradle 仍对顶层入口使用 forkEvery=1；未列入清单的类保持原有的独立 JVM。
 * 本入口由实验开关显式选择，默认测试发现必须排除四个入口，避免重复执行。
 *
 * 配置缺失、重复、清单外选择和空组均直接报错，不能把漏测当成成功。
 * 规划器同时保留实际子类名称，便于核对本次选择与最终执行的真实测试集合。
 */
class ReusableAppSuite private constructor(
    suiteClass: Class<*>,
    builder: RunnerBuilder,
    private val selection: Selection,
) : Suite(builder, suiteClass, selection.classes) {

    constructor(suiteClass: Class<*>, builder: RunnerBuilder) :
        this(suiteClass, builder, select(suiteClass))

    init {
        // 使用 JUnit 4.13 的标准乱序实现；保留子类原有的 Runner、规则和断言。
        Ordering.shuffledBy(Random(selection.seed)).apply(this)
        println(
            "App 测试 JVM 复用：组=${selection.group}，种子=${selection.seed}，" +
                "类=${description.children.joinToString(",") { it.className ?: it.displayName }}",
        )
    }

    private data class Selection(val group: Int, val seed: Long, val classes: Array<Class<*>>)

    companion object {
        const val GROUP_COUNT = 4
        const val CLASSES_PROPERTY = "oai.testReuseClasses"
        const val SEED_PROPERTY = "oai.testReuseSeed"
        const val ALLOWLIST_PROPERTY = "oai.testReuseAllowlist"

        /** Python 规划器须镜像 Java String.hashCode 与 floorMod，不能使用 Python hash。 */
        @JvmStatic
        fun groupFor(className: String): Int = Math.floorMod(className.hashCode(), GROUP_COUNT)

        private val classNamePattern = Regex("com\\.openandroidintelligence\\.mobile(?:\\.[A-Za-z_][A-Za-z0-9_]*)+Test")

        private fun select(suiteClass: Class<*>): Selection {
            val group = requireNotNull(suiteClass.getAnnotation(ReusableAppGroup::class.java)) {
                "JVM 复用入口缺少分组声明：${suiteClass.name}"
            }.value
            require(group in 0 until GROUP_COUNT) { "JVM 复用分组越界：$group" }
            val seed = requireNotNull(System.getProperty(SEED_PROPERTY)?.toLongOrNull()) {
                "JVM 复用必须显式提供 Long 类型随机种子：$SEED_PROPERTY"
            }
            val allowlist = requiredProperty(ALLOWLIST_PROPERTY).lineSequence()
                .map { it.substringBefore('#').trim() }.filter(String::isNotEmpty).toList()
            validateNames(allowlist, "审计清单")
            val selected = requiredProperty(CLASSES_PROPERTY).split(',').map(String::trim)
            validateNames(selected, "本次测试选择")
            val unreviewed = selected.toSet() - allowlist.toSet()
            require(unreviewed.isEmpty()) { "本次测试选择包含未经审计的类：${unreviewed.sorted()}" }
            val groupNames = selected.filter { groupFor(it) == group }.sorted()
            require(groupNames.isNotEmpty()) { "JVM 复用组 $group 没有本次选中的测试，规划器不得选择空入口" }
            val classes = groupNames.map { name ->
                // 不扫描类路径，也不加载本次未选中或属于其他组的测试类。
                val candidate = Class.forName(name, false, suiteClass.classLoader)
                require(Modifier.isPublic(candidate.modifiers) && !Modifier.isAbstract(candidate.modifiers)) {
                    "JVM 复用测试必须是可实例化的公开类：$name"
                }
                require(candidate.methods.any { it.isAnnotationPresent(Test::class.java) }) {
                    "JVM 复用清单中的类没有 JUnit 4 测试方法：$name"
                }
                candidate
            }.toTypedArray()
            return Selection(group, seed, classes)
        }

        private fun requiredProperty(name: String): String =
            requireNotNull(System.getProperty(name)?.takeIf(String::isNotBlank)) {
                "JVM 复用缺少必要配置：$name"
            }

        private fun validateNames(names: List<String>, source: String) {
            require(names.isNotEmpty()) { "$source 不能为空" }
            require(names.all(classNamePattern::matches)) { "$source 必须逐项使用真实测试类的完整限定名称" }
            require(names.size == names.toSet().size) { "$source 存在重复类，不能重复执行或静默去重" }
        }
    }
}
