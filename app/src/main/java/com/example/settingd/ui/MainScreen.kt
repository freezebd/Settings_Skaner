package com.example.settingd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.settingd.data.NetworkScannerNew.Device
import androidx.compose.material.DismissDirection
import androidx.compose.material.DismissValue
import androidx.compose.material.SwipeToDismiss
import androidx.compose.material.rememberDismissState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onDeviceClick: (Device) -> Unit
) {
    val devices by viewModel.devices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val progress by viewModel.scanProgress.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                actions = {
                    Text(
                        text = viewModel.getLocalIpAddress(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 16.dp)
                        )
                    } else {
                        IconButton(
                            onClick = { viewModel.startScan() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Search devices",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isScanning) {
                item {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                }
            }

            items(
                items = devices,
                key = { device -> device.mac }
            ) { device ->
                var isDeleting by remember { mutableStateOf(false) }
                val dismissState = rememberDismissState(
                    confirmStateChange = { dismissValue ->
                        when (dismissValue) {
                            DismissValue.DismissedToStart -> {
                                isDeleting = true
                                viewModel.removeDevice(device)
                                true
                            }
                            DismissValue.Default -> {
                                isDeleting = false
                                true
                            }
                            else -> false
                        }
                    }
                )

                if (!isDeleting) {
                    SwipeToDismiss(
                        state = dismissState,
                        background = {
                            val scale by animateFloatAsState(
                                if (dismissState.targetValue == DismissValue.Default) 0.75f else 1f,
                                label = "scale"
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.error)
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    modifier = Modifier.scale(scale),
                                    tint = MaterialTheme.colorScheme.onError
                                )
                            }
                        },
                        dismissContent = {
                            DeviceCard(
                                device = device,
                                onClick = { 
                                    if (device.isOnline) {
                                        onDeviceClick(device)
                                    }
                                }
                            )
                        },
                        directions = setOf(DismissDirection.EndToStart)
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: Device,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = device.isOnline,
                onClick = onClick
            )
            .alpha(if (device.isOnline) 1f else 0.7f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (device.isOnline) "Online" else "Offline",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (device.isOnline) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.error
                )
            }
            
            Text(
                text = "${device.ipAddress} • ${device.mac}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
} 