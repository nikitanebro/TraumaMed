package ru.medic.kpk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Держит таймеры жгутов живыми при выключенном экране: foreground-сервис + частичный wake lock.
 * Работает, только пока есть хотя бы один активный жгут.
 */
class TimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private val tick = object : Runnable {
        override fun run() {
            checkThresholds()
            if (Repo.hasActive()) {
                updateNotification()
                handler.postDelayed(this, TICK_MS)
            } else {
                shutdown()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        val channel = NotificationChannel(CHANNEL, "Таймеры жгутов", NotificationManager.IMPORTANCE_LOW)
        channel.setSound(null, null)
        channel.enableVibration(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MedicKPK:timers").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return Service.START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun checkThresholds() {
        val s = Repo.state.value
        val now = System.currentTimeMillis()
        val warnMs = s.settings.warnMinutes * 60_000L
        val critMs = s.settings.critMinutes * 60_000L
        for (c in s.casualties) {
            for (t in c.tourniquets) {
                if (!t.active) continue
                val elapsed = now - t.startedAt
                if (!t.critFired && elapsed >= critMs) {
                    Haptics.critical(this)
                    Repo.markFired(c.id, t.id, critical = true)
                } else if (!t.warnFired && elapsed >= warnMs) {
                    Haptics.warn(this)
                    Repo.markFired(c.id, t.id, critical = false)
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        val s = Repo.state.value
        val now = System.currentTimeMillis()
        val active = s.casualties.flatMap { c -> c.tourniquets.filter { it.active }.map { c to it } }
        val oldest = active.minByOrNull { it.second.startedAt }
        val text = if (oldest == null) {
            "Активных жгутов нет"
        } else {
            "Жгутов: ${active.size}. Самый долгий: ${formatElapsed(now - oldest.second.startedAt)}, раненый №${oldest.first.number}"
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cross)
            .setContentTitle("Таймеры жгутов")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun shutdown() {
        handler.removeCallbacks(tick)
        releaseWakeLock()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL = "tourniquets"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 5_000L

        /** Запускает сервис, если есть активные жгуты, и останавливает, если их нет. */
        fun sync(ctx: Context) {
            val intent = Intent(ctx, TimerService::class.java)
            if (Repo.hasActive()) ctx.startForegroundService(intent) else ctx.stopService(intent)
        }
    }
}
