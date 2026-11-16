package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.store.AgentDocs.Outcome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ARCHITECTURE.md §20.1 (#87). */
class AgentDocsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val template = (File("../templates/config-folder/AGENTS.md").takeIf { it.isFile } ?: File("templates/config-folder/AGENTS.md")).readBytes()
    private val old = "old shipped".toByteArray()
    private fun folder() = LocalFileSource(tmp.newFolder(), sidecars = true)
    private fun LocalFileSource.agents() = File(dir, AgentDocs.NAME).takeIf { it.isFile }?.readBytes()

    @Test fun currentTemplateHashIsListedAsShipped() {
        assertTrue("templates/config-folder/AGENTS.md changed: add its SHA-256 to AgentDocs.SHIPPED_SHA256",
            ConfigCodec.sha256(template) in AgentDocs.SHIPPED_SHA256)
    }

    @Test fun writesOnceIntoEmptyFolder() {
        val f = folder()
        assertEquals(Outcome.WROTE, AgentDocs.sync(f, template, writtenBefore = false, enabled = true))
        assertArrayEquals(template, f.agents())
        assertEquals(Outcome.UP_TO_DATE, AgentDocs.sync(f, template, writtenBefore = true, enabled = true))
        assertFalse(File(f.dir, "AGENTS.md.tmp").exists())
    }

    @Test fun neverOverwritesUserEdits() {
        val f = folder()
        File(f.dir, "AGENTS.md").writeText("my own rules")
        assertEquals(Outcome.USER_CHANGED, AgentDocs.sync(f, template, writtenBefore = true, enabled = true))
        assertEquals("my own rules", File(f.dir, "AGENTS.md").readText())
        assertTrue(AgentDocs.remember(Outcome.USER_CHANGED))
    }

    @Test fun updatesAnUntouchedShippedVersion() {
        // The bundled template is itself a shipped version, so once a newer one ships, this file is updated.
        assertTrue(ConfigCodec.sha256(template) in AgentDocs.SHIPPED_SHA256)
        assertEquals(Outcome.UPDATED, AgentDocs.decide("new".toByteArray(), template, true, true, false))
        assertEquals(Outcome.USER_CHANGED, AgentDocs.decide(template, old, true, true, false))
    }

    @Test fun deletedFileIsNotRecreatedButAddAgainRestoresIt() {
        val f = folder()
        assertEquals(Outcome.USER_DELETED, AgentDocs.sync(f, template, writtenBefore = true, enabled = true))
        assertNull(f.agents())
        assertEquals(Outcome.WROTE, AgentDocs.sync(f, template, writtenBefore = true, enabled = true, force = true))
        assertArrayEquals(template, f.agents())
    }

    @Test fun addAgainOverwritesEvenUserEdits() {
        val f = folder()
        File(f.dir, "AGENTS.md").writeText("mine")
        assertEquals(Outcome.UPDATED, AgentDocs.sync(f, template, writtenBefore = true, enabled = false, force = true))
        assertArrayEquals(template, f.agents())
    }

    @Test fun settingOffWritesNothing() {
        val f = folder()
        assertEquals(Outcome.OFF, AgentDocs.sync(f, template, writtenBefore = false, enabled = false))
        assertNull(f.agents())
    }

    @Test fun appOnlyModeHasNoFolder() {
        val f = LocalFileSource(tmp.newFolder(), sidecars = false)
        assertEquals(Outcome.FAILED, AgentDocs.sync(f, template, writtenBefore = false, enabled = true))
        assertNull(f.agents())
    }

    // ---- ux.md §7.1 menu action ----

    @Test fun menuLabelFollowsFilePresence() {
        assertEquals(AgentDocs.MenuAction.ADD, AgentDocs.menuAction(null))
        assertEquals(AgentDocs.MenuAction.UPDATE, AgentDocs.menuAction(template))
    }

    @Test fun addWritesWhenAbsent() {
        val f = folder()
        assertEquals(AgentDocs.Update.ADDED, AgentDocs.update(f, template, confirmed = false))
        assertArrayEquals(template, f.agents())
    }

    @Test fun updateReplacesUntouchedFileSilently() {
        val f = folder()
        File(f.dir, "AGENTS.md").writeBytes(template)   // a shipped version
        val newer = template + "\nmore".toByteArray()
        assertEquals(AgentDocs.Update.UPDATED, AgentDocs.update(f, newer, confirmed = false))
        assertArrayEquals(newer, f.agents())
        assertFalse(File(f.dir, ".configlauncher/backup").exists())
    }

    @Test fun editedFileNeedsConfirmThenIsBackedUp() {
        val f = folder()
        File(f.dir, "AGENTS.md").writeText("my edits")
        assertEquals(AgentDocs.Update.NEEDS_CONFIRM, AgentDocs.update(f, template, confirmed = false))
        assertEquals("my edits", File(f.dir, "AGENTS.md").readText())
        val now = 1_792_000_000_000L
        assertEquals(AgentDocs.Update.UPDATED, AgentDocs.update(f, template, confirmed = true, nowMillis = now))
        assertArrayEquals(template, f.agents())
        val backup = File(f.dir, ".configlauncher/backup/" + AgentDocs.backupName(now))
        assertEquals("my edits", backup.readText())
        assertTrue(backup.name, Regex("""^AGENTS\.\d{8}-\d{6}\.md$""").matches(backup.name))
    }

    @Test fun appOnlyUpdateDoesNothing() {
        val f = LocalFileSource(tmp.newFolder(), sidecars = false)
        assertEquals(AgentDocs.Update.FAILED, AgentDocs.update(f, template, confirmed = true))
        assertNull(f.agents())
    }

    // #262: no backup, no overwrite — and the reason must be visible (a swallowed error fails these).
    @Test fun confirmedReplaceLeavesTheFileWhenTheBackupIsHidden() {
        val f = folder()
        File(f.dir, "AGENTS.md").writeText("my edits")
        val hidden = object : ConfigSource by f {
            override fun writeSidecar(subdir: String, name: String, bytes: ByteArray) {
                throw FolderHiddenException(".configlauncher")
            }
            override fun writeRoot(name: String, bytes: ByteArray) {
                fail("no overwrite when the backup failed")
            }
        }
        val now = 1_792_000_000_000L
        assertEquals(AgentDocs.Update.FAILED_HIDDEN, AgentDocs.update(hidden, template, confirmed = true, nowMillis = now))
        assertEquals("my edits", File(f.dir, "AGENTS.md").readText())
        assertFalse(File(f.dir, ".configlauncher/backup").exists())
        assertEquals(Msg(R.string.agent_docs_replace_hidden), AgentDocs.Update.FAILED_HIDDEN.message())
    }

    @Test fun confirmedReplaceLeavesTheFileWhenTheBackupFails() {
        val f = folder()
        File(f.dir, "AGENTS.md").writeText("my edits")
        val full = object : ConfigSource by f {
            override fun writeSidecar(subdir: String, name: String, bytes: ByteArray) {
                throw java.io.IOException("full")
            }
            override fun writeRoot(name: String, bytes: ByteArray) {
                fail("no overwrite when the backup failed")
            }
        }
        assertEquals(AgentDocs.Update.FAILED, AgentDocs.update(full, template, confirmed = true, nowMillis = 1_792_000_000_000L))
        assertEquals("my edits", File(f.dir, "AGENTS.md").readText())
        assertEquals(Msg(R.string.agent_docs_replace_failed), AgentDocs.Update.FAILED.message())
    }

    @Test fun replaceFailureIsShownInTheMenuAction() {
        val src = (File("src/main/java/dev/nasawa/configlauncher/ui/HomeScreen.kt").takeIf { it.isFile }
            ?: File("app/src/main/java/dev/nasawa/configlauncher/ui/HomeScreen.kt")).readText()
        val fn = src.substringAfter("val runAgentUpdate").substringBefore("val launchApp")
        assertTrue("#262: runAgentUpdate must show FAILED, not swallow it", "Update.FAILED" in fn)
        assertTrue("#262: hidden-folder failure must be shown", "FAILED_HIDDEN" in fn)
        assertTrue("#262: the message must be toasted (this fails if the error is swallowed)",
            "message()" in fn && "LENGTH_LONG" in fn)
    }

    // #196: ConfigStore.start() runs this on every cold start, so an app upgrade refreshes an untouched AGENTS.md.
    @Test fun startupSyncAfterUpgradeReplacesShippedFile() {
        val f = folder()
        File(f.dir, "AGENTS.md").writeBytes(template)          // written by the previous app version
        val newer = "newer template".toByteArray()             // bundled by the upgraded app
        assertEquals(Outcome.UPDATED, AgentDocs.sync(f, newer, writtenBefore = true, enabled = true))
        assertArrayEquals(newer, f.agents())
    }

    @Test fun startupSyncWithoutUpgradeDoesNotRewrite() {
        val f = folder()
        val file = File(f.dir, "AGENTS.md").apply { writeBytes(template); setLastModified(1_000_000L) }
        assertEquals(Outcome.UP_TO_DATE, AgentDocs.sync(f, template, writtenBefore = true, enabled = true))
        assertEquals(1_000_000L, file.lastModified())
    }

    @Test fun configStoreStartSyncsAgentDocs() {
        val src = (File("src/main/java/dev/nasawa/configlauncher/store/ConfigStore.kt").takeIf { it.isFile }
            ?: File("app/src/main/java/dev/nasawa/configlauncher/store/ConfigStore.kt")).readText()
        val start = src.substringAfter("fun start()").substringBefore("fun stop()")
        assertTrue("#196: ConfigStore.start() must call syncAgentDocs()", "syncAgentDocs()" in start)
    }
}
