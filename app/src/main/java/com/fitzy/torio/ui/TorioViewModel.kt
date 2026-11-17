package com.fitzy.torio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitzy.torio.TorioApp
import com.fitzy.torio.data.BookEntity
import com.fitzy.torio.data.ProgressEntity
import com.fitzy.torio.net.BookRepository
import com.fitzy.torio.net.Provider
import com.fitzy.torio.net.SourceParser
import com.fitzy.torio.net.SourceResult
import com.fitzy.torio.player.DownloadWorker
import com.fitzy.torio.player.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SearchState(
    val loading: Boolean = false,
    val results: List<SourceResult> = emptyList(),
    val messages: List<String> = emptyList(),
    val busyId: String? = null,
)

class TorioViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as TorioApp).container
    private val repo = BookRepository(c)
    val player = PlayerConnection(app, c)
    val settings = c.settings

    private val _search = MutableStateFlow(SearchState())
    val search = _search.asStateFlow()
    val books: StateFlow<List<BookEntity>> = c.db.bookDao().books().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val progress: StateFlow<Map<String, ProgressEntity>> =
        c.db.progressDao().all().map { l -> l.associateBy { it.bookId } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init { viewModelScope.launch { player.autoResume() } }

    fun runSearch(q: String) {
        if (q.isBlank()) return
        _search.value = SearchState(loading = true)
        viewModelScope.launch {
            val (r, errs) = withContext(Dispatchers.IO) { repo.search(q) }
            _search.value = SearchState(results = r, messages = errs.ifEmpty { if (r.isEmpty()) listOf("No results") else emptyList() })
        }
    }

    /** stream = play right away; otherwise queue an offline download. */
    fun pick(s: SourceResult, stream: Boolean, onStarted: () -> Unit) {
        _search.value = _search.value.copy(busyId = s.id, messages = emptyList())
        viewModelScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { repo.saveSource(s) }
                if (stream) { player.open(id, play = true); onStarted() }
                else { DownloadWorker.enqueue(getApplication(), id); _search.value = _search.value.copy(messages = listOf("Download started. Find it in Library.")) }
            } catch (e: Exception) {
                _search.value = _search.value.copy(messages = listOf(e.message ?: "Failed"))
            } finally { _search.value = _search.value.copy(busyId = null) }
        }
    }

    /** Magnet tapped in the Browse tab: save it as a book (adds it to TorBox) so it shows up in Library. */
    fun addMagnet(magnet: String, onDone: (String) -> Unit) {
        val hash = Regex("btih:([0-9a-zA-Z]{32,40})").find(magnet)?.groupValues?.get(1)?.lowercase()
        if (hash == null) { onDone("Not a valid magnet link"); return }
        val name = Regex("[?&]dn=([^&]+)").find(magnet)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: hash
        viewModelScope.launch {
            val msg = try {
                withContext(Dispatchers.IO) { repo.saveSource(SourceResult("mag:$hash", Provider.TORBOX_SEARCH, name, 0, SourceParser.parseCodec(name), 0, 0, false, hash)) }
                "Added to TorBox. Find it in Library."
            } catch (e: Exception) { e.message ?: "Failed" }
            onDone(msg)
        }
    }

    fun play(b: BookEntity) = viewModelScope.launch { player.open(b.id, play = true) }

    fun download(b: BookEntity) = DownloadWorker.enqueue(getApplication(), b.id)

    fun delete(b: BookEntity) = viewModelScope.launch(Dispatchers.IO) {
        c.db.bookDao().tracks(b.id).forEach { it.localPath?.let { p -> File(p).delete() } }
        c.db.bookDao().deleteTracks(b.id); c.db.bookDao().deleteBook(b.id); c.db.progressDao().delete(b.id)
    }

    fun markFinished(b: BookEntity, done: Boolean) = viewModelScope.launch {
        val p = progress.value[b.id] ?: ProgressEntity(b.id)
        c.sync.saveProgress(p.copy(finished = done, percent = if (done) 100 else p.percent, updatedAt = System.currentTimeMillis()))
    }
}
