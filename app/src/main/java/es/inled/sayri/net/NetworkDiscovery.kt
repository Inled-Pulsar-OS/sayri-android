package es.inled.sayri.net

import android.content.Context
import android.net.wifi.WifiManager
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class DiscoveredHost(
    val ip: String,
    val port: Int = 8765,
    val name: String = "Pulsar OS",
    val version: String = "",
    val activeAgent: String = "default"
)

object NetworkDiscovery {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(400, TimeUnit.MILLISECONDS)
        .readTimeout(600, TimeUnit.MILLISECONDS)
        .build()

    suspend fun discoverSayriHosts(context: Context): List<DiscoveredHost> = withContext(Dispatchers.IO) {
        val found = mutableMapOf<String, DiscoveredHost>()

        // 1. Send UDP Broadcast
        val udpJob = launch {
            try {
                val socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 800
                }
                val buffer = "SAYRI_DISCOVER".toByteArray()
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(buffer, buffer.size, broadcastAddr, 8765)
                socket.send(packet)

                val recvBuf = ByteArray(1024)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 1000) {
                    try {
                        socket.receive(recvPacket)
                        val msg = String(recvPacket.data, 0, recvPacket.length)
                        val hostIp = recvPacket.address.hostAddress ?: continue
                        found[hostIp] = DiscoveredHost(
                            ip = hostIp,
                            port = 8765,
                            name = "Pulsar OS",
                            version = "0.1.38"
                        )
                    } catch (e: Exception) {
                        break
                    }
                }
                socket.close()
            } catch (e: Exception) {
                // UDP broadcast error
            }
        }

        // 2. Scan Local Subnet via HTTP /api/v1/status in parallel
        val subnetJob = launch {
            val subnetPrefix = getSubnetPrefix(context) ?: "172.16.180"
            val scanJobs = (1..254).map { i ->
                async {
                    val targetIp = "$subnetPrefix.$i"
                    try {
                        val req = Request.Builder()
                            .url("http://$targetIp:8765/api/v1/status")
                            .get()
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val body = resp.body?.string() ?: ""
                                val json = gson.fromJson(body, Map::class.java)
                                val platform = json["platform"]?.toString() ?: "Pulsar OS"
                                val version = json["version"]?.toString() ?: ""
                                val agent = json["active_agent"]?.toString() ?: "default"
                                synchronized(found) {
                                    found[targetIp] = DiscoveredHost(
                                        ip = targetIp,
                                        port = 8765,
                                        name = platform,
                                        version = version,
                                        activeAgent = agent
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Not reachable
                    }
                }
            }
            scanJobs.awaitAll()
        }

        joinAll(udpJob, subnetJob)
        found.values.toList()
    }

    private fun getSubnetPrefix(context: Context): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val ipInt = wm.connectionInfo?.ipAddress ?: return null
        if (ipInt == 0) return null
        val b1 = ipInt and 0xff
        val b2 = (ipInt shr 8) and 0xff
        val b3 = (ipInt shr 16) and 0xff
        return "$b1.$b2.$b3"
    }
}
