package dev.metro.anime.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.metro.anime.MainActivity
import dev.metro.anime.data.model.AnimeTitle

object EpisodeNotificationHelper {
    const val CHANNEL_ID = "new_episodes"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Новые серии",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Уведомления о выходе новых серий отслеживаемых аниме"
                enableLights(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun showEpisodeNotification(context: Context, anime: AnimeTitle, episodeNumber: Int) {
        createNotificationChannel(context)
        if (!canPostNotifications(context)) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_ANIME, true)
            putExtra(MainActivity.EXTRA_ANIME_ID, anime.id)
            putExtra(MainActivity.EXTRA_ANIME_TITLE, anime.titleRu)
            putExtra(MainActivity.EXTRA_ANIME_POSTER, anime.posterUrl)
            putExtra(MainActivity.EXTRA_ANIME_SHIKI, anime.shikimoriId ?: 0L)
            putExtra(MainActivity.EXTRA_ANIME_SOURCE, anime.safeSource.name)
        }

        val notificationId = 4000 + (anime.id.hashCode() and 0x0FFF)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Вышла новая серия")
            .setContentText("${anime.titleRu} • Серия $episodeNumber")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${anime.titleRu}\nДоступна серия $episodeNumber")
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Permission revoked
        }
    }
}