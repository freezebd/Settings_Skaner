package com.example.settingd.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.settingd.data.NetworkScannerNew
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Job

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

    private val _showIpDialog = MutableStateFlow(false)
    val showIpDialog: StateFlow<Boolean> = _showIpDialog.asStateFlow()

    private val _isCheckingIp = MutableStateFlow(false)
    val isCheckingIp: StateFlow<Boolean> = _isCheckingIp.asStateFlow()

    private var statusCheckJob: Job? = null
    
    init {
        loadSavedDevices()
        startPeriodicStatusCheck()
    }

    private fun startPeriodicStatusCheck() {
        statusCheckJob?.cancel() // Отменяем предыдущую проверку, если она есть
        
        statusCheckJob = viewModelScope.launch {
            while (true) {
                checkDevicesStatus()
                delay(5000) // Ждем 5 секунд перед следующей проверкой
            }
        }
    }

    private suspend fun checkDevicesStatus() {
        if (_isScanning.value) return // Пропускаем проверку, если идет сканирование
        
        val currentDevices = _devices.value
        if (currentDevices.isNotEmpty()) {
            try {
                networkScanner.checkDevicesStatus(currentDevices) { updatedDevices ->
                    _devices.value = updatedDevices.sortedWith(
                        compareBy<NetworkScannerNew.Device> { !it.isOnline }
                            .thenBy { it.name.lowercase() }
                    )
                    saveDevices(_devices.value)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun startScan() {
        if (_isScanning.value) return // Предотвращаем повторный запуск сканирования
        
        viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = 0f
            
            networkScanner.scanNetwork { progress ->
                _scanProgress.value = progress
            }.also { newDevices ->
                // Сначала сохраняем найденные устройства
                val foundDevices = newDevices.map { it.copy(isOnline = true) }
                
                // Обновляем список устройств
                _devices.value = foundDevices.sortedWith(
                    compareBy<NetworkScannerNew.Device> { !it.isOnline }
                        .thenBy { it.name.lowercase() }
                )
                
                // Сохраняем устройства
                saveDevices(_devices.value)
                
                _isScanning.value = false
            }
        }
    }
    
    fun removeDevice(device: NetworkScannerNew.Device) {
        val updatedDevices = _devices.value.filter { it.mac != device.mac }
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
                        isOnline = false // При загрузке все устройства помечаются как оффлайн
                    )
                )
            }
            
            _devices.value = devices
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCleared() {
        super.onCleared()
        statusCheckJob?.cancel() // Отменяем проверку при уничтожении ViewModel
    }

    fun showIpInputDialog() {
        _showIpDialog.value = true
    }

    fun hideIpInputDialog() {
        _showIpDialog.value = false
    }

    fun checkDeviceByIp(ipAddress: String) {
        if (_isCheckingIp.value) return

        viewModelScope.launch {
            _isCheckingIp.value = true
            try {
                val device = networkScanner.checkSingleDevice(ipAddress)
                if (device != null) {
                    // Проверяем, нет ли уже устройства с таким MAC-адресом
                    val existingDeviceIndex = _devices.value.indexOfFirst { it.mac == device.mac }
                    val updatedDevices = _devices.value.toMutableList()
                    
                    if (existingDeviceIndex != -1) {
                        // Обновляем существующее устройство
                        updatedDevices[existingDeviceIndex] = device
                    } else {
                        // Добавляем новое устройство
                        updatedDevices.add(device)
                    }
                    
                    // Сортируем и обновляем список
                    _devices.value = updatedDevices.sortedWith(
                        compareBy<NetworkScannerNew.Device> { !it.isOnline }
                            .thenBy { it.name.lowercase() }
                    )
                    saveDevices(_devices.value)
                }
            } finally {
                _isCheckingIp.value = false
                hideIpInputDialog()
            }
        }
    }
} 