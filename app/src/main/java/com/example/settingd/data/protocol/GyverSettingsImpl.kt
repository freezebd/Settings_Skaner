package com.example.settingd.data.protocol

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GyverSettingsImpl : GyverSettingsProtocol {
    private val TAG = "GyverSettingsImpl"
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var reader: BufferedReader? = null

    private object Commands {
        const val DISCOVER = 0x00
        const val GET_INFO = 0x01
        const val GET_SETTINGS = 0x02
        const val READ_SETTING = 0x03
        const val WRITE_SETTING = 0x04
        const val LIST_FILES = 0x05
        const val READ_FILE = 0x06
        const val WRITE_FILE = 0x07
        const val DELETE_FILE = 0x08
        const val OTA_BEGIN = 0x09
        const val OTA_WRITE = 0x0A
        const val OTA_END = 0x0B
    }

    override suspend fun connect(host: String, port: Int) {
        withContext(Dispatchers.IO) {
            try {
                socket = Socket(host, port).apply {
                    keepAlive = true
                    soTimeout = 5000
                }
                outputStream = socket?.getOutputStream()
                reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
                Log.d(TAG, "Connected to $host:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                throw e
            }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                reader?.close()
                outputStream?.close()
                socket?.close()
                reader = null
                outputStream = null
                socket = null
                Log.d(TAG, "Disconnected")
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect failed: ${e.message}")
            }
        }
    }

    override suspend fun getDeviceInfo(): DeviceInfo = withContext(Dispatchers.IO) {
        val command = ByteBuffer.allocate(1).apply {
            put(Commands.GET_INFO.toByte())
        }.array()
        
        sendCommand(command)
        val response = readResponse()
        
        try {
            val json = JSONObject(response)
            DeviceInfo(
                name = json.getString("name"),
                mac = json.getString("mac"),
                type = json.getString("type"),
                version = json.getString("version"),
                ip = json.getString("ip")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse device info: ${e.message}")
            throw e
        }
    }

    override suspend fun getSettings(): List<Setting> = withContext(Dispatchers.IO) {
        val command = ByteBuffer.allocate(1).apply {
            put(Commands.GET_SETTINGS.toByte())
        }.array()
        
        sendCommand(command)
        val response = readResponse()
        
        try {
            val json = JSONObject(response)
            val settings = mutableListOf<Setting>()
            
            val settingsArray = json.getJSONArray("settings")
            for (i in 0 until settingsArray.length()) {
                val settingJson = settingsArray.getJSONObject(i)
                val type = settingJson.getString("type")
                val key = settingJson.getString("key")
                val label = settingJson.getString("label")
                
                when (type) {
                    "number" -> settings.add(Setting.Number(
                        key = key,
                        label = label,
                        value = settingJson.getDouble("value"),
                        min = settingJson.getDouble("min"),
                        max = settingJson.getDouble("max"),
                        step = settingJson.optDouble("step", 1.0)
                    ))
                    "toggle" -> settings.add(Setting.Toggle(
                        key = key,
                        label = label,
                        value = settingJson.getBoolean("value")
                    ))
                    "text" -> settings.add(Setting.Text(
                        key = key,
                        label = label,
                        value = settingJson.getString("value"),
                        maxLength = settingJson.optInt("maxLength", 0)
                    ))
                    "select" -> settings.add(Setting.Select(
                        key = key,
                        label = label,
                        value = settingJson.getInt("value"),
                        options = List(settingJson.getJSONArray("options").length()) { i ->
                            settingJson.getJSONArray("options").getString(i)
                        }
                    ))
                    "color" -> settings.add(Setting.Color(
                        key = key,
                        label = label,
                        value = settingJson.getInt("value")
                    ))
                }
            }
            settings
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse settings: ${e.message}")
            throw e
        }
    }

    override suspend fun readSetting(key: String): Setting = withContext(Dispatchers.IO) {
        val command = ByteBuffer.allocate(1 + key.length).apply {
            put(Commands.READ_SETTING.toByte())
            put(key.toByteArray())
        }.array()
        
        sendCommand(command)
        val response = readResponse()
        
        try {
            val json = JSONObject(response)
            val type = json.getString("type")
            val label = json.getString("label")
            
            when (type) {
                "number" -> Setting.Number(
                    key = key,
                    label = label,
                    value = json.getDouble("value"),
                    min = json.getDouble("min"),
                    max = json.getDouble("max"),
                    step = json.optDouble("step", 1.0)
                )
                "toggle" -> Setting.Toggle(
                    key = key,
                    label = label,
                    value = json.getBoolean("value")
                )
                "text" -> Setting.Text(
                    key = key,
                    label = label,
                    value = json.getString("value"),
                    maxLength = json.optInt("maxLength", 0)
                )
                "select" -> Setting.Select(
                    key = key,
                    label = label,
                    value = json.getInt("value"),
                    options = List(json.getJSONArray("options").length()) { i ->
                        json.getJSONArray("options").getString(i)
                    }
                )
                "color" -> Setting.Color(
                    key = key,
                    label = label,
                    value = json.getInt("value")
                )
                else -> throw IllegalArgumentException("Unknown setting type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse setting: ${e.message}")
            throw e
        }
    }

    override suspend fun writeSetting(key: String, value: Any) {
        withContext(Dispatchers.IO) {
            val valueBytes = when (value) {
                is Boolean -> ByteBuffer.allocate(1).put(if (value) 1.toByte() else 0.toByte()).array()
                is Int -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
                is Double -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()
                is String -> value.toByteArray()
                else -> throw IllegalArgumentException("Unsupported value type: ${value::class.java}")
            }
            
            val command = ByteBuffer.allocate(1 + key.length + valueBytes.size).apply {
                put(Commands.WRITE_SETTING.toByte())
                put(key.toByteArray())
                put(valueBytes)
            }.array()
            
            sendCommand(command)
            readResponse() // Check for errors
        }
    }

    override suspend fun listFiles(path: String): List<FileInfo> = withContext(Dispatchers.IO) {
        val command = ByteBuffer.allocate(1 + path.length).apply {
            put(Commands.LIST_FILES.toByte())
            put(path.toByteArray())
        }.array()
        
        sendCommand(command)
        val response = readResponse()
        
        try {
            val json = JSONObject(response)
            val files = mutableListOf<FileInfo>()
            
            val filesArray = json.getJSONArray("files")
            for (i in 0 until filesArray.length()) {
                val fileJson = filesArray.getJSONObject(i)
                files.add(FileInfo(
                    name = fileJson.getString("name"),
                    path = fileJson.getString("path"),
                    size = fileJson.getLong("size"),
                    isDirectory = fileJson.getBoolean("isDir"),
                    lastModified = fileJson.getLong("modified")
                ))
            }
            files
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse file list: ${e.message}")
            throw e
        }
    }

    override suspend fun downloadFile(path: String): ByteArray = withContext(Dispatchers.IO) {
        val command = ByteBuffer.allocate(1 + path.length).apply {
            put(Commands.READ_FILE.toByte())
            put(path.toByteArray())
        }.array()
        
        sendCommand(command)
        readBinaryResponse()
    }

    override suspend fun uploadFile(path: String, data: ByteArray) {
        withContext(Dispatchers.IO) {
            val command = ByteBuffer.allocate(1 + path.length + data.size).apply {
                put(Commands.WRITE_FILE.toByte())
                put(path.toByteArray())
                put(data)
            }.array()
            
            sendCommand(command)
            readResponse() // Check for errors
        }
    }

    override suspend fun deleteFile(path: String) {
        withContext(Dispatchers.IO) {
            val command = ByteBuffer.allocate(1 + path.length).apply {
                put(Commands.DELETE_FILE.toByte())
                put(path.toByteArray())
            }.array()
            
            sendCommand(command)
            readResponse() // Check for errors
        }
    }

    override suspend fun performOTA(firmware: ByteArray, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            // Start OTA
            sendCommand(byteArrayOf(Commands.OTA_BEGIN.toByte()))
            readResponse() // Check for errors
            
            // Send firmware in chunks
            val chunkSize = 1024
            var sent = 0
            firmware.asSequence().chunked(chunkSize).forEach { chunk ->
                val command = ByteBuffer.allocate(1 + chunk.size).apply {
                    put(Commands.OTA_WRITE.toByte())
                    chunk.forEach { put(it) }
                }.array()
                
                sendCommand(command)
                readResponse() // Check for errors
                
                sent += chunk.size
                onProgress(sent.toFloat() / firmware.size)
            }
            
            // Finish OTA
            sendCommand(byteArrayOf(Commands.OTA_END.toByte()))
            readResponse() // Check for errors
        }
    }

    private suspend fun sendCommand(command: ByteArray) = withContext(Dispatchers.IO) {
        try {
            outputStream?.write(command)
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command: ${e.message}")
            throw e
        }
    }

    private suspend fun readResponse(): String = withContext(Dispatchers.IO) {
        try {
            val response = StringBuilder()
            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                response.append(line)
            }
            response.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read response: ${e.message}")
            throw e
        }
    }

    private suspend fun readBinaryResponse(): ByteArray = withContext(Dispatchers.IO) {
        try {
            val input = socket?.getInputStream() ?: throw IllegalStateException("Socket not connected")
            val size = ByteBuffer.wrap(ByteArray(4).apply { input.read(this) })
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
            
            val data = ByteArray(size)
            var read = 0
            while (read < size) {
                val count = input.read(data, read, size - read)
                if (count == -1) break
                read += count
            }
            data
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read binary response: ${e.message}")
            throw e
        }
    }
} 