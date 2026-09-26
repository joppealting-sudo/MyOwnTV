package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.components.OwnTVTextField
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.format.formatBestDateTime
import tv.own.owntv.ui.format.localizedInteger
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * Solcon TV+: sign in, see what the account brings into OwnTV, keep it current, and see what went wrong
 * when something does. Reached from Settings, from Manage sources and from setup's Add source. The PIN
 * goes to Solcon only and is never stored.
 */
@Composable
fun SolconTvPlusAccountScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSynchronized: (() -> Unit)? = null,
    /** The profile the playlist is added to — setup's new profile; null for the active one. */
    profileId: Long? = null,
) {
    val vm: SolconTvPlusViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    val synchronizedCallback by rememberUpdatedState(onSynchronized)
    var subscriptionNumber by remember { mutableStateOf(String()) }
    var pin by remember { mutableStateOf(String()) }
    val names = SolconTvPlusViewModel.CatalogNames(
        source = stringResource(R.string.solcon_tvplus_source_name),
        tv = stringResource(R.string.solcon_tvplus_tv_category),
        radio = stringResource(R.string.solcon_tvplus_radio_category),
    )

    LaunchedEffect(vm) {
        vm.onScreenShown()
        vm.synced.collect { synchronizedCallback?.invoke() }
    }
    LaunchedEffect(state) {
        if (state !is SolconTvPlusViewModel.UiState.Busy) {
            kotlinx.coroutines.delay(80)
            runCatching { firstFocus.requestFocus() }
        }
    }
    BackHandler { onBack() }

    SolconTvPlusAccountContent(
        state = state,
        error = error,
        diagnostics = diagnostics,
        subscriptionNumber = subscriptionNumber,
        onSubscriptionNumberChange = { subscriptionNumber = it },
        pin = pin,
        onPinChange = { pin = it },
        onSignIn = {
            val submittedPin = pin
            pin = String()
            vm.signIn(subscriptionNumber, submittedPin, names, profileId)
        },
        onRefresh = { vm.refresh(names, profileId) },
        onSignOut = vm::signOut,
        onBack = onBack,
        firstFocus = firstFocus,
        modifier = modifier,
    )
}

/** The screen itself, from state alone. [firstFocus] goes to the first field, or to "Sync now" once signed in. */
@Composable
internal fun SolconTvPlusAccountContent(
    state: SolconTvPlusViewModel.UiState,
    error: SolconTvPlusViewModel.ErrorKind?,
    diagnostics: SolconDiagnostics.Snapshot,
    subscriptionNumber: String,
    onSubscriptionNumberChange: (String) -> Unit,
    pin: String,
    onPinChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    firstFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel()
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Header(
            stringResource(R.string.solcon_tvplus_title),
            onBack,
            subtitle = stringResource(R.string.solcon_tvplus_subtitle),
        )
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(0.58f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (state) {
                    SolconTvPlusViewModel.UiState.SignedOut -> SignInCard(
                        subscriptionNumber = subscriptionNumber,
                        onSubscriptionNumberChange = onSubscriptionNumberChange,
                        pin = pin,
                        onPinChange = onPinChange,
                        error = error,
                        onSignIn = onSignIn,
                        firstFocus = firstFocus,
                    )
                    is SolconTvPlusViewModel.UiState.Busy -> BusyCard(state.phase)
                    is SolconTvPlusViewModel.UiState.Connected -> ConnectedPanel(
                        summary = state.summary,
                        diagnostics = diagnostics,
                        error = error,
                        onRefresh = onRefresh,
                        onSignOut = onSignOut,
                        firstFocus = firstFocus,
                    )
                }
            }
            Column(Modifier.weight(0.42f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                StatusCard(diagnostics)
                if (diagnostics.lastSyncAtMs == null) HowItWorksCard()
            }
        }
    }
}

