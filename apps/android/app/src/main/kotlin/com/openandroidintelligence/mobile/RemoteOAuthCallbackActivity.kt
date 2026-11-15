package com.openandroidintelligence.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity

/** 只处理本机已发起且尚未消费的官方回调，不把 token 再转发给工作台 Intent。 */
class RemoteOAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callback = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
        setIntent(Intent())
        val app = application as OpenAndroidIntelligenceApplication
        if (callback != null && app.remoteRuntime.loginEntryBridge.handleOAuthCallback(callback)) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}

/** 相机画面含一次性配对秘密，禁止截屏及最近任务预览。 */
class RemotePairingCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }
}
