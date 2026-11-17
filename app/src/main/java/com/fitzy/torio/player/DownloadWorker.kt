package com.fitzy.torio.player

import android.content.Context
import androidx.work.*
import com.fitzy.torio.TorioApp
import com.fitzy.torio.net.TorBoxApiClient
import okhttp3.Request
import java.io.File

/** Downloads every track of a book into app storage and flips the book to "downloaded". */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val bookId = inputData.getString("bookId") ?: return Result.failure()
        val c = (applicationContext as TorioApp).container
        val dao = c.db.bookDao()
        val torbox = TorBoxApiClient(c.http) { c.settings.torboxKey }
        val dir = File(applicationContext.filesDir, "books/${bookId.hashCode()}").apply { mkdirs() }
        return try {
            for (t in dao.tracks(bookId)) {
                if (t.localPath != null && File(t.localPath).exists()) continue
                val url = if (t.remoteUrl.startsWith("torbox://")) {
                    val u = android.net.Uri.parse(t.remoteUrl)
                    torbox.requestLink(u.host!!.toLong(), u.pathSegments[0].toLong())
                } else t.remoteUrl
                val ext = t.title.substringAfterLast('.', "mp3").take(4)
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
        fun enqueue(ctx: Context, bookId: String) {
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("bookId" to bookId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("dl-$bookId", ExistingWorkPolicy.KEEP, req)
        }
    }
}
