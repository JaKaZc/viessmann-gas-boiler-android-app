package com.vigasboiler.app.ui.login

import android.content.Intent
import android.net.Uri
import androidx.browser.auth.AuthTabIntent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.vigasboiler.app.analytics.Analytics
import com.vigasboiler.app.analytics.AnalyticsEvents
import com.vigasboiler.app.analytics.AnalyticsNames
import com.vigasboiler.app.analytics.AnalyticsParams
import com.vigasboiler.app.auth.AuthRepository
import com.vigasboiler.app.auth.AuthState
import com.vigasboiler.app.auth.ClientId
import com.vigasboiler.app.auth.ClientIdStore
import com.vigasboiler.app.auth.LoginResult
import com.vigasboiler.app.auth.TokenManager
import com.vigasboiler.app.config.ApiConfig
import com.vigasboiler.app.demo.DemoSession
import net.openid.appauth.AuthorizationRequest

data class LoginUiState(
    val inProgress: Boolean = false,
    val error: String? = null,
    val cancelled: Boolean = false,
)

/** How the wizard opens the login page. */
sealed interface SignInLaunch {
    data class AuthTab(val uri: Uri, val redirectScheme: String) : SignInLaunch
    data class CustomTab(val intent: Intent) : SignInLaunch
}

/** Stored client ID once DataStore has been read; [loaded] false means not known yet. */
data class ClientSetup(val loaded: Boolean, val clientId: String?)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val clientIdStore: ClientIdStore,
    private val savedState: SavedStateHandle,
    private val analytics: Analytics,
    private val demo: DemoSession,
    config: ApiConfig,
) : ViewModel() {

    val authState: StateFlow<AuthState> = tokenManager.state

    val redirectUri: String = config.redirectUri

    val client: StateFlow<ClientSetup> = clientIdStore.clientId
        .map { ClientSetup(true, it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ClientSetup(false, null))

    private val _ui = MutableStateFlow(LoginUiState())
    val ui: StateFlow<LoginUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { tokenManager.initialize() }
    }

    fun startDemo() {
        _ui.update { it.copy(inProgress = true, error = null, cancelled = false) }
        viewModelScope.launch { demo.start() }
    }

    /** Saves a valid client ID (normalized); returns false for input that is not one. */
    fun saveClientId(input: String): Boolean {
        val id = ClientId.normalize(input)
        if (!ClientId.isValid(id)) return false
        viewModelScope.launch { clientIdStore.write(id) }
        _ui.update { LoginUiState() }
        return true
    }

    /** Null while no client ID is stored. */
    fun startSignIn(): SignInLaunch? {
        val clientId = client.value.clientId ?: return null
        _ui.update { it.copy(inProgress = true, error = null, cancelled = false) }
        val request = authRepository.newRequest(clientId)
        return if (authRepository.authTabSupported()) {
            // The PKCE verifier must survive Android stopping the app while the login page is open.
            savedState[PENDING_REQUEST] = request.jsonSerializeString()
            savedState[METHOD] = "auth_tab"
            SignInLaunch.AuthTab(request.toUri(), authRepository.redirectScheme)
        } else {
            savedState[METHOD] = "custom_tab"
            SignInLaunch.CustomTab(authRepository.customTabIntent(request))
        }
    }

    fun onAuthTabResult(result: AuthTabIntent.AuthResult) {
        val request = savedState.get<String>(PENDING_REQUEST)?.let(AuthorizationRequest::jsonDeserialize)
        savedState.remove<String>(PENDING_REQUEST)
        viewModelScope.launch {
            show(
                when {
                    request == null -> LoginResult.Failure("Sign-in request was lost, please try again")
                    result.resultCode == AuthTabIntent.RESULT_OK -> authRepository.handleRedirect(request, result.resultUri)
                    result.resultCode == AuthTabIntent.RESULT_CANCELED -> LoginResult.Cancelled
                    else -> LoginResult.Failure("Auth Tab result ${result.resultCode}")
                },
            )
        }
    }

    fun onAuthorizationResult(data: Intent?) {
        viewModelScope.launch { show(authRepository.handleAuthorizationResult(data)) }
    }

    private fun show(result: LoginResult) {
        val method = savedState.get<String>(METHOD).orEmpty()
        // Only sent if the user agreed earlier (signing in again after signing out or expiry).
        when (result) {
            LoginResult.Success -> analytics.event(AnalyticsEvents.LOGIN, mapOf(AnalyticsParams.METHOD to method))
            LoginResult.Cancelled -> analytics.event(AnalyticsEvents.SIGN_IN_FAILED, mapOf(AnalyticsParams.METHOD to method, AnalyticsParams.REASON to "cancelled"))
            is LoginResult.Failure -> analytics.event(AnalyticsEvents.SIGN_IN_FAILED, mapOf(AnalyticsParams.METHOD to method, AnalyticsParams.REASON to AnalyticsNames.reason(result.message)))
        }
        _ui.update {
            it.copy(
                inProgress = false,
                error = (result as? LoginResult.Failure)?.message,
                cancelled = result == LoginResult.Cancelled,
            )
        }
    }

    /** Keeps the client ID: signing in again only needs the ViCare login. Ends a demo instead. */
    fun signOut() {
        viewModelScope.launch {
            if (demo.active.value) demo.end() else tokenManager.signOut()
            _ui.update { LoginUiState() }
        }
    }

    private companion object {
        const val PENDING_REQUEST = "pending_auth_request"
        const val METHOD = "sign_in_method"
    }
}
