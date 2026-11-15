package com.openandroidintelligence.gateway.remote.network

import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.gateway.remote.facade.*
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 平台能力采集器为隔离实现，Control、上游原 Client/Noise、Server 和手机门面均走真实实现。 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class OriginalPluginHostInteropTest {
    @Test fun 固定上游原客户端从控制连接经Noise探测能力并读取手机工作区() = runBlocking {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "tools/remote-server/fixed-server.mjs").isFile }
        val node = System.getenv("OAI_NODE") ?: "node"
        val server = ProcessBuilder(node, "--experimental-vm-modules", "tools/remote-server/fixed-server.mjs")
            .directory(root).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var host: RemoteSignalService? = null
        var handler: PhoneRemoteRpcHostHandler? = null
        var client: Process? = null
        try {
            val descriptor = RemoteWireJson.parse(server.inputStream.bufferedReader().readLine()).objectValue()
            val api = RemoteServerApi(descriptor.requiredText("origin"))
            val login = api.login(descriptor.requiredText("account"), descriptor.requiredText("password"))
            val identity = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
            val identityScope = RemoteIdentityScope(api.origin, login.accountId, RemoteRole.HOST)
            val credential = api.registerAccountRole(identityScope, identity, login.token)
            val rows = linkedMapOf<String, ControlRequestMetadata>()
            val ledger = object : ControlMetadataLedger {
                @Synchronized override fun <T> transaction(change: (MutableMap<String, ControlRequestMetadata>) -> T): T {
                    val next = rows.toMutableMap(); val result = change(next); rows.clear(); rows.putAll(next); return result
                }
            }
            val release = CompletableDeferred<Unit>()
            val executor = object : PhoneControlExecutor {
                override fun requireCurrentAccess(access: ControlAccessBinding) { check(access.principal.accountId == login.accountId) }
                override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
                    context.beforeDispatch(); release.await()
                    return PhoneControlResult(ControlValue.Object(mapOf("snapshotId" to ControlValue.Text("isolated-native-tree"))))
                }
                override fun cancel(scope: ControlRequestScope) { }
                override fun capabilities(access: ControlAccessBinding) = ControlValue.Object(mapOf("isolated" to ControlValue.Boolean(true)))
            }
            val router = PhoneVirtualHostRouter(PhoneControlBus(parent, ledger, executor, phoneFacadeTestStrings))
            fun access(connection: RemoteRpcConnectionScope) = ControlAccessBinding(RemoteControlPrincipal(
                ControlOrigin.fromCanonical(api.origin), login.accountId, connection.clientDeviceId, identity.deviceId.value,
                "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)), 1, connection.connectionId, connection.generation)
            handler = PhoneRemoteRpcHostHandler(parent, router, ::access)
            val delegate = handler
            val networkHandler = object : RemoteRpcHostHandler {
                override suspend fun invoke(scope: RemoteRpcConnectionScope, method: String, params: JsonValue.JObject): JsonValue {
                    if (method == "probe.release") { release.complete(Unit); return JsonValue.JNull }
                    return delegate.invoke(scope, method, params)
                }
                override fun events(scope: RemoteRpcConnectionScope) = delegate.events(scope)
                override fun connectionClosed(scope: RemoteRpcConnectionScope) = delegate.connectionClosed(scope)
            }
            host = RemoteSignalService(RuntimeEnvironment.getApplication(), parent, api, identityScope, credential, {},
                { id, _ -> api.peer(credential.tokens.accessToken, id) }, networkHandler)
            host.startHost()
            client = ProcessBuilder(node, "--experimental-vm-modules", "tools/remote-interop/original-client.mjs", api.origin, identity.deviceId.value)
                .directory(root).redirectErrorStream(true).start()
            val finished = withContext(Dispatchers.IO) { client.waitFor(30, TimeUnit.SECONDS) }
            if (!finished) client.destroy()
            val output = withContext(Dispatchers.IO) { client.inputStream.bufferedReader().readText() }
            assertTrue("上游原客户端未在期限内完成：$output", finished)
            assertEquals("上游原客户端互通失败：$output", 0, client.exitValue())
            assertTrue(output, output.contains("\"status\":\"PASS\""))
        } finally {
            client?.destroy(); host?.close(); handler?.close(); parent.cancel()
            server.outputStream.close()
            if (!withContext(Dispatchers.IO) { server.waitFor(5, TimeUnit.SECONDS) }) server.destroy()
        }
    }
}
