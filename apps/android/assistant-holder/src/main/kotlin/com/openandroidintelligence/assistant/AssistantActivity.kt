package com.openandroidintelligence.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Fallback ASSIST entry shares the same main-process controller through the private bridge. */
class AssistantActivity : Activity() {
    private var panel: AssistantPanel? = null
    override fun onCreate(state: Bundle?) { super.onCreate(state); show(intent) }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); if (intent != null) show(intent) }
    private fun show(intent: Intent) { panel?.dispose(); panel=AssistantPanel(this,{ finish() },initialText=intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(50_000)); setContentView(panel) }
    override fun onDestroy() { panel?.dispose(); panel=null; super.onDestroy() }
}
