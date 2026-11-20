package dev.nasawa.configlauncher.store

import android.content.ContentResolver
import android.net.Uri
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * #339: the two ways [SafSource] puts bytes into a SAF document.
 *
 * [write] opens "wt" (the only open mode the app used before #339) and then, where the provider gave a real file
 * descriptor, truncates it to the new length itself, so a provider that ignores the "t" can't leave the old file's
 * tail behind our bytes. [replace] does the whole replace in one "rw" open: it reads the current bytes, lets the
 * caller check them (a write by someone else since the caller's own look stops the save before anything changes),
 * then truncates, writes and checks the length through the same descriptor. No other provider call comes between the
 * check and the write, so the window where another writer's whole `cp` can land unseen shrinks from two provider
 * round trips to a few local syscalls.
 */
internal object SafIo {
    fun write(resolver: ContentResolver, uri: Uri, bytes: ByteArray) {
        (resolver.openOutputStream(uri, "wt") ?: throw IOException("can't open $uri")).use { out ->
            out.write(bytes); out.flush()
            // A pipe (a cloud provider) has no size or truncate: nothing to cut there.
            (out as? FileOutputStream)?.let { f -> runCatching { f.channel }.getOrNull() }?.let { ch ->
                val size = runCatching { ch.size() }.getOrNull()
                if (size != null && size > bytes.size) ch.truncate(bytes.size.toLong())
            }
        }
    }

    /**
     * [check] gets the document's bytes as they are in this open and throws (e.g. [ConcurrentChangeException]) to stop
     * with nothing written. Returns false, having changed nothing, when the provider can't give a seekable read/write
     * descriptor (the caller then uses [write]); anything that fails after the truncate throws.
     */
    fun replace(resolver: ContentResolver, uri: Uri, bytes: ByteArray, maxBytes: Int, check: (ByteArray) -> Unit): Boolean {
        val pfd = try { resolver.openFileDescriptor(uri, "rw") } catch (e: SecurityException) { throw e } catch (_: Exception) { null } ?: return false
        pfd.use {
            // Not closed on their own: the descriptor belongs to pfd.
            val input = FileInputStream(it.fileDescriptor).channel
            val output = FileOutputStream(it.fileDescriptor).channel
            if (runCatching { input.size() }.isFailure) return false
            replaceOn(input, output, bytes, maxBytes, check)
        }
        return true
    }

    /** [replace]'s steps on an open file (unit-tested on a plain file). */
    fun replaceOn(input: FileChannel, output: FileChannel, bytes: ByteArray, maxBytes: Int, check: (ByteArray) -> Unit) {
        check(readAll(input, maxBytes))
        output.truncate(0)
        val buf = ByteBuffer.wrap(bytes)
        var pos = 0L
        while (buf.hasRemaining()) pos += output.write(buf, pos)
        runCatching { output.force(false) }
        // Longer than ours: another writer's bytes landed after our truncate. The caller's read-back sees the same
        // file and decides (SafWrites); nothing is cut here, so their bytes aren't hidden.
    }

    fun readAll(ch: FileChannel, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(8192)
        var pos = 0L
        while (true) {
            buf.clear()
            val n = ch.read(buf, pos)
            if (n < 0) break
            if (n == 0) { if (pos >= ch.size()) break else continue }
            out.write(buf.array(), 0, n); pos += n
            if (out.size() > maxBytes) throw IOException("launcher.json is larger than ${maxBytes / 1024} KiB")
        }
        return out.toByteArray()
    }
}
