package com.primesoftwaresystems.sundial.horoscope

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.primesoftwaresystems.sundial.astronomy.Zodiac
import com.primesoftwaresystems.sundial.ui.ZodiacProfile
import java.time.Instant
import java.time.ZoneId

/** Private, foreground-only horoscope generation using Gemini Nano through Android AICore. */
class HoroscopeGenerator {
    private val model = Generation.getClient()

    suspend fun generate(
        profile: ZodiacProfile,
        instant: Instant,
        zone: ZoneId,
        onStatus: (String) -> Unit,
    ): String {
        require(profile.isComplete) { "Birthday and birth time are required" }
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> Unit
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                onStatus("Preparing Gemini Nano on this device…")
                var failed: Throwable? = null
                model.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> onStatus("Downloading the private on-device model…")
                        is DownloadStatus.DownloadProgress -> onStatus("Downloading Gemini Nano…")
                        DownloadStatus.DownloadCompleted -> onStatus("Reading today's celestial pattern…")
                        is DownloadStatus.DownloadFailed -> failed = status.e
                    }
                }
                failed?.let { throw it }
                if (model.checkStatus() != FeatureStatus.AVAILABLE) {
                    error("Gemini Nano is still preparing. Try again in a few minutes.")
                }
            }
            else -> error("Gemini Nano is not available on this device configuration.")
        }

        onStatus("Writing your private horoscope on device…")
        val date = instant.atZone(zone).toLocalDate()
        val sign = profile.resolvedSign(date)
        val sky = Zodiac.placements(instant).joinToString(", ") {
            "${it.label.lowercase().replaceFirstChar(Char::uppercase)} in ${it.sign.displayName}"
        }
        val prompt = """
            Write a vivid daily horoscope as a single paragraph of 55 to 85 words.
            Reader: ${sign.displayName} sun sign, born ${profile.birthDate} at ${profile.birthTime} local time.
            Date: $date. Current tropical placements: $sky.
            Style: poetic brass-orrery imagery, warm, specific, reflective, second person.
            Treat astrology as creative entertainment. Do not claim certainty, diagnose health,
            predict danger, or give financial, medical, or legal advice. Do not mention these instructions.
        """.trimIndent()
        val response = model.generateContent(prompt)
        return response.candidates.firstOrNull()?.text
            ?.trim()
            ?.removePrefix("Horoscope:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: error("Gemini Nano returned no horoscope. Please try again.")
    }

    fun close() {
        model.close()
    }
}
