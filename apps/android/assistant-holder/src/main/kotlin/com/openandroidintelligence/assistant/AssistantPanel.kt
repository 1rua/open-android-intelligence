package com.openandroidintelligence.assistant

import android.content.*
import android.graphics.*
import android.os.*
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import org.json.JSONObject

/** This process is a presentation client. The main process owns the only runtime and conversation state. */
internal class AssistantPanel(context: Context, private val close: () -> Unit,
    private val pickScreen: (() -> Unit)? = null, initialText: String? = null, private val dockPanel:(()->Unit)?=null) : LinearLayout(context) {
    private var bridge: Messenger? = null
    private var token: String? = null
    private var updating = false
    private var bound = false
    private val status = TextView(context)
    private val history = TextView(context)
    private val composer = EditText(context).apply { hint="输入消息"; contentDescription="助理消息输入"; maxLines=4 }
    private val consent = Button(context).apply { text="绑定当前 Gateway 对话" }
    private val send = Button(context).apply { text="发送"; isEnabled=false }
    private val threads = Button(context).apply { text="切换对话"; isEnabled=false }
    private val selectedText = initialText?.takeIf { it.isNotBlank() }?.let { text ->
        Button(context).apply {
            this.text="使用所选文字"; visibility=GONE
            setOnClickListener { command("draft") { putString("text",text.take(50_000)) } }
        }
    }
    private var threadIds = listOf<Pair<String,String>>()
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.data.getInt("version") != 1) return@Handler true
        token = message.data.getString("token")
        val state = runCatching { JSONObject(message.data.getString("state") ?: "{}") }.getOrNull() ?: return@Handler true
        val connected = state.optBoolean("connected")
        val allowed = connected && state.optBoolean("consent")
        consent.visibility=if (allowed) GONE else VISIBLE
        consent.isEnabled=connected
        status.text= if (!connected) "请在 App 中连接 Gateway，然后重新打开助理" else state.optString("title").ifBlank { "选择当前对话" } + "\n" + state.optString("notice","").takeIf { it != "null" }.orEmpty()
        send.isEnabled=allowed; threads.isEnabled=allowed; composer.isEnabled=allowed
        selectedText?.visibility=if (allowed) VISIBLE else GONE
        val entries=state.optJSONArray("messages")
        history.text=(0 until (entries?.length() ?: 0)).joinToString("\n\n") { i ->
            val row=entries!!.getJSONObject(i)
            (if (row.optString("sender") == "user") "你：" else "助理：") + row.optString("text") +
                (row.optJSONArray("attachments")?.let { a -> (0 until a.length()).joinToString("\n",prefix="\n") { a.getString(it) } } ?: "")
        }
        val list=state.optJSONArray("threads")
        threadIds=(0 until (list?.length() ?: 0)).map { i -> val row=list!!.getJSONObject(i); row.getString("id") to row.getString("title") }
        val draft=state.optString("draft","")
        if (allowed && composer.text.toString() != draft) { updating=true; composer.setText(draft); composer.setSelection(composer.length()); updating=false }
        true
    })
    private val connection=object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { bridge=Messenger(service); command("connect") }
        override fun onServiceDisconnected(name: ComponentName?) { bridge=null; token=null; send.isEnabled=false; status.text="助理连接已中断，请重新打开" }
        override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
    }
    init {
        orientation=VERTICAL; setPadding(24,16,24,24); setBackgroundColor(Color.rgb(245,247,251))
        addView(status)
        addView(consent)
        val scroll=ScrollView(context).apply { addView(history.apply { setPadding(8,16,8,16); textSize=16f }) }
        addView(scroll,LayoutParams(LayoutParams.MATCH_PARENT,0,1f))
        addView(composer)
        val buttons=LinearLayout(context).apply { orientation=HORIZONTAL }
        buttons.addView(threads); buttons.addView(send)
        buttons.addView(Button(context).apply { text="停止"; setOnClickListener { command("cancel") } })
        addView(buttons)
        val dock=LinearLayout(context).apply { orientation=HORIZONTAL }
        dock.addView(Button(context).apply { text="打开 App"; setOnClickListener {
            context.startActivity(Intent().setClassName(context.packageName,"com.openandroidintelligence.mobile.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)); close()
        } })
        if (pickScreen != null) dock.addView(Button(context).apply { text="屏幕圈选"; setOnClickListener { pickScreen.invoke() } })
        if (dockPanel != null) dock.addView(Button(context).apply { text="收起到侧边"; setOnClickListener { dockPanel.invoke() } })
        dock.addView(Button(context).apply { text="关闭"; setOnClickListener { close() } }); addView(dock)
        selectedText?.let(::addView)
        consent.setOnClickListener { android.app.AlertDialog.Builder(context).setTitle("绑定当前 Gateway")
            .setMessage("助理将显示当前账号的对话。消息和屏幕内容仅在你点击发送后提交。")
            .setPositiveButton("允许") { _,_ -> command("consent") }.setNegativeButton("取消",null).show() }
        send.setOnClickListener { command("send") }
        threads.setOnClickListener { android.app.AlertDialog.Builder(context).setTitle("选择对话").setItems(threadIds.map { it.second }.toTypedArray()) { _, index -> command("thread") { putString("threadId",threadIds[index].first) } }.show() }
        composer.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { if (!updating) command("draft") { putString("text",s.toString().take(50_000)) } }
            override fun afterTextChanged(s:Editable?) {}
        })
        bound=context.bindService(Intent().setClassName(context.packageName,"com.openandroidintelligence.mobile.assistant.AssistantBridgeService"),connection,Context.BIND_AUTO_CREATE)
        if (!bound) status.text="助理服务无法连接，请打开 App"
    }
    private fun command(name:String,fill:Bundle.()->Unit = {}):Boolean {
        val body=Bundle().apply { putInt("version",1); putString("command",name); token?.let { putString("token",it) }; fill() }
        return runCatching { val target=bridge ?: error("BRIDGE_UNAVAILABLE");target.send(Message.obtain().apply { data=body; replyTo=receiver });true }.getOrElse { status.text="连接已中断，请重新打开助理";false }
    }
    fun attachScreen(bitmap: Bitmap) {
        val output=java.io.ByteArrayOutputStream(); if (!bitmap.compress(Bitmap.CompressFormat.PNG,100,output)) return
        val bytes=output.toByteArray(); if (bytes.size > 8*1024*1024) { bytes.fill(0); Toast.makeText(context,"圈选图片过大，请缩小范围",Toast.LENGTH_LONG).show();return }
        val pipe=ParcelFileDescriptor.createPipe()
        if (!command("screen") { putParcelable("content",pipe[0]); putInt("size",bytes.size) }) { pipe.forEach { it.close() };bytes.fill(0);return }
        val timeout=Runnable { runCatching { pipe[1].close() } }
        val watchdogHandler=Handler(Looper.getMainLooper());watchdogHandler.postDelayed(timeout,10_000)
        Thread {
            try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } }
            catch (_:Exception) {} finally { watchdogHandler.removeCallbacks(timeout);bytes.fill(0); pipe[0].close() }
        }.start()
    }
    fun dispose() { command("disconnect"); bridge=null; token=null; if (bound) { context.unbindService(connection); bound=false }; history.text=""; updating=true; composer.setText("") }
}

