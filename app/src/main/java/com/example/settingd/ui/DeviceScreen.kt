package com.example.settingd.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.settingd.data.protocol.DeviceInfo
import com.example.settingd.data.protocol.Setting
import com.example.settingd.ui.widgets.SettingWidget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    deviceInfo: DeviceInfo,
    settings: List<Setting>,
    onSettingChange: (String, Any) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(deviceInfo.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, "Обновить")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                DeviceInfoCard(deviceInfo)
            }
            
            items(settings) { setting ->
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingWidget(
                        setting = setting,
                        onValueChange = { value -> onSettingChange(setting.key, value) },
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceInfoCard(deviceInfo: DeviceInfo) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Информация об устройстве",
                style = MaterialTheme.typography.titleMedium
            )
            InfoRow("Имя", deviceInfo.name)
            InfoRow("MAC", deviceInfo.mac)
            InfoRow("IP", deviceInfo.ip)
            InfoRow("Тип", deviceInfo.type)
            InfoRow("Версия", deviceInfo.version)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
} 