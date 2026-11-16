package dev.nasawa.configlauncher.store

import android.provider.DocumentsContract.Document
import dev.nasawa.configlauncher.config.ConfigCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * #274: `.configlauncher/` sidecar temps follow the #251/#263 rules (a new name per save, journaled, deleted after the
 * save, restored or kept by the start sweep), a fixed-name temp from a pre-#274 build is settled safely, and a null
 * display name after a create is never taken as success.
 */
class SidecarTempsTest {
    private val dir = Document.MIME_TYPE_DIR
    private val bin = "application/octet-stream"
    private val json = "application/json"
    private var keyN = 0
    private fun key() = "sidecar-${System.identityHashCode(this)}-${keyN++}"

    private class MemJournal : TempJournal {
        val ids = mutableMapOf<String, MutableList<String>>()
        val shas = mutableMapOf<String, String?>()
        override fun add(treeKey: String, docId: String, sha256: String?) { ids.getOrPut(treeKey) { mutableListOf() } += docId; shas[docId] = sha256 }
        override fun remove(treeKey: String, docId: String) { ids[treeKey]?.remove(docId) }
        override fun list(treeKey: String) = ids[treeKey].orEmpty().toList()
        override fun sha256(treeKey: String, docId: String) = shas[docId]
    }

    /** A force-stop: no catch in the save runs (an Error); `finally` and later calls reach a dead provider. */
    private class Killed : Error("force-stop")

    /**
     * A Downloads-style provider on top of [SafDirsTest.FakeTree] (which already renames a colliding create to
     * `name (N)`): `.configlauncher` is listed, but listings of it can hide every `.tmp` name, and a killed
     * process's calls never reach it.
     */
    private class Provider(val t: SafDirsTest.FakeTree = SafDirsTest.FakeTree()) : DocTree by t {
        var hideTmpInSidecar = false
        var dead = false
        /** Kills the process during the next write to a root document with this name, leaving [leave] in it. */
        var killOn: String? = null
        var leave: (ByteArray) -> ByteArray = { ByteArray(0) }
        var nullNames = false
        val asked = mutableListOf<String>()
        private fun sidecar() = t.docs.values.firstOrNull { it.parent == "root" && it.name == ".configlauncher" }?.id
        override fun list(parentDocId: String) = t.list(parentDocId).let { m ->
            if (hideTmpInSidecar && parentDocId == sidecar()) m.filterKeys { !it.endsWith(".tmp") && !it.contains(".tmp ") } else m
        }
        override fun create(parentDocId: String, mime: String, name: String): String = t.create(parentDocId, mime, name).also { asked += name }
        override fun displayName(docId: String) = if (nullNames) null else t.displayName(docId)
        override fun delete(docId: String) = if (dead) false else t.delete(docId)
        override fun write(docId: String, bytes: ByteArray) {
            if (dead) throw Killed()
            val d = t.docs[docId]
            if (d != null && d.parent == "root" && d.name == killOn) { killOn = null; t.write(docId, leave(bytes)); dead = true; throw Killed() }
            t.write(docId, bytes)
        }
    }

    private class Setup(val p: Provider, val key: String, val journal: MemJournal, val token: () -> String) {
        val t get() = p.t
        /** A new process: fresh SafWrites / SafDirs; the journal file survives. */
        fun start() = SafWrites(p, SafDirs(p, key), key, journal, token)
        fun sidecar() = t.docs.values.single { it.parent == "root" && it.name == ".configlauncher" }.id
        fun inSidecar() = t.namesIn(sidecar())
        fun text(parent: String, name: String) = t.docs.values.firstOrNull { it.parent == parent && it.name == name }?.let { String(t.read(it.id)!!) }
        fun root(name: String) = text("root", name)
        fun numbered() = t.docs.values.filter { "(" in it.name }.map { it.name }
    }
    private fun counter(): () -> String { var n = 0; return { "%08x".format(n++) } }
    private fun setup(token: () -> String = counter()): Setup {
        val p = Provider(); p.t.add("root", ".configlauncher", dir)
        return Setup(p, key(), MemJournal(), token)
    }

