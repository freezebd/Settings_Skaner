package com.example.settingd.data

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

class NetworkScanner(private val context: Context) {
    private val TAG = "NetworkScanner"
    private val devices = mutableListOf<Device>()
    private val scannedCount = AtomicInteger(0)
    private var onProgressUpdate: ((Float) -> Unit)? = null
    private val prefs = context.getSharedPreferences("NetworkScanner", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    data class Device(
        val ipAddress: String,
        val name: String,
        val mac: String,
        val type: String,
        var isOnline: Boolean = false
    )

    init {
        loadDevices()
        checkOnlineStatus()
    }

    fun setOnProgressUpdateListener(listener: (Float) -> Unit) {
        onProgressUpdate = listener
    }

    suspend fun scanSingleDevice(ipAddress: String): List<Device> = coroutineScope {
        scannedCount.set(0)
        onProgressUpdate?.invoke(0f)

        Log.d(TAG, "Checking single device: $ipAddress")
        
        try {
            checkDeviceAsync(ipAddress)
            scannedCount.incrementAndGet()
            onProgressUpdate?.invoke(100f)
            Log.d(TAG, "Device check completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device at $ipAddress: ${e.message}")
        }
        
        devices
    }

    suspend fun scanNetwork(): List<Device> = coroutineScope {
        scannedCount.set(0)
        onProgressUpdate?.invoke(0f)

        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcpInfo = wifiManager.dhcpInfo
        val ipAddress = dhcpInfo.ipAddress

        // Получаем октеты IP-адреса
        val firstOctet = ipAddress and 0xff
        val secondOctet = (ipAddress shr 8) and 0xff
        val thirdOctet = (ipAddress shr 16) and 0xff

        Log.d(TAG, "Local IP: ${formatIpAddress(ipAddress)}")

        // Создаем список адресов для сканирования (1-254)
        val addresses = mutableListOf<String>()
        
        // Добавляем фиксированный адрес ESP
        addresses.add("192.168.4.1")
        
        // Добавляем адреса локальной сети
        for (i in 1..254) {
            addresses.add("$firstOctet.$secondOctet.$thirdOctet.$i")
        }

        Log.d(TAG, "Starting network scan for ${addresses.size} addresses")
        Log.d(TAG, "Scanning IP range: $firstOctet.$secondOctet.$thirdOctet.1 - $firstOctet.$secondOctet.$thirdOctet.254")

        // Разбиваем адреса на чанки для параллельной обработки
        val chunkSize = 10
        val chunks = addresses.chunked(chunkSize)
        
        // Создаем список для хранения всех корутин
        val jobs = mutableListOf<Deferred<Unit>>()
        
        // Запускаем параллельное сканирование для каждого чанка
        chunks.forEach { chunk ->
            val job = async {
                chunk.forEach { host ->
                    try {
                        checkDeviceAsync(host)
                        val progress = (scannedCount.incrementAndGet().toFloat() / addresses.size) * 100
                        onProgressUpdate?.invoke(progress)
                        Log.d(TAG, "Scanned $host (${scannedCount.get()}/${addresses.size})")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking device at $host: ${e.message}")
                    }
                }
            }
            jobs.add(job)
        }
        
        // Ждем завершения всех корутин
        jobs.awaitAll()
        
        Log.d(TAG, "Network scan completed. Found ${devices.size} devices")
        onProgressUpdate?.invoke(100f)

        // Возвращаем копию списка устройств
        devices.toList()
    }

    private fun formatIpAddress(ip: Int): String {
        return String.format(
            "%d.%d.%d.%d",
            ip shr 24 and 0xff,
            ip shr 16 and 0xff,
            ip shr 8 and 0xff,
            ip and 0xff
        )
    }

    private suspend fun checkDeviceAsync(host: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting device check for $host")
        
        // Проверяем доступность устройства
        if (!isDeviceReachable(host)) {
            Log.d(TAG, "Device at $host is not reachable")
            return@withContext
        }
        Log.d(TAG, "Device at $host is reachable")

        // Отправляем HTTP запрос
        val response = sendHttpRequest(host)
        if (response != null) {
            Log.d(TAG, "Received response from $host: $response")
            try {
                val device = parseDeviceResponse(response, host)
                if (device != null) {
                    synchronized(devices) {
                        // Ищем устройство по MAC-адресу
                        val existingIndex = devices.indexOfFirst { it.mac == device.mac }
                        if (existingIndex != -1) {
                            // Обновляем существующее устройство
                            devices[existingIndex] = device
                            Log.d(TAG, "Updated existing device: $device")
                        } else {
                            // Добавляем новое устройство
                            devices.add(device)
                            Log.d(TAG, "Added new device: $device")
                        }
                        saveDevices() // Сохраняем после каждого изменения
                    }
                    // Уведомляем UI о новом устройстве
                    withContext(Dispatchers.Main) {
                        onProgressUpdate?.invoke(-1f)
                    }
                } else {
                    Log.d(TAG, "Failed to parse device response from $host")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing response from $host: ${e.message}")
            }
        } else {
            Log.d(TAG, "No response received from $host")
        }
    }

    private fun isDeviceReachable(host: String): Boolean {
        var socket: Socket? = null
        return try {
            Log.d(TAG, "Checking device reachability: $host")
            socket = Socket()
            socket.connect(InetSocketAddress(host, 80), 2500)
            socket.soTimeout = 2500

            // Отправляем запрос для проверки статуса
            val request = "GET /settings?action=discover HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
            socket.getOutputStream().flush()

            // Читаем ответ
            val inputStream = socket.getInputStream()
            val response = StringBuilder()
            val buffer = ByteArray(1024)
            var bytesRead: Int
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                val chunk = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
                response.append(chunk)
            }

            // Проверяем наличие JSON в ответе
            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}")
            
            if (jsonStart != -1 && jsonEnd != -1) {
                val jsonStr = response.substring(jsonStart, jsonEnd + 1)
                Log.d(TAG, "Status response from $host: $jsonStr")
                val isDiscoverResponse = jsonStr.contains("\"type\":\"discover\"")
                Log.d(TAG, "Device $host is ${if (isDiscoverResponse) "reachable" else "not responding correctly"}")
                isDiscoverResponse
            } else {
                Log.d(TAG, "Device $host response does not contain valid JSON")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device status at $host: ${e.message}")
            Log.d(TAG, "Device $host is unreachable")
            false
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing socket for $host: ${e.message}")
            }
        }
    }

