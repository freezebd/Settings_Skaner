package com.example.settingd.data.protocol

import java.io.File

/**
 * Нативная реализация протокола GyverSettings
 */
interface GyverSettingsProtocol {
    /**
     * Подключение к устройству
     */
    suspend fun connect(host: String, port: Int = 80)
    
    /**
     * Отключение от устройства
     */
    suspend fun disconnect()
    
    /**
     * Получение информации об устройстве
     */
    suspend fun getDeviceInfo(): DeviceInfo
    
    /**
     * Получение списка настроек
     */
    suspend fun getSettings(): List<Setting>
    
    /**
     * Чтение значения настройки
     */
    suspend fun readSetting(key: String): Setting
    
    /**
     * Запись значения настройки
     */
    suspend fun writeSetting(key: String, value: Any)
    
    /**
     * Получение списка файлов
     */
    suspend fun listFiles(path: String = "/"): List<FileInfo>
    
    /**
     * Скачивание файла
     */
    suspend fun downloadFile(path: String): ByteArray
    
    /**
     * Загрузка файла
     */
    suspend fun uploadFile(path: String, data: ByteArray)
    
    /**
     * Удаление файла
     */
    suspend fun deleteFile(path: String)
    
    /**
     * Обновление прошивки
     */
    suspend fun performOTA(firmware: ByteArray, onProgress: (Float) -> Unit)
}

/**
 * Информация об устройстве
 */
data class DeviceInfo(
    val name: String,
    val mac: String,
    val type: String,
    val version: String,
    val ip: String
)

/**
 * Настройка устройства
 */
sealed class Setting {
    abstract val key: String
    abstract val label: String
    
    data class Number(
        override val key: String,
        override val label: String,
        val value: Double,
        val min: Double,
        val max: Double,
        val step: Double = 1.0
    ) : Setting()
    
    data class Toggle(
        override val key: String,
        override val label: String,
        val value: Boolean
    ) : Setting()
    
    data class Text(
        override val key: String,
        override val label: String,
        val value: String,
        val maxLength: Int = 0
    ) : Setting()
    
    data class Select(
        override val key: String,
        override val label: String,
        val value: Int,
        val options: List<String>
    ) : Setting()
    
    data class Color(
        override val key: String,
        override val label: String,
        val value: Int // RGB color
    ) : Setting()
}

/**
 * Информация о файле
 */
data class FileInfo(
    val name: String,
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
    val lastModified: Long
) 