package dev.nasawa.configlauncher.hosted

import dev.nasawa.configlauncher.hosted.BindFlow.Hint
import dev.nasawa.configlauncher.hosted.BindFlow.Step
import dev.nasawa.configlauncher.hosted.BindFlow.Target
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #301 NW-4: the bind / configure state machine (ux.md §16.2–§16.3, native-widgets.md §4 D1). */
class BindFlowTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Android: allocated IDs, which providers may bind silently, and every screen we started. */
    private class Fake : BindFlow.Platform, WidgetBindings.Host {
        val alive = mutableSetOf<Int>()
        var next = 1
        val allowed = mutableSetOf<String>()
        val targets = mutableMapOf<String, Target>()
        val screens = mutableListOf<String>()
        var canLaunch = true
        override fun target(pkg: String, cls: String) = targets["$pkg/$cls"]
        override fun allocate() = next++.also { alive += it }
        override fun bindIfAllowed(appWidgetId: Int, t: Target) = t.pkg in allowed
        override fun launchBind(appWidgetId: Int, t: Target) = canLaunch.also { if (it) screens += "bind:$appWidgetId" }
        override fun launchConfigure(appWidgetId: Int) = canLaunch.also { if (it) screens += "configure:$appWidgetId" }
        override fun ids() = alive.toIntArray()
        override fun delete(appWidgetId: Int) { alive -= appWidgetId }
    }

    private class Seen : BindFlow.Explained {
        val set = mutableSetOf<String>()
        override fun has(pkg: String) = pkg in set
        override fun add(pkg: String) { set += pkg }
    }

    private val clock = Target("com.example.clock", "com.example.clock.Clock", "Clock", configureRequired = false, reconfigurable = false)
    private val cal = Target("com.example.cal", "com.example.cal.Month", "Calendar", configureRequired = true, reconfigurable = true)

    private val fake = Fake().apply { targets["${clock.pkg}/${clock.cls}"] = clock; targets["${cal.pkg}/${cal.cls}"] = cal }
    private val dir by lazy { tmp.newFolder() }
    private val bindings by lazy { WidgetBindings(dir, fake) }
    private val seen = Seen()
    private val flow by lazy { BindFlow(fake, bindings, seen).also { it.resumed = true } }
    private val file get() = File(dir, WidgetBindings.FILE)

    private fun leaked() = fake.alive - bindings.all().values.map { it.appWidgetId }.toSet()

    @Test fun silentBindNeedsResumeAndAllowAndNoConfigure() {
        val f = BindFlow(fake, bindings, seen)
        val unbound = listOf("c" to clock, "k" to cal, "x" to null)
        fake.allowed += setOf(clock.pkg, cal.pkg)
        f.silentBindAll(unbound)
        assertTrue("never while home isn't resumed", bindings.all().isEmpty())
        f.resumed = true
        assertTrue(f.silentPending("c", clock))
        assertFalse("configure screen → never silent", f.silentPending("k", cal))
        assertFalse(f.silentPending("x", null))
        f.silentBindAll(unbound)
        assertEquals(setOf("c"), bindings.all().keys)
        assertTrue("a load never opens a screen", fake.screens.isEmpty())
        assertTrue(leaked().isEmpty())
    }

    @Test fun sixAgentWidgetsZeroDialogsAndNoFlash() {
        val other = Target("com.example.notes", "com.example.notes.N", "Notes", false, false)
        fake.targets["${other.pkg}/${other.cls}"] = other
        fake.allowed += clock.pkg
        val unbound = (1..3).map { "c$it" to clock } + (1..3).map { "n$it" to other }
        // Before the attempt: icon and label only (silentPending), never `Tap to set up`.
        assertTrue(unbound.all { (id, t) -> flow.silentPending(id, t) })
        flow.silentBindAll(unbound)
        assertEquals(setOf("c1", "c2", "c3"), bindings.all().keys)
        assertTrue(fake.screens.isEmpty())
        // After: the refused ones are `Tap to set up` (no hint, no longer pending) until home resumes again.
        assertTrue((1..3).none { flow.silentPending("n$it", other) })
        assertTrue((1..3).all { flow.hint("n$it") == null })
        assertTrue(leaked().isEmpty())
        flow.resumed = false; flow.resumed = true
        assertTrue("retried on the next resume", flow.silentPending("n1", other))
    }

    @Test fun tapExplainsOncePerPackageThenBindDialogThenConfigure() {
        flow.tap("k", cal.pkg, cal.cls)
        val p = flow.pending.value!!
        assertEquals(Step.EXPLAIN, p.step)
        assertTrue(fake.screens.isEmpty())
        flow.explainContinue()
        assertEquals(listOf("bind:${p.appWidgetId}"), fake.screens)
        flow.onBindResult(true)
        assertEquals(Step.CONFIGURE, flow.pending.value!!.step)
        assertEquals("configure:${p.appWidgetId}", fake.screens.last())
        flow.onConfigureResult(true)
        assertNull(flow.pending.value)
        assertEquals(WidgetBindings.State.BOUND, bindings.binding("k")!!.state)
        assertEquals(p.appWidgetId, bindings.binding("k")!!.appWidgetId)
        // Second widget from the same package: no explanation.
        flow.tap("k2", cal.pkg, cal.cls)
        assertEquals(Step.BIND, flow.pending.value!!.step)
        assertEquals(setOf(cal.pkg), seen.set)
    }

    @Test fun notNowAndBindCancelGoToTapToAllowAndFreeTheId() {
        flow.tap("k", cal.pkg, cal.cls)
        flow.explainNotNow()
        assertEquals(Hint.ALLOW, flow.hint("k"))
        assertNull(flow.pending.value)
        assertTrue(leaked().isEmpty())
        // `Tap to allow` → the dialog directly (explained already) → cancel → `Tap to allow` again.
        flow.tap("k", cal.pkg, cal.cls)
        assertEquals(Step.BIND, flow.pending.value!!.step)
        flow.onBindResult(false)
        assertEquals(Hint.ALLOW, flow.hint("k"))
        assertTrue(leaked().isEmpty())
        assertTrue(fake.alive.isEmpty())
    }

    @Test fun configureCancelIsFinishSetupAndRetapBindsAgain() {
        fake.allowed += cal.pkg                          // "Always allow": bindIfAllowed succeeds, configure still runs
        flow.tap("k", cal.pkg, cal.cls)
        assertEquals(Step.CONFIGURE, flow.pending.value!!.step)
        flow.onConfigureResult(false)
        assertEquals(Hint.FINISH, flow.hint("k"))
        assertNull(bindings.binding("k"))
        assertTrue(fake.alive.isEmpty())
        // The tile resolves to needsConfig (`Tap to finish setup`).
        val c = (dev.nasawa.configlauncher.config.ConfigCodec.decode("""{"schemaVersion":1,"pages":[{"id":"home","items":[]}],"elements":[{"id":"k","type":"appWidget","requires":5,"page":"home","x":0,"y":0,"w":1,"h":1,"content":{"provider":{"package":"${cal.pkg}","class":"${cal.cls}"}}}]}""") as dev.nasawa.configlauncher.config.ParseResult.Ok).config
        val t = HostedWidgets.tiles(c, bindings.all(), { _, _, _ -> HostedWidgets.Facts("Calendar") }, hints = { id, _, _ -> flow.hint(id) }).single()
        assertEquals(HostedWidgets.State.NEEDS_CONFIG, t.state)
        flow.tap("k", cal.pkg, cal.cls)
        flow.onConfigureResult(true)
        assertNull(flow.hint("k"))
        assertEquals(HostedWidgets.State.BOUND, HostedWidgets.tiles(c, bindings.all(), { _, _, _ -> HostedWidgets.Facts("Calendar") }).single().state)
    }

    @Test fun screenThatCantStartFreesTheId() {
        fake.canLaunch = false
        seen.add(cal.pkg)
        assertFalse(flow.tap("k", cal.pkg, cal.cls))
        assertEquals(Hint.ALLOW, flow.hint("k"))
        fake.allowed += cal.pkg
        assertFalse(flow.tap("k", cal.pkg, cal.cls))
        assertEquals(Hint.FINISH, flow.hint("k"))
        assertTrue(fake.alive.isEmpty())
    }

    @Test fun reconfigureUsesTheSameIdAndKeepsTheBindingEitherWay() {
        fake.allowed += cal.pkg
        flow.tap("k", cal.pkg, cal.cls); flow.onConfigureResult(true)
        val id = bindings.binding("k")!!.appWidgetId
        assertTrue(flow.reconfigure("k"))
        assertEquals("configure:$id", fake.screens.last())
        flow.onConfigureResult(false)
        assertEquals(id, bindings.binding("k")!!.appWidgetId)
        assertEquals(setOf(id), fake.alive)
        // Not reconfigurable → no action.
        fake.allowed += clock.pkg
        flow.tap("c", clock.pkg, clock.cls)
        assertFalse(flow.reconfigure("c"))
    }

    @Test fun oneFlowAtATimeAndNothingWhilePaused() {
        flow.tap("k", cal.pkg, cal.cls)
        assertFalse(flow.tap("k2", cal.pkg, cal.cls))
        flow.explainNotNow()
        flow.resumed = false
        assertFalse(flow.tap("k", cal.pkg, cal.cls))
        assertTrue(fake.alive.isEmpty())
    }

    @Test fun launcherJsonIsNeverWritten() {
        // The flow only knows the bindings store; the only file it can change is bindings.json in its own dir.
        fake.allowed += setOf(clock.pkg, cal.pkg)
        flow.silentBindAll(listOf("c" to clock))
        flow.tap("k", cal.pkg, cal.cls); flow.onConfigureResult(true)
        assertEquals(listOf(WidgetBindings.FILE), dir.list()!!.toList())
        assertTrue(file.readText().contains("\"k\""))
    }
}
