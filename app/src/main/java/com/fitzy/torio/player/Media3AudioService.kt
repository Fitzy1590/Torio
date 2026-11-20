package com.fitzy.torio.player

import android.content.Intent
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.fitzy.torio.TorioApp
import com.fitzy.torio.data.ProgressEntity
import com.fitzy.torio.net.TorBoxApiClient
import kotlinx.coroutines.*

/**
 * Owns the only ExoPlayer. Streams use big RAM buffers (read-ahead across chapters), `torbox://` URIs are
 * resolved to fresh CDN links just before they're read, and progress is written to Room every 3 s.
 */
class Media3AudioService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val dsp = VoiceDspEnhancer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var saver: Job? = null

    override fun onCreate() {
        super.onCreate()
        val c = (application as TorioApp).container
        val torbox = TorBoxApiClient(c.http) { c.settings.torboxKey }

        val http = OkHttpDataSource.Factory(c.http)
        val resolving = ResolvingDataSource.Factory(http) { spec: DataSpec ->
            if (spec.uri.scheme == "torbox") {
                spec.withUri(Uri.parse(torbox.resolve(spec.uri)))
            } else spec
        }
        val load = DefaultLoadControl.Builder()
            .setAllocator(androidx.media3.exoplayer.upstream.DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(60_000, 600_000, 1_500, 4_000)   // keep 10 min ahead, start in 1.5 s
            .setTargetBufferBytes(96 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(60_000, true)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { resolving.createDataSource() }))
            .setLoadControl(load)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.skipSilenceEnabled = c.settings.skipSilence
        player.setPlaybackParameters(PlaybackParameters(c.settings.speed, 1f))  // pitch stays 1 = pitch-preserved
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onConnect(s: MediaSession, ctl: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(PlayerConnection.DSP_CMD).add(PlayerConnection.SILENCE_CMD).build()
                return MediaSession.ConnectionResult.accept(cmds, MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
            }
            override fun onCustomCommand(s: MediaSession, ctl: MediaSession.ControllerInfo, cmd: androidx.media3.session.SessionCommand, args: android.os.Bundle):
                com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
                if (cmd.customAction == "torio.dsp") dsp.setEnabled(args.getBoolean("on", true))
                if (cmd.customAction == "torio.silence") player.skipSilenceEnabled = args.getBoolean("on", false)
                return com.google.common.util.concurrent.Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
            }
        }).build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = dsp.attach(audioSessionId, c.settings.dspOn)
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startSaver() else { saver?.cancel(); scope.launch { saveNow() } }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    dsp.attach(player.audioSessionId, c.settings.dspOn)
                    val i = player.currentMediaItemIndex
                    val d = player.duration
                    val id = player.currentMediaItem?.mediaId?.substringBefore('#')
                    if (id != null && d > 0) scope.launch { c.db.bookDao().setDuration(id, i, d) }
                }
                if (state == Player.STATE_ENDED) scope.launch { saveNow(finished = true) }
            }
        })
    }

    private fun startSaver() {
        saver?.cancel()
        saver = scope.launch { while (isActive) { saveNow(); delay(3_000) } }
    }

    private suspend fun saveNow(finished: Boolean = false) {
        val c = (application as TorioApp).container
        val item = player.currentMediaItem ?: return
        val bookId = item.mediaId.substringBefore('#')
        val tracks = c.db.bookDao().tracks(bookId)
        val idx = player.currentMediaItemIndex
        val pos = player.currentPosition.coerceAtLeast(0)
        // Durations are learned as tracks load; guess unknown ones from the average so percent doesn't jump or hit 100 early.
        val known = tracks.filter { it.durationMs > 0 }.map { it.durationMs }
        val avg = if (known.isNotEmpty()) known.average().toLong() else player.duration.coerceAtLeast(0)
        fun len(i: Int) = tracks.getOrNull(i)?.durationMs?.takeIf { it > 0 } ?: avg
        val before = (0 until idx).sumOf { len(it) }
        val total = tracks.indices.sumOf { len(it) }.coerceAtLeast(player.duration.coerceAtLeast(0))
        val abs = before + pos
        val pct = if (total > 0) ((abs * 100) / total).toInt().coerceIn(0, 100) else 0
        val done = finished || (idx == tracks.lastIndex && pct >= 99)
        c.sync.saveProgress(ProgressEntity(bookId, idx, pos, abs, total, if (done) 100 else pct, done))
        c.settings.lastBookId = bookId
    }

    override fun onGetSession(info: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        runBlocking { withTimeoutOrNull(500) { saveNow() } }
        scope.cancel(); dsp.release()
        session?.release(); player.release(); session = null
        super.onDestroy()
    }
}
