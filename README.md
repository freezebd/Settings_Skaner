# Settings App

Mobile application for discovering and managing ESP devices with Settings library.

[Русская версия](#русская-версия)

## Features
- Automatic network scanning for ESP devices
- Real-time device status monitoring
- Support for multiple devices
- Easy device management (add/remove)
- Dark/Light theme support
- Material Design 3 UI

## Technical Details
- Minimum Android version: 10 (API 29)
- Target Android version: 14 (API 34)
- Written in Kotlin using Jetpack Compose
- Uses coroutines for asynchronous operations
- Network discovery via HTTP requests
- Persistent device storage

## Installation
1. Download the latest APK from [Releases](../../releases)
2. Enable "Install from unknown sources" in Android settings
3. Install the APK

## Usage
1. Launch the app
2. Press "Search" to scan your network for devices
3. Or enter a specific IP address and press "Check IP"
4. Found devices will appear in the list
5. Click on an online device to open its web interface
6. Long press on a device to remove it from the list

## Network Requirements
- Devices should be on the same network
- Port 80 should be accessible
- Devices should support Settings library API

---

# Русская версия

Мобильное приложение для поиска и управления ESP устройствами с библиотекой Settings.

## Возможности
- Автоматическое сканирование сети для поиска ESP устройств
- Мониторинг статуса устройств в реальном времени
- Поддержка нескольких устройств
- Удобное управление устройствами (добавление/удаление)
- Поддержка темной/светлой темы
- Интерфейс в стиле Material Design 3

## Технические детали
- Минимальная версия Android: 10 (API 29)
- Целевая версия Android: 14 (API 34)
- Написано на Kotlin с использованием Jetpack Compose
- Использует корутины для асинхронных операций
- Поиск устройств через HTTP запросы
- Сохранение списка устройств между запусками

## Установка
1. Скачайте последнюю версию APK из раздела [Releases](../../releases)
2. Включите "Установка из неизвестных источников" в настройках Android
3. Установите APK

## Использование
1. Запустите приложение
2. Нажмите "Поиск" для сканирования сети
3. Или введите конкретный IP-адрес и нажмите "Проверить IP"
4. Найденные устройства появятся в списке
5. Нажмите на устройство в сети для открытия веб-интерфейса
6. Удерживайте устройство для удаления из списка

## Требования к сети
- Устройства должны быть в одной сети
- Порт 80 должен быть доступен
- Устройства должны поддерживать API библиотеки Settings 