package com.vigasboiler.app.config

import com.vigasboiler.app.BuildConfig

/**
 * Single place for every endpoint and OAuth parameter the app uses. The client ID is not here:
 * each user enters their own at runtime ([com.vigasboiler.app.auth.ClientIdStore]).
 */
data class ApiConfig(
    val apiBaseUrl: String,
    val iamBaseUrl: String,
    /** Fixed per app: the user registers exactly this URI for their client on the portal. */
    val redirectUri: String,
    val scope: String,
) {
    val authorizationEndpoint: String get() = "$iamBaseUrl/idp/v3/authorize"
    val tokenEndpoint: String get() = "$iamBaseUrl/idp/v3/token"

    companion object {
        /** `offline_access` is required, otherwise the token endpoint returns no refresh token. */
        const val SCOPE = "IoT User offline_access"

        val Default = ApiConfig(
            apiBaseUrl = "https://api.viessmann-climatesolutions.com",
            iamBaseUrl = "https://iam.viessmann-climatesolutions.com",
            redirectUri = BuildConfig.REDIRECT_URI,
            scope = SCOPE,
        )
    }
}
