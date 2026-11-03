package com.nuvio.tv.data.xmplayer

import com.nuvio.tv.core.probe.AppProbe
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Hands every XMPlayer API exchange to the probe as one line: time, method, path, status, duration, and for writes
 * (`/v1/scrobble/…`, `/v1/events`) what was sent and what XMPlayer answered. OAuth bodies and tokens are never read.
 * Without the probe ([AppProbe.enabled] false) it only forwards.
 */
class XmpProbeEngine(private val engine: XmpHttpEngine) : XmpHttpEngine {
    override suspend fun execute(request: XmpHttpRequest): XmpHttpResponse {
        if (!AppProbe.enabled) return engine.execute(request)
        val started = System.currentTimeMillis()
        val response = try {
            engine.execute(request)
        } catch (error: Exception) {
            AppProbe.xmpExchange { "${Instant.ofEpochMilli(started)} ${request.method} ${request.path} failed=${error.javaClass.simpleName}" }
            throw error
        }
        AppProbe.xmpExchange {
            val ms = System.currentTimeMillis() - started
            val write = request.path.startsWith("/v1/scrobble/") || request.path == "/v1/events"
            buildString {
                append(Instant.ofEpochMilli(started)).append(' ').append(request.method).append(' ').append(request.path)
                append(" status=").append(response.status).append(" ms=").append(ms)
                response.errorCode()?.let { append(" error=").append(it) }
                if (write) {
                    append(" sent[").append(flatten(request.body)).append(']')
                    append(" reply[").append(flatten(response.body)).append(']')
                }
            }
        }
        return response
    }

    /** A write body as `key=value` pairs (nested keys joined with dots), so a line reads without a parser. */
    private fun flatten(body: String): String {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return "unreadable"
        val out = ArrayList<String>()
        fun walk(prefix: String, element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (k, v) -> walk(if (prefix.isEmpty()) k else "$prefix.$k", v) }
                is JsonArray -> element.forEachIndexed { i, v -> walk("$prefix.$i", v) }
                is JsonPrimitive -> if (element.toString() != "null") out += "$prefix=${element.content}"
            }
        }
        walk("", root)
        return out.joinToString(" ").take(900)
    }
}
