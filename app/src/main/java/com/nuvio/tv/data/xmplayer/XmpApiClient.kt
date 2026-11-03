package com.nuvio.tv.data.xmplayer

import kotlinx.serialization.json.Json

internal data class XmpMeResult(val account: XmpAccount, val cursor: Long)

/** Authorised calls: a rejected access token is refreshed once and retried; a second 401 means the user disconnected. */
class XmpApiClient(
    private val http: XmpHttpClient,
    private val auth: XmpAuthRepository,
    private val store: XmpAuthStore
) {
    @PublishedApi internal val json = Json { ignoreUnknownKeys = true }

    suspend fun get(path: String, query: Map<String, String> = emptyMap(), scope: XmpAuthScope = store.scope()): XmpHttpResponse =
        execute(XmpHttpMethod.GET, path, query, "", scope)

    suspend fun post(path: String, body: String, scope: XmpAuthScope = store.scope()): XmpHttpResponse =
        execute(XmpHttpMethod.POST, path, emptyMap(), body, scope)

    /** GET /v1/me → the profile this connection works in, the plan, and what it allows. Stored with the connection. */
    suspend fun refreshAccount(scope: XmpAuthScope = store.scope()): XmpAccount = me(scope).account

    /** The same read, with the change cursor it was made at (a first load starts from it). */
    internal suspend fun me(scope: XmpAuthScope = store.scope()): XmpMeResult {
        val me = decode<XmpMeResponse>(get("/v1/me", scope = scope).body)
        val profileId = me.profile?.id?.takeIf(String::isNotBlank) ?: throw XmpDecodingException()
        val account = XmpAccount(
            profileId = profileId,
            profileName = me.profile.name,
            tier = me.account?.tier ?: "free",
            write = me.limits?.write == true,
            stream = me.limits?.stream == true,
            readsPerMinute = me.limits?.readsPerMinute ?: 1
        )
        if (!store.saveAccount(account, scope)) store.checkScope(scope)
        return XmpMeResult(account, me.cursor ?: 0L)
    }

    internal inline fun <reified T> decode(body: String): T =
        runCatching { json.decodeFromString<T>(body) }.getOrElse { throw XmpDecodingException() }

    private suspend fun execute(method: XmpHttpMethod, path: String, query: Map<String, String>, body: String, scope: XmpAuthScope): XmpHttpResponse {
        var authorization = auth.authorization(scope)
        repeat(2) { attempt ->
            val response = http.execute(
                XmpHttpRequest(
                    method = method,
                    path = path,
                    query = query,
                    body = body,
                    accessToken = authorization.tokens.accessToken,
                    limitKey = limitKey(scope, method)
                ),
                checkScope = { store.checkScope(scope) }
            )
            if (response.status == 401) {
                if (attempt == 1 || response.errorCode() == "token_revoked") {
                    store.clearAuth(scope, XmpAuthError.AUTHORIZATION_REVOKED, authorization.tokens.accessToken)
                    throw XmpAuthException(XmpAuthError.AUTHORIZATION_REVOKED)
                }
                authorization = auth.authorization(scope, authorization.tokens.accessToken)
            } else {
                if (response.status !in 200..299) throw XmpApiException(response.status, response.errorCode())
                return response
            }
        }
        throw XmpAuthException(XmpAuthError.AUTHORIZATION_REVOKED)
    }

    // Reads and writes have separate per-minute limits in the API, so a write is never blocked by a read limit.
    private fun limitKey(scope: XmpAuthScope, method: XmpHttpMethod): String =
        "profile:${scope.profileId}:${scope.generation}:${if (method == XmpHttpMethod.GET) "read" else "write"}"
}