    private val template = (File("../templates/config-folder/AGENTS.md").takeIf { it.isFile } ?: File("templates/config-folder/AGENTS.md")).readBytes()
    private val valid = """{"schemaVersion":1,"pages":[{"id":"home","items":[]}]}""" + "\n"
    private val validNew = """{"schemaVersion":1,"pages":[{"id":"home","items":[]},{"id":"two","items":[]}]}""" + "\n"

    private fun Setup.replaceAgents(w: SafWrites, text: String) = w.writeVerified("root", "AGENTS.md", bin, text.toByteArray(), "AGENTS.md")

    // ---- (1) kill mid-Replace ----

    @Test fun killMidReplaceRestoresAgentsMdFromTheSidecarTempOnNextStart() {
        for (leave in listOf<(ByteArray) -> ByteArray>({ ByteArray(0) }, { it.copyOf(9) })) { // truncated / torn
            val s = setup()
            s.replaceAgents(s.start(), "# agents v1\n")
            s.p.killOn = "AGENTS.md"; s.p.leave = leave
            assertThrows(Killed::class.java) { s.replaceAgents(s.start(), "# agents v2, the full new file\n") }
            s.p.dead = false
            val left = s.inSidecar().single()
            assertTrue(left, SafWrites.tempTarget(left) == "AGENTS.md") // `.AGENTS.md.<8hex>.tmp`, not a fixed name
            assertEquals("the temp holds the only full copy", "# agents v2, the full new file\n", s.text(s.sidecar(), left))
            assertNotEquals("# agents v2, the full new file\n", s.root("AGENTS.md"))
            assertEquals(1, s.journal.list(s.key).size)

            val warnings = s.start().sweepRootTemps("root") // next start
            assertEquals("# agents v2, the full new file\n", s.root("AGENTS.md"))
            assertEquals(emptyList<String>(), warnings)
            assertEquals(emptyList<String>(), s.inSidecar())
            assertTrue(s.journal.list(s.key).isEmpty())
        }
    }

    @Test fun aFailedSidecarRestoreKeepsTheTempAndNamesItWithItsFolder() {
        val s = setup()
        s.replaceAgents(s.start(), "v1")
        s.p.killOn = "AGENTS.md"
        assertThrows(Killed::class.java) { s.replaceAgents(s.start(), "v2") }
        s.p.dead = false
        val left = s.inSidecar().single()
        val readOnly = object : DocTree by s.p { override fun write(docId: String, bytes: ByteArray) = throw IOException("read-only") }
        val warnings = SafWrites(readOnly, SafDirs(readOnly, s.key), s.key, s.journal).sweepRootTemps("root")
        assertEquals(listOf("Couldn't finish saving AGENTS.md after the app was stopped: the new version is kept in .configlauncher/$left. Copy it over AGENTS.md to keep your last edit."), warnings)
        assertEquals("v2", s.text(s.sidecar(), left))
        assertEquals(emptyList<String>(), s.start().sweepRootTemps("root"))
        assertEquals("v2", s.root("AGENTS.md"))
        assertEquals(emptyList<String>(), s.inSidecar())
    }

    @Test fun aFailedSaveThatCantRestoreKeepsTheSidecarTempAndSaysWhere() {
        val s = setup(token = { "cafef00d" })
        s.replaceAgents(s.start(), "old")
        val failing = object : DocTree by s.p {
            override fun write(docId: String, bytes: ByteArray) {
                if (s.t.docs[docId]?.name == "AGENTS.md") { s.t.write(docId, "ha".toByteArray()); throw IOException("disk full") }
                s.p.write(docId, bytes)
            }
        }
        val w = SafWrites(failing, SafDirs(failing, s.key), s.key, s.journal, { "cafef00d" })
        val e = assertThrows(IOException::class.java) { w.writeVerified("root", "AGENTS.md", bin, "new".toByteArray(), "AGENTS.md") }
        assertTrue(e.message!!, e.message!!.contains("kept in .configlauncher/.AGENTS.md.cafef00d.tmp"))
        assertEquals("new", s.text(s.sidecar(), ".AGENTS.md.cafef00d.tmp"))
        assertTrue("out of the journal, so no sweep deletes it", s.journal.list(s.key).isEmpty())
        s.start().sweepRootTemps("root")
        assertEquals("unjournaled with bytes: left alone", "new", s.text(s.sidecar(), ".AGENTS.md.cafef00d.tmp"))
    }

