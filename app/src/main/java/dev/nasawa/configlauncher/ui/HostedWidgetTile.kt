package dev.nasawa.configlauncher.ui

import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.hosted.HostedWidgets
import dev.nasawa.configlauncher.hosted.HostedWidgets.State
import dev.nasawa.configlauncher.hosted.LongPressHostView
import dev.nasawa.configlauncher.hosted.WidgetRuntime

/** ux.md §16.5 amber. */
internal val SizeCueAmber = Color(0xFFFFB300)

/** ux.md §16.2 reason line for a placeholder state; null for a live widget. */
/** #301: the reason line also reflects the bind flow: none while a silent bind is pending, `Tap to allow` after a refusal. */
internal fun reasonRes(t: HostedWidgets.Tile): Int? = when (t.hint) {
    dev.nasawa.configlauncher.hosted.BindFlow.Hint.BINDING -> null
    dev.nasawa.configlauncher.hosted.BindFlow.Hint.ALLOW -> R.string.hosted_tap_to_allow
    else -> reasonRes(t.state)
}

internal fun reasonRes(s: State): Int? = when (s) {
    State.BOUND -> null
    State.NEEDS_SETUP -> R.string.hosted_tap_to_set_up
    State.NEEDS_CONFIG -> R.string.hosted_tap_to_finish
    State.NOT_INSTALLED -> R.string.hosted_not_installed
    State.UNSUPPORTED -> R.string.hosted_work_later
    State.FAILED -> R.string.hosted_cant_show
}

/** The §16.5 cue: 2 dp amber outline and a ▲ badge at the top-end corner. */
@Composable
private fun BoxScope.SizeCue() {
    Box(Modifier.matchParentSize().border(2.dp, SizeCueAmber, RoundedCornerShape(12.dp)))
    Text("▲", color = SizeCueAmber, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clearAndSetSemantics { })
}

/**
 * #300 NW-3: one hosted widget in its cells. Bound → the provider's RemoteViews in our [LongPressHostView] (long-press
 * opens [onLong], never the widget's click); otherwise the §16.2 placeholder. [hostWDp] × [hostHDp] is the capped host
 * size (`HomeGeometry.widgetHostSizeDp`), reported to the provider whenever it changes.
 */
@Composable
fun HostedWidgetCell(tile: HostedWidgets.Tile, runtime: WidgetRuntime?, hostWDp: Float, hostHDp: Float,
                     onLong: () -> Unit, onTap: (() -> Unit)?, modifier: Modifier = Modifier,
                     actions: List<CustomAccessibilityAction> = emptyList(),
                     makeView: ((android.content.Context, Int) -> LongPressHostView?)? = runtime?.let { r -> { c, i -> r.host.createView(c, i) } }) {
    val id = tile.appWidgetId
    if (tile.state == State.BOUND && id != null && makeView != null) {
        val cue = if (tile.range != null) stringResource(R.string.hosted_size_cue_a11y) else null
        val long by rememberUpdatedState(onLong)
        Box(modifier.fillMaxSize()) {
            key(id) {
                AndroidView(
                    factory = { ctx ->
                        makeView(ctx, id)?.also { v -> v.onLongPress = { long() } }
                            ?: android.widget.FrameLayout(ctx).also { runtime?.viewMissing(tile.id, id) }
                    },
                    modifier = Modifier.matchParentSize(),
                    update = { v -> (v as? LongPressHostView)?.let {
                        it.sizeCue = cue
                        it.a11yActions = actions.map { a -> a.label to { a.action(); Unit } }
                        reportSize(it, hostWDp, hostHDp)
                    } },
                )
            }
            if (tile.range != null) SizeCue()
        }
    } else HostedPlaceholder(tile, onLong, onTap, modifier, actions)
}

private val reported = java.util.WeakHashMap<LongPressHostView, Pair<Float, Float>>()

/** `updateAppWidgetSize` / `OPTION_APPWIDGET_SIZES` on every size change (never polled). */
internal fun reportSize(v: LongPressHostView, w: Float, h: Float) {
    if (reported[v] == (w to h)) return
    reported[v] = w to h
    runCatching {
        if (Build.VERSION.SDK_INT >= 31) v.updateAppWidgetSize(Bundle(), listOf(SizeF(w, h)))
        else @Suppress("DEPRECATION") v.updateAppWidgetSize(null, w.toInt(), h.toInt(), w.toInt(), h.toInt())
    }
    WidgetRuntime.debug { "size id=${v.appWidgetId} ${w}x${h} dp" }
}

