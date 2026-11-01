package si.vicare.customapp.ui.login

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import si.vicare.customapp.auth.AuthRepository
import si.vicare.customapp.auth.AuthState
import si.vicare.customapp.auth.LoginResult
import si.vicare.customapp.auth.TokenManager
import si.vicare.customapp.config.ApiConfig

data class LoginUiState(
    val inProgress: Boolean = false,
    val error: String? = null,
    val cancelled: Boolean = false,
    val clientConfigured: Boolean = true,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    config: ApiConfig,
) : ViewModel() {

    val authState: StateFlow<AuthState> = tokenManager.state

    private val _ui = MutableStateFlow(LoginUiState(clientConfigured = config.isClientConfigured))
    val ui: StateFlow<LoginUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { tokenManager.initialize() }
    }

    fun authorizationIntent(): Intent {
        _ui.update { it.copy(inProgress = true, error = null, cancelled = false) }
        return authRepository.authorizationIntent()
    }

    fun onAuthorizationResult(data: Intent?) {
        viewModelScope.launch {
            val result = authRepository.handleAuthorizationResult(data)
            _ui.update {
                it.copy(
                    inProgress = false,
                    error = (result as? LoginResult.Failure)?.message,
                    cancelled = result == LoginResult.Cancelled,
                )
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { tokenManager.signOut() }
    }
}
