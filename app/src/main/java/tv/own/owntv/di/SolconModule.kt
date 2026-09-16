package tv.own.owntv.di

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
