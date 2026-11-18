package com.openandroidintelligence.conversation.remote

import java.util.Collections

/** 原生模型标识必需；只有 Host 真实提供 provider 时才加入该坐标。 */
data class ModelKey(val modelId: String, val providerId: String? = null) {
    init {
        require(modelId.isNotEmpty()) { "模型标识不能为空" }
        require(providerId == null || providerId.isNotEmpty()) { "已提供的模型 provider 标识不能为空" }
    }
}

data class SessionModelOption(val key: ModelKey, val displayName: String)

data class ReasoningEffortOption(val id: String, val displayName: String) {
    init { require(id.isNotEmpty()) { "思考强度标识不能为空" } }
}

/** 思考强度目录只属于 Host 已确认的一个模型；空列表保留真实的无选项状态。 */
class ConfirmedModelEfforts(
    val model: ModelKey,
    options: List<ReasoningEffortOption>,
    val selectedEffortId: String? = null,
) {
    val options: List<ReasoningEffortOption> = Collections.unmodifiableList(options.toList())

    init {
        require(this.options.map { it.id }.distinct().size == this.options.size) { "思考强度目录包含重复标识" }
        require(selectedEffortId == null || selectedEffortId.isNotEmpty()) { "思考强度标识不能为空" }
    }
}

/** 同一会话也可能先后请求不同模型；请求序号防止较早目录覆盖较新的确认结果。 */
data class SessionModelRequest(val owner: SessionOwner, val sequence: Long) {
    init { require(sequence >= 0) { "模型请求序号不能为负数" } }
}

/** 目录未加载与 Host 返回真实空目录是两种状态，均不生成静态模型或思考强度。 */
sealed interface SessionModelDirectory {
    val request: SessionModelRequest

    fun isCurrent(current: SessionModelRequest): Boolean = request == current

    data class NotLoaded(override val request: SessionModelRequest) : SessionModelDirectory

    class Loaded(
        override val request: SessionModelRequest,
        models: List<SessionModelOption>,
        val confirmedModel: ModelKey? = null,
        /** null 表示该模型的思考强度目录尚未确认，空 options 表示已确认没有选项。 */
        val efforts: ConfirmedModelEfforts? = null,
    ) : SessionModelDirectory {
        /** 发现层可重用其可变缓冲区，已确认目录始终保存独立快照。 */
        val models: List<SessionModelOption> = Collections.unmodifiableList(models.toList())

        init {
            require(this.models.map { it.key }.distinct().size == this.models.size) { "模型目录包含重复身份" }
            require(efforts == null || efforts.model == confirmedModel) { "思考强度不属于已确认模型" }
        }
    }
}
