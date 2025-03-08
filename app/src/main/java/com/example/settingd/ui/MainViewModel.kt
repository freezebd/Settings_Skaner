package com.example.settingd.ui

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.settingd.data.NetworkScannerFixed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "MainViewModel"
    private val networkScanner = NetworkScannerFixed(application)

    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress: StateFlow<Float> = _scanProgress.asStateFlow()

    private val _devices = MutableStateFlow<List<NetworkScannerFixed.Device>>(emptyList())
    val devices: StateFlow<List<NetworkScannerFixed.Device>> = _devices.asStateFlow()

    init {
        networkScanner.setOnProgressUpdateListener { progress ->
            viewModelScope.launch {
                _scanProgress.emit(progress)
                if (progress == -1f || progress == 100f) {
                    _devices.emit(networkScanner.getDevices())
                }
            }
        }
        // Загружаем сохраненные устройства при создании ViewModel
        viewModelScope.launch {
            _devices.emit(networkScanner.getDevices())
        }
    }

    fun scanNetwork() {
        viewModelScope.launch {
            try {
                networkScanner.scanNetwork()
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning network", e)
            }
        }
    }

    fun scanSingleDevice(ipAddress: String) {
        viewModelScope.launch {
            try {
                networkScanner.scanSingleDevice(ipAddress)
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning device at $ipAddress", e)
            }
        }
    }

    fun removeDevice(mac: String) {
        networkScanner.removeDevice(mac)
        viewModelScope.launch {
            _devices.emit(networkScanner.getDevices())
        }
    }

    fun getLocalIpAddress(context: Context): String {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.dhcpInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ipAddress and 0xff,
            (ipAddress shr 8) and 0xff,
            (ipAddress shr 16) and 0xff,
            (ipAddress shr 24) and 0xff
        )
    }

    override fun onCleared() {
        super.onCleared()
        networkScanner.cleanup()
    }
} 