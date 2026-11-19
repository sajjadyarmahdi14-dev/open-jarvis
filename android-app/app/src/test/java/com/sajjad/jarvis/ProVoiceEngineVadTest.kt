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
}
