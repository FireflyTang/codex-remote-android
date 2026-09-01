package com.firefly.codexremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable

class AndroidNetworkMonitor(
    context: Context,
    private val commandSink: (String) -> Unit,
) : Closeable {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    @Volatile
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publishCurrentNetwork()

        override fun onLost(network: Network) = publishCurrentNetwork()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            publishCurrentNetwork()

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            publishCurrentNetwork()
    }

    @Synchronized
    fun start() {
        if (registered) return
        connectivityManager.registerDefaultNetworkCallback(callback)
        registered = true
        publishCurrentNetwork()
    }

    private fun publishCurrentNetwork() {
        if (!registered) return
        val network = connectivityManager.activeNetwork
        val properties = network?.let(connectivityManager::getLinkProperties)
        val defaultInterface = properties?.interfaceName.orEmpty()
        val defaultGateway = properties?.routes
            ?.firstOrNull { route -> route.isDefaultRoute && route.gateway != null }
            ?.gateway
            ?.hostAddress
            ?.substringBefore('%')
            .orEmpty()
        commandSink(networkChangedCommand(defaultInterface, defaultGateway).toString())
    }

    @Synchronized
    override fun close() {
        if (!registered) return
        registered = false
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }
}

internal fun networkChangedCommand(
    defaultInterface: String?,
    defaultGateway: String?,
) = coreCommand(
    type = "network_changed",
    payload = org.json.JSONObject()
        .put("defaultInterface", defaultInterface.orEmpty())
        .put("defaultGateway", defaultGateway.orEmpty()),
)
