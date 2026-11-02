package com.vigasboiler.app.auth

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Sends the refresh-token grant to the IAM token endpoint. Uses its own OkHttpClient without the
 * API authenticator so a failing refresh can never recurse into another refresh.
 */
class IamRefreshClient(
    private val httpClient: OkHttpClient,
    private val tokenEndpoint: String,
    /** Read on every refresh: the user can change the client ID in the app. */
    private val clientId: suspend () -> String?,
) : RefreshClient {

    override suspend fun refresh(refreshToken: String): RefreshResult = withContext(Dispatchers.IO) {
        // Without a client ID the refresh token is useless: sign in again via the setup wizard.
        val clientId = clientId() ?: return@withContext RefreshResult.Rejected("no_client_id")
        val request = Request.Builder()
            .url(tokenEndpoint)
            .post(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("client_id", clientId)
                    .add("refresh_token", refreshToken)
                    .build(),
            )
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                when {
                    response.isSuccessful && json != null -> parseSuccess(json)
                    response.code in 400..499 -> RefreshResult.Rejected(json?.string("error") ?: "http_${response.code}")
                    else -> RefreshResult.TransientFailure(IOException("Token endpoint returned ${response.code}"))
                }
            }
        } catch (e: IOException) {
            RefreshResult.TransientFailure(e)
        }
    }

    private fun parseSuccess(json: JsonObject): RefreshResult {
        val access = json.string("access_token")
            ?: return RefreshResult.TransientFailure(IOException("Token response without access_token"))
        val expiresIn = json["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        return RefreshResult.Success(access, json.string("refresh_token"), expiresIn)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
