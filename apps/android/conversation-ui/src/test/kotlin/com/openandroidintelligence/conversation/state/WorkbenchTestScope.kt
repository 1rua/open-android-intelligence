package com.openandroidintelligence.conversation.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope

/** 主测试调度器驱动行为，后台生命周期在每个用例结束时统一取消工作台。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.workbenchTestScope():CoroutineScope = CoroutineScope(
    coroutineContext + requireNotNull(backgroundScope.coroutineContext[Job]),
)
