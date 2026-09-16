#!/usr/bin/env python3
"""Apply the first-class Solcon TV+ UI/DI/setup integration deterministically.

This is intentionally idempotent: CI can re-run it without duplicating routes or rows.
It performs exact replacements so upstream drift fails loudly instead of guessing at source edits.
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str, marker: str | None = None) -> None:
    text = read(path)
    if marker and marker in text:
        return
    if old not in text:
        raise SystemExit(f"Expected integration anchor missing in {path}: {old[:100]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"Integration anchor is not unique in {path}: {old[:100]!r}")
    write(path, text.replace(old, new, 1))


# Stable route metadata: tests and both UI surfaces point at the same provider destination.
route_path = "app/src/main/java/tv/own/owntv/features/settings/SolconSettingsRoute.kt"
if not (ROOT / route_path).exists():
    write(
        route_path,
        '''package tv.own.owntv.features.settings

import androidx.annotation.StringRes
import tv.own.owntv.R

/** One source of truth for every UI entry into the Solcon TV+ account/setup flow. */
internal object SolconSettingsRoute {
    const val key = "solcon_tvplus"
    @StringRes val titleRes: Int = R.string.solcon_tvplus_title
    @StringRes val descriptionRes: Int = R.string.solcon_tvplus_settings_description

    val reachableFromSettings: Boolean = true
    val reachableFromSourceSetup: Boolean = true
}
''',
    )

# App-scoped account/session/repository graph. The PIN is never a dependency or persisted value.
module_path = "app/src/main/java/tv/own/owntv/di/SolconModule.kt"
if not (ROOT / module_path).exists():
    write(
        module_path,
        '''package tv.own.owntv.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tv.own.owntv.features.settings.SolconTvPlusViewModel
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusClient
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusSessionStore

/** Application-scoped bindings for the legitimate Solcon TV+ account/session flow. */
val solconModule = module {
    single { SolconTvPlusSessionStore(androidContext()) }
    singleOf(::SolconTvPlusClient)
    singleOf(::SolconTvPlusRepository)
    viewModelOf(::SolconTvPlusViewModel)
}
''',
    )

replace_once(
    "app/src/main/java/tv/own/owntv/OwnTVApp.kt",
    "import tv.own.owntv.di.playerModule\n",
    "import tv.own.owntv.di.playerModule\nimport tv.own.owntv.di.solconModule\n",
    marker="import tv.own.owntv.di.solconModule",
)
replace_once(
    "app/src/main/java/tv/own/owntv/OwnTVApp.kt",
    "modules(coreModule, appModule, databaseModule, dataModule, playerModule)",
    "modules(coreModule, appModule, databaseModule, dataModule, playerModule, solconModule)",
    marker="playerModule, solconModule",
)

# Settings: real tab, real dispatcher and APP-group row.
settings = "app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt"
replace_once(
    settings,
    "import tv.own.owntv.features.settings.SettingsViewModel\n",
    "import tv.own.owntv.features.settings.SettingsViewModel\nimport tv.own.owntv.features.settings.SolconSettingsRoute\nimport tv.own.owntv.features.settings.SolconTvPlusAccountScreen\n",
    marker="import tv.own.owntv.features.settings.SolconSettingsRoute",
)
replace_once(
    settings,
    "private enum class SettingsTab { ROOT, RECORDING, LANGUAGE, SOURCES, EPG, PROFILES, BACKUP, LOCAL_SYNC, VIDEO, CUSTOMIZE, HOME, NETWORK, DNS, METADATA, OPEN_SUBTITLES, WEATHER, NAV_MENU, CH_NAV, PANEL_WIDTH, GUIDE_WIDTH, GLASS_EFFECT, CONTENT_MENUS }",
    "private enum class SettingsTab { ROOT, RECORDING, LANGUAGE, SOURCES, EPG, PROFILES, BACKUP, LOCAL_SYNC, VIDEO, CUSTOMIZE, HOME, NETWORK, DNS, METADATA, OPEN_SUBTITLES, WEATHER, NAV_MENU, CH_NAV, PANEL_WIDTH, GUIDE_WIDTH, GLASS_EFFECT, CONTENT_MENUS, SOLCON }",
    marker="CONTENT_MENUS, SOLCON }",
)
replace_once(
    settings,
    "        SettingsTab.OPEN_SUBTITLES -> { tv.own.owntv.features.settings.OpenSubtitlesAccountScreen(onBack = { tab = SettingsTab.ROOT }, modifier = modifier); return }\n",
    "        SettingsTab.OPEN_SUBTITLES -> { tv.own.owntv.features.settings.OpenSubtitlesAccountScreen(onBack = { tab = SettingsTab.ROOT }, modifier = modifier); return }\n        SettingsTab.SOLCON -> { SolconTvPlusAccountScreen(onBack = { tab = SettingsTab.ROOT }, modifier = modifier); return }\n",
    marker="SettingsTab.SOLCON -> { SolconTvPlusAccountScreen",
)
replace_once(
    settings,
    '''        RootGroup("group_app", stringResource(R.string.settings_app_group), OwnTVIcon.INFO, stringResource(R.string.settings_group_summary_app)),
        RootRow(
            tabRowKey(SettingsTab.LANGUAGE), TileTone.PRIMARY, OwnTVIcon.LANGUAGE,
''',
    '''        RootGroup("group_app", stringResource(R.string.settings_app_group), OwnTVIcon.INFO, stringResource(R.string.settings_group_summary_app)),
        RootRow(
            tabRowKey(SettingsTab.SOLCON), TileTone.PRIMARY, OwnTVIcon.LIVE_TV,
            title = stringResource(SolconSettingsRoute.titleRes),
            desc = stringResource(SolconSettingsRoute.descriptionRes),
            focus = rowFocus.getValue(SettingsTab.SOLCON),
            onClick = { open(SettingsTab.SOLCON) },
        ),
        RootRow(
            tabRowKey(SettingsTab.LANGUAGE), TileTone.PRIMARY, OwnTVIcon.LANGUAGE,
''',
    marker="tabRowKey(SettingsTab.SOLCON)",
)

# Reuse the existing account screen from onboarding and Manage Sources. The callback only fires
# after a new successful sync, never merely because a restored session exists.
account = "app/src/main/java/tv/own/owntv/features/settings/SolconTvPlusAccountScreen.kt"
replace_once(
    account,
    "import androidx.compose.runtime.remember\n",
    "import androidx.compose.runtime.remember\nimport androidx.compose.runtime.rememberUpdatedState\n",
    marker="rememberUpdatedState",
)
replace_once(
    account,
    '''fun SolconTvPlusAccountScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {''',
    '''fun SolconTvPlusAccountScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSynchronized: (() -> Unit)? = null,
) {''',
    marker="onSynchronized: (() -> Unit)? = null",
)
replace_once(
    account,
    '''    val firstFocus = remember { FocusRequester() }
    var subscriptionNumber by remember { mutableStateOf(String()) }
''',
    '''    val firstFocus = remember { FocusRequester() }
    val synchronizedCallback by rememberUpdatedState(onSynchronized)
    var subscriptionNumber by remember { mutableStateOf(String()) }
''',
    marker="val synchronizedCallback by rememberUpdatedState",
)
replace_once(
    account,
    '''    LaunchedEffect(state) {
        if (state !is SolconTvPlusViewModel.UiState.Busy) {
            kotlinx.coroutines.delay(80)
            runCatching { firstFocus.requestFocus() }
        }
    }
''',
    '''    LaunchedEffect(state) {
        val connected = state as? SolconTvPlusViewModel.UiState.Connected
        if (connected?.summary != null && synchronizedCallback != null) {
            synchronizedCallback?.invoke()
            return@LaunchedEffect
        }
        if (state !is SolconTvPlusViewModel.UiState.Busy) {
            kotlinx.coroutines.delay(80)
            runCatching { firstFocus.requestFocus() }
        }
    }
''',
    marker="connected?.summary != null",
)

# Add source chooser: Solcon is discoverable alongside the existing remote/manual routes.
chooser = "app/src/main/java/tv/own/owntv/features/setup/AddSourceChooserScreen.kt"
replace_once(
    chooser,
    '''fun AddSourceChooserScreen(
    onRemote: () -> Unit,
    onManual: () -> Unit,
    onBack: () -> Unit,
''',
    '''fun AddSourceChooserScreen(
    onRemote: () -> Unit,
    onSolcon: () -> Unit,
    onManual: () -> Unit,
    onBack: () -> Unit,
''',
    marker="onSolcon: () -> Unit",
)
replace_once(
    chooser,
    '''                ChooserCard(
                    icon = OwnTVIcon.ADD,
                    title = stringResource(R.string.setup_manual),
                    subtitle = stringResource(R.string.setup_type_source_here),
                    onClick = onManual,
                )
''',
    '''                ChooserCard(
                    icon = OwnTVIcon.LIVE_TV,
                    title = stringResource(R.string.solcon_tvplus_title),
                    subtitle = stringResource(R.string.solcon_tvplus_chooser_subtitle),
                    onClick = onSolcon,
                )
                ChooserCard(
                    icon = OwnTVIcon.ADD,
                    title = stringResource(R.string.setup_manual),
                    subtitle = stringResource(R.string.setup_type_source_here),
                    onClick = onManual,
                )
''',
    marker="onClick = onSolcon",
)

# First-run wizard: route to exactly the same account screen and finish only after sync succeeds.
wizard = "app/src/main/java/tv/own/owntv/features/setup/SetupWizard.kt"
replace_once(
    wizard,
    "import tv.own.owntv.features.settings.SetupLocalSyncScreen\n",
    "import tv.own.owntv.features.settings.SetupLocalSyncScreen\nimport tv.own.owntv.features.settings.SolconTvPlusAccountScreen\n",
    marker="import tv.own.owntv.features.settings.SolconTvPlusAccountScreen",
)
replace_once(
    wizard,
    "private enum class Step { WELCOME, DISPLAY_SIZE, DISCLAIMER, SETUP_CHOICE, SYNC_DEVICE, CREATE_PROFILE, ADD_CONTENT, ADD_SOURCE_CHOOSER, ADD_SOURCE_REMOTE, ADD_SOURCE, IMPORTING, EXISTING, IMPORT_BACKUP_CHOOSER, IMPORT_BACKUP_REMOTE, IMPORT_BACKUP }",
    "private enum class Step { WELCOME, DISPLAY_SIZE, DISCLAIMER, SETUP_CHOICE, SYNC_DEVICE, CREATE_PROFILE, ADD_CONTENT, ADD_SOURCE_CHOOSER, ADD_SOURCE_REMOTE, ADD_SOURCE_SOLCON, ADD_SOURCE, IMPORTING, EXISTING, IMPORT_BACKUP_CHOOSER, IMPORT_BACKUP_REMOTE, IMPORT_BACKUP }",
    marker="ADD_SOURCE_REMOTE, ADD_SOURCE_SOLCON",
)
replace_once(
    wizard,
    '''            Step.ADD_SOURCE_CHOOSER -> AddSourceChooserScreen(
                onRemote = { step = Step.ADD_SOURCE_REMOTE },
                onManual = { step = Step.ADD_SOURCE },
                onBack = { step = Step.ADD_CONTENT },
            )
''',
    '''            Step.ADD_SOURCE_CHOOSER -> AddSourceChooserScreen(
                onRemote = { step = Step.ADD_SOURCE_REMOTE },
                onSolcon = { step = Step.ADD_SOURCE_SOLCON },
                onManual = { step = Step.ADD_SOURCE },
                onBack = { step = Step.ADD_CONTENT },
            )
''',
    marker="onSolcon = { step = Step.ADD_SOURCE_SOLCON }",
)
replace_once(
    wizard,
    '''            Step.ADD_SOURCE_REMOTE -> RemoteSetupScreen(
''',
    '''            Step.ADD_SOURCE_SOLCON -> SolconTvPlusAccountScreen(
                onBack = { step = Step.ADD_SOURCE_CHOOSER },
                onSynchronized = { vm.finish(onDone) },
            )
            Step.ADD_SOURCE_REMOTE -> RemoteSetupScreen(
''',
    marker="Step.ADD_SOURCE_SOLCON -> SolconTvPlusAccountScreen",
)

# Settings -> Manage Sources uses the same chooser/account flow, then returns to the observed list.
manage = "app/src/main/java/tv/own/owntv/features/settings/ManageSourcesScreen.kt"
replace_once(
    manage,
    '    // Within "Add source": null = the Remote|Manual chooser, else the chosen path.\n',
    '    // Within "Add source": null = the Remote|Solcon|Manual chooser, else the chosen path.\n',
    marker="Remote|Solcon|Manual",
)
replace_once(
    manage,
    '''                    null -> AddSourceChooserScreen(
                        onRemote = { addMode = AddMode.REMOTE },
                        onManual = { addMode = AddMode.MANUAL },
''',
    '''                    null -> AddSourceChooserScreen(
                        onRemote = { addMode = AddMode.REMOTE },
                        onSolcon = { addMode = AddMode.SOLCON },
                        onManual = { addMode = AddMode.MANUAL },
''',
    marker="onSolcon = { addMode = AddMode.SOLCON }",
)
replace_once(
    manage,
    '''                    AddMode.REMOTE -> RemoteSetupScreen(
''',
    '''                    AddMode.SOLCON -> SolconTvPlusAccountScreen(
                        onBack = { addMode = null },
                        onSynchronized = { addMode = null; showAdd = false },
                        modifier = Modifier,
                    )
                    AddMode.REMOTE -> RemoteSetupScreen(
''',
    marker="AddMode.SOLCON -> SolconTvPlusAccountScreen",
)
replace_once(
    manage,
    "private enum class AddMode { REMOTE, MANUAL }",
    "private enum class AddMode { REMOTE, SOLCON, MANUAL }",
    marker="private enum class AddMode { REMOTE, SOLCON, MANUAL }",
)
replace_once(
    manage,
    '    // Leaving "Add source" always returns to the Remote|Manual chooser next time (and drops any\n',
    '    // Leaving "Add source" always returns to the Remote|Solcon|Manual chooser next time (and drops any\n',
    marker='returns to the Remote|Solcon|Manual chooser',
)

# New localized settings description. All other copy already lives in strings_solcon.xml.
strings = "app/src/main/res/values/strings_solcon.xml"
replace_once(
    strings,
    '    <string name="solcon_tvplus_chooser_subtitle">Use your Solcon TV+ subscription</string>\n',
    '    <string name="solcon_tvplus_chooser_subtitle">Use your Solcon TV+ subscription</string>\n    <string name="solcon_tvplus_settings_description">Login, subscription and synchronization</string>\n',
    marker='name="solcon_tvplus_settings_description"',
)

print("Applied first-class Solcon TV+ Settings, DI and source-setup integration.")
