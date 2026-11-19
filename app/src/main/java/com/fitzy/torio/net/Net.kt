package com.fitzy.torio.net

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.net.URLEncoder
import java.util.Locale

enum class Provider { TORBOX_CLOUD, TORBOX_SEARCH }

/** One row in the source picker. Either [hash] (needs TorBox) or [directUrl] is set. */
data class SourceResult(
    val id: String,
    val provider: Provider,
    val title: String,
    val sizeBytes: Long,
    val codec: String,
    val bitrateKbps: Int,
    val seeders: Int,
    val cached: Boolean,
    val hash: String? = null,
    val directUrl: String? = null,
    val torboxId: Long? = null,
    val usenet: Boolean = false,
    val nzbUrl: String? = null,
) {
    /** 0 = bad, 1 = ok, 2 = great */
    val health: Int get() = when {
        cached || provider == Provider.TORBOX_CLOUD || directUrl != null -> 2
        seeders >= 20 -> 2
        seeders >= 5 -> 1
        else -> 0
    }

    /** Higher = better audio: codec tier first, then bitrate. Used to sort results. */
    val quality: Int get() = SourceParser.codecTier(codec) * 10_000 + bitrateKbps.coerceAtMost(9_999)
}

object SourceParser {
    private val size = Regex("""(\d+(?:[.,]\d+)?)\s*(TB|GB|MB|KB)""", RegexOption.IGNORE_CASE)
    private val kbps = Regex("""(\d{2,4})\s*k(?:bps|b/s)?\b""", RegexOption.IGNORE_CASE)
    private val seeds = Regex("""(?:👤|seeders?:?|seeds?:?)\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val codecs = listOf("M4B", "FLAC", "AAC", "OPUS", "OGG", "MP3", "M4A")

    private val video = Regex("""\b(2160p|1080[pi]|720p|480p|4k|uhd|blu-?ray|bdrip|brrip|bdremux|web-?dl|web-?rip|hdtv|dvdrip|remux|x26[45]|h\.?26[45]|hevc|hdr10?|s\d{1,2}e\d{1,2}|mkv|avi)\b""", RegexOption.IGNORE_CASE)
    private val book = Regex("""audio\s?book|unabridged|abridged|narrat|read by|audible|librivox|\bm4b\b""", RegexOption.IGNORE_CASE)

    /** True only for audio content: rejects video releases, and needs an audio codec or audiobook wording. */
    fun isAudiobook(text: String): Boolean = !video.containsMatchIn(text) && (parseCodec(text) != "Audio" || book.containsMatchIn(text))

    fun codecTier(codec: String): Int = when (codec) {
        "FLAC" -> 5; "M4B" -> 4; "M4A", "AAC", "OPUS", "OGG" -> 3; "MP3" -> 2; else -> 1
    }

    fun parseSize(s: String): Long {
        val m = size.find(s) ?: return 0
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0
        val mult = when (m.groupValues[2].uppercase(Locale.ROOT)) {
            "TB" -> 1L shl 40; "GB" -> 1L shl 30; "MB" -> 1L shl 20; else -> 1L shl 10
        }
        return (n * mult).toLong()
    }
    fun parseCodec(s: String): String = codecs.firstOrNull { s.contains(it, ignoreCase = true) } ?: "Audio"
    fun parseBitrate(s: String): Int = kbps.find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    fun parseSeeders(s: String): Int = seeds.find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

fun formatSize(b: Long): String = when {
    b <= 0 -> "? MB"
    b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / (1L shl 30).toDouble())
    else -> String.format(Locale.US, "%d MB", b shr 20)
}

private val AUDIO_EXT = setOf("m4b", "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
fun isAudioFile(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in AUDIO_EXT

data class TorBoxFile(val id: Long, val name: String, val size: Long)
data class TorBoxTorrent(val id: Long, val name: String, val hash: String, val size: Long, val files: List<TorBoxFile>)

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.lng(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L

class TorBoxApiClient(private val http: OkHttpClient, private val key: () -> String) {
    private val base = "https://api.torbox.app/v1/api"
    private val searchBase = "https://search-api.torbox.app"

    private fun call(req: Request.Builder): JsonObject {
        val r = req.header("Authorization", "Bearer ${key()}").build()
        http.newCall(r).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("TorBox ${resp.code}: ${body.take(120)}")
            return Json.parseToJsonElement(body).jsonObject
        }
    }

    fun myList(usenet: Boolean = false): List<TorBoxTorrent> {
        val arr = call(Request.Builder().url("$base/${if (usenet) "usenet" else "torrents"}/mylist"))["data"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.map { o ->
            TorBoxTorrent(
                o.lng("id"), o.str("name") ?: "", (o.str("hash") ?: "").lowercase(), o.lng("size"),
                (o["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map {
                    TorBoxFile(it.lng("id"), it.str("short_name") ?: it.str("name") ?: "", it.lng("size"))
                },
            )
        }
    }

    /** Searches your cloud (torrents + usenet), then TorBox's torrent and usenet search. Errors are returned, not hidden. */
    fun search(query: String): Pair<List<SourceResult>, List<String>> {
        val errors = mutableListOf<String>()
        val words = query.lowercase().split(' ').filter { it.length > 1 }
        val mine = listOf(false, true).flatMap { nzb ->
            runCatching { myList(nzb) }.getOrElse { errors += "TorBox cloud: ${it.message}"; emptyList() }
                .filter { t -> words.all { t.name.lowercase().contains(it) } }
                .mapNotNull { t ->
                    val meta = t.name + " " + t.files.joinToString(" ") { it.name }
                    if (!SourceParser.isAudiobook(meta)) return@mapNotNull null
                    SourceResult("cloud:${if (nzb) "u" else "t"}${t.id}", Provider.TORBOX_CLOUD, t.name, t.size, SourceParser.parseCodec(meta),
                        SourceParser.parseBitrate(t.name), 0, true, t.hash, null, t.id, nzb)
                }
        }
        val found = listOf(false, true).flatMap { nzb ->
            runCatching {
                val path = if (nzb) "usenet/search" else "torrents/search"
                val o = call(Request.Builder().url("$searchBase/$path/${enc("$query audiobook")}?metadata=false&check_cache=true&check_owned=true"))
                val data = o["data"] as? JsonObject
                val arr = (data?.get(if (nzb) "nzbs" else "torrents") as? JsonArray) ?: JsonArray(emptyList())
                arr.mapNotNull { it as? JsonObject }.mapNotNull { t ->
                    val title = t.str("raw_title") ?: t.str("title") ?: return@mapNotNull null
                    val hash = t.str("hash")?.lowercase() ?: return@mapNotNull null
                    val link = if (nzb) (t.str("nzb") ?: t.str("link") ?: return@mapNotNull null) else null
                    if (!SourceParser.isAudiobook(title)) return@mapNotNull null
                    SourceResult("${if (nzb) "us" else "ts"}:$hash", Provider.TORBOX_SEARCH, title, t.lng("size"), SourceParser.parseCodec(title),
                        SourceParser.parseBitrate(title), t.lng("last_known_seeders").toInt(),
                        (t["cached"] as? JsonPrimitive)?.booleanOrNull ?: false, hash, null, null, nzb, link)
                }
            }.getOrElse { errors += "TorBox ${if (nzb) "usenet" else "torrent"} search: ${it.message}"; emptyList() }
        }
        val mineHashes = mine.mapNotNull { it.hash }.toSet()
        return (mine + found.filter { it.hash !in mineHashes }) to errors
    }

    /** Adds the torrent/NZB to the account if needed and returns it with its file list. */
    fun ensureInAccount(hash: String, title: String, usenet: Boolean = false, nzbUrl: String? = null): TorBoxTorrent {
        val existing = myList(usenet).firstOrNull { it.hash == hash.lowercase() }
        if (existing?.files?.isNotEmpty() == true) return existing
        if (existing == null) {
            if (usenet) {
                val form = FormBody.Builder().add("link", nzbUrl ?: error("No NZB link for this result")).add("name", title).build()
                call(Request.Builder().url("$base/usenet/createusenetdownload").post(form))
            } else {
                val form = FormBody.Builder().add("magnet", "magnet:?xt=urn:btih:$hash&dn=${enc(title)}").add("seed", "3").build()
                call(Request.Builder().url("$base/torrents/createtorrent").post(form))
            }
        }
        repeat(10) {
            myList(usenet).firstOrNull { it.hash == hash.lowercase() && it.files.isNotEmpty() }?.let { return it }
            Thread.sleep(1500)
        }
        error("TorBox is still preparing this ${if (usenet) "usenet download" else "torrent"}. Try again in a minute.")
    }

    /** Turns a torbox://id/fileId[?u=1] track address into a short-lived CDN link. */
    fun resolve(uri: android.net.Uri): String =
        requestLink(uri.host!!.toLong(), uri.pathSegments[0].toLong(), uri.getQueryParameter("u") == "1")

    /** Short-lived direct CDN link for one file. */
    fun requestLink(id: Long, fileId: Long, usenet: Boolean = false): String {
        val url = if (usenet) "$base/usenet/requestdl?token=${enc(key())}&usenet_id=$id&file_id=$fileId&zip_link=false"
                  else "$base/torrents/requestdl?token=${enc(key())}&torrent_id=$id&file_id=$fileId&zip_link=false"
        return call(Request.Builder().url(url))["data"]?.jsonPrimitive?.contentOrNull ?: error("No download link")
    }
}
