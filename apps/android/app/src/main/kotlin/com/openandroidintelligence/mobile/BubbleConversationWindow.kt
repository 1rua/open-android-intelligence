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

    private var owner by mutableStateOf<WorkbenchController?>(null)
    private var launchTarget by mutableStateOf<ReplyNotificationTarget?>(null)
    private var open: (ReplyNotificationTarget) -> WorkbenchController? = { null }
    private var release: (WorkbenchController) -> Unit = {}
    private var creationJob: Job? = null
    private var bindingRevision = 0L

    fun bind(
        owner: WorkbenchController?,
        target: ReplyNotificationTarget?,
        open: (ReplyNotificationTarget) -> WorkbenchController?,
        release: (WorkbenchController) -> Unit,
    ) {
        val routeChanged = savedState.get<String>(PROFILE) != target?.profileId ||
            savedState.get<String>(ACCOUNT) != target?.accountId ||
            savedState.get<String>(CONVERSATION) != target?.conversationId
        if (routeChanged || this.owner !== owner) {
            bindingRevision++
            creationJob?.cancel()
            creationJob = null
            creatingThread = false
            notice = null
            releaseController()
            if (routeChanged) selectId(null)
        }
        savedState[PROFILE] = target?.profileId
        savedState[ACCOUNT] = target?.accountId
        savedState[CONVERSATION] = target?.conversationId
        this.owner = owner
        launchTarget = target
        this.open = open
        this.release = release
        selectedConversationId?.let { if (owner != null) openSelected(it) }
    }

    fun isBoundTo(owner: WorkbenchController?, target: ReplyNotificationTarget?): Boolean =
        this.owner === owner && launchTarget == target

    fun showBrowser() = selectId(null)

    fun select(conversationId: String) {
        if (owner == null || conversationId.isBlank()) return
        notice = null
        selectId(conversationId)
        openSelected(conversationId)
    }

    fun createThread(create: suspend (ReplyNotificationTarget) -> ReplyNotificationTarget) {
        val target = launchTarget ?: return
        val history = owner ?: return
        if (creatingThread) return
        val revision = bindingRevision
        creatingThread = true
        notice = null
        creationJob = viewModelScope.launch {
            try {
                val created = create(target)
                if (bindingRevision == revision && owner === history && launchTarget == target) {
                    select(created.conversationId)
                    history.refreshThreads()
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
    }
}
