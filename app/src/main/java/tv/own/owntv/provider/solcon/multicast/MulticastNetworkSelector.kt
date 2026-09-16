package tv.own.owntv.provider.solcon.multicast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.NetworkInterface
import java.util.Collections

/** Selects an active Android network interface that can actually join IPv4 multicast groups. */
object MulticastNetworkSelector {
    data class Selection(
        val networkInterface: NetworkInterface,
        val isWifi: Boolean,
    )

    fun select(context: Context): Selection {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val active = connectivity?.activeNetwork
        val capabilities = active?.let(connectivity::getNetworkCapabilities)
        val linkProperties = active?.let(connectivity::getLinkProperties)
        val activeInterface = linkProperties?.interfaceName
            ?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }

        if (activeInterface != null && usable(activeInterface)) {
            return Selection(
                networkInterface = activeInterface,
                isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            )
        }

        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.let(Collections::list).orEmpty()
        }.getOrDefault(emptyList())

        val fallback = interfaces.firstOrNull(::usable) ?: throw IOException()
        return Selection(networkInterface = fallback, isWifi = false)
    }

    private fun usable(networkInterface: NetworkInterface): Boolean = runCatching {
        networkInterface.isUp && !networkInterface.isLoopback && networkInterface.supportsMulticast()
    }.getOrDefault(false)
}
