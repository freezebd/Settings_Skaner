package com.example.settingd.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

class GyverSettingsManager {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null

    suspend fun connect(ipAddress: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                socket = Socket(ipAddress, port)
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                writer = PrintWriter(socket!!.getOutputStream(), true)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                writer?.close()
                reader?.close()
                socket?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun getSettings(): List<Setting> {
        return withContext(Dispatchers.IO) {
            try {
                writer?.println("GET")
                writer?.flush()
                
                val response = reader?.readLine() ?: ""
                parseSettings(response)
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    suspend fun setSetting(name: String, value: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                writer?.println("SET $name $value")
                writer?.flush()
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    private fun parseSettings(response: String): List<Setting> {
        return response.split("\n")
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split("=")
                if (parts.size == 2) {
                    Setting(parts[0].trim(), parts[1].trim())
                } else {
                    null
                }
            }
            .filterNotNull()
    }
}

data class Setting(
    val name: String,
    val value: String
) 