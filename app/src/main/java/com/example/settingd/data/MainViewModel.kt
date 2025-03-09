package com.example.settingd.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface

class MainViewModel(private val context: Context) : ViewModel() {
    private val networkScanner = NetworkScannerNew(context)
    private var statusCheckJob: Job? = null
    private val PREFS_NAME = "SettingsDiscover"
    private val KEY_DEVICES = "saved_devices"
    
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress: StateFlow<Float> = _scanProgress.asStateFlow()

    private val _showIpDialog = MutableStateFlow(false)
    val showIpDialog: StateFlow<Boolean> = _showIpDialog.asStateFlow()

    private val _isCheckingIp = MutableStateFlow(false)
    val isCheckingIp: StateFlow<Boolean> = _isCheckingIp.asStateFlow()

    init {
        loadSavedDevices()
        startStatusCheck()
    }

    override fun onCleared() {
        super.onCleared()
        stopStatusCheck()
    }

    private fun startStatusCheck() {
        stopStatusCheck()
        statusCheckJob = viewModelScope.launch {
            while (isActive) {
                if (!_isScanning.value && _devices.value.isNotEmpty()) {
                    networkScanner.checkDevicesStatus(_devices.value.map { device ->
                        NetworkScannerNew.Device(
                            ipAddress = device.ipAddress,
                            name = device.name,
                            type = device.model,
                            mac = device.mac,
                            version = "",
                            isOnline = device.isOnline
                        )
                    }) { updatedDevices ->
                        val mappedDevices = updatedDevices.map { networkDevice ->
                            Device(
                                ipAddress = networkDevice.ipAddress,
                                isOnline = networkDevice.isOnline,
                                name = networkDevice.name,
                                model = networkDevice.type,
                                mac = networkDevice.mac
                            )
                        }
                        _devices.value = mappedDevices
                        saveDevices(mappedDevices)
                    }
                }
                delay(5000) // Проверяем каждые 5 секунд
            }
        }
    }

    private fun stopStatusCheck() {
        statusCheckJob?.cancel()
        statusCheckJob = null
    }

    fun startScan() {
        viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = 0f
            
            try {
                val scannedDevices = networkScanner.scanNetwork { progress ->
                    _scanProgress.value = progress
                }
                
                val mappedDevices = scannedDevices.map { networkDevice ->
                    Device(
                        ipAddress = networkDevice.ipAddress,
                        isOnline = networkDevice.isOnline,
                        name = networkDevice.name,
                        model = networkDevice.type,
                        mac = networkDevice.mac
                    )
                }
                
                _devices.value = mappedDevices
                saveDevices(mappedDevices)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isScanning.value = false
                _scanProgress.value = 1f
            }
        }
    }

    private fun loadSavedDevices() {
        viewModelScope.launch {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val devicesJson = prefs.getString(KEY_DEVICES, null)
                if (!devicesJson.isNullOrEmpty()) {
                    val jsonArray = JSONArray(devicesJson)
                    val loadedDevices = mutableListOf<Device>()
                    
                    for (i in 0 until jsonArray.length()) {
                        val deviceJson = jsonArray.getJSONObject(i)
                        loadedDevices.add(
                            Device(
                                ipAddress = deviceJson.getString("ipAddress"),
                                name = deviceJson.getString("name"),
                                model = deviceJson.getString("model"),
                                mac = deviceJson.getString("mac"),
                                isOnline = false // При загрузке все устройства считаем оффлайн
                            )
                        )
                    }
                    _devices.value = loadedDevices
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveDevices(devices: List<Device>) {
        viewModelScope.launch {
            try {
                val jsonArray = JSONArray()
                devices.forEach { device ->
                    val deviceJson = JSONObject().apply {
                        put("ipAddress", device.ipAddress)
                        put("name", device.name)
                        put("model", device.model)
                        put("mac", device.mac)
                    }
                    jsonArray.put(deviceJson)
                }
                
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_DEVICES, jsonArray.toString())
                    .apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getLocalIpAddress(): String {
        return networkScanner.getLocalIpAddress()
    }

    fun showIpInputDialog() {
        _showIpDialog.value = true
    }

    fun hideIpInputDialog() {
        _showIpDialog.value = false
    }

    fun checkDeviceByIp(ip: String) {
        viewModelScope.launch {
            _isCheckingIp.value = true
            try {
                networkScanner.checkDeviceAsync(ip)
                val scannedDevices = networkScanner.getDevices()
                val mappedDevices = scannedDevices.map { networkDevice ->
                    Device(
                        ipAddress = networkDevice.ipAddress,
                        isOnline = networkDevice.isOnline,
                        name = networkDevice.name,
                        model = networkDevice.type,
                        mac = networkDevice.mac
                    )
                }
                _devices.value = mappedDevices
                saveDevices(mappedDevices)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isCheckingIp.value = false
                hideIpInputDialog()
            }
        }
    }

    fun removeDevice(device: Device) {
        val updatedDevices = _devices.value.filter { it != device }
        _devices.value = updatedDevices
        saveDevices(updatedDevices)
    }

    fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            "Unknown"
        }
    }
} 