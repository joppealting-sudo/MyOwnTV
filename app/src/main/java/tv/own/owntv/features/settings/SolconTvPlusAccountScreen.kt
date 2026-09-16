package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.components.OwnTVTextField
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.theme.OwnTVTheme

/** Account/setup surface for Solcon TV+. Credentials are submitted to the provider and never persisted. */
@Composable
fun SolconTvPlusAccountScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: SolconTvPlusViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val colors = OwnTVTheme.colors
    val firstFocus = remember { FocusRequester() }
    var subscriptionNumber by remember { mutableStateOf(String()) }
    var pin by remember { mutableStateOf(String()) }

    val sourceName = stringResource(R.string.solcon_tvplus_source_name)
    val tvCategoryName = stringResource(R.string.solcon_tvplus_tv_category)
    val radioCategoryName = stringResource(R.string.solcon_tvplus_radio_category)

    LaunchedEffect(state) {
        if (state !is SolconTvPlusViewModel.UiState.Busy) {
            kotlinx.coroutines.delay(80)
            runCatching { firstFocus.requestFocus() }
        }
    }
    BackHandler { onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel()
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.solcon_tvplus_title),
            style = MaterialTheme.typography.headlineLarge,
            color = colors.onSurface,
        )
        Text(
            stringResource(R.string.solcon_tvplus_login_description),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        when (val current = state) {
            SolconTvPlusViewModel.UiState.SignedOut -> {
                OwnTVTextField(
                    value = subscriptionNumber,
                    onValueChange = { subscriptionNumber = it },
                    label = stringResource(R.string.solcon_tvplus_subscription_number),
                    placeholder = stringResource(R.string.solcon_tvplus_subscription_placeholder),
                    keyboardType = KeyboardType.Number,
                    focusRequester = firstFocus,
                    modifier = Modifier.fillMaxWidth(),
                )
                OwnTVTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = stringResource(R.string.solcon_tvplus_pin),
                    placeholder = stringResource(R.string.solcon_tvplus_pin_placeholder),
                    keyboardType = KeyboardType.NumberPassword,
                    isPassword = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.solcon_tvplus_security_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                error?.let {
                    Text(
                        errorText(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.favorite,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OwnTVButton(
                        stringResource(R.string.solcon_tvplus_sign_in),
                        onClick = {
                            val submittedPin = pin
                            pin = String()
                            vm.signIn(
                                subscriptionNumber,
                                submittedPin,
                                sourceName,
                                tvCategoryName,
                                radioCategoryName,
                            )
                        },
                        enabled = subscriptionNumber.isNotBlank() && pin.isNotBlank(),
                    )
                    OwnTVButton(
                        stringResource(R.string.common_back),
                        onClick = onBack,
                        style = OwnTVButtonStyle.SECONDARY,
                    )
                }
            }

            is SolconTvPlusViewModel.UiState.Busy -> {
                OwnTVSpinner(sizeDp = 48)
                Text(
                    stringResource(
                        if (current.phase == SolconTvPlusViewModel.BusyPhase.SIGN_IN) {
                            R.string.solcon_tvplus_signing_in
                        } else {
                            R.string.solcon_tvplus_syncing
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                )
            }

            is SolconTvPlusViewModel.UiState.Connected -> {
                Text(
                    stringResource(R.string.solcon_tvplus_connected),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.primary,
                )
                current.summary?.let { summary ->
                    val channelsText = pluralStringResource(
                        R.plurals.solcon_tvplus_channels_count,
                        summary.channels,
                        summary.channels,
                    )
                    val radioText = pluralStringResource(
                        R.plurals.solcon_tvplus_radio_channels_count,
                        summary.radioChannels,
                        summary.radioChannels,
                    )
                    val programmesText = pluralStringResource(
                        R.plurals.solcon_tvplus_guide_entries_count,
                        summary.programmes,
                        summary.programmes,
                    )
                    Text(
                        if (summary.programmes > 0) {
                            stringResource(
                                R.string.solcon_tvplus_sync_done_epg,
                                channelsText,
                                radioText,
                                programmesText,
                            )
                        } else {
                            stringResource(
                                R.string.solcon_tvplus_sync_done,
                                channelsText,
                                radioText,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                error?.let {
                    Text(
                        errorText(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.favorite,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OwnTVButton(
                        stringResource(R.string.solcon_tvplus_refresh),
                        onClick = { vm.refresh(sourceName, tvCategoryName, radioCategoryName) },
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                    OwnTVButton(
                        stringResource(R.string.solcon_tvplus_sign_out),
                        onClick = vm::signOut,
                        style = OwnTVButtonStyle.SECONDARY,
                    )
                    OwnTVButton(
                        stringResource(R.string.common_back),
                        onClick = onBack,
                        style = OwnTVButtonStyle.SECONDARY,
                    )
                }
            }
        }
    }
}

@Composable
private fun errorText(kind: SolconTvPlusViewModel.ErrorKind): String = stringResource(
    when (kind) {
        SolconTvPlusViewModel.ErrorKind.INVALID_CREDENTIALS -> R.string.solcon_tvplus_error_credentials
        SolconTvPlusViewModel.ErrorKind.DEVICE_LIMIT -> R.string.solcon_tvplus_error_device_limit
        SolconTvPlusViewModel.ErrorKind.NETWORK -> R.string.solcon_tvplus_error_network
        SolconTvPlusViewModel.ErrorKind.PROTOCOL -> R.string.solcon_tvplus_error_protocol
        SolconTvPlusViewModel.ErrorKind.EMPTY_CATALOG -> R.string.solcon_tvplus_error_empty_catalog
    },
)
