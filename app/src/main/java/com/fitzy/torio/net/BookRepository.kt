package com.fitzy.torio.net

import com.fitzy.torio.AppContainer
import com.fitzy.torio.data.BookEntity
import com.fitzy.torio.data.TrackEntity
import kotlinx.serialization.json.*

/** Turns a picked source into a saved book with playable tracks. Blocking: call from Dispatchers.IO. */
class BookRepository(private val c: AppContainer) {
    private val torbox = TorBoxApiClient(c.http) { c.settings.torboxKey }
    private val index = IndexSearchClient(c.http)

    fun search(q: String): Pair<List<SourceResult>, List<String>> {
        if (c.settings.torboxKey.isBlank()) return emptyList<SourceResult>() to listOf("Add your TorBox key in Settings")
        val (cloud, errors) = torbox.searchCloud(q).let { it.first to it.second.toMutableList() }
        val (found, idxErrors) = index.search(q)
        errors += idxErrors
        val cached = runCatching { torbox.cachedHashes(found.mapNotNull { it.hash }.distinct()) }
            .getOrElse { errors += "TorBox cache check: ${it.message}"; emptySet() }
        val mine = cloud.mapNotNull { it.hash }.toSet()
        val fresh = found.filter { it.hash !in mine }.map { if (it.hash in cached) it.copy(cached = true) else it }
        val all = (cloud + fresh).filter { it.sizeBytes == 0L || it.sizeBytes > 5L shl 20 }
        return all.sortedWith(compareByDescending<SourceResult> { it.quality }.thenByDescending { it.health }.thenByDescending { it.cached }.thenByDescending { it.seeders }) to errors
    }

    /** Creates the book + track rows and returns its id. */
    suspend fun saveSource(s: SourceResult): String {
        val dao = c.db.bookDao()
        val id = s.hash ?: s.id
        val tracks: List<TrackEntity> = when {
            s.directUrl != null -> listOf(TrackEntity(id, 0, s.title, s.directUrl))
            else -> {
                val t = torbox.ensureInAccount(s.hash!!, s.title, s.usenet, s.nzbUrl)
                val files = t.files.filter { isAudioFile(it.name) }.sortedWith(naturalOrder())
                files.mapIndexed { i, f -> TrackEntity(id, i, f.name.substringBeforeLast('.'), "torbox://${t.id}/${f.id}${if (s.usenet) "?u=1" else ""}") }
            }
        }
        if (tracks.isEmpty()) error("No audio files found in this source")
        val title = cleanTitle(s.title)
        val (author, cover) = lookupCover(title)
        dao.upsertBook(BookEntity(id, title, author, cover, s.codec, s.bitrateKbps, s.sizeBytes))
        dao.upsertTracks(tracks)
        return id
    }

    /** Best-effort author + cover from Open Library (title only is sent). Returns "" / null on any failure. */
    private fun lookupCover(title: String): Pair<String, String?> = runCatching {
        val q = title.replace(Regex("""[\[(].*?[\])]"""), " ")
            .replace(Regex("""(?i)\b(unabridged|abridged|audiobook|fully chaptered|m4b|mp3|flac)\b"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        val url = "https://openlibrary.org/search.json?limit=1&fields=cover_i,author_name&q=" + java.net.URLEncoder.encode(q, "UTF-8")
        val doc = c.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) return@runCatching "" to null
            kotlinx.serialization.json.Json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject["docs"]?.jsonArray?.firstOrNull()?.jsonObject
        }
        val author = doc?.get("author_name")?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty()
        val cover = doc?.get("cover_i")?.jsonPrimitive?.longOrNull?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" }
        author to cover
    }.getOrDefault("" to null)

    private fun naturalOrder() = Comparator<TorBoxFile> { a, b -> collate(a.name, b.name) }
    private fun collate(a: String, b: String): Int {
        val ra = Regex("""\d+|\D+""").findAll(a).map { it.value }.toList()
        val rb = Regex("""\d+|\D+""").findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y, true)
            if (c != 0) return c
        }
        return ra.size - rb.size
    }
    private fun cleanTitle(t: String) = t.replace(Regex("""[._]"""), " ").replace(Regex("""\s+"""), " ").trim()
}
