package com.fitzy.torio.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

const val RELEASES_PAGE = "https://github.com/Fitzy1590/Torio/releases/latest"

data class UpdateInfo(val build: Int, val apkUrl: String, val pageUrl: String)

/** Checks the latest GitHub release (tag build-N) against this app's versionCode. */
object Updater {
    fun currentBuild(ctx: Context): Int =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()

    /** Returns the newer release, or null when this build is current. Throws on network errors. */
    suspend fun check(http: OkHttpClient, current: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/Fitzy1590/Torio/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return@withContext null
            if (!r.isSuccessful) error("GitHub returned ${r.code}")
            val o = Json.parseToJsonElement(r.body!!.string()).jsonObject
            val build = o["tag_name"]?.jsonPrimitive?.content?.removePrefix("build-")?.toIntOrNull() ?: return@withContext null
            if (build <= current) return@withContext null
            val apk = o["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                ?.get("browser_download_url")?.jsonPrimitive?.content ?: return@withContext null
            UpdateInfo(build, apk, o["html_url"]?.jsonPrimitive?.content ?: RELEASES_PAGE)
        }
    }

    suspend fun download(ctx: Context, http: OkHttpClient, info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "Torio-${info.build}.apk")
        http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) error("Download failed (${r.code})")
            r.body!!.byteStream().use { i -> out.outputStream().use { i.copyTo(it) } }
        }
        out
    }

    /** Opens the system installer. Returns false if the user must first allow installs from Torio. */
    fun install(ctx: Context, apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }
}
