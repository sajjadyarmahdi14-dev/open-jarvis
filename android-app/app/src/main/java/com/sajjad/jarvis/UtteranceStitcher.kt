package com.sajjad.jarvis

import android.util.Log
import java.util.Locale

/**
 * Utterance Stitcher & Literal Text Sanitizer for J.A.R.V.I.S.
 *
 * 1. Accumulates multi-clause long utterances without microphone closure.
 * 2. Incomplete-Clause Guard: Extends silence detection when trailing conjunctions/prepositions are spoken.
 * 3. Strict Literal Text Sanitizer: Strips instruction wrappers before typing in any app.
 */
object UtteranceStitcher {

    private const val TAG = "UtteranceStitcher"

    val trailingEnglishTokens = setOf(
        "and", "to", "that", "with", "for", "say", "message", "play", "on", "from", "then", "also", "set"
    )

    val trailingPersianTokens = setOf(
        "و", "که", "به", "با", "بگو", "بنویس", "پخش کن", "از", "رو", "بزار"
    )

    /**
     * Checks if text ends with a trailing conjunction, preposition, or transitive verb.
     */
    fun hasIncompleteClause(text: String): Boolean {
        val trimmed = text.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) return false

        val words = trimmed.split(Regex("\\s+"))
        val lastWord = words.lastOrNull()?.trim() ?: return false
        val cleanLast = lastWord.replace(Regex("[^a-z0-9\u0600-\u06FF]"), "")

        if (cleanLast in trailingEnglishTokens || cleanLast in trailingPersianTokens) {
            Log.i(TAG, "Incomplete-clause guard triggered on trailing token: '$cleanLast'")
            return true
        }

        if (trimmed.endsWith("پخش کن") || trimmed.endsWith("گوش بده به") || trimmed.endsWith("and also")) {
            return true
        }

        return false
    }

    /**
     * Stitch two utterance segments into a single cohesive sentence.
     */
    fun stitchUtterances(base: String, addition: String): String {
        val b = base.trim()
        val a = addition.trim()
        if (b.isEmpty()) return a
        if (a.isEmpty()) return b
        return "$b $a"
    }

    /**
     * Strict Literal Text Sanitizer:
     * Strips any wrapper phrases (e.g. "and type ", "type ", "and send it", "send it", "say ", "بگو ", "بنویس ", "و بفرست")
     * to guarantee ZERO instruction leakage into apps.
     */
    fun sanitizeLiteralText(raw: String): String {
        var text = raw.trim()
        if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
            if (text.length >= 2) {
                text = text.substring(1, text.length - 1).trim()
            }
        }

        var prev = ""
        while (prev != text) {
            prev = text
            // Strip common LLM hallucination wrappers globally
            val strictPrefix = Regex("^(?:and type |type |and send it|send it|say |message |بنویس |بگو |و بفرست |تایپ کن |ارسال کن )", RegexOption.IGNORE_CASE)
            val strictSuffix = Regex("(?: and send it| and send| send it| و بفرست| و ارسال کن| بفرست)$", RegexOption.IGNORE_CASE)
            text = text.replace(strictPrefix, "").replace(strictSuffix, "").trim()

            // English wrapper regexes
            val enPrefix = Regex("^(?:please\\s+)?(?:and\\s+)?(?:type|write|say|enter|input|message|send)\\s+(?:that\\s+|it\\s+|text\\s+)?(?:saying\\s+|quoting\\s+|:\\s*)?", RegexOption.IGNORE_CASE)
            val enSuffix = Regex("(?:\\s+(?:and\\s+)?(?:send(?:\\s+it)?|submit(?:\\s+it)?|press\\s+enter))$", RegexOption.IGNORE_CASE)
            text = text.replace(enPrefix, "").replace(enSuffix, "").trim()

            // Persian wrapper regexes
            val faPrefix = Regex("^(?:لطفاً\\s+|لطفا\\s+)?(?:و\\s+)?(?:بنویس|بگو|تایپ\\s*کن|ارسال\\s*کن|بفرست)\\s*(?:که\\s+|:\\s*)?", RegexOption.IGNORE_CASE)
            val faSuffix = Regex("(?:\\s*(?:و\\s+)?(?:بفرست|ارسال\\s*کن))$", RegexOption.IGNORE_CASE)
            text = text.replace(faPrefix, "").replace(faSuffix, "").trim()

            // Standalone residual wrappers
            val resPrefix = Regex("^(?:and\\s+type\\s+|type\\s+|say\\s+|بگو\\s+|بنویس\\s+)", RegexOption.IGNORE_CASE)
            val resSuffix = Regex("(?:\\s+and\\s+send\\s+it|\\s+and\\s+send|\\s+send\\s+it|\\s*و\\s*بفرست)$", RegexOption.IGNORE_CASE)
            text = text.replace(resPrefix, "").replace(resSuffix, "").trim()

            if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
                if (text.length >= 2) {
                    text = text.substring(1, text.length - 1).trim()
                }
            }
        }

        return text
    }
}
