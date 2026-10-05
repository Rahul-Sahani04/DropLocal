package com.droplocal.app.ui

import android.Manifest
import org.junit.Assert.*
import org.junit.Test

class PermissionsTest {
    @Test fun legacyReceivingRequestsDownloadsButSendingDoesNot() {
        assertTrue(Manifest.permission.WRITE_EXTERNAL_STORAGE in transportPermissions(28, true))
        assertFalse(Manifest.permission.WRITE_EXTERNAL_STORAGE in transportPermissions(28, false))
        assertFalse(Manifest.permission.WRITE_EXTERNAL_STORAGE in transportPermissions(29, true))
    }
    @Test fun android12RequestsBothLocationPermissionsAndBluetooth() {
        for (sdk in 31..32) {
            val permissions = transportPermissions(sdk)
            assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
            assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
            assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
            assertTrue(Manifest.permission.BLUETOOTH_ADVERTISE in permissions)
            assertTrue(Manifest.permission.BLUETOOTH_CONNECT in permissions)
        }
    }
    @Test fun modernTransportDoesNotRequireCameraNotificationsOrLocation() {
        val permissions = transportPermissions(36, true)
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in permissions)
        assertFalse(Manifest.permission.CAMERA in permissions)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertEquals(permissions.size, permissions.distinct().size)
    }

    @Test fun preciseLocationUpgradeStillRequestsCoarseAndFineTogether() {
        val requested = permissionRequestSet(transportPermissions(31), setOf(Manifest.permission.ACCESS_COARSE_LOCATION), 31)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in requested)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in requested)
        assertTrue(permissionRequestSet(transportPermissions(36), transportPermissions(36).toSet(), 36).isEmpty())
    }
}
