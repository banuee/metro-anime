package dev.metro.anime.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.metro.anime.data.model.DownloadStatus
import dev.metro.anime.data.model.DownloadTask
import dev.metro.anime.data.model.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null
    private var lastStartId: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(DownloadManager.tasks.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            // Foreground start can fail on newer restrictions; keep service best-effort
        }

        if (collector == null) {
            collector = scope.launch {
                DownloadManager.tasks.collectLatest { tasks ->
                    if (tasks.none { it.status.isActive }) {
                        // Перепроверяем: задача могла добавиться между сбором списка
                        // и остановкой сервиса (тогда сервис нельзя останавливать).
                        if (DownloadManager.tasks.value.none { it.status.isActive }) {
                            stopForegroundCompat()
                            // stopSelfResult(lastStartId) — не трогает более новые запросы старта
                            stopSelfResult(lastStartId)
                        }
                    } else {
                        notifyNotification(buildNotification(tasks))
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun stopForegroundCompat() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
    }

    private fun notifyNotification(notification: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {}
    }

    private fun buildNotification(tasks: List<DownloadTask>): Notification {
        val active = tasks.filter { it.status.isActive }
        val downloading = active.firstOrNull { it.status == DownloadStatus.DOWNLOADING }

        val title = if (downloading != null) {
            "Скачивание: ${downloading.anime.titleRu}"
        } else {
            "Подготовка загрузки…"
        }
        val text = buildString {
            if (downloading != null) append("Серия ${downloading.episodeOrdinal}")
            val pending = active.count { it.status == DownloadStatus.QUEUED }
            if (pending > 0) {
                if (isNotEmpty()) append(" • ")
                append("в очереди: $pending")
            }
            if (isEmpty()) append("Скачивание серий")
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (downloading != null && downloading.totalBytes > 0) {
            val max = 100
            val progress = (downloading.progress * max).toInt().coerceIn(0, max)
            builder.setProgress(max, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Загрузки",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Скачивание серий для просмотра офлайн"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        collector = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 5001

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, DownloadService::class.java))
            } catch (_: Exception) {}
        }
    }
}