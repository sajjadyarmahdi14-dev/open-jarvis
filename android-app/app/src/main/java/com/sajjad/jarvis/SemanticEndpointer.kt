package com.sajjad.jarvis

import java.util.Locale

/**
 * Layer 1: Semantic Endpointing
 *
 * Replaces the rigid 3.5s silence wait with an intelligent 3-tier adaptive silence cutoff
 * that inspects live partial speech transcripts to decide the exact moment to commit:
 * - Fast Path (650ms): complete unambiguous commands / questions
 * - Natural Path (1300ms): complete declarative statements
 * - Patient Path (3200ms): incomplete trailing conjunctions, prepositions, fillers, open verbs
 * - Hard Maximum (60000ms): long-form dictation limit
 */
object SemanticEndpointer {

    const val FAST_PATH_SILENCE_MS = 650L
    const val NATURAL_PATH_SILENCE_MS = 1300L
    const val PATIENT_PATH_SILENCE_MS = 3200L
    const val MAX_COMMAND_DURATION_MS = 60000L

    private val PERSIAN_INCOMPLETE_MARKERS = setOf(
        "و", "که", "به", "با", "از", "رو", "را", "تا", "ولی", "اما", "یا",
        "چون", "یعنی", "بگو", "بنویس", "بفرست", "بزار", "بذار", "ببین", "اممم", "چیزه"
    )

    private val ENGLISH_INCOMPLETE_MARKERS = setOf(
        "and", "to", "that", "with", "for", "but", "or", "so", "because",
        "if", "then", "say", "tell", "message", "play", "set", "open", "um", "uh", "like"
    )

    private val FAST_PATH_PATTERNS = listOf(
        "چراغ قوه", "فلش", "ساعت چنده", "ساعت", "تلگرام", "واتساپ", "دوربین", "عکس بگیر",
        "آهنگ", "موزیک", "اهنگ پخش کن", "پخش موزیک", "سازندت کیه", "کی ساختت", "سجاد یارمیری", "jellybean",
        "turn on flashlight", "turn off flashlight", "flashlight", "what time is it", "time",
        "open camera", "take photo", "take picture", "play music", "pause music", "stop music",
        "next track", "previous track", "volume up", "volume down", "mute", "unmute",
        "who made you", "who is sajjad yarmiri"
    )

    private val COMPLETE_VERB_ENDINGS_FA = listOf(
        "کن", "بکن", "بزن", "بده", "بگیر", "بفرست", "باز کن", "ببند", "روشن کن", "خاموش کن",
        "بگو", "چنده", "کیه", "چیست", "است", "هست", "نیست", "شد", "باشه"
    )

    /**
     * Determines whether the partial transcript has reached a natural endpoint
     * and calculates the adaptive silence cutoff in milliseconds.
     */
    fun getAdaptiveSilenceTimeoutMs(partialTranscript: String): Long {
        val trimmed = partialTranscript.trim().lowercase(Locale.ROOT)
        if (trimmed.isBlank()) return NATURAL_PATH_SILENCE_MS

        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return NATURAL_PATH_SILENCE_MS

        val lastToken = tokens.last()

        // 1. Patient Path (trailing conjunctions, prepositions, filler words, or open transitive verbs)
        if (lastToken in PERSIAN_INCOMPLETE_MARKERS || lastToken in ENGLISH_INCOMPLETE_MARKERS) {
            return PATIENT_PATH_SILENCE_MS
        }

        // 2. Fast Path (unambiguous commands or direct questions)
        if (FAST_PATH_PATTERNS.any { trimmed.contains(it) }) {
            if (COMPLETE_VERB_ENDINGS_FA.any { trimmed.endsWith(it) } || tokens.size <= 5) {
                return FAST_PATH_SILENCE_MS
            }
        }
        if (COMPLETE_VERB_ENDINGS_FA.any { trimmed.endsWith(it) }) {
            return FAST_PATH_SILENCE_MS
        }
        if (trimmed.endsWith("?") || trimmed.endsWith("؟")) {
            return FAST_PATH_SILENCE_MS
        }

        // 3. Default Natural Path
        return NATURAL_PATH_SILENCE_MS
    }

    /**
     * Helper to check if a partial transcript represents a fast-path command immediately.
     */
    fun isImmediateFastPathCommand(partialTranscript: String): Boolean {
        return getAdaptiveSilenceTimeoutMs(partialTranscript) == FAST_PATH_SILENCE_MS
    }
}
