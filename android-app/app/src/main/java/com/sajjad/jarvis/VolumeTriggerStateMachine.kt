package com.sajjad.jarvis

/**
 * Pure-Kotlin state machine for Feature 1: Stealth Hardware Volume Trigger.
 *
 * - Double-Tap Volume Down (two ACTION_DOWN events within 380ms): consumes 2nd press,
 *   fires 35ms haptic tick, and triggers Stealth Listening (bypassing wake-word).
 * - Long-Press Volume Down (>750ms hold): configurable Off / Silent Camera Shutter / Quick Voice Memo.
 * - Single normal taps of Volume Up or Volume Down ALWAYS pass through untouched (PASS_THROUGH)
 *   so regular OS volume adjustment works normally.
 */
class VolumeTriggerStateMachine(
    var doubleTapEnabled: Boolean = true,
    var longPressAction: LongPressAction = LongPressAction.OFF
) {
    enum class LongPressAction {
        OFF,
        SHUTTER,
        MEMO;

        companion object {
            fun fromPref(value: String?): LongPressAction {
                return when (value?.trim()?.lowercase()) {
                    "shutter", "camera" -> SHUTTER
                    "memo", "voice_memo", "record" -> MEMO
                    else -> OFF
                }
            }
        }
    }

    enum class VolumeAction {
        PASS_THROUGH,
        CONSUME_ONLY,
        TRIGGER_STEALTH_LISTEN,
        TRIGGER_LONG_PRESS_SHUTTER,
        TRIGGER_LONG_PRESS_MEMO
    }

    companion object {
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1

        const val DOUBLE_TAP_WINDOW_MS = 380L
        const val LONG_PRESS_HOLD_MS = 750L

        const val PREF_STEALTH_DOUBLE_TAP = "stealth_volume_double_tap"
        const val PREF_STEALTH_LONG_PRESS = "stealth_volume_long_press"
    }

    var lastDownMs: Long = 0L
        private set
    private var isDown: Boolean = false
    private var longPressFired: Boolean = false
    private var consumeNextUp: Boolean = false

    fun onKeyEvent(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        eventTimeMs: Long
    ): VolumeAction {
        if (keyCode != KEYCODE_VOLUME_DOWN) {
            return VolumeAction.PASS_THROUGH
        }

        if (action == ACTION_DOWN) {
            if (repeatCount == 0) {
                val elapsedSincePrev = if (lastDownMs > 0L) (eventTimeMs - lastDownMs) else -1L
                if (doubleTapEnabled && elapsedSincePrev in 1L..DOUBLE_TAP_WINDOW_MS) {
                    // Double-Tap Volume Down detected! Consume second press cleanly.
                    lastDownMs = 0L
                    isDown = true
                    longPressFired = false
                    consumeNextUp = true
                    return VolumeAction.TRIGGER_STEALTH_LISTEN
                }

                // First tap: pass through immediately so normal volume adjustment is never broken
                lastDownMs = eventTimeMs
                isDown = true
                longPressFired = false
                consumeNextUp = false
                return VolumeAction.PASS_THROUGH
            } else {
                // Key held down (repeatCount > 0)
                if (consumeNextUp || longPressFired) {
                    return VolumeAction.CONSUME_ONLY
                }
                if (longPressAction != LongPressAction.OFF && isDown && lastDownMs > 0L &&
                    (eventTimeMs - lastDownMs) >= LONG_PRESS_HOLD_MS
                ) {
                    return fireLongPressAction()
                }
                return VolumeAction.PASS_THROUGH
            }
        } else if (action == ACTION_UP) {
            isDown = false
            if (consumeNextUp) {
                consumeNextUp = false
                longPressFired = false
                return VolumeAction.CONSUME_ONLY
            }
            return VolumeAction.PASS_THROUGH
        }

        return VolumeAction.PASS_THROUGH
    }

    /**
     * Called by scheduled handler/coroutine after LONG_PRESS_HOLD_MS to handle devices that
     * do not emit repeatCount > 0 events while the screen is off.
     */
    fun onLongPressTimeout(downTimestampMs: Long): VolumeAction {
        if (!isDown || longPressFired || consumeNextUp || lastDownMs != downTimestampMs) {
            return VolumeAction.PASS_THROUGH
        }
        if (longPressAction == LongPressAction.OFF) {
            return VolumeAction.PASS_THROUGH
        }
        return fireLongPressAction()
    }

    private fun fireLongPressAction(): VolumeAction {
        longPressFired = true
        consumeNextUp = true
        lastDownMs = 0L
        return when (longPressAction) {
            LongPressAction.SHUTTER -> VolumeAction.TRIGGER_LONG_PRESS_SHUTTER
            LongPressAction.MEMO -> VolumeAction.TRIGGER_LONG_PRESS_MEMO
            LongPressAction.OFF -> VolumeAction.PASS_THROUGH
        }
    }

    fun reset() {
        lastDownMs = 0L
        isDown = false
        longPressFired = false
        consumeNextUp = false
    }
}
