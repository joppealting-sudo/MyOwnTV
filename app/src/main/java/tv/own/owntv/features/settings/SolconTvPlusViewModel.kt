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

    enum class ErrorKind { INVALID_CREDENTIALS, DEVICE_LIMIT, NETWORK, PROTOCOL, EMPTY_CATALOG }

    private val _state = MutableStateFlow<UiState>(
        if (repository.isLoggedIn()) UiState.Connected() else UiState.SignedOut,
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _error = MutableStateFlow<ErrorKind?>(null)
    val error: StateFlow<ErrorKind?> = _error.asStateFlow()

    fun signIn(
        subscriptionNumber: String,
        pin: String,
        sourceName: String,
        tvCategoryName: String,
        radioCategoryName: String,
    ) {
        if (subscriptionNumber.isBlank() || pin.isBlank()) {
            _error.value = ErrorKind.INVALID_CREDENTIALS
            return
        }
        viewModelScope.launch {
            _error.value = null
            _state.value = UiState.Busy(BusyPhase.SIGN_IN)
            when (val result = repository.login(subscriptionNumber.trim(), pin)) {
                is SolconTvPlusClient.LoginResult.Success -> syncInternal(sourceName, tvCategoryName, radioCategoryName)
                is SolconTvPlusClient.LoginResult.Failure -> {
                    _state.value = UiState.SignedOut
                    _error.value = when (result.reason) {
                        SolconTvPlusClient.FailureReason.INVALID_CREDENTIALS -> ErrorKind.INVALID_CREDENTIALS
                        SolconTvPlusClient.FailureReason.DEVICE_LIMIT -> ErrorKind.DEVICE_LIMIT
                        SolconTvPlusClient.FailureReason.NETWORK -> ErrorKind.NETWORK
                        SolconTvPlusClient.FailureReason.PROTOCOL,
                        SolconTvPlusClient.FailureReason.NOT_AUTHENTICATED -> ErrorKind.PROTOCOL
                    }
                }
            }
        }
    }

    fun refresh(sourceName: String, tvCategoryName: String, radioCategoryName: String) {
        if (!repository.isLoggedIn()) {
            _state.value = UiState.SignedOut
            return
        }
        viewModelScope.launch {
            _error.value = null
            syncInternal(sourceName, tvCategoryName, radioCategoryName)
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

    private suspend fun syncInternal(sourceName: String, tvCategoryName: String, radioCategoryName: String) {
        _state.value = UiState.Busy(BusyPhase.SYNC)
        repository.sync(sourceName, tvCategoryName, radioCategoryName)
            .onSuccess { _state.value = UiState.Connected(it) }
            .onFailure { failure ->
                _state.value = if (repository.isLoggedIn()) UiState.Connected() else UiState.SignedOut
                _error.value = if (failure.message?.contains("no subscribed channels", ignoreCase = true) == true) {
                    ErrorKind.EMPTY_CATALOG
                } else {
                    ErrorKind.NETWORK
                }
            }
    }
}
