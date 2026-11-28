package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.Edit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #339 (in device runs): two outcomes #334 left.
 * - Silent loss: the agent's `cp` landed between the save's last look at launcher.json and its truncate-write (two
 *   provider calls), was overwritten in full, and the read-back saw only our bytes: status.json `ok`, agent item gone.
 * - Torn file: the agent's longer file landed after our truncate and we wrote over its start: our JSON plus its
 *   tail. The read-back did differ, but "not a prefix of ours" made it "the agent's file": left torn, B6 over it, RELOAD
 *   busy and status.json on the old `ok` (polls skip the file under B6) until the user tapped Use file.
 * Real [SafWrites] and [ConfigEngine] on a fake tree; the agent's steps land at fixed points of the save. No clock.
 */
class Issue339TornWriteTest {
    @get:Rule val tmp = TemporaryFolder()

    private val base = """{"schemaVersion":1,"revision":62,"pages":[{"id":"home","items":[""" +
        """{"id":"a-camera","type":"app","package":"a.camera","x":0,"y":0},{"id":"a-keep","type":"app","package":"a.keep","x":1,"y":0}]}]}"""
    private fun agent(rev: Int, extra: String = "a-agent-22") = """{"schemaVersion":1,"revision":$rev,"pages":[{"id":"home","items":[""" +
        """{"id":"a-camera","type":"app","package":"a.camera","x":0,"y":0},{"id":"a-keep","type":"app","package":"a.keep","x":1,"y":0},""" +
        """{"id":"$extra","type":"app","package":"com.sec.android.app.popupcalculator","x":2,"y":4}]}]}"""

    /** A fake provider. [fd]: it gives a seekable read/write descriptor ([DocTree.replace]), as a test phone's does. */
    private class Racing(val t: SafDirsTest.FakeTree, val fd: Boolean) : DocTree by t {
        /** Once, right after a read of launcher.json returns (the save's own look comes first). */
        var afterTargetRead: (() -> Unit)? = null
        /** Once, after our bytes reach launcher.json: what the file becomes (the agent's write meeting ours). */
        var afterTargetWrite: ((ByteArray) -> ByteArray)? = null
        val replaces = mutableListOf<String>()
        private fun isTarget(id: String) = t.docs[id]?.name == "launcher.json"
        override fun read(docId: String): ByteArray? {
            val out = t.read(docId)
            if (isTarget(docId)) afterTargetRead?.let { afterTargetRead = null; it() }
            return out
        }
        override fun write(docId: String, bytes: ByteArray) {
            t.write(docId, bytes)
            if (isTarget(docId)) afterTargetWrite?.let { f -> afterTargetWrite = null; t.write(docId, f(bytes)) }
        }
        override fun replace(docId: String, bytes: ByteArray, check: (ByteArray) -> Unit): Boolean {
            if (!fd) return false
            replaces += docId
            check(t.read(docId) ?: ByteArray(0)) // the same open: nothing lands between this and the write
            write(docId, bytes)
            return true
        }
    }

    private inner class TreeSource(fd: Boolean = true) : ConfigSource {
        val t = SafDirsTest.FakeTree()
        val r = Racing(t, fd)
        private val key = "t339-${System.identityHashCode(this)}"
        val writes = SafWrites(r, SafDirs(r, key), key)
        init { t.write(t.add("root", "launcher.json", "application/json"), base.toByteArray()) }
        val file: String? get() = t.list("root")["launcher.json"]?.let { t.read(it.docId) }?.let { String(it) }
        val sidecars = mutableMapOf<String, String>()
        fun agentWrites(json: String) { t.write(t.list("root")["launcher.json"]!!.docId, json.toByteArray()) }
        override val displayName = "Folder: test"
        override fun stamp() = t.list("root")["launcher.json"]?.let { Stamp(it.docId, 1, it.size) }
        override fun read(maxBytes: Int) = t.list("root")["launcher.json"]?.let { t.read(it.docId) }
        override fun write(bytes: ByteArray, expectHash: String?) = writes.writeVerified("root", "launcher.json", "application/json", bytes, "layout", expectHash)
        override fun lastWriteOverlapped() = writes.overlapped
        var statusWrites = 0
        override fun writeSidecar(subdir: String, name: String, bytes: ByteArray) {
            sidecars["$subdir/$name"] = String(bytes); if (name == StatusJson.FILE) statusWrites++
        }
        fun status() = sidecars["/status.json"]!!
        fun result() = Regex("\"result\":\"([a-z_]+)\"").find(status())!!.groupValues[1]
    }

