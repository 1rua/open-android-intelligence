package com.openandroidintelligence.assistant

import androidx.compose.foundation.lazy.LazyListState

/**
 * 展开会话框的窗口几何与滚动策略。
 *
 * 这里只放纯计算：像素、整数与列表状态，不碰 WindowManager、不持会话、
 * 不读桥接状态。界面层负责把窗口可用高度、IME 高度与顶部安全区量出来传给
 * [calculateMaxHeight]；滚动跟随只根据 [LazyListState] 的真实滚动位置判断。
 * 这样「多高算高」「什么时候算回到最新」都能在没有真机与渲染的情况下单测。
 *
 * 约定：展开会话框的消息列表使用 `reverseLayout = true`（最新一条永远贴着输入栏），
 * 因此「最新」对应索引 0、偏移 0 —— 用户向上翻历史时索引与偏移同时增大。
 */
internal object AssistantSessionWindowPolicy {
    /** 展开高度上限占可用高度的比例（0.7）。用整数分数避免浮点误差。 */
    const val MAX_HEIGHT_NUMERATOR = 7
    const val MAX_HEIGHT_DENOMINATOR = 10

    /**
     * 「已经在最新处」允许的像素容差。
     *
     * 列表停在最新处时偏移应当是 0；留一点余量只是避免布局取整让
     * 「回到最新」按钮闪一下，不改变跟随语义。
     */
    const val LATEST_EDGE_TOLERANCE_PX = 2

    /**
     * 键盘弹出后真正可用的内容高度：可用高度减 IME 高度与顶部安全区。
     *
     * 输入框与顶部安全区可能已经占掉全部空间（例如横屏 + 大键盘），
     * 此时返回 0 而不是负数：调用方据此把面板压到最小，而不是画出负高度。
     */
    fun keyboardAvailableHeight(availableHeight: Int, imeHeight: Int, topInset: Int): Int {
        if (availableHeight <= 0) return 0
        val occupied = imeHeight.coerceAtLeast(0) + topInset.coerceAtLeast(0)
        return (availableHeight - occupied).coerceAtLeast(0)
    }

    /**
     * 展开会话框内容区的最大高度：
     * `min(可用高度 × 0.7, 可用高度 - IME - 顶部安全区)`。
     *
     * 两个上限取更紧的一个：短屏幕或大键盘时以键盘可用区为准，否则以比例上限为准，
     * 保证会话框永远不会盖住顶部安全区，也不会把输入栏挤出屏幕。
     */
    fun calculateMaxHeight(availableHeight: Int, imeHeight: Int, topInset: Int): Int {
        if (availableHeight <= 0) return 0
        val fractionCap = (availableHeight.toLong() * MAX_HEIGHT_NUMERATOR / MAX_HEIGHT_DENOMINATOR).toInt()
        val keyboardCap = keyboardAvailableHeight(availableHeight, imeHeight, topInset)
        return minOf(fractionCap, keyboardCap).coerceAtLeast(0)
    }

    /**
     * 智能跟随：列表是否仍停在最新一条。
     *
     * 列表反向布局时索引 0 是最新消息；偏移趋近 0 表示没有把最新内容推出视口。
     * 用户一旦上滑查看历史（索引或偏移变大）就返回 false，宿主据此停止强制跳底，
     * 让流式输出不会把用户正在读的内容拽走。
     */
    fun shouldFollowLatest(listState: LazyListState): Boolean = shouldFollowLatest(
        firstVisibleItemIndex = listState.firstVisibleItemIndex,
        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
    )

    /** [shouldFollowLatest] 的纯整数形式：不依赖 Compose 的布局对象，可直接断言。 */
    fun shouldFollowLatest(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Boolean =
        firstVisibleItemIndex <= 0 &&
            firstVisibleItemScrollOffset <= LATEST_EDGE_TOLERANCE_PX
}
