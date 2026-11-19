package com.fitzy.torio.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.fitzy.torio.TorioApp
import com.fitzy.torio.net.TorBoxApiClient
import com.fitzy.torio.net.isAudioFile
import okhttp3.Request
import java.io.File

/** Downloads every track of a book into app storage and flips the book to "downloaded". */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private fun foreground(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Torio download").setContentText(text).setOngoing(true).setOnlyAlertOnce(true).build()
        val id = (inputData.getString("bookId") ?: "").hashCode()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, n)
    }

    // Required for expedited work on Android 8-11; without it the worker crashes.
    override suspend fun getForegroundInfo() = foreground("Downloading audiobook")

    override suspend fun doWork(): Result {
        val bookId = inputData.getString("bookId") ?: return Result.failure()
        val c = (applicationContext as TorioApp).container
        val dao = c.db.bookDao()
        val torbox = TorBoxApiClient(c.http) { c.settings.torboxKey }
        val dir = File(applicationContext.filesDir, "books/${bookId.hashCode()}").apply { mkdirs() }
        val title = dao.book(bookId)?.title ?: "audiobook"
        return try {
            val all = dao.tracks(bookId)
            for ((n, t) in all.withIndex()) {
                if (t.localPath != null && File(t.localPath).exists()) continue
                runCatching { setForeground(foreground("$title (${n + 1}/${all.size})")) }
                val url = if (t.remoteUrl.startsWith("torbox://")) {
                    val u = android.net.Uri.parse(t.remoteUrl)
                    torbox.requestLink(u.host!!.toLong(), u.pathSegments[0].toLong())
                } else t.remoteUrl
                val ext = t.title.substringAfterLast('.', "").takeIf { isAudioFile("x.$it") } ?: "mp3"
                val out = File(dir, "${t.idx}.$ext")
                c.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    r.body!!.byteStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                }
                dao.setLocalPath(bookId, t.idx, out.absolutePath)
            }
            dao.setDownloaded(bookId, true)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val CHANNEL = "downloads"
        fun enqueue(ctx: Context, bookId: String) {
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("bookId" to bookId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("dl-$bookId", ExistingWorkPolicy.KEEP, req)
        }
    }
}
