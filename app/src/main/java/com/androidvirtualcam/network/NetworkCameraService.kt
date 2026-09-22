package com.androidvirtualcam.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.androidvirtualcam.framebus.VCamFrameBus
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Production network camera service – fallback for non-root devices.
 * Now uses SharedMemory ring buffer (VCamFrameBus) instead of file polling.
 * Serves MJPEG at http://phone_ip:8080/video and HTML at /
 *
 * Senior additions:
 * - Connects as consumer to VCamFrameBus via Unix socket
 * - Converts NV21 to JPEG via YuvImage (production would use native libjpeg)
 * - Proper lifecycle, error handling, resource cleanup
 */
class NetworkCameraService : Service() {

    companion object {
        const val CHANNEL_ID = "network_camera"
        const val NOTIFICATION_ID = 3001
        const val PORT = 8080
    }

    private var server: MJpegServer? = null
    private val tag = "NetworkCameraService"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        Log.i(tag, "NetworkCameraService created – production bus mode")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        try {
            var frameBus: VCamFrameBus? = null
            try {
                frameBus = VCamFrameBus.connectAsConsumer()
                Log.i(tag, "Network service connected to frame bus: ${frameBus.maxWidth}x${frameBus.maxHeight}")
            } catch (e: Exception) {
                Log.w(tag, "Frame bus not available yet, will retry on request: ${e.message}")
            }

            server = MJpegServer(PORT, frameBus)
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(tag, "MJPEG server started on port $PORT")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start server", e)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            server?.stop()
            server?.frameBus?.close()
        } catch (e: Exception) {
            Log.w(tag, "Cleanup failed", e)
        }
        Log.i(tag, "NetworkCameraService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Network Camera", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Serves composed video over HTTP via SharedMemory bus"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Network Camera Active")
            .setContentText("MJPEG at http://<phone_ip>:8080 – SharedMemory bus")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    class MJpegServer(port: Int, var frameBus: VCamFrameBus?) : NanoHTTPD(port) {
        private val tag = "MJpegServer"

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            return when {
                uri == "/" || uri == "/index.html" -> {
                    val html = """
                        <html><head><title>Android Virtual Cam – Free ManyCam</title></head>
                        <body style="background:#121212;color:white;font-family:sans-serif;text-align:center">
                        <h1>Android Virtual Cam – Free ManyCam Alternative</h1>
                        <p>Composed feed via SharedMemory ring buffer – 100% free, no watermark</p>
                        <img src="/video" style="max-width:90%;border:2px solid #3DDC84"/>
                        <p>Use this URL in OBS Browser Source: <code>http://PHONE_IP:8080/video</code></p>
                        <p>System-wide injection requires root + LSPosed – see app settings</p>
                        <p>Frame bus: ${frameBus?.let { "${it.maxWidth}x${it.maxHeight}, slot ${it.slotSize}" } ?: "not connected yet"}</p>
                        </body></html>
                    """.trimIndent()
                    newFixedLengthResponse(Response.Status.OK, "text/html", html)
                }
                uri == "/video" -> {
                    try {
                        if (frameBus == null) {
                            try {
                                frameBus = VCamFrameBus.connectAsConsumer()
                            } catch (e: Exception) {
                                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Frame bus not available – start Virtual Camera service first: ${e.message}")
                            }
                        }

                        val bus = frameBus ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No bus")
                        val desc = bus.acquireLatestFrame()
                        if (desc == null) {
                            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No frame yet – start Virtual Camera service")
                        }

                        try {
                            val nv21Array = ByteArray(desc.dataSize)
                            desc.data.duplicate().get(nv21Array)
                            val yuvImage = YuvImage(nv21Array, ImageFormat.NV21, desc.width, desc.height, null)
                            val out = ByteArrayOutputStream()
                            yuvImage.compressToJpeg(Rect(0, 0, desc.width, desc.height), 85, out)
                            val jpegBytes = out.toByteArray()
                            val input = ByteArrayInputStream(jpegBytes)
                            newChunkedResponse(Response.Status.OK, "image/jpeg", input)
                        } finally {
                            bus.releaseFrame(desc)
                        }

                    } catch (e: Exception) {
                        Log.e(tag, "serve /video failed", e)
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
                    }
                }
                uri == "/config" -> {
                    newFixedLengthResponse(Response.Status.OK, "application/json", "{\"frameBus\":\"${frameBus?.maxWidth}x${frameBus?.maxHeight}\",\"transport\":\"SharedMemory ring buffer\"}")
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        }
    }
}
