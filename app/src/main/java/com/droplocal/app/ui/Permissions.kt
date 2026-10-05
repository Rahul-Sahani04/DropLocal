package com.droplocal.app.ui

import android.Manifest
import android.os.Build
import android.annotation.SuppressLint

/** Optional camera access is requested only for scanning, never as a startup prerequisite. */
@SuppressLint("InlinedApi") // Permission names are inlined strings; OS-specific requests are guarded by sdk below.
fun transportPermissions(sdk: Int = Build.VERSION.SDK_INT, receiving: Boolean = false): List<String> = buildList {
    if (sdk >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (sdk <= 32) {
        // Android 12+ requires COARSE and FINE in the same runtime request.
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (sdk >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    if (receiving && sdk <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}

fun permissionLabel(permission: String): String = when (permission) {
    Manifest.permission.CAMERA -> "Camera · QR scanner only"
    Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION -> "Location · required by Nearby on this Android version"
    Manifest.permission.NEARBY_WIFI_DEVICES -> "Nearby Wi-Fi devices"
    Manifest.permission.WRITE_EXTERNAL_STORAGE -> "Downloads · save received files"
    else -> "Bluetooth · nearby connections"
}

fun permissionRequestSet(required: List<String>, alreadyGranted: Set<String>, sdk: Int): List<String> {
    val missing = required.filterNot { it in alreadyGranted }
    return if (sdk in 31..32 && Manifest.permission.ACCESS_FINE_LOCATION in missing)
        (missing + Manifest.permission.ACCESS_COARSE_LOCATION).distinct() else missing
}
