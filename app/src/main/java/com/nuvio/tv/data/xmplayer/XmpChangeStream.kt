package com.nuvio.tv.data.xmplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * GET /v1/stream?cursor= (Premium / Pro): the profile's changes as server-sent events, each `data:` one change object as
 * /v1/changes gives them. [follow] returns when the server closes the stream; the caller reconnects from the last
 * cursor it applied. A 401 refreshes the access token first, so the reconnect uses a fresh one.
 */
class XmpChangeStream(
    client: OkHttpClient,
    private val configuration: XmpConfiguration,
    private val auth: XmpAuthRepository,
    private val store: XmpAuthStore
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }

    internal suspend fun follow(scope: XmpAuthScope, cursor: Long, onChange: suspend (XmpChange) -> Unit) = withContext(Dispatchers.IO) {
        store.checkScope(scope)
        val authorization = auth.authorization(scope)
        val url = configuration.baseUrl.toHttpUrl().newBuilder().encodedPath("/v1/stream")
            .addQueryParameter("cursor", cursor.toString()).build()
        val request = Request.Builder().url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "NuvioTV/${configuration.appVersion} (XMPlayer sync)")
            .header("Authorization", "Bearer ${authorization.tokens.accessToken}")
            .build()
        val call = client.newCall(request)
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (response.code == 401) {
                auth.authorization(scope, authorization.tokens.accessToken)
                throw XmpApiException(401, "token_expired")
            }
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw XmpApiException(response.code, XmpHttpResponse(response.code, body).errorCode())
            }
            val source = response.body?.source() ?: return@use
            // Only `event: change` blocks are changes. The stream opens with `event: hello` (the CURRENT cursor): read as
            // a change it would move the cursor past the replayed changes that follow it, and they would be skipped.
            val data = StringBuilder()
            var event: String? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                store.checkScope(scope)
                val line = source.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty() && (event == null || event == "change")) {
                            val change = runCatching { json.decodeFromString<XmpChange>(data.toString()) }.getOrNull()
                            if (change != null) onChange(change)
                        }
                        data.setLength(0)
                        event = null
                    }
                    line.startsWith(":") -> Unit                       // keep-alive comment
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart()).append('\n')
                    else -> Unit                                        // id: / retry:
                }
            }
        }
    }
}
