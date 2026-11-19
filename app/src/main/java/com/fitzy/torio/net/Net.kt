package com.fitzy.torio.net

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.net.URLEncoder
import java.util.Locale

enum class Provider { TORBOX_CLOUD, TORBOX_SEARCH, AIOSTREAMS }

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

    fun myList(): List<TorBoxTorrent> {
        val arr = call(Request.Builder().url("$base/torrents/mylist"))["data"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.map { o ->
            TorBoxTorrent(
                o.lng("id"), o.str("name") ?: "", (o.str("hash") ?: "").lowercase(), o.lng("size"),
                (o["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map {
                    TorBoxFile(it.lng("id"), it.str("short_name") ?: it.str("name") ?: "", it.lng("size"))
                },
            )
        }
    }

    /** Searches the user's own cloud first, then TorBox's search index. */
    fun search(query: String): List<SourceResult> {
        val q = query.lowercase()
        val mine = runCatching { myList() }.getOrDefault(emptyList())
            .filter { t -> q.split(' ').filter { it.length > 1 }.all { t.name.lowercase().contains(it) } }
            .mapNotNull { t ->
                val meta = t.name + " " + t.files.joinToString(" ") { it.name }
                if (!SourceParser.isAudiobook(meta)) return@mapNotNull null
                SourceResult("cloud:${t.id}", Provider.TORBOX_CLOUD, t.name, t.size, SourceParser.parseCodec(meta),
                    SourceParser.parseBitrate(t.name), 0, true, t.hash, null, t.id)
            }
        val foundResult = runCatching {
            val o = call(Request.Builder().url("$searchBase/torrents/search/${enc("$query audiobook")}?metadata=false&check_cache=true&check_owned=true"))
            val arr = (o["data"] as? JsonObject)?.get("torrents") as? JsonArray ?: JsonArray(emptyList())
            arr.mapNotNull { it as? JsonObject }.mapNotNull { t ->
                val title = t.str("raw_title") ?: t.str("title") ?: return@mapNotNull null
                val hash = t.str("hash")?.lowercase() ?: return@mapNotNull null
                if (!SourceParser.isAudiobook(title)) return@mapNotNull null
                SourceResult("ts:$hash", Provider.TORBOX_SEARCH, title, t.lng("size"), SourceParser.parseCodec(title),
                    SourceParser.parseBitrate(title), t.lng("last_known_seeders").toInt(),
                    (t["cached"] as? JsonPrimitive)?.booleanOrNull ?: false, hash)
            }
        }
        if (mine.isEmpty()) foundResult.exceptionOrNull()?.let { throw it }   // show the real error instead of "No results"
        val found = foundResult.getOrDefault(emptyList())
        val mineHashes = mine.mapNotNull { it.hash }.toSet()
        return mine + found.filter { it.hash !in mineHashes }
    }

    /** Adds the magnet to the account if needed and returns the torrent with its file list. */
    fun ensureInAccount(hash: String, title: String): TorBoxTorrent {
        val existing = myList().firstOrNull { it.hash == hash.lowercase() }
        if (existing?.files?.isNotEmpty() == true) return existing
        if (existing == null) {
            val form = FormBody.Builder().add("magnet", "magnet:?xt=urn:btih:$hash&dn=${enc(title)}").add("seed", "3").build()
            call(Request.Builder().url("$base/torrents/createtorrent").post(form))
        }
        repeat(10) {
            myList().firstOrNull { it.hash == hash.lowercase() && it.files.isNotEmpty() }?.let { return it }
            Thread.sleep(1500)
        }
        error("TorBox is still preparing this torrent. Try again in a minute.")
    }

    /** Short-lived direct CDN link for one file. */
    fun requestLink(torrentId: Long, fileId: Long): String {
        val url = "$base/torrents/requestdl?token=${enc(key())}&torrent_id=$torrentId&file_id=$fileId&zip_link=false"
        return call(Request.Builder().url(url))["data"]?.jsonPrimitive?.contentOrNull ?: error("No download link")
    }
}

class AioStreamsClient(private val http: OkHttpClient, private val manifestUrl: () -> String) {
    private fun get(url: String): JsonObject =
        http.newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) error("AIOStreams ${it.code}")
            Json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject
        }

    /** Stremio protocol: search catalogs from the manifest, then fetch streams for the top hits. */
    fun search(query: String): List<SourceResult> {
        val mUrl = manifestUrl().ifBlank { return emptyList() }
        val base = mUrl.removeSuffix("/manifest.json").trimEnd('/')
        val catalogs = (get("$base/manifest.json")["catalogs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { c -> (c["extra"] as? JsonArray).orEmpty().any { (it as? JsonObject)?.str("name") == "search" } }
        val metas = catalogs.take(3).flatMap { c ->
            runCatching {
                val m = get("$base/catalog/${c.str("type")}/${c.str("id")}/search=${enc(query)}.json")["metas"] as? JsonArray
                m.orEmpty().mapNotNull { it as? JsonObject }.take(3).map { c.str("type")!! to it.str("id")!! }
            }.getOrDefault(emptyList())
        }.distinct().take(5)
        return metas.flatMap { (type, id) ->
            runCatching {
                (get("$base/stream/$type/${enc(id)}.json")["streams"] as? JsonArray).orEmpty()
                    .mapNotNull { it as? JsonObject }.mapIndexedNotNull { i, s -> toResult(id, i, s) }
            }.getOrDefault(emptyList())
        }
    }

    private fun toResult(id: String, i: Int, s: JsonObject): SourceResult? {
        val text = listOfNotNull(s.str("name"), s.str("title"), s.str("description")).joinToString(" ")
        val hints = s["behaviorHints"] as? JsonObject
        val size = hints?.lng("videoSize")?.takeIf { it > 0 } ?: SourceParser.parseSize(text)
        val url = s.str("url")
        val hash = s.str("infoHash")?.lowercase()
        if (url == null && hash == null) return null
        if (!SourceParser.isAudiobook(text + " " + (hints?.str("filename") ?: ""))) return null
        val title = hints?.str("filename") ?: text.lineSequence().firstOrNull { it.isNotBlank() } ?: id
        return SourceResult("aio:$id:$i", Provider.AIOSTREAMS, title, size, SourceParser.parseCodec(text),
            SourceParser.parseBitrate(text), SourceParser.parseSeeders(text),
            text.contains("⚡") || text.contains("cached", true), hash, url)
    }
}
