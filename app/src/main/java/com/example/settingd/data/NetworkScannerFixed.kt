package com.example.settingd.data

import android.content.Context
import android.net.DhcpInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class NetworkScannerFixed(private val context: Context) {
    private val TAG = "NetworkScannerFixed"
    private val SOCKET_TIMEOUT = 800 // ms
    private val BATCH_SIZE = 32 // количество одновременных проверок
    private val STATUS_CHECK_INTERVAL = 3000L // ms
    
    private val devices = ConcurrentHashMap<String, Device>()
    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress = _scanProgress

    data class Device(
        val ipAddress: String,
        val mac: String,
        val name: String,
        var isOnline: Boolean = true,
        val type: String = "ESP",
        val version: String = ""
    )

    suspend fun scanNetwork(onProgressUpdate: (Float) -> Unit = {}) = coroutineScope {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcpInfo: DhcpInfo = wifiManager.dhcpInfo
        val baseIp = dhcpInfo.gateway

        val addresses = mutableListOf<String>()
        // Сканируем адреса в диапазоне шлюза
        for (i in 1..254) {
            val testIp = (baseIp and 0xFF000000.toInt()) or
                        ((baseIp and 0x00FF0000.toInt()) shr 16 shl 16) or
                        ((baseIp and 0x0000FF00.toInt()) shr 8 shl 8) or i
            addresses.add(formatIp(testIp))
        }

        // Добавляем специальный IP для ESP устройств
        addresses.add("192.168.4.1")

        val totalAddresses = addresses.size
        var scannedCount = 0

        // Разбиваем адреса на батчи для параллельного сканирования
        addresses.chunked(BATCH_SIZE).forEach { batch ->
            val deferreds = batch.map { address ->
                async(Dispatchers.IO) {
                    checkDevice(address)
                    scannedCount++
                    val progress = (scannedCount.toFloat() / totalAddresses) * 100
                    _scanProgress.value = progress
                    onProgressUpdate(progress)
                }
            }
            deferreds.awaitAll()
        }

        _scanProgress.value = -1f
        devices.values.toList()
    }

    private suspend fun checkDevice(ipAddress: String) {
        if (!isDeviceReachable(ipAddress)) {
            return
        }

        val response = sendHttpRequest(ipAddress)
        if (response != null) {
            try {
                parseDeviceResponse(ipAddress, response)?.let { device ->
                    devices[device.mac] = device
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing response from $ipAddress: ${e.message}")
            }
        }
    }

    private fun isDeviceReachable(host: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, 80), SOCKET_TIMEOUT)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun sendHttpRequest(ipAddress: String): String? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ipAddress, 80), SOCKET_TIMEOUT)
                socket.soTimeout = SOCKET_TIMEOUT

                // Формируем запрос в формате GyverSettings
                val request = """
                    GET /settings?action=discover HTTP/1.1
                    Host: $ipAddress
                    Connection: close
                    User-Agent: SettingsDiscover/1.0.0
                    Accept: application/json
                    
                """.trimIndent()

                socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))

                BufferedReader(InputStreamReader(socket.getInputStream())).use { reader ->
                    val response = StringBuilder()
                    var line: String?
                    var emptyLineFound = false

                    while (reader.readLine().also { line = it } != null) {
                        response.append(line).append("\n")
                        if (line?.isEmpty() == true) {
                            emptyLineFound = true
                            // Читаем тело ответа
                            while (reader.readLine().also { line = it } != null) {
                                response.append(line).append("\n")
                            }
                            break
                        }
                    }

                    if (emptyLineFound) response.toString() else null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending HTTP request to $ipAddress: ${e.message}")
            null
        }
    }

    private fun parseDeviceResponse(ipAddress: String, response: String): Device? {
        try {
            if (!response.contains("application/json")) {
                return null
            }

            val jsonStart = response.indexOf("{")
            if (jsonStart == -1) {
                return null
            }

            val jsonStr = response.substring(jsonStart).trim()
            val json = JSONObject(jsonStr)

            // Парсим ответ в формате GyverSettings
            return Device(
                ipAddress = ipAddress,
                mac = json.optString("mac", "unknown"),
                name = json.optString("name", "ESP Device"),
                isOnline = true,
                type = json.optString("type", "ESP"),
                version = json.optString("version", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSON from $ipAddress: ${e.message}")
            return null
        }
    }

    private fun formatIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }

    suspend fun checkDevicesStatus() = coroutineScope {
        while (true) {
            devices.forEach { (mac, device) ->
                launch(Dispatchers.IO) {
                    val isOnline = isDeviceReachable(device.ipAddress)
                    val updatedDevice = device.copy(isOnline = isOnline)
                    devices[mac] = updatedDevice
                }
            }
            delay(STATUS_CHECK_INTERVAL)
        }
    }

    fun getDevices(): List<Device> = devices.values.toList()

    fun removeDevice(mac: String) {
        devices.remove(mac)
    }
} 