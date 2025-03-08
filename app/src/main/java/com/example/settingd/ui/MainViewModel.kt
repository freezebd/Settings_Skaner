package com.example.settingd.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.settingd.data.NetworkScannerNew
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainViewModel(private val context: Context) : ViewModel() {
    private val networkScanner = NetworkScannerNew(context)
    private val PREFS_NAME = "SettingsDiscover"
    private val DEVICES_KEY = "saved_devices"
    
    private val _devices = MutableStateFlow<List<NetworkScannerNew.Device>>(emptyList())
    val devices: StateFlow<List<NetworkScannerNew.Device>> = _devices.asStateFlow()
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress: StateFlow<Float> = _scanProgress.asStateFlow()
    
    init {
        loadSavedDevices()
        checkSavedDevices()
    }
    
    private fun checkSavedDevices() {
        viewModelScope.launch {
            val currentDevices = _devices.value
            if (currentDevices.isEmpty()) return@launch
            
            _isScanning.value = true
            _scanProgress.value = 0f
            
            networkScanner.scanNetwork { progress ->
                _scanProgress.value = progress
            }.also { newDevices ->
                val updatedDevices = mutableListOf<NetworkScannerNew.Device>()
                
                // Обновляем статус существующих устройств
                currentDevices.forEach { existingDevice ->
                    val newDevice = newDevices.find { it.mac == existingDevice.mac }
                    if (newDevice != null) {
                        updatedDevices.add(newDevice)
                    } else {
                        updatedDevices.add(existingDevice.copy(isOnline = false))
                    }
                }
                
                // Добавляем новые устройства
                newDevices.forEach { newDevice ->
                    if (updatedDevices.none { it.mac == newDevice.mac }) {
                        updatedDevices.add(newDevice)
                    }
                }
                
                _devices.value = updatedDevices
                saveDevices(updatedDevices)
                _isScanning.value = false
            }
        }
    }
    
    fun startScan() {
        viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = 0f
            networkScanner.scanNetwork { progress ->
                _scanProgress.value = progress
            }.also { newDevices ->
                val updatedDevices = mutableListOf<NetworkScannerNew.Device>()
                val currentDevices = _devices.value
                
                // Обновляем статус существующих устройств
                currentDevices.forEach { existingDevice ->
                    val newDevice = newDevices.find { it.mac == existingDevice.mac }
                    if (newDevice != null) {
                        updatedDevices.add(newDevice)
                    } else {
                        updatedDevices.add(existingDevice.copy(isOnline = false))
                    }
                }
                
                // Добавляем новые устройства
                newDevices.forEach { newDevice ->
                    if (updatedDevices.none { it.mac == newDevice.mac }) {
                        updatedDevices.add(newDevice)
                    }
                }
                
                _devices.value = updatedDevices
                saveDevices(updatedDevices)
                _isScanning.value = false
            }
        }
    }
    
    fun removeDevice(device: NetworkScannerNew.Device) {
        val updatedDevices = _devices.value.toMutableList()
        updatedDevices.remove(device)
        _devices.value = updatedDevices
        saveDevices(updatedDevices)
    }
    
    fun getLocalIpAddress(): String {
        return networkScanner.getLocalIpAddress()
    }
    
    private fun saveDevices(devices: List<NetworkScannerNew.Device>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        
        devices.forEach { device ->
            val jsonObject = JSONObject().apply {
                put("name", device.name)
                put("mac", device.mac)
                put("ipAddress", device.ipAddress)
                put("type", device.type)
                put("version", device.version)
                put("isOnline", device.isOnline)
            }
            jsonArray.put(jsonObject)
        }
        
        prefs.edit().putString(DEVICES_KEY, jsonArray.toString()).apply()
    }
    
    private fun loadSavedDevices() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedDevicesJson = prefs.getString(DEVICES_KEY, "[]")
        
        try {
            val jsonArray = JSONArray(savedDevicesJson)
            val devices = mutableListOf<NetworkScannerNew.Device>()
            
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                devices.add(
                    NetworkScannerNew.Device(
                        name = jsonObject.getString("name"),
                        mac = jsonObject.getString("mac"),
                        ipAddress = jsonObject.getString("ipAddress"),
                        type = jsonObject.getString("type"),
                        version = jsonObject.getString("version"),
                        isOnline = jsonObject.getBoolean("isOnline")
                    )
                )
            }
            
            _devices.value = devices
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
} 