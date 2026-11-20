package com.sajjad.jarvis.router

import java.util.Locale

/**
 * Shared Persian/English NLP Normalizer for J.A.R.V.I.S. Multi-Domain Zero-Token Engine.
 *
 * Provides:
 * 1. Persian & Arabic digit normalization to standard 0-9.
 * 2. Persian character harmonization (Arabic Yeh/Kaf to Persian ی/ک, zero-width non-joiner normalization).
 * 3. Persian-to-Latin transliteration (Pinglish/Finglish) for music and app query matching.
 * 4. Fuzzy token scoring (Jaccard + Levenshtein).
 * 5. Extraction of numbers, durations, and conversational slots.
 */
object NlpNormalizer {

    private val FA_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    private val AR_DIGITS = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')

    fun isPersian(text: String): Boolean {
        return text.any { it in '\u0600'..'\u06FF' }
    }

    fun normalizeDigits(input: String): String {
        var res = input
        for (i in 0..9) {
            res = res.replace(FA_DIGITS[i], ('0' + i)).replace(AR_DIGITS[i], ('0' + i))
        }
        return res
    }

    fun normalize(input: String): String {
        if (input.isBlank()) return ""
        var s = normalizeDigits(input)
        // Harmonize Arabic kaf and yeh
        s = s.replace('ي', 'ی').replace('ك', 'ک')
        // Remove zero-width non-joiners (\u200C) and zero-width spaces
        s = s.replace("\u200C", " ").replace("\u200B", " ")
        // Normalize common punctuation to space
        s = s.replace(Regex("[,.!?؛؟:،\"'«»]+"), " ")
        // Collapse multiple spaces
        return s.trim().replace(Regex("\\s+"), " ")
    }

    fun cleanLower(input: String): String {
        return normalize(input).lowercase(Locale.ROOT)
    }

    /**
     * Maps colloquial Persian words to Latin phonetics (Pinglish)
     * allowing Persian voice queries to match English ID3 tags and filenames.
     */
    fun transliteratePersianToLatin(input: String): String {
        var s = normalize(input).lowercase(Locale.ROOT)
        val charMap = mapOf(
            "ش" to "sh", "چ" to "ch", "خ" to "kh", "ژ" to "zh", "ق" to "gh", "غ" to "gh",
            "ف" to "f", "پ" to "p", "گ" to "g", "ک" to "k", "س" to "s", "ص" to "s", "ث" to "s",
            "ز" to "z", "ض" to "z", "ظ" to "z", "ذ" to "z", "ت" to "t", "ط" to "t", "ح" to "h",
            "ه" to "h", "ع" to "a", "ء" to "", "آ" to "a", "ا" to "a", "ب" to "b", "د" to "d",
            "ر" to "r", "م" to "m", "ن" to "n", "و" to "o", "ی" to "i", "ل" to "l"
        )
        for ((fa, en) in charMap) {
            s = s.replace(fa, en)
        }
        return s.trim().replace(Regex("\\s+"), " ")
    }

    fun levenshtein(s1: String, s2: String): Int {
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                dp[j] = if (s1[i - 1] == s2[j - 1]) prev else minOf(prev, dp[j], dp[j - 1]) + 1
                prev = temp
            }
        }
        return dp[s2.length]
    }

    /**
     * Compute token overlap similarity [0.0 .. 1.0] between query and target string.
     */
    fun tokenFuzzyScore(query: String, target: String): Double {
        val qTokens = cleanLower(query).split(" ").filter { it.length > 1 }
        val tTokens = cleanLower(target).split(" ").filter { it.length > 1 }
        if (qTokens.isEmpty() || tTokens.isEmpty()) return 0.0

        var matchCount = 0
        for (q in qTokens) {
            if (tTokens.any { t -> t == q || t.contains(q) || (t.length > 3 && q.length > 3 && levenshtein(t, q) <= 1) }) {
                matchCount++
            }
        }
        return matchCount.toDouble() / qTokens.size.toDouble()
    }

    fun extractFirstNumber(text: String): Int? {
        val norm = normalizeDigits(text)
        val m = Regex("(\\d+)").find(norm)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    fun containsAny(text: String, keywords: Collection<String>): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return keywords.any { lower.contains(it.lowercase(Locale.ROOT)) }
    }
}
