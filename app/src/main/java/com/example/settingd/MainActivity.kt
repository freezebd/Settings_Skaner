package com.example.settingd

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.settingd.data.Device
import com.example.settingd.ui.MainScreen
import com.example.settingd.ui.SplashScreen
import com.example.settingd.ui.WebViewActivity
import com.example.settingd.ui.theme.SettingdTheme
import com.example.settingd.data.MainViewModel

class MainActivity : ComponentActivity() {
    private var backPressedTime: Long = 0
    private val BACK_PRESS_DELAY = 2000 // 2 секунды для двойного нажатия
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Настраиваем обработчик кнопки "Назад"
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (backPressedTime + BACK_PRESS_DELAY > System.currentTimeMillis()) {
                    finish()
                } else {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Двойное нажатие - выход",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                backPressedTime = System.currentTimeMillis()
            }
        })
        
        val viewModel = MainViewModel(this)
        
        setContent {
            SettingdTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showSplash by remember { mutableStateOf(true) }
                    
                    if (showSplash) {
                        SplashScreen(
                            onSplashFinished = { showSplash = false }
                        )
                    } else {
                        MainScreen(
                            viewModel = viewModel,
                            onDeviceClick = { device -> openDeviceWebInterface(device) }
                        )
                    }
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