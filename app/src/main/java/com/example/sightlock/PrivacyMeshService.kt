package com.example.sightlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * Service responsible for drawing the PrivacyMeshView over the entire screen.
 * It runs as a foreground service so it isn't killed, and uses TYPE_APPLICATION_OVERLAY
 * with FLAG_NOT_TOUCHABLE to allow interaction with underlying apps.
 */
class PrivacyMeshService : Service() {

    private var windowManager: WindowManager? = null
    private var meshView: PrivacyMeshView? = null
    private var isOverlayShowing = false

    companion object {
        private const val TAG = "PrivacyMeshService"
        private const val NOTIFICATION_CHANNEL_ID = "privacy_mesh_channel"
        private const val NOTIFICATION_ID = 2

        const val ACTION_TOGGLE = "com.example.sightlock.ACTION_TOGGLE_MESH"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createOverlayView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        if (intent?.action == ACTION_TOGGLE) {
            if (isOverlayShowing) {
                hideOverlay()
            } else {
                showOverlay()
            }
        } else {
            // Default to showing if started directly
            showOverlay()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Privacy Display Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the privacy mesh active"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Privacy Display Active")
            .setContentText("Screen contents are hidden from side angles.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createOverlayView() {
        if (meshView == null) {
            meshView = PrivacyMeshView(this)
        }
    }

    private fun showOverlay() {
        if (isOverlayShowing) return
        if (meshView == null) createOverlayView()

        // FLAG_NOT_TOUCHABLE is crucial so the user can still use their phone!
        // FLAG_NOT_FOCUSABLE prevents it from stealing keyboard focus.
        // FLAG_LAYOUT_IN_SCREEN ensures it covers the status bar/nav bar.
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        try {
            meshView?.setMeshActive(true)
            windowManager?.addView(meshView, params)
            isOverlayShowing = true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding privacy mesh overlay: ${e.message}")
        }
    }

    private fun hideOverlay() {
        if (!isOverlayShowing) return

        try {
            meshView?.setMeshActive(false)
            windowManager?.removeView(meshView)
            isOverlayShowing = false
        } catch (e: Exception) {
            Log.e(TAG, "Error removing privacy mesh overlay: ${e.message}")
        }
    }
}
