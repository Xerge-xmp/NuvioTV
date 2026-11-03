package com.nuvio.tv.data.xmplayer

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * One request at a time. A 429 (the plan's reads / writes a minute) blocks that limit key until the API's
 * `retryAfter`; safe reads are retried on transport errors and 5xx with a short back-off.
 */
class XmpHttpClient(
    private val engine: XmpHttpEngine,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    private val mutex = Mutex()
    private val blockedUntil = mutableMapOf<String, Long>()

    suspend fun execute(request: XmpHttpRequest, checkScope: () -> Unit = {}): XmpHttpResponse = mutex.withLock {
        val attempts = if (request.retrySafe) 3 else 1
        repeat(attempts) { attempt ->
            checkScope()
            val now = nowEpochMs()
            blockedUntil.entries.removeAll { it.value <= now }
            blockedUntil[request.limitKey]?.let { until -> throw XmpApiException(429, "rate_limited", until) }
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
                blockedUntil[request.limitKey] = until
                throw XmpApiException(429, "rate_limited", until)
            }
            if (request.retrySafe && response.status in setOf(500, 502, 503, 504) && attempt < attempts - 1) {
                sleep(1_000L shl attempt)
            } else {
                return@withLock response
            }
        }
        throw XmpApiException(code = "request_failed")
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
