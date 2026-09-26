package tv.own.owntv.provider.solcon

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * The Wi-Fi multicast lock for IPTV multicast: taken when a multicast channel is about to play, and let go
 * when OwnTV leaves the screen. Holding it costs the radio a little power, so it is never kept in the
 * background; on Ethernet it is harmless and does nothing.
 */
class WifiMulticastLock(context: Context) : MulticastLock, DefaultLifecycleObserver {
    private val lock: WifiManager.MulticastLock? =
        ContextCompat.getSystemService(context.applicationContext, WifiManager::class.java)
            ?.createMulticastLock(LOCK_TAG)
            ?.apply { setReferenceCounted(false) }

    init {
        // addObserver must run on the main thread; Koin may build this on whichever thread asks first.
        ContextCompat.getMainExecutor(context).execute {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
    }

    override fun hold() {
        val held = lock ?: return
        if (!held.isHeld) runCatching { held.acquire() }.onFailure { Log.w(TAG, "multicast lock not taken", it) }
    }

    override fun onStop(owner: LifecycleOwner) {
        val held = lock ?: return
        if (held.isHeld) runCatching { held.release() }
    }

    private companion object {
        const val TAG = "SolconMulticast"
        const val LOCK_TAG = "owntv-iptv-multicast"
    }
}