    private suspend fun sendHttpRequest(ipAddress: String): String? = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            Log.d(TAG, "Connecting to $ipAddress...")
            socket = Socket()
            socket.connect(InetSocketAddress(ipAddress, 80), 2500)
            socket.soTimeout = 2500
            Log.d(TAG, "Connected to $ipAddress")

            // Максимально простой запрос, как в веб-версии
            val request = "GET /settings?action=discover HTTP/1.1\r\nHost: $ipAddress\r\nConnection: close\r\n\r\n"

            Log.d(TAG, "Sending request to $ipAddress: $request")
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
            socket.getOutputStream().flush()
            Log.d(TAG, "Request sent to $ipAddress")

            // Читаем весь ответ как есть
            val inputStream = socket.getInputStream()
            val response = StringBuilder()
            val buffer = ByteArray(1024)
            var bytesRead: Int
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                val chunk = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
                response.append(chunk)
                Log.d(TAG, "Read chunk from $ipAddress: '$chunk'")
            }

            val responseStr = response.toString()
            Log.d(TAG, "Complete response from $ipAddress: '$responseStr'")
            
            // Ищем JSON в ответе
            val jsonStart = responseStr.indexOf("{")
            val jsonEnd = responseStr.lastIndexOf("}")
            
            if (jsonStart != -1 && jsonEnd != -1) {
                val jsonStr = responseStr.substring(jsonStart, jsonEnd + 1)
                Log.d(TAG, "Found JSON from $ipAddress: '$jsonStr'")
                return@withContext jsonStr
            }
            
            Log.e(TAG, "No JSON found in response from $ipAddress")
            return@withContext null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending HTTP request to $ipAddress: ${e.message}")
            e.printStackTrace()
            null
        } finally {
            try {
                socket?.close()
                Log.d(TAG, "Socket closed for $ipAddress")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing socket for $ipAddress: ${e.message}")
            }
        }
    }

    private fun parseDeviceResponse(response: String, ipAddress: String): Device? {
        try {
            Log.d(TAG, "Parsing response: '$response'")
            
            // Проверяем обязательные поля
            if (!response.contains("\"type\":\"discover\"")) {
                Log.e(TAG, "Response does not contain type:discover: '$response'")
                return null
            }

            // Извлекаем поля из JSON
            val name = response.substringAfter("\"name\":\"").substringBefore("\"")
            val mac = response.substringAfter("\"mac\":\"").substringBefore("\"")
            
            if (name.isEmpty() || mac.isEmpty()) {
                Log.e(TAG, "Required fields are empty. name: '$name', mac: '$mac'")
                return null
            }

            Log.d(TAG, "Successfully parsed device: name=$name, mac=$mac")
            
            return Device(
                ipAddress = ipAddress,
                name = name,
                mac = mac,
                type = "discover",
                isOnline = true // Устройство онлайн, так как только что ответило
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing device response: ${e.message}, response: '$response'")
            e.printStackTrace()
        }
        return null
    }

    private fun checkOnlineStatus() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                var statusChanged = false
                devices.forEach { device ->
                    val newStatus = isDeviceReachable(device.ipAddress)
                    Log.d(TAG, "Device ${device.name} (${device.ipAddress}) status check: $newStatus")
                    if (device.isOnline != newStatus) {
                        device.isOnline = newStatus
                        statusChanged = true
                        Log.d(TAG, "Device ${device.name} status changed to: ${if (newStatus) "online" else "offline"}")
                    }
                }
                // Сохраняем и уведомляем UI если статус изменился
                if (statusChanged) {
                    saveDevices() // Сохраняем изменения статуса
                    withContext(Dispatchers.Main) {
                        onProgressUpdate?.invoke(-1f)
                    }
                }
                delay(10000) // Задержка 10 секунд
            }
        }
    }

    fun checkDeviceOnline(ipAddress: String): Boolean {
        return isDeviceReachable(ipAddress)
    }

    private fun saveDevices() {
        val deviceSet = devices.map { device ->
            "${device.ipAddress}|${device.name}|${device.mac}|${device.isOnline}"
        }.toSet()
        prefs.edit().putStringSet("saved_devices", deviceSet).apply()
        Log.d(TAG, "Saved ${devices.size} devices to storage with their status")
    }

    fun clearDevices() {
        devices.clear()
        saveDevices()
        Log.d(TAG, "Cleared all devices")
    }

    fun removeDevice(mac: String) {
        devices.removeAll { it.mac == mac }
        saveDevices()
        Log.d(TAG, "Removed device with MAC: $mac")
    }

    fun getDevices(): List<Device> = devices.toList()

    private fun loadDevices() {
        val savedDevices = prefs.getStringSet("saved_devices", null)
        savedDevices?.forEach { deviceStr ->
            try {
                val parts = deviceStr.split("|")
                if (parts.size >= 3) {
                    val device = Device(
                        ipAddress = parts[0],
                        name = parts[1],
                        mac = parts[2],
                        type = "discover",
                        isOnline = if (parts.size >= 4) parts[3].toBoolean() else false
                    )
                    if (!devices.any { it.mac == device.mac }) {
                        devices.add(device)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading device: $deviceStr", e)
            }
        }
        Log.d(TAG, "Loaded ${devices.size} devices from storage")
        // После загрузки сразу проверяем статус
        checkOnlineStatus()
    }
} 