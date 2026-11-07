package com.nuvio.tv.data.xmplayer

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource

/**
 * Two lanes ([XmpLane]). The API lane runs one request at a time, and a 429 (the plan's reads / writes a minute) blocks
 * that limit key until the API's `retryAfter`. The agent lane — the catalog, which the Sync API hands to the account's
 * XMPlayer DVR agent without counting it — runs [AGENT_PARALLEL] at once: Nuvio's Library reads one page per category,
 * and one at a time made 185 categories a minute's wait.
 *
 * Safe reads are retried on transport errors and 5xx with a short back-off (an agent that is starting answers within a
 * second or two). Not `agent_timeout`: that agent is connected but did not answer within the Sync API's 25 s, and asking
 * it again only waits 25 s more.
 *
 * An agent that is away (none online, still starting, or not answering) answers once for every agent request that was
 * already waiting when that answer came: the Library's 185 category reads get the one answer the Sync API gave, not
 * 185 rounds of retries against it. A request made after that answer — a Retry, a later visit — asks again.
 */
class XmpHttpClient(
    private val engine: XmpHttpEngine,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    private val apiLane = Mutex()
    private val agentLane = Semaphore(AGENT_PARALLEL)
    private val blockedUntil = mutableMapOf<String, Long>()
    private val asks = AtomicLong()

    /** The newest "the agent is away" answer, and the ask count it came at. */
    private class AgentAway(val at: Long, val response: XmpHttpResponse)

    @Volatile private var agentAway: AgentAway? = null

    suspend fun execute(request: XmpHttpRequest, checkScope: () -> Unit = {}): XmpHttpResponse = when (request.lane) {
        XmpLane.API -> apiLane.withLock { send(request, checkScope) }
        XmpLane.AGENT -> {
            val askedAt = asks.incrementAndGet()
            agentLane.withPermit {
                agentAway?.takeIf { it.at > askedAt }?.response ?: send(request, checkScope).also { response ->
                    when {
                        response.isAgentAway() -> agentAway = AgentAway(asks.incrementAndGet(), response)
                        response.status in 200..299 -> agentAway = null
                    }
                }
            }
        }
    }

    private suspend fun send(request: XmpHttpRequest, checkScope: () -> Unit): XmpHttpResponse {
        val attempts = if (request.retrySafe) 3 else 1
        repeat(attempts) { attempt ->
            checkScope()
            val now = nowEpochMs()
            synchronized(blockedUntil) {
                blockedUntil.entries.removeAll { it.value <= now }
                blockedUntil[request.limitKey]
            }?.let { until -> throw XmpApiException(429, "rate_limited", until) }
            val response = try {
                engine.execute(request)
            } catch (_: IOException) {
                checkScope()
                if (attempt == attempts - 1) throw XmpApiException(code = "transport_failure")
                sleep(1_000L shl attempt)
                return@repeat
            }
            checkScope()
            if (response.status == 429) {
                val seconds = response.retryAfterSeconds() ?: response.header("Retry-After")?.trim()?.toLongOrNull() ?: 60L
                val until = nowEpochMs() + seconds.coerceIn(1L, 3_600L) * 1_000L
                synchronized(blockedUntil) { blockedUntil[request.limitKey] = until }
                throw XmpApiException(429, "rate_limited", until)
            }
            val retry = request.retrySafe && response.status in RETRIED_STATUSES && response.errorCode() != AGENT_TIMEOUT
            if (retry && attempt < attempts - 1) {
                sleep(1_000L shl attempt)
            } else {
                return response
            }
        }
        throw XmpApiException(code = "request_failed")
    }

    private fun XmpHttpResponse.isAgentAway(): Boolean = status == 503 || errorCode() in AGENT_AWAY_CODES

    private companion object {
        /** Catalog reads at once: each one is a question to the one DVR agent, which answers them off its main thread. */
        const val AGENT_PARALLEL = 4
        const val AGENT_TIMEOUT = "agent_timeout"
        val RETRIED_STATUSES = setOf(500, 502, 503, 504)
        val AGENT_AWAY_CODES = setOf("no_agent", "no_answering_device", "agent_offline", "agent_not_ready", AGENT_TIMEOUT, "no_answer")
    }
}

class OkHttpXmpEngine(
    client: OkHttpClient,
    private val configuration: XmpConfiguration
) : XmpHttpEngine {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun execute(request: XmpHttpRequest): XmpHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(buildRequest(request))
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val body = response.body?.let { readXmpResponseBody(it.source(), it.contentLength()) }.orEmpty()
                            if (continuation.isActive) {
                                continuation.resume(
                                    XmpHttpResponse(
                                        status = response.code,
                                        body = body,
                                        headers = response.headers.toMultimap().mapValues { it.value.joinToString(",") }
                                    )
                                )
                            }
                        } catch (error: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    internal fun buildRequest(request: XmpHttpRequest): Request {
        require(request.path.startsWith("/v1/"))
        require(request.path.none { it == '?' || it == '#' })
        val base = configuration.baseUrl.toHttpUrl()
        require(base.isHttps)
        val url = base.newBuilder().encodedPath(request.path).query(null).fragment(null).apply {
            request.query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        return Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "NuvioTV/${configuration.appVersion} (XMPlayer sync)")
            .apply {
                request.accessToken?.let { header("Authorization", "Bearer $it") }
                when (request.method) {
                    XmpHttpMethod.GET -> get()
                    XmpHttpMethod.POST -> post(
                        request.form?.let { fields ->
                            FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
                        } ?: request.body.toRequestBody("application/json".toMediaType())
                    )
                }
            }.build()
    }
}

internal fun readXmpResponseBody(source: BufferedSource, contentLength: Long, maxBytes: Long = 16L * 1024L * 1024L): String {
    if (contentLength > maxBytes) throw IOException("XMPlayer response exceeds size limit")
    val buffer = Buffer()
    while (true) {
        val count = source.read(buffer, minOf(8_192L, maxBytes - buffer.size + 1L))
        if (count == -1L) return buffer.readUtf8()
        if (buffer.size > maxBytes) throw IOException("XMPlayer response exceeds size limit")
    }
}
