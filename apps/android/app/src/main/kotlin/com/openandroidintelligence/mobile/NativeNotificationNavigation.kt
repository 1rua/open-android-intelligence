package com.openandroidintelligence.mobile

/**
 * 点开一条原生提醒时，主界面是否还要等一次工作台交接。
 *
 * `selectedAgentId` 在用户点选 Agent 的瞬间就同步更新，而新工作台要等旧分区
 * 的 IO 收尾之后才发布。只看“已经选中这台 Agent”会把会话开在旧工作台上，
 * 并让调用方误以为导航成功、把这条通知清掉。只有真正发布出来的工作台所属的
 * Agent 才能证明交接完成。
 */
internal fun nativeNavigationWaitsForWorkbench(
    targetAgentId: String,
    selectedAgentId: String?,
    publishedWorkbenchAgentId: String?,
): Boolean = selectedAgentId != targetAgentId || publishedWorkbenchAgentId != targetAgentId
