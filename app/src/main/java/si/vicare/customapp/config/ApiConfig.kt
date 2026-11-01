package si.vicare.customapp.config

import si.vicare.customapp.BuildConfig

/** Single place for every endpoint and OAuth parameter the app uses. */
data class ApiConfig(
    val apiBaseUrl: String,
    val iamBaseUrl: String,
    val clientId: String,
    val redirectUri: String,
    val scope: String,
) {
    val authorizationEndpoint: String get() = "$iamBaseUrl/idp/v3/authorize"
    val tokenEndpoint: String get() = "$iamBaseUrl/idp/v3/token"
    val isClientConfigured: Boolean get() = clientId.isNotBlank()

    companion object {
        /** `offline_access` is required, otherwise the token endpoint returns no refresh token. */
        const val SCOPE = "IoT User offline_access"

        val Default = ApiConfig(
            apiBaseUrl = "https://api.viessmann-climatesolutions.com",
            iamBaseUrl = "https://iam.viessmann-climatesolutions.com",
            clientId = BuildConfig.VIESSMANN_CLIENT_ID,
            redirectUri = BuildConfig.VIESSMANN_REDIRECT_URI,
            scope = SCOPE,
        )
    }
}
