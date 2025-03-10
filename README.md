# Settings Scanner

Мобильное приложение для Android, предназначенное для поиска и управления устройствами с протоколом GyverSettings в локальной сети.

Использует протокол [GyverSettings](https://github.com/GyverLibs/Settings) - библиотеку для Arduino, которая позволяет создавать веб-интерфейс для управления параметрами устройства.

## История изменений

### Версия 1.2.1 (Текущая)
#### Исправления
- Исправлена логика сканирования устройств:
  - Устройства не исчезают из списка при потере соединения
  - Корректно обновляется статус онлайн/оффлайн
  - Сохраняется история найденных устройств
  - Улучшена стабильность определения статуса

#### Улучшения в работе с сетью
- Оптимизирована обработка сетевых таймаутов
- Улучшена обработка ошибок соединения
- Более стабильная работа с HTTP-запросами

### Версия 1.2.0
#### Основные функции
1. Проверка удаленных устройств:
   - Возможность проверки устройств по произвольному IP адресу
   - При нажатии на локальный IP открывается диалог ввода
   - Валидация введенного IP адреса
   - Поддержка устройств вне локальной сети

2. Управление устройствами:
   - Сохранение найденных устройств между запусками
   - Визуальная индикация статуса устройств
   - Сортировка по статусу и имени
   - Свайп для удаления устройств из списка

### Версия 1.1.0
- Автоматическая проверка статуса устройств каждые 5 секунд
- Улучшенная логика обнаружения устройств
- Визуальная индикация статуса устройств
- Оптимизированная производительность
- Сортировка устройств по статусу и имени
- Защита от конфликтов при одновременном сканировании

## Технические детали
- Текущая версия: 1.2.1
- Код версии: 4
- Минимальная версия Android: 10 (API 29)
- Целевая версия Android: 14 (API 34)
- Используется Jetpack Compose для UI
- Kotlin Coroutines для асинхронных операций

## Возможности

- Автоматическое сканирование сети для поиска совместимых устройств
- Отображение списка найденных устройств с их статусом (онлайн/оффлайн)
- Сохранение найденных устройств между запусками приложения
- Удаление устройств из списка с помощью свайпа
- Отображение локального IP-адреса устройства
- Индикация прогресса сканирования сети
- Просмотр веб-интерфейса устройства
- Поддержка сжатия gzip при обмене данными с устройствами

## Установка

1. Скачайте последнюю версию APK из раздела Releases
2. Установите APK на ваше Android устройство
3. Предоставьте необходимые разрешения при первом запуске

## Использование

1. Запустите приложение
2. При первом запуске будет выполнено автоматическое сканирование сети
3. Для повторного сканирования нажмите на иконку шестеренки в правом верхнем углу
4. Для просмотра веб-интерфейса устройства нажмите на его карточку
5. Для удаления устройства из списка сделайте свайп влево по карточке


## Особенности
- Современный Material You дизайн в стиле Android
- Поддержка светлой и тёмной темы
- Встроенный веб-просмотр без элементов браузера
- Оптимизированное параллельное сканирование сети
- Автоматическое обновление статуса устройств
- Поддержка всех устройств с веб-интерфейсом GyverSettings

## Системные требования
- Android 10.0 (API 29) и выше
- Разрешение на доступ к локальной сети
- Включенный Wi-Fi
- Устройства с библиотекой GyverSettings v1.0.13+

## Совместимость
- ESP8266
- ESP32
- Любые устройства с веб-интерфейсом GyverSettings v1.0.13+

## Разработка
Проект использует:
- Kotlin
- Jetpack Compose
- Material 3
- Coroutines
- WebView

## Благодарности
- [AlexGyver](https://github.com/AlexGyver) - за создание библиотеки GyverSettings
- [Anthropic](https://www.anthropic.com) - за Claude 3.5 Sonnet, который помог в разработке

## Лицензия
MIT License 