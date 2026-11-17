package com.fitzy.torio.net

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import org.xmlpull.v1.XmlPullParser
import java.net.URLEncoder
import java.util.Locale

enum class Provider { TORBOX_CLOUD, TORBOX_SEARCH, TORBOX_USENET, TORZNAB, AUDIOBOOKBAY, AIOSTREAMS }

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
    private val ebook = Regex("""\b(epub|pdf|mobi|azw3?|ebook|e-book|cbz|cbr|comics?|magazines?|software|keygen|iso)\b""", RegexOption.IGNORE_CASE)

    /** [lenient] (audiobook-focused sources) also accepts titles with no codec/wording, unless they look like ebooks. */
    fun isAudiobook(text: String, lenient: Boolean = false): Boolean = !video.containsMatchIn(text) &&
        (parseCodec(text) != "Audio" || book.containsMatchIn(text) || (lenient && !ebook.containsMatchIn(text)))

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

    fun myList(): List<TorBoxTorrent> = list("torrents/mylist")

    fun usenetList(): List<TorBoxTorrent> = list("usenet/mylist")

    private fun list(path: String): List<TorBoxTorrent> {
        val arr = call(Request.Builder().url("$base/$path"))["data"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.map { o ->
            TorBoxTorrent(
                o.lng("id"), o.str("name") ?: "", (o.str("hash") ?: "").lowercase(), o.lng("size"),
                (o["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map {
                    TorBoxFile(it.lng("id"), it.str("short_name") ?: it.str("name") ?: "", it.lng("size"))
                },
            )
        }
    }

    /** Searches the user's own TorBox cloud (TorBox's public search host no longer exists). */
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
        return mine
    }

    /** Hashes (lowercase) that TorBox already has cached, so they play instantly. */
    fun cachedHashes(hashes: List<String>): Set<String> = hashes.chunked(50).flatMap { chunk ->
        val d = call(Request.Builder().url("$base/torrents/checkcached?hash=${chunk.joinToString(",")}&format=object&list_files=false"))["data"]
        when (d) {
            is JsonObject -> d.keys.map { it.lowercase() }
            is JsonArray -> d.mapNotNull { (it as? JsonObject)?.str("hash")?.lowercase() }
            else -> emptyList()
        }
    }.toSet()

    /** Adds the NZB to the account if needed and returns the download with its file list. */
    fun ensureUsenetInAccount(nzbUrl: String, title: String): TorBoxTorrent {
        usenetList().firstOrNull { it.name == title && it.files.isNotEmpty() }?.let { return it }
        val form = FormBody.Builder().add("link", nzbUrl).add("name", title).build()
        val id = call(Request.Builder().url("$base/usenet/createusenetdownload").post(form))["data"]
            ?.jsonObject?.lng("usenetdownload_id") ?: 0L
        repeat(10) {
            usenetList().firstOrNull { (id != 0L && it.id == id || it.name == title) && it.files.isNotEmpty() }?.let { return it }
            Thread.sleep(1500)
        }
        error("TorBox is still fetching this Usenet download. Try again in a minute.")
    }

    /** Adds the magnet to the account if needed and returns the torrent with its file list. */
    fun ensureInAccount(hash: String, title: String): TorBoxTorrent {
        myList().firstOrNull { it.hash == hash.lowercase() }?.let { return it }
        val form = FormBody.Builder().add("magnet", "magnet:?xt=urn:btih:$hash&dn=${enc(title)}").add("seed", "3").build()
        call(Request.Builder().url("$base/torrents/createtorrent").post(form))
        repeat(10) {
            myList().firstOrNull { it.hash == hash.lowercase() && it.files.isNotEmpty() }?.let { return it }
            Thread.sleep(1500)
        }
        error("TorBox is still preparing this torrent. Try again in a minute.")
    }

    /** Short-lived direct CDN link for one file. */
    fun requestLink(torrentId: Long, fileId: Long, usenet: Boolean = false): String {
        val url = if (usenet) "$base/usenet/requestdl?token=${enc(key())}&usenet_id=$torrentId&file_id=$fileId&zip_link=false"
                  else "$base/torrents/requestdl?token=${enc(key())}&torrent_id=$torrentId&file_id=$fileId&zip_link=false"
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
        if (catalogs.isEmpty()) error("AIOStreams manifest has no search catalog")
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

/** Torznab (Prowlarr / Jackett) search restricted to the audiobook category. Only results with an info hash are usable. */
class TorznabClient(private val http: OkHttpClient, private val url: () -> String, private val key: () -> String) {
    private val btih = Regex("""btih:([0-9a-fA-F]{40})""")

    fun search(query: String): List<SourceResult> {
        val base = url().toHttpUrlOrNull() ?: error("Torznab URL is not valid")
        val u = base.newBuilder().setQueryParameter("t", "search").setQueryParameter("q", query)
            .setQueryParameter("cat", "3030").setQueryParameter("extended", "1")
            .apply { key().takeIf { it.isNotBlank() }?.let { setQueryParameter("apikey", it) } }.build()
        val body = http.newCall(Request.Builder().url(u).build()).execute().use {
            if (!it.isSuccessful) error("Torznab ${it.code}")
            it.body?.string().orEmpty()
        }
        return parse(body)
    }

    private fun parse(xml: String): List<SourceResult> {
        val p = android.util.Xml.newPullParser().apply { setInput(java.io.StringReader(xml)) }
        val out = mutableListOf<SourceResult>()
        var inItem = false
        var title = ""; var size = 0L; var seeders = 0; var link: String? = null; var magnet: String? = null; var hash: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val n = p.name
                when {
                    n == "error" -> error("Torznab: ${p.getAttributeValue(null, "description") ?: "error"}")
                    n == "item" -> { inItem = true; title = ""; size = 0L; seeders = 0; link = null; magnet = null; hash = null }
                    !inItem -> {}
                    n == "title" -> title = p.nextText()
                    n == "size" -> size = p.nextText().toLongOrNull() ?: 0L
                    n == "link" -> link = p.nextText()
                    n == "enclosure" -> {
                        if (size == 0L) size = p.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L
                        if (link == null) link = p.getAttributeValue(null, "url")
                    }
                    n.endsWith(":attr") -> {
                        val v = p.getAttributeValue(null, "value") ?: ""
                        when (p.getAttributeValue(null, "name")) {
                            "infohash" -> hash = v.lowercase()
                            "seeders" -> seeders = v.toIntOrNull() ?: 0
                            "size" -> if (size == 0L) size = v.toLongOrNull() ?: 0L
                            "magneturl" -> magnet = v
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "item" && inItem) {
                inItem = false
                val h = hash?.takeIf { it.length == 40 } ?: btih.find(magnet ?: link ?: "")?.groupValues?.get(1)?.lowercase()
                if (h != null && title.isNotBlank() && SourceParser.isAudiobook(title, lenient = true)) {
                    out += SourceResult("tz:$h", Provider.TORZNAB, title, size, SourceParser.parseCodec(title),
                        SourceParser.parseBitrate(title), seeders, false, h)
                }
            }
            ev = p.next()
        }
        return out
    }
}

/** Scrapes AudiobookBay search + detail pages for info hashes. [cookies] can supply the Browse tab's login cookies. */
class AudiobookBayClient(private val http: OkHttpClient, private val baseUrl: () -> String, private val cookies: (String) -> String?) {
    private val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val post = Regex("""<h2[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val hashRe = Regex("""info\s*hash:?\s*(?:</[^>]+>\s*)*(?:<[^>]+>\s*)*([0-9a-fA-F]{40})""", RegexOption.IGNORE_CASE)
    private val btihRe = Regex("""btih:([0-9a-fA-F]{40})""")
    private val sizeRe = Regex("""file\s*size:?\s*(?:</[^>]+>\s*)*(?:<[^>]+>\s*)*([\d.,]+\s*[KMGT]B)""", RegexOption.IGNORE_CASE)
    private val bitrateRe = Regex("""bitrate:?\s*(?:</[^>]+>\s*)*(?:<[^>]+>\s*)*(\d{2,4})""", RegexOption.IGNORE_CASE)

    private fun fetch(url: String): String {
        val b = Request.Builder().url(url).header("User-Agent", ua)
        cookies(url)?.takeIf { it.isNotBlank() }?.let { b.header("Cookie", it) }
        http.newCall(b.build()).execute().use {
            if (!it.isSuccessful) error("AudiobookBay ${it.code}")
            return it.body?.string().orEmpty()
        }
    }

    private fun text(html: String) = android.text.Html.fromHtml(html, 0).toString().replace(Regex("""\s+"""), " ").trim()

    fun search(query: String): List<SourceResult> {
        val base = baseUrl().trimEnd('/').ifBlank { return emptyList() }
        val html = fetch("$base/?s=${enc(query)}")
        val hits = post.findAll(html).map { it.groupValues[1] to text(it.groupValues[2]) }
            .filter { it.second.isNotBlank() }.distinctBy { it.first }.take(8).toList()
        if (hits.isEmpty()) {
            if (html.contains("cloudflare", true) || html.contains("just a moment", true)) error("AudiobookBay blocked the request (Cloudflare). Open it once in the Browse tab.")
            return emptyList()
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            return pool.invokeAll(hits.map { (href, title) -> java.util.concurrent.Callable { runCatching { detail(base, href, title) }.getOrNull() } })
                .mapNotNull { it.get() }
        } finally { pool.shutdown() }
    }

    private fun detail(base: String, href: String, title: String): SourceResult? {
        val url = if (href.startsWith("http")) href else base + (if (href.startsWith("/")) href else "/$href")
        val html = fetch(url)
        val hash = (hashRe.find(html)?.groupValues?.get(1) ?: btihRe.find(html)?.groupValues?.get(1))?.lowercase() ?: return null
        val size = sizeRe.find(html)?.groupValues?.get(1)?.let { SourceParser.parseSize(it) } ?: 0L
        val meta = title + " " + text(html.substringAfter("Format", "").take(600))
        val bitrate = bitrateRe.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: SourceParser.parseBitrate(title)
        return SourceResult("abb:$hash", Provider.AUDIOBOOKBAY, title, size, SourceParser.parseCodec(meta), bitrate, 0, false, hash)
    }
}
