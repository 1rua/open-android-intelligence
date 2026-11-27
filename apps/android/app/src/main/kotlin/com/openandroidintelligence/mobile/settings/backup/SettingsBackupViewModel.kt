package com.openandroidintelligence.mobile.settings.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一次性结果提示；[id] 让同一段文字重复出现时也能被消费两次。 */
data class BackupNotice(
    val id: Long,
    val message: String,
    val isError: Boolean,
    val formatFailure: SettingsBackupFormatException? = null,
    val failureOperation: BackupFailureOperation? = null,
)

enum class BackupFailureOperation { INSPECT, IMPORT }

/** 通过校验、等待用户确认的导入。 */
data class PendingImport(
    val text: String,
    val summaries: List<String>,
    val ignoredFields: List<String>,
    val exportedAt: String,
    val appVersionName: String,
    val localVersionName: String,
)

data class SettingsBackupUiState(
    val busy: Boolean = false,
    val isPreparing: Boolean = true,
    val exportSummary: List<String> = emptyList(),
    val pendingImport: PendingImport? = null,
    val notice: BackupNotice? = null,
    /** 环境缺失（例如运行时不完整）时的如实说明；非空时导出与导入都不可用。 */
    val unavailableReason: String? = null,
)

/**
 * 备份与恢复页面的状态持有者。
 *
 * 文件与偏好读写全部在 IO 调度器上完成；界面只拿不可变状态与明确回调。
 * 导入拆成「先校验出摘要 → 用户确认 → 再写入」两段，确认框看到的摘要就是
 * 真正会写入的内容，不存在「确认的和执行的不是同一份数据」。
 */
class SettingsBackupViewModel(
    private val coordinator: SettingsBackupCoordinator?,
    private val localVersionName: String,
    private val unavailableReason: String? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsBackupUiState(
            isPreparing = coordinator != null,
            unavailableReason = if (coordinator == null) unavailableReason ?: "当前环境无法读取设置。" else null,
        ),
    )
    val state: StateFlow<SettingsBackupUiState> = _state.asStateFlow()

    private var noticeSeq = 0L

    fun prepare() {
        val owner = coordinator ?: return
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) { runCatching { owner.exportSummary() } }
            _state.value = _state.value.copy(
                isPreparing = false,
                exportSummary = summary.getOrDefault(emptyList()),
                notice = summary.exceptionOrNull()?.let { failure ->
                    notice("无法读取本机设置摘要：${failure.message ?: "请重试。"}", isError = true)
                } ?: _state.value.notice,
            )
        }
    }

    /** 导出到用户选定的位置。写失败必须如实告知，不能提示「已保存」。 */
    fun exportTo(uri: Uri, open: (Uri) -> java.io.OutputStream?) {
        val owner = coordinator ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val export = owner.export()
                    val stream = open(uri) ?: error("无法写入所选位置。")
                    stream.use { it.write(export.json.toByteArray(Charsets.UTF_8)) }
                    export.suggestedFileName
                }
            }
            _state.value = _state.value.copy(
                busy = false,
                notice = result.fold(
                    onSuccess = { name -> notice("设置已导出（$name）。") },
                    onFailure = { notice("导出失败：${it.message ?: "请重试。"}", isError = true) },
                ),
            )
        }
    }

    /** 读取并校验所选文件；通过校验才进入待确认状态。 */
    fun inspect(uri: Uri, read: (Uri) -> String?) {
        val owner = coordinator ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, pendingImport = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = read(uri) ?: error("无法读取所选文件。")
                    val preview = owner.preview(text)
                    PendingImport(
                        text = text,
                        summaries = preview.summaries,
                        ignoredFields = preview.ignoredFields,
                        exportedAt = preview.exportedAt,
                        appVersionName = preview.appVersionName,
                        localVersionName = localVersionName,
                    )
                }
            }
            _state.value = _state.value.copy(
                busy = false,
                pendingImport = result.getOrNull(),
                notice = result.exceptionOrNull()?.let {
                    notice(
                        "无法导入：${it.message ?: "文件格式不正确。"}", isError = true,
                        failure = it, operation = BackupFailureOperation.INSPECT,
                    )
                } ?: _state.value.notice,
            )
        }
    }

    fun dismissPending() {
        _state.value = _state.value.copy(pendingImport = null)
    }

    /**
     * 执行导入；结束后重新读出摘要，界面展示的是写入后的真实状态。
     *
     * 结果留在页面上（可滚动、可逐行阅读），而不是只闪一次 Snackbar：跳过项与
     * 「需要重新连接才生效」这类信息，用户需要有时间读完并照着做。
     */
    fun confirmImport() {
        val owner = coordinator ?: return
        val pending = _state.value.pendingImport ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, pendingImport = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { owner.import(pending.text) }
            val refreshed = withContext(Dispatchers.IO) {
                runCatching { owner.exportSummary() }.getOrDefault(emptyList())
            }
            val message = result.fold(
                onSuccess = { outcome ->
                    buildList {
                        add("已恢复 ${outcome.appliedSummaries.size} 项设置。")
                        addAll(outcome.skipped)
                        if (outcome.ignoredFields.isNotEmpty()) {
                            add("已忽略 ${outcome.ignoredFields.size} 个本版本不认识的字段。")
                        }
                        addAll(outcome.deferredNotices)
                    }.joinToString("\n")
                },
                onFailure = { "导入失败：${it.message ?: "请检查文件后重试。"}" },
            )
            _state.value = _state.value.copy(
                busy = false,
                exportSummary = refreshed,
                notice = notice(
                    message, isError = result.isFailure,
                    failure = result.exceptionOrNull(), operation = BackupFailureOperation.IMPORT,
                ),
            )
        }
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    private fun notice(
        message: String,
        isError: Boolean = false,
        failure: Throwable? = null,
        operation: BackupFailureOperation? = null,
    ) = BackupNotice(++noticeSeq, message, isError, failure as? SettingsBackupFormatException, operation)
}
