package com.openandroidintelligence.kernel

/** Raised for any network attempt the proxy will not mediate. */
class NetworkDenied(code: String) : IllegalArgumentException("NETWORK_DENIED:$code")

data class MediatedRequest(
    val scheme: String,
    val host: String,
    val port: Int,
    val method: String,
    val pathAndQuery: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val maximumResponseBytes:Int = Int.MAX_VALUE,
) {
    fun requestBytes(): Long = (body?.size ?: 0).toLong()
}

data class MediatedResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun responseBytes(): Long = body.size.toLong()
}

/**
 * The only way a plugin reaches the network.
 *
 * The kernel module owns no transport of its own: [MediatedTransport] is
 * implemented by the audited Gateway client, which is the sole owner of outbound
 * HTTP in this repository. Everything here is policy that runs before and after
 * that transport is used.
 */
interface MediatedTransport {
    fun exchange(request: MediatedRequest): MediatedResponse
}

/**
 * The destinations and methods a plugin declared and the user approved.
 *
 * Hosts are exact strings. There is no wildcard, suffix match, port escape or
 * "ignore certificate errors" switch, because each of those turns a reviewed
 * destination into an open one.
 */
data class NetworkAllowlist(
    val hosts: Set<String>,
    val methods: Set<String>,
    val methodsByHost:Map<String,Set<String>> = emptyMap(),
)

/**
 * Mediates plugin network access hop by hop.
 *
 * Certificates are validated by the transport on every hop, including
 * redirects, so a redirection cannot move a reviewed destination to an
 * unreviewed one while keeping the user's approval.
 */
data class NetworkUsage(val windowStartMillis:Long,val spentBytes:Long)
interface NetworkUsageStore { fun read():NetworkUsage?; fun write(usage:NetworkUsage) }

class MediatedNetworkProxy(
    private val allowlist: NetworkAllowlist,
    private val transport: MediatedTransport,
    private val dailyBudgetBytes: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val usageStore:NetworkUsageStore? = null,
) {
    private var windowStartMillis = usageStore?.read()?.windowStartMillis ?: clock()
    private var spentBytes = usageStore?.read()?.spentBytes ?: 0L

    companion object {
        const val MAX_REDIRECTS = 3
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }

    fun spentToday(): Long {
        rollWindowIfNeeded()
        return spentBytes
    }

    @Synchronized fun exchange(request: MediatedRequest): MediatedResponse = follow(request, hop = 0)

    private fun follow(request: MediatedRequest, hop: Int): MediatedResponse {
        checkAllowed(request)
        rollWindowIfNeeded()

        val projected = spentBytes + request.requestBytes()
        if (projected > dailyBudgetBytes) throw NetworkDenied("DAILY_BUDGET")

        val available=(dailyBudgetBytes-projected).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // Reserve before I/O. A crash or ambiguous network error cannot reset the daily counter.
        spentBytes=dailyBudgetBytes
        usageStore?.write(NetworkUsage(windowStartMillis,spentBytes))
        val response = transport.exchange(request.copy(maximumResponseBytes=available))
        spentBytes = projected + response.responseBytes()
        usageStore?.write(NetworkUsage(windowStartMillis,spentBytes))
        if (spentBytes > dailyBudgetBytes) throw NetworkDenied("DAILY_BUDGET")

        val location = response.headers.entries
            .firstOrNull { it.key.equals("location", ignoreCase = true) }
            ?.value
        if (location == null || response.status !in 300..399) return response

        if (hop >= MAX_REDIRECTS) throw NetworkDenied("TOO_MANY_REDIRECTS")
        val next = resolveLocation(request, location)
            ?: throw NetworkDenied("REDIRECT_UNPARSEABLE")
        return follow(next, hop + 1)
    }

    /** Every hop is re-checked, not just the first: a redirect is a new destination. */
    private fun checkAllowed(request: MediatedRequest) {
        if (!request.scheme.equals("https", ignoreCase = true)) {
            throw NetworkDenied("SCHEME")
        }
        if (request.port != 443) throw NetworkDenied("PORT")
        if (!isAllowedHost(request.host)) throw NetworkDenied("HOST_NOT_ALLOWED")
        val methods=if (allowlist.methodsByHost.isEmpty()) allowlist.methods else allowlist.methodsByHost[request.host.lowercase().trimEnd('.')].orEmpty()
        if (!methods.any { it.equals(request.method, ignoreCase = true) }) {
            throw NetworkDenied("METHOD_NOT_ALLOWED")
        }
    }

    private fun isAllowedHost(host: String): Boolean {
        val normalised = host.lowercase().trimEnd('.')
        if (normalised.isEmpty() || normalised.contains("@")) return false
        return allowlist.hosts.any { it.lowercase().trimEnd('.') == normalised }
    }

    /**
     * Resolves a redirect target without a general address parser: only
     * `https` on port 443 and path-relative targets are accepted, so a
     * `Location` cannot downgrade the scheme, move to another port, or carry
     * credentials.
     */
    internal fun resolveLocation(current: MediatedRequest, location: String): MediatedRequest? {
        val target = location.trim()
        if (target.isEmpty()) return null

        if (target.startsWith("//")) {
            val withoutScheme = target.substring(2)
            val authority = withoutScheme.substringBefore('/')
            val path = withoutScheme.substring(authority.length)
            return buildTarget(current, authority, path.ifEmpty { "/" })
        }

        if (target.startsWith("https://", ignoreCase = true)) {
            val withoutScheme = target.substring("https://".length)
            val authority = withoutScheme.substringBefore('/')
            val path = withoutScheme.substring(authority.length)
            return buildTarget(current, authority, path.ifEmpty { "/" })
        }

        if (target.startsWith("/")) return current.copy(pathAndQuery = target)

        // Anything else names a different scheme, a relative path with no
        // leading slash, or an opaque address; none are reviewed destinations.
        return null
    }

    private fun buildTarget(
        current: MediatedRequest,
        authority: String,
        path: String,
    ): MediatedRequest? {
        if (authority.isEmpty()) return null
        val hostPart = authority.substringBefore(':')
        val portPart = authority.substringAfter(':', missingDelimiterValue = "443")
        val port = portPart.toIntOrNull() ?: return null
        if (port != 443) return null
        if (hostPart.isEmpty() || hostPart.startsWith("@") || hostPart.contains("@")) return null
        return current.copy(host = hostPart, port = port, pathAndQuery = path,headers=if (hostPart.equals(current.host,ignoreCase=true)) current.headers else current.headers.filterKeys { !it.equals("Authorization",true) && !it.equals("Cookie",true) })
    }

    private fun rollWindowIfNeeded() {
        val now = clock()
        if (now - windowStartMillis >= DAY_MILLIS) {
            windowStartMillis = now
            spentBytes = 0L
            usageStore?.write(NetworkUsage(windowStartMillis,spentBytes))
        }
    }
}
