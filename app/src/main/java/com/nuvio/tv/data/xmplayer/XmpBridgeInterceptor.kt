package com.nuvio.tv.data.xmplayer

import android.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Sends a provider call through XMPlayer when [shouldBridge] says so: `https://api.trakt.tv/sync/watched/movies?x=1` →
 * `https://stateroom.xmplayer.app/v1/trakt/sync/watched/movies?x=1`, with the XMPlayer access token instead of any provider
 * key or token ([stripHeaders], [stripQuery]). XMPlayer adds its own. A 401 that is not XMPlayer's own refusal means the
 * XMPlayer token expired: refreshed once and retried. XMPlayer's "link is gone" answers (409 with [XMP_BRIDGE_ERROR_HEADER])
 * mark the link lost so the profile shows as not linked.
 */
class XmpBridgeInterceptor(
    private val bridge: XmpBridge,
    private val provider: XmpBridgeProvider,
    private val stripHeaders: Set<String>,
    private val stripQuery: Set<String> = emptySet(),
    private val shouldBridge: (Request) -> Boolean
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!shouldBridge(original)) return chain.proceed(original)

        val target = passthroughUrl(original.url)
        var token = runBlocking { bridge.accessToken() }
        var response = chain.proceed(bridged(original, target, token))
        if (response.code == 401 && response.header(XMP_BRIDGE_ERROR_HEADER) == null) {
            response.close()
            token = runBlocking { bridge.accessToken(rejected = token) }
            response = chain.proceed(bridged(original, target, token))
        }
        val bridgeError = response.header(XMP_BRIDGE_ERROR_HEADER) != null
        if (bridgeError) {
            Log.w(TAG, "${provider.wire} ${original.method} ${original.url.encodedPath}: XMPlayer answered ${response.code}")
            if (response.code == 409) bridge.onLinkLost(provider)
        }
        return response
    }

    private fun bridged(original: Request, target: HttpUrl, token: String): Request {
        val builder = original.newBuilder().url(target)
        stripHeaders.forEach(builder::removeHeader)
        return builder.header("Authorization", "Bearer $token").build()
    }

    private fun passthroughUrl(url: HttpUrl): HttpUrl {
        val base = bridge.passthroughBase(provider).toHttpUrl()
        val builder = base.newBuilder()
        url.pathSegments.filter(String::isNotEmpty).forEach(builder::addPathSegment)
        for (index in 0 until url.querySize) {
            val name = url.queryParameterName(index)
            if (name in stripQuery) continue
            builder.addQueryParameter(name, url.queryParameterValue(index))
        }
        return builder.build()
    }

    private companion object {
        const val TAG = "XmpBridge"
    }
}
