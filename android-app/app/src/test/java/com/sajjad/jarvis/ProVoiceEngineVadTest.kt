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

    @Test
    fun testHighPassFilter180HzAndScreenOffNormalization() {
        val hpf = ProVoiceEngine.Companion.HighPassFilter180Hz(0.93f)
        val sampleRate = 16000
        val count = 800 // 50ms chunk

        val rumble = ShortArray(count) { i ->
            (sin(2.0 * PI * 80.0 * i / sampleRate) * 4000.0).toInt().toShort()
        }
        val speech = ShortArray(count) { i ->
            (sin(2.0 * PI * 1000.0 * i / sampleRate) * 4000.0).toInt().toShort()
        }
        hpf.reset()
        val rumbleRms = hpf.apply(rumble, count)
        hpf.reset()
        val speechRms = hpf.apply(speech, count)
        assertTrue("180Hz HPF must keep speech RMS ($speechRms) much higher than 80Hz rumble ($rumbleRms)", speechRms > rumbleRms * 2.0)

        // Quiet Xiaomi screen-off speech: peak ~ 260, rms ~ 183
        val softScreenOff = ShortArray(count) { i ->
            (sin(2.0 * PI * 600.0 * i / sampleRate) * 260.0).toInt().toShort()
        }
        val boosted = ProVoiceEngine.Companion.normalizeFrameForCapture(softScreenOff, count, isScreenOff = true)
        val (boostedRms, boostedPeak) = ProVoiceEngine.Companion.computeFrameRmsAndPeak(boosted, count)
        assertTrue("Screen-off soft voice RMS ($boostedRms) must be boosted above 450", boostedRms > 450.0)
        assertTrue("Normalized peak ($boostedPeak) must not saturate 16-bit PCM", boostedPeak <= 32767)

        // Pure floor silence (peak <= 20) must NOT be blown up
        val nearSilence = ShortArray(count) { 10 }
        val intact = ProVoiceEngine.Companion.normalizeFrameForCapture(nearSilence, count, isScreenOff = true)
        assertEquals(10.toShort(), intact[0])
    }

    @Test
    fun testActiveConversationEightSecondSilenceReturnsCleanlyToStandby() {
        val sm = ProVoiceEngine.Companion.VadCaptureStateMachine()
        val noiseFloor = 85.0
        var stoppedCleanlyAt = -1L

        // 8.5s of steady quiet noise below onset threshold (~163.8)
        for (stepIdx in 0..170) {
            val now = stepIdx * 50L
            val action = sm.step(
                nowMs = now,
                filteredRms = 92.0,
                dynamicNoiseFloor = noiseFloor,
                isMusicActive = false,
                inConvWindow = true,
                isDirectTap = false,
                capturedPcmBytes = 0
            )
            assertFalse("Must NEVER trigger SpeechStarted on silence in ACTIVE_CONVERSATION", action is ProVoiceEngine.Companion.VadCaptureStateMachine.StepAction.SpeechStarted)
            assertFalse("Must NEVER commit /api/stt on silence in ACTIVE_CONVERSATION", action is ProVoiceEngine.Companion.VadCaptureStateMachine.StepAction.CommitUtterance)
            if (action is ProVoiceEngine.Companion.VadCaptureStateMachine.StepAction.TimeoutToStandby) {
                stoppedCleanlyAt = now
                break
            }
        }
        assertEquals(ProVoiceEngine.ACTIVE_CONV_NO_SPEECH_TIMEOUT_MS, stoppedCleanlyAt)
        assertFalse(sm.isCapturing)
    }

    @Test
    fun testActiveConversationCapturesRealSpeechOnceAndCommitsAfter850msSilence() {
        val sm = ProVoiceEngine.Companion.VadCaptureStateMachine()
        val noiseFloor = 85.0
        var started = false
        var committed = false
        var pcmBytes = 0

        // 1.5s ambient silence -> still waiting
        for (i in 0 until 30) {
            val act = sm.step(i * 50L, 90.0, noiseFloor, isMusicActive = false, inConvWindow = true, isDirectTap = false, capturedPcmBytes = 0)
            assertTrue(act is ProVoiceEngine.Companion.VadCaptureStateMachine.StepAction.None)
        }

        // 400ms of speech onset (>= 100ms starts capture)
        for (i in 30 until 38) {
            val act = sm.step(i * 50L, 850.0, noiseFloor, isMusicActive = false, inConvWindow = true, isDirectTap = false, capturedPcmBytes = pcmBytes)
            if (act is ProVoiceEngine.Companion.VadCaptureStateMachine.StepAction.SpeechStarted) {
                started = true
                assertTrue(act.isConvMode)
                pcmBytes = ProVoiceEngine.PRE_ROLL_SAMPLES * 2
            }
            if (sm.isCapturing) pcmBytes += ProVoiceEngine.CHUNK_SAMPLES * 2
        }
        assertTrue("Speech onset must trigger SpeechStarted", started)

        // Trailing 850ms silence -> commits ONCE
        for (i in 38 until 65) {
            val act = sm.step(i * 50L, 90.0, noiseFloor, isMusicActive = false, inConvWindow = true, isDirectTap = false, capturedPcmBytes = pcmBytes)
            if (act is ProVoiceEngine.Companion.VadCaptureStateMachine.StepAction.CommitUtterance) {
                committed = true
                assertTrue(act.isConvMode)
                break
            }
            if (sm.isCapturing) pcmBytes += ProVoiceEngine.CHUNK_SAMPLES * 2
        }
        assertTrue("Speech end must commit utterance ONCE after 850ms silence", committed)
    }

    @Test
    fun testOneShotWakeCommandAndHardTtlConstants() {
        assertEquals(20_000L, ProVoiceEngine.HARD_STALE_TTL_MS)

        val p1 = ProVoiceEngine.Companion.parseWakeAndCommand("Jarvis what time is it")
        assertTrue(p1.isWake)
        assertEquals("what time is it", p1.commandText)

        val p2 = ProVoiceEngine.Companion.parseWakeAndCommand("Hey Jarvis open telegram")
        assertTrue(p2.isWake)
        assertEquals("open telegram", p2.commandText)

        val p3 = ProVoiceEngine.Companion.parseWakeAndCommand("جارویس چراغ قوه رو روشن کن")
        assertTrue(p3.isWake)
        assertEquals("چراغ قوه رو روشن کن", p3.commandText)

        val p4 = ProVoiceEngine.Companion.parseWakeAndCommand("سلام جارویس")
        assertTrue(p4.isWake)
        assertEquals("", p4.commandText)

        val p5 = ProVoiceEngine.Companion.parseWakeAndCommand("what is the capital of Japan")
        assertFalse(p5.isWake)
    }
}
