import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.plugin.pkg.*
import java.io.File

fun main(args: Array<String>) {
    var observed = ""
    val proxy = MediatedNetworkProxy(
        NetworkAllowlist(setOf("api.example.org"), setOf("GET"), mapOf("api.example.org" to setOf("GET"))),
        object : MediatedTransport {
            override fun exchange(request: MediatedRequest): MediatedResponse {
                observed = request.pathAndQuery
                return MediatedResponse(200, emptyMap(), byteArrayOf())
            }
        }, 4096, { 0L })
    proxy.exchange(MediatedRequest("https", "api.example.org", 443, "GET", "/private/admin", emptyMap(), null))
    check(observed == "/private/admin")
    println("当前网络代理将声明域名的范围外路径交给传输层：" + observed)

    val verifier = AlpVerifier(PackageLimits(64, 8388608, 33554432), File(args[1]))
    val parser = AlpVerifier::class.java.getDeclaredMethod("parseManifest", ByteArray::class.java).apply { isAccessible = true }
    val original = File(args[0], "plugins/sms/manifest.json").readText()
    fun surface(method: String): SecuritySurface {
        val manifest = original.replace("\"network\": []", "\"network\": [{\"scheme\":\"https\",\"host\":\"api.example.org\",\"port\":443,\"methods\":[\""+method+"\"],\"pathPrefix\":\"/v1/public/\",\"purpose\":\"审查探针\"}]")
        check(manifest != original)
        val parsed = parser.invoke(verifier, manifest.toByteArray())
        return parsed.javaClass.getDeclaredMethod("getSurface").apply { isAccessible = true }.invoke(parsed) as SecuritySurface
    }
    val before = surface("GET")
    val after = surface("POST")
    val decision = PluginUpdatePolicy().classify(InstalledSurface("review-author", "1.0.0", before),
        CandidateSurface("review-author", "1.0.1", after))
    check(decision is UpdateDecision.AutoApply)
    println("当前真实 Manifest 解析及更新策略将 GET 改为 POST 判为：" + decision)

    val primitive = "kernel.call-log.read"
    val granted = PairingGrant("review-pairing", setOf(primitive), 1, false)
    fun input(background: Boolean) = CapabilityInputs(setOf(primitive), setOf(primitive), setOf(primitive), true,
        granted, SessionConstraints(setOf(primitive), background, "review-correlation"))
    check(EffectiveCapabilities.compute(input(false)).primitives.contains(primitive))
    check(!EffectiveCapabilities.compute(input(true)).backgroundAllowed)
    println("未授后台权限：前台标识保留读取能力，真实后台标识的 backgroundAllowed=false")
}