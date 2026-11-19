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

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: com.fitzy.torio.net.UpdateInfo, val note: String? = null) : UpdateState
    data class Downloading(val info: com.fitzy.torio.net.UpdateInfo) : UpdateState
    data class Ready(val info: com.fitzy.torio.net.UpdateInfo, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

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

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update = _update.asStateFlow()

    /** [auto]: also download the new build straight away so the installer can open. */
    fun checkForUpdates(auto: Boolean) {
        if (_update.value is UpdateState.Checking || _update.value is UpdateState.Downloading) return
        viewModelScope.launch {
            _update.value = UpdateState.Checking
            try {
                val info = com.fitzy.torio.net.Updater.check(c.http, com.fitzy.torio.net.Updater.currentBuild(getApplication()))
                if (info == null) _update.value = UpdateState.UpToDate
                else if (auto) downloadUpdate(info) else _update.value = UpdateState.Available(info)
            } catch (e: Exception) {
                _update.value = UpdateState.Failed(e.message ?: "Update check failed")
            }
        }
    }

    fun downloadUpdate(info: com.fitzy.torio.net.UpdateInfo) {
        viewModelScope.launch {
            _update.value = UpdateState.Downloading(info)
            _update.value = try {
                UpdateState.Ready(info, com.fitzy.torio.net.Updater.download(getApplication(), c.http, info))
            } catch (e: Exception) { UpdateState.Failed(e.message ?: "Download failed") }
        }
    }

    fun installLaunched(info: com.fitzy.torio.net.UpdateInfo, opened: Boolean) {
        _update.value = UpdateState.Available(info, if (opened) null else "Allow Torio to install apps, then tap Update again.")
    }

    init {
        if (settings.autoUpdate) checkForUpdates(auto = true)
        viewModelScope.launch { player.autoResume() }
        // Books saved before cover lookup worked: try again in the background.
        viewModelScope.launch(Dispatchers.IO) {
            c.db.bookDao().books().first().filter { it.coverUrl == null }.forEach { b ->
                repo.backfillCover(b)?.let { player.updateCover(b.id, it) }
            }
        }
    }

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

    /** Opens the book unless it is already loaded in the player (then just resume without reloading). */
    fun play(b: BookEntity) = viewModelScope.launch {
        if (player.ui.value.bookId == b.id) { if (!player.ui.value.isPlaying) player.toggle() } else player.open(b.id, play = true)
    }

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
