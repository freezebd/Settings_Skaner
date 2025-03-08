package com.example.settingd.data

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class NetworkScannerNew(private val context: Context) {
    private val TAG = "NetworkScannerNew"
    private val devices = mutableListOf<Device>()
    private val scannedCount = AtomicInteger(0)
    private val CONNECT_TIMEOUT = 2500 // 2.5 seconds
    private val READ_TIMEOUT = 2500 // 2.5 seconds
    private val MAX_RETRIES = 1 // Одна попытка как в веб-версии
    private val MAX_RESPONSE_SIZE = 1024 * 1024 // 1MB
    private var localIpAddress: String? = null

    data class Device(
        val ipAddress: String,
        val name: String,
        val type: String,
        val mac: String,
        val version: String,
        val isOnline: Boolean = true
    )

    fun getLocalIpAddress(): String {
        if (localIpAddress == null) {
            localIpAddress = findLocalIpAddress()
        }
        return localIpAddress ?: "Unknown"
    }

    private fun findLocalIpAddress(): String? {
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP address: ${e.message}")
        }
        return null
    }

    suspend fun scanNetwork(onProgress: (Float) -> Unit = {}): List<Device> = coroutineScope {
        val existingDevices = devices.toList()
        scannedCount.set(0)

        // Получаем информацию о текущей WiFi сети
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
        
        if (network == null || networkCapabilities == null || 
            !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            Log.e(TAG, "No WiFi connection available")
            // Помечаем все устройства как оффлайн
            existingDevices.forEach { device ->
                synchronized(devices) {
                    val index = devices.indexOfFirst { it.mac == device.mac }
                    if (index != -1) {
                        devices[index] = device.copy(isOnline = false)
                    }
                }
            }
            return@coroutineScope devices.toList()
        }

        // Получаем IP-адрес устройства и маску подсети
        val localIpAddress = getLocalIpAddress()
        if (localIpAddress == "Unknown") {
            Log.e(TAG, "Could not get local IP address")
            return@coroutineScope devices.toList()
        }

        // Разбиваем IP-адрес на октеты
        val ipParts = localIpAddress.split(".")
        if (ipParts.size != 4) {
            Log.e(TAG, "Invalid IP address format")
            return@coroutineScope devices.toList()
        }

        // Создаем список адресов для сканирования
        val subnet = "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}"
        val addresses = (1..254).map { "$subnet.$it" }
        
        val jobs = mutableListOf<Job>()

        // Сначала помечаем все существующие устройства как оффлайн
        existingDevices.forEach { device ->
            synchronized(devices) {
                val index = devices.indexOfFirst { it.mac == device.mac }
                if (index != -1) {
                    devices[index] = device.copy(isOnline = false)
                }
            }
        }

        // Сканируем адреса
        val chunks = addresses.chunked(10)
        chunks.forEach { chunk ->
            chunk.forEach { host ->
                jobs += launch {
                    try {
                        checkDeviceAsync(host)
                        val progress = scannedCount.incrementAndGet().toFloat() / addresses.size
                        onProgress(progress)
                        Log.d(TAG, "Scanned $host (${(progress * 100).toInt()}%)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking device at $host: ${e.message}")
                    }
                }
            }
        }

        // Ждем завершения всех проверок
        jobs.forEach { it.join() }
        
        Log.d(TAG, "Network scan completed. Found ${devices.size} devices")
        devices.toList()
    }

    private suspend fun checkDeviceAsync(ip: String) {
        var retryCount = 0
        while (retryCount < MAX_RETRIES) {
            try {
                withContext(Dispatchers.IO) {
                    Log.d(TAG, "Attempting to connect to $ip (attempt ${retryCount + 1})")
                    
                    // Сначала проверяем базовую доступность устройства
                    if (!isDeviceReachable(ip)) {
                        Log.d(TAG, "Device $ip is not reachable")
                        return@withContext
                    }
                    
                    val socket = Socket()
                    Log.d(TAG, "Socket created for $ip")
                    
                    socket.connect(InetSocketAddress(ip, 80), CONNECT_TIMEOUT)
                    Log.d(TAG, "Connected to $ip")
                    
                    socket.soTimeout = READ_TIMEOUT
                    Log.d(TAG, "Set socket timeout for $ip")

                    // Отправляем HTTP GET запрос
                    val request = """
                        GET /settings?action=discover HTTP/1.1
                        Host: $ip
                        Accept: */*
                        Accept-Encoding: gzip, deflate
                        Connection: close
                        User-Agent: Mozilla/5.0 SettingsDiscover/1.0
                        
                    """.trimIndent().replace("\n", "\r\n") + "\r\n"

                    Log.d(TAG, "Sending request to $ip")
                    socket.getOutputStream().write(request.toByteArray())
                    socket.getOutputStream().flush()

                    // Читаем ответ
                    val inputStream = socket.getInputStream()
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val response = StringBuilder()
                    var line: String?
                    var isBody = false

                    while (reader.readLine().also { line = it } != null) {
                        if (line.isNullOrEmpty()) {
                            isBody = true
                            continue
                        }
                        if (isBody) {
                            response.append(line)
                        }
                    }

                    val responseStr = response.toString()
                    Log.d(TAG, "Response from $ip: $responseStr")

                    if (responseStr.isNotEmpty()) {
                        try {
                            val json = JSONObject(responseStr)
                            val device = Device(
                                ipAddress = ip,
                                name = json.getString("name"),
                                type = json.optString("type", "Unknown"),
                                mac = json.getString("mac"),
                                version = json.optString("version", "Unknown"),
                                isOnline = true
                            )
                            synchronized(devices) {
                                // Удаляем старую версию устройства, если она есть
                                devices.removeIf { it.mac == device.mac }
                                // Добавляем новую версию
                                devices.add(device)
                            }
                            Log.d(TAG, "Found device: $device")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing JSON from $ip: ${e.message}")
                        }
                    }

                    socket.close()
                }
                return
            } catch (e: Exception) {
                Log.e(TAG, "Error checking device $ip (attempt ${retryCount + 1}): ${e.message}")
                Log.e(TAG, "Stack trace:", e)
                retryCount++
                if (retryCount < MAX_RETRIES) {
                    delay(1000) // Ждем 1 секунду перед следующей попыткой
                }
            }
        }
    }

    private suspend fun fetchResource(host: String, resourceUrl: String) = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, 80), CONNECT_TIMEOUT)
            socket.soTimeout = READ_TIMEOUT

            val request = """
                GET $resourceUrl HTTP/1.1
                Host: $host
                Accept: */*
                Accept-Encoding: gzip, deflate
                Connection: close
                User-Agent: Mozilla/5.0 SettingsDiscover/1.0
                
            """.trimIndent().replace("\n", "\r\n") + "\r\n"

            socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
            socket.getOutputStream().flush()

            val inputStream = socket.getInputStream()
            val response = StringBuilder()
            var isHeader = true
            var isGzipped = false
            
            // Читаем заголовки
            while (isHeader) {
                val line = readLine(inputStream)
                if (line == null || line.isEmpty()) {
                    isHeader = false
                    continue
                }
                Log.d(TAG, "Resource header: $line")
                if (line.startsWith("Content-Encoding:", ignoreCase = true) && 
                    line.contains("gzip", ignoreCase = true)) {
                    isGzipped = true
                }
            }
            
            // Читаем тело ответа
            var responseStream = inputStream
            if (isGzipped) {
                responseStream = GZIPInputStream(inputStream)
            }
            
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (responseStream.read(buffer).also { bytesRead = it } != -1) {
                val chunk = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
                response.append(chunk)
            }

            Log.d(TAG, "Resource content from $resourceUrl: ${response.toString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching resource $resourceUrl: ${e.message}")
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing socket for $resourceUrl: ${e.message}")
            }
        }
    }

    private fun isDeviceReachable(host: String): Boolean {
        var socket: Socket? = null
        return try {
            Log.d(TAG, "Checking if device $host is reachable...")
            socket = Socket()
            socket.connect(InetSocketAddress(host, 80), CONNECT_TIMEOUT)
            Log.d(TAG, "Device $host is reachable")
            socket.close()
            true
        } catch (e: Exception) {
            Log.d(TAG, "Device $host is not reachable: ${e.message}")
            false
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing socket for $host: ${e.message}")
            }
        }
    }

    private fun parseDeviceResponse(host: String, response: String): Device? {
        return try {
            val json = JSONObject(response)
            Device(
                ipAddress = host,
                name = json.getString("name"),
                type = json.getString("type"),
                mac = json.getString("mac"),
                version = json.getString("version"),
                isOnline = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing device response: ${e.message}")
            null
        }
    }

    private fun readLine(inputStream: java.io.InputStream): String? {
        val line = StringBuilder()
        var c: Int
        while (inputStream.read().also { c = it } != -1) {
            if (c == '\n'.code) {
                break
            }
            if (c != '\r'.code) {
                line.append(c.toChar())
            }
        }
        return if (line.isEmpty() && c == -1) null else line.toString()
    }

    suspend fun checkDevicesStatus(
        existingDevices: List<Device>,
        onStatusUpdated: (List<Device>) -> Unit
    ) = withContext(Dispatchers.IO) {
        val updatedDevices = mutableListOf<Device>()

        existingDevices.forEach { device ->
            try {
                Log.d(TAG, "Checking status for device: ${device.ipAddress}")
                var socket: Socket? = null
                try {
                    socket = Socket()
                    socket.connect(InetSocketAddress(device.ipAddress, 80), CONNECT_TIMEOUT)
                    socket.soTimeout = READ_TIMEOUT

                    val request = """
                        GET /settings?action=discover HTTP/1.1
                        Host: ${device.ipAddress}
                        Accept: */*
                        Connection: close
                        
                    """.trimIndent().replace("\n", "\r\n") + "\r\n"

                    Log.d(TAG, "Sending request to ${device.ipAddress}")
                    socket.getOutputStream().write(request.toByteArray())
                    socket.getOutputStream().flush()

                    val inputStream = socket.getInputStream()
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val response = StringBuilder()
                    var line: String?
                    var isBody = false

                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "Received line: $line")
                        if (line.isNullOrEmpty()) {
                            isBody = true
                            continue
                        }
                        if (isBody) {
                            response.append(line)
                        }
                    }

                    val responseStr = response.toString()
                    Log.d(TAG, "Response from ${device.ipAddress}: $responseStr")

                    if (responseStr.isNotEmpty()) {
                        try {
                            val json = JSONObject(responseStr)
                            val responseMac = json.optString("mac", "")
                            Log.d(TAG, "Comparing MACs - Device: ${device.mac}, Response: $responseMac")
                            
                            if (responseMac == device.mac) {
                                Log.d(TAG, "Device ${device.ipAddress} is ONLINE")
                                updatedDevices.add(device.copy(isOnline = true))
                            } else {
                                Log.d(TAG, "Device ${device.ipAddress} MAC mismatch")
                                updatedDevices.add(device.copy(isOnline = false))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing JSON from ${device.ipAddress}: ${e.message}")
                            updatedDevices.add(device.copy(isOnline = false))
                        }
                    } else {
                        Log.d(TAG, "Empty response from ${device.ipAddress}")
                        updatedDevices.add(device.copy(isOnline = false))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Connection error for ${device.ipAddress}: ${e.message}")
                    updatedDevices.add(device.copy(isOnline = false))
                } finally {
                    try {
                        socket?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing socket for ${device.ipAddress}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "General error checking ${device.ipAddress}: ${e.message}")
                updatedDevices.add(device.copy(isOnline = false))
            }
        }

        withContext(Dispatchers.Main) {
            onStatusUpdated(updatedDevices)
        }
    }

    suspend fun checkSingleDevice(ipAddress: String): Device? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Checking single device at $ipAddress")
            if (!isDeviceReachable(ipAddress)) {
                Log.d(TAG, "Device at $ipAddress is not reachable")
                return@withContext null
            }

            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(ipAddress, 80), CONNECT_TIMEOUT)
                socket.soTimeout = READ_TIMEOUT

                val request = """
                    GET /settings?action=discover HTTP/1.1
                    Host: $ipAddress
                    Accept: */*
                    Accept-Encoding: gzip, deflate
                    Connection: close
                    User-Agent: Mozilla/5.0 SettingsDiscover/1.0
                    
                """.trimIndent().replace("\n", "\r\n") + "\r\n"

                Log.d(TAG, "Sending request to $ipAddress")
                socket.getOutputStream().write(request.toByteArray())
                socket.getOutputStream().flush()

                val inputStream = socket.getInputStream()
                val reader = BufferedReader(InputStreamReader(inputStream))
                val response = StringBuilder()
                var line: String?
                var isBody = false

                while (reader.readLine().also { line = it } != null) {
                    if (line.isNullOrEmpty()) {
                        isBody = true
                        continue
                    }
                    if (isBody) {
                        response.append(line)
                    }
                }

                val responseStr = response.toString()
                Log.d(TAG, "Response from $ipAddress: $responseStr")

                if (responseStr.isNotEmpty()) {
                    try {
                        val json = JSONObject(responseStr)
                        return@withContext Device(
                            ipAddress = ipAddress,
                            name = json.getString("name"),
                            type = json.optString("type", "Unknown"),
                            mac = json.getString("mac"),
                            version = json.optString("version", "Unknown"),
                            isOnline = true
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing JSON from $ipAddress: ${e.message}")
                    }
                }
            } finally {
                socket?.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device at $ipAddress: ${e.message}")
        }
        return@withContext null
    }
} 