/** Assist screenshot is memory-only. Selection and confirmation are separate user actions. */
internal class AssistSelectionView(context: Context, private val source: Bitmap) : View(context) {
    private val path=Path(); private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.CYAN; style=Paint.Style.STROKE; strokeWidth=4f }
    private val points=mutableListOf<PointF>()
    override fun onDraw(canvas: Canvas) { canvas.drawBitmap(source,null,Rect(0,0,width,height),null); canvas.drawPath(path,paint) }
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val x=event.x.coerceIn(0f,width.toFloat()); val y=event.y.coerceIn(0f,height.toFloat())
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) { points.clear(); path.reset(); path.moveTo(x,y) }
        if (points.size < 4096) points.add(PointF(x,y))
        if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE) path.lineTo(x,y)
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP) path.close()
        invalidate(); return true
    }
    fun crop(): Bitmap? {
        if (points.size < 3 || width == 0 || height == 0) return null
        val x=source.width.toFloat()/width; val y=source.height.toFloat()/height
        val left=(points.minOf { it.x }*x).toInt().coerceIn(0,source.width-1); val top=(points.minOf { it.y }*y).toInt().coerceIn(0,source.height-1)
        val right=kotlin.math.ceil(points.maxOf { it.x }*x).toInt().coerceIn(left+1,source.width); val bottom=kotlin.math.ceil(points.maxOf { it.y }*y).toInt().coerceIn(top+1,source.height)
        if ((right-left)*(bottom-top) < 16) return null
        val output=Bitmap.createBitmap(right-left,bottom-top,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(output); val mask=Path().apply { points.forEachIndexed { i,p -> if (i==0) moveTo(p.x*x-left,p.y*y-top) else lineTo(p.x*x-left,p.y*y-top) }; close() }
        canvas.drawPath(mask,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE })
        canvas.drawBitmap(source,-left.toFloat(),-top.toFloat(),Paint().apply { xfermode=PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
        return output
    }
}
