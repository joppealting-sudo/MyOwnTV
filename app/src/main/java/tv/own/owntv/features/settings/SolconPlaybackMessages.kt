package tv.own.owntv.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.features.live.LiveViewModel.SolconFailure

/** What the player says when a Solcon channel will not play, one line per [SolconFailure.Reason]. */
@Composable
fun rememberSolconFailureText(): (SolconFailure.Reason) -> String {
    val signIn = stringResource(R.string.solcon_tvplus_playback_session_expired)
    val network = stringResource(R.string.solcon_tvplus_error_network)
    val protected = stringResource(R.string.solcon_tvplus_playback_unsupported)
    val protectedExternal = stringResource(R.string.solcon_tvplus_playback_protected_external)
    val unavailable = stringResource(R.string.solcon_tvplus_error_playback)
    return remember(signIn, network, protected, protectedExternal, unavailable) {
        { reason ->
            when (reason) {
                SolconFailure.Reason.SIGN_IN_REQUIRED -> signIn
                SolconFailure.Reason.NETWORK -> network
                SolconFailure.Reason.PROTECTED -> protected
                SolconFailure.Reason.PROTECTED_EXTERNAL -> protectedExternal
                SolconFailure.Reason.UNAVAILABLE -> unavailable
            }
        }
    }
}
