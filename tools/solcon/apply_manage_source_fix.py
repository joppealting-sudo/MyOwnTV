#!/usr/bin/env python3
"""Keep the synthetic Solcon source on its provider-managed account/sync flow.

Exact/idempotent edits only. Generic M3U edit/probe/resync controls must never receive the
solcon-tvplus://account sentinel URL; deletion stays on OwnTV's normal source deletion path.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str, marker: str | None = None) -> None:
    text = read(path)
    if marker and marker in text:
        return
    if old not in text:
        raise SystemExit(f"Expected manage-source anchor missing in {path}: {old[:140]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"Manage-source anchor is not unique in {path}: {old[:140]!r}")
    write(path, text.replace(old, new, 1))


screen = "app/src/main/java/tv/own/owntv/features/settings/ManageSourcesScreen.kt"

replace_once(
    screen,
    "import tv.own.owntv.features.setup.RemoteSetupScreen\n",
    "import tv.own.owntv.features.setup.RemoteSetupScreen\nimport tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository\n",
    marker="import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository",
)

replace_once(
    screen,
    """    var addMode by remember { mutableStateOf<AddMode?>(null) }
    var editingSource by remember { mutableStateOf<SourceEntity?>(null) }
""",
    """    var addMode by remember { mutableStateOf<AddMode?>(null) }
    var managingSolcon by remember { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf<SourceEntity?>(null) }
