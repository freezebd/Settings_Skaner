package com.example.settingd.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.settingd.data.NetworkScanner
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(networkScanner: NetworkScanner) {
    var progress by remember { mutableStateOf(0f) }
    var devices by remember { mutableStateOf(networkScanner.getDevices()) }
    var ipAddress by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(false) }
    var updateTrigger by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var longPressedDevice by remember { mutableStateOf<NetworkScanner.Device?>(null) }

    // Устанавливаем слушатель прогресса
    DisposableEffect(networkScanner) {
        networkScanner.setOnProgressUpdateListener { newProgress ->
            if (newProgress == -1f) {
                // Обновляем список устройств при изменении статуса
                devices = networkScanner.getDevices()
                updateTrigger += 1
            } else {
                progress = newProgress
            }
        }
        onDispose { }
    }

    LaunchedEffect(updateTrigger) {
        devices = networkScanner.getDevices()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Поле для ввода IP-адреса
        OutlinedTextField(
            value = ipAddress,
            onValueChange = { ipAddress = it },
            label = { Text("IP-адрес") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Например: 192.168.1.39") }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Кнопки управления
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {
                    if (ipAddress.isNotEmpty()) {
                        isScanning = true
                        scope.launch {
                            try {
                                val result = networkScanner.scanSingleDevice(ipAddress)
                                devices = result
                                progress = 100f
                            } catch (e: Exception) {
                                // Обработка ошибок
                            } finally {
                                isScanning = false
                            }
                        }
                    }
                },
                enabled = !isScanning && ipAddress.isNotEmpty(),
                modifier = Modifier.width(150.dp)
            ) {
                Text("Проверить IP")
            }

            Button(
                onClick = {
                    isScanning = true
                    scope.launch {
                        try {
                            val result = networkScanner.scanNetwork()
                            devices = result
                            progress = 100f
                        } catch (e: Exception) {
                            // Обработка ошибок
                        } finally {
                            isScanning = false
                        }
                    }
                },
                enabled = !isScanning,
                modifier = Modifier.width(150.dp)
            ) {
                Text("Поиск")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Прогресс сканирования
        if (isScanning) {
            LinearProgressIndicator(
                progress = progress / 100f,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Прогресс: ${progress.toInt()}%",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Список найденных устройств
        Text(
            text = "Найденные устройства:",
            style = MaterialTheme.typography.titleMedium
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(devices) { device ->
                var isLongPressed by remember { mutableStateOf(false) }
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    // Открываем устройство по клику
                                    if (device.isOnline) {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            data = Uri.parse("http://${device.ipAddress}/")
                                        }
                                        context.startActivity(intent)
                                    }
                                },
                                onLongPress = {
                                    // Удаляем устройство при длительном нажатии
                                    networkScanner.removeDevice(device.mac)
                                    devices = networkScanner.getDevices()
                                }
                            )
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (device.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = "IP: ${device.ipAddress}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Имя: ${device.name}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "MAC: ${device.mac}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = if (device.isOnline) "Статус: В сети" else "Статус: Не в сети",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (device.isOnline) MaterialTheme.colorScheme.onPrimary else Color.Gray
                        )
                    }
                }
            }
        }
    }
} 