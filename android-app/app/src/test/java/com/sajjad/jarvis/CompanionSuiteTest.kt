package com.sajjad.jarvis

import com.sajjad.jarvis.router.NlpNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin JUnit verification suite for v2.6.0 Flagship Companion Features:
 * 1. Stealth Hardware Trigger (VolumeTriggerStateMachine)
 * 2. Protocol Escape / Fake Call Parser (FakeCallActivity.parseFakeCallIntent)
 * 3. Auto Morning Briefing Window Guard (DailyBriefingEngine.shouldRunAutoMorningBriefing)
 * 4. Smart Clipboard & Quick Notes Parsers (ClipboardAndNotesEngine)
 * 5. Proactive Watchdog Thresholds & 45m Cooldown (ProactiveWatchdog)
 * 6. Hands-Free Language Coach & Interpreter + Male Voice Selection (LanguageModeManager)
 */
class CompanionSuiteTest {

    @Test
    fun testVolumeTriggerDoubleTapStealthAndSingleTapPassThrough() {
        val sm = VolumeTriggerStateMachine(
            doubleTapEnabled = true,
            longPressAction = VolumeTriggerStateMachine.LongPressAction.OFF
        )

        // Volume Up must ALWAYS pass through
        assertEquals(
            VolumeTriggerStateMachine.VolumeAction.PASS_THROUGH,
            sm.onKeyEvent(VolumeTriggerStateMachine.KEYCODE_VOLUME_UP, VolumeTriggerStateMachine.ACTION_DOWN, 0, 100L)
        )
        assertEquals(
            VolumeTriggerStateMachine.VolumeAction.PASS_THROUGH,
            sm.onKeyEvent(VolumeTriggerStateMachine.KEYCODE_VOLUME_UP, VolumeTriggerStateMachine.ACTION_UP, 0, 150L)
        )

        // Single normal tap of Volume Down passes through untouched
        assertEquals(
            VolumeTriggerStateMachine.VolumeAction.PASS_THROUGH,
            sm.onKeyEvent(VolumeTriggerStateMachine.KEYCODE_VOLUME_DOWN, VolumeTriggerStateMachine.ACTION_DOWN, 0, 1000L)
        )
        assertEquals(
            VolumeTriggerStateMachine.VolumeAction.PASS_THROUGH,
            sm.onKeyEvent(VolumeTriggerStateMachine.KEYCODE_VOLUME_DOWN, VolumeTriggerStateMachine.ACTION_UP, 0, 1060L)
        )

        // Second tap outside 380ms window (1500 - 1000 = 500ms) -> normal pass-through
        assertEquals(
            VolumeTriggerStateMachine.VolumeAction.PASS_THROUGH,
            sm.onKeyEvent(VolumeTriggerStateMachine.KEYCODE_VOLUME_DOWN, VolumeTriggerStateMachine.ACTION_DOWN, 0, 1500L)
        )
        assertEquals(
            VolumeTriggerStateMachine.VolumeAction.PASS_THROUGH,
            sm.onKeyEvent(VolumeTriggerStateMachine.KEYCODE_VOLUME_DOWN, VolumeTriggerStateMachine.ACTION_UP, 0, 1540L)
        )

        // Double-Tap within 250ms (<= 380ms): 2nd DOWN triggers stealth & consumes, 2nd UP is consumed
        assertEquals(
            VolumeTriggerStateMachine.VolumeAction.TRIGGER_STEALTH_LISTEN,
            sm.onKeyEvent(VolumeTriggerStateMachine.KEYCODE_VOLUME_DOWN, VolumeTriggerStateMachine.ACTION_DOWN, 0, 1750L)
        )
        assertEquals(
            VolumeTriggerStateMachine.VolumeAction.CONSUME_ONLY,
            sm.onKeyEvent(VolumeTriggerStateMachine.KEYCODE_VOLUME_DOWN, VolumeTriggerStateMachine.ACTION_UP, 0, 1800L)
        )
    }

