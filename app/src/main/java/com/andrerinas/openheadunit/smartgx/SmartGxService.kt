package com.andrerinas.openheadunit.smartgx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps MySpinLink alive independently of the Activity.
 * Without this, Android suspends/kills the process after minimizing and the TFT screen goes dark.
 */
class SmartGxService : Service() {
    private var link: MySpinLink? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FileLog.init(this)
        if (intent?.action == ACTION_STOP) {
            FileLog.write("SERVICE: stop from UI")
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (link == null) {
            val l = MySpinLink(applicationContext) { msg ->
                FileLog.write(msg)
                LogBus.post(msg)          // for display in SmartGxActivity (see below)
            }
            link = l
            acquireLocks()
            l.start(Regex("SUZUKI|MYSPIN|GSX|Bosch", RegexOption.IGNORE_CASE), 8158)
            FileLog.write("SERVICE: MySpinLink started")
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("smartgx", "SmartGX", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = android.app.PendingIntent.getService(
            this, 0, Intent(this, SmartGxService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "smartgx")
            .setContentTitle("SmartGX running")
            .setContentText("Connected to GX display")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                1, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else startForeground(1, n)
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartGX:link").apply {
            setReferenceCounted(false); acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(
            if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SmartGX:wifi"
        ).apply { setReferenceCounted(false); acquire() }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        FileLog.write("SERVICE: onTaskRemoved (app closed from recents) - service keeps running")
    }

    override fun onDestroy() {
        FileLog.write("SERVICE: onDestroy")
        link?.stop(); link = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object { const val ACTION_STOP = "smartgx.STOP" }
}

/** Simple log -> UI bridge (SmartGxActivity sets the listener in onStart and clears it in onStop). */
object LogBus {
    @Volatile var listener: ((String) -> Unit)? = null
    fun post(msg: String) { listener?.invoke(msg) }
}
