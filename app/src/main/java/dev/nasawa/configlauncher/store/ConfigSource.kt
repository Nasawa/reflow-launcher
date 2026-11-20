package dev.nasawa.configlauncher.store

import java.io.IOException

/** Cheap change detector: (document id, mtime, size). Equal stamps mean "no need to read". */
data class Stamp(val docId: String, val lastModified: Long, val size: Long)

/** Where launcher.json lives. Implementations: [LocalFileSource] (app storage), SafSource (picked folder). */
interface ConfigSource {
    val displayName: String
    /** False for app storage: there is no folder grant to reconnect, so B4 hides "Reconnect". */
    val reconnectable: Boolean get() = true
    /** #57: full location of launcher.json for people and agents; null for app storage. */
    val location: String? get() = null
    /** null when launcher.json does not exist. */
    fun stamp(): Stamp?
    /** null when launcher.json does not exist. Must enforce [maxBytes]. */
    fun read(maxBytes: Int): ByteArray?
    /**
     * Replace launcher.json with [bytes] safely (implementation-specific, see ARCHITECTURE.md §5). Throws on failure.
     *
     * #317: [expectHash] is the SHA-256 of the launcher.json the caller decided on ([ABSENT]: it had none; null: no
     * check). Each source re-checks it as late as it can, right before the target is replaced, and throws
     * [ConcurrentChangeException] (nothing of ours left in the file) when someone else's write landed since. There is no
     * compare-and-swap on SAF or files, so a write landing after that last check is the window that's left (§5; #339:
     * on SAF the last check shares the target's own open where the provider allows).
     */
    @Throws(IOException::class)
    fun write(bytes: ByteArray, expectHash: String? = null)

    /**
     * #339: true when the last [write] succeeded but found another writer's bytes after ours (their file landed after
     * our truncate and we wrote over its start), so it put ours back cut to length and their write is lost.
     */
    fun lastWriteOverlapped(): Boolean = false

    /**
     * Write a sidecar file at `.configlauncher/<subdir>/<name>` ([subdir] may be empty), creating
     * directories as needed. Never touches launcher.json, so it never changes [stamp] (no reload loop).
     */
    /** Names of the files next to launcher.json (for OQ-21 conflict detection). */
    fun siblingNames(): List<String> = emptyList()

    @Throws(IOException::class)
    fun writeSidecar(subdir: String, name: String, bytes: ByteArray)

    /** #139: cheap change check for a sidecar; null when absent or unknown (the caller then reads). */
    fun sidecarStamp(subdir: String, name: String): Stamp? = null

    /** `.configlauncher/<subdir>/<name>` ([subdir] may be empty); null when absent. */
    fun readSidecar(subdir: String, name: String, maxBytes: Int): ByteArray? = throw UnsupportedOperationException("no folder")

    /** §20.1: a file next to launcher.json (e.g. AGENTS.md); null when absent. Only folder sources have one. */
    fun readRoot(name: String, maxBytes: Int): ByteArray? = throw UnsupportedOperationException("no folder")

    /** #244/#251: names this folder won't create again because the provider hides the existing one ([SafDirs]). */
    fun suppressedNames(): List<String> = emptyList()

    /** #251: Warnings / status.json lines for save temps the start sweep kept as the only copy ([SafWrites]). */
    fun tempWarnings(): List<String> = emptyList()

    /** #251: names suppressed since the last call, each returned once (for the one-shot notice). */
    fun takeNewlySuppressed(): List<String> = emptyList()

    /** #251: the user picked this folder again: forget its suppressions so creates are tried again. */
    fun clearSuppression() {}

    /** §20.1: write a file next to launcher.json through the same temp + verify path as launcher.json. */
    @Throws(IOException::class)
    fun writeRoot(name: String, bytes: ByteArray): Unit = throw UnsupportedOperationException("no folder")
}

/**
 * #317: launcher.json changed under a save (an agent's write landed after the caller's hash check). [current] is what the
 * file holds now (null: absent or unreadable). The save left the file as the other writer did: the caller merges or
 * re-checks, never reports ok.
 */
class ConcurrentChangeException(val current: ByteArray?, message: String = "launcher.json changed while saving") : IOException(message)

/** #317: [ConfigSource.write]'s `expectHash` for "there was no launcher.json". */
const val ABSENT = "absent"

/** #317: the hash of [bytes] as [ConfigSource.write]'s `expectHash` compares it ([ABSENT] for none). */
internal fun expectOf(bytes: ByteArray?): String = bytes?.let { dev.nasawa.configlauncher.config.ConfigCodec.sha256(it) } ?: ABSENT

/** #317: throws [ConcurrentChangeException] unless [now] (the target's bytes read just now) still matches [expectHash]. */
internal fun requireUnchanged(expectHash: String?, now: ByteArray?) {
    if (expectHash == null) return
    val same = if (expectHash == ABSENT) now == null || now.isEmpty() else now != null && expectOf(now) == expectHash
    if (!same) throw ConcurrentChangeException(now)
}

internal fun java.io.InputStream.readCapped(maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > maxBytes) throw IOException("launcher.json is larger than ${maxBytes / 1024} KiB")
    }
    return out.toByteArray()
}