@Composable
private fun SignInCard(
    subscriptionNumber: String,
    onSubscriptionNumberChange: (String) -> Unit,
    pin: String,
    onPinChange: (String) -> Unit,
    error: SolconTvPlusViewModel.ErrorKind?,
    onSignIn: () -> Unit,
    firstFocus: FocusRequester,
) {
    val colors = OwnTVTheme.colors
    SolconCard {
        Text(stringResource(R.string.solcon_tvplus_sign_in_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        Text(stringResource(R.string.solcon_tvplus_login_description), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        OwnTVTextField(
            value = subscriptionNumber,
            onValueChange = onSubscriptionNumberChange,
            label = stringResource(R.string.solcon_tvplus_subscription_number),
            placeholder = stringResource(R.string.solcon_tvplus_subscription_placeholder),
            keyboardType = KeyboardType.Number,
            focusRequester = firstFocus,
            modifier = Modifier.fillMaxWidth(),
        )
        OwnTVTextField(
            value = pin,
            onValueChange = onPinChange,
            label = stringResource(R.string.solcon_tvplus_pin),
            placeholder = stringResource(R.string.solcon_tvplus_pin_placeholder),
            keyboardType = KeyboardType.NumberPassword,
            isPassword = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OwnTVIcon(OwnTVIcon.INFO, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.solcon_tvplus_security_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        error?.let { ErrorBanner(errorText(it)) }
        Spacer(Modifier.height(2.dp))
        OwnTVButton(
            stringResource(R.string.solcon_tvplus_sign_in),
            onClick = onSignIn,
            icon = OwnTVIcon.PLAY,
            enabled = subscriptionNumber.isNotBlank() && pin.isNotBlank(),
        )
    }
}

@Composable
private fun BusyCard(phase: SolconTvPlusViewModel.BusyPhase) {
    val colors = OwnTVTheme.colors
    SolconCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            OwnTVSpinner(sizeDp = 40)
            Column {
                Text(
                    stringResource(
                        if (phase == SolconTvPlusViewModel.BusyPhase.SIGN_IN) R.string.solcon_tvplus_signing_in else R.string.solcon_tvplus_syncing,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                )
                Text(stringResource(R.string.solcon_tvplus_busy_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        val signingIn = phase == SolconTvPlusViewModel.BusyPhase.SIGN_IN
        StepLine(1, stringResource(R.string.solcon_tvplus_step_sign_in), done = !signingIn, active = signingIn)
        StepLine(2, stringResource(R.string.solcon_tvplus_step_catalog), done = false, active = !signingIn)
    }
}

@Composable
private fun ConnectedPanel(
    summary: SolconTvPlusRepository.SyncSummary?,
    diagnostics: SolconDiagnostics.Snapshot,
    error: SolconTvPlusViewModel.ErrorKind?,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    firstFocus: FocusRequester,
) {
    val colors = OwnTVTheme.colors
    // This session's sync when there was one, otherwise what the last one recorded.
    val radio = summary?.radioChannels ?: diagnostics.radioChannels
    val tv = summary?.let { it.channels - it.radioChannels } ?: diagnostics.tvChannels
    val programmes = summary?.programmes ?: diagnostics.programmes
    SolconCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.solcon_tvplus_connected),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            Pill(stringResource(R.string.solcon_tvplus_connected_chip), colors.primaryContainer, colors.onPrimaryContainer)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCell(stringResource(R.string.solcon_tvplus_stat_tv), localizedInteger(tv), Modifier.weight(1f))
            StatCell(stringResource(R.string.solcon_tvplus_stat_radio), localizedInteger(radio), Modifier.weight(1f))
            StatCell(stringResource(R.string.solcon_tvplus_stat_programmes), localizedInteger(programmes), Modifier.weight(1f))
        }
    }
    error?.let { ErrorBanner(errorText(it)) }
    Column {
        GroupLabel(stringResource(R.string.solcon_tvplus_group_account))
        ServiceSettingsRow(
            icon = OwnTVIcon.REFRESH,
            title = stringResource(R.string.solcon_tvplus_refresh),
            desc = stringResource(R.string.solcon_tvplus_refresh_description),
            modifier = Modifier.focusRequester(firstFocus),
            onClick = onRefresh,
        )
        ServiceSettingsRow(
            icon = OwnTVIcon.PERSON,
            title = stringResource(R.string.solcon_tvplus_sign_out),
            desc = stringResource(R.string.solcon_tvplus_sign_out_description),
            onClick = onSignOut,
        )
    }
}

/** What OwnTV knows about the account, without anything private: all enums, counts and a timestamp. */
@Composable
private fun StatusCard(diagnostics: SolconDiagnostics.Snapshot) {
    val colors = OwnTVTheme.colors
    val signedIn = diagnostics.sessionAuthenticated
    SolconCard {
        Text(stringResource(R.string.solcon_tvplus_status_title), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        StatusLine(
            stringResource(R.string.solcon_tvplus_status_session),
            stringResource(if (signedIn) R.string.solcon_tvplus_status_signed_in else R.string.solcon_tvplus_status_signed_out),
            tone = if (signedIn) StatusTone.GOOD else StatusTone.NEUTRAL,
        )
        StatusLine(
            stringResource(R.string.solcon_tvplus_status_last_sync),
            diagnostics.lastSyncAtMs?.let { rememberDateTime(it) } ?: stringResource(R.string.solcon_tvplus_status_never),
        )
        diagnostics.epgComplete?.let { complete ->
            StatusLine(
                stringResource(R.string.solcon_tvplus_status_guide),
                stringResource(if (complete) R.string.solcon_tvplus_status_guide_complete else R.string.solcon_tvplus_status_guide_partial),
                tone = if (complete) StatusTone.GOOD else StatusTone.WARNING,
            )
        }
        diagnostics.lastDiscovery?.let { discovery ->
            StatusLine(
                stringResource(R.string.solcon_tvplus_status_server),
                stringResource(
                    when (discovery) {
                        SolconDiagnostics.DiscoveryResult.DISCOVERED -> R.string.solcon_tvplus_status_server_found
                        SolconDiagnostics.DiscoveryResult.DEFAULT_FALLBACK -> R.string.solcon_tvplus_status_server_standard
                        SolconDiagnostics.DiscoveryResult.COMPAT_FALLBACK -> R.string.solcon_tvplus_status_server_compat
                    },
                ),
            )
        }
        diagnostics.lastPlaybackRoute?.let { route ->
            StatusLine(
                stringResource(R.string.solcon_tvplus_status_playback),
                stringResource(
                    when (route) {
                        SolconDiagnostics.PlaybackRoute.CLEAR_HTTP -> R.string.solcon_tvplus_status_playback_clear
                        SolconDiagnostics.PlaybackRoute.MULTICAST -> R.string.solcon_tvplus_status_playback_multicast
                        SolconDiagnostics.PlaybackRoute.WIDEVINE -> R.string.solcon_tvplus_status_playback_widevine
                    },
                ),
            )
        }
        diagnostics.lastFailure?.let { detail ->
            StatusLine(stringResource(R.string.solcon_tvplus_status_problem), failureDetailText(detail), tone = StatusTone.WARNING)
        }
    }
}

@Composable
private fun HowItWorksCard() {
    val colors = OwnTVTheme.colors
    SolconCard {
        Text(stringResource(R.string.solcon_tvplus_how_title), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        StepLine(1, stringResource(R.string.solcon_tvplus_how_1), done = false, active = false)
        StepLine(2, stringResource(R.string.solcon_tvplus_how_2), done = false, active = false)
        StepLine(3, stringResource(R.string.solcon_tvplus_how_3), done = false, active = false)
    }
}

@Composable
private fun SolconCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OwnTVTheme.colors.card.copy(alpha = .82f), RoundedCornerShape(16.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

private enum class StatusTone { NEUTRAL, GOOD, WARNING }

@Composable
private fun StatusLine(label: String, value: String, tone: StatusTone = StatusTone.NEUTRAL) {
    val colors = OwnTVTheme.colors
    val style = MaterialTheme.typography.bodyMedium
    // Label, dot and value share the first baseline, so a value that wraps keeps them on its first line.
    Row {
        Text(label, style = style, color = colors.onSurfaceVariant, modifier = Modifier.weight(0.45f).alignByBaseline())
        // Every value keeps the dot's room, so they line up whether or not they carry one.
        Box(
            Modifier
                .alignBy { it.measuredHeight }
                .padding(end = 8.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when (tone) {
                        StatusTone.GOOD -> colors.primary
                        StatusTone.WARNING -> colors.favorite
                        StatusTone.NEUTRAL -> Color.Transparent
                    },
                ),
        )
        Text(
            value,
            style = style,
            color = colors.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.55f).alignByBaseline(),
        )
    }
}

/** A numbered step: a filled badge once [done] or while [active], an outline before. */
@Composable
private fun StepLine(number: Int, text: String, done: Boolean, active: Boolean) {
    val colors = OwnTVTheme.colors
    val filled = done || active
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (filled) colors.primaryContainer else colors.surface.copy(alpha = .46f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (done) DONE_MARK else localizedInteger(number),
                style = MaterialTheme.typography.labelMedium,
                color = if (filled) colors.onPrimaryContainer else colors.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) colors.onSurfaceVariant else colors.onSurface,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = OwnTVTheme.colors
    Column(modifier.background(colors.surface.copy(alpha = .46f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Pill(text: String, background: Color, foreground: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun ErrorBanner(message: String) {
    val colors = OwnTVTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.favorite.copy(alpha = .14f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OwnTVIcon(OwnTVIcon.WARNING, tint = colors.favorite, modifier = Modifier.size(20.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
    }
}

@Composable
private fun rememberDateTime(atMs: Long): String {
    val context = LocalContext.current
    return remember(atMs, context) { formatBestDateTime(context, DATE_SKELETON, atMs) }
}

private const val DONE_MARK = "✓"

/** Day and month — the year only adds noise to a sync that happened this week. */
private const val DATE_SKELETON = "MMMd"

/**
 * The last failure as Solcon reported it — "Sign-in · HTTP 403 · UNAUTHORIZED_CLIENT" — so a refusal can be
 * told apart from an outage or a changed API without anyone reading logs. Holds nothing private.
 */
@Composable
internal fun failureDetailText(detail: SolconDiagnostics.FailureDetail): String {
    val step = stringResource(
        when (detail.step) {
            SolconDiagnostics.Step.SIGN_IN -> R.string.solcon_tvplus_step_sign_in
            SolconDiagnostics.Step.CHANNELS -> R.string.solcon_tvplus_step_channels
            SolconDiagnostics.Step.GUIDE -> R.string.solcon_tvplus_step_guide
            SolconDiagnostics.Step.PLAYBACK -> R.string.solcon_tvplus_step_playback
        },
    )
    val status = detail.httpStatus?.let { stringResource(R.string.solcon_tvplus_http_status, it) }
    return listOfNotNull(step, status, detail.providerCode).joinToString(stringResource(R.string.solcon_tvplus_detail_separator))
}

@Composable
private fun errorText(kind: SolconTvPlusViewModel.ErrorKind): String = stringResource(
    when (kind) {
        SolconTvPlusViewModel.ErrorKind.INVALID_CREDENTIALS -> R.string.solcon_tvplus_error_credentials
        SolconTvPlusViewModel.ErrorKind.DEVICE_LIMIT -> R.string.solcon_tvplus_error_device_limit
        SolconTvPlusViewModel.ErrorKind.ACCOUNT_BLOCKED -> R.string.solcon_tvplus_error_account_blocked
        SolconTvPlusViewModel.ErrorKind.REJECTED -> R.string.solcon_tvplus_error_rejected
        SolconTvPlusViewModel.ErrorKind.SESSION_EXPIRED -> R.string.solcon_tvplus_playback_session_expired
        SolconTvPlusViewModel.ErrorKind.NETWORK -> R.string.solcon_tvplus_error_network
        SolconTvPlusViewModel.ErrorKind.PROTOCOL -> R.string.solcon_tvplus_error_protocol
        SolconTvPlusViewModel.ErrorKind.EMPTY_CATALOG -> R.string.solcon_tvplus_error_empty_catalog
    },
)
