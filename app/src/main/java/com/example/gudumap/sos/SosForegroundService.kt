package com.example.gudumap.sos

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

class SosForegroundService : Service() {

    companion object {
        private const val TAG = "Gudumap:SosService"
        private const val NOTIFICATION_ID = 9911

        const val ACTION_START_SOS = "com.example.gudumap.sos.START_SOS"
        const val ACTION_STOP_SOS = "com.example.gudumap.sos.STOP_SOS"

        fun startService(context: Context) {
            try {
                val intent = Intent(context, SosForegroundService::class.java).apply {
                    action = ACTION_START_SOS
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch SosForegroundService: ${e.message}")
            }
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, SosForegroundService::class.java).apply {
                    action = ACTION_STOP_SOS
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop SosForegroundService: ${e.message}")
            }
        }
    }

    private var bleManager: SosBleManager? = null

    override fun onCreate() {
        super.onCreate()
        bleManager = SosBleManager(this)
        Log.i(TAG, "SosForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_SOS) {
            Log.i(TAG, "SosForegroundService stopping via ACTION_STOP_SOS")
            bleManager?.stopSosBroadcast()
            bleManager?.stopScanning()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = SosNotificationManager.getForegroundServiceNotification(
            this,
            "Broadcasting Emergency SOS to nearby devices..."
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    } else 0
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start foreground notification: ${e.message}")
        }

        val activePacket = SosRepository.activeSosPacket.value
        if (activePacket != null) {
            bleManager?.startSosBroadcast(activePacket)
        }
        bleManager?.startScanning()

        return START_STICKY
    }

    override fun onDestroy() {
        bleManager?.stopSosBroadcast()
        bleManager?.stopScanning()
        Log.i(TAG, "SosForegroundService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
