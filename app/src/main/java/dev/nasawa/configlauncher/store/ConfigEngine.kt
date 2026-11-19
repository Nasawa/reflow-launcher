package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.Elements

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.Limits
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.Edits
import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileNotFoundException
import java.io.IOException

enum class Origin { FILE, LAST_GOOD, DEFAULT }

/** Typed problems so the UI shows the right banner (docs/ux.md §6.1, ARCHITECTURE.md §17 AQ-8). */
sealed interface Issue {
    val details: List<String>
    /** B1: unreadable (syntax, encoding, duplicate key, too deep, over the byte cap). */
    data class ParseError(override val details: List<String>) : Issue
    /** B2: parsed but failed validation. */
    data class Invalid(override val details: List<String>) : Issue
    /** B3: folder grant missing or the tree itself is unreachable. Action: Reconnect only. */
    data class AccessLost(val message: String) : Issue { override val details get() = listOf(message) }
    /** B4: folder fine, launcher.json missing. Actions: Create from current layout, Reconnect. Never auto-created. */
    data object Missing : Issue { override val details get() = emptyList<String>() }
    /** B5: our write failed (or would exceed the cap); the edit stays in memory. [title] is the banner text; [message] the technical detail. */
    data class SaveFailed(val message: String, val title: Msg = Msg(dev.nasawa.configlauncher.R.string.b5_save_failed)) : Issue {
        override val details get() = listOf(message)
        companion object {
            fun of(e: Exception, title: Int = dev.nasawa.configlauncher.R.string.b5_save_failed) = SaveFailed(e.reason(),
                if (e is TooLargeException) Msg(dev.nasawa.configlauncher.R.string.b5_too_large, e.limitMb) else Msg(title))
        }
    }
    /** B7: schemaVersion newer than supported. */
    data class TooNew(val message: String) : Issue { override val details get() = listOf(message) }
    /** Transient read failure (provider unavailable); shown as B1-style "can't read". */
    data class ReadFailed(val message: String) : Issue { override val details get() = listOf(message) }
}

/**
 * #75: the RELOAD broadcast's reply. [code] is the broadcast result code, [word] its result data.
 * REJECTED: the current file isn't valid (details in status.json). UNAVAILABLE: the folder or file can't be read.
 * BUSY: a drag, B6 (conflict), or a phone edit still waiting for its debounced save, so the file wasn't read; a failed
 * save (B5) is SAVE_FAILED or RELOADED instead (#334). TIMEOUT: no answer within 8 s.
 * SAVE_FAILED (#334): the phone's own save had failed (B5); RELOAD read the file and tried that save again (merging onto
 * the file), and it failed again. status.json `warnings` says why; retry later.
 */
enum class ReloadOutcome(val code: Int, val word: String) {
    UNCHANGED(0, "unchanged"), RELOADED(1, "reloaded"), REJECTED(2, "rejected"),
    UNAVAILABLE(3, "unavailable"), BUSY(4, "busy"), TIMEOUT(5, "timeout"), SAVE_FAILED(6, "save_failed"),
}

/** Thrown by sources when the grant/tree is gone (B3) rather than the file (B4). */
class AccessLostException(message: String) : IOException(message)

data class LoadState(
    val config: LauncherConfig,
    val origin: Origin,
    val sourceName: String,
    /** Hash of the file version [config] was derived from (the write-back base hash). */
    val baseHash: String? = null,
    val issue: Issue? = null,
    /** Local edits not yet written (optimistic; already shown). */
    val pendingEdits: Int = 0,
    /** launcher.json changed under pending edits (banner B6: Use file / Keep mine). */
    val conflict: Boolean = false,
    /** One-shot snackbar text, cleared by [ConfigEngine.consumeNotice]. */
    val notice: Msg? = null,
    /** Whether the source has a folder grant that "Reconnect" can re-pick (false for app storage). */
    val reconnectable: Boolean = true,
    /** §18.2: an automatic merge just happened; the snackbar offers Undo ([ConfigEngine.undoMerge]). */
    val mergeUndo: Boolean = false,
    /** ux.md §9.1 merge snackbar (formatted by the UI from strings.xml); cleared by [ConfigEngine.consumeMerge]. */
    val merge: MergeNotice? = null,
) {
    /** Problem lines for the detail view (kept for callers/tests that only need text). */
    val errors: List<String> get() = when (issue) { null, Issue.Missing -> emptyList(); else -> issue.details }
}

/**
 * Inbound binding (ARCHITECTURE.md §4): poll → stamp change (or every 5th poll / resume: hash anyway,
 * §17 Q5) → capped read → hash guard → 300 ms stability re-check → parse/migrate/validate →
 * atomic swap into [state] → last-good. Pure Kotlin; Android wiring lives in ConfigStore.
 */
private val CANCELLED = Msg(dev.nasawa.configlauncher.R.string.undo_cancelled)

/** #317: an agent kept writing launcher.json through every attempt of one save (each attempt merged onto its newer file). */
private const val RACE_RETRIES = 3
private const val CHANGING = "launcher.json kept changing while saving; your change is kept here. Retry to merge it into the newest file."

