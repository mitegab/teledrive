package com.teledrive.playback.video

/**
 * What the playback engine behind this app (Media3/ExoPlayer 1.11 + platform MediaCodec and
 * AudioEffect) can actually do.
 *
 * The video workspace lists every requested capability next to its real status instead of
 * faking switches that do nothing: anything marked [EngineSupport.NOT_EXPOSED] has no public
 * API in Media3, and anything marked [EngineSupport.DEVICE_DEPENDENT] only works when the
 * device ships the required effect or codec.
 */
enum class EngineSupport(val label: String) {
    SUPPORTED("Supported"),
    DEVICE_DEPENDENT("Device dependent"),
    NOT_EXPOSED("Not exposed by Media3"),
}

data class CapabilityRow(
    val name: String,
    val support: EngineSupport,
    val detail: String,
)

fun audioCapabilities(): List<CapabilityRow> = listOf(
    CapabilityRow(
        "Equalizer",
        EngineSupport.DEVICE_DEPENDENT,
        "Android AudioEffect attached to the player's audio session; presets and per-band gains.",
    ),
    CapabilityRow(
        "Volume amplification",
        EngineSupport.DEVICE_DEPENDENT,
        "LoudnessEnhancer gain up to +15 dB on top of the player volume.",
    ),
    CapabilityRow(
        "Track selection",
        EngineSupport.SUPPORTED,
        "Every audio/subtitle track in the container can be selected from the settings sheet.",
    ),
    CapabilityRow(
        "Stereo / mono and L/R balance",
        EngineSupport.NOT_EXPOSED,
        "Needs a custom channel-mixing audio processor; Media3's default sink only downmixes " +
            "following the device's own output configuration.",
    ),
    CapabilityRow(
        "Audio normalisation / passthrough",
        EngineSupport.NOT_EXPOSED,
        "ReplayGain and IEC61937 passthrough are decoder/HDMI features the platform controls, " +
            "not the player.",
    ),
    CapabilityRow(
        "A/V sync offset",
        EngineSupport.NOT_EXPOSED,
        "ExoPlayer keeps audio and video synchronised internally but exposes no per-track delay.",
    ),
    CapabilityRow(
        "External audio track",
        EngineSupport.NOT_EXPOSED,
        "Merging a sidecar audio file needs a MergingMediaSource per playback session.",
    ),
)

fun videoCapabilities(): List<CapabilityRow> = listOf(
    CapabilityRow(
        "Hardware decoding",
        EngineSupport.DEVICE_DEPENDENT,
        "ExoPlayer picks the platform MediaCodec; hardware decoders are used when the SoC has one.",
    ),
    CapabilityRow(
        "Frame stepping",
        EngineSupport.SUPPORTED,
        "Pause + exact single-frame seek, the standard Media3 approach.",
    ),
    CapabilityRow(
        "Aspect ratio, rotation, zoom",
        EngineSupport.SUPPORTED,
        "Fit / zoom / stretch plus 90° video rotation and forced orientation.",
    ),
    CapabilityRow(
        "SDR / HDR",
        EngineSupport.DEVICE_DEPENDENT,
        "HDR10/HLG are detected and reported; tone mapping depends on the display and decoder.",
    ),
    CapabilityRow(
        "Colour adjustment (brightness, contrast, saturation, hue, gamma)",
        EngineSupport.NOT_EXPOSED,
        "Media3 has no colour grading stage; a GL effect would be needed.",
    ),
    CapabilityRow(
        "Deinterlacing, denoising, sharpening, upscaling",
        EngineSupport.NOT_EXPOSED,
        "Handled by the hardware decoder or display scaler; not switchable from the player.",
    ),
    CapabilityRow(
        "High frame rate (60/120 fps)",
        EngineSupport.DEVICE_DEPENDENT,
        "Played natively when the panel and decoder support it; the detected rate is reported.",
    ),
)
