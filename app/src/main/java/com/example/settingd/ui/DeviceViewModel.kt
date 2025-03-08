package com.example.settingd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.settingd.data.protocol.DeviceInfo
import com.example.settingd.data.protocol.GyverSettingsProtocol
import com.example.settingd.data.protocol.Setting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeviceViewModel(
    private val protocol: GyverSettingsProtocol,
    private val host: String
) : ViewModel() {
    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()
    
    private val _settings = MutableStateFlow<List<Setting>>(emptyList())
    val settings: StateFlow<List<Setting>> = _settings.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    init {
        connect()
    }
    
    private fun connect() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                
                protocol.connect(host)
                loadDeviceInfo()
                loadSettings()
            } catch (e: Exception) {
                _error.value = "Ошибка подключения: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    private suspend fun loadDeviceInfo() {
        try {
            _deviceInfo.value = protocol.getDeviceInfo()
        } catch (e: Exception) {
            _error.value = "Ошибка получения информации: ${e.message}"
        }
    }
    
    private suspend fun loadSettings() {
        try {
            _settings.value = protocol.getSettings()
        } catch (e: Exception) {
            _error.value = "Ошибка получения настроек: ${e.message}"
        }
    }
    
    fun refresh() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                
                loadDeviceInfo()
                loadSettings()
            } catch (e: Exception) {
                _error.value = "Ошибка обновления: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun updateSetting(key: String, value: Any) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                
                protocol.writeSetting(key, value)
                // Перечитываем настройку, чтобы убедиться, что значение сохранилось
                val updatedSetting = protocol.readSetting(key)
                
                // Обновляем список настроек
                _settings.value = _settings.value.map { setting ->
                    if (setting.key == key) updatedSetting else setting
                }
            } catch (e: Exception) {
                _error.value = "Ошибка сохранения настройки: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            protocol.disconnect()
        }
    }
} 