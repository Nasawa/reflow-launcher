package dev.nasawa.configlauncher.ui

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView

/**
 * dev1 fix: the one-buzz-per-cell tick for widget resize and move (and app-tile drags), which gave nothing on the
 * S25 Ultra. One UI quietly drops `View.performHapticFeedback(CLOCK_TICK / TEXT_HANDLE_MOVE)` (Compose's
 * `TextHandleMove` is the latter), so where the vibrator has the predefined tick we play that, as a *touch*
 * vibration (API 33+ `USAGE_TOUCH`, so the system also applies the touch-feedback intensity), and only while the
 * system touch-feedback setting (`HAPTIC_FEEDBACK_ENABLED`) is on. Otherwise the View path: `SEGMENT_TICK` (API 34+, else
 * `CLOCK_TICK`), then `VIRTUAL_KEY` if that reports nothing done. A view with haptics turned off gets none.
 */
object Haptics {
    sealed interface Plan {
        data object None : Plan
        /** Predefined `EFFECT_TICK`; [touchUsage] = API 33+ `VibrationAttributes.USAGE_TOUCH`. */
        data class Tick(val touchUsage: Boolean) : Plan
        /** `View.performHapticFeedback([type])`, then [fallback] when it returns false. */
        data class ViewFeedback(val type: Int, val fallback: Int) : Plan
    }

    /** Pure choice (JVM-tested). [settingOn] null = unreadable (treated as on, the platform default). */
    @android.annotation.SuppressLint("InlinedApi")   // SEGMENT_TICK only when [sdk] >= 34
    fun plan(sdk: Int, viewEnabled: Boolean, settingOn: Boolean?, tickSupported: Boolean): Plan = when {
        !viewEnabled || settingOn == false -> Plan.None
        tickSupported -> Plan.Tick(touchUsage = sdk >= 33)
        else -> Plan.ViewFeedback(if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK,
            HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun vibrator(ctx: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
    }.getOrNull()?.takeIf { it.hasVibrator() }

    /** API 30+ can ask; on API 29 we can't tell, so the View path is used. */
    private fun tickSupported(v: Vibrator?): Boolean = v != null && Build.VERSION.SDK_INT >= 30 &&
        v.areEffectsSupported(VibrationEffect.EFFECT_TICK).firstOrNull() != Vibrator.VIBRATION_EFFECT_SUPPORT_NO

    private fun setting(ctx: Context): Boolean? =
        runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED) != 0 }.getOrNull()

    /** One cell step. */
    fun cellTick(view: View) {
        val ctx = view.context
        val v = vibrator(ctx)
        when (val p = plan(Build.VERSION.SDK_INT, view.isHapticFeedbackEnabled, setting(ctx), tickSupported(v))) {
            Plan.None -> {}
            is Plan.Tick -> runCatching {
                val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                if (p.touchUsage && Build.VERSION.SDK_INT >= 33) v!!.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
                else @Suppress("DEPRECATION") v!!.vibrate(effect, android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
            is Plan.ViewFeedback -> if (!view.performHapticFeedback(p.type)) view.performHapticFeedback(p.fallback)
        }
    }

    /**
     * The buzz when a long-press menu or sheet opens. One `LONG_PRESS` through the view, default flags, so the
     * system touch-feedback setting decides (same path as a drag-start tick, never forced). Tests may set [menuOpenHook].
     */
    fun menuOpen(view: View) {
        (menuOpenHook ?: defaultMenuOpen)(view)
    }

    private val defaultMenuOpen: (View) -> Unit = { it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }

    /** Tests only: replace the view call, then clear. */
    @Volatile internal var menuOpenHook: ((View) -> Unit)? = null
}

/** Fires [Haptics.menuOpen] once, when this long-press sheet first enters composition. */
@Composable
internal fun MenuOpenHaptic() {
    val view = LocalView.current
    LaunchedEffect(Unit) { Haptics.menuOpen(view) }
}
