package com.fitzy.torio.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.fitzy.torio.AppContainer
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import java.io.File

data class PlayerUi(
    val bookId: String? = null,
    val title: String = "",
    val author: String = "",
    val coverUrl: String? = null,
    val isPlaying: Boolean = false,
    val trackIndex: Int = 0,
    val trackTitles: List<String> = emptyList(),
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val dsp: Boolean = true,
)

/** UI-side handle to the service's player. All UI state flows from here. */
class PlayerConnection(private val ctx: Context, private val c: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private val _ui = MutableStateFlow(PlayerUi(speed = c.settings.speed, dsp = c.settings.dspOn))
    val ui = _ui.asStateFlow()
    private var meta = Triple("", "", null as String?)
    private var titles = emptyList<String>()
    private var bookId: String? = null

    init {
        val token = SessionToken(ctx, ComponentName(ctx, Media3AudioService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        f.addListener({
            val mc = f.get(); controller = mc
            mc.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
            })
            pending.forEach { it(mc) }; pending.clear(); refresh()
        }, MoreExecutors.directExecutor())
        scope.launch { while (isActive) { refresh(); delay(500) } }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: pending.add(block)
    }

    private fun refresh() {
        val mc = controller ?: return
        _ui.value = _ui.value.copy(
            bookId = bookId, title = meta.first, author = meta.second, coverUrl = meta.third,
            isPlaying = mc.isPlaying, trackIndex = mc.currentMediaItemIndex, trackTitles = titles,
            positionMs = mc.currentPosition.coerceAtLeast(0), durationMs = mc.duration.coerceAtLeast(0),
            speed = mc.playbackParameters.speed,
        )
    }

    /** Loads a saved book at its saved position. [play] false = ready but paused. */
    suspend fun open(id: String, play: Boolean, startTrack: Int? = null, startMs: Long? = null) {
        val dao = c.db.bookDao()
        val book = dao.book(id) ?: return
        val tracks = dao.tracks(id)
        if (tracks.isEmpty()) return
        val saved = c.sync.progressFor(id)
        val idx = startTrack ?: saved?.trackIndex?.takeIf { !saved.finished } ?: 0
        val pos = startMs ?: saved?.positionMs?.takeIf { !saved.finished } ?: 0
        bookId = id; meta = Triple(book.title, book.author, book.coverUrl); titles = tracks.map { it.title }
        val items = tracks.map { t ->
            val uri = t.localPath?.takeIf { File(it).exists() }?.let { Uri.fromFile(File(it)) } ?: Uri.parse(t.remoteUrl)
            MediaItem.Builder().setMediaId("$id#${t.idx}").setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(book.author).setAlbumTitle(book.title)
                    .setArtworkUri(book.coverUrl?.let(Uri::parse)).build()).build()
        }
        withController { mc ->
            mc.setMediaItems(items, idx.coerceIn(0, items.lastIndex), pos)
            mc.setPlaybackParameters(PlaybackParameters(c.settings.speed, 1f))
            mc.prepare(); mc.playWhenReady = play
        }
        c.settings.lastBookId = id
    }

    /** Auto-resume on launch: reopens the most recently played unfinished book at its exact position. */
    suspend fun autoResume() {
        val p = c.sync.latestProgress() ?: return
        if (p.finished || !c.settings.autoResume || _ui.value.bookId != null) return
        open(p.bookId, play = true)
    }

    fun toggle() = withController { if (it.isPlaying) it.pause() else it.play() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun skip(deltaMs: Long) = withController { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0)) }
    fun selectTrack(i: Int) = withController { it.seekTo(i, 0) }
    fun setSpeed(s: Float) {
        val v = (Math.round(s * 10) / 10f).coerceIn(0.5f, 3.5f)
        c.settings.speed = v
        withController { it.setPlaybackParameters(PlaybackParameters(v, 1f)) }
    }
    fun setDsp(on: Boolean) {
        c.settings.dspOn = on
        _ui.value = _ui.value.copy(dsp = on)
        // Service reads the setting on session (re)attach; nudge it with a custom no-op via restart of effects.
        withController { it.sendCustomCommand(DSP_CMD, android.os.Bundle().apply { putBoolean("on", on) }) }
    }

    companion object {
        val DSP_CMD = androidx.media3.session.SessionCommand("torio.dsp", android.os.Bundle.EMPTY)
    }
}