/**
 * ux.md §16.2 placeholder on the shared frame: provider icon (48 dp), label (12 sp, one line), reason (11 sp).
 * TalkBack `<Label>, widget, <reason>` (+ `, size may be wrong`). A state whose tap does nothing yet has no click
 * action, so TalkBack doesn't offer one.
 */
@Composable
fun HostedPlaceholder(tile: HostedWidgets.Tile, onLong: () -> Unit, onTap: (() -> Unit)?, modifier: Modifier = Modifier,
                      actions: List<CustomAccessibilityAction> = emptyList()) {
    val ctx = LocalContext.current
    val label = tile.label ?: tile.id
    val reason = reasonRes(tile)?.let { stringResource(it) } ?: ""
    val cue = if (tile.range != null) stringResource(R.string.hosted_size_cue_a11y) else ""
    val said = (if (reason.isEmpty()) stringResource(R.string.hosted_a11y_plain, label) else stringResource(R.string.hosted_a11y, label, reason)) + cue
    val icon = remember(tile.pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(tile.pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    val keys = remember { CenterKey(onTap = { tap?.invoke() }, onLong = { long() }) }
    Box(
        modifier.fillMaxSize().placeholderFrame()
            .pointerInput(onTap != null) { detectTapGestures(onTap = if (onTap != null) { _ -> tap?.invoke() } else null, onLongPress = { long() }) }
            .then(if (focused) Modifier.focusRing() else Modifier)
            .onKeyEvent(keys::onKey)
            .focusable(interactionSource = focus)
            .clearAndSetSemantics {
                contentDescription = said
                onTap?.let { t -> onClick { t(); true } }
                onLongClick { long(); true }
                if (actions.isNotEmpty()) customActions = actions
            },
        contentAlignment = Alignment.Center,
    ) {
        val p = tile.placement
        val shadowed = androidx.compose.ui.text.TextStyle(shadow = LabelShadow.shadow)
        Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            icon?.let { Image(it, null, Modifier.size(if (p.h == 1) 28.dp else 48.dp)) }
            if (!(p.w == 1 && p.h == 1)) {
                Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = shadowed)
                if (reason.isNotEmpty()) Text(reason, color = Color(0xB3FFFFFF), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = shadowed)
            }
        }
        if (tile.range != null) SizeCue()
    }
}

/**
 * ux.md §16.2 / §16.7.3 long-press sheet for an `appWidget`: label, `Widget · from launcher.json`, the reason, then
 * `Edit layout`, `App info` (when installed), `Remove from home` (#303, when [onRemove] is given) and, for a failed
 * view, `Retry`. `Reconfigure` (#301) for a bound widget whose provider is reconfigurable. `Move to…` and `Resize…`
 * are TalkBack actions on the widget itself (§16.7.3), not sheet rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostedWidgetSheet(tile: HostedWidgets.Tile, installed: Boolean, onEditLayout: () -> Unit, onAppInfo: () -> Unit,
                      onRetry: () -> Unit, onDismiss: () -> Unit, onReconfigure: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(tile.label ?: tile.id, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.widget_sheet_source_file), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            reasonRes(tile)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
            if (tile.range != null) Text(stringResource(R.string.hosted_size_cue_sheet), style = MaterialTheme.typography.bodySmall, color = SizeCueAmber, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            if (tile.state == State.FAILED) TextButton(onRetry, Modifier.fillMaxWidth()) { Text(stringResource(R.string.hosted_retry), Modifier.fillMaxWidth()) }
            onReconfigure?.let { TextButton(it, Modifier.fillMaxWidth()) { Text(stringResource(R.string.hosted_reconfigure), Modifier.fillMaxWidth()) } }
            TextButton(onEditLayout, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_edit_layout), Modifier.fillMaxWidth()) }
            if (installed) TextButton(onAppInfo, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_app_info), Modifier.fillMaxWidth()) }
            onRemove?.let { TextButton(it, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_remove_home), Modifier.fillMaxWidth()) } }
        }
    }
}

/** ux.md §16.3 bind explanation, once per provider package, right before Android's bind dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BindExplainSheet(app: String, onContinue: () -> Unit, onNotNow: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onNotNow, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.bind_explain_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.bind_explain_body, app), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(onContinue, Modifier.fillMaxWidth()) { Text(stringResource(R.string.bind_explain_continue)) }
            TextButton(onNotNow, Modifier.fillMaxWidth()) { Text(stringResource(R.string.bind_explain_not_now)) }
        }
    }
}
