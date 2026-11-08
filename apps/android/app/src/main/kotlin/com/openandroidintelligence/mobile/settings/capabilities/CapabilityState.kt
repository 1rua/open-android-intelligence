package com.openandroidintelligence.mobile.settings.capabilities

/**
 * 主页面上每项能力只显示一个状态词，且必须与详情页的四条事实一致。
 *
 * 判定顺序固定，命中即停：不可用 → 已关闭 → 需授权 → 已开启。
 * 顺序本身是需求的一部分：能力根本没加载成功时，说“已关闭”会让用户去点一个
 * 永远不会生效的开关；用户自己关掉的能力也不该被说成“不可用”。
 */
enum class CapabilityState(val label: String) {
    /** 当前可以真的被调用：开关开启且所有前置条件成立。 */
    ON("已开启"),

    /** 用户自己关闭了这项能力。这是决定，不是故障。 */
    OFF("已关闭"),

    /** 开关开启，但还有一项用户能自己解决的阻塞（系统权限、服务断连、提供者未选）。 */
    NEEDS_AUTH("需授权"),

    /** 内建能力加载失败、声明缺失或系统版本不支持：用户点一下恢复不了。 */
    UNAVAILABLE("不可用"),
}

/**
 * 汇总一项能力的全部事实。
 *
 * 每个字段都来自一处真实读数，没有默认值兜底：调用方漏填就是编译错误，
 * 而不是默默显示“已开启”。
 */
internal data class CapabilityFacts(
    /** 内建能力本次启动的校验/加载是否失败。失败时详情页要给出可诊断原因。 */
    val loadFailed: Boolean,
    /** 是否存在已验证的能力声明。 */
    val declared: Boolean,
    /** 本机能力开关。 */
    val policyEnabled: Boolean,
    /** 本机授权存储是否可读。读不出来时必须按关闭处理，不能显示可用。 */
    val policyReadable: Boolean,
    /** Android 系统权限与系统服务是否满足。 */
    val androidGranted: Boolean,
    /** 运行服务是否就绪（提供者可用）。 */
    val providerReady: Boolean,
)

/**
 * 把事实折叠成一个状态词。
 *
 * Core 同步不在入参里：它只影响详情页的单独一行，不改变这台手机上能不能用。
 */
internal fun capabilityState(facts: CapabilityFacts): CapabilityState = when {
    facts.loadFailed || !facts.declared -> CapabilityState.UNAVAILABLE
    !facts.policyReadable || !facts.policyEnabled -> CapabilityState.OFF
    !facts.androidGranted || !facts.providerReady -> CapabilityState.NEEDS_AUTH
    else -> CapabilityState.ON
}

/** 主页面与详情页共用的「已就绪」判据：与主页面 `已开启` 严格等价。 */
internal fun isReady(facts: CapabilityFacts): Boolean = capabilityState(facts) == CapabilityState.ON
