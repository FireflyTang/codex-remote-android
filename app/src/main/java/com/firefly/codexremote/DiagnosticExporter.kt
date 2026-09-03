package com.firefly.codexremote

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal data class DiagnosticExport(
    val file: File,
    val shareIntent: Intent,
)

internal class DiagnosticExporter(private val context: Context) {
    fun create(): DiagnosticExport {
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val archiveFiles = diagnosticArchiveFiles(directory, System.currentTimeMillis(), UUID.randomUUID().toString())
        cleanupOldDiagnosticArchives(directory, DiagnosticArchiveRetention - 1, setOf(archiveFiles.output, archiveFiles.temporary))
        val entries = mutableListOf(
            diagnosticTextEntry("manifest.json", manifest().toString(2)),
            diagnosticTextEntry("network.json", networkSnapshot().toString(2)),
        )
        val appLog = File(context.filesDir, "diagnostics/app.log")
        if (appLog.isFile) entries += diagnosticFileEntry("app.log", appLog)
        val tailnetDirectory = File(context.filesDir, "tailnet")
        eligibleTailscaleLogFiles(tailnetDirectory.listFiles().orEmpty().toList()).forEach { source ->
            entries += diagnosticFileEntry("tailscale/${source.name}", source)
        }
        writeDiagnosticZipAtomically(archiveFiles.output, archiveFiles.temporary, entries)
        cleanupOldDiagnosticArchives(directory, DiagnosticArchiveRetention, setOf(archiveFiles.output))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", archiveFiles.output)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return DiagnosticExport(archiveFiles.output, Intent.createChooser(intent, "分享诊断日志"))
    }

    private fun manifest(): JSONObject {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return JSONObject()
            .put("generatedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
            .put("appVersion", packageInfo.versionName ?: "unknown")
            .put("appVersionCode", packageInfo.longVersionCode)
            .put("androidApi", Build.VERSION.SDK_INT)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("buildDisplay", Build.DISPLAY)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("contents", JSONArray(entriesForManifest()))
            .put("excluded", JSONArray(listOf("tailscaled.state", "auth keys", "user messages", "workspace file contents")))
    }

    private fun entriesForManifest() = buildList {
        add("manifest.json")
        add("network.json")
        if (File(context.filesDir, "diagnostics/app.log").isFile) add("app.log")
        eligibleTailscaleLogFiles(File(context.filesDir, "tailnet").listFiles().orEmpty().toList())
            .forEach { add("tailscale/${it.name}") }
    }

    private fun networkSnapshot(): JSONObject {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager.activeNetwork
        val all = manager.allNetworks.toList()
        fun describe(network: android.net.Network): JSONObject {
            val capabilities = manager.getNetworkCapabilities(network)
            val transports = JSONArray()
            if (capabilities != null) {
                listOf(
                    NetworkCapabilities.TRANSPORT_VPN to "vpn",
                    NetworkCapabilities.TRANSPORT_WIFI to "wifi",
                    NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
                    NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
                    NetworkCapabilities.TRANSPORT_USB to "usb",
                ).filter { capabilities.hasTransport(it.first) }.forEach { transports.put(it.second) }
            }
            return JSONObject()
                .put("active", network == active)
                .put("transports", transports)
                .put("internet", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
                .put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                .put("notVpn", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true)
        }
        val activeCapabilities = active?.let(manager::getNetworkCapabilities)
        return JSONObject()
            .put("activePresent", active != null)
            .put("activeVpn", activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
            .put("networks", JSONArray().apply { all.forEach { put(describe(it)) } })
    }
}
