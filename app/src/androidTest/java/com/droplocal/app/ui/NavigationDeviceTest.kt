package com.droplocal.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droplocal.app.MainActivity
import com.droplocal.app.DropLocalApp
import com.droplocal.app.nearby.ConnState
import android.Manifest
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun textDraftSurvivesActivityRecreation() {
        ui.onNodeWithText("Send text").performClick()
        ui.onNode(hasSetTextAction()).performTextInput("Synthetic rotation draft 😀")
        ui.activityRule.scenario.recreate()
        ui.onNode(hasSetTextAction()).assert(hasText("Synthetic rotation draft 😀"))
        ui.onNodeWithText("Choose device").assertIsEnabled()
    }

    @Test fun leavingTextCanKeepDraftWithoutSendingIt() {
        ui.onNodeWithText("Send text").performClick()
        ui.onNode(hasSetTextAction()).performTextInput("Synthetic unsent draft")
        ui.onNodeWithText("Back").performClick()
        ui.onNodeWithText("Leave unsent text?").assertIsDisplayed()
        ui.onNodeWithText("Keep draft and leave").performClick()
        ui.onNodeWithText("DropLocal").assertIsDisplayed()
        ui.onNodeWithText("Send text").performClick()
        ui.onNode(hasSetTextAction()).assert(hasText("Synthetic unsent draft"))
    }

    @Test fun historyCanBeOpenedAndLeftWithoutClearingExistingRecords() {
        ui.onNodeWithText("History").performClick()
        ui.onNodeWithText("Last 10 local transfers · Message contents are not stored").assertIsDisplayed()
        ui.onNodeWithText("Back").performScrollTo().performClick()
        ui.onNodeWithText("DropLocal").assertIsDisplayed()
    }

    @Test fun systemBackHonoursUnsentDraftGuard() {
        ui.onNodeWithText("Send text").performClick()
        ui.onNode(hasSetTextAction()).performTextInput("Synthetic back guard")
        ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithText("Leave unsent text?").assertIsDisplayed()
    }

    @Test fun systemBackFromDiscoveryStopsRadioWorkAndReturnsHome() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (permission in listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.NEARBY_WIFI_DEVICES)) {
            automation.grantRuntimePermission(ui.activity.packageName, permission)
        }
        ui.onNodeWithText("Send text").performClick()
        ui.onNode(hasSetTextAction()).performTextInput("Synthetic discovery guard")
        ui.onNodeWithText("Choose device").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Choose a nearby device").fetchSemanticsNodes().isNotEmpty() }
        ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithText("DropLocal").assertIsDisplayed()
        ui.runOnIdle { assertEquals(ConnState.IDLE, (ui.activity.application as DropLocalApp).nearby.connState.value) }
    }
}
