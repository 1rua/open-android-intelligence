package com.openandroidintelligence.assistant

import android.os.Bundle
import android.graphics.Bitmap
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.widget.*

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)
}

internal class AssistantSession(service: AssistantSessionService) : VoiceInteractionSession(service) {
    private var panel: AssistantPanel? = null
    private var screenshot: Bitmap? = null
    private var selection: AssistSelectionView? = null
    private var container:FrameLayout?=null
    private var bubble:Button?=null
    private var docked=false
    override fun onCreateContentView(): View {
        val container=FrameLayout(context);this.container=container
        val ui=AssistantPanel(context,{ hide() },pickScreen={
            val source=screenshot
            if (source == null) { Toast.makeText(context,"系统没有提供截图，请重新唤起助理",Toast.LENGTH_LONG).show() }
            else {
                val view=AssistSelectionView(context,source); selection=view
                val box=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; addView(view,LinearLayout.LayoutParams(-1,0,1f)) }
                box.addView(Button(context).apply { text="确认圈选"; setOnClickListener {
                    val crop=view.crop()
                    if (crop == null) Toast.makeText(context,"请圈出需要的区域",Toast.LENGTH_SHORT).show()
                    else {
                        val target = panel
                        if (target != null) target.attachScreen(crop) else crop.recycle()
                        container.removeView(box); selection=null; screenshot?.recycle(); screenshot=null
                    }
                } })
                box.addView(Button(context).apply { text="取消"; setOnClickListener { container.removeView(box); selection=null } })
                container.addView(box,FrameLayout.LayoutParams(-1,-1))
            }
        },dockPanel={ setDocked(true) }); panel=ui; container.addView(ui,FrameLayout.LayoutParams(-1,-1))
        bubble=Button(context).apply { text="助理";contentDescription="展开助理对话";visibility=View.GONE;setOnClickListener { setDocked(false) } }
        container.addView(bubble,FrameLayout.LayoutParams(-1,-1)); return container
    }
    private fun setDocked(value:Boolean) {
        docked=value;panel?.visibility=if(value) View.GONE else View.VISIBLE;bubble?.visibility=if(value) View.VISIBLE else View.GONE
        if(value) { screenshot?.recycle();screenshot=null;selection=null }
        window?.window?.apply { setGravity(if(value) android.view.Gravity.END or android.view.Gravity.BOTTOM else android.view.Gravity.BOTTOM)
            setLayout(if(value) (72*context.resources.displayMetrics.density).toInt() else android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                if(value) (72*context.resources.displayMetrics.density).toInt() else (420*context.resources.displayMetrics.density).toInt()) }
    }
    override fun onShow(args: Bundle?, showFlags: Int) { super.onShow(args,showFlags); if (panel == null) setContentView(onCreateContentView());setDocked(false) }
    override fun onHandleScreenshot(bitmap: Bitmap?) {
        screenshot?.recycle(); screenshot=null
        if (bitmap != null && bitmap.width.toLong()*bitmap.height <= 4_000_000) screenshot=bitmap.copy(Bitmap.Config.ARGB_8888,false)
    }
    override fun onHide() { panel?.dispose(); panel=null; selection=null;container=null;bubble=null;docked=false;screenshot?.recycle(); screenshot=null; super.onHide() }
    override fun onDestroy() { panel?.dispose(); panel=null; screenshot?.recycle(); screenshot=null; super.onDestroy() }
}
