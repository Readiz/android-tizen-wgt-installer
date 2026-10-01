// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.readiz.wgtinstaller.core.*
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Television(val ip: String, val name: String, val model: String)

class WifiLan(context: Context) : LanAccess {
    private val cm = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
    private val network: Network = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.let { c -> c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
    } ?: throw BootstrapError("wifi.missing", "휴대폰을 TV와 같은 Wi-Fi에 연결하세요. VPN·게스트 네트워크에서는 연결되지 않을 수 있습니다.")
    private val address = cm.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }
        ?: throw BootstrapError("wifi.ip", "Wi-Fi의 사설 IPv4 주소를 확인하지 못했습니다.")
    val localIp: String = address.address.hostAddress ?: error("Missing local IP")
    override fun connect(ip: String, cancellation: Cancellation): SdbSession {
        val target = InetSocketAddress(Safety.literalIpv4(ip), 26101)
        var last: Exception? = null
        repeat(3) { attempt ->
            cancellation.check()
            try { return SdbSession.connect(target, cancellation, network.socketFactory) }
            catch (e: Exception) {
                last = e
                // Only retry connection setup, never the installation command.
                if (e is BootstrapError || (e !is java.net.SocketException && e !is java.io.EOFException)) throw e
                if (attempt < 2) Thread.sleep(400L * (attempt + 1))
            }
        }
        throw BootstrapError("sdb.connect", "TV 연결이 끊겼습니다. Host PC IP가 $localIp 인지, 설정 후 TV를 재부팅했는지 확인하세요.", last)
    }
    override fun http(ip: String, cancellation: Cancellation): HttpTransport = SafeHttp(cancellation, Safety.privateIpv4(ip)) { network.openConnection(it) as HttpURLConnection }
    fun discover(onFound: (Television) -> Unit, cancel: Cancellation) {
        val octets = address.address.address
        val ip = octets.fold(0L) { acc, b -> (acc shl 8) or (b.toInt() and 255).toLong() }
        val prefix = maxOf(24, address.prefixLength)
        if (prefix > 30) return
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val start = ip and mask; val end = start or (mask xor 0xffffffffL)
        val workers = Executors.newFixedThreadPool(16)
        try {
            val tasks = (start + 1 until end).filter { it != ip }.map { number -> Callable {
                cancel.check()
                val host = (3 downTo 0).joinToString(".") { ((number ushr (it * 8)) and 255).toString() }
                var conn: HttpURLConnection? = null
                try {
                    Safety.privateIpv4(host)
                    val connection = network.openConnection(URL("http://$host:8001/api/v2/")) as HttpURLConnection
                    conn = connection
                    connection.connectTimeout = 500; connection.readTimeout = 700; connection.instanceFollowRedirects = false
                    val hook = cancel.onCancel { connection.disconnect() }
                    try {
                        if (connection.responseCode == 200) {
                            val root = Json.obj(connection.inputStream.use { it.readBounded(128 * 1024, cancel) }.toString(Charsets.UTF_8))
                            val device = root.objectAt("device")
                            val model = (device["modelName"] as? String ?: device["model"] as? String ?: "").take(100)
                            val name = (device["name"] as? String ?: "Samsung TV").take(100)
                            if (model.isNotBlank() || device.containsKey("developerMode")) onFound(Television(host, name, model))
                        }
                    } finally { hook.close() }
                } catch (_: Exception) { /* Non-TV hosts and unreachable addresses are ignored. */ }
                finally { conn?.disconnect() }
            } }
            workers.invokeAll(tasks, 25, TimeUnit.SECONDS)
        } finally { workers.shutdownNow() }
    }
}
