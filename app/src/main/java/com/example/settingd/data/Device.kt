package com.example.settingd.data

data class Device(
    val ipAddress: String,
    var isOnline: Boolean = false,
    val name: String = "",
    val model: String = "",
    val mac: String = ""
) 