    // ---- (2) re-runs on a collision-renaming, name-hiding provider ----

    @Test fun repeatedReplacesAndStartsLeaveNoNumberedOrFixedNameTemps() {
        // The listing of `.configlauncher` hides every temp, and the token repeats, so creates collide with
        // a hidden leftover and come back as `.AGENTS.md.aaaaaaaa (1).tmp`: each is used and deleted by id.
        val s = setup(token = { "aaaaaaaa" })
        s.p.hideTmpInSidecar = true
        repeat(6) { i ->
            s.replaceAgents(s.start(), "agents $i")
            s.start().sweepRootTemps("root")
            if (i % 2 == 1) { // a kill mid-Replace every other round, then a start
                s.p.killOn = "AGENTS.md"
                assertThrows(Killed::class.java) { s.replaceAgents(s.start(), "agents $i killed") }
                s.p.dead = false
                assertEquals(emptyList<String>(), s.start().sweepRootTemps("root"))
                assertEquals("agents $i killed", s.root("AGENTS.md"))
            }
            s.start().writeVerified("root", "launcher.json", json, "{\"v\":$i}".toByteArray(), "layout")
        }
        assertEquals("agents 5 killed", s.root("AGENTS.md"))
        assertEquals(emptyList<String>(), s.numbered())
        assertEquals("no temp left at all, listed or not", emptyList<String>(), s.inSidecar())
        assertFalse(s.p.asked.toString(), "AGENTS.md.tmp" in s.p.asked || "launcher.json.tmp" in s.p.asked)
        assertEquals(listOf(".configlauncher", "AGENTS.md", "launcher.json"), s.t.namesIn("root"))
        assertTrue(s.journal.list(s.key).isEmpty())
    }

    // ---- (3) null display name ----

    @Test fun aNullDisplayNameAfterCreateIsNotTakenAsSuccess() {
        // The listing misses an existing `AGENTS.md.tmp`, the create is renamed `AGENTS.md (1).tmp`, and the
        // provider can't say its name. It must not be used (written into) as if it were `AGENTS.md.tmp`.
        val p = Provider(); val k = key()
        val cl = p.t.add("root", ".configlauncher", dir)
        val original = p.t.add(cl, "AGENTS.md.tmp", bin); p.t.write(original, "x".toByteArray())
        p.t.unlisted += "AGENTS.md.tmp"; p.nullNames = true
        val e = assertThrows(IOException::class.java) { SafDirs(p, k).ensure(cl, "AGENTS.md.tmp", bin) }
        assertTrue(e is FolderHiddenException)
        assertEquals("the unconfirmed (N) copy was deleted while still empty", listOf("AGENTS.md.tmp"), p.t.namesIn(cl))
        assertEquals("x", String(p.t.read(original)!!))
    }

    @Test fun aNullDisplayNameIsUsedWhenAFreshListingConfirmsIt() {
        val p = Provider(); val k = key(); p.nullNames = true
        val id = SafDirs(p, k).ensure("root", ".configlauncher", dir)
        assertEquals(listOf(".configlauncher"), p.t.namesIn("root"))
        assertEquals(id, SafDirs(p, k).ensure("root", ".configlauncher", dir))
        assertEquals(1, p.t.creates.get())
    }

    @Test fun aSaveNeverWritesIntoATargetWhoseNameItCantConfirm() {
        // AGENTS.md exists but the root listing misses it; the create comes back as `AGENTS (1).md`, name unknown.
        val s = setup()
        val agents = s.t.add("root", "AGENTS.md", bin); s.t.write(agents, "the user's".toByteArray())
        s.t.unlisted += "AGENTS.md"; s.p.nullNames = true
        assertThrows(IOException::class.java) { s.replaceAgents(s.start(), "new") }
        assertEquals("the user's", String(s.t.read(agents)!!))
        assertEquals(emptyList<String>(), s.numbered())
        assertEquals("the temp was still deleted by id", emptyList<String>(), s.inSidecar())
    }

    // ---- pre-#274 fixed-name `.configlauncher/<name>.tmp` ----

