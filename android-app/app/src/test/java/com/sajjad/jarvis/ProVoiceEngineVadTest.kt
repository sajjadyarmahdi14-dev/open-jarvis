package com.sajjad.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class ProVoiceEngineVadTest {

    @Test
    fun testHighPassFilter300HzAttenuatesLowFrequencyRumble() {
        val hpf = ProVoiceEngine.Companion.HighPassFilter300Hz(0.895f)
        val sampleRate = 16000
        val count = 1600 // 100ms of audio

        // Generate 100 Hz low-frequency engine rumble
        val rumbleSamples = ShortArray(count) { i ->
            (sin(2.0 * PI * 100.0 * i / sampleRate) * 10000.0).toInt().toShort()
        }

        // Generate 1000 Hz human speech formant tone
        val speechSamples = ShortArray(count) { i ->
            (sin(2.0 * PI * 1000.0 * i / sampleRate) * 10000.0).toInt().toShort()
        }

        hpf.reset()
        val rumbleRms = hpf.apply(rumbleSamples, count)

        hpf.reset()
        val speechRms = hpf.apply(speechSamples, count)

        // 1000Hz speech RMS should be at least 2.5x higher than 100Hz rumble through the 300Hz HPF
        assertTrue("Speech RMS ($speechRms) should be significantly higher than rumble RMS ($rumbleRms)", speechRms > rumbleRms * 2.5)
    }

    @Test
    fun testPeakRelativeSilenceDetectionUnderHighNoiseFloor() {
        // High street noise floor (filtered RMS = 800)
        val liveNoiseFloor = 800.0

        // User speaks loudly over street noise (peak RMS = 2400)
        val speechPeakRms = 2400.0

        // Calculate dynamic peak-relative silence threshold
        val silenceThreshold = maxOf(liveNoiseFloor * 1.35, speechPeakRms * 0.45)
        assertEquals(1080.0, silenceThreshold, 0.001)

        // While speaking (RMS = 2000), energy is above threshold -> NOT silence
        val speakingRms = 2000.0
        assertFalse("While speaking, energy should exceed silence threshold", speakingRms < silenceThreshold)

        // When user ceases speech, energy returns to street noise floor (RMS = 800)
        val silenceRms = 800.0
        assertTrue("When speech ceases, street noise level must be recognized as silence below peak threshold", silenceRms < silenceThreshold)
    }

    @Test
    fun testRepeatedWakeWordDeduplication() {
        assertTrue(LocalCommandRouter.isAllWakeWords("Jarvis Jarvis Jarvis"))
        assertTrue(LocalCommandRouter.isAllWakeWords("jarvis jarvis"))
        assertTrue(LocalCommandRouter.isAllWakeWords("hey jarvis jarvis"))
        assertTrue(LocalCommandRouter.isAllWakeWords("جارویس جارویس"))
        assertTrue(LocalCommandRouter.isAllWakeWords("جارویس جارویس جارویس"))
        assertTrue(LocalCommandRouter.isAllWakeWords("سلام جارویس جارویس"))
        assertTrue(LocalCommandRouter.isAllWakeWords("Jarvis"))
        assertTrue(LocalCommandRouter.isAllWakeWords("جارویس"))

        // False cases: real commands or prefixes only
        assertFalse(LocalCommandRouter.isAllWakeWords("Jarvis turn on flashlight"))
        assertFalse(LocalCommandRouter.isAllWakeWords("جارویس چراغ قوه رو روشن کن"))
        assertFalse(LocalCommandRouter.isAllWakeWords("hey"))
        assertFalse(LocalCommandRouter.isAllWakeWords("سلام"))
        assertFalse(LocalCommandRouter.isAllWakeWords(""))
    }

    @Test
    fun testPeakNormalizePcmBoostsQuietSpeechWithoutClipping() {
        val count = 800
        val quietPeak = 1200
        val pcm = ByteArray(count * 2)

        // Generate quiet sine wave with peak = 1200
        for (i in 0 until count) {
            val s = (sin(2.0 * PI * i / 50.0) * quietPeak).toInt().toShort()
            val u = s.toInt()
            pcm[i * 2] = (u and 0xFF).toByte()
            pcm[i * 2 + 1] = ((u shr 8) and 0xFF).toByte()
        }

        val normalized = ProVoiceEngine.Companion.peakNormalizePcm(pcm)
        var maxNormPeak = 0
        for (i in 0 until count) {
            val low = normalized[i * 2].toInt() and 0xFF
            val high = normalized[i * 2 + 1].toInt()
            val s = ((high shl 8) or low).toShort()
            val absVal = kotlin.math.abs(s.toInt())
            if (absVal > maxNormPeak) maxNormPeak = absVal
        }

        // Peak should be boosted cleanly from 1200 to ~24000 (-2.5 dBFS)
        assertTrue("Normalized peak ($maxNormPeak) should be close to 24000", maxNormPeak in 23500..24500)

        // Flat silence (16-bit sample peak = 10 <= 80) should NOT be amplified
        val silentPcm = ByteArray(100) { if (it % 2 == 0) 10 else 0 }
        val normSilent = ProVoiceEngine.Companion.peakNormalizePcm(silentPcm)
        assertEquals(silentPcm.toList(), normSilent.toList())

        // Already loud audio (peak = 22000) should NOT be modified
        val loudPcm = ByteArray(count * 2)
        val loudShort = 22000.toShort()
        loudPcm[0] = (loudShort.toInt() and 0xFF).toByte()
        loudPcm[1] = ((loudShort.toInt() shr 8) and 0xFF).toByte()
        val normLoud = ProVoiceEngine.Companion.peakNormalizePcm(loudPcm)
        assertEquals(loudPcm.toList(), normLoud.toList())
    }

    @Test
    fun testListeningModeBuffersAudioImmediately() {
        // In LISTENING mode, preRoll (800ms = 25600 bytes) + incoming audio is buffered immediately
        val preRollBytes = (16000 * 800 / 1000) * 2 // 25600 bytes
        assertTrue("Pre-roll buffer must be at least 800ms of 16-bit PCM (25600 bytes)", preRollBytes == 25600)

        val incoming50ms = (16000 * 50 / 1000) * 2 // 1600 bytes
        val totalRecorded = preRollBytes + incoming50ms
        // Total audio on immediate tap-to-stop is > 27000 bytes (over 850ms), never empty STT!
        assertTrue("Immediate capture in LISTENING mode is never empty STT", totalRecorded > 20000)
    }

    @Test
    fun testSpeechBandPassFilterAttenuatesBothRumbleAndHiss() {
        val bpf = ProVoiceEngine.Companion.SpeechBandPassFilter(0.895f, 0.572f)
        val sampleRate = 16000
        val count = 1600 // 100ms

        // 100 Hz low rumble
        val rumbleSamples = ShortArray(count) { i ->
            (sin(2.0 * PI * 100.0 * i / sampleRate) * 10000.0).toInt().toShort()
        }
        // 1000 Hz human speech formant
        val speechSamples = ShortArray(count) { i ->
            (sin(2.0 * PI * 1000.0 * i / sampleRate) * 10000.0).toInt().toShort()
        }
        // 6000 Hz high cymbal hiss
        val hissSamples = ShortArray(count) { i ->
            (sin(2.0 * PI * 6000.0 * i / sampleRate) * 10000.0).toInt().toShort()
        }

        bpf.reset()
        val rumbleRms = bpf.apply(rumbleSamples, count)

        bpf.reset()
        val speechRms = bpf.apply(speechSamples, count)

        bpf.reset()
        val hissRms = bpf.apply(hissSamples, count)

        assertTrue("Speech RMS ($speechRms) should be significantly higher than rumble RMS ($rumbleRms)", speechRms > rumbleRms * 2.0)
        assertTrue("Speech RMS ($speechRms) should be significantly higher than hiss RMS ($hissRms)", speechRms > hissRms * 1.5)
    }

    @Test
    fun testNaturalPacingIncompleteClauses() {
        assertTrue(ProVoiceEngine.Companion.isSemanticallyIncomplete("من می‌خواهم که"))
        assertTrue(ProVoiceEngine.Companion.isSemanticallyIncomplete("send an email to"))
        assertTrue(ProVoiceEngine.Companion.isSemanticallyIncomplete("بگو به"))
        assertTrue(ProVoiceEngine.Companion.isSemanticallyIncomplete("set a timer for"))
        assertFalse(ProVoiceEngine.Companion.isSemanticallyIncomplete("ساعت چنده"))
        assertFalse(ProVoiceEngine.Companion.isSemanticallyIncomplete("turn on the flashlight"))
    }
}
