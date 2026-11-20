package com.fitzy.torio.net

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

    private fun call(req: Request.Builder): JsonObject {
        val r = req.header("Authorization", "Bearer ${key()}").build()
        http.newCall(r).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("TorBox ${resp.code}: ${body.take(120)}")
            return Json.parseToJsonElement(body).jsonObject
        }
    }

    fun myList(usenet: Boolean = false, id: Long? = null, fresh: Boolean = true): List<TorBoxTorrent> {
        val q = listOfNotNull(id?.let { "id=$it" }, if (fresh) "bypass_cache=true" else null).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        val data = call(Request.Builder().url("$base/${if (usenet) "usenet" else "torrents"}/mylist$q"))["data"]
        val arr = when (data) { is JsonArray -> data; is JsonObject -> JsonArray(listOf(data)); else -> return emptyList() }
        return arr.mapNotNull { it as? JsonObject }.map { o ->
            TorBoxTorrent(
                o.lng("id"), o.str("name") ?: "", (o.str("hash") ?: "").lowercase(), o.lng("size"),
                (o["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map {
                    TorBoxFile(it.lng("id"), it.str("short_name") ?: it.str("name") ?: "", it.lng("size"))
                },
            )
        }
    }

    /** Searches your own cloud (torrents + usenet). Errors are returned, not hidden. */
    fun searchCloud(query: String): Pair<List<SourceResult>, List<String>> {
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
        return mine to errors
    }

    /** Which of these torrent hashes TorBox already has cached (instant to stream). */
    fun cachedHashes(hashes: List<String>): Set<String> {
        val out = mutableSetOf<String>()
        hashes.chunked(40).forEach { chunk ->
            val o = call(Request.Builder().url("$base/torrents/checkcached?hash=${chunk.joinToString(",")}&format=list"))
            when (val d = o["data"]) {
                is JsonArray -> d.mapNotNullTo(out) { e -> ((e as? JsonObject)?.str("hash") ?: (e as? JsonPrimitive)?.contentOrNull)?.lowercase() }
                is JsonObject -> d.keys.mapTo(out) { it.lowercase() }
                else -> {}
            }
        }
        return out
    }

    /** Adds the torrent/NZB to the account if needed and returns it with its file list. */
    fun ensureInAccount(hash: String, title: String, usenet: Boolean = false, nzbUrl: String? = null, knownId: Long? = null): TorBoxTorrent {
        fun find(id: Long?) = myList(usenet, fresh = true).firstOrNull { if (id != null) it.id == id else it.hash == hash.lowercase() }
        var id = knownId
        val existing = find(id)
        if (existing?.files?.isNotEmpty() == true) return existing
        if (existing == null) {
            val resp = if (usenet) {
                val form = FormBody.Builder().add("link", nzbUrl ?: error("No NZB link for this result")).add("name", title).build()
                call(Request.Builder().url("$base/usenet/createusenetdownload").post(form))
            } else {
                val form = FormBody.Builder().add("magnet", "magnet:?xt=urn:btih:$hash&dn=${enc(title)}").add("seed", "3").build()
                call(Request.Builder().url("$base/torrents/createtorrent").post(form))
            }
            val d = resp["data"] as? JsonObject
            id = d?.lng("torrent_id")?.takeIf { it > 0 } ?: d?.lng("usenetdownload_id")?.takeIf { it > 0 } ?: id
        }
        repeat(10) {
            find(id)?.takeIf { it.files.isNotEmpty() }?.let { return it }
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

/** Free public torrent indexes (no key). Each source fails on its own and reports its error. */
class IndexSearchClient(private val http: OkHttpClient) {
    fun search(query: String): Pair<List<SourceResult>, List<String>> {
        val errors = mutableListOf<String>()
        val a = runCatching { apibay(query) }.getOrElse { errors += "Pirate Bay index: ${it.message}"; emptyList() }
        val b = runCatching { knaben(query) }.getOrElse { errors += "Knaben: ${it.message}"; emptyList() }
        return (a + b).distinctBy { it.hash } to errors
    }

    private fun get(req: Request): JsonElement = http.newCall(req).execute().use {
        if (!it.isSuccessful) error("HTTP ${it.code}")
        Json.parseToJsonElement(it.body?.string().orEmpty())
    }

    private fun result(id: String, title: String, hash: String, size: Long, seeders: Int): SourceResult? {
        // the audiobook-only filter still applies (drops video); indexes list these as audiobooks already, so allow bare titles
        if (!SourceParser.isAudiobook("$title audiobook")) return null
        return SourceResult("$id:$hash", Provider.TORBOX_SEARCH, title, size, SourceParser.parseCodec(title),
            SourceParser.parseBitrate(title), seeders, false, hash)
    }

    /** Pirate Bay's public JSON API, category 102 = audio books. */
    private fun apibay(query: String): List<SourceResult> {
        val arr = get(Request.Builder().url("https://apibay.org/q.php?q=${enc(query)}&cat=102").build()) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val hash = o.str("info_hash")?.lowercase()?.takeIf { h -> h.isNotBlank() && h.any { it != '0' } } ?: return@mapNotNull null
            result("tpb", o.str("name") ?: return@mapNotNull null, hash, o.lng("size"), o.lng("seeders").toInt())
        }
    }

    /** Knaben's open search API (aggregates many trackers). */
    private fun knaben(query: String): List<SourceResult> {
        val body = buildJsonObject {
            put("search_type", "100%"); put("search_field", "title"); put("query", "$query audiobook")
            put("order_by", "seeders"); put("order_direction", "desc"); put("size", 50); put("hide_unsafe", true)
        }.toString().toRequestBody("application/json".toMediaType())
        val hits = ((get(Request.Builder().url("https://api.knaben.org/v1").post(body).build()) as? JsonObject)?.get("hits") as? JsonArray) ?: return emptyList()
        return hits.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val hash = o.str("hash")?.lowercase()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            result("kn", o.str("title") ?: return@mapNotNull null, hash, o.lng("bytes"), o.lng("seeders").toInt())
        }
    }
}