    private fun Setup.legacy(name: String, bytes: ByteArray, mime: String = bin) = t.add(sidecar(), name, mime).also { if (mime != dir) t.write(it, bytes) }
    private fun Setup.rootFile(name: String, bytes: ByteArray) = t.add("root", name, bin).also { t.write(it, bytes) }

    @Test fun legacyTempEqualToTheTargetIsDeletedOnStart() {
        val s = setup()
        s.rootFile("AGENTS.md", template); s.legacy("AGENTS.md.tmp", template) // Grace's leftover after a Replace
        s.rootFile("launcher.json", valid.toByteArray()); s.legacy("launcher.json.tmp", valid.toByteArray())
        assertEquals(emptyList<String>(), s.start().sweepRootTemps("root"))
        assertEquals(emptyList<String>(), s.inSidecar())
        assertEquals(String(template), s.root("AGENTS.md"))
    }

    // ---- #276 NO-GO at 5fa0299 (Grace) + manager: a legacy temp has no hash, so the current file always wins ----

    private fun kept(name: String, target: String) = SafWrites.legacyKeptWarning(name, target)

    /** Plants [cur] (null: no file) and a legacy temp [tmp] for [target], runs two starts, and checks nothing was lost. */
    private fun keptCase(target: String, cur: ByteArray?, tmp: ByteArray) {
        val s = setup()
        cur?.let { s.rootFile(target, it) }
        s.legacy("$target.tmp", tmp)
        repeat(2) { // and again on the next start: still kept, still warned
            assertEquals(listOf(kept("$target.tmp", target)), s.start().sweepRootTemps("root"))
            assertEquals("the current file always wins", cur?.let { String(it) }, s.root(target))
            assertEquals("the temp is kept", String(tmp), s.text(s.sidecar(), "$target.tmp"))
        }
    }

    @Test fun shortenedAgentsMdIsNotOverwrittenByTheLongerLegacyTemp() {
        // Grace 1 (QB276prefix): the user shortened AGENTS.md after the last pre-#274 save.
        keptCase("AGENTS.md", "my edited agent rules for QA 276".toByteArray(), "my edited agent rules for QA 276 and a section I later removed".toByteArray())
    }

    @Test fun aPartialLegacyTempThatStartsWithTheCurrentAgentsMdIsNotRestored() {
        // Grace 2: killed mid temp write of an append; the temp is torn, the target is complete.
        keptCase("AGENTS.md", "# rules\n- one\n".toByteArray(), "# rules\n- one\n- tw".toByteArray())
    }

    @Test fun aTornAgentsMdThatIsNotAPrefixNeverCostsTheCompleteLegacyTemp() {
        // Grace 3: the provider didn't truncate: new bytes + an old tail. The temp is the only good copy.
        keptCase("AGENTS.md", "# rules v2\n-old tail".toByteArray(), "# rules v2\n".toByteArray())
    }

    @Test fun anEmptyAgentsMdIsNotRestoredFromTheLegacyTemp() {
        // The only complete copy: kept with the ▲ line, and the (empty) target is not overwritten.
        keptCase("AGENTS.md", ByteArray(0), "# rules v2\n".toByteArray())
    }

    @Test fun anEditedAgentsMdIsNotTreatedAsSupersedingTheLegacyTemp() {
        keptCase("AGENTS.md", "edited by the user since".toByteArray(), "my own rules".toByteArray())
    }

    @Test fun aValidLauncherJsonThatIsAPrefixOfTheLegacyTempIsNotOverwritten() {
        keptCase("launcher.json", valid.trimEnd().toByteArray(), valid.toByteArray()) // both parse
    }

    @Test fun aPartialLegacyLauncherJsonTempIsNotRestored() {
        keptCase("launcher.json", valid.toByteArray(), (valid + "{").toByteArray())
        keptCase("launcher.json", ByteArray(0), validNew.take(30).toByteArray()) // doesn't parse
    }

    @Test fun aTornOrEmptyLauncherJsonIsNotRestoredFromAValidLegacyTemp() {
        keptCase("launcher.json", validNew.take(20).toByteArray(), validNew.toByteArray()) // a prefix
        keptCase("launcher.json", ByteArray(0), validNew.toByteArray())                    // empty
        keptCase("launcher.json", "{ an agent's half edit".toByteArray(), validNew.toByteArray()) // invalid
        keptCase("launcher.json", null, validNew.toByteArray())                             // missing
    }