""",
    marker="var managingSolcon by remember",
)

replace_once(
    screen,
    """    LaunchedEffect(showAdd, editingSource, confirmDelete) {
        if (showAdd || editingSource != null || confirmDelete != null) return@LaunchedEffect
""",
    """    LaunchedEffect(showAdd, managingSolcon, editingSource, confirmDelete) {
        if (showAdd || managingSolcon || editingSource != null || confirmDelete != null) return@LaunchedEffect
""",
    marker="LaunchedEffect(showAdd, managingSolcon, editingSource, confirmDelete)",
)

replace_once(
    screen,
    """    BackHandler {
        when {
            showAdd -> { showAdd = false; addMode = null; vm.stopRemoteListener(); vm.cancelImport() }
            editingSource != null -> editingSource = null
            else -> onBack()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (editingSource != null) {
""",
    """    BackHandler {
        when {
            managingSolcon -> managingSolcon = false
            showAdd -> { showAdd = false; addMode = null; vm.stopRemoteListener(); vm.cancelImport() }
            editingSource != null -> editingSource = null
            else -> onBack()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (managingSolcon) {
            SolconTvPlusAccountScreen(
                onBack = { managingSolcon = false },
                onSynchronized = { managingSolcon = false },
                modifier = Modifier,
            )
        } else if (editingSource != null) {
""",
    marker="if (managingSolcon) {",
)

replace_once(
    screen,
    """                            val isDefault = source.id == defaultId
                            val counts by remember(source.id) { vm.contentCounts(source.id) }.collectAsStateWithLifecycle(null)
                            val syncState by remember(source.id) { vm.syncState(source.id) }.collectAsStateWithLifecycle(CatalogSyncState.Idle)

                            SourceRow(
                                source = source,
""",
    """                            val isDefault = source.id == defaultId
                            val isSolcon = source.url == SolconTvPlusRepository.SOURCE_URL
                            val counts by remember(source.id) { vm.contentCounts(source.id) }.collectAsStateWithLifecycle(null)
                            val syncState by remember(source.id) { vm.syncState(source.id) }.collectAsStateWithLifecycle(CatalogSyncState.Idle)

                            SourceRow(
                                source = source,
                                providerManaged = isSolcon,
""",
    marker="providerManaged = isSolcon",
)

replace_once(
    screen,
    """                                onEdit = { contextId = source.id; contextIndex = index; editingSource = source },
                                onTest = { contextId = source.id; contextIndex = index; vm.testSource(source) },
                                onResync = { contextId = source.id; contextIndex = index; resyncChoice = source },
""",
    """                                onManageProvider = {
                                    contextId = source.id
                                    contextIndex = index
                                    managingSolcon = true
                                },
                                onEdit = { contextId = source.id; contextIndex = index; editingSource = source },
                                onTest = { contextId = source.id; contextIndex = index; vm.testSource(source) },
                                onResync = { contextId = source.id; contextIndex = index; resyncChoice = source },
""",
    marker="onManageProvider = {",
)

replace_once(
    screen,
    """private fun SourceRow(
    source: SourceEntity,
    autoRefresh: PlaylistRefresh,
""",
    """private fun SourceRow(
    source: SourceEntity,
    providerManaged: Boolean,
    autoRefresh: PlaylistRefresh,
""",
    marker="providerManaged: Boolean",
)

replace_once(
    screen,
    """    rowModifier: Modifier,
    onEdit: () -> Unit,
""",
    """    rowModifier: Modifier,
    onManageProvider: () -> Unit,
    onEdit: () -> Unit,
""",
    marker="onManageProvider: () -> Unit",
)

replace_once(
    screen,
    """    val colors = OwnTVTheme.colors
    val activeSync = syncState as? CatalogSyncState.Syncing
""",
    """    val colors = OwnTVTheme.colors
    val activeSync = if (providerManaged) null else syncState as? CatalogSyncState.Syncing
""",
    marker="val activeSync = if (providerManaged)",
)

old_type = """            val sourceTypeText = stringResource(
                when (source.type) {
                    SourceType.XTREAM -> R.string.settings_sources_type_xtream
                    SourceType.M3U -> R.string.settings_sources_type_m3u
                    SourceType.STALKER -> R.string.settings_sources_type_stalker
                    SourceType.LOCAL_BACKUP -> R.string.settings_sources_backup
                },
                source.url,
            )
"""
new_type = """            val sourceTypeText = if (providerManaged) {
                stringResource(R.string.solcon_tvplus_source_type)
            } else {
                stringResource(
                    when (source.type) {
                        SourceType.XTREAM -> R.string.settings_sources_type_xtream
                        SourceType.M3U -> R.string.settings_sources_type_m3u
                        SourceType.STALKER -> R.string.settings_sources_type_stalker
                        SourceType.LOCAL_BACKUP -> R.string.settings_sources_backup
                    },
                    source.url,
                )
            }
"""
replace_once(screen, old_type, new_type, marker="R.string.solcon_tvplus_source_type")

replace_once(
    screen,
    """                add(sourceTypeText)
                if (autoRefresh.mode != PlaylistAutoRefresh.OFF) add(stringResource(R.string.settings_sources_auto_refresh, playlistAutoRefreshLabel(autoRefresh)))
""",
    """                add(sourceTypeText)
                if (!providerManaged && autoRefresh.mode != PlaylistAutoRefresh.OFF) {
                    add(stringResource(R.string.settings_sources_auto_refresh, playlistAutoRefreshLabel(autoRefresh)))
                }
""",
    marker="if (!providerManaged && autoRefresh.mode",
)

old_actions = """        } else {
            OwnTVButton(stringResource(R.string.settings_sources_edit), onClick = onEdit, style = OwnTVButtonStyle.SECONDARY)
            Spacer(Modifier.width(10.dp))
            // \"Info\", not \"Test\": the expensive measurement now lives behind Re-test inside the
            // popup, and this button answers the question it always really answered — is it alive?
            OwnTVButton(stringResource(R.string.settings_sources_info), onClick = onTest, style = OwnTVButtonStyle.SECONDARY)
            Spacer(Modifier.width(10.dp))
            // One stable button whose label/action flips with syncState. Keeping the SAME composable
            // in the tree (instead of an if/else that disposes \"Re-sync\" and composes \"Cancel\") means
            // the focusable node is never removed, so D-pad focus survives the swap instead of escaping
            // the row — that swap was the re-sync focus loss.
            OwnTVButton(
                label = stringResource(if (syncState.isActive) R.string.settings_sources_cancel else R.string.settings_sources_resync),
                onClick = if (syncState.isActive) onCancelSync else onResync,
                style = OwnTVButtonStyle.SECONDARY,
            )
            Spacer(Modifier.width(10.dp))
            OwnTVButton(stringResource(R.string.settings_sources_delete), onClick = onDelete, style = OwnTVButtonStyle.SECONDARY)
        }
"""
new_actions = """        } else if (providerManaged) {
            OwnTVButton(
                stringResource(R.string.solcon_tvplus_manage_source),
                onClick = onManageProvider,
                style = OwnTVButtonStyle.SECONDARY,
            )
            Spacer(Modifier.width(10.dp))
            OwnTVButton(stringResource(R.string.settings_sources_delete), onClick = onDelete, style = OwnTVButtonStyle.SECONDARY)
        } else {
            OwnTVButton(stringResource(R.string.settings_sources_edit), onClick = onEdit, style = OwnTVButtonStyle.SECONDARY)
            Spacer(Modifier.width(10.dp))
            // \"Info\", not \"Test\": the expensive measurement now lives behind Re-test inside the
            // popup, and this button answers the question it always really answered — is it alive?
            OwnTVButton(stringResource(R.string.settings_sources_info), onClick = onTest, style = OwnTVButtonStyle.SECONDARY)
            Spacer(Modifier.width(10.dp))
            // One stable button whose label/action flips with syncState. Keeping the SAME composable
            // in the tree (instead of an if/else that disposes \"Re-sync\" and composes \"Cancel\") means
            // the focusable node is never removed, so D-pad focus survives the swap instead of escaping
            // the row — that swap was the re-sync focus loss.
            OwnTVButton(
                label = stringResource(if (syncState.isActive) R.string.settings_sources_cancel else R.string.settings_sources_resync),
                onClick = if (syncState.isActive) onCancelSync else onResync,
                style = OwnTVButtonStyle.SECONDARY,
            )
            Spacer(Modifier.width(10.dp))
            OwnTVButton(stringResource(R.string.settings_sources_delete), onClick = onDelete, style = OwnTVButtonStyle.SECONDARY)
        }
"""
replace_once(screen, old_actions, new_actions, marker="R.string.solcon_tvplus_manage_source")

strings = "app/src/main/res/values/strings_solcon.xml"
text = read(strings)
if "solcon_tvplus_source_type" not in text:
    addition = """    <string name=\"solcon_tvplus_source_type\">Solcon TV+</string>\n    <string name=\"solcon_tvplus_manage_source\">Account &amp; sync</string>\n"""
    text = text.replace("</resources>", addition + "</resources>")
    write(strings, text)

print("Applied provider-managed Solcon source controls.")
