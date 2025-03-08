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
        devices.clear()
        scannedCount.set(0)

        // Получаем информацию о текущей WiFi сети
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
        
        if (network == null || networkCapabilities == null || 
            !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            Log.e(TAG, "No WiFi connection available")
            return@coroutineScope emptyList()
        }

        // Получаем IP-адрес устройства и маску подсети
        val localIpAddress = getLocalIpAddress()
        if (localIpAddress == "Unknown") {
            Log.e(TAG, "Could not get local IP address")
            return@coroutineScope emptyList()
        }

        // Разбиваем IP-адрес на октеты
        val ipParts = localIpAddress.split(".")
        if (ipParts.size != 4) {
            Log.e(TAG, "Invalid IP address format")
            return@coroutineScope emptyList()
        }

        // Создаем список адресов для сканирования
        val subnet = "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}"
        
        // Сначала проверяем существующие устройства
        val existingAddresses = existingDevices.map { it.ipAddress }
        val newAddresses = (1..254).map { "$subnet.$it" }.filter { it !in existingAddresses }
        
        val jobs = mutableListOf<Job>()

        // Проверяем существующие устройства
        existingDevices.forEach { device ->
            jobs += launch {
                try {
                    val isReachable = isDeviceReachable(device.ipAddress)
                    if (isReachable) {
                        checkDeviceAsync(device.ipAddress)
                    } else {
                        // Если устройство недоступно, добавляем его в список как оффлайн
                        synchronized(devices) {
                            devices.add(device.copy(isOnline = false))
                        }
                    }
                    val progress = scannedCount.incrementAndGet().toFloat() / (existingDevices.size + newAddresses.size)
                    onProgress(progress)
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking existing device ${device.ipAddress}: ${e.message}")
                    synchronized(devices) {
                        devices.add(device.copy(isOnline = false))
                    }
                }
            }
        }

        // Сканируем новые адреса
        val chunks = newAddresses.chunked(10)
        chunks.forEach { chunk ->
            chunk.forEach { host ->
                jobs += launch {
                    try {
                        checkDeviceAsync(host)
                        val progress = scannedCount.incrementAndGet().toFloat() / (existingDevices.size + newAddresses.size)
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

                    // Отправляем HTTP GET запрос к корневому пути
                    val request = """
                        GET /settings?action=discover HTTP/1.1
                        Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7
                        Accept-Encoding: gzip, deflate
                        Accept-Language: ru,en;q=0.9
                        Cache-Control: max-age=0
                        Connection: keep-alive
                        Host: $ip
                        Upgrade-Insecure-Requests: 1
                        User-Agent: Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36

                    """.trimIndent().replace("\n", "\r\n") + "\r\n"

                    Log.d(TAG, "Sending request to $ip:\n$request")
                    val outputStream = socket.getOutputStream()
                    outputStream.write(request.toByteArray())
                    outputStream.flush()
                    Log.d(TAG, "Request sent to $ip")

                    // Читаем ответ
                    val inputStream = socket.getInputStream()
                    
                    // Читаем заголовки
                    var contentLength = 0
                    var isGzip = false
                    var statusCode = 0
                    val headerBuffer = ByteArray(8192)
                    var headerEnd = false
                    var headerBytesRead = 0
                    val headerBuilder = StringBuilder()
                    
                    // Читаем заголовки до двойного CRLF
                    while (!headerEnd && headerBytesRead < headerBuffer.size) {
                        val read = inputStream.read(headerBuffer, headerBytesRead, 1)
                        if (read == -1) break
                        
                        headerBytesRead++
                        headerBuilder.append(headerBuffer[headerBytesRead - 1].toInt().toChar())
                        
                        if (headerBuilder.endsWith("\r\n\r\n")) {
                            headerEnd = true
                        }
                    }
                    
                    // Парсим заголовки
                    val headers = headerBuilder.toString().split("\r\n")
                    headers.forEach { line ->
                        if (line.isNotEmpty()) {
                            Log.d(TAG, "Response header from $ip: $line")
                            if (line.startsWith("HTTP/1.1 ")) {
                                statusCode = line.substring(9, 12).toInt()
                            } else if (line.startsWith("Content-Length: ")) {
                                contentLength = line.substring(15).trim().toInt()
                            } else if (line.startsWith("Content-Encoding: ") && line.contains("gzip")) {
                                isGzip = true
                            }
                        }
                    }

                    // Проверяем статус ответа
                    if (statusCode != 200) {
                        Log.w(TAG, "Bad response status from $ip: $statusCode")
                        socket.close()
                        return@withContext
                    }

                    // Проверяем размер ответа
                    if (contentLength > MAX_RESPONSE_SIZE) {
                        Log.w(TAG, "Response too large from $ip: $contentLength bytes")
                        socket.close()
                        return@withContext
                    }

                    // Читаем тело ответа
                    val responseStream = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    
                    if (contentLength > 0) {
                        // Если есть Content-Length, читаем указанное количество байт
                        var bytesRead = 0
                        while (bytesRead < contentLength) {
                            val read = inputStream.read(buffer, 0, Math.min(buffer.size, contentLength - bytesRead))
                            if (read == -1) break
                            responseStream.write(buffer, 0, read)
                            bytesRead += read
                        }
                    } else {
                        // Если нет Content-Length, читаем до закрытия соединения
                        try {
                            while (true) {
                                val read = inputStream.read(buffer)
                                if (read == -1) break
                                responseStream.write(buffer, 0, read)
                            }
                        } catch (e: Exception) {
                            // Игнорируем ошибку закрытия соединения
                            Log.d(TAG, "Connection closed by server")
                        }
                    }

                    val responseBytes = responseStream.toByteArray()
                    Log.d(TAG, "Read ${responseBytes.size} bytes from $ip")

                    // Распаковываем gzip если нужно
                    val responseBody = if (isGzip) {
                        try {
                            val gzipStream = GZIPInputStream(responseBytes.inputStream())
                            val result = gzipStream.readBytes()
                            String(result, StandardCharsets.UTF_8)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to decompress gzip response: ${e.message}")
                            String(responseBytes, StandardCharsets.UTF_8)
                        }
                    } else {
                        String(responseBytes, StandardCharsets.UTF_8)
                    }

                    Log.d(TAG, "Raw response from $ip: $responseBody")

                    try {
                        val json = JSONObject(responseBody)
                        
                        // Проверяем, что это ответ на discover
                        if (json.optString("type") == "discover") {
                            val device = Device(
                                name = json.getString("name"),
                                mac = json.getString("mac"),
                                ipAddress = ip,
                                type = json.optString("type", "Unknown"),
                                version = json.optString("version", "Unknown"),
                                isOnline = true // Устройство точно онлайн, так как мы получили от него ответ
                            )
                            synchronized(devices) {
                                // Удаляем старую версию устройства, если она есть
                                devices.removeIf { it.mac == device.mac }
                                // Добавляем новую версию
                                devices.add(device)
                            }
                            Log.d(TAG, "Found device: $device")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to parse JSON from $ip: ${e.message}")
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
} 