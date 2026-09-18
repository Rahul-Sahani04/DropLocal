package com.droplocal.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.droplocal.app.ui.NavGraph
import com.droplocal.app.ui.requiredPermissions
import com.droplocal.app.ui.theme.DropLocalTheme

class MainActivity : ComponentActivity() {
    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        perms.launch(requiredPermissions().toTypedArray())
        val app = application as DropLocalApp
        setContent {
            DropLocalTheme { NavGraph(app) }
        }
    }

    override fun onDestroy() {
        try { (application as DropLocalApp).nearby.stopAll() } catch (_: Exception) { }
        super.onDestroy()
    }
}