class ConfigEngine(
    @Volatile var source: ConfigSource,
    private val lastGood: LastGoodStore,
    private val stabilityMs: Long = 300,
    private val scope: CoroutineScope? = null,
    private val debounceMs: Long = 750,
    private val clock: () -> Long = System::currentTimeMillis,
    /** #138: device-local widget approvals; null in tests that don't need them (no `consent` map then). */
    val consent: dev.nasawa.configlauncher.consent.ConsentStore? = null,
    /** #299: device-local appWidget bindings, settled after every successful load (never after a B2). */
    val bindings: dev.nasawa.configlauncher.hosted.WidgetBindings? = null,
    /**
     * #257: debug (default) keeps consent and live-card behaviour. Release builds pass false so cards
     * get a placeholder tile and a "not shown in this version" warning. Approvals in [consent] are
     * still settled and never cleared. 1.1 removes this when #140 and #141 land.
     */
    private val cardsLive: Boolean = dev.nasawa.configlauncher.BuildConfig.CARDS_LIVE,
) {
    private val pending = mutableListOf<Edit>()

    /**
     * #314 review: callers that must know whether their edit reached the file ([editKept]). [onKept] runs once, inside
     * the write path, after the replay decided the edit is kept and **before** the write (so before the settle in it);
     * [done] completes true once that write succeeded, false when the edit was skipped or dropped (B6 Use file).
     */
    private class Waiter(val edit: Edit, val onKept: () -> Unit, val onUnkept: () -> Unit, val done: kotlinx.coroutines.CompletableDeferred<Boolean>) { var kept = false }
    private val waiters = mutableListOf<Waiter>()

    /**
     * #314 GO follow-up: the write that [keepWaiters] ran onKept for failed, so nothing of it reached the file. Undo
     * that ([Waiter.onUnkept], e.g. set the holds aside again) and wait for the next write: its replay decides afresh,
     * so a Retry after the agent re-added the element skips the edit with the holds still aside.
     */
    private fun unkeepWaiters() {
        for (w in waiters) if (w.kept) { w.kept = false; runCatching { w.onUnkept() } }
    }

    /** Before a write of [ops]' outcome: run onKept for the kept ones ([skipped] by identity). */
    private fun keepWaiters(skipped: List<Edit>) {
        for (w in waiters) if (!w.kept && skipped.none { it === w.edit }) { w.kept = true; runCatching { w.onKept() } }
    }

    /**
     * #319 review: skipped edits whose [editKept] caller reports the outcome itself (ElementUndo's "already back on home")
     * aren't counted again in the merge snackbar. Call before [finishWaiters] (it clears [waiters]).
     */
    private fun unreportedSkips(skipped: List<dev.nasawa.configlauncher.model.Skip>) =
        skipped.filter { s -> waiters.none { it.edit === s.edit } }

    /** After the write succeeded (or the pending ops were dropped: [dropped]). */
    private fun finishWaiters(skipped: List<Edit>, dropped: Boolean = false) {
        for (w in waiters) w.done.complete(!dropped && w.kept && skipped.none { it === w.edit })
        waiters.clear()
    }
    private var flushJob: Job? = null
    /** Hash of the file bytes that last failed to parse/validate (for the OQ-15 broken-copy path). */
    private var invalidHash: String? = null
    /** Bytes of the file that last failed, kept only so the detail view can locate errors on demand. */
    @Volatile private var invalidBytes: ByteArray? = null
    /** §18.2 `Ask before merging` (default off): when true, conflicts show the B6 prompt instead of auto-merging. */
    @Volatile var askBeforeMerge = false
    /** §18.2 Undo record: pre-merge file bytes, the hash the merge wrote, and the items it touched. */
    private var undoBytes: ByteArray? = null
    private var mergedHash: String? = null
    private var undoTouched: List<dev.nasawa.configlauncher.model.UndoMerge.Touched> = emptyList()

    /** #300: hosted-widget states and phone-only size warnings for status.json; null in tests that don't need them. */
    @Volatile var appWidgets: dev.nasawa.configlauncher.hosted.AppWidgetStatus? = null

    /** #300: a provider was installed or removed, a binding changed or the grid's cell size changed: refresh status.json. */
    suspend fun appWidgetsChanged() = mutex.withLock { emitStatus() }

    /** #300 debug builds: a `.configlauncher/` sidecar for the hands-on session. Best effort. */
    suspend fun writeDebugSidecar(name: String, bytes: ByteArray) = mutex.withLock { runCatching { source.writeSidecar("", name, bytes) } }

    /** While true (drag in progress) inbound reloads are held until drop (OQ-24). */
    @Volatile var dragging = false
    private val mutex = Mutex()
    private var lastStamp: Stamp? = null
    private var pollCount = 0
    /** Hashes we wrote ourselves (last 4); seeing one of them again is an echo, not an edit (§5). */
    private val selfWrites = ArrayDeque<String>()

    /** Key of the last status.json written; identical results are not rewritten (no churn, no loop). */
    private var lastStatusKey: String? = null
    /** Last load result reported in status.json, so backup/conflict changes can re-emit it. */
    private var statusResult: Triple<String, List<String>, String>? = null
    /** #55: revision of the file at the status hash, when it was applied (null when rejected). */
    private var statusRevision: Long? = null
    private var statusWarnings: List<String> = emptyList()
    /** #125: the phone's current theme (dark?, token → `#RRGGBB`) for the phone-only contrast check; null until the UI sets it. */
    /** #125: the loaded config at the status hash, for [phoneWarnings] (null when rejected). */
    private var statusConfig: LauncherConfig? = null
    private var phoneTheme: Pair<Boolean, Map<String, String>>? = null
    /** §17 Q4: last backup-copy failure (null once a later save succeeds). */
    private var backupError: String? = null
    /** OQ-21: sync/merge leftovers next to launcher.json. */
    private var conflictFiles: List<String> = emptyList()
    /**
     * #334 / #321: why the phone's last save failed while its edit is still pending (B5), for status.json `warnings`;
     * null once a write succeeds or the edits are dropped. Without it status.json kept the last `ok` receipt.
     */
    private var unsaved: String? = null
    /** #334: launcher.json's stamp right after that failed save; a poll retries the save only once the file changed. */
    private var failedStamp: Stamp? = null

    private val _state = MutableStateFlow(initial().copy(reconnectable = source.reconnectable))
    val state: StateFlow<LoadState> = _state.asStateFlow()

    private fun initial(): LoadState {
        val bytes = runCatching { lastGood.load() }.getOrNull()
        // #120: lenient about bare tokens only here, so a last-good saved by an older build still loads.
        val parsed = bytes?.let { ConfigCodec.decodeLastGood(it) as? ParseResult.Ok }
        return if (parsed != null) LoadState(parsed.config, Origin.LAST_GOOD, source.displayName, ConfigCodec.sha256(bytes))
        else LoadState(DefaultLayout.config, Origin.DEFAULT, source.displayName)
    }

    /** What a candidate source holds, for the §18.2 "move layout" dialog. */
    sealed interface Inspection {
        data object Empty : Inspection
        data class Valid(val pages: Int, val items: Int) : Inspection
        data class Invalid(val errors: List<String>) : Inspection
        /** #74: the folder couldn't be read. Its own outcome: never treated as Empty, never written over. */
        data class Unreadable(val reason: String, val accessLost: Boolean) : Inspection
    }

    fun inspect(src: ConfigSource): Inspection {
        val bytes = try { src.read(Limits.MAX_BYTES) } catch (e: Exception) {
            return Inspection.Unreadable(e.reason(), e is AccessLostException)
        } ?: return Inspection.Empty
        return when (val r = ConfigCodec.decode(bytes)) {
            is ParseResult.Ok -> Inspection.Valid(r.config.pages.size, r.config.pages.sumOf { it.items.size } + r.config.dock.orEmpty().size)
            is ParseResult.Invalid -> Inspection.Invalid(r.errors)
        }
    }

    /**
     * §18.2 move the layout to [target]. [useMine]: our current layout is written there (an existing
     * target file is copied to `.configlauncher/backup/` first). Otherwise the target's file is used
     * and our current layout is kept as a history entry. An empty target always gets our layout.
     */
    suspend fun moveTo(target: ConfigSource, useMine: Boolean): Boolean = mutex.withLock {
        target.clearSuppression() // #251: picking the folder again retries the hidden-name creates ([SafDirs])
        try { moveLocked(target, useMine) } finally { publishSuppression() }
    }

    private fun moveLocked(target: ConfigSource, useMine: Boolean): Boolean {
        val existing = try { target.read(Limits.MAX_BYTES) } catch (e: Exception) {
            _state.update { it.copy(notice = Msg(dev.nasawa.configlauncher.R.string.folder_read_failed, e.reason())) }; return false
        }
        val mine = existing == null || useMine
        val adopted = if (mine) null else when (val r = ConfigCodec.decode(existing!!)) {
            is ParseResult.Ok -> r.config
            // Grace review: never adopt an invalid folder file. Nothing changes; the user can replace it or fix it.
            is ParseResult.Invalid -> { _state.update { it.copy(notice = Msg(dev.nasawa.configlauncher.R.string.folder_invalid)) }; return false }
        }
        if (existing != null && useMine) {
            // Folder: agent-visible backup first; if that fails, don't overwrite. App-only (§18.5): private history.
            try { target.writeSidecar("backup", StatusJson.brokenFileName(clock()), existing) }
            catch (_: UnsupportedOperationException) { runCatching { lastGood.snapshot(existing) } }
            catch (e: Exception) { _state.update { it.copy(notice = Msg(dev.nasawa.configlauncher.R.string.folder_backup_failed, e.reason())) }; return false }
        }
        val oldSource = source; val oldState = _state.value; val oldStamp = lastStamp
        val oldStatus = Triple(statusResult, lastStatusKey, conflictFiles); val oldWarnings = _warnings.value
        if (adopted != null) runCatching { lastGood.snapshot(ConfigCodec.encodeBytes(_state.value.config)) }
        // #171 (Grace #168 L1): the old folder's Warnings list goes with its status; the new one's comes with its own.
        source = target; lastStamp = null; lastStatusKey = null; statusResult = null; conflictFiles = emptyList(); _warnings.value = emptyList()
        return try {
            if (adopted != null) {
                val hash = ConfigCodec.sha256(existing!!)
                lastStamp = target.stamp()
                _state.value = LoadState(adopted, Origin.FILE, target.displayName, hash, reconnectable = target.reconnectable)
                // #103: a fresh receipt for the adopted file, so status.json isn't left from an earlier install.
                writeStatus("ok", emptyList(), hash, adopted.revision, Elements.warnings(adopted), adopted)
                saveLastGood(existing)
            } else writeLocked(oldState.config, expectOf(existing))
            true
        } catch (e: Exception) {
            // Roll back: the old source stays in charge and nothing is reported as moved.
            source = oldSource; lastStamp = oldStamp
            // #173 L3: the old folder's status and Warnings list come straight back, not on the next poll.
            statusResult = oldStatus.first; lastStatusKey = oldStatus.second; conflictFiles = oldStatus.third; _warnings.value = oldWarnings
            _state.value = oldState.copy(notice = Msg(dev.nasawa.configlauncher.R.string.move_failed, e.reason()))
            false
        }
    }

    suspend fun switchSource(newSource: ConfigSource) {
        mutex.withLock {
            newSource.clearSuppression() // #251: Reconnect is a re-pick too ([SafDirs])
            source = newSource
            lastStamp = null
            lastStatusKey = null; statusResult = null; conflictFiles = emptyList(); _warnings.value = emptyList()
            _state.update { it.copy(sourceName = newSource.displayName, issue = null, reconnectable = newSource.reconnectable) }
        }
        poll(force = true)
    }

    /**
     * Check the source once. [force] (resume, ContentObserver, manual reload) always reads and hashes.
     * Returns true when a new model was published.
     */
    suspend fun poll(force: Boolean = false): Boolean = mutex.withLock { try { pollLocked(force) } finally { publishSuppression() } }

    /**
     * #55 adb reload: the same pipeline as ⋮ → Reload, then make sure status.json reflects it
     * (rewritten even if unchanged, so its `time` is a receipt for this request).
     */
    suspend fun reloadForAgent(): ReloadOutcome = mutex.withLock {
        // #251 (Archimedes): RELOAD is the agent's "try again", so every tree's hidden-name suppression is cleared.
        SafDirs.clearAll()
        try { reloadLocked() } finally { publishSuppression() }
    }

    private suspend fun reloadLocked(): ReloadOutcome {
        // pollLocked skips the read while an edit, drag or conflict is pending: say so instead of "unchanged".
        // #334: with B5 up (the save failed) the poll below retries that save, merging onto the file: not busy.
        val retried = !dragging && !_state.value.conflict && pending.isNotEmpty() && _state.value.issue is Issue.SaveFailed
        val busy = dragging || _state.value.conflict || (pending.isNotEmpty() && !retried)
        val changed = pollLocked(force = true, retrySave = true)
        lastStatusKey = null; emitStatus() // also reports a suppression that recurred (noteSuppression)
        return when {
            busy -> ReloadOutcome.BUSY
            retried -> when {
                changed -> ReloadOutcome.RELOADED
                _state.value.conflict -> ReloadOutcome.BUSY // B6: the user decides (Ask before merging, or a torn file)
                else -> ReloadOutcome.SAVE_FAILED
            }
            changed -> ReloadOutcome.RELOADED
            else -> when (_state.value.issue) {
                is Issue.ParseError, is Issue.Invalid, is Issue.TooNew -> ReloadOutcome.REJECTED
                is Issue.AccessLost, Issue.Missing, is Issue.ReadFailed -> ReloadOutcome.UNAVAILABLE
                else -> ReloadOutcome.UNCHANGED
            }
        }
    }

    private suspend fun pollLocked(force: Boolean, retrySave: Boolean = false): Boolean {
        if (dragging || _state.value.conflict) return false
        if (pending.isNotEmpty()) {
            // A save is coming (debounced) or B6 waits on the user: the file is read then. But with B5 up the save already
            // failed and nothing would run it again (#334: polls and RELOAD skipped the file for good, agents' writes
            // were ignored). So RELOAD ([retrySave]) and any poll that sees the file changed since that failure (an
            // agent's write) retry it: the flush re-reads the file and merges the pending edits onto the newer one.
            // Not on every forced poll: an observer that fires for our own attempt's temp files would loop.
            if (_state.value.issue !is Issue.SaveFailed) return false
            if (!retrySave && runCatching { source.stamp() }.getOrNull() == failedStamp) return false
            return flushLocked()
        }
        val src = source
        val hashAnyway = force || (++pollCount % 5 == 0)
        val stamp = try { src.stamp() } catch (e: Exception) { return fail(classify(e, src)) }
        if (stamp == null) {
            lastStamp = null
            return fail(Issue.Missing)
        }
        if (hashAnyway) refreshConflicts(src)
        if (!hashAnyway && stamp == lastStamp) return false
        val bytes = try { src.read(Limits.MAX_BYTES) } catch (e: Exception) { return fail(classify(e, src)) }
            ?: return fail(Issue.Missing)
        val hash = ConfigCodec.sha256(bytes)
        if (hash == _state.value.baseHash && _state.value.origin == Origin.FILE || hash in selfWrites) {
            lastStamp = stamp
            if (_state.value.issue != null) _state.update { it.copy(issue = null) }
            // #103: first look at a newly connected folder holding the file we already show: still write a receipt.
            if (statusResult == null) writeStatus("ok", emptyList(), hash, _state.value.config.revision, Elements.warnings(_state.value.config), _state.value.config)
            return false
        }
        if (stabilityMs > 0) {
            delay(stabilityMs)
            val again = try { src.stamp() } catch (_: Exception) { null }
            if (again != stamp) return false // still being written; next poll retries
        }
        lastStamp = stamp
        return when (val r = ConfigCodec.decode(bytes)) {
            is ParseResult.Invalid -> {
                invalidHash = hash; invalidBytes = bytes
                val issue = classifyParse(r.errors)
                writeStatus(StatusJson.resultOf(issue), r.errors, hash)
                fail(issue)
            }
            is ParseResult.Ok -> {
                invalidHash = null; invalidBytes = null
                writeStatus("ok", emptyList(), hash, r.config.revision, Elements.warnings(r.config), r.config)
                // An external reload doesn't expire Undo (§18.2); it takes the "changed" path.
                _state.value = LoadState(r.config, Origin.FILE, src.displayName, hash, reconnectable = src.reconnectable,
                    mergeUndo = undoBytes != null, merge = _state.value.merge.takeIf { undoBytes != null })
                saveLastGood(bytes)
                true
            }
        }
    }

    /**
     * Outbound binding (§5): apply [edit] to the shown model now; write after [debounceMs] of quiet.
     * Edits made while B6 is up join the queue (§17 Q8). Returns false for a no-op edit.
     */
    /**
     * #314 review: like [edit], but suspends until the write that carries it: true only when the file kept it (a merge
     * onto a newer file can skip it, e.g. an Undo whose element the agent already re-added). [onKept] runs in the write
     * path before the write; see [Waiter].
     */
    suspend fun editKept(edit: Edit, onUnkept: () -> Unit = {}, onKept: () -> Unit): Boolean {
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        mutex.withLock {
            val r = Edits.apply(_state.value.config, edit)
            if (!r.applied) return false
            pending += edit
            waiters += Waiter(edit, onKept, onUnkept, done)
            _state.update { it.copy(config = r.config, pendingEdits = pending.size) }
        }
        if (scope == null) flush() else scheduleFlush()
        return done.await()
    }

    suspend fun edit(edit: Edit): Boolean {
        mutex.withLock {
            val r = Edits.apply(_state.value.config, edit)
            if (!r.applied) return false
            pending += edit
            _state.update { it.copy(config = r.config, pendingEdits = pending.size) }
        }
        scheduleFlush()
        return true
    }

    private fun scheduleFlush() {
        val s = scope ?: return
        flushJob?.cancel()
        flushJob = s.launch { delay(debounceMs); while (dragging) delay(100); flush() }
    }

    /** B5 `Retry`: write the edits kept in memory again (same path as a debounced flush, incl. the B6 hash check). */
    suspend fun retrySave(): Boolean {
        flushJob?.cancel()
        // #334: never a silent no-op. Under B6 there's nothing to retry until the user picks Use file / Keep mine, so
        // a B5 still showing next to it goes and B6 is the one banner left.
        mutex.withLock {
            if (_state.value.conflict && _state.value.issue is Issue.SaveFailed) { _state.update { it.copy(issue = null) }; unsaved = null; emitStatus() }
        }
        return flush()
    }

    /**
     * Write pending edits. The B6 hash check runs first (§17 Q7): if the file changed since we
     * loaded it, enter the conflict state. Only if the file is still the same broken bytes is the
     * broken copy snapshotted before overwriting (OQ-15).
     */
    suspend fun flush(): Boolean = mutex.withLock { try { flushLocked() } finally { publishSuppression() } }

    private fun flushLocked(): Boolean {
        // #317: an agent's write that lands during the save stops it (ConcurrentChangeException, the file is left as the
        // agent wrote it); start over from that file, which now differs from the base, so it merges (or B6) like any other.
        repeat(RACE_RETRIES) {
            try { return flushOnce() } catch (_: ConcurrentChangeException) { lastStamp = null }
        }
        return fail(Issue.SaveFailed(CHANGING))
    }

    private fun flushOnce(): Boolean {
        if (pending.isEmpty() || _state.value.conflict) return false
        val current = try { source.read(Limits.MAX_BYTES) } catch (e: Exception) { return fail(Issue.SaveFailed.of(e)) }
        val curHash = current?.let { ConfigCodec.sha256(it) }
        val base = _state.value.baseHash
        val stillBroken = curHash != null && curHash == invalidHash
        if (current != null && curHash != base && curHash !in selfWrites && !stillBroken) {
            // §18.2: the file wins except the items targeted by pending ops; prompt only if asked to.
            // A merge whose write failed still returns true from autoMergeLocked (no B6), but reports B5.
            if (!askBeforeMerge && autoMergeLocked(current)) return _state.value.issue !is Issue.SaveFailed
            // #334: B6 takes over from a B5 left by an earlier failed save: one banner, with actions that work.
            _state.update { it.copy(conflict = true, issue = it.issue.takeUnless { i -> i is Issue.SaveFailed }) }
            unsaved = null; emitStatus()
            return false
        }
        var brokenName: String? = null
        if (stillBroken) current?.let { b ->
            runCatching { lastGood.snapshot(b) }
            // OQ-15: agent-visible copy in the folder too. Non-blocking: a failure keeps the private copy only.
            val name = StatusJson.brokenFileName(clock())
            if (runCatching { source.writeSidecar("broken", name, b) }.isSuccess) brokenName = name
        }
        val cfg = _state.value.config.let { it.copy(revision = (it.revision ?: 0) + 1) }
        keepWaiters(emptyList())
        try { writeLocked(cfg, expectOf(current)) } catch (e: ConcurrentChangeException) { throw e } catch (e: Exception) { return fail(Issue.SaveFailed.of(e)) }
        pending.clear(); invalidHash = null; clearUndo(); finishWaiters(emptyList())
        _state.update { it.copy(pendingEdits = 0, issue = null, notice = when {
            brokenName != null -> Msg(dev.nasawa.configlauncher.R.string.broken_saved_folder)
            stillBroken -> Msg(dev.nasawa.configlauncher.R.string.broken_saved_history)
            else -> it.notice
        }) }
        return true
    }

    /**
     * Replay pending ops by ID onto the file's version and write it. Both pre-merge versions go to
     * history. Returns false (caller falls back to B6) if the file or the merged result is invalid.
     */
    private fun autoMergeLocked(fileBytes: ByteArray): Boolean {
        val fileCfg = (ConfigCodec.decode(fileBytes) as? ParseResult.Ok)?.config ?: return false
        val ops = pending.toList()
        val rep = Edits.replay(fileCfg, ops)
        val merged = rep.config
        val out = merged.copy(revision = maxOf(merged.revision ?: 0, _state.value.config.revision ?: 0) + 1)
        if (ConfigCodec.decode(ConfigCodec.encodeBytes(out)) !is ParseResult.Ok) return false
        runCatching { lastGood.snapshot(fileBytes) }
        runCatching { lastGood.snapshot(ConfigCodec.encodeBytes(_state.value.config)) }
        keepWaiters(rep.skipped.map { it.edit })
        // #317: changed again under the merge's own write: the caller ([flushLocked]) merges onto the newer file.
        val written = try { writeLocked(out, expectOf(fileBytes)) } catch (e: ConcurrentChangeException) { throw e } catch (e: Exception) { fail(Issue.SaveFailed.of(e)); return true }
        val shown = unreportedSkips(rep.skipped)
        pending.clear(); finishWaiters(rep.skipped.map { it.edit })
        // §9.1: all-skipped merges have no Undo (nothing of ours was written).
        val undo = rep.kept > 0
        if (undo) { undoBytes = fileBytes; mergedHash = written; undoTouched = dev.nasawa.configlauncher.model.UndoMerge.record(fileCfg, out) }
        else clearUndo()
        _state.update {
            it.copy(pendingEdits = 0, issue = null, mergeUndo = undo,
                // Nothing left to say when the only skip is one its caller already reported (#319 review).
                merge = MergeNotice(rep.kept, shown.size, shown.singleOrNull()?.folder?.takeIf { rep.kept == 0 }, undo).takeUnless { n -> n.kept == 0 && n.skipped == 0 })
        }
        return true
    }

    /**
     * Snackbar Undo after an automatic merge (§18.2 Undo safety rule). Re-reads and hashes the file:
     * unchanged → write the pre-merge version through [writeLocked]; changed → revert only the
     * touched items by ID onto the new file, or cancel with no write. Both versions go to history first.
     */
    suspend fun undoMerge(): Boolean = mutex.withLock {
        val before = undoBytes ?: return false
        val touched = undoTouched
        clearUndo()
        val cancel = { msg: Msg -> _state.update { it.copy(mergeUndo = false, merge = null, notice = msg) }; false }
        val now = try { source.read(Limits.MAX_BYTES) } catch (_: Exception) { null } ?: return cancel(CANCELLED)
        val target: LauncherConfig = if (ConfigCodec.sha256(now) == mergedHash) {
            (ConfigCodec.decode(before) as? ParseResult.Ok)?.config ?: return cancel(CANCELLED)
        } else {
            val nowCfg = (ConfigCodec.decode(now) as? ParseResult.Ok)?.config ?: return cancel(CANCELLED)
            val r = dev.nasawa.configlauncher.model.UndoMerge.revert(nowCfg, touched) ?: return cancel(CANCELLED)
            if (ConfigCodec.decode(ConfigCodec.encodeBytes(r)) !is ParseResult.Ok) return cancel(CANCELLED)
            r.copy(revision = (nowCfg.revision ?: 0) + 1)
        }
        runCatching { lastGood.snapshot(ConfigCodec.encodeBytes(_state.value.config)) }
        runCatching { lastGood.snapshot(before) }
        if (ConfigCodec.sha256(now) != mergedHash) runCatching { lastGood.snapshot(now) }
        // #317: an agent's write landing now cancels the Undo (nothing written) rather than reverting over it.
        try { writeLocked(target, expectOf(now)); _state.update { it.copy(mergeUndo = false, merge = null) }; true }
        catch (_: ConcurrentChangeException) { cancel(CANCELLED) }
        catch (e: Exception) { fail(Issue.SaveFailed.of(e, dev.nasawa.configlauncher.R.string.b5_undo_failed)) }
    }

    private fun clearUndo() { undoBytes = null; mergedHash = null; undoTouched = emptyList() }

    fun dismissMergeUndo() { clearUndo(); _state.update { it.copy(mergeUndo = false, merge = null) } }

    /** B6 "Use file": drop local edits (snapshotted to history) and load the file. */
    suspend fun resolveUseFile() {
        mutex.withLock {
            runCatching { lastGood.snapshot(ConfigCodec.encodeBytes(_state.value.config)) }
            pending.clear(); lastStamp = null; finishWaiters(emptyList(), dropped = true); unsaved = null
            _state.update { it.copy(conflict = false, pendingEdits = 0, baseHash = null) }
            pollLocked(force = true)
        }
    }

    /** B6 "Keep mine": replay local ops by ID onto the file's version (snapshotted first) and write. */
    suspend fun resolveKeepMine(): Boolean = mutex.withLock {
        // #317: an agent's write landing during the save: replay onto that newer file instead (the user chose "mine").
        repeat(RACE_RETRIES) { try { return keepMineOnce() } catch (_: ConcurrentChangeException) { lastStamp = null } }
        fail(Issue.SaveFailed(CHANGING))
    }

    private fun keepMineOnce(): Boolean {
        val bytes = try { source.read(Limits.MAX_BYTES) } catch (e: Exception) { return fail(Issue.SaveFailed.of(e)) }
        val fileCfg = bytes?.let { (ConfigCodec.decode(it) as? ParseResult.Ok)?.config }
        val ops = pending.toList()
        val rep = if (fileCfg == null) dev.nasawa.configlauncher.model.Replay(_state.value.config, ops.size, emptyList()) else Edits.replay(fileCfg, ops)
        val merged = rep.config
        bytes?.let { runCatching { lastGood.snapshot(it) } }
        val out = merged.copy(revision = maxOf(merged.revision ?: 0, _state.value.config.revision ?: 0) + 1)
        keepWaiters(rep.skipped.map { it.edit })
        try { writeLocked(out, expectOf(bytes)) } catch (e: ConcurrentChangeException) { throw e } catch (e: Exception) { return fail(Issue.SaveFailed.of(e)) }
        val shown = unreportedSkips(rep.skipped)
        pending.clear(); finishWaiters(rep.skipped.map { it.edit })
        _state.update {
            it.copy(conflict = false, pendingEdits = 0, issue = null,
                // §9.1: Keep mine uses the same copy, without Undo; all kept shows nothing extra.
                merge = MergeNotice(rep.kept, shown.size, shown.singleOrNull()?.folder?.takeIf { rep.kept == 0 }, undo = false).takeUnless { n -> n.silent })
        }
        return true
    }

    /** B4 action "Create from current layout": the only way launcher.json is ever created. */
    suspend fun createFromCurrentLayout(): Boolean = mutex.withLock {
        try {
            if (source.stamp() != null) return pollLocked(force = true) // appeared meanwhile: never clobber
            writeLocked(_state.value.config, ABSENT)
            true
        } catch (_: ConcurrentChangeException) { pollLocked(force = true) // #317: created by someone else mid-save
        } catch (e: Exception) { fail(Issue.SaveFailed.of(e, dev.nasawa.configlauncher.R.string.b5_create_failed)) }
    }

    /**
     * Serialize and write [config] canonically, recording its hash as a self-write. Caller holds the mutex.
     * Refuses output over the cap (§17 Q16) so the launcher never writes a file it can't read.
     */
    /**
     * #319 + #318: any failure, including a raced write ([ConcurrentChangeException], whose caller then merges onto the
     * newer file), un-keeps the waiting [editKept] callers first, so the retry's replay decides afresh.
     */
    internal fun writeLocked(config: LauncherConfig, expectHash: String? = null): String =
        try { writeOrThrow(config, expectHash) } catch (e: Exception) { unkeepWaiters(); throw e }

    private fun writeOrThrow(config: LauncherConfig, expectHash: String?): String {
        val bytes = ConfigCodec.encodeBytes(config)
        if (bytes.size > Limits.MAX_BYTES) throw TooLargeException(Limits.MAX_BYTES / 1024 / 1024)
        val hash = ConfigCodec.sha256(bytes)
        selfWrites.addLast(hash); while (selfWrites.size > 4) selfWrites.removeFirst()
        // #317: [expectHash] is the file the caller checked; the source re-checks it right before replacing the target.
        // Raced: our bytes aren't the file, so they're no self-write (the next look must see the agent's file as new).
        // #334: any failure, not only a race: bytes that never became the file aren't an echo of ours.
        try { source.write(bytes, expectHash) } catch (e: Exception) { selfWrites.remove(hash); throw e }
        unsaved = null // written: whatever failed before is saved now
        lastStamp = source.stamp()
        writeStatus("ok", emptyList(), hash, config.revision, Elements.warnings(config), config)
        _state.value = LoadState(config, Origin.FILE, source.displayName, hash, reconnectable = source.reconnectable)
        saveLastGood(bytes)
        return hash
    }

    /** §17 Q4: a failed backup copy is non-blocking: one snackbar, retried on the next load/write. */
    private fun saveLastGood(bytes: ByteArray) {
        try {
            lastGood.save(bytes)
            if (backupError != null) { backupError = null; emitStatus() }
        } catch (e: Exception) {
            backupError = "Couldn't update the backup copy: ${e.message ?: e.javaClass.simpleName}"
            emitStatus()
            _state.update { it.copy(notice = Msg(dev.nasawa.configlauncher.R.string.backup_failed)) }
        }
    }

    /** Re-list sync/merge leftovers (cheap: one directory listing, only on hash-anyway polls). */
    private fun refreshConflicts(src: ConfigSource) {
        val found = runCatching { SyncConflicts.find(src.siblingNames()) }.getOrNull() ?: return
        if (found != conflictFiles) { conflictFiles = found; emitStatus() }
    }

    /** §16/§17: `.configlauncher/status.json` so an agent can see why an edit was rejected. Best effort. */
    private fun writeStatus(result: String, errors: List<String>, hash: String, revision: Long? = null, warnings: List<String> = emptyList(), config: LauncherConfig? = null) {
        statusResult = Triple(result, errors, hash); statusRevision = revision; statusWarnings = warnings; statusConfig = config
        if (config != null) { consent?.settle(config); bindings?.settle(config) }
        emitStatus()
    }

    /**
     * #125 (Pragma): the UI passes the theme colours at load and whenever the theme or wallpaper colours change.
     * Theme-colour contrast warnings ([Elements.phoneWarnings]) for the loaded file are then appended to
     * status.json `warnings`, marked "Checked on this phone". status.json is rewritten only if they change.
     */
    suspend fun setPhoneTheme(dark: Boolean, colors: Map<String, String>) = mutex.withLock {
        if (phoneTheme == (dark to colors)) return@withLock
        phoneTheme = dark to colors
        emitStatus()
    }

    private fun phoneWarnings(): List<String> =
        phoneTheme?.let { (dark, colors) -> statusConfig?.let { c -> Elements.phoneWarnings(c, dark) { colors[it] } } }.orEmpty() + listOfNotNull(titleBarFit)

    private var titleBarFit: String? = null

    /**
     * #149 (ux.md §15 overflow ladder): the phone-only `warn_titlebar_fit` line while the title bar needs step 2 or later,
     * null once it fits again. status.json is rewritten silently, only when it changes.
     */
    suspend fun setTitleBarFit(warning: String?) = mutex.withLock {
        if (titleBarFit == warning) return@withLock
        titleBarFit = warning
        emitStatus()
    }

    private var lastApps: Pair<ConfigSource, List<kotlinx.serialization.json.JsonObject>>? = null

    private var lastWidgets: List<kotlinx.serialization.json.JsonObject>? = null

    /**
     * #54: write `.configlauncher/apps.json` when the app list (or the source) changed. Never touches
     * launcher.json, so no reload loop. App-only mode has no folder sidecars: skipped.
     */
    suspend fun writeApps(apps: List<dev.nasawa.configlauncher.model.InstalledApp>, includeWork: Boolean = false, includeHidden: Boolean = false,
                          widgets: List<kotlinx.serialization.json.JsonObject>? = null): Boolean = mutex.withLock {
        // #72: drawer.hidden from the model being shown, so hiding or unhiding an app rewrites apps.json.
        val entries = AppsJson.entries(apps, includeWork, _state.value.config.drawer?.hidden.orEmpty().toSet(), includeHidden)
        val src = source
        if (lastApps?.first === src && lastApps?.second == entries && lastWidgets == widgets) return false
        return try { src.writeSidecar("", AppsJson.FILE, AppsJson.encode(entries, clock(), widgets)); lastApps = src to entries; lastWidgets = widgets; true }
        catch (_: Exception) { false }
        finally { publishSuppression() }
    }

    /** #138: the loaded file's widget consent states (empty when rejected or without a store). */
    fun consentReport(): List<dev.nasawa.configlauncher.consent.Consent.Status> =
        statusConfig?.let { c -> consent?.report(c) }.orEmpty()

    /**
     * #142 `Allow` from the prompt: approves [shown] only while the card in the file the engine has loaded right now
     * still has exactly that fingerprint (Archimedes, #158). The caller passes what the user saw, never the file.
     */
    fun allowConsent(id: String, shown: List<String>): Boolean = consent?.allow(id, shown, state.value.config) == true

    /** #138: re-emit status.json after the UI changed an approval (Allow, Turn off, Undo, Forget approval). */
    suspend fun consentChanged() = mutex.withLock { pollWidgetsLocked(force = true); emitStatus() }

    private val widgetReader = dev.nasawa.configlauncher.widgetdata.WidgetDataReader(clock)
    private val _widgetData = MutableStateFlow<Map<String, dev.nasawa.configlauncher.widgetdata.WidgetDataReader.Snapshot>>(emptyMap())
    /** #139: each readable card's last data, by element id. Only cards allowed on this phone that declare `data.file`. */
    val widgetData: StateFlow<Map<String, dev.nasawa.configlauncher.widgetdata.WidgetDataReader.Snapshot>> = _widgetData

    /** #139: cards whose data file may be read: shown, `allowed` here, and declaring `data.file` (Q-A7: only approved APIs). */
    private fun dataTargets(): List<dev.nasawa.configlauncher.widgetdata.WidgetDataReader.Target> =
        consentReport().filter { it.state == dev.nasawa.configlauncher.consent.Consent.State.Allowed && "data.file" in it.lines }
            .map { dev.nasawa.configlauncher.widgetdata.WidgetDataReader.Target(it.id, it.path) }

    /** #139: poll the data files (called with every launcher.json poll; [force] on resume). */
    suspend fun pollWidgets(force: Boolean = false) = mutex.withLock { if (pollWidgetsLocked(force)) emitStatus() }

    private fun pollWidgetsLocked(force: Boolean): Boolean {
        if (!cardsLive) return false
        val changed = widgetReader.poll(source, dataTargets(), force)
        if (changed) _widgetData.value = widgetReader.snapshots()
        return changed
    }

    /** #139 `ui.reload`: re-read one card's data file now, if it's allowed and declares `ui.reload` and `data.file`. */
    suspend fun reloadWidget(id: String): dev.nasawa.configlauncher.widgetdata.WidgetDataReader.Reload = mutex.withLock {
        val t = dataTargets().firstOrNull { it.id == id }
        // #158 review: only a live (allowed) card's reload tap counts, even before the #140 renderer can send one.
        if (t == null || consentReport().none { it.id == id && it.state == dev.nasawa.configlauncher.consent.Consent.State.Allowed && "ui.reload" in it.lines }) return@withLock dev.nasawa.configlauncher.widgetdata.WidgetDataReader.Reload.Ignored
        widgetReader.reload(source, t).also { _widgetData.value = widgetReader.snapshots(); emitStatus() }
    }

    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    /**
     * #159 (ux.md §15): the status.json `warnings` lines, in the same order and text. The on-phone Warnings list
     * reads this, so the phone and the agent always see the same thing (consent and widget-data warnings included).
     */
    val warnings: StateFlow<List<String>> = _warnings.asStateFlow()

    private val _keptTemps = MutableStateFlow<List<String>>(emptyList())
    /**
     * #262: the [ConfigSource.tempWarnings] lines (a save temp the start sweep kept as the only copy of an edit). They're
     * in [warnings] / status.json too, but the Warnings list is hidden while a problem banner is up and status.json
     * may not be writable (msd:12), so HomeScreen also puts these in the problem banner ([BannerNotes.keptTemps]).
     */
    val keptTemps: StateFlow<List<String>> = _keptTemps.asStateFlow()

    private fun refreshKeptTemps() {
        val now = runCatching { source.tempWarnings() }.getOrDefault(emptyList())
        if (now != _keptTemps.value) _keptTemps.value = now
    }

    private fun emitStatus() {
        refreshKeptTemps()
        val (result, errors, hash) = statusResult ?: return
        val report = if (result == "ok") consentReport() else emptyList()
        val cardWarnings = when {
            result != "ok" -> emptyList()
            !cardsLive -> statusConfig?.let { dev.nasawa.configlauncher.config.CardPlaceholder.warnings(it) }.orEmpty()
            else -> report.mapNotNull { it.warning }
        }
        val widgetWarnings = if (result == "ok" && cardsLive) widgetReader.warnings(dataTargets()) else emptyList()
        val aw = appWidgets.takeIf { result == "ok" }
        val awConfig = statusConfig
        val appWidgetMap = if (aw != null && awConfig != null) runCatching { aw.states(awConfig) }.getOrNull() else null
        val appWidgetWarnings = if (aw != null && awConfig != null) runCatching { aw.warnings(awConfig) }.getOrDefault(emptyList()) else emptyList()
        val warnings = (if (result == "ok") statusWarnings + phoneWarnings() + cardWarnings + widgetWarnings + appWidgetWarnings else statusWarnings) +
            source.suppressedNames().map(::suppressedWarning) + source.tempWarnings()
        _warnings.value = warnings // the phone's ▲ line; B5 already says the save failed there
        // #334 / #321: agents see the pending phone change that didn't save (hash/result still describe the file).
        val listed = warnings + listOfNotNull(unsaved?.let(::unsavedWarning))
        // Release builds still record each card's stored state, but drop the "waiting for approval" sentence
        // so it doesn't contradict the "isn't shown in this version" warning.
        val consentMap = if (consent == null) null else report.associate { it.id to (it.state.key to if (cardsLive) it.warning else null) }
        val key = "$result|$hash|$statusRevision|${errors.hashCode()}|${listed.hashCode()}|$backupError|$conflictFiles|$consentMap|$appWidgetMap"
        if (key == lastStatusKey) return
        try {
            source.writeSidecar("", StatusJson.FILE, StatusJson.encode(result, errors, hash, clock(), backupError, conflictFiles, source.location, revision = statusRevision, warnings = listed, consent = consentMap, appWidgets = appWidgetMap?.takeIf { it.isNotEmpty() }))
            lastStatusKey = key
        } catch (_: Exception) { /* folder may be read-only or gone; the banner already says so */ }
        noteSuppression()
    }

    /**
     * #251: a create the provider renamed while hiding the original is suppressed ([SafDirs]). The first time
     * per suppression: a one-shot notice ([publishSuppression]), and the Warnings list / status.json get [suppressedWarning] (emitStatus).
     * status.json lives in `.configlauncher/`, which may be the hidden name itself, so the notice is the reliable
     * channel. Re-entrant via [emitStatus] only while new names keep appearing ([ConfigSource.takeNewlySuppressed] drains).
     */
    private fun noteSuppression() {
        val fresh = source.takeNewlySuppressed()
        if (fresh.isEmpty()) return
        if (hiddenNotice == null) hiddenNotice = fresh.first()
        emitStatus()
    }

    /** First name newly suppressed during this call, shown once [publishSuppression] runs (a load replaces [state]). */
    private var hiddenNotice: String? = null

    /** End of each public entry point (poll, flush, reload, move, apps.json): drain, then raise the one-shot notice. */
    private fun publishSuppression() {
        refreshKeptTemps()
        noteSuppression()
        val name = hiddenNotice ?: return
        hiddenNotice = null
        val msg = if (name in SafWrites.ROOT_FILES) Msg(dev.nasawa.configlauncher.R.string.folder_hidden_notice_file, name)
            else Msg(dev.nasawa.configlauncher.R.string.folder_hidden_notice)
        _state.update { it.copy(notice = msg) }
    }

    /**
     * Line/column for each of [errors] in the last failed file (Q-S3). Call off the main thread and
     * only when the error detail view opens; returns all nulls if no failed file is held.
     */
    fun locateErrors(errors: List<String>): List<dev.nasawa.configlauncher.config.TextPos?> {
        val b = invalidBytes ?: return errors.map { null }
        val text = String(b, Charsets.UTF_8).removePrefix("\uFEFF")
        return dev.nasawa.configlauncher.config.ErrorLocator.locate(text, errors)
    }

    /** #251 Warnings / status.json line for a suppressed [name] (English like the other status lines; Pragma copy). */
    /** #334 / #321: the status.json line while a phone change is pending after a failed save. */
    internal fun unsavedWarning(reason: String) =
        "The phone has a change that isn't saved yet ($reason). It stays on the phone and is merged onto launcher.json on the next save (RELOAD retries it); until then hash and revision describe the file as last loaded or saved."

    internal fun suppressedWarning(name: String) =
        if (name in SafWrites.ROOT_FILES) "Can't save $name: the storage app hides the one already in this folder, so no copy was made. To fix it, choose the folder again from internal storage."
        // `.configlauncher` (or something in it): launcher.json still saves through a root temp ([SafWrites]).
        else "Backups and status can't be kept here because the storage app hides $name. To fix it, choose the folder again from internal storage."

    fun consumeNotice() = _state.update { it.copy(notice = null) }
    fun consumeMerge() = _state.update { it.copy(merge = null) }

    private fun classify(e: Exception, src: ConfigSource): Issue = when (e) {
        is AccessLostException -> Issue.AccessLost(e.message ?: "folder not accessible")
        is SecurityException -> Issue.AccessLost("Can't reach ${src.displayName}: ${e.message}")
        is FileNotFoundException -> Issue.ReadFailed(e.message ?: "not found")
        else -> if (e.message?.contains("larger than") == true) Issue.ParseError(listOf(e.message!!))
            else Issue.ReadFailed("Can't read ${src.displayName}: ${e.message}")
    }

    private fun classifyParse(errors: List<String>): Issue = when {
        errors.any { it.contains("newer than this launcher") } -> Issue.TooNew(errors.first())
        errors.size == 1 && (errors[0].startsWith("not valid") || errors[0].startsWith("file is larger")) -> Issue.ParseError(errors)
        else -> Issue.Invalid(errors)
    }

    private fun fail(issue: Issue): Boolean {
        _state.update { it.copy(issue = issue) }
        // #334 / #321: a failed save with edits still pending is reported in status.json, not hidden behind the last `ok`.
        if (issue is Issue.SaveFailed && pending.isNotEmpty()) {
            unsaved = issue.message; failedStamp = runCatching { source.stamp() }.getOrNull(); emitStatus()
        }
        return false
    }
}
