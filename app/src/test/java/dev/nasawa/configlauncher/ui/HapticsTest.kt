package dev.nasawa.configlauncher.ui

import android.view.HapticFeedbackConstants
import dev.nasawa.configlauncher.ui.Haptics.Plan
import org.junit.Assert.assertEquals
import org.junit.Test

/** dev1 fix: which path the per-cell tick takes (the buzz itself can only be felt on a device). */
class HapticsTest {
    @Test fun predefinedTickAsATouchVibrationWhereSupported() {
        assertEquals(Plan.Tick(touchUsage = true), Haptics.plan(35, viewEnabled = true, settingOn = true, tickSupported = true))
        assertEquals(Plan.Tick(touchUsage = true), Haptics.plan(33, viewEnabled = true, settingOn = null, tickSupported = true))
        assertEquals(Plan.Tick(touchUsage = false), Haptics.plan(29, viewEnabled = true, settingOn = true, tickSupported = true))
    }

    @Test fun viewFeedbackWithFallbacksOtherwise() {
        assertEquals(Plan.ViewFeedback(HapticFeedbackConstants.SEGMENT_TICK, HapticFeedbackConstants.VIRTUAL_KEY),
            Haptics.plan(34, viewEnabled = true, settingOn = true, tickSupported = false))
        assertEquals(Plan.ViewFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.VIRTUAL_KEY),
            Haptics.plan(31, viewEnabled = true, settingOn = true, tickSupported = false))
    }

    @Test fun theSystemSettingAndTheViewFlagStillDecide() {
        assertEquals(Plan.None, Haptics.plan(35, viewEnabled = true, settingOn = false, tickSupported = true))
        assertEquals(Plan.None, Haptics.plan(29, viewEnabled = true, settingOn = false, tickSupported = false))
        assertEquals(Plan.None, Haptics.plan(35, viewEnabled = false, settingOn = true, tickSupported = true))
    }
}
