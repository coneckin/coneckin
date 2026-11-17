package com.panlink.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class LoginTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Login WiFi"
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_AUTO_LOGIN, true)
        }
        val launch = {
            if (Build.VERSION.SDK_INT >= 34) {
                val pending = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pending)
            } else {
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun { launch() } else launch()
    }
}