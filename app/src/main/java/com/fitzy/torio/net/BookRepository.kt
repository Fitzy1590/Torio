package com.fitzy.torio.net

import com.fitzy.torio.AppContainer
import com.fitzy.torio.data.BookEntity
import com.fitzy.torio.data.TrackEntity

/** Turns a picked source into a saved book with playable tracks. Blocking: call from Dispatchers.IO. */
class BookRepository(private val c: AppContainer) {
    private val torbox = TorBoxApiClient(c.http) { c.settings.torboxKey }
    private val aio = AioStreamsClient(c.http) { c.settings.aioUrl }

    fun search(q: String): Pair<List<SourceResult>, List<String>> {
        val errors = mutableListOf<String>()
        val tb = if (c.settings.torboxKey.isBlank()) { errors += "Add your TorBox key in Settings"; emptyList() }
                 else runCatching { torbox.search(q) }.getOrElse { errors += it.message ?: "TorBox failed"; emptyList() }
        val ai = if (c.settings.aioUrl.isBlank()) emptyList()
                 else runCatching { aio.search(q) }.getOrElse { errors += it.message ?: "AIOStreams failed"; emptyList() }
        val all = (tb + ai).filter { it.sizeBytes == 0L || it.sizeBytes > 5L shl 20 }
        return all.sortedWith(compareByDescending<SourceResult> { it.health }.thenByDescending { it.cached }.thenByDescending { it.seeders }) to errors
    }

    /** Creates the book + track rows and returns its id. */
    suspend fun saveSource(s: SourceResult): String {
        val dao = c.db.bookDao()
        val id = s.hash ?: s.id
        val tracks: List<TrackEntity> = when {
            s.directUrl != null -> listOf(TrackEntity(id, 0, s.title, s.directUrl))
            else -> {
                val t = torbox.ensureInAccount(s.hash!!, s.title)
                val files = t.files.filter { isAudioFile(it.name) }.sortedWith(naturalOrder())
                files.mapIndexed { i, f -> TrackEntity(id, i, f.name.substringBeforeLast('.'), "torbox://${t.id}/${f.id}") }
            }
        }
        if (tracks.isEmpty()) error("No audio files found in this source")
        dao.upsertBook(BookEntity(id, cleanTitle(s.title), "", null, s.codec, s.bitrateKbps, s.sizeBytes))
        dao.upsertTracks(tracks)
        return id
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
