package com.androidvirtualcam.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.androidvirtualcam.framebus.VCamFrameBus
import fi.iki.elonen.NanoHTTPD

/**
 * WebControlService – foreground service that runs RemoteControlServer (NanoHTTPD).
 *
 * Serves remote control UI on WiFi network: http://PHONE_IP:8080
 * Allows control from any browser on same WiFi – change scenes, toggle effects, start/stop stream from PC while phone on tripod.
 *
 * Extends existing NetworkCameraService concept but with full remote control API + web UI.
 * NanoHTTPD already in project – this extends it.
 *
 * Features:
 * - MJPEG preview at /video for OBS Browser Source
 * - REST API at /api/ endpoints
 * - Full HTML remote UI at /
 * - Foreground notification with URL
 * - Auto-discovers WiFi IP
 */
class WebControlService : Service() {

    companion object {
        const val CHANNEL_ID = "web_control"
        const val NOTIFICATION_ID = 3002
        const val DEFAULT_PORT = 8080

        const val ACTION_START = "com.androidvirtualcam.network.ACTION_START_WEB"
        const val ACTION_STOP = "com.androidvirtualcam.network.ACTION_STOP_WEB"

        fun getStartIntent(context: Context, port: Int = DEFAULT_PORT): Intent {
            return Intent(context, WebControlService::class.java).apply {
                action = ACTION_START
                putExtra("port", port)
            }
        }

        fun getStopIntent(context: Context): Intent {
            return Intent(context, WebControlService::class.java).apply {
                action = ACTION_STOP
            }
        }

        fun getWifiIpAddress(context: Context): String {
            return try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ip = wifiManager.connectionInfo.ipAddress
                // Convert int IP to string
                String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff,
                    ip shr 8 and 0xff,
                    ip shr 16 and 0xff,
                    ip shr 24 and 0xff
                )
            } catch (e: Exception) {
                "PHONE_IP"
            }
        }
    }

    private var server: RemoteControlServer? = null
    private var currentPort = DEFAULT_PORT
    private val tag = "WebControlService"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        Log.i(tag, "WebControlService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentPort = intent.getIntExtra("port", DEFAULT_PORT)
                startServer(currentPort)
            }
            ACTION_STOP -> {
                stopServer()
                stopSelf()
            }
            else -> {
                // Default start if no action
                startServer(currentPort)
            }
        }
        return START_STICKY
    }

    private fun startServer(port: Int) {
        try {
            stopServer()

            var frameBus: VCamFrameBus? = null
            try {
                frameBus = VCamFrameBus.connectAsConsumer()
                Log.i(tag, "Connected to frame bus for MJPEG")
            } catch (e: Exception) {
                Log.w(tag, "Frame bus not available yet: ${e.message}")
            }

            server = RemoteControlServer(port, frameBus)
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

            val ip = getWifiIpAddress(this)
            val notification = buildNotification(ip, port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            Log.i(tag, "Remote control server started on http://$ip:$port – control from PC browser")

        } catch (e: Exception) {
            Log.e(tag, "Failed to start server", e)
            stopSelf()
        }
    }

    private fun stopServer() {
        try {
            server?.stop()
            server = null
            Log.i(tag, "Server stopped")
        } catch (e: Exception) {
            Log.w(tag, "Stop server failed", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
        Log.i(tag, "WebControlService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Web Remote Control", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Remote control from browser on WiFi – change scenes, toggle effects, start/stop stream"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(ip: String, port: Int): Notification {
        val url = "http://$ip:$port"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VCAM Remote Control Active")
            .setContentText("Control from PC: $url – scenes, effects, stream")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Open $url from any browser on same WiFi to control app from PC while phone is on tripod.\n\nFeatures:\n• Change scenes with transitions\n• Toggle Center Stage, effects\n• Start/stop recording & streaming\n• Control screen capture & teleprompter\n• Live MJPEG preview")
            )
            .build()
    }
}
