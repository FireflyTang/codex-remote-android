package com.firefly.codexremote

import android.content.Context
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
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
        val network = connectivityManager.activeNetwork ?: return false
        return runCatching {
            ParcelFileDescriptor.fromFd(fd).use { descriptor ->
                network.bindSocket(descriptor.fileDescriptor)
            }
        }.isSuccess
    }

    override fun onState(json: String) {
        stateCallback(json)
    }
}
