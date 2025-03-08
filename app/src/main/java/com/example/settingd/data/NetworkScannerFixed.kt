package com.example.settingd.data

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

class NetworkScannerFixed(private val context: Context) {
    private val TAG = "NetworkScannerFixed"
    private val devices = mutableListOf<Device>()
    private val scannedCount = AtomicInteger(0)
    private var onProgressUpdate: ((Float) -> Unit)? = null
    private val prefs = context.getSharedPreferences("NetworkScanner", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var statusCheckJob: Job? = null
    
    // Константы для настройки сканирования
    private val SOCKET_TIMEOUT = 800 // Уменьшаем таймаут
    private val BATCH_SIZE = 32 // Увеличиваем размер батча для параллельного сканирования
    private val STATUS_CHECK_INTERVAL = 3000L

    data class Device(
        val ipAddress: String,
        val name: String,
        val mac: String,
        val type: String,
        var isOnline: Boolean = false
    )

    init {
        loadDevices()
        startStatusCheck()
    }

    fun setOnProgressUpdateListener(listener: (Float) -> Unit) {
        onProgressUpdate = listener
    }

    private fun startStatusCheck() {
        stopStatusCheck()
        statusCheckJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    checkDevicesStatus()
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка при проверке статуса устройств: ${e.message}")
                }
                delay(STATUS_CHECK_INTERVAL)
            }
        }
    }

    private suspend fun checkDevicesStatus() {
        Log.d(TAG, "Начало проверки статуса для ${devices.size} устройств")
        
        val devicesCopy = synchronized(devices) { devices.toList() }
        var hasChanges = false

        // Проверяем устройства последовательно
        devicesCopy.forEach { device ->
            try {
                val isReachable = isDeviceReachable(device.ipAddress)
                synchronized(devices) {
                    val existingDevice = devices.find { it.mac == device.mac }
                    if (existingDevice != null && existingDevice.isOnline != isReachable) {
                        Log.d(TAG, "Статус устройства ${device.name} (${device.ipAddress}) изменился на: ${if (isReachable) "онлайн" else "оффлайн"}")
                        existingDevice.isOnline = isReachable
                        hasChanges = true
                        // Немедленно сохраняем изменения и обновляем UI
                        saveDevices()
                        scope.launch(Dispatchers.Main) {
                            onProgressUpdate?.invoke(-1f)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка проверки ${device.name}: ${e.message}")
            }
        }
    }

    suspend fun scanNetwork(): List<Device> = coroutineScope {
        scannedCount.set(0)
        onProgressUpdate?.invoke(0f)

        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcpInfo = wifiManager.dhcpInfo
        val ipAddress = dhcpInfo.ipAddress

        // Получаем список IP для сканирования
        val addresses = mutableListOf<String>()
        // Добавляем специальный IP для ESP устройств
        addresses.add("192.168.4.1")
        
        // Получаем текущую подсеть
        val firstOctet = ipAddress and 0xff
        val secondOctet = (ipAddress shr 8) and 0xff
        val thirdOctet = (ipAddress shr 16) and 0xff

        // Сканируем всю подсеть
        for (i in 1..254) {
            addresses.add("$firstOctet.$secondOctet.$thirdOctet.$i")
        }

        Log.d(TAG, "Начало сканирования ${addresses.size} адресов")
        val totalAddresses = addresses.size

        // Разбиваем на батчи и сканируем параллельно
        addresses.chunked(BATCH_SIZE).forEachIndexed { batchIndex, batch ->
            coroutineScope {
                val jobs = batch.map { host ->
                    async(Dispatchers.IO) {
                        try {
                            checkDeviceAsync(host)
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка сканирования $host: ${e.message}")
                        }
                    }
                }
                jobs.awaitAll()
                
                val progress = ((batchIndex + 1) * BATCH_SIZE * 100f) / totalAddresses
                withContext(Dispatchers.Main) {
                    onProgressUpdate?.invoke(progress.coerceAtMost(100f))
                }
            }
        }

        Log.d(TAG, "Сканирование завершено. Найдено ${devices.size} устройств")
        withContext(Dispatchers.Main) {
            onProgressUpdate?.invoke(100f)
        }

        devices.toList()
    }

    private fun isDeviceReachable(host: String): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, 80), SOCKET_TIMEOUT)
            socket.isConnected
        } catch (e: Exception) {
            false
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) { }
        }
    }

    private suspend fun checkDeviceAsync(host: String) = withContext(Dispatchers.IO) {
        if (!isDeviceReachable(host)) {
            return@withContext
        }

        val response = sendHttpRequest(host)
        if (response != null) {
            try {
                val device = parseDeviceResponse(response, host)
                if (device != null) {
                    val updatedDevice = device.copy(isOnline = true)
                    synchronized(devices) {
                        val existingIndex = devices.indexOfFirst { it.mac == device.mac }
                        if (existingIndex != -1) {
                            devices[existingIndex] = updatedDevice
                        } else {
                            devices.add(updatedDevice)
                        }
                        saveDevices()
                    }
                    withContext(Dispatchers.Main) {
                        onProgressUpdate?.invoke(-1f)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing response from $host: ${e.message}")
            }
        }
    }

    private suspend fun sendHttpRequest(ipAddress: String): String? = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(ipAddress, 80), SOCKET_TIMEOUT)
            socket.soTimeout = SOCKET_TIMEOUT

            val request = "GET /settings?action=discover HTTP/1.1\r\nHost: $ipAddress\r\nAccept: application/json\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
            socket.getOutputStream().flush()

            val inputStream = socket.getInputStream()
            val response = StringBuilder()
            val buffer = ByteArray(1024)
            var bytesRead: Int
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                response.append(String(buffer, 0, bytesRead, StandardCharsets.UTF_8))
            }

            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}")
            
            if (jsonStart != -1 && jsonEnd != -1) {
                response.substring(jsonStart, jsonEnd + 1)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) { }
        }
    }

    private fun parseDeviceResponse(response: String, ipAddress: String): Device? {
        try {
            val json = JSONObject(response)
            
            // Проверяем тип как в веб-версии
            if (json.optString("type") != "discover") {
                return null
            }

            val name = json.optString("name", "")
            val mac = json.optString("mac", "")
            
            if (name.isEmpty() || mac.isEmpty()) {
                return null
            }
            
            return Device(
                ipAddress = ipAddress,
                name = name,
                mac = mac,
                type = "discover",
                isOnline = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing device response: ${e.message}")
            return null
        }
    }

    private fun saveDevices() {
        synchronized(devices) {
            val deviceSet = devices.map { device ->
                "${device.ipAddress}|${device.name}|${device.mac}|${device.isOnline}"
            }.toSet()
            
            prefs.edit()
                .clear()
                .putStringSet("saved_devices", deviceSet)
                .apply()
        }
    }

    fun clearDevices() {
        devices.clear()
        saveDevices()
    }

    fun removeDevice(mac: String) {
        devices.removeAll { it.mac == mac }
        saveDevices()
    }

    fun getDevices(): List<Device> = synchronized(devices) { devices.toList() }

    private fun loadDevices() {
        synchronized(devices) {
            val savedDevices = prefs.getStringSet("saved_devices", null)
            devices.clear()
            
            savedDevices?.forEach { deviceStr ->
                try {
                    val parts = deviceStr.split("|")
                    if (parts.size >= 4) { // Теперь проверяем 4 части
                        val device = Device(
                            ipAddress = parts[0],
                            name = parts[1],
                            mac = parts[2],
                            type = "discover",
                            isOnline = parts[3].toBoolean() // Загружаем сохраненное состояние
                        )
                        if (!devices.any { it.mac == device.mac }) {
                            devices.add(device)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка загрузки устройства: $deviceStr", e)
                }
            }
        }
    }

    fun cleanup() {
        stopStatusCheck()
        scope.cancel()
    }

    private fun stopStatusCheck() {
        statusCheckJob?.cancel()
        statusCheckJob = null
    }

    suspend fun scanSingleDevice(ipAddress: String): List<Device> = coroutineScope {
        scannedCount.set(0)
        onProgressUpdate?.invoke(0f)

        try {
            checkDeviceAsync(ipAddress)
            scannedCount.incrementAndGet()
            onProgressUpdate?.invoke(100f)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device at $ipAddress: ${e.message}")
        }
        
        devices
    }
} 