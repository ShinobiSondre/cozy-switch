package com.cozyswitch.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import com.cozyswitch.app.ui.MainScreen
import com.cozyswitch.app.update.Installer
import com.cozyswitch.app.update.UpdateOverlay
import com.cozyswitch.app.update.UpdateWorker
import com.cozyswitch.app.update.Updater
import com.cozyswitch.app.widget.PlugWidget

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Updater.start(this)
        UpdateWorker.schedule(this)
        com.cozyswitch.app.auto.Scheduler.rescheduleAll(this)
        com.cozyswitch.app.data.RefreshWorker.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            Box {
                MainScreen()
                UpdateOverlay()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Installer.appVisible = true
        Updater.onResume(this)
        com.cozyswitch.app.data.LiveLink.start(this) // standing cloud link while the app is open, like CozyLife
    }

    override fun onPause() {
        super.onPause()
        Installer.appVisible = false
        com.cozyswitch.app.data.LiveLink.stopSoon()
        PlugWidget.updateAll(this)
    }
}
