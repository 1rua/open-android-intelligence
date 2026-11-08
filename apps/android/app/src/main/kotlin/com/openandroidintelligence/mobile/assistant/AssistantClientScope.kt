package com.openandroidintelligence.mobile.assistant

/**
 * 每个 IPC 客户端只跟随一个已连接的账号：账号可用即绑定，不再有独立的 consent 步骤。
 *
 * 跨账号共享必须失败关闭——账号切换、断连或任何不匹配都会清掉已绑定状态，
 * 直到重新 [bind] 到当前可用账号为止。
 */
internal class AssistantClientScope(initialAccountId: String?) {
    var accountId: String? = initialAccountId
        private set
    private var attached = false

    /** 当前存在已连接账号时绑定到它；没有可用账号时保持失败关闭。 */
    fun bind(connectedAccountId: String?): Boolean {
        if (connectedAccountId == null) {
            attached = false
            return false
        }
        accountId = connectedAccountId
        attached = true
        return true
    }

    /** 只有已绑定且账号仍与当前连接一致时才允许读写；任何不匹配立即失效。 */
    fun canShare(connectedAccountId: String?): Boolean {
        if (connectedAccountId == null || accountId != connectedAccountId) {
            attached = false
            return false
        }
        return attached
    }

    /** 已绑定的客户端遇到账号切换；调用方应据此关闭旧工作区再重新绑定。 */
    fun accountChanged(connectedAccountId: String?): Boolean =
        attached && accountId != connectedAccountId
}
