package tv.own.owntv.provider.solcon.tvplus

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.SourceDao

/**
 * Keeps the Solcon playlist current without anyone pressing "Refresh": each time OwnTV comes to the
 * screen, a playlist last synced more than [STALE_MS] ago is synced again in the background. The guide
 * holds two days ahead, so without this it would run dry.
 *
 * Nothing happens at construction beyond registering for the app's lifecycle — even the database is
 * reached lazily — and the check itself waits out the app's own start-up, so a cold start pays nothing.
 */
class SolconAutoRefresh(
    private val context: Context,
    private val sourceDao: Lazy<SourceDao>,
    private val repository: Lazy<SolconTvPlusRepository>,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var check: Job? = null

    init {
        // addObserver must run on the main thread; Koin builds this wherever it is first asked for.
        ContextCompat.getMainExecutor(context).execute {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (check?.isActive == true) return
        check = scope.launch {
            delay(STARTUP_GRACE_MS)
            val source = sourceDao.value.getAllOnce().firstOrNull { it.url == SolconTvPlusRepository.SOURCE_URL } ?: return@launch
            if (nowMs() - (source.lastSyncAt ?: 0L) < STALE_MS) return@launch
            val result = repository.value.refreshInBackground(
                sourceName = context.getString(R.string.solcon_tvplus_source_name),
                tvCategoryName = context.getString(R.string.solcon_tvplus_tv_category),
                radioCategoryName = context.getString(R.string.solcon_tvplus_radio_category),
            )
            result?.onFailure { Log.w(TAG, "background refresh failed: ${it.javaClass.simpleName}") }
        }
    }

    private companion object {
        const val TAG = "SolconAutoRefresh"
        const val STALE_MS = 12L * 60L * 60L * 1000L
        const val STARTUP_GRACE_MS = 15_000L
    }
}
