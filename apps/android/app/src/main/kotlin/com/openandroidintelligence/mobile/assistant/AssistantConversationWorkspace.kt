package com.openandroidintelligence.mobile.assistant

import com.openandroidintelligence.conversation.state.WorkbenchController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns assistant controllers independently of the main window's selection and drafts.
 *
 * 一个工作区只属于一个 (profileId, accountId, agentId) 身份；身份之内再用
 * sessionToken 区分每次助理会话。账号或 Agent 切换时宿主调用 [closeAndAwait]，
 * 旧身份的发送任务不会被带进新工作区。
 *
 * [busyThreadIds] 刻意不加锁：工作台在投影状态时会同步读取它，若这里再取
 * 工作区监视器，就会与关闭路径形成锁顺序反转。
 */
internal class AssistantConversationWorkspace(
    internal val profileId: String,
    internal val accountId: String,
    internal val agentId: String?,
    private val scope: CoroutineScope,
    private val create: (String) -> WorkbenchController?,
    /** 会话真正关闭时回调；宿主借此释放该会话专用的仓储与事件订阅。 */
    private val onSessionReleased: (String) -> Unit = {},
) : AutoCloseable {
    @Volatile private var closed = false
    private val controllers = ConcurrentHashMap<String, WorkbenchController>()
    /** 界面已释放但仍有进程内发送的会话；终态到达后才真正关闭。 */
    private val detached = ConcurrentHashMap.newKeySet<String>()
    private val observers = ConcurrentHashMap<String, Job>()
    private val _hasActiveWork = MutableStateFlow(false)

    /** 是否有助手会话仍在等待真实终态；账号/Agent 切换与后台保活都读取它。 */
    val hasActiveWork: StateFlow<Boolean> = _hasActiveWork.asStateFlow()

    /** 判断本工作区是否仍属于当前连接与 Agent，避免旧身份借到新会话。 */
    fun matches(profileId: String, accountId: String, agentId: String?): Boolean =
        this.profileId == profileId && this.accountId == accountId && this.agentId == agentId

    /**
     * Returns the controller owned by [sessionToken], creating it on first use.
     *
     * Repeating `open` for the same token is idempotent: an IPC reconnect must not
     * replace a controller whose send is still owned by this session.
     */
    @Synchronized fun open(sessionToken: String): WorkbenchController {
        check(!closed) { "ASSISTANT_WORKSPACE_CLOSED" }
        check(sessionToken.isNotBlank()) { "ASSISTANT_SESSION_TOKEN_REQUIRED" }
        controllers[sessionToken]?.let { return it }
        val controller = checkNotNull(create(sessionToken)) { "ASSISTANT_SESSION_UNAVAILABLE" }
        controllers[sessionToken] = controller
        observers[sessionToken] = scope.launch {
            controller.hasActiveWork.collect {
                publishActiveWork()
                closeIfDetachedIdle(sessionToken, controller)
            }
        }
        publishActiveWork()
        return controller
    }

    /**
     * Releases the UI's claim on a session.
     *
     * A controller with live work stays open until the Gateway reports its terminal
     * status: closing it here would cancel a POST the process still owns. Releasing
     * an unknown or already closed token is a no-op.
     */
    @Synchronized fun release(sessionToken: String) {
        val controller = controllers[sessionToken] ?: return
        if (controller.hasActiveWork.value) {
            detached += sessionToken
            return
        }
        closeOwned(sessionToken, controller)
    }

    /** Conversations this workspace is currently sending to; hosts exclude them from management. */
    fun busyThreadIds(): Set<String> =
        controllers.values.flatMap { it.ownedBusyThreadIds }.toSet()

    /** 指定会话 token 当前展示的线程；没有该会话时为 null。 */
    fun conversationFor(sessionToken: String): String? =
        controllers[sessionToken]?.state?.value?.activeThreadId

    @Synchronized override fun close() {
        closed = true
        val owned = controllers.toMap()
        val jobs = observers.values.toList()
        controllers.clear()
        detached.clear()
        observers.clear()
        jobs.forEach(Job::cancel)
        owned.values.forEach(WorkbenchController::close)
        owned.keys.forEach { token -> runCatching { onSessionReleased(token) } }
        publishActiveWork()
    }

    /**
     * 账号/Agent 切换时关闭整个工作区。
     *
     * 与 [close] 不同，这里等待每个控制器把本地提交收尾；切换期间调用方被取消
     * 也不能让旧会话的关闭半途而废，因此收尾阶段使用 [NonCancellable]。
     */
    suspend fun closeAndAwait() {
        val (owned, jobs) = synchronized(this) {
            if (closed && controllers.isEmpty()) return
            closed = true
            val snapshot = controllers.toMap() to observers.values.toList()
            controllers.clear()
            detached.clear()
            observers.clear()
            snapshot
        }
        jobs.forEach(Job::cancel)
        withContext(NonCancellable) {
            owned.forEach { (token, controller) ->
                try {
                    controller.closeAndAwait()
                } catch (_: Throwable) {
                    // 收尾失败仍要释放；同步 close 至少停止事件订阅与后台任务。
                    runCatching { controller.close() }
                }
                runCatching { onSessionReleased(token) }
            }
        }
        publishActiveWork()
    }

    @Synchronized private fun closeIfDetachedIdle(sessionToken: String, controller: WorkbenchController) {
        if (closed || sessionToken !in detached || controller.hasActiveWork.value) return
        closeOwned(sessionToken, controller)
    }

    private fun closeOwned(sessionToken: String, controller: WorkbenchController) {
        controllers.remove(sessionToken)
        detached -= sessionToken
        observers.remove(sessionToken)?.cancel()
        controller.close()
        runCatching { onSessionReleased(sessionToken) }
        publishActiveWork()
    }

    private fun publishActiveWork() {
        _hasActiveWork.value = controllers.values.any { it.hasActiveWork.value }
    }
}
