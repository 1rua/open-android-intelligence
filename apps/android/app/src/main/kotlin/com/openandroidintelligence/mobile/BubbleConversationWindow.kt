package com.openandroidintelligence.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openandroidintelligence.conversation.state.WorkbenchController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A bubble's draft and pending picker destination survive Activity recreation. */
internal class BubbleConversationWindow(private val savedState: SavedStateHandle) : ViewModel() {
    var selectedConversationId by mutableStateOf(savedState.get<String>(SELECTED))
        private set
    var controller by mutableStateOf<WorkbenchController?>(null)
        private set
    var creatingThread by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    private var owner by mutableStateOf<Any?>(null)
    private var launchTarget by mutableStateOf<ReplyNotificationTarget?>(null)
    /**
     * 通知指向的会话还没有被打开过。
     *
     * 原生气泡可能在账号恢复或 Agent 发现完成之前就先绑定一次，那一次拿不到
     * session，控制器也建不出来。这个标记必须留到真正可用的那次绑定再消费：
     * 只按“是不是首次绑定”判断，会让之后每次绑定都看到“不是首次”，气泡就
     * 永远停在会话列表的转圈上。标记落在 saved state 里，进程重建也不丢。
     */
    private var launchSelectionPending: Boolean
        get() = savedState.get<Boolean>(LAUNCH_PENDING) == true
        set(value) { savedState[LAUNCH_PENDING] = value }
    private var open: (ReplyNotificationTarget) -> WorkbenchController? = { null }
    private var release: (WorkbenchController) -> Unit = {}
    private var creationJob: Job? = null
    private var bindingRevision = 0L

    /**
     * [session] 只用来识别“会话身份是否换了”。
     *
     * 平台旧链路传主账号工作台实例；原生会话传目标本身——气泡自己持有独立
     * 控制器，不能因为主界面换了 Agent 就丢掉正在写的气泡草稿。
     */
    fun bind(
        session: Any?,
        target: ReplyNotificationTarget?,
        open: (ReplyNotificationTarget) -> WorkbenchController?,
        release: (WorkbenchController) -> Unit,
    ) {
        val routeChanged = savedState.get<String>(PROFILE) != target?.profileId ||
            savedState.get<String>(ACCOUNT) != target?.accountId ||
            savedState.get<String>(CONVERSATION) != target?.conversationId ||
            savedState.get<String>(AGENT) != target?.agentId
        // 用值相等而不是引用相等：旋转后重建的窗口拿到的是同一个目标的新实例，
        // 草稿与上传必须保留；旧链路的控制器没有自定义 equals，行为不变。
        if (routeChanged || this.owner != session) {
            bindingRevision++
            creationJob?.cancel()
            creationJob = null
            creatingThread = false
            notice = null
            releaseController()
            if (routeChanged) selectId(null)
        }
        // 原生目标换了（首次打开或气泡内又点了另一条通知）：这条通知的会话还没被打开。
        if (routeChanged && target?.agentId != null) launchSelectionPending = true
        savedState[PROFILE] = target?.profileId
        savedState[ACCOUNT] = target?.accountId
        savedState[CONVERSATION] = target?.conversationId
        savedState[AGENT] = target?.agentId
        this.owner = session
        launchTarget = target
        this.open = open
        this.release = release
        // 原生气泡需要自己的控制器才能读取会话列表；通知指向的会话在拿到 session
        // 的第一次绑定就直接进入。之后用户回到列表是明确的导航选择，不会再被绑回聊天页。
        if (session != null && target?.agentId != null && launchSelectionPending) {
            launchSelectionPending = false
            selectId(target.conversationId)
        }
        selectedConversationId?.let { if (session != null) openSelected(it) }
    }

    fun isBoundTo(session: Any?, target: ReplyNotificationTarget?): Boolean =
        this.owner == session && launchTarget == target

    fun showBrowser() = selectId(null)

    fun select(conversationId: String) {
        if (owner == null || conversationId.isBlank()) return
        notice = null
        selectId(conversationId)
        openSelected(conversationId)
    }

    fun createThread(create: suspend (ReplyNotificationTarget) -> ReplyNotificationTarget) {
        val target = launchTarget ?: return
        val session = owner ?: return
        if (creatingThread) return
        val revision = bindingRevision
        creatingThread = true
        notice = null
        creationJob = viewModelScope.launch {
            try {
                val created = create(target)
                if (bindingRevision == revision && owner === session && launchTarget == target) {
                    select(created.conversationId)
                    controller?.refreshThreads()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (bindingRevision == revision) notice = "新建对话失败，请重试"
            } finally {
                if (bindingRevision == revision) creatingThread = false
            }
        }
    }

    private fun selectId(id: String?) {
        selectedConversationId = id
        savedState[SELECTED] = id
    }

    private fun openSelected(conversationId: String) {
        val current = controller
        if (current?.state?.value?.isOnline == true && current.state.value.activeThreadId == conversationId) return
        releaseController()
        val target = launchTarget ?: return
        controller = open(target.copy(conversationId = conversationId))
    }

    private fun releaseController() {
        controller?.let(release)
        controller = null
    }

    override fun onCleared() {
        releaseController()
    }

    private companion object {
        const val SELECTED = "bubbleSelectedConversation"
        const val PROFILE = "bubbleLaunchProfile"
        const val ACCOUNT = "bubbleLaunchAccount"
        const val CONVERSATION = "bubbleLaunchConversation"
        const val AGENT = "bubbleLaunchAgent"
        const val LAUNCH_PENDING = "bubbleLaunchSelectionPending"
    }
}
