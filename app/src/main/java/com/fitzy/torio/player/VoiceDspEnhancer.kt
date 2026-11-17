package com.fitzy.torio.player

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build

/**
 * Voice clarity chain on the player's audio session: presence EQ (cut rumble, lift 1-4 kHz),
 * compression to even out quiet narrators, and a small loudness lift.
 * Follows RootlessJamesDSP-style parametric EQ + compressor ideas using stock AudioEffect APIs.
 */
class VoiceDspEnhancer {
    private var eq: Equalizer? = null
    private var loud: LoudnessEnhancer? = null
    private var dyn: DynamicsProcessing? = null
    private var session = 0

    fun attach(audioSessionId: Int, enabled: Boolean) {
        if (audioSessionId == 0) return
        if (audioSessionId != session) { release(); session = audioSessionId }
        runCatching { if (eq == null) eq = Equalizer(0, audioSessionId) }
        runCatching { if (loud == null) loud = LoudnessEnhancer(audioSessionId) }
        if (dyn == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { dyn = buildDynamics(audioSessionId) }
        setEnabled(enabled)
    }

    fun setEnabled(on: Boolean) {
        runCatching {
            eq?.let { e ->
                if (on) applyVoiceCurve(e)
                e.enabled = on
            }
            loud?.apply { setTargetGain(if (on) 400 else 0); enabled = on }
            dyn?.enabled = on
        }
    }

    private fun applyVoiceCurve(e: Equalizer) {
        val range = e.bandLevelRange
        val lo = range[0]; val hi = range[1]
        for (b in 0 until e.numberOfBands) {
            val hz = e.getCenterFreq(b.toShort()) / 1000
            val gain = when {
                hz < 90 -> -600          // rumble
                hz < 250 -> -200         // mud
                hz in 1000..4500 -> 450  // speech presence
                hz > 8000 -> 100         // air
                else -> 0
            }
            e.setBandLevel(b.toShort(), gain.coerceIn(lo.toInt(), hi.toInt()).toShort())
        }
    }

    private fun buildDynamics(session: Int): DynamicsProcessing {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2, false, 0, true, 1, false, 0, true,
        ).build()
        val d = DynamicsProcessing(0, session, cfg)
        for (ch in 0 until 2) {
            d.getMbcByChannelIndex(ch).let { mbc ->
                val band = mbc.getBand(0)
                band.isEnabled = true
                band.cutoffFrequency = 20000f
                band.attackTime = 20f; band.releaseTime = 250f
                band.ratio = 3f; band.threshold = -32f; band.kneeWidth = 6f
                band.postGain = 5f   // make up for the squeezed peaks
                d.setMbcBandByChannelIndex(ch, 0, band)
            }
            d.getLimiterByChannelIndex(ch).let { lim ->
                lim.isEnabled = true; lim.threshold = -2f; lim.ratio = 10f
                lim.attackTime = 1f; lim.releaseTime = 60f
                d.setLimiterByChannelIndex(ch, lim)
            }
        }
        return d
    }

    fun release() {
        runCatching { eq?.release() }; runCatching { loud?.release() }; runCatching { dyn?.release() }
        eq = null; loud = null; dyn = null; session = 0
    }
}
