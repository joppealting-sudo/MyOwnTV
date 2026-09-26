package tv.own.owntv.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusClient
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository

/** OwnTV-facing account state for the official Solcon TV+ device-session flow. */
class SolconTvPlusViewModel(
    private val repository: SolconTvPlusRepository,
) : ViewModel() {
    enum class BusyPhase { SIGN_IN, SYNC }

    sealed interface UiState {
        data object SignedOut : UiState
        data class Busy(val phase: BusyPhase) : UiState
        data class Connected(val summary: SolconTvPlusRepository.SyncSummary? = null) : UiState
    }

    enum class ErrorKind {
        INVALID_CREDENTIALS,
        DEVICE_LIMIT,
        ACCOUNT_BLOCKED,
        REJECTED,
        SESSION_EXPIRED,
        NETWORK,
        PROTOCOL,
        EMPTY_CATALOG,
    }

    /** What the synced playlist and its two categories are called, in the user's language. */
    data class CatalogNames(val source: String, val tv: String, val radio: String)

    private val _state = MutableStateFlow<UiState>(
        if (repository.isLoggedIn()) UiState.Connected() else UiState.SignedOut,
    )
    val state: StateFlow<UiState> = _state.asStateFlow()
    val diagnostics = repository.diagnosticsState

    private val _error = MutableStateFlow<ErrorKind?>(null)
    val error: StateFlow<ErrorKind?> = _error.asStateFlow()

    /**
     * Sign in and sync straight away. [profileId] is the profile the playlist is added to — the one being
     * created during setup, which is not active until setup finishes; null means the active profile.
     */
    fun signIn(subscriptionNumber: String, pin: String, names: CatalogNames, profileId: Long? = null) {
        if (subscriptionNumber.isBlank() || pin.isBlank()) {
            _error.value = ErrorKind.INVALID_CREDENTIALS
            return
        }
        if (_state.value is UiState.Busy) return
        viewModelScope.launch {
            _error.value = null
            _state.value = UiState.Busy(BusyPhase.SIGN_IN)
            when (val result = repository.login(subscriptionNumber.trim(), pin)) {
                is SolconTvPlusClient.LoginResult.Success -> syncInternal(names, profileId)
                is SolconTvPlusClient.LoginResult.Failure -> {
                    _state.value = UiState.SignedOut
                    _error.value = when (result.reason) {
                        SolconTvPlusClient.FailureReason.INVALID_CREDENTIALS -> ErrorKind.INVALID_CREDENTIALS
                        SolconTvPlusClient.FailureReason.DEVICE_LIMIT -> ErrorKind.DEVICE_LIMIT
                        SolconTvPlusClient.FailureReason.ACCOUNT_BLOCKED -> ErrorKind.ACCOUNT_BLOCKED
                        SolconTvPlusClient.FailureReason.REJECTED -> ErrorKind.REJECTED
                        SolconTvPlusClient.FailureReason.NETWORK -> ErrorKind.NETWORK
                        SolconTvPlusClient.FailureReason.PROTOCOL,
                        SolconTvPlusClient.FailureReason.NOT_AUTHENTICATED,
                        -> ErrorKind.PROTOCOL
                    }
                }
            }
        }
    }

    fun refresh(names: CatalogNames, profileId: Long? = null) {
        if (!repository.isLoggedIn()) {
            _state.value = UiState.SignedOut
            return
        }
        if (_state.value is UiState.Busy) return
        viewModelScope.launch {
            _error.value = null
            syncInternal(names, profileId)
        }
    }

    fun signOut() {
        repository.logout()
        _error.value = null
        _state.value = UiState.SignedOut
    }

    fun dismissError() {
        _error.value = null
    }

    private suspend fun syncInternal(names: CatalogNames, profileId: Long?) {
        _state.value = UiState.Busy(BusyPhase.SYNC)
        repository.sync(names.source, names.tv, names.radio, profileId)
            .onSuccess { _state.value = UiState.Connected(it) }
            .onFailure { failure ->
                if (failure is SolconTvPlusClient.NotAuthenticatedException) {
                    // Solcon no longer accepts the stored session: say so and ask for the PIN again,
                    // rather than showing "connected" beside an error that will not go away.
                    repository.logout()
                    _state.value = UiState.SignedOut
                    _error.value = ErrorKind.SESSION_EXPIRED
                    return@onFailure
                }
                _state.value = if (repository.isLoggedIn()) UiState.Connected() else UiState.SignedOut
                _error.value = when (failure) {
                    is SolconTvPlusRepository.EmptyCatalogException -> ErrorKind.EMPTY_CATALOG
                    is java.io.IOException -> ErrorKind.NETWORK
                    else -> ErrorKind.PROTOCOL
                }
            }
    }
}