    private fun engine(src: ConfigSource) = ConfigEngine(src, LastGoodStore(tmp.newFolder()), stabilityMs = 0, clock = { 1_000L })
    private fun ids(json: String?) = ((ConfigCodec.decode(json!!.toByteArray()) as ParseResult.Ok).config.pages.flatMap { p -> p.items.map { it.id } }).sorted()
    private fun camera(json: String?) = (ConfigCodec.decode(json!!.toByteArray()) as ParseResult.Ok).config.pages.flatMap { it.items }
        .first { it.id == "a-camera" }.let { it.x to it.y }
    private fun parses(json: String?) = ConfigCodec.decode(json!!.toByteArray()) is ParseResult.Ok
    private fun moveCamera() = Edit.MoveToCell("a-camera", "home", 3, 3)

    // ---- (1) silent loss ----

    @Test fun anAgentWriteAfterTheSavesLookIsMergedNotOverwritten() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(moveCamera()))
        // In device runs: the agent's whole cp lands right after the save's look at launcher.json, before its write.
        src.r.afterTargetRead = { src.agentWrites(agent(63)) }
        assertTrue(e.flush())
        assertTrue("the replace re-checked in its own open", src.r.replaces.isNotEmpty())
        assertEquals("the agent's item kept, our move merged onto it", listOf("a-agent-22", "a-camera", "a-keep"), ids(src.file))
        assertEquals("our move kept", 3 to 3, camera(src.file))
        assertEquals("ok", src.result())
        assertTrue(src.status().contains(ConfigCodec.sha256(src.file!!.toByteArray())))
        assertNull(e.state.value.issue); assertFalse(e.state.value.conflict)
    }

    // ---- (2) torn file ----

    /** In device runs: the agent's longer file landed after our truncate; our bytes went over its start. */
    private fun TreeSource.tearsTheNextWrite(agentJson: String) {
        // As the agent's tool would write it: pretty-printed, so longer than our save.
        val a = ConfigCodec.encodeBytes((ConfigCodec.decode(agentJson.toByteArray()) as ParseResult.Ok).config)
        r.afterTargetWrite = { ours -> check(a.size > ours.size); ours + a.copyOfRange(ours.size, a.size) }
    }

    @Test fun aReadBackWithTrailingBytesIsRejectedAndRepaired() {
        val src = TreeSource()
        val ours = base.replace("\"revision\":62", "\"revision\":63").toByteArray()
        val longer = agent(63)
        assertTrue(longer.length > ours.size)
        src.tearsTheNextWrite(longer)
        src.writes.writeVerified("root", "launcher.json", "application/json", ours, "layout", expectOf(base.toByteArray()))
        assertEquals("exactly our bytes: no trailing ones left", String(ours), src.file)
        assertTrue(src.writes.overlapped)
        // The next clean save resets it.
        src.writes.writeVerified("root", "launcher.json", "application/json", base.toByteArray(), "layout", expectOf(ours))
        assertFalse(src.writes.overlapped)
    }

    @Test fun anOldTailFromAProviderThatDidNotTruncateIsNoOverlap() {
        val src = TreeSource(fd = false)
        val longOld = agent(62); src.agentWrites(longOld)
        val ours = base.toByteArray()
        src.r.afterTargetWrite = { o -> val a = longOld.toByteArray(); o + a.copyOfRange(o.size, a.size) }
        src.writes.writeVerified("root", "launcher.json", "application/json", ours, "layout", expectOf(longOld.toByteArray()))
        assertEquals(base, src.file)
        assertFalse("the tail was the old file's, nobody else wrote", src.writes.overlapped)
    }

    @Test fun aTornSaveEndsValidAndStatusSaysTheAgentWriteWasCut() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(moveCamera()))
        src.tearsTheNextWrite(agent(63))
        assertTrue(e.flush())
        assertTrue("never left torn", parses(src.file))
        assertFalse(e.state.value.conflict); assertNull(e.state.value.issue)
        assertEquals("ok", src.result())
        assertTrue(src.status(), src.status().contains("overlapped the phone's save"))
        assertEquals(ReloadOutcome.UNCHANGED, e.reloadForAgent())
        // The agent writes again: loaded (merged nothing pending), and the overlap line is answered.
        src.agentWrites(agent(64, "a-agent-23"))
        assertEquals(ReloadOutcome.RELOADED, e.reloadForAgent())
        assertFalse(src.status().contains("overlapped"))
    }

    // ---- status.json while B6 is up ----

    @Test fun aTornFileUnderB6IsReportedAndMergedOnceItLoads() = runBlocking {
        // A torn file the save didn't make (e.g. the agent's own cp caught mid-way): B6 over a file that doesn't load.
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertEquals("ok", src.result())
        assertTrue(e.edit(moveCamera()))
        val torn = String(ConfigCodec.encodeBytes(e.state.value.config)) + agent(63).takeLast(40)
        src.agentWrites(torn)
        assertFalse(e.flush())
        assertTrue(e.state.value.conflict)
        // Before: status.json kept the last "ok" (rev 62) with a fresh time; now it describes the torn file.
        assertEquals("parse_error", src.result())
        assertTrue(src.status().contains(ConfigCodec.sha256(torn.toByteArray())))
        assertTrue(src.status().contains("doesn't load"))
        assertEquals("rejected, not busy", ReloadOutcome.REJECTED, e.reloadForAgent())
        assertEquals("parse_error", src.result())
        // The agent writes a valid file: the next poll merges our move onto it and B6 goes (no Use file needed).
        src.agentWrites(agent(84))
        assertTrue(e.poll())
        assertFalse(e.state.value.conflict); assertNull(e.state.value.issue)
        assertEquals(listOf("a-agent-22", "a-camera", "a-keep"), ids(src.file))
        assertEquals("our move kept", 3 to 3, camera(src.file))
        assertEquals("ok", src.result())
        assertTrue(src.status().contains(ConfigCodec.sha256(src.file!!.toByteArray())))
        assertFalse(src.status().contains("isn't saved yet"))
        assertEquals(ReloadOutcome.UNCHANGED, e.reloadForAgent())
    }

    @Test fun reloadAloneClearsATornB6OnceTheFileLoads() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(moveCamera()))
        src.agentWrites(agent(63).dropLast(3))
        assertFalse(e.flush()); assertTrue(e.state.value.conflict)
        src.agentWrites(agent(64))
        assertEquals(ReloadOutcome.RELOADED, e.reloadForAgent())
        assertFalse(e.state.value.conflict)
        assertEquals(listOf("a-agent-22", "a-camera", "a-keep"), ids(src.file))
    }

    @Test fun askBeforeMergingB6IsConflictInStatusNotOk() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        e.askBeforeMerge = true
        assertTrue(e.edit(moveCamera()))
        src.agentWrites(agent(63))
        assertFalse(e.flush()); assertTrue(e.state.value.conflict)
        assertEquals("conflict", src.result())
        assertTrue(src.status().contains(ConfigCodec.sha256(agent(63).toByteArray())))
        assertTrue(src.status(), src.status().contains("\"revision\":63"))
        assertTrue(src.status().contains("Use file or Keep mine"))
        // The user's call: polls and RELOAD don't merge it away, and status.json stays honest through RELOAD.
        src.agentWrites(agent(64))
        assertFalse(e.poll(force = true)); assertTrue(e.state.value.conflict)
        assertEquals(ReloadOutcome.BUSY, e.reloadForAgent())
        assertEquals("conflict", src.result())
        e.resolveUseFile()
        assertFalse(e.state.value.conflict)
        assertEquals("ok", src.result())
        assertTrue(src.status().contains("\"revision\":64"))
        assertFalse(src.status().contains("isn't saved yet"))
    }

    @Test fun keepMineAfterATornB6ClearsTheBrokenState() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(moveCamera()))
        src.agentWrites(agent(63).dropLast(3))
        assertFalse(e.flush()); assertTrue(e.state.value.conflict)
        assertTrue(e.resolveKeepMine())
        assertFalse(e.state.value.conflict)
        assertEquals("ok", src.result())
        // A later broken write is an ordinary rejected file, not a B6 left over.
        src.agentWrites("{")
        assertEquals(ReloadOutcome.REJECTED, e.reloadForAgent())
        assertFalse(e.state.value.conflict)
        assertFalse(src.status().contains("isn't saved yet"))
    }

    @Test fun aTearThatCannotBeRepairedIsRestoredNeverKeptAsTheirs() = runBlocking {
        // Every write of our bytes comes back torn (the repair too): the original goes back and B5 says so. Before, a
        // torn read-back was "someone else's file": kept torn, B6 over it.
        val src = TreeSource()
        val tail = "  ]}]}  , \"a-agent-22\" }".toByteArray()
        val t = src.t
        val tearing = object : DocTree by src.r {
            override fun write(docId: String, bytes: ByteArray) {
                src.r.write(docId, bytes)
                if (t.docs[docId]?.name == "launcher.json" && !bytes.contentEquals(base.toByteArray())) t.write(docId, bytes + tail)
            }
            override fun replace(docId: String, bytes: ByteArray, check: (ByteArray) -> Unit): Boolean {
                check(t.read(docId) ?: ByteArray(0)); write(docId, bytes); return true
            }
        }
        val key = "t339-tear-${System.identityHashCode(this)}"
        val w = SafWrites(tearing, SafDirs(tearing, key), key)
        val src2 = object : ConfigSource by src {
            override fun write(bytes: ByteArray, expectHash: String?) = w.writeVerified("root", "launcher.json", "application/json", bytes, "layout", expectHash)
            override fun lastWriteOverlapped() = w.overlapped
        }
        val e2 = engine(src2)
        assertTrue(e2.poll(force = true))
        assertTrue(e2.edit(moveCamera()))
        assertFalse(e2.flush())
        assertEquals("the old file is put back", base, src.file)
        assertFalse("no B6 over a torn file", e2.state.value.conflict)
        assertTrue(e2.state.value.issue is Issue.SaveFailed)
        assertEquals(1, e2.state.value.pendingEdits)
    }

    @Test fun withAskBeforeMergingABrokenFileB6StaysTheUsersCall() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        e.askBeforeMerge = true
        assertTrue(e.edit(moveCamera()))
        src.agentWrites(agent(63).dropLast(3))
        assertFalse(e.flush()); assertTrue(e.state.value.conflict)
        // #340 follow-up: the user's B6, so `conflict`, with the file's errors.
        assertEquals("conflict", src.result())
        assertTrue(src.status(), src.status().contains("not valid"))
        assertTrue(src.status().contains("Use file or Keep mine"))
        assertEquals(ReloadOutcome.BUSY, e.reloadForAgent())
        src.agentWrites(agent(64))
        assertFalse(e.poll(force = true))
        assertTrue("never merged without asking", e.state.value.conflict)
        assertEquals(ReloadOutcome.BUSY, e.reloadForAgent())
    }

    // ---- #340 follow-ups (Grace's review) ----

    /** Grace's scratch test: the agent's SHORTER cp (truncating open before our truncate, its write after ours). */
    @Test fun graceShorterAgentMixedWithOurTail() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(moveCamera()))
        val a = """{"schemaVersion":1,"revision":63,"pages":[{"id":"home","items":[]}]}""".toByteArray()
        src.r.afterTargetWrite = { ours -> check(a.size < ours.size); a + ours.copyOfRange(a.size, ours.size) }
        // Before: kept torn as "theirs": B6 over it, status parse_error, RELOAD 2.
        assertTrue(e.flush())
        assertTrue("never left torn", parses(src.file))
        assertEquals("our save, cut to length", listOf("a-camera", "a-keep"), ids(src.file)); assertEquals(3 to 3, camera(src.file))
        assertFalse(e.state.value.conflict); assertNull(e.state.value.issue)
        assertEquals("ok", src.result())
        assertTrue(src.status(), src.status().contains("overlapped the phone's save"))
        assertEquals(ReloadOutcome.UNCHANGED, e.reloadForAgent())
    }

    /** Grace's scratch test: status never `ok` while a broken-file B6 is up; RELOAD 2 throughout, then it merges. */
    @Test fun graceStaleOkNeverDuringBrokenB6() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(moveCamera()))
        src.agentWrites(agent(63).dropLast(5))
        assertFalse(e.flush()); assertTrue(e.state.value.conflict)
        repeat(3) { assertNotEquals("ok", src.result()); assertEquals(ReloadOutcome.REJECTED, e.reloadForAgent()); assertNotEquals("ok", src.result()); assertFalse(e.poll(force = true)) }
        // a different broken file: still rejected, status describes the new one
        val b2 = agent(64).dropLast(2); src.agentWrites(b2)
        assertFalse(e.poll()); assertTrue(src.status().contains(ConfigCodec.sha256(b2.toByteArray())))
        assertEquals("parse_error", src.result())
        // an edit while broken B6 joins pending; RELOAD still 2 not 4
        assertTrue(e.edit(Edit.MoveToCell("a-keep", "home", 2, 2)))
        assertEquals(ReloadOutcome.REJECTED, e.reloadForAgent())
        src.agentWrites(agent(65))
        assertEquals(ReloadOutcome.RELOADED, e.reloadForAgent())
        assertFalse(e.state.value.conflict); assertEquals("ok", src.result())
        assertEquals(listOf("a-agent-22", "a-camera", "a-keep"), ids(src.file)); assertEquals(3 to 3, camera(src.file))
    }

    /** Same length as ours, with a tail like ours: a file of theirs that loads is theirs, never "repaired". */
    @Test fun aSameLengthAgentFileThatLoadsIsNotMixed() {
        val ours = (base + "\n").toByteArray()
        val theirsJson = base.replace("\"revision\":62", "\"revision\":63").replace("a.keep", "a.k")
        assertTrue(theirsJson.length + 1 < ours.size)
        val theirs = (theirsJson.replaceFirst("{", "{" + " ".repeat(ours.size - theirsJson.length - 1)) + "\n").toByteArray()
        assertEquals(ours.size, theirs.size); assertTrue(parses(String(theirs)))
        assertFalse(SafWrites.headOverOurs(theirs, ours))
        // The same shape that doesn't load (theirs shorter, ours after it) is mixed.
        val a = theirsJson.toByteArray()
        assertTrue(SafWrites.headOverOurs(a + ours.copyOfRange(a.size, ours.size), ours))
        // Broken bytes in the middle of ours, no whole object of theirs before our tail: not mixed.
        val garbled = ours.copyOf().also { for (i in 20 until 30) it[i] = '#'.code.toByte() }
        assertFalse(SafWrites.headOverOurs(garbled, ours))
        assertFalse("ours is ours", SafWrites.headOverOurs(ours, ours))
        // A whole object of theirs followed by bytes that aren't ours: not our tail, so not mixed.
        val filler = a + ByteArray(ours.size - a.size) { 'x'.code.toByte() }
        assertFalse(SafWrites.headOverOurs(filler, ours))
        // Same, with only the last byte different from ours: no tail of ours at all.
        val oneOff = (theirsJson.replaceFirst("{", "{" + " ".repeat(ours.size - theirsJson.length - 1)) + "x").toByteArray()
        assertEquals(ours.size, oneOff.size)
        assertFalse(SafWrites.headOverOurs(oneOff, ours))
    }

    // ---- #341 follow-ups (Grace's review 5986551503) ----

    /** (a) Same length, valid JSON that fails the schema, ours only from the final newline: theirs, never mixed. */
    @Test fun aSameLengthSchemaInvalidAgentFileIsNotMixed() {
        val ours = ConfigCodec.encodeBytes((ConfigCodec.decode(base.toByteArray()) as ParseResult.Ok).config)
        assertEquals('\n'.code.toByte(), ours.last())
        val theirs = String(ours).replace("\"a-keep\"", "\"A-keep\"").toByteArray()
        assertEquals(ours.size, theirs.size)
        assertTrue("valid JSON", runCatching { kotlinx.serialization.json.Json.parseToJsonElement(String(theirs)) }.isSuccess)
        assertTrue("fails the schema", ConfigCodec.decode(theirs) is ParseResult.Invalid)
        assertFalse(SafWrites.headOverOurs(theirs, ours))
        // A whole object of theirs, then only whitespace up to our final newline: still not mixed.
        val a = """{"schemaVersion":1}""".toByteArray()
        val spaced = a + ByteArray(ours.size - a.size - 1) { ' '.code.toByte() } + byteArrayOf('\n'.code.toByte())
        assertFalse(SafWrites.headOverOurs(spaced, ours))
        // A real mix (their object, then real bytes of ours) still is.
        assertTrue(SafWrites.headOverOurs(a + ours.copyOfRange(a.size, ours.size), ours))
    }

    /** (a) The flow: that agent file lands after our write; it's kept as theirs (invalid), not overwritten as an overlap. */
    @Test fun aSameLengthSchemaInvalidAgentWriteAfterOursIsKeptAsTheirs() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(moveCamera()))
        var theirs: ByteArray? = null
        src.r.afterTargetWrite = { ours -> String(ours).replace("\"a-keep\"", "\"A-keep\"").toByteArray().also { theirs = it } }
        e.flush()
        assertFalse("not an overlap", src.writes.overlapped)
        assertEquals("their file is left alone", String(theirs!!), src.file)
        assertFalse(src.status().contains("overlapped"))
        assertNotEquals("ok", src.result())
    }

    /** (a) Guard: only same-length read-backs can be mixed (a longer or shorter one never reads past either array). */
    @Test fun onlySameLengthReadBacksAreMixed() {
        val ours = ConfigCodec.encodeBytes((ConfigCodec.decode(base.toByteArray()) as ParseResult.Ok).config)
        val a = """{"schemaVersion":1}""".toByteArray()
        val mixed = a + ours.copyOfRange(a.size, ours.size)
        assertTrue(SafWrites.headOverOurs(mixed, ours))
        assertFalse("longer", SafWrites.headOverOurs(byteArrayOf(' '.code.toByte()) + mixed, ours))
        assertFalse("shorter", SafWrites.headOverOurs(mixed.copyOfRange(1, mixed.size), ours))
    }

    /** (b) Every write of ours comes back mixed (the repair too): the original goes back and B5 says so, never kept as theirs. */
    @Test fun aMixedFileThatCannotBeRepairedIsRestoredNeverKeptAsTheirs() = runBlocking {
        val src = TreeSource()
        val head = """{"schemaVersion":1,"revision":63,"pages":[]}""".toByteArray()
        val t = src.t
        val mixing = object : DocTree by src.r {
            override fun write(docId: String, bytes: ByteArray) {
                src.r.write(docId, bytes)
                if (t.docs[docId]?.name == "launcher.json" && !bytes.contentEquals(base.toByteArray()) && bytes.size > head.size)
                    t.write(docId, head + bytes.copyOfRange(head.size, bytes.size))
            }
            override fun replace(docId: String, bytes: ByteArray, check: (ByteArray) -> Unit): Boolean {
                check(t.read(docId) ?: ByteArray(0)); write(docId, bytes); return true
            }
        }
        val key = "t341-mix-${System.identityHashCode(this)}"
        val w = SafWrites(mixing, SafDirs(mixing, key), key)
        val src2 = object : ConfigSource by src {
            override fun write(bytes: ByteArray, expectHash: String?) = w.writeVerified("root", "launcher.json", "application/json", bytes, "layout", expectHash)
            override fun lastWriteOverlapped() = w.overlapped
        }
        val e2 = engine(src2)
        assertTrue(e2.poll(force = true))
        assertTrue(e2.edit(moveCamera()))
        assertFalse(e2.flush())
        assertEquals("the old file is put back", base, src.file)
        assertFalse("no B6 over a mixed file", e2.state.value.conflict)
        assertTrue(e2.state.value.issue is Issue.SaveFailed)
        assertEquals(1, e2.state.value.pendingEdits)
    }

    /** (b) A mixed repair over an original of the same length as ours (a one-digit change) still reports the overlap. */
    @Test fun aMixedRepairOverASameLengthOriginalReportsTheOverlap() {
        val src = TreeSource()
        val cfg = (ConfigCodec.decode(base.toByteArray()) as ParseResult.Ok).config
        val original = ConfigCodec.encodeBytes(cfg)
        val ours = ConfigCodec.encodeBytes(cfg.copy(revision = 63))
        assertEquals("same length", original.size, ours.size)
        src.agentWrites(String(original))
        val head = """{"schemaVersion":1,"revision":63,"pages":[]}""".toByteArray()
        src.r.afterTargetWrite = { o -> head + o.copyOfRange(head.size, o.size) }
        src.writes.writeVerified("root", "launcher.json", "application/json", ours, "layout", expectOf(original))
        assertEquals("ours, repaired", String(ours), src.file)
        assertTrue("their write was cut: reported", src.writes.overlapped)
    }

    @Test fun aSameLengthAgentWriteAfterOursIsKeptAndMerged() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(moveCamera()))
        val theirsJson = base.replace("\"revision\":62", "\"revision\":63").replace("a.keep", "a.k")
        src.r.afterTargetWrite = { ours ->
            check(theirsJson.length <= ours.size)
            theirsJson.replaceFirst("{", "{" + " ".repeat(ours.size - theirsJson.length)).toByteArray().also { check(it.size == ours.size) }
        }
        assertTrue(e.flush())
        assertFalse("theirs, not an overlap", src.writes.overlapped)
        assertEquals(listOf("a-camera", "a-keep"), ids(src.file)); assertEquals(3 to 3, camera(src.file))
        assertTrue("their change kept", src.file!!.contains("\"a.k\""))
        assertFalse(src.status().contains("overlapped"))
    }

    @Test fun withAskBeforeMergingStatusFollowsTheConflictingFile() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        e.askBeforeMerge = true
        assertTrue(e.edit(moveCamera()))
        src.agentWrites(agent(63))
        assertFalse(e.flush()); assertTrue(e.state.value.conflict)
        assertEquals("conflict", src.result()); assertTrue(src.status().contains("\"revision\":63"))
        // A second valid write: hash and revision follow it, still conflict and the warning.
        src.agentWrites(agent(64, "a-agent-23"))
        assertFalse(e.poll(force = true)); assertTrue(e.state.value.conflict)
        assertEquals("conflict", src.result())
        assertTrue(src.status().contains(ConfigCodec.sha256(agent(64, "a-agent-23").toByteArray())))
        assertTrue(src.status(), src.status().contains("\"revision\":64"))
        assertTrue(src.status().contains("Use file or Keep mine"))
        // Then a broken one: the agent can see it's broken (errors), still the user's B6.
        val broken = agent(65).dropLast(4)
        src.agentWrites(broken)
        assertEquals(ReloadOutcome.BUSY, e.reloadForAgent())
        assertTrue(e.state.value.conflict)
        assertEquals("conflict", src.result())
        assertTrue(src.status().contains(ConfigCodec.sha256(broken.toByteArray())))
        assertTrue(src.status(), src.status().contains("\"revision\":null") && src.status().contains("not valid"))
        assertTrue(src.status().contains("Use file or Keep mine"))
        // A valid write again (status follows it), then Keep mine replays our move onto it: ok.
        src.agentWrites(agent(66))
        assertFalse(e.poll(force = true))
        assertTrue(src.status().contains("\"revision\":66") && src.status().contains("\"errors\":[]"))
        assertTrue(e.resolveKeepMine())
        assertEquals("ok", src.result())
    }

    @Test fun aForcedPollOfAnUnchangedBrokenFileDoesNotRewriteStatus() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(moveCamera()))
        src.agentWrites(agent(63).dropLast(3))
        assertFalse(e.flush()); assertTrue(e.state.value.conflict)
        val n = src.statusWrites
        repeat(3) { assertFalse(e.poll(force = true)) }
        assertEquals("nothing new to say", n, src.statusWrites)
    }
}
