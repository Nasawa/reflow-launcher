package dev.nasawa.configlauncher.ui

import android.appwidget.AppWidgetProviderInfo
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.hosted.WidgetRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** #302 ux.md §16.1: the picker's grouping and search, pure for tests. */
object WidgetPickerModel {
    data class Group(val pkg: String, val app: String, val rows: List<WidgetRuntime.Pick>)

    /** Grouped by app (app label order), rows by label; [query] matches the widget or the app label. */
    fun groups(picks: List<WidgetRuntime.Pick>, query: String): List<Group> {
        val q = query.trim().lowercase()
        return picks.filter { q.isEmpty() || q in it.label.lowercase() || q in it.app.lowercase() }
            .groupBy { it.pkg }
            .map { (pkg, rows) -> Group(pkg, rows.first().app, rows.sortedWith(compareBy({ it.label.lowercase() }, { it.cls }))) }
            .sortedWith(compareBy({ it.app.lowercase() }, { it.pkg }))
    }
}

/**
 * ux.md §16.1 picker: full-screen `Widgets`, `Search widgets`, groups by app (icon + label, sticky), each row a preview
 * (`previewImage`, else `previewLayout` on Android 12+, else a grey frame with the app icon), the label and a `w × h`
 * size chip. Tap picks; long-press opens `App info`. Personal providers only (D3).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WidgetPicker(picks: List<WidgetRuntime.Pick>, onPick: (WidgetRuntime.Pick) -> Unit, onAppInfo: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(picks, query) { WidgetPickerModel.groups(picks, query) }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.widget_picker_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                    TextButton(onDismiss) { Text(stringResource(R.string.close)) }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.widget_search_hint)) }, singleLine = true)
                if (groups.isEmpty()) Text(stringResource(R.string.widget_picker_empty), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxSize()) {
                    for (g in groups) {
                        stickyHeader(key = "h:${g.pkg}") { GroupHeader(g) }
                        items(g.rows, key = { "r:${it.pkg}/${it.cls}" }) { p -> PickRow(p, { onPick(p) }, { onAppInfo(p.pkg) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(g: WidgetPickerModel.Group) {
    val ctx = LocalContext.current
    val icon = remember(g.pkg) { runCatching { ctx.packageManager.getApplicationIcon(g.pkg).toBitmap(72, 72).asImageBitmap() }.getOrNull() }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Image(it, null, Modifier.size(24.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(g.app, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickRow(p: WidgetRuntime.Pick, onPick: () -> Unit, onLong: () -> Unit) {
    val size = stringResource(R.string.widget_size_chip, p.w, p.h)
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onPick, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 120.dp, height = 72.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Preview(p.info, p.pkg)
        }
        Spacer(Modifier.width(16.dp))
        Text(p.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        AssistChip(onClick = onPick, label = { Text(size) })
    }
}

/** Off the main thread, so ~50 rows scroll smoothly; rows recycle their preview by provider. */
@Composable
private fun Preview(info: AppWidgetProviderInfo, pkg: String) {
    val ctx = LocalContext.current
    val image by produceState<ImageBitmap?>(null, info.provider) {
        value = withContext(Dispatchers.IO) {
            runCatching { info.loadPreviewImage(ctx, 0)?.let { d -> d.toBitmap(minOf(d.intrinsicWidth.coerceAtLeast(1), 360), minOf(d.intrinsicHeight.coerceAtLeast(1), 216)).asImageBitmap() } }.getOrNull()
        }
    }
    val img = image
    when {
        img != null -> Image(img, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
        Build.VERSION.SDK_INT >= 31 && info.previewLayout != 0 -> AndroidView(
            factory = { c ->
                runCatching { android.widget.RemoteViews(pkg, info.previewLayout).apply(c, android.widget.FrameLayout(c)) }.getOrNull()
                    ?: android.widget.FrameLayout(c)
            }, modifier = Modifier.fillMaxSize())
        else -> {
            val icon = remember(pkg) { runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
            icon?.let { Image(it, null, Modifier.size(36.dp)) }
        }
    }
}
