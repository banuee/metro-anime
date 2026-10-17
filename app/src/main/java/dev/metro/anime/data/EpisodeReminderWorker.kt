package dev.metro.anime.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.metro.anime.data.api.ApiClient
import dev.metro.anime.data.repository.AnimeRepository
import java.util.concurrent.TimeUnit

class EpisodeReleaseWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "EpisodeReleaseWorker"
    }

    override suspend fun doWork(): Result {
        val repo = AnimeRepository(context)
        val subscriptions = repo.getAllSubscriptions()
        if (subscriptions.isEmpty()) {
            EpisodeReminderManager.cancel(context)
            return Result.success()
        }

        val updated = mutableListOf<dev.metro.anime.data.model.EpisodeSubscription>()
        for (sub in subscriptions) {
            val shikiId = sub.shikimoriId
            if (shikiId == null || shikiId <= 0) {
                updated.add(sub)
                continue
            }
            val schedule = try {
                ApiClient.getShikimoriSchedule(shikiId)
            } catch (e: Exception) {
                Log.w(TAG, "Schedule fetch failed for ${sub.anime.titleRu}: ${e.message}")
                null
            }
            if (schedule == null) {
                updated.add(sub)
                continue
            }

            val airedNow = schedule.episodesAired
            if (schedule.isOngoing && airedNow != null && airedNow > sub.lastAiredEpisode) {
                Log.i(TAG, "Новая серия ${airedNow} — ${sub.anime.titleRu}")
                EpisodeNotificationHelper.showEpisodeNotification(context, sub.anime, airedNow)
                updated.add(sub.copy(lastAiredEpisode = airedNow, nextEpisodeAt = schedule.nextEpisodeAt))
            } else {
                updated.add(sub.copy(nextEpisodeAt = schedule.nextEpisodeAt))
            }
        }

        repo.replaceSubscriptions(updated)
        return Result.success()
    }
}

object EpisodeReminderManager {
    private const val WORK_NAME = "metro_anime_episode_reminder_worker"

    fun sync(context: Context) {
        val repo = AnimeRepository(context)
        if (repo.getAllSubscriptions().isEmpty()) {
            cancel(context)
        } else {
            schedule(context)
        }
    }

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<EpisodeReleaseWorker>(3, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}