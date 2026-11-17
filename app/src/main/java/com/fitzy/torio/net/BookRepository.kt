package com.fitzy.torio.net

import com.fitzy.torio.AppContainer
import com.fitzy.torio.data.BookEntity
import com.fitzy.torio.data.TrackEntity

/** Turns a picked source into a saved book with playable tracks. Blocking: call from Dispatchers.IO. */
class BookRepository(private val c: AppContainer) {
    private val torbox = TorBoxApiClient(c.http) { c.settings.torboxKey }
    private val aio = AioStreamsClient(c.http) { c.settings.aioUrl }
    private val abb = AudiobookBayClient(c.http, { c.settings.abbUrl }) { url ->
        runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }.getOrNull()
    }
    private val torznab = TorznabClient(c.http, { c.settings.torznabUrl }, { c.settings.torznabKey })

    fun search(q: String): Pair<List<SourceResult>, List<String>> {
        val errors = mutableListOf<String>()
        val tb = if (c.settings.torboxKey.isBlank()) { errors += "Add your TorBox key in Settings"; emptyList() }
                 else runCatching { torbox.search(q) }.getOrElse { errors += it.message ?: "TorBox failed"; emptyList() }
        val ai = if (c.settings.aioUrl.isBlank()) emptyList()
                 else runCatching { aio.search(q) }.getOrElse { errors += it.message ?: "AIOStreams failed"; emptyList() }
        var usenetError: String? = null
        val nz = if (c.settings.torboxKey.isBlank()) emptyList()
                 else runCatching { torbox.searchUsenet(q) }.getOrElse { usenetError = "Usenet: ${it.message}"; emptyList() }
        val tz = if (c.settings.torznabUrl.isBlank()) emptyList()
                 else runCatching { torznab.search(q) }.getOrElse { errors += it.message ?: "Torznab failed"; emptyList() }
        var abbError: String? = null
        val ab = runCatching { abb.search(q) }.getOrElse { abbError = it.message ?: "AudiobookBay failed"; emptyList() }
        val all = markCached(tb + nz + tz + ab + ai).filter { it.sizeBytes == 0L || it.sizeBytes > 5L shl 20 }
        val sorted = all.sortedWith(compareByDescending<SourceResult> { it.cached || it.provider == Provider.TORBOX_CLOUD }.thenByDescending { it.quality }.thenByDescending { it.health }.thenByDescending { it.seeders })
            .distinctBy { it.hash ?: it.id }
        if (sorted.isEmpty()) { usenetError?.let { errors += it }; abbError?.let { errors += it } }
        return sorted to errors
    }

    /** Asks TorBox which found hashes are already cached, so they show as instant. */
    private fun markCached(list: List<SourceResult>): List<SourceResult> {
        if (c.settings.torboxKey.isBlank()) return list
        val hashes = list.filter { !it.cached && it.provider != Provider.TORBOX_CLOUD }.mapNotNull { it.hash }.distinct().take(100)
        if (hashes.isEmpty()) return list
        val hit = runCatching { torbox.cachedHashes(hashes) }.getOrDefault(emptySet())
        return list.map { r -> if (r.hash?.let { it in hit } == true) r.copy(cached = true) else r }
    }

    /** Creates the book + track rows and returns its id. */
    suspend fun saveSource(s: SourceResult): String {
        val dao = c.db.bookDao()
        val id = s.hash ?: s.id
        val tracks: List<TrackEntity> = when {
            s.directUrl != null -> listOf(TrackEntity(id, 0, s.title, s.directUrl))
            s.nzbUrl != null -> {
                val t = torbox.ensureUsenetInAccount(s.nzbUrl, s.title)
                val files = t.files.filter { isAudioFile(it.name) }.sortedWith(naturalOrder())
                files.mapIndexed { i, f -> TrackEntity(id, i, f.name.substringBeforeLast('.'), "tbusenet://${t.id}/${f.id}") }
            }
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
