package com.droplocal.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import com.droplocal.app.ui.NavGraph
import com.droplocal.app.ui.theme.DropLocalTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        val app = application as DropLocalApp
        setContent {
            DropLocalTheme { NavGraph(app) }
        }
    }

    override fun onDestroy() {
        // Rotation is not a user-requested disconnect. Application owns live transport.
        if (isFinishing) (application as DropLocalApp).nearby.disconnect()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) (application as DropLocalApp).nearby.stopAll()
    }
}
