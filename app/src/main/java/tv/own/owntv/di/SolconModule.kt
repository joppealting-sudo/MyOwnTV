package tv.own.owntv.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.features.settings.SolconTvPlusViewModel
import tv.own.owntv.provider.solcon.MulticastLock
import tv.own.owntv.provider.solcon.SolconPlayback
import tv.own.owntv.provider.solcon.WifiMulticastLock
import tv.own.owntv.provider.solcon.tvplus.SolconAutoRefresh
import tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusClient
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusSessionStore

/** Solcon TV+ (sign-in, sync, just-in-time playback) and IPTV multicast. */
val solconModule = module {
    single { SolconTvPlusSessionStore(androidContext()) }
    single { SolconDiagnostics(androidContext()) }
    singleOf(::SolconTvPlusClient)
    singleOf(::SolconTvPlusRepository)
    single<MulticastLock> { WifiMulticastLock(androidContext()) }
    single { SolconPlayback(repository = get<SolconTvPlusRepository>(), multicastLock = get()) }
    // Created with Koin so it can follow the app's lifecycle; its first check waits out start-up.
    single(createdAtStart = true) { SolconAutoRefresh(androidContext(), lazy { get<SourceDao>() }, lazy { get<SolconTvPlusRepository>() }) }
    viewModelOf(::SolconTvPlusViewModel)
}
