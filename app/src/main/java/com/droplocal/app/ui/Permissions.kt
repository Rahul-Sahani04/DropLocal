package com.droplocal.app.ui

import android.Manifest
import android.os.Build

/** PRD §16: permission set varies by OS version. */
fun requiredPermissions(): List<String> {
    val list = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= 31) {
        list += Manifest.permission.BLUETOOTH_SCAN
        list += Manifest.permission.BLUETOOTH_ADVERTISE
        list += Manifest.permission.BLUETOOTH_CONNECT
    } else {
        list += Manifest.permission.BLUETOOTH
        list += Manifest.permission.BLUETOOTH_ADMIN
        list += Manifest.permission.ACCESS_FINE_LOCATION
    }
    if (Build.VERSION.SDK_INT in 29..32) {
        list += Manifest.permission.ACCESS_FINE_LOCATION
    }
    if (Build.VERSION.SDK_INT >= 33) {
        list += Manifest.permission.NEARBY_WIFI_DEVICES
        list += Manifest.permission.POST_NOTIFICATIONS
    }
    if (Build.VERSION.SDK_INT >= 26) {
        list += Manifest.permission.CAMERA // optional: QR scan only
    }
    return list.distinct()
}
