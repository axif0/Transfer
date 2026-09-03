package com.matanh.transfer.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface

data class NetworkInfo(
    val localIp: String? = null,        // e.g. 192.168.1.42
    val localHostname: String? = null,  // e.g. pixel‑2.lan
    val hotspotIp: String? = null,       // e.g. 10.0.0.1 (when acting as hotspot)
    val hasCellular: Boolean = false,
) {
    val mainIp: String?
        get() =  when {
            localIp != null -> localIp
            hotspotIp != null -> hotspotIp
            else -> null
    }
}


/**
 * A helper class to monitor network state and provide the current WiFi IP address.
 * It uses ConnectivityManager.NetworkCallback to listen for network changes.
 */
class NetworkHelper(context: Context,
                    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private data class IpInfo(val wifiAddress: Inet4Address?, val hotspotIp: String?)

    private val applicationContext = context.applicationContext
    private val connectivityManager =
        applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkInfo  = MutableStateFlow<NetworkInfo>(NetworkInfo())
    val networkInfo : StateFlow<NetworkInfo> = _networkInfo.asStateFlow()


    private val hotspotReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            // Any change in hotspot state ⇒ re‑scan interfaces
            refresh()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network)  {refresh()}
        override fun onLost(network: Network)       {refresh()}
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {refresh()}
    }

    init {
        // Get initial IP address on creation
        refresh()
    }

    /**
     * Registers the network callback to start listening for network changes.
     */
    fun register() {
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        } catch (e: SecurityException) {
            Timber.e(e, "Failed to register network callback. Missing ACCESS_NETWORK_STATE permission?")
        }
        applicationContext.registerReceiver(
            hotspotReceiver,
            IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED")
        )

    }

    /**
     * Unregisters the network callback to stop listening and prevent memory leaks.
     */
    fun unregister() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Timber.d("Network callback unregistered.")
        } catch (e: Exception) {
            // Can throw IllegalArgumentException if not registered, which is safe to ignore.
            Timber.e(e, "Error unregistering network callback.")
        }
    }

    /**
     * Fetches the current local IP address and updates the state flow.
     */
    private fun refresh() = scope.launch {
        val wifiname = getWifiInterfaceName(applicationContext)
        Timber.i("Wifi interface name: $wifiname")

        val ipInfo = findIpAddresses(wifiname)
        val newLocalIp = ipInfo.wifiAddress
        val newHotspot = ipInfo.hotspotIp


        val newHostname = newLocalIp?.let { findHostname(it) }

        val newSnapshot = NetworkInfo(
            localIp = newLocalIp?.hostAddress,
            localHostname = newHostname,
            hotspotIp = newHotspot,
            hasCellular = hasCellularConnectivity(),
        )

        if (newSnapshot != _networkInfo.value) {
            _networkInfo.value = newSnapshot
            Timber.i("Network info updated → $newSnapshot")
        }
    }
    private suspend fun findHostname(addr: Inet4Address): String? = withContext(Dispatchers.IO) {
        runCatching { addr.canonicalHostName }.getOrNull()?.takeIf { it != addr.hostAddress }
    }

    private fun hasCellularConnectivity(): Boolean {
        for (network in connectivityManager.allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * find the name of the Wi‑Fi interface.
     */
    fun getWifiInterfaceName(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            // a Wi‑Fi
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            // not a VPN
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue

            val lp = cm.getLinkProperties(network)
            val iface = lp?.interfaceName
            if (!iface.isNullOrBlank()) return iface
        }
        return null
    }


    /**
     * Scans all active network interfaces to find the device's local IPv4 addresses
     * for both the Wi‑Fi client and the hotspot.
     *
     * If [wifiName], its address will be used
     * as the Wi‑Fi IP . Otherwise, the first interface will be treated as Wi‑Fi,
     * and the second (if any) as hotspot.
     *
     * @return An [IpInfo] containing the nullable Wi‑Fi and hotspot IP addresses.
     */
    private fun findIpAddresses(wifiName:String?): IpInfo {
        var wifiAddress: Inet4Address? = null
        var hotspotIp: String? = null

        // Collect all relevant interfaces (Wi‑Fi or hotspot)
        val interfaces = NetworkInterface
            .getNetworkInterfaces()
            .toList()
            .asSequence()
            .filter { it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")|| it.name.startsWith("wifi") }
            .filter { it.isUp && !it.isLoopback }
            .toList()
        if (interfaces.isEmpty()) {
            Timber.w("No Wi‑Fi or hotspot interfaces found")
            return IpInfo(null, null)
        }
        val chosenWifi = wifiName?.let { name ->
            interfaces.find { it.name == name } ?: run { Timber.e("Wi‑Fi IP on interface $name not found"); null }
        }
        // Determine the hotspot interface as the first other one
        val chosenHotspot = interfaces.firstOrNull { it.name != chosenWifi?.name }

        // Helper to extract IPv4
        fun extractIp(nif: NetworkInterface?): Inet4Address? = nif
            ?.inetAddresses
            ?.asSequence()
            ?.filterIsInstance<Inet4Address>()
            ?.find { !it.isLoopbackAddress }

        // Extract IPs
        wifiAddress = extractIp(chosenWifi).also {
            if (it != null) Timber.i("Wi‑Fi on ${chosenWifi?.name} → ${it.hostAddress}")
            else Timber.w("No IPv4 address on Wi‑Fi interface ${chosenWifi?.name}")
        }

        hotspotIp = extractIp(chosenHotspot)?.hostAddress?.also {
            Timber.i("Hotspot on ${chosenHotspot?.name} → $it")
        } ?: run {
            Timber.w("No hotspot interface or no IPv4 address on hotspot interface")
            null
        }

        return IpInfo(wifiAddress, hotspotIp)
    }



    /**
     * Scans network interfaces to find the device's local IPv4 address on the WiFi network.
     */
    private fun getWifiIp(): Inet4Address? {
        val networks = connectivityManager.allNetworks
        for (network in networks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val linkProperties = connectivityManager.getLinkProperties(network)
                linkProperties?.linkAddresses?.forEach { linkAddress ->
                    val ipAddress = linkAddress.address
                    // We want an IPv4 address that is not a loopback address.
                    if (ipAddress is Inet4Address && !ipAddress.isLoopbackAddress) {
                        return ipAddress
                    }
                }
            }
        }
        return null
    }
    private fun getHotspotIP(): String? {
        // TODO: also track changes like on/off
        NetworkInterface.getNetworkInterfaces().iterator().asSequence()
            .filter { ni ->
                ni.isUp &&
                        !ni.isLoopback &&
                        // common Soft-AP interface prefixes
                        (ni.name.startsWith("ap") || ni.name.startsWith("wlan") ||
                                ni.name.contains("swlan", ignoreCase = true))
            }
            .flatMap { ni -> ni.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .firstOrNull()
            ?.let { return it.hostAddress }
        return null

    }
    }