    @Test fun aValidLauncherJsonDoesNotMakeADifferentLegacyTempOutdated() {
        keptCase("launcher.json", valid.toByteArray(), validNew.toByteArray())
    }

    @Test fun aMissingAgentsMdIsNeverRecreatedAndANonTemplateTempIsKept() {
        keptCase("AGENTS.md", null, "my own rules".toByteArray())
        keptCase("AGENTS.md", null, (String(template) + "\nmy edit").toByteArray()) // resembles a template: not one
    }

    @Test fun aKeptLegacyTempGoesOnceItMatchesTheTarget() {
        val s = setup()
        val t = s.rootFile("launcher.json", valid.toByteArray()); s.legacy("launcher.json.tmp", validNew.toByteArray())
        assertEquals(1, s.start().sweepRootTemps("root").size)
        s.t.write(t, validNew.toByteArray()) // the user copied it over
        assertEquals(emptyList<String>(), s.start().sweepRootTemps("root"))
        assertEquals(emptyList<String>(), s.inSidecar())
    }

    @Test fun legacyShippedTemplateIsDeletedEvenWhenAgentsMdIsGone() {
        val s = setup()
        s.legacy("AGENTS.md.tmp", template)
        assertEquals(emptyList<String>(), s.start().sweepRootTemps("root"))
        assertEquals(emptyList<String>(), s.inSidecar())
        assertNull(s.root("AGENTS.md"))
    }

    @Test fun legacyNumberedCopiesAreSettledButNothingElse() {
        val s = setup()
        s.rootFile("AGENTS.md", "same".toByteArray())
        for (n in listOf("AGENTS.md.tmp (1)", "AGENTS.md (2).tmp")) s.legacy(n, "same".toByteArray())
        s.legacy("AGENTS.md (3).tmp", ByteArray(0))
        val user = listOf("AGENTS.md.tmp.bak", "notes.tmp", "AGENTS.md", "agents.md.tmp", "launcher.json.tmp.tmp")
        user.forEach { s.legacy(it, "same".toByteArray()) }
        s.legacy("launcher.json.tmp", ByteArray(0), mime = dir) // a folder
        s.start().sweepRootTemps("root")
        assertEquals((user + "launcher.json.tmp").sorted(), s.inSidecar())
    }

    @Test fun aSaveThroughConfiglauncherSettlesTheLegacyTempForThatFile() {
        val s = setup()
        s.rootFile("AGENTS.md", "v1".toByteArray()); s.legacy("AGENTS.md.tmp", "v1".toByteArray())
        s.legacy("launcher.json.tmp", validNew.toByteArray()) // another file: left for the start sweep
        s.replaceAgents(s.start(), "v2")
        assertEquals("v2", s.root("AGENTS.md"))
        assertEquals(listOf("launcher.json.tmp"), s.inSidecar())
        // One that differs is kept through a save (Grace's QB276rep had an identical one, which goes).
        s.legacy("AGENTS.md.tmp", "my own rules".toByteArray())
        s.replaceAgents(s.start(), "v3")
        assertEquals("my own rules", s.text(s.sidecar(), "AGENTS.md.tmp"))
    }

    @Test fun anEmptyUnjournaledSidecarTempIsDeletedOnStart() {
        // Killed between the create and the journal write.
        val s = setup()
        s.legacy(".AGENTS.md.0123abcd.tmp", ByteArray(0))
        s.legacy(".launcher.json.89abcdef (1).tmp", ByteArray(0))
        s.start().sweepRootTemps("root")
        assertEquals(emptyList<String>(), s.inSidecar())
    }

    @Test fun legacySweepSurvivesAProviderError() {
        val s = setup()
        s.legacy("AGENTS.md.tmp", "x".toByteArray())
        val busy = object : DocTree by s.p { override fun read(docId: String) = throw IOException("busy") }
        assertEquals(emptyList<String>(), SafWrites(busy, SafDirs(busy, s.key), s.key, s.journal).sweepRootTemps("root"))
        assertEquals(listOf("AGENTS.md.tmp"), s.inSidecar())
        assertTrue(ConfigCodec.sha256(template) in AgentDocs.SHIPPED_SHA256)
    }
}
