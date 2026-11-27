package com.openandroidintelligence.assistant

/**
 * 一次助理展示期间对桥接客户端的生命周期动作。
 *
 * 系统助理会话与 ASSIST 兜底页是两套外壳，但语义必须完全一致：
 * - **进入展示**：绑定主进程，并声明「界面正在展示当前会话」。主进程据此抑制
 *   同会话的原生通知气泡，避免界面、自绘小球和原生气泡重复提醒。
 * - **退出展示**：**先**撤销可见性，**再**断开连接——断开后就没有通道可发了。
 *   只发 `disconnect`，绝不发 `cancel`：已提交给 Gateway 的生成任务继续在
 *   主进程里跑，用户回到 App 仍能看到结果。
 *
 * 每一条命令都有明确的先后顺序，所以集中在这里而不是散落在两个 Android
 * 生命周期回调里；测试只需要断言这一个序列。
 */
internal object AssistantSessionLifecycle {

    /** 进入展示：绑定 + 声明可见。未真正连上时由客户端记住意图，首帧后补发。 */
    fun enter(client: AssistantBridgeClient) {
        client.connect()
        client.setPresence(true)
    }

    /** 退出展示：先撤销可见，再断开；不取消生成。 */
    fun leave(client: AssistantBridgeClient) {
        client.setPresence(false)
        client.dispose()
    }
}
