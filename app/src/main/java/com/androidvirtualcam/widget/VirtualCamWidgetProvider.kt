package com.androidvirtualcam.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.androidvirtualcam.R
import com.androidvirtualcam.service.VirtualCameraService
import com.androidvirtualcam.network.WebControlService
import android.util.Log

/**
 * Quick toggle widget: one tap to start/stop VirtualCam service and streaming without opening full app
 * 
 * Widget shows:
 * - VirtualCam toggle button
 * - Streaming toggle button
 * - Recording toggle
 * - Status badges
 * - Opens app on tap
 */
class VirtualCamWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_VCAM = "com.androidvirtualcam.widget.TOGGLE_VCAM"
        const val ACTION_TOGGLE_STREAM = "com.androidvirtualcam.widget.TOGGLE_STREAM"
        const val ACTION_TOGGLE_RECORD = "com.androidvirtualcam.widget.TOGGLE_RECORD"
        const val ACTION_TOGGLE_REPLAY = "com.androidvirtualcam.widget.TOGGLE_REPLAY"
        const val ACTION_SAVE_REPLAY = "com.androidvirtualcam.widget.SAVE_REPLAY"
        const val ACTION_OPEN_APP = "com.androidvirtualcam.widget.OPEN_APP"

        private const val TAG = "VCamWidget"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        Log.d(TAG, "onReceive action=$action")

        when (action) {
            ACTION_TOGGLE_VCAM -> {
                toggleVirtualCam(context)
            }
            ACTION_TOGGLE_STREAM -> {
                toggleStreaming(context)
            }
            ACTION_TOGGLE_RECORD -> {
                toggleRecording(context)
            }
            ACTION_TOGGLE_REPLAY -> {
                toggleReplay(context)
            }
            ACTION_SAVE_REPLAY -> {
                saveReplay(context)
            }
            ACTION_OPEN_APP -> {
                openApp(context)
            }
        }

        // Update all widgets after action
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val ids = appWidgetManager.getAppWidgetIds(android.content.ComponentName(context, VirtualCamWidgetProvider::class.java))
        for (id in ids) {
            updateAppWidget(context, appWidgetManager, id)
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_virtualcam)

        // Check service statuses
        val isVcamRunning = VirtualCameraService.isRunning()
        val prefs = context.getSharedPreferences("vcam_widget", Context.MODE_PRIVATE)
        val isStreaming = prefs.getBoolean("isStreaming", false)
        val isRecording = prefs.getBoolean("isRecording", false)
        val isReplaying = prefs.getBoolean("isReplaying", false)

        // Update status text
        views.setTextViewText(R.id.widget_status, when {
            isVcamRunning && isStreaming -> "● VCAM + LIVE"
            isVcamRunning -> "● VCAM Active"
            isStreaming -> "● LIVE"
            else -> "○ Idle"
        })

        // Update button texts / colors based on state
        views.setTextViewText(R.id.widget_btn_vcam, if (isVcamRunning) "■ Stop VCam" else "▶ Start VCam")
        views.setTextViewText(R.id.widget_btn_stream, if (isStreaming) "■ Stop Stream" else "▶ Stream")
        views.setTextViewText(R.id.widget_btn_record, if (isRecording) "■ Stop Rec" else "● Record")
        views.setTextViewText(R.id.widget_btn_replay, if (isReplaying) "⏹ Replay" else "⏪ Replay")
        views.setTextViewText(R.id.widget_btn_save_clip, "💾 SAVE CLIP")

        // Pending intents
        views.setOnClickPendingIntent(R.id.widget_btn_vcam, getPendingIntent(context, ACTION_TOGGLE_VCAM))
        views.setOnClickPendingIntent(R.id.widget_btn_stream, getPendingIntent(context, ACTION_TOGGLE_STREAM))
        views.setOnClickPendingIntent(R.id.widget_btn_record, getPendingIntent(context, ACTION_TOGGLE_RECORD))
        views.setOnClickPendingIntent(R.id.widget_btn_replay, getPendingIntent(context, ACTION_TOGGLE_REPLAY))
        views.setOnClickPendingIntent(R.id.widget_btn_save_clip, getPendingIntent(context, ACTION_SAVE_REPLAY))
        views.setOnClickPendingIntent(R.id.widget_container, getPendingIntent(context, ACTION_OPEN_APP))
        views.setOnClickPendingIntent(R.id.widget_header, getPendingIntent(context, ACTION_OPEN_APP))

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun getPendingIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, VirtualCamWidgetProvider::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun toggleVirtualCam(context: Context) {
        try {
            val isRunning = VirtualCameraService.isRunning()
            if (isRunning) {
                val stopIntent = Intent(context, VirtualCameraService::class.java).apply {
                    this.action = VirtualCameraService.ACTION_STOP
                }
                context.startService(stopIntent)
                Log.i(TAG, "Widget: Stop VirtualCam")
            } else {
                val startIntent = Intent(context, VirtualCameraService::class.java).apply {
                    this.action = VirtualCameraService.ACTION_START
                    putExtra(VirtualCameraService.EXTRA_WIDTH, 1280)
                    putExtra(VirtualCameraService.EXTRA_HEIGHT, 720)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(startIntent)
                } else {
                    context.startService(startIntent)
                }
                Log.i(TAG, "Widget: Start VirtualCam")
            }
        } catch (e: Exception) {
            Log.e(TAG, "toggleVirtualCam failed", e)
        }
    }

    private fun toggleStreaming(context: Context) {
        try {
            val prefs = context.getSharedPreferences("vcam_widget", Context.MODE_PRIVATE)
            val isStreaming = prefs.getBoolean("isStreaming", false)
            // Use RemoteControlHub if app running, otherwise just toggle pref and notify
            com.androidvirtualcam.network.RemoteControlHub.postToMain {
                try {
                    if (isStreaming) {
                        com.androidvirtualcam.network.RemoteControlHub.stopAllStreaming()
                    } else {
                        com.androidvirtualcam.network.RemoteControlHub.startAllStreaming()
                    }
                } catch (_: Exception) {}
            }
            prefs.edit().putBoolean("isStreaming", !isStreaming).apply()
            Log.i(TAG, "Widget: Toggle Streaming -> ${!isStreaming}")
        } catch (e: Exception) {
            Log.e(TAG, "toggleStreaming failed", e)
        }
    }

    private fun toggleRecording(context: Context) {
        try {
            val prefs = context.getSharedPreferences("vcam_widget", Context.MODE_PRIVATE)
            val isRecording = prefs.getBoolean("isRecording", false)
            com.androidvirtualcam.network.RemoteControlHub.postToMain {
                try {
                    com.androidvirtualcam.network.RemoteControlHub.toggleRecording()
                } catch (_: Exception) {}
            }
            prefs.edit().putBoolean("isRecording", !isRecording).apply()
            Log.i(TAG, "Widget: Toggle Recording -> ${!isRecording}")
        } catch (e: Exception) {
            Log.e(TAG, "toggleRecording failed", e)
        }
    }

    private fun toggleReplay(context: Context) {
        try {
            val prefs = context.getSharedPreferences("vcam_widget", Context.MODE_PRIVATE)
            val isReplaying = prefs.getBoolean("isReplaying", false)
            com.androidvirtualcam.network.RemoteControlHub.postToMain {
                try {
                    com.androidvirtualcam.network.RemoteControlHub.toggleInstantReplay()
                } catch (_: Exception) {}
            }
            prefs.edit().putBoolean("isReplaying", !isReplaying).apply()
            Log.i(TAG, "Widget: Toggle Replay -> ${!isReplaying}")
        } catch (e: Exception) {
            Log.e(TAG, "toggleReplay failed", e)
        }
    }

    private fun saveReplay(context: Context) {
        try {
            com.androidvirtualcam.network.RemoteControlHub.postToMain {
                try {
                    com.androidvirtualcam.network.RemoteControlHub.saveInstantReplay()
                } catch (_: Exception) {}
            }
            Log.i(TAG, "Widget: Save Replay Clip")
        } catch (e: Exception) {
            Log.e(TAG, "saveReplay failed", e)
        }
    }

    private fun openApp(context: Context) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            launchIntent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "openApp failed", e)
        }
    }
}
