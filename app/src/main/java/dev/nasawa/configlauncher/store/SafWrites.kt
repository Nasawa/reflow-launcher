package dev.nasawa.configlauncher.store

import android.provider.DocumentsContract.Document
import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import java.io.File
import java.io.IOException

/**
 * #251 / #274: document ids of save temps (root or `.configlauncher/`) that may still exist, per tree, in app-private storage. A save adds its
 * temp (with the SHA-256 of the bytes it's about to hold) right after creating it and removes it once deleted;
 * [SafWrites.sweepRootTemps] deals with what's left on start. That's how a temp the provider hides (MediaStore skips
 * dot names) is still found after a killed save, and how the sweep knows the temp is a complete copy.
 */
interface TempJournal {
    fun add(treeKey: String, docId: String, sha256: String? = null)
    fun remove(treeKey: String, docId: String)
    fun list(treeKey: String): List<String>
    /** The SHA-256 recorded with [docId], or null (none recorded, e.g. a journal from an earlier #251 build). */
    fun sha256(treeKey: String, docId: String): String? = null
    /**
     * #262: one more start whose sweep couldn't settle [docId] (a transient error); returns the count so far.
     * [SafWrites.sweepRootTemps] drops the entry (never the file) after [SafWrites.MAX_STRIKES] in a row.
     */
    fun strike(treeKey: String, docId: String): Int = 0
    /** #262: the sweep settled [docId] this start (kept on purpose as the only copy): its strike count restarts. */
    fun clearStrikes(treeKey: String, docId: String) {}

    companion object {
        val NONE = object : TempJournal {
            override fun add(treeKey: String, docId: String, sha256: String?) {}
            override fun remove(treeKey: String, docId: String) {}
            override fun list(treeKey: String) = emptyList<String>()
        }
    }
}

/**
 * [TempJournal] as `tree<TAB>docId[<TAB>sha256[<TAB>strikes]]` lines in one app-private file, replaced via a temp
 * file and rename. Two- and three-field lines (earlier #251 builds) still read, with no hash / no strikes.
 */
class FileTempJournal(private val file: File) : TempJournal {
    private class E(val tree: String, val id: String, val sha: String?, val strikes: Int = 0)
    private fun read(): List<E> = synchronized(this) {
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull { l ->
            l.split('\t').takeIf { it.size in 2..4 }?.let { E(it[0], it[1], it.getOrNull(2)?.ifEmpty { null }, it.getOrNull(3)?.toIntOrNull() ?: 0) }
        }
    }
    private fun save(entries: List<E>) {
        file.parentFile?.mkdirs()
        val t = File(file.path + ".tmp")
        t.writeText(entries.joinToString("") { e ->
            "${e.tree}\t${e.id}" + when { e.strikes > 0 -> "\t${e.sha.orEmpty()}\t${e.strikes}"; e.sha != null -> "\t${e.sha}"; else -> "" } + "\n"
        })
        if (!t.renameTo(file)) { file.delete(); t.renameTo(file) }
    }
    override fun add(treeKey: String, docId: String, sha256: String?) = synchronized(this) {
        save(read().filterNot { it.tree == treeKey && it.id == docId } + E(treeKey, docId, sha256))
    }
    private fun setStrikes(treeKey: String, docId: String, f: (Int) -> Int): Int = synchronized(this) {
        var n = 0
        val all = read()
        if (all.none { it.tree == treeKey && it.id == docId }) return 0
        save(all.map { e -> if (e.tree == treeKey && e.id == docId) E(e.tree, e.id, e.sha, f(e.strikes).also { n = it }) else e })
        n
    }
    override fun strike(treeKey: String, docId: String) = setStrikes(treeKey, docId) { it + 1 }
    override fun clearStrikes(treeKey: String, docId: String) {
        if (read().any { it.tree == treeKey && it.id == docId && it.strikes > 0 }) setStrikes(treeKey, docId) { 0 }
    }
    override fun remove(treeKey: String, docId: String) = synchronized(this) { save(read().filterNot { it.tree == treeKey && it.id == docId }) }
    override fun list(treeKey: String) = read().filter { it.tree == treeKey }.map { it.id }
    override fun sha256(treeKey: String, docId: String) = read().firstOrNull { it.tree == treeKey && it.id == docId }?.sha
}

