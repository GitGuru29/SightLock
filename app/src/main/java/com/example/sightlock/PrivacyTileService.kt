package com.example.sightlock

import android.app.Service
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Android Quick Settings Tile to manually toggle the Privacy Display mesh pattern.
 */
class PrivacyTileService : TileService() {

    companion object {
        private const val TAG = "PrivacyTileService"
        
        // This static state works well enough since the service is typically active
        // while the tile is. A shared preference would be more robust for across-reboots.
        var isMeshActive = false
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        
        // Toggle the state
        isMeshActive = !isMeshActive
        
        Log.d(TAG, "Privacy Tile clicked. New state: \$isMeshActive")
        
        // Start or stop the mesh foreground service
        val serviceIntent = Intent(this, PrivacyMeshService::class.java).apply {
            action = PrivacyMeshService.ACTION_TOGGLE
        }

        if (isMeshActive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            stopService(serviceIntent)
        }
        
        // Update the visual tile UI
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        
        if (isMeshActive) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Privacy Display On"
            tile.subtitle = "Shielding Screen"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Privacy Display Off"
            tile.subtitle = "Tap to Shield"
        }
        
        tile.updateTile()
    }
}
