package dev.nasawa.configlauncher.store

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/**
 * #339 (Claw, A51): launcher.json was left as the app's JSON followed by the tail of a longer file. These pin that
 * [SafIo] leaves exactly the new bytes even on a provider whose "w"/"wt" open doesn't truncate, through a real
 * ContentResolver and provider (Robolectric), and that [SafIo.replace] checks and writes in one open.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SafIoTest {
    /** A provider that opens every write mode without truncating (the old bytes stay past what's written). */
    class NoTruncateProvider : ContentProvider() {
        var allowRw = true
        val opened = mutableListOf<String>()
        lateinit var file: File
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opened += mode
            if (mode == "rw" && !allowRw) throw java.io.FileNotFoundException("rw not supported")
            val m = when {
                mode.startsWith("r") && mode.length == 1 -> ParcelFileDescriptor.MODE_READ_ONLY
                mode.contains("r") -> ParcelFileDescriptor.MODE_READ_WRITE
                else -> ParcelFileDescriptor.MODE_WRITE_ONLY
            } // never MODE_TRUNCATE: the provider bug
            return ParcelFileDescriptor.open(file, m or ParcelFileDescriptor.MODE_CREATE)
        }
        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String = "application/json"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private lateinit var provider: NoTruncateProvider
    private lateinit var file: File
    private val uri = Uri.parse("content://reflow.test.notrunc/launcher.json")
    private val resolver get() = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
    private val agentFile = ("{\"revision\":38,\"items\":[" + "\"a-agent-22\",".repeat(30) + "\"end\"]}").toByteArray()
    private val ours = "{\"revision\":38,\"items\":[\"a-camera\"]}".toByteArray()

    @Before fun setUp() {
        provider = Robolectric.setupContentProvider(NoTruncateProvider::class.java, "reflow.test.notrunc")
        file = File.createTempFile("launcher", ".json").apply { deleteOnExit() }
        provider.file = file
        file.writeBytes(agentFile)
    }

    @Test fun theProviderReallyDoesNotTruncate() {
        // The fake is faithful: a plain "wt" stream leaves the longer file's tail (Claw's torn shape).
        resolver.openOutputStream(uri, "wt")!!.use { it.write(ours) }
        assertEquals(agentFile.size.toLong(), file.length())
    }

    @Test fun writeCutsTheOldTailItself() {
        SafIo.write(resolver, uri, ours)
        assertEquals("wt", provider.opened.single())
        assertArrayEquals("exactly our bytes, no trailing ones", ours, file.readBytes())
    }

    @Test fun writeOfALongerFileKeepsItAll() {
        file.writeBytes(ours)
        SafIo.write(resolver, uri, agentFile)
        assertArrayEquals(agentFile, file.readBytes())
    }

    @Test fun replaceChecksThenTruncatesInOneOpen() {
        var seen: ByteArray? = null
        assertTrue(SafIo.replace(resolver, uri, ours, 1 shl 20) { seen = it })
        assertEquals(listOf("rw"), provider.opened)
        assertArrayEquals("the check saw the file as it was in this open", agentFile, seen)
        assertArrayEquals(ours, file.readBytes())
    }

    @Test fun aFailedCheckWritesNothing() {
        try {
            SafIo.replace(resolver, uri, ours, 1 shl 20) { throw ConcurrentChangeException(it) }
            fail()
        } catch (e: ConcurrentChangeException) { assertArrayEquals(agentFile, e.current) }
        assertArrayEquals(agentFile, file.readBytes())
    }

    @Test fun withoutReadWriteReplaceSaysSoAndChangesNothing() {
        provider.allowRw = false
        assertFalse(SafIo.replace(resolver, uri, ours, 1 shl 20) { fail("no check without an open") })
        assertArrayEquals(agentFile, file.readBytes())
    }

    @Test fun replaceOnTruncatesALongerFileItself() {
        // A plain file longer than ours (no provider involved): replaceOn's own truncate.
        RandomAccessFile(file, "rw").use { raf ->
            SafIo.replaceOn(raf.channel, raf.channel, ours, 1 shl 20) { }
            assertEquals(ours.size.toLong(), raf.length())
        }
        assertArrayEquals(ours, file.readBytes())
    }
}