/**
 * AQ-4 verified write of a root file (launcher.json, AGENTS.md) on a SAF tree, which has no atomic replace:
 * stage a temp copy, verify it, truncate-write the target, verify, and on failure put the target's original bytes back.
 *
 * Staging (#251, #263, #274):
 * - Every save stages in a new temp `.<name>.<token>.tmp` (token: 8 lowercase hex, new per save):
 *   - a sidecar temp inside `.configlauncher/` (found through [SafDirs.ensure], never created twice);
 *   - or, when `.configlauncher` can't be used (suppressed because the provider hides it, or otherwise failing),
 *     a root temp next to the target.
 *   There is no fixed temp name any more (#274: the old `.configlauncher/<name>.tmp` was never journaled or
 *   deleted, so a kill mid target write left a torn target nothing restored, and a listing that missed it made
 *   `<name>.tmp (1)` litter on Downloads-style providers). A new name per save never collides with a hidden
 *   leftover. The temp is made with [SafDirs.createScratch]: any name the provider returns is accepted, the returned
 *   document is used, and it's deleted by that document id in `finally`, never looked up by name.
 * - Each temp's id (and the hash of the bytes it will hold) is written to a [TempJournal] before any bytes go
 *   in, inside the try/finally that deletes the temp, so one left by a killed save is found on the next start
 *   ([sweepRootTemps]) even when the provider's listing hides it. A process killed between the create and the
 *   journal write can orphan one empty file; in the root (hidden) that's accepted, in `.configlauncher/` the start
 *   sweep deletes it once it's listed and still empty.
 *
 * Write order: the temp is written and verified in full before the target is touched. The target itself can't be
 * replaced atomically (no rename-over on SAF), so a process killed during the target's truncate-write leaves it
 * empty or a prefix of the new bytes. The temp is then the only full copy, and the next start's sweep restores the
 * target from it before deleting it (Grace, #251 NO-GO at 3fc9b0e).
 */