    @Test
    fun testVolumeTriggerLongPressShutterAndMemo() {
        val shutterSm = VolumeTriggerStateMachine(
            doubleTapEnabled = true,
            longPressAction = VolumeTriggerStateMachine.LongPressAction.SHUTTER
        )
        assertEquals(
            VolumeTriggerStateMachine.VolumeAction.PASS_THROUGH,
            shutterSm.onKeyEvent(VolumeTriggerStateMachine.KEYCODE_VOLUME_DOWN, VolumeTriggerStateMachine.ACTION_DOWN, 0, 2000L)
        )
        assertEquals(
            VolumeTriggerStateMachine.VolumeAction.TRIGGER_LONG_PRESS_SHUTTER,
            shutterSm.onKeyEvent(VolumeTriggerStateMachine.KEYCODE_VOLUME_DOWN, VolumeTriggerStateMachine.ACTION_DOWN, 1, 2800L)
        )
        assertEquals(
            VolumeTriggerStateMachine.VolumeAction.CONSUME_ONLY,
            shutterSm.onKeyEvent(VolumeTriggerStateMachine.KEYCODE_VOLUME_DOWN, VolumeTriggerStateMachine.ACTION_UP, 0, 2900L)
        )

        // Screen-off timeout fallback when repeat events are not emitted
        val memoSm = VolumeTriggerStateMachine(
            doubleTapEnabled = false,
            longPressAction = VolumeTriggerStateMachine.LongPressAction.MEMO
        )
        memoSm.onKeyEvent(VolumeTriggerStateMachine.KEYCODE_VOLUME_DOWN, VolumeTriggerStateMachine.ACTION_DOWN, 0, 5000L)
        val downTs = memoSm.lastDownMs
        assertEquals(
            VolumeTriggerStateMachine.VolumeAction.TRIGGER_LONG_PRESS_MEMO,
            memoSm.onLongPressTimeout(downTs)
        )
        assertEquals(
            VolumeTriggerStateMachine.VolumeAction.CONSUME_ONLY,
            memoSm.onKeyEvent(VolumeTriggerStateMachine.KEYCODE_VOLUME_DOWN, VolumeTriggerStateMachine.ACTION_UP, 0, 5900L)
        )
    }

    @Test
    fun testFakeCallIntentParser() {
        val faDefault = FakeCallActivity.parseFakeCallIntent("پروتکل فرار", isFa = true)
        assertNotNull(faDefault)
        assertEquals(10, faDefault!!.delaySeconds)
        assertTrue(faDefault.isPersian)

        val faDelay = FakeCallActivity.parseFakeCallIntent("۲۰ ثانیه دیگه زنگ بزن", isFa = true)
        assertNotNull(faDelay)
        assertEquals(20, faDelay!!.delaySeconds)

        val faCaller = FakeCallActivity.parseFakeCallIntent("از طرف علی زنگ بزن", isFa = true)
        assertNotNull(faCaller)
        assertEquals("علی", faCaller!!.callerName)

        val enDelay = FakeCallActivity.parseFakeCallIntent("call me in 15 seconds", isFa = false)
        assertNotNull(enDelay)
        assertEquals(15, enDelay!!.delaySeconds)
        assertFalse(enDelay.isPersian)

        val enCaller = FakeCallActivity.parseFakeCallIntent("fake call from Alex in 30 seconds", isFa = false)
        assertNotNull(enCaller)
        assertEquals("Alex", enCaller!!.callerName)
        assertEquals(30, enCaller.delaySeconds)

        assertNull(FakeCallActivity.parseFakeCallIntent("هوا امروز چطوره؟", isFa = true))
    }

    @Test
    fun testAutoMorningBriefingWindowAndOncePerDayGuard() {
        assertTrue(
            DailyBriefingEngine.shouldRunAutoMorningBriefing(
                enabled = true,
                hourOfDay = 7,
                minute = 30,
                todayDateKey = "2026-11-26",
                lastRunDateKey = null
            )
        )
        // Must NOT fire twice on the same morning
        assertFalse(
            DailyBriefingEngine.shouldRunAutoMorningBriefing(
                enabled = true,
                hourOfDay = 9,
                minute = 0,
                todayDateKey = "2026-11-26",
                lastRunDateKey = "2026-11-26"
            )
        )
        // Must NOT fire before 05:00 or after 12:00
        assertFalse(
            DailyBriefingEngine.shouldRunAutoMorningBriefing(
                enabled = true,
                hourOfDay = 4,
                minute = 59,
                todayDateKey = "2026-11-27",
                lastRunDateKey = "2026-11-26"
            )
        )
        assertFalse(
            DailyBriefingEngine.shouldRunAutoMorningBriefing(
                enabled = true,
                hourOfDay = 12,
                minute = 1,
                todayDateKey = "2026-11-27",
                lastRunDateKey = "2026-11-26"
            )
        )
        // Must NOT fire when disabled
        assertFalse(
            DailyBriefingEngine.shouldRunAutoMorningBriefing(
                enabled = false,
                hourOfDay = 8,
                minute = 0,
                todayDateKey = "2026-11-27",
                lastRunDateKey = null
            )
        )
    }

