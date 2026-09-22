package com.androidvirtualcam.screen

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service for MediaProjection screen capture.
 *
 * Android 14+ requires MediaProjection to be captured from a foreground service
 * with type mediaProjection. This service holds MediaProjection instance
 * and provides it to ScreenCaptureManager.
 */
class ScreenCaptureService : Service() {

    private val tag = "ScreenCaptureService"

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
        fun getMediaProjection(): MediaProjection? = mediaProjection
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        instance = this
        Log.d(tag, "Service created, instance set")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> {
                // Samsung S22 Ultra fix: Start foreground service BEFORE MediaProjection permission dialog
                // Samsung blocks MediaProjection without explicit user confirmation if service not already running
                Log.i(tag, "ACTION_PREPARE: Starting foreground service BEFORE permission dialog - Samsung fix")
                startForegroundWithNotification(isPreparing = true)
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != -1 && resultData != null) {
                    startForegroundWithNotification(isPreparing = false)
                    // On Android 14+, need small delay after startForeground before getMediaProjection
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            createMediaProjection(resultCode, resultData)
                        }, 200)
                    } else {
                        createMediaProjection(resultCode, resultData)
                    }
                } else {
                    Log.e(tag, "Invalid resultCode or data for ACTION_START")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification(isPreparing: Boolean = false) {
        val channelId = "screen_capture_channel"
        val channelName = "Screen Capture"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Screen capture for virtual camera"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (isPreparing) "Preparing screen capture - Samsung S22 Ultra fix: service running before permission" else "Capturing screen for tutorial/gaming overlay"
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Virtual Cam – Screen Capture")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Log.d(tag, "Foreground notification started isPreparing=$isPreparing - Samsung fix: service before permission")
    }

    private fun createMediaProjection(resultCode: Int, data: Intent) {
        try {
            val projection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            if (projection == null) {
                Log.e(tag, "MediaProjection is null")
                stopSelf()
                return
            }

            mediaProjection = projection

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(tag, "MediaProjection stopped")
                    stopCapture()
                    stopSelf()
                }
            }, null)

            val readyIntent = Intent(ACTION_PROJECTION_READY)
            sendBroadcast(readyIntent)

            Log.i(tag, "MediaProjection created and ready")

        } catch (e: Exception) {
            Log.e(tag, "Failed to create MediaProjection", e)
            stopSelf()
        }
    }

    private fun stopCapture() {
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(tag, "MediaProjection stop failed", e)
        }
        mediaProjection = null

        stopForeground(STOP_FOREGROUND_REMOVE)

        Log.i(tag, "Capture stopped")
    }

    override fun onDestroy() {
        stopCapture()
        instance = null
        super.onDestroy()
        Log.d(tag, "Service destroyed, instance cleared")
    }

    companion object {
        @Volatile
        var instance: ScreenCaptureService? = null
            private set

        fun getMediaProjectionStatic(): MediaProjection? = instance?.mediaProjection

        const val ACTION_PREPARE = "com.androidvirtualcam.screen.ACTION_PREPARE"
        const val ACTION_START = "com.androidvirtualcam.screen.ACTION_START"
        const val ACTION_STOP = "com.androidvirtualcam.screen.ACTION_STOP"
        const val ACTION_PROJECTION_READY = "com.androidvirtualcam.screen.PROJECTION_READY"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        const val NOTIFICATION_ID = 1001

        fun getPrepareIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_PREPARE
            }
        }

        fun getStartIntent(context: Context, resultCode: Int, data: Intent): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
        }

        fun getStopIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
