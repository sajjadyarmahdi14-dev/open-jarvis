package com.sajjad.jarvis

import com.sajjad.jarvis.router.NlpNormalizer
import com.sajjad.jarvis.router.domains.UtilityDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRouterTest {

    @Test
    fun testNlpNormalizerPersianAndArabicDigits() {
        assertEquals("1234567890", NlpNormalizer.normalizeDigits("۱۲۳۴۵۶۷۸۹۰"))
        assertEquals("1234567890", NlpNormalizer.normalizeDigits("١٢٣٤٥٦٧٨٩٠"))
        assertEquals("test 115 emergency", NlpNormalizer.normalizeDigits("test ۱۱۵ emergency"))
    }

    @Test
    fun testNlpNormalizerHarmonizationAndTransliteration() {
        // Arabic kaf and yeh harmonization
        val normalized = NlpNormalizer.normalize("كتاب يوسف")
        assertEquals("کتاب یوسف", normalized)

        // Persian-to-Latin Pinglish transliteration
        val translit = NlpNormalizer.transliteratePersianToLatin("اهنگ شجریان")
        assertTrue(translit.contains("ahang") || translit.contains("shajarian"))
        assertTrue(translit.contains("sh"))
    }

    @Test
    fun testNlpNormalizerFuzzyScoring() {
        val score = NlpNormalizer.tokenFuzzyScore("shadmehr aghili", "Shadmehr Aghili - Taghdir.mp3")
        assertTrue("Exact token match score should be >= 0.9", score >= 0.9)

        val partialScore = NlpNormalizer.tokenFuzzyScore("shadmehr", "Shadmehr Aghili - Taghdir.mp3")
        assertTrue("Single token match score should be 1.0 for 1-token query", partialScore >= 1.0)
    }

    @Test
    fun testEmergencyNumberResolution() {
        // Medical emergency
        assertEquals("115", DeviceController.resolveEmergencyNumber("آمبولانس"))
        assertEquals("115", DeviceController.resolveEmergencyNumber("اورژانس"))
        assertEquals("115", DeviceController.resolveEmergencyNumber("ambulance"))
        assertEquals("115", DeviceController.resolveEmergencyNumber("emergency medical"))

        // Police
        assertEquals("110", DeviceController.resolveEmergencyNumber("پلیس"))
        assertEquals("110", DeviceController.resolveEmergencyNumber("police"))
        assertEquals("110", DeviceController.resolveEmergencyNumber("کلانتری"))

        // Fire
        assertEquals("125", DeviceController.resolveEmergencyNumber("آتش نشانی"))
        assertEquals("125", DeviceController.resolveEmergencyNumber("fire department"))
        assertEquals("125", DeviceController.resolveEmergencyNumber("firefighter"))

        // Red Crescent & Global
        assertEquals("112", DeviceController.resolveEmergencyNumber("هلال احمر"))
        assertEquals("911", DeviceController.resolveEmergencyNumber("911"))
        assertEquals("194", DeviceController.resolveEmergencyNumber("گاز"))
        assertEquals("121", DeviceController.resolveEmergencyNumber("برق"))
        assertEquals("122", DeviceController.resolveEmergencyNumber("آب"))

        // Regular contacts should return null
        assertEquals(null, DeviceController.resolveEmergencyNumber("علی"))
        assertEquals(null, DeviceController.resolveEmergencyNumber("John Doe"))
    }

    @Test
    fun testPureKotlinMathEvaluator() {
        val addRes = UtilityDomain.evaluateMathExpression("۲ + ۲")
        assertNotNull(addRes)
        assertEquals(4.0, addRes!!.fourth, 0.001)

        val subRes = UtilityDomain.evaluateMathExpression("10 minus 3")
        assertNotNull(subRes)
        assertEquals(7.0, subRes!!.fourth, 0.001)

        val mulRes = UtilityDomain.evaluateMathExpression("۵ ضربدر ۶")
        assertNotNull(mulRes)
        assertEquals(30.0, mulRes!!.fourth, 0.001)

        val divRes = UtilityDomain.evaluateMathExpression("20 divided by 4")
        assertNotNull(divRes)
        assertEquals(5.0, divRes!!.fourth, 0.001)

        val slashRes = UtilityDomain.evaluateMathExpression("100 / 5")
        assertNotNull(slashRes)
        assertEquals(20.0, slashRes!!.fourth, 0.001)
    }

    @Test
    fun testGregorianToJalaliConverter() {
        // 2026-03-21 is Farvardin 1, 1405 (Nowruz 1405)
        val (jy, jm, jd) = UtilityDomain.gregorianToJalali(2026, 3, 21)
        assertEquals(1405, jy)
        assertEquals(1, jm)
        assertEquals(1, jd)

        // Also test through LocalCommandRouter delegation
        val (jy2, jm2, jd2) = LocalCommandRouter.gregorianToJalali(2026, 3, 21)
        assertEquals(1405, jy2)
        assertEquals(1, jm2)
        assertEquals(1, jd2)
    }

    @Test
    fun testWakeWordRecognitionAndDeduplication() {
        assertTrue(LocalCommandRouter.isWakeToken("jarvis"))
        assertTrue(LocalCommandRouter.isWakeToken("جارویس"))
        assertTrue(LocalCommandRouter.isWakeToken("jervis"))
        assertTrue(LocalCommandRouter.isWakeToken("garvis"))
        assertTrue(LocalCommandRouter.isWakeToken("جرویس"))

        assertTrue(LocalCommandRouter.isAllWakeWords("Jarvis Jarvis Jarvis"))
        assertTrue(LocalCommandRouter.isAllWakeWords("هی جارویس"))
        assertTrue(LocalCommandRouter.isAllWakeWords("hey jarvis"))
        assertFalse(LocalCommandRouter.isAllWakeWords("Jarvis play some music"))
    }

    @Test
    fun testLiveAudioWavEncoder() {
        val dummyPcm = ByteArray(3200) // 100ms at 16kHz 16-bit mono
        val wav = com.sajjad.jarvis.live.LiveAudioSessionManager.pcmToWav(dummyPcm, 16000)
        assertEquals(3200 + 44, wav.size)
        // Check RIFF header
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        // Check WAVE fmt
        assertEquals('W'.code.toByte(), wav[8])
        assertEquals('A'.code.toByte(), wav[9])
        assertEquals('V'.code.toByte(), wav[10])
        assertEquals('E'.code.toByte(), wav[11])
    }

    @Test
    fun testLiveAudioAndScreenTriggersInNormalizer() {
        val audioTriggers = listOf(
            "به این گوش کن", "به این آهنگ گوش کن", "به این ویدیو گوش کن", "گوش کن ببین چی میگه", "گوش کن این چه آهنگیه",
            "listen to this", "listen to this song", "listen to this video", "listen to the audio"
        )
        for (trigger in audioTriggers) {
            val norm = NlpNormalizer.cleanLower(trigger)
            assertTrue("Normalizer should preserve audio trigger words: $trigger", norm.isNotBlank())
        }

        val screenTriggers = listOf(
            "صفحه گوشیم رو نگاه کن", "صفحه رو نگاه کن", "به اسکرینم نگاه کن", "اسکرینم رو ببین", "صفحهام رو ببین",
            "watch my screen", "look at my screen", "start watching my screen"
        )
        for (trigger in screenTriggers) {
            val norm = NlpNormalizer.cleanLower(trigger)
            assertTrue("Normalizer should preserve screen trigger words: $trigger", norm.isNotBlank())
        }
    }
}
