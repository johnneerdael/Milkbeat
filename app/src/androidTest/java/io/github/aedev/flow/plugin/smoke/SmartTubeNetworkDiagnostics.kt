package io.github.aedev.flow.plugin.smoke

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.Call
import okhttp3.EventListener
import java.io.IOException
import java.net.InetAddress

/** Read-only app-UID evidence. Never expose resolver addresses, proxy credentials, URLs or exception messages. */
internal object SmartTubeNetworkDiagnostics {
    fun profile(context: Context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val active = connectivity.activeNetwork
        val capabilities = active?.let(connectivity::getNetworkCapabilities)
        val properties = active?.let(connectivity::getLinkProperties)
        val process = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        val transports =
            listOf(
                NetworkCapabilities.TRANSPORT_WIFI to "WIFI",
                NetworkCapabilities.TRANSPORT_ETHERNET to "ETHERNET",
                NetworkCapabilities.TRANSPORT_VPN to "VPN",
                NetworkCapabilities.TRANSPORT_CELLULAR to "CELLULAR",
            ).filter { capabilities?.hasTransport(it.first) == true }.joinToString(",") { it.second }
        SmartTubeSmoke.report(
            "NETWORK_UID_PROFILE",
            mapOf(
                "activeNetwork" to (active != null),
                "networkValidated" to (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true),
                "networkTransports" to transports,
                "dnsServerCount" to (properties?.dnsServers?.size ?: 0),
                "privateDnsActive" to (properties?.isPrivateDnsActive == true),
                "internetPermission" to (context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED),
                "backgroundRestriction" to connectivity.restrictBackgroundStatus,
                "processImportance" to process.importance,
                "appUid" to android.os.Process.myUid(),
            ),
        )
    }

    fun listener(): EventListener =
        object : EventListener() {
            private var nameClass = "NONE"
            private var resolvedCount = 0

            override fun dnsStart(
                call: Call,
                domainName: String,
            ) {
                nameClass =
                    when {
                        domainName == "youtube.com" || domainName.endsWith(".youtube.com") -> "YOUTUBE"
                        domainName != call.request().url.host -> "PROXY"
                        else -> "OTHER"
                    }
            }

            override fun dnsEnd(
                call: Call,
                domainName: String,
                inetAddressList: List<InetAddress>,
            ) {
                resolvedCount = inetAddressList.size
            }

            override fun callFailed(
                call: Call,
                ioe: IOException,
            ) {
                val kind = ioe.javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z0-9_${'$'}]{1,60}")) } ?: "IOException"
                SmartTubeSmoke.report(
                    "NETWORK_REQUEST_FAILURE",
                    mapOf("errorType" to kind, "dnsNameClass" to nameClass, "resolvedAddressCount" to resolvedCount),
                )
            }
        }
}
