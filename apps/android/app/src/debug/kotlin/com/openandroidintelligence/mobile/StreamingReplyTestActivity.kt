package com.openandroidintelligence.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity

/** 空白的调试测试宿主，不创建工作台、账号或连接运行时。 */
class StreamingReplyTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installContent?.invoke(this)
    }

    companion object {
        /** 测试在启动前注入，旋转重建后仍使用同一份接收事实。 */
        @Volatile var installContent: ((StreamingReplyTestActivity) -> Unit)? = null
    }
}
