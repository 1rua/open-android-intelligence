package com.openandroidintelligence.mobile.assistant

import android.app.Service
import android.content.Intent
import android.os.*
import com.openandroidintelligence.mobile.OpenAndroidIntelligenceApplication
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.ports.AttachmentContentSource
import com.openandroidintelligence.gateway.schema.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.UUID

/** Same-UID, unexported, versioned IPC. No credentials, provider URIs or Gateway selection cross it. */
class AssistantBridgeService : Service() {
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = (application as OpenAndroidIntelligenceApplication).gatewayRuntime
    private data class Client(val reply: Messenger, val token: String, val scope: AssistantClientScope)
    private val clients = linkedMapOf<IBinder,Client>()
    private val handler: Handler = Handler(Looper.getMainLooper()) { message ->
        if (message.sendingUid != android.os.Process.myUid()) return@Handler true
        val reply = message.replyTo ?: return@Handler true
        val b = message.data
        if (b.getInt("version") != 1 || b.keySet().any { it !in setOf("version","token","command","text","threadId","content","size") }) return@Handler true
        val command = b.getString("command") ?: return@Handler true
        if (command == "connect") {
            if (clients.size >= 4 && !clients.containsKey(reply.binder)) return@Handler true
            val client = Client(reply,UUID.randomUUID().toString(),AssistantClientScope(runtime.connectedAccountId))
            clients[reply.binder] = client
            runCatching { reply.binder.linkToDeath({ handler.post { clients.remove(reply.binder) } },0) }
            publish(client)
            return@Handler true
        }
        val client = clients[reply.binder] ?: return@Handler true
        if (b.getString("token") != client.token) return@Handler true
        if (command == "disconnect") { clients.remove(reply.binder); return@Handler true }
        if (command == "consent") {
            if (runtime.controller.value != null) client.scope.consentTo(runtime.connectedAccountId)
            publish(client); return@Handler true
        }
        if (!client.scope.canShare(runtime.connectedAccountId)) { publish(client); return@Handler true }
        val controller = runtime.controller.value ?: return@Handler true
        try {
            when(command) {
                "draft" -> { val text = b.getString("text") ?: ""; require(text.length <= 50_000); controller.editDraft(text) }
                "send" -> controller.sendDraft()
                "cancel" -> controller.stopGeneration()
                "thread" -> {
                    val id = b.getString("threadId") ?: error("THREAD_REQUIRED")
                    val threads = (controller.state.value.threads as? Loadable.Ready)?.value.orEmpty()
                    require(threads.any { it.id.value == id }) { "THREAD_UNKNOWN" }; controller.openThread(id)
                }
                "screen" -> {
                    val fd = b.getParcelable("content",ParcelFileDescriptor::class.java) ?: error("CONTENT_REQUIRED")
                    val size = b.getInt("size"); if (size !in 1..(8*1024*1024)) { fd.close();error("CONTENT_TOO_LARGE") }
                    jobs.launch(Dispatchers.IO) {
                        var bytes:ByteArray?=null;var transferred=false
                        val watchdog=Runnable { runCatching { fd.close() } };handler.postDelayed(watchdog,10_000)
                        try {
                            val content=ParcelFileDescriptor.AutoCloseInputStream(fd).use { stream ->
                                val buffer=ByteArray(size);var offset=0
                                try { while(offset<size) { val count=stream.read(buffer,offset,size-offset);check(count>0) { "CONTENT_LENGTH_MISMATCH" };offset+=count }
                                    check(stream.read()==-1) { "CONTENT_LENGTH_MISMATCH" };buffer
                                } catch(c:Throwable) { buffer.fill(0);throw c }
                            };bytes=content
                            check(content.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) { "CONTENT_TYPE_INVALID" }
                            withContext(Dispatchers.Main) {
                                check(clients[reply.binder]===client && client.scope.canShare(runtime.connectedAccountId) && runtime.controller.value === controller) { "ASSISTANT_SCOPE_CHANGED" }
                                controller.addAttachment(LocalAttachmentSelection("assist-screen.png","image/png",AttachmentContentSource.fromWriter { try { it.write(content) } finally { content.fill(0) } },recoverAfterRestart=false));transferred=true
                            }
                        } catch(c:CancellationException) { throw c }
                        catch (_: Exception) { withContext(Dispatchers.Main) { if (clients[reply.binder]===client) publish(client,"屏幕附件无法读取，请重新圈选") } }
                        finally { handler.removeCallbacks(watchdog);runCatching { fd.close() };if(!transferred) bytes?.fill(0) }
                        // The source closure owns the bounded bytes until staging has consumed them.
                    }
                }
                else -> error("IPC_COMMAND_UNSUPPORTED")
            }
            publish(client)
        } catch (_: Exception) { publish(client,"操作未完成，请检查当前连接或对话") }
        true
    }
    private val messenger = Messenger(handler)
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onCreate() {
        super.onCreate()
        runtime.restoreSessionIfAvailable()
        jobs.launch { runtime.controller.collectLatest { controller ->
            if (controller == null) { clients.values.toList().forEach { publish(it) }; return@collectLatest }
            controller.state.collect { clients.values.toList().forEach { publish(it) } }
        } }
    }
    private fun publish(client: Client, error: String? = null) {
        val state = runtime.controller.value?.state?.value
        val consent = client.scope.canShare(runtime.connectedAccountId)
        val connected = runtime.controller.value != null && client.scope.canConsent(runtime.connectedAccountId)
        val threads = (state?.threads as? Loadable.Ready)?.value.orEmpty()
        val rows = (state?.timeline as? Loadable.Ready)?.value.orEmpty().takeLast(40)
        val payload = Json.canonical(Json.of(mapOf("connected" to connected,"consent" to consent,
            "draft" to if (consent) state?.draft else "","title" to if (consent) state?.activeThreadTitle else "",
            "notice" to (error ?: state?.notice),"generation" to state?.generation?.name,
            "threads" to if (consent) threads.take(100).map { mapOf("id" to it.id.value,"title" to it.title) } else emptyList<Any>(),
            "messages" to if (consent) rows.map { mapOf("id" to it.key,"sender" to it.sender,"text" to it.text.take(4000),"streaming" to it.isStreaming,
                "attachments" to it.attachments.map { a -> a.filename }) } else emptyList<Any>())))
        runCatching { client.reply.send(Message.obtain().apply { data = Bundle().apply { putInt("version",1); putString("token",client.token); putString("state",payload) } }) }
            .onFailure { clients.remove(client.reply.binder) }
    }
    override fun onDestroy() { clients.clear(); jobs.cancel(); super.onDestroy() }
}
