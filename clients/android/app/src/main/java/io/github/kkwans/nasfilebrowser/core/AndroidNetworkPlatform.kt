package io.github.kkwans.nasfilebrowser.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections

/** One process-wide monitor, matching the process-wide Go engine. Never owns a VPN. */
internal object AndroidNetworkPlatform {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var callback: ConnectivityManager.NetworkCallback? = null

    suspend fun refresh(context: Context) = mutex.withLock {
        val application = context.applicationContext
        val manager = application.getSystemService(ConnectivityManager::class.java)
        publish(manager)
        if (callback == null) {
            val observer = object : ConnectivityManager.NetworkCallback() {
                private fun refresh() {
                    scope.launch {
                        // Read the current default network, not an old callback's
                        // arguments; a queued loss event must not erase a new link.
                        runCatching { mutex.withLock { publish(manager) } }
                        // Startup/status explicitly refresh again and report failures.
                    }
                }
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refresh()
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refresh()
            }
            manager.registerDefaultNetworkCallback(observer)
            callback = observer
        }
    }

    private suspend fun publish(manager: ConnectivityManager) {
        val snapshot = withContext(Dispatchers.IO) {
            val interfaces = JSONArray()
            val enumeration = NetworkInterface.getNetworkInterfaces()
            for (iface in if (enumeration == null) emptyList() else Collections.list(enumeration)) {
                if (iface.index <= 0) continue
                val addresses = JSONArray()
                iface.interfaceAddresses.forEach { address ->
                    val ip = address.address?.hostAddress?.substringBefore('%')
                    if (ip != null) addresses.put("$ip/${address.networkPrefixLength}")
                }
                interfaces.put(JSONObject().put("name", iface.name).put("index", iface.index)
                    .put("mtu", iface.mtu).put("up", iface.isUp).put("loopback", iface.isLoopback)
                    .put("multicast", iface.supportsMulticast()).put("addresses", addresses))
            }
            val properties = manager.activeNetwork?.let(manager::getLinkProperties)
            val gateway = properties?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }
                ?.gateway?.hostAddress?.substringBefore('%').orEmpty()
            JSONObject().put("interfaces", interfaces)
                .put("defaultInterface", properties?.interfaceName.orEmpty()).put("defaultGateway", gateway)
        }
        NativeTransport.call(JSONObject().put("op", "network_platform").put("platformNetwork", snapshot))
    }
}