    @Test
    fun testClipboardAndQuickNotesParsers() {
        assertEquals(
            "فردا به علی زنگ بزنم",
            ClipboardAndNotesEngine.extractQuickNoteText("یادداشت کن فردا به علی زنگ بزنم", isFa = true)
        )
        assertEquals(
            "build offline TTS cache",
            ClipboardAndNotesEngine.extractQuickNoteText("note this down: build offline TTS cache", isFa = false)
        )
        assertNull(ClipboardAndNotesEngine.extractQuickNoteText("سلام جارویس", isFa = true))

        assertTrue(
            ClipboardAndNotesEngine.isReadNotesTrigger(NlpNormalizer.cleanLower("یادداشت‌های امروز رو بخون"))
        )
        assertTrue(
            ClipboardAndNotesEngine.isReadNotesTrigger(NlpNormalizer.cleanLower("read my notes"))
        )

        assertEquals(
            ClipboardAndNotesEngine.ClipboardAction.SEND_TO_VAULT,
            ClipboardAndNotesEngine.detectClipboardAction(NlpNormalizer.cleanLower("کلیپبورد رو بفرست تلگرام"))
        )
        assertEquals(
            ClipboardAndNotesEngine.ClipboardAction.TRANSLATE,
            ClipboardAndNotesEngine.detectClipboardAction(NlpNormalizer.cleanLower("translate clipboard"))
        )
        assertEquals(
            ClipboardAndNotesEngine.ClipboardAction.SUMMARIZE,
            ClipboardAndNotesEngine.detectClipboardAction(NlpNormalizer.cleanLower("متنی که کپی کردم رو خلاصه کن"))
        )
    }

    @Test
    fun testProactiveWatchdogThresholdsAndCooldown() {
        val high90 = ProactiveWatchdog.evaluateBatteryThresholds(
            pct = 92,
            isCharging = true,
            tempCelsius = 33.0f,
            enableHigh = true,
            highThreshold = 90,
            enableLow = true,
            enableTemp = true
        )
        assertTrue(high90.contains(ProactiveWatchdog.ALERT_BATTERY_HIGH))
        assertFalse(high90.contains(ProactiveWatchdog.ALERT_BATTERY_LOW))

        val high100 = ProactiveWatchdog.evaluateBatteryThresholds(
            pct = 95,
            isCharging = true,
            tempCelsius = 33.0f,
            enableHigh = true,
            highThreshold = 100,
            enableLow = true,
            enableTemp = true
        )
        assertFalse(high100.contains(ProactiveWatchdog.ALERT_BATTERY_HIGH))

        val lowAndHot = ProactiveWatchdog.evaluateBatteryThresholds(
            pct = 12,
            isCharging = false,
            tempCelsius = 44.5f,
            enableHigh = true,
            highThreshold = 90,
            enableLow = true,
            enableTemp = true
        )
        assertTrue(lowAndHot.contains(ProactiveWatchdog.ALERT_BATTERY_LOW))
        assertTrue(lowAndHot.contains(ProactiveWatchdog.ALERT_BATTERY_TEMP))

        // 45-minute anti-spam cooldown per alert type
        val tracker = ProactiveWatchdog.AlertCooldownTracker(45 * 60 * 1000L)
        assertTrue(tracker.shouldEmit(ProactiveWatchdog.ALERT_VPS_CPU, 1_000_000L))
        assertFalse(tracker.shouldEmit(ProactiveWatchdog.ALERT_VPS_CPU, 1_000_000L + 10 * 60_000L))
        assertTrue(tracker.shouldEmit(ProactiveWatchdog.ALERT_VPS_RAM, 1_000_000L + 10 * 60_000L))
        assertTrue(tracker.shouldEmit(ProactiveWatchdog.ALERT_VPS_CPU, 1_000_000L + 46 * 60_000L))
    }

    @Test
    fun testLanguageCoachAndInterpreterParsingAndMaleVoices() {
        assertEquals("de-DE-ConradNeural", LanguageModeManager.getVoiceForLanguage("de"))
        assertEquals("en-GB-RyanNeural", LanguageModeManager.getVoiceForLanguage("en"))
        assertEquals("fa-IR-FaridNeural", LanguageModeManager.getVoiceForLanguage("fa"))
        assertEquals("de-DE-ConradNeural", LanguageModeManager.getVoiceForLanguage(null, "Wie heißt du?"))
        assertEquals("fa-IR-FaridNeural", LanguageModeManager.getVoiceForLanguage(null, "سلام قربان"))

        val enterDe = LanguageModeManager.parseLanguageCommand("تمرین آلمانی", isFa = true)
        assertTrue(enterDe is LanguageModeManager.CommandAction.EnterPractice)
        assertEquals("de", (enterDe as LanguageModeManager.CommandAction.EnterPractice).targetLanguage)

        val enterInterpEn = LanguageModeManager.parseLanguageCommand("حالت مترجم همزمان به انگلیسی", isFa = true)
        assertTrue(enterInterpEn is LanguageModeManager.CommandAction.EnterInterpreter)
        assertEquals("en", (enterInterpEn as LanguageModeManager.CommandAction.EnterInterpreter).targetLanguage)

        val exitCmd = LanguageModeManager.parseLanguageCommand("پایان تمرین", isFa = true)
        assertTrue(exitCmd is LanguageModeManager.CommandAction.Exit)
    }
}
