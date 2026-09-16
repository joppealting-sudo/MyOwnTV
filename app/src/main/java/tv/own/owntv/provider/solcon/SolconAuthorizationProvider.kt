package tv.own.owntv.provider.solcon

/**
 * Explicit authorization seam for provider-supported protected playback.
 *
 * The public app ships with no provider credentials, copied device authorization, keys or tokens.
 * A future implementation may be added only when Solcon provisions this client/device itself.
 */
sealed interface SolconAuthorizationState {
    val canPlayProtectedContent: Boolean

    data object NotProvisioned : SolconAuthorizationState {
        override val canPlayProtectedContent: Boolean = false
    }
}

fun interface SolconAuthorizationProvider {
    fun state(): SolconAuthorizationState
}

object NotProvisionedSolconAuthorizationProvider : SolconAuthorizationProvider {
    override fun state(): SolconAuthorizationState = SolconAuthorizationState.NotProvisioned
}
