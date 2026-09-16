package tv.own.owntv.features.settings

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
