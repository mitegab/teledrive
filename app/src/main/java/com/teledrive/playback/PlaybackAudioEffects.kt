package com.teledrive.playback

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

data class AudioBand(
    val index: Int,
    val label: String,
    val levelMb: Int,
)

data class AudioEffectsState(
    val attached: Boolean = false,
    val available: Boolean = false,
    val enabled: Boolean = false,
    val presets: List<String> = emptyList(),
    val presetIndex: Int = 0,
    val bands: List<AudioBand> = emptyList(),
    val minLevelMb: Int = -1500,
    val maxLevelMb: Int = 1500,
    val gainMb: Int = 0,
    val maxGainMb: Int = 1500,
    val message: String? = null,
)

/**
 * Equalizer and loudness amplifier for the singleton player.
 *
 * Both are Android [android.media.audiofx.AudioEffect]s bound to the ExoPlayer audio session, so
 * they need no renderer changes and survive track changes within the session. Settings are
 * re-applied whenever the player creates a new audio session (new player instance or a session
 * restart), and everything degrades to a clear "unavailable" state on devices without the effect.
 */
@Singleton
class PlaybackAudioEffects @Inject constructor() {
    private var equalizer: Equalizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var sessionId: Int = 0

    private var desiredEnabled = false
    private var desiredPreset = 0
    private val desiredBands = mutableMapOf<Int, Int>()
    private var desiredGainMb = 0
    private var applyPresetBands = true

    private val _state = MutableStateFlow(AudioEffectsState())
    val state: StateFlow<AudioEffectsState> = _state.asStateFlow()

    /** Binds the effects to [audioSessionId]; safe to call repeatedly. */
    fun attach(audioSessionId: Int) {
        if (audioSessionId <= 0) {
            _state.update { it.copy(attached = false) }
            return
        }
        if (audioSessionId == sessionId && (equalizer != null || loudness != null)) return

        releaseEffects()
        sessionId = audioSessionId
        equalizer = runCatching { Equalizer(0, audioSessionId) }.getOrNull()?.apply {
            runCatching { enabled = desiredEnabled }
        }
        loudness = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()?.apply {
            runCatching { setTargetGain(desiredGainMb) }
            runCatching { enabled = desiredGainMb > 0 }
        }
        applyDesiredBands()
        publish()
    }

    fun setEnabled(enabled: Boolean) {
        desiredEnabled = enabled
        equalizer?.let { eq -> runCatching { eq.enabled = enabled } }
        publish()
    }

    fun usePreset(index: Int) {
        desiredPreset = index
        applyPresetBands = true
        equalizer?.let { eq ->
            runCatching { eq.usePreset(index.toShort()) }
        }
        publish()
    }

    fun setBandLevel(index: Int, levelMb: Int) {
        desiredBands[index] = levelMb
        applyPresetBands = false
        equalizer?.let { eq ->
            runCatching { eq.setBandLevel(index.toShort(), levelMb.toShort()) }
        }
        publish()
    }

    /** Loudness gain in millibels; 0 dB disables the enhancer. */
    fun setGainMb(gainMb: Int) {
        val clamped = gainMb.coerceIn(0, _state.value.maxGainMb)
        desiredGainMb = clamped
        loudness?.let { le ->
            runCatching { le.setTargetGain(clamped) }
            runCatching { le.enabled = clamped > 0 }
        }
        publish()
    }

    fun reset() {
        desiredEnabled = false
        desiredPreset = 0
        desiredBands.clear()
        desiredGainMb = 0
        applyPresetBands = true
        equalizer?.let { eq ->
            runCatching { eq.enabled = false }
            runCatching { eq.usePreset(0) }
        }
        loudness?.let { le ->
            runCatching { le.setTargetGain(0) }
            runCatching { le.enabled = false }
        }
        publish()
    }

    fun release() {
        releaseEffects()
        _state.value = AudioEffectsState()
        sessionId = 0
    }

    private fun releaseEffects() {
        runCatching { equalizer?.release() }
        runCatching { loudness?.release() }
        equalizer = null
        loudness = null
    }

    private fun applyDesiredBands() {
        val eq = equalizer ?: return
        runCatching {
            if (applyPresetBands) {
                eq.usePreset(desiredPreset.toShort())
            } else {
                desiredBands.forEach { (index, level) ->
                    eq.setBandLevel(index.toShort(), level.toShort())
                }
            }
        }
    }

    private fun publish() {
        val eq = equalizer
        val le = loudness
        if (eq == null && le == null) {
            _state.value = AudioEffectsState(
                attached = sessionId > 0,
                available = false,
                message = "This device exposes no equalizer for the player's audio session.",
            )
            return
        }
        val presets = mutableListOf<String>()
        val bands = mutableListOf<AudioBand>()
        var range = intArrayOf(-1500, 1500)
        if (eq != null) {
            runCatching {
                for (index in 0 until eq.numberOfPresets.toInt()) {
                    val name = runCatching { eq.getPresetName(index.toShort()) }.getOrNull()
                    presets.add(name?.takeIf { it.isNotBlank() } ?: "Preset ${index + 1}")
                }
                range = runCatching { eq.bandLevelRange.map { it.toInt() }.toIntArray() }
                    .getOrDefault(range)
                for (index in 0 until eq.numberOfBands.toInt()) {
                    val level = runCatching { eq.getBandLevel(index.toShort()).toInt() }.getOrDefault(0)
                    bands.add(AudioBand(index, bandLabel(eq, index), level))
                }
            }
        }
        _state.value = AudioEffectsState(
            attached = sessionId > 0,
            available = true,
            enabled = eq?.let { runCatching { it.enabled }.getOrDefault(false) } ?: false,
            presets = presets,
            presetIndex = desiredPreset.coerceIn(0, (presets.size - 1).coerceAtLeast(0)),
            bands = bands,
            minLevelMb = range.firstOrNull() ?: -1500,
            maxLevelMb = range.lastOrNull() ?: 1500,
            gainMb = desiredGainMb,
            message = if (eq == null) "Equalizer unavailable; loudness amplification still works." else null,
        )
    }

    private fun bandLabel(eq: Equalizer, index: Int): String {
        val hz = runCatching { eq.getCenterFreq(index.toShort()) / 1000 }.getOrDefault(0)
        return when {
            hz <= 0 -> "Band ${index + 1}"
            hz < 1000 -> "${hz}Hz"
            else -> String.format(Locale.getDefault(), "%.1fkHz", hz / 1000f)
        }
    }
}
