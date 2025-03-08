package com.example.settingd

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.settingd.data.NetworkScannerNew.Device
import com.example.settingd.ui.MainScreen
import com.example.settingd.ui.MainViewModel
import com.example.settingd.ui.WebViewActivity
import com.example.settingd.ui.theme.SettingdTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val viewModel = MainViewModel(this)
        
        setContent {
            SettingdTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel = viewModel,
                        onDeviceClick = { device -> openDeviceWebInterface(device) }
                    )
                }
            }
        }
    }
    
    private fun openDeviceWebInterface(device: Device) {
        val intent = Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_DEVICE_IP, device.ipAddress)
        }
        startActivity(intent)
    }
}