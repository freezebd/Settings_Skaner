package com.example.settingd.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.settingd.data.protocol.Setting

@Composable
fun SettingWidget(
    setting: Setting,
    onValueChange: (Any) -> Unit,
    modifier: Modifier = Modifier
) {
    when (setting) {
        is Setting.Number -> NumberSettingWidget(setting, onValueChange, modifier)
        is Setting.Toggle -> ToggleSettingWidget(setting, onValueChange, modifier)
        is Setting.Text -> TextSettingWidget(setting, onValueChange, modifier)
        is Setting.Select -> SelectSettingWidget(setting, onValueChange, modifier)
        is Setting.Color -> ColorSettingWidget(setting, onValueChange, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumberSettingWidget(
    setting: Setting.Number,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var value by remember { mutableStateOf(setting.value) }
    var isEditing by remember { mutableStateOf(false) }
    
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = setting.label,
            style = MaterialTheme.typography.bodyMedium
        )
        if (isEditing) {
            OutlinedTextField(
                value = value.toString(),
                onValueChange = { text ->
                    text.toDoubleOrNull()?.let { newValue ->
                        if (newValue in setting.min..setting.max) {
                            value = newValue
                            onValueChange(newValue)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        } else {
            Slider(
                value = value.toFloat(),
                onValueChange = { newValue ->
                    value = newValue.toDouble()
                    onValueChange(value)
                },
                valueRange = setting.min.toFloat()..setting.max.toFloat(),
                steps = ((setting.max - setting.min) / setting.step).toInt(),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
fun ToggleSettingWidget(
    setting: Setting.Toggle,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = setting.label,
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = setting.value,
            onCheckedChange = onValueChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSettingWidget(
    setting: Setting.Text,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = setting.label,
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = setting.value,
            onValueChange = { text ->
                if (setting.maxLength == 0 || text.length <= setting.maxLength) {
                    onValueChange(text)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@Composable
fun SelectSettingWidget(
    setting: Setting.Select,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = setting.label,
            style = MaterialTheme.typography.bodyMedium
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(setting.options.getOrNull(setting.value) ?: "")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                setting.options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onValueChange(index)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ColorSettingWidget(
    setting: Setting.Color,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showColorPicker by remember { mutableStateOf(false) }
    val color = Color(setting.value)
    
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = setting.label,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color)
            )
            OutlinedButton(
                onClick = { showColorPicker = true }
            ) {
                Text("Выбрать цвет")
            }
        }
    }
    
    if (showColorPicker) {
        AlertDialog(
            onDismissRequest = { showColorPicker = false },
            title = { Text("Выберите цвет") },
            text = {
                // Здесь будет компонент выбора цвета
                // Можно использовать стороннюю библиотеку или создать свой
            },
            confirmButton = {
                TextButton(onClick = { showColorPicker = false }) {
                    Text("OK")
                }
            }
        )
    }
} 