internal class SafWrites(
    private val tree: DocTree,
    private val dirs: SafDirs,
    private val treeKey: String,
    private val journal: TempJournal = TempJournal.NONE,
    private val token: () -> String = { java.util.UUID.randomUUID().toString().take(8) }, // 8 lowercase hex,
) {

    /**
     * #317: with [expectHash] (see [ConfigSource.write]) the target is re-read right before its truncate-write and the
     * save stops with [ConcurrentChangeException] when it no longer matches, leaving the other writer's file alone.
     * After the write, a read-back that is neither our bytes, a torn copy of them nor the original means someone else
     * wrote mid-save: that file is kept (no restore over it) and the same exception is thrown. #339: the re-read is
     * repeated inside the target's own "rw" open ([DocTree.replace]) where the provider allows, and the read-back is
     * compared exactly: ours with trailing bytes is repaired ([overlapped]), never kept as theirs. What's left: a write
     * that starts and ends between the in-open re-read and the truncate (or, without "rw", between the re-read and the
     * "wt" open: one provider call), or one we then overwrite in full, isn't seen.
     */
    fun writeVerified(rootDocId: String, name: String, mime: String, bytes: ByteArray, what: String, expectHash: String? = null) = synchronized(lock) {
        overlapped = false
        val want = ConfigCodec.sha256(bytes)
        val tmpName = ".$name.${token()}.tmp"
        // #274: a random-name sidecar temp in `.configlauncher/`, else a root temp; both journaled and deleted by id.
        val sidecarTmp = try {
            dirs.createScratch(dirs.ensure(rootDocId, DIR_NAME, Document.MIME_TYPE_DIR), BIN, tmpName)
        } catch (e: AccessLostException) { throw e } catch (_: IOException) { null }
        val tmp = sidecarTmp ?: dirs.createScratch(rootDocId, BIN, tmpName)
        // #274: a fixed-name sidecar temp from a pre-#274 build that is identical to the target (or a shipped
        // AGENTS.md) goes before the target changes; one that differs is kept, as on start.
        if (sidecarTmp != null) runCatching { sweepSidecarDir(rootDocId, only = name, legacyOnly = true) }
        var keepTmp = false
        try {
            // Journaled inside the try, so a journal that can't be written still gets the temp deleted (finally).
            try { journal.add(treeKey, tmp, want) } catch (e: Exception) {
                throw IOException("Couldn't save $what: can't record the temporary copy (${e.message})", e)
            }
            tree.write(tmp, bytes)
            if (tree.read(tmp)?.let { ConfigCodec.sha256(it) } != want) throw IOException("temp copy verification failed")
            val target = dirs.ensure(rootDocId, name, mime)
            // The target's current bytes, kept in memory: the restore puts these back (not the temp's new bytes).
            var original = tree.read(target)
            // #317: a look before the target changes (an agent's write since the caller's check stops the save).
            requireUnchanged(expectHash, original)
            var back: ByteArray? = null
            var readBack = false
            try {
                // #339: the last look and the write in one open, so an agent's `cp` landing after the look above is
                // caught too (it was overwritten in full, and the read-back saw only our bytes: a silent loss).
                // Providers without a seekable descriptor take the old two-call path ([DocTree.write]).
                val replaced = tree.replace(target, bytes) { now -> requireUnchanged(expectHash, now); original = now }
                if (!replaced) tree.write(target, bytes)
                back = tree.read(target); readBack = true
                // #339: exact bytes. Ours with trailing bytes after them: another writer's file landed after our
                // truncate and we wrote over its start, so neither file is left (Claw's torn launcher.json). Theirs
                // can't be put back, so ours is written again (cut to length) and the overlap is reported.
                // #340 follow-up (Grace): the mirror image, a shorter file over the start of ours (its truncating open
                // landed before our truncate, its write after ours): same length as ours, doesn't load, ours from where
                // theirs ends. Repaired and reported the same way.
                val trailing = back != null && back.size > bytes.size && back.startsWith(bytes)
                val mixed = !trailing && back != null && name == SafSource.FILE && headOverOurs(back, bytes)
                if (back != null && (trailing || mixed)) {
                    val repaired = runCatching { tree.write(target, bytes); tree.read(target)?.contentEquals(bytes) == true }.getOrDefault(false)
                    if (repaired) {
                        // A tail that is simply the old file's (a provider that didn't truncate) is no overlap.
                        val oldTail = trailing && original?.let { o -> o.size == back.size && (bytes.size until o.size).all { o[it] == back[it] } } == true
                        if (!oldTail) overlapped = true
                        back = bytes
                    }
                }
                if (back?.let { ConfigCodec.sha256(it) } != want) {
                    // #317: not ours, not a torn copy of ours, not the original: another writer's bytes landed mid-save.
                    if (expectHash != null && back != null && back.isNotEmpty() && !bytes.startsWith(back) && !back.startsWith(bytes) && !mixed && original?.contentEquals(back) != true)
                        throw ConcurrentChangeException(back, "launcher.json changed while saving (someone else wrote it)")
                    throw IOException("write verification failed")
                }
            } catch (e: ConcurrentChangeException) { throw e } catch (e: Exception) {
                // #334: look once more before restoring. An empty read-back (or a prefix of ours) can be another writer
                // mid-`cp` (truncate, then write); if its bytes landed since, restoring would put the old file over
                // them. So with [expectHash], restore only a target that's still what the read-back saw (or, when the
                // write itself threw, still empty, a prefix of ours or the original); anything else is kept as theirs.
                if (expectHash != null) {
                    val now = runCatching { tree.read(target) }.getOrNull()
                    val unchanged = if (readBack) java.util.Arrays.equals(now, back)
                        else now == null || now.isEmpty() || bytes.startsWith(now) || original?.contentEquals(now) == true
                    if (!unchanged && now != null && now.isNotEmpty() && ConfigCodec.sha256(now) != want)
                        throw ConcurrentChangeException(now, "launcher.json changed while saving (someone else wrote it)")
                    if (!unchanged && now != null && ConfigCodec.sha256(now) == want) return@synchronized // ours after all
                }
                val restored = original != null &&
                    runCatching { tree.write(target, original); tree.read(target)?.contentEquals(original) == true }.getOrDefault(false)
                // Keep or delete the root temp:
                // - target restored to its original bytes: the file is as it was and the edit is still pending in
                //   memory for Retry, so the temp isn't needed and is deleted;
                // - target write and restore both failed (or there was no original): the target may be truncated or
                //   partial and the temp is the only durable copy of the new version, so it's kept, dropped from the
                //   journal (so no start sweep deletes it), and named in the error.
                // The same for a `.configlauncher` temp (#274).
                if (!restored) {
                    keepTmp = true
                    val keptName = (if (sidecarTmp != null) "$DIR_NAME/" else "") +
                        (runCatching { tree.displayName(tmp) }.getOrNull() ?: ".$name.*.tmp")
                    throw IOException("Couldn't save $what: ${e.message}; the new version is kept in $keptName", e)
                }
                throw IOException("Couldn't save $what: ${e.message}" + if (restored) "" else "; restoring the old version also failed", e)
            }
        } finally {
            if (keepTmp) runCatching { journal.remove(treeKey, tmp) }
            else if (runCatching { tree.delete(tmp) }.getOrDefault(false)) runCatching { journal.remove(treeKey, tmp) }
            // A failed delete stays in the journal for the next start's sweep.
        }
        // Saved through `.configlauncher`: an exact-name root temp from a 7803222 build is no longer needed.
        if (sidecarTmp != null) runCatching { sweepLegacy(rootDocId, name) }
    }

    /**
     * Start: deal with root temps left by killed saves, by journaled document id (the listing may hide them).
     * For each journaled id:
     * - not found (null info, no name, or the provider's not-found / not-in-this-tree error from any lookup, read or
     *   delete; #262: MediaStore can return a row for a deleted file, then throw FileNotFoundException on read):
     *   dropped from the journal;
     * - a folder, or not exactly `.launcher.json.<8 hex>.tmp` / `.AGENTS.md.<8 hex>.tmp` (a provider's ` (N)` before
     *   `.tmp` allowed): dropped from the journal and never deleted;
     * - the save was killed while writing the target (the target is empty or a prefix of the temp) and the temp is a
     *   complete copy (its recorded hash matches; without one, launcher.json must parse and AGENTS.md be non-empty):
     *   the target is restored from the temp, then the temp is deleted. If the restore fails, the temp is kept (still
     *   journaled, tried again next start) and a Warnings / status.json line names it ([tempWarnings]);
     * - launcher.json was replaced by something that doesn't parse and isn't ours: it's not overwritten; the temp
     *   is kept with a warning, and deleted on a later start once launcher.json is fixed;
     * - otherwise the save finished or never reached the target: the temp is deleted.
     * - any other error (or a delete the provider refuses): kept for the next start. #262: after [MAX_STRIKES] such
     *   starts in a row the entry is dropped from the journal (the file is left alone), so the journal can't grow forever.
     * Losing access stops the sweep with every entry kept, and costs no strike.
     * Exact legacy names [legacyTemp] are found by listing. Never a folder, never anything else.
     *
     * #274: journaled temps may live in `.configlauncher/` too (same rules: the target is always the root file).
     * Then `.configlauncher/` itself is listed (only if the root listing shows it; it's never created here):
     * - an unjournaled `.<name>.<8 hex>.tmp` that is still empty (a kill between create and journal write) is deleted;
     *   one with bytes is left alone (it may be a temp a failed save kept and named as the only copy);
     * - a fixed-name `launcher.json.tmp` / `AGENTS.md.tmp` from a pre-#274 build (or its ` (N)` copy) is never restored
     *   from; it's deleted only when empty, identical to the target or a shipped AGENTS.md, else kept with a ▲ line
     *   ([settleLegacySidecar]).
     */
    fun sweepRootTemps(rootDocId: String): List<String> = synchronized(lock) {
        val warnings = mutableListOf<String>()
        for (id in journal.list(treeKey)) {
            try { sweepOne(rootDocId, id)?.let { warnings += it; runCatching { journal.clearStrikes(treeKey, id) } } }
            catch (_: AccessLostException) { break } // no access: every entry is kept for a later start
            catch (_: Exception) {
                // Transient: kept for the next start, until it has failed MAX_STRIKES starts in a row.
                runCatching { if (journal.strike(treeKey, id) >= MAX_STRIKES) journal.remove(treeKey, id) }
            }
        }
        ROOT_FILES.forEach { runCatching { sweepLegacy(rootDocId, it) } }
        try { warnings += sweepSidecarDir(rootDocId) } catch (_: Exception) {} // access lost or transient: next start
        kept[treeKey] = warnings.toList()
        warnings
    }

    /**
     * #339: the last [writeVerified] found another writer's bytes after ours (it wrote over the start of their file) and
     * put ours back cut to length. Their write is lost; the caller says so (status.json). Reset by each save.
     */
    @Volatile var overlapped = false
        private set

    /** #251: Warnings / status.json lines from the last sweep of this tree (temps kept as the only copy). */
    fun tempWarnings(): List<String> = kept[treeKey].orEmpty()

    /** [block], or null when the provider says the document is gone ([notFound]); access loss and other errors throw. */
    private fun <T> orGone(block: () -> T?): T? = try { block() } catch (e: AccessLostException) { throw e } catch (e: Exception) {
        if (notFound(e)) null else throw e
    }

    private fun sweepOne(rootDocId: String, id: String): String? {
        val info = orGone { tree.info(id) }
        val rawName = info?.takeIf { it.mime != Document.MIME_TYPE_DIR }?.let { orGone { tree.displayName(id) } }
        val target = rawName?.let(::tempTarget)
        val temp = target?.let { orGone { tree.read(id) } }
        if (temp == null) { journal.remove(treeKey, id); return null } // gone, or not ours: never deleted
        // #274: a sidecar temp is named with its folder in a warning.
        val tmpName by lazy { if (inSidecarDir(rootDocId, id)) "$DIR_NAME/$rawName" else rawName }
        val complete = journal.sha256(treeKey, id)?.let { ConfigCodec.sha256(temp) == it } ?: looksComplete(target, temp)
        val doc = tree.list(rootDocId)[target]?.takeIf { it.mime != Document.MIME_TYPE_DIR }
        val current = doc?.let { tree.read(it.docId) }
        if (complete && doc != null && current != null && !current.contentEquals(temp)) {
            if (current.isEmpty() || temp.startsWith(current)) {
                // Killed mid target write: the temp is the only full copy of the new version.
                val restored = runCatching { tree.write(doc.docId, temp); tree.read(doc.docId)?.contentEquals(temp) == true }.getOrDefault(false)
                if (!restored) return "Couldn't finish saving $target after the app was stopped: the new version is kept in $tmpName. Copy it over $target to keep your last edit."
            } else if (target == SafSource.FILE && ConfigCodec.decode(current) is ParseResult.Invalid) {
                // Someone else's launcher.json since then: not overwritten, and the edit isn't thrown away either.
                return "$target has problems, and the app was stopped while saving it: your last edit on the phone is kept in $tmpName."
            }
        }
        // Gone by now (#262: a not-found from the delete) counts as deleted; a refused delete is retried next start.
        val deleted = try { tree.delete(id) } catch (e: AccessLostException) { throw e } catch (e: Exception) { if (notFound(e)) true else throw e }
        if (!deleted) throw IOException("couldn't delete $tmpName")
        journal.remove(treeKey, id)
        return null
    }

    /** Whether `.configlauncher/` (when listed) lists [id]: only for naming a kept temp in a warning. */
    private fun inSidecarDir(rootDocId: String, id: String): Boolean = runCatching {
        val dir = tree.list(rootDocId)[DIR_NAME]?.takeIf { it.mime == Document.MIME_TYPE_DIR } ?: return@runCatching false
        tree.list(dir.docId).values.any { it.docId == id }
    }.getOrDefault(false)

    /** #274: the listing part of the start sweep for `.configlauncher/` (see [sweepRootTemps]); [only]: one target. */
    private fun sweepSidecarDir(rootDocId: String, only: String? = null, legacyOnly: Boolean = false): List<String> {
        val dir = tree.list(rootDocId)[DIR_NAME]?.takeIf { it.mime == Document.MIME_TYPE_DIR } ?: return emptyList()
        val journaled = journal.list(treeKey).toSet()
        val warnings = mutableListOf<String>()
        for ((n, c) in tree.list(dir.docId)) {
            if (c.docId in journaled) continue
            try {
                val target = tempTarget(n)
                if (target != null) {
                    if (legacyOnly || (only != null && target != only)) continue
                    val info = tree.info(c.docId) ?: continue
                    if (info.mime != Document.MIME_TYPE_DIR && info.size == 0L && tree.read(c.docId)?.isEmpty() == true) tree.delete(c.docId)
                    continue
                }
                val legacy = legacySidecarTarget(n) ?: continue
                if (only != null && legacy != only) continue
                settleLegacySidecar(rootDocId, c.docId, n, legacy)?.let { warnings += it }
            } catch (e: AccessLostException) { throw e } catch (_: Exception) {} // transient: next start
        }
        return warnings
    }

    /**
     * #274: a fixed-name `.configlauncher/<name>.tmp` (or a provider's ` (N)` copy of it) left by a pre-#274 build.
     * Those builds staged every save there, never deleted it and recorded no hash, so nothing says whether it is a
     * complete copy, a torn one, or an older version the user has since shortened or edited (Grace, #276 NO-GO at
     * 5fa0299). The user's current file always wins: it is never restored from, for either file, even when the target
     * is empty, missing or invalid, and it is never deleted as "outdated". It's deleted only when that loses nothing:
     * - it's empty;
     * - it's byte-identical to the target;
     * - (AGENTS.md only) it's byte-identical to a shipped template ([AgentDocs.SHIPPED_SHA256]): the app can write that
     *   again (Add agent instructions); a deleted AGENTS.md is still never re-created.
     * Anything else is kept, with one Warnings (▲) line naming it, every start until the user deletes it (or it comes to
     * match the target): [legacyBrokenTargetWarning] when the target is missing, empty or (launcher.json) invalid,
     * else [legacyKeptWarning] (Pragma, #293). Never a folder. Journaled temps don't come here ([sweepOne], #263 rules).
     */
    private fun settleLegacySidecar(rootDocId: String, id: String, tmpName: String, target: String): String? {
        val info = tree.info(id) ?: return null
        if (info.mime == Document.MIME_TYPE_DIR) return null
        val temp = tree.read(id) ?: return null
        val current = tree.list(rootDocId)[target]?.takeIf { it.mime != Document.MIME_TYPE_DIR }?.let { tree.read(it.docId) }
        val delete = temp.isEmpty() || (current != null && current.contentEquals(temp)) ||
            (target == AgentDocs.NAME && ConfigCodec.sha256(temp) in AgentDocs.SHIPPED_SHA256)
        if (!delete) {
            val broken = current == null || current.isEmpty() ||
                (target == SafSource.FILE && ConfigCodec.decode(current) is ParseResult.Invalid)
            return if (broken) legacyBrokenTargetWarning(tmpName, target) else legacyKeptWarning(tmpName, target)
        }
        tree.delete(id)
        return null
    }

    private fun sweepLegacy(rootDocId: String, name: String) {
        val c = tree.list(rootDocId)[legacyTemp(name)] ?: return
        if ((tree.info(c.docId)?.mime ?: c.mime) != Document.MIME_TYPE_DIR) tree.delete(c.docId)
    }

    companion object {
        private const val BIN = "application/octet-stream"
        private const val DIR_NAME = ".configlauncher"
        /** #274: the fixed sidecar temp names of pre-#274 builds, maybe renamed ` (N)` before or after `.tmp`. */
        private val LEGACY_SIDECAR = Regex("""(launcher\.json|AGENTS\.md)(?: \(\d+\))?\.tmp(?: \(\d+\))?""")
        /** The root file a pre-#274 sidecar temp named [displayName] belongs to, or null. */
        fun legacySidecarTarget(displayName: String): String? = LEGACY_SIDECAR.matchEntire(displayName)?.groupValues?.get(1)
        /** #274: the ▲ line for a kept pre-#274 sidecar temp next to a valid target (ux.md §4.3, AQ-4). */
        fun legacyKeptWarning(tmpName: String, target: String) =
            "An older version of the app left $DIR_NAME/$tmpName, and it doesn't match $target. It's kept in case it's your only copy. Delete it once you don't need it."
        /** #293: the ▲ line for a kept pre-#274 sidecar temp next to a missing, empty or (launcher.json) invalid target. */
        fun legacyBrokenTargetWarning(tmpName: String, target: String) =
            "An older version of the app left $DIR_NAME/$tmpName, and $target is missing or broken. The .tmp may be your only good copy. Copy it over $target to keep it."
        /** #262: starts in a row a journaled temp may fail to settle before its entry (never the file) is dropped. */
        const val MAX_STRIKES = 5
        /** The root files that go through [writeVerified] (launcher.json, AGENTS.md). */
        val ROOT_FILES = listOf(SafSource.FILE, AgentDocs.NAME)
        /** The fixed root temp name of 7803222 builds (`.launcher.json.tmp`), swept where a listing shows it. */
        fun legacyTemp(name: String) = ".$name.tmp"
        /** #251: exactly the root temp names [writeVerified] makes (token: 8 lowercase hex), maybe renamed ` (N)`. */
        private val TEMP_NAME = Regex("""\.(launcher\.json|AGENTS\.md)\.[0-9a-f]{8}(?: \(\d+\))?\.tmp""")
        /** The root file a temp named [displayName] belongs to, or null when it isn't one of ours. */
        fun tempTarget(displayName: String): String? = TEMP_NAME.matchEntire(displayName)?.groupValues?.get(1)
        /** Without a recorded hash: launcher.json must load, AGENTS.md must be non-empty. */
        private fun looksComplete(target: String, temp: ByteArray) =
            if (target == SafSource.FILE) ConfigCodec.decode(temp) is ParseResult.Ok else temp.isNotEmpty()
        /** A provider saying the document is gone (deleted, or "not a descendant" of the tree any more). */
        private fun notFound(e: Exception) =
            e is java.io.FileNotFoundException || e is SecurityException || e is IllegalArgumentException
        private fun ByteArray.startsWith(p: ByteArray) = size >= p.size && p.indices.all { this[it] == p[it] }
        /**
         * #340 follow-up (Grace): [back] is another writer's shorter JSON file written over the start of [ours]: the same
         * length, not a launcher.json that loads (a same-length file of theirs that loads is theirs), and from some
         * point on byte for byte ours, with a whole JSON object before that point (their file).
         */
        internal fun headOverOurs(back: ByteArray, ours: ByteArray): Boolean {
            if (back.size != ours.size || back.contentEquals(ours)) return false
            if (ConfigCodec.decode(back) is ParseResult.Ok) return false
            var k = back.size
            while (k > 0 && back[k - 1] == ours[k - 1]) k--  // where the common tail starts
            if (k == back.size) return false                    // no tail of ours at all
            // Their file ends somewhere in [k, size): try the ends a JSON object can have.
            return (maxOf(k, 1) until back.size).any { end ->
                val c = back[end - 1].toInt().toChar()
                (c == '}' || c.isWhitespace()) &&
                    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(String(back, 0, end, Charsets.UTF_8)) is kotlinx.serialization.json.JsonObject }.getOrDefault(false)
            }
        }
        /** Per tree, the last sweep's [tempWarnings] (SafSource instances are short-lived). */
        private val kept = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
        /** One save or sweep at a time per process, so a start-up sweep never deletes a temp a save is using. */
        private val lock = Any()
    }
}
