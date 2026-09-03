package com.firefly.codexremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.firefly.codexremote.mobilecore.Platform
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface

class AndroidPlatformAdapter(
    context: Context,
    private val stateCallback: (String) -> Unit,
) : Platform {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun interfacesJSON(): String = runCatching {
        val result = JSONArray()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        interfaces.forEach { networkInterface ->
            val addresses = JSONArray()
            networkInterface.interfaceAddresses.forEach { address ->
                addresses.put(
                    JSONObject()
                        .put("ip", address.address.hostAddress.orEmpty().substringBefore('%'))
                        .put("prefixLen", address.networkPrefixLength.toInt()),
                )
            }
            result.put(
                JSONObject()
                    .put("name", networkInterface.name)
                    .put("index", networkInterface.index)
                    .put("mtu", networkInterface.mtu)
                    .put("up", networkInterface.isUp)
                    .put("broadcast", !networkInterface.isLoopback && !networkInterface.isPointToPoint)
                    .put("loopback", networkInterface.isLoopback)
                    .put("pointToPoint", networkInterface.isPointToPoint)
                    .put("multicast", networkInterface.supportsMulticast())
                    .put("addrs", addresses),
            )
        }
        result.toString()
    }.getOrDefault("[]")

    override fun bindSocketToNetwork(fd: Int): Boolean {
        // Deliberately leave the socket unbound. When Clash owns Android's VPN,
        // Network.bindSocket() to either the VPN or its physical underlay can be
        // rejected. The system default route lets Clash apply its configured
        // split-routing rules to this socket instead.
        return shouldUseDefaultRoute(connectivityManager.activeNetwork != null)
    }

    override fun onState(json: String) {
        stateCallback(json)
    }
}

internal fun shouldUseDefaultRoute(hasActiveNetwork: Boolean): Boolean = hasActiveNetwork

internal data class PhysicalNetworkCandidate(
    val networkHandle: Long,
    val isActive: Boolean,
    val isVpn: Boolean,
    val hasInternet: Boolean,
    val isNotVpn: Boolean,
    val isValidated: Boolean,
    val transport: PhysicalTransport?,
)

internal enum class PhysicalTransport(val preference: Int) {
    ETHERNET(0),
    WIFI(1),
    USB(2),
    CELLULAR(3),
}

/**
 * Chooses a real physical network for tsnet's network-change metadata.
 *
 * The input order is significant only as a final stable tie-breaker. Explicit
 * VPN underlays are preferred over the global fallback set. The selected
 * network must not be used to bind sockets; sockets follow the system route.
 */
internal fun selectPhysicalNetworkHandle(
    activeNetworkHandle: Long?,
    activeIsVpn: Boolean,
    underlyingNetworkHandles: List<Long>,
    candidates: List<PhysicalNetworkCandidate>,
): Long? {
    fun PhysicalNetworkCandidate.isUsablePhysical() =
        hasInternet && isNotVpn && !isVpn && transport != null

    fun best(networks: List<PhysicalNetworkCandidate>): PhysicalNetworkCandidate? =
        networks
            .filter(PhysicalNetworkCandidate::isUsablePhysical)
            .withIndex()
            .minWithOrNull(
                compareBy<IndexedValue<PhysicalNetworkCandidate>>(
                    { if (it.value.isValidated) 0 else 1 },
                    { if (it.value.isActive) 0 else 1 },
                    { it.value.transport?.preference ?: Int.MAX_VALUE },
                    { it.index },
                ),
            )
            ?.value

    if (!activeIsVpn) {
        candidates.firstOrNull {
            it.networkHandle == activeNetworkHandle && it.isUsablePhysical()
        }?.let { return it.networkHandle }
    } else if (underlyingNetworkHandles.isNotEmpty()) {
        val underlying = underlyingNetworkHandles.toSet()
        best(candidates.filter { it.networkHandle in underlying })?.let {
            return it.networkHandle
        }
    }

    return best(candidates)?.networkHandle
}

internal fun selectPhysicalNetworkForMonitoring(connectivityManager: ConnectivityManager): Network? {
    val activeNetwork = connectivityManager.activeNetwork
    val activeCapabilities = activeNetwork?.let(connectivityManager::getNetworkCapabilities)
    val activeIsVpn = activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    // Android exposes VpnService.Builder.setUnderlyingNetworks() to the VPN owner,
    // but does not expose the resulting underlay list to ordinary client apps.
    // allNetworks still contains those physical networks, so the selector safely
    // falls back to validated non-VPN Wi-Fi/cellular instead of the default VPN.
    val networks = connectivityManager.allNetworks.toList()
    val candidatesByHandle = networks.mapNotNull { network ->
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
        network.networkHandle to PhysicalNetworkCandidate(
            networkHandle = network.networkHandle,
            isActive = network == activeNetwork,
            isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isNotVpn = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            transport = capabilities.physicalTransport(),
        )
    }.toMap()
    val selectedHandle = selectPhysicalNetworkHandle(
        activeNetworkHandle = activeNetwork?.networkHandle,
        activeIsVpn = activeIsVpn,
        underlyingNetworkHandles = emptyList(),
        candidates = candidatesByHandle.values.toList(),
    ) ?: return null
    return networks.firstOrNull { it.networkHandle == selectedHandle }
}

private fun NetworkCapabilities.physicalTransport(): PhysicalTransport? = when {
    hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> PhysicalTransport.ETHERNET
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> PhysicalTransport.WIFI
    hasTransport(NetworkCapabilities.TRANSPORT_USB) -> PhysicalTransport.USB
    hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> PhysicalTransport.CELLULAR
    else -> null
}
