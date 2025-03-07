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

class NetworkScannerNew(private val context: Context) {
    private val TAG = "NetworkScannerNew"
    private val devices = mutableListOf<Device>()
    private val scannedCount = AtomicInteger(0)

    data class Device(
        val ipAddress: String,
        val name: String,
        val type: String
    )

    suspend fun scanNetwork(): List<Device> = coroutineScope {
        devices.clear()
        scannedCount.set(0)

        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ipAddress = wifiInfo.ipAddress
        val subnet = (ipAddress and 0xFF000000.toInt()) shr 24

        Log.d(TAG, "Starting network scan for subnet: $subnet")

        // Создаем список адресов для сканирования
        val addresses = (1..254).map { "$subnet.$it" }
        
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
                        scannedCount.incrementAndGet()
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
        devices
    }

    private suspend fun checkDeviceAsync(host: String) = withContext(Dispatchers.IO) {
        // Проверяем доступность устройства
        if (!isDeviceReachable(host)) {
            return@withContext
        }

        // Отправляем HTTP запрос
        val response = sendHttpRequest(host)
        if (response != null) {
            try {
                val device = parseDeviceResponse(response)
                if (device != null) {
                    devices.add(device)
                    Log.d(TAG, "Found device: $device")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing response from $host: ${e.message}")
            }
        }
    }

    private fun isDeviceReachable(host: String): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, 80), 1000)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun sendHttpRequest(ipAddress: String): String? = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ipAddress, 80), 2000)
            socket.soTimeout = 2000

            val request = """
                GET /settings?action=discover HTTP/1.1
                Host: $ipAddress
                Accept: */*
                Accept-Encoding: gzip, deflate
                Accept-Language: ru,en;q=0.9
                Connection: close
                User-Agent: Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36
                
            """.trimIndent()

            socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val response = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                response.append(line).append("\n")
                if (line?.isEmpty() == true) break
            }

            socket.close()
            response.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending HTTP request to $ipAddress: ${e.message}")
            null
        }
    }

    private fun parseDeviceResponse(response: String): Device? {
        try {
            val lines = response.split("\n")
            var name: String? = null
            var type: String? = null

            for (line in lines) {
                if (line.startsWith("Content-Type: application/json")) {
                    val jsonStart = response.indexOf("{")
                    if (jsonStart != -1) {
                        val jsonStr = response.substring(jsonStart)
                        // Здесь можно добавить парсинг JSON, если нужно
                        // Пока просто возвращаем базовую информацию
                        return Device(
                            ipAddress = response.substringAfter("Host: ").substringBefore("\n"),
                            name = "Device",
                            type = "Unknown"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing device response: ${e.message}")
        }
        return null
    }
} 