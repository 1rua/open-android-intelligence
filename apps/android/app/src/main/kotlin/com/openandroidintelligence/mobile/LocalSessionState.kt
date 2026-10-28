package com.openandroidintelligence.mobile

/**
 * 本地会话权威快照，统一发布不可变的本地展示事实，避免 UI 拼接多个独立流猜测身份。
 */
sealed interface LocalSessionState {
    /** 正在读取本地安全存储中的凭据与镜像，展示骨架或启动表面，不闪现登录表单 */
    object LoadingLocal : LocalSessionState

    /** 明确处于未登录状态或本地登出完成，展示登录/连接表单 */
    data class SignedOut(
        val lastEndpoint: String? = null,
        val lastUsername: String? = null,
        val reason: String? = null
    ) : LocalSessionState

    /** 本地账号选择器状态，允许在保存的多个合法账号之间切换 */
    data class AccountPicker(
        val accounts: List<AccountProfile>,
        val currentProfileId: String? = null
    ) : LocalSessionState

    /**
     * 已验证本地上下文并拥有恢复许可，已进入工作台。
     * 可以离线渲染本地镜像，或在后台进行网络协商/重连。
     */
    data class Workspace(
        val account: AccountProfile,
        val connectionPhase: ConnectionPhase = ConnectionPhase.Disconnected
    ) : LocalSessionState

    /** 明确被服务端拒绝、凭据损坏或密钥不可用，需要重新认证 */
    data class Locked(
        val account: AccountProfile?,
        val reason: String
    ) : LocalSessionState
}
