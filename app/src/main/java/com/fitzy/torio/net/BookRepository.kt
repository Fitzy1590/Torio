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
                val t = torbox.ensureInAccount(s.hash!!, s.title, s.usenet, s.nzbUrl, s.torboxId)
                val files = t.files.filter { isAudioFile(it.name) }.sortedWith(naturalOrder())
                if (files.isEmpty()) error("No audio files found in this source (${t.files.size} files: ${t.files.take(3).joinToString { it.name.takeLast(30) }})")
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

    /** Fills in a missing cover/author for an already-saved book. Returns the cover url if one was found. */
    suspend fun backfillCover(b: com.fitzy.torio.data.BookEntity): String? {
        if (b.coverUrl != null) return null
        val (author, cover) = lookupCover(b.title)
        if (cover == null && (author.isBlank() || b.author.isNotBlank())) return null
        c.db.bookDao().setCover(b.id, cover, author)
        return cover
    }

    /** Best-effort author + cover (title only is sent): iTunes audiobooks first, then Open Library. Returns "" / null on failure. */
    private fun lookupCover(title: String): Pair<String, String?> {
        val parts = title.replace(Regex("""[\[(].*?[\])]"""), " ")
            .replace(Regex("""(?i)\b(unabridged|abridged|audiobook|fully chaptered|chapterized|chaptered|easier listen|m4b|mp3|flac|\d+\s?kbps)\b"""), " ")
            .split(Regex("""\s+-\s+""")).map { it.replace(Regex("""\s+"""), " ").trim(' ', '-') }.filter { it.isNotBlank() }
        val queries = listOfNotNull(parts.joinToString(" "), parts.drop(1).firstOrNull(), parts.firstOrNull()).distinct().filter { it.isNotBlank() }
        var author = ""
        for (q in queries) itunes(q)?.let { (a, cv) -> if (author.isBlank()) author = a; if (cv != null) return author to cv }
        for (q in queries) openLibrary(q)?.let { (a, cv) -> if (author.isBlank()) author = a; if (cv != null) return author to cv }
        return author to null
    }

    private fun getJson(url: String): JsonObject? = runCatching {
        c.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) null else Json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject
        }
    }.getOrNull()

    private fun itunes(q: String): Pair<String, String?>? {
        val url = "https://itunes.apple.com/search?media=audiobook&limit=1&term=" + java.net.URLEncoder.encode(q, "UTF-8")
        val r = getJson(url)?.get("results")?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val art = r["artworkUrl100"]?.jsonPrimitive?.contentOrNull?.replace("100x100bb", "600x600bb")
        return r["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty() to art
    }

    private fun openLibrary(q: String): Pair<String, String?>? {
        val url = "https://openlibrary.org/search.json?limit=1&fields=cover_i,author_name&q=" + java.net.URLEncoder.encode(q, "UTF-8")
        val doc = getJson(url)?.get("docs")?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val cover = doc["cover_i"]?.jsonPrimitive?.longOrNull?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" }
        return doc["author_name"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty() to cover
    }

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
