package dev.nasawa.configlauncher.ui

import android.appwidget.AppWidgetProviderInfo
import dev.nasawa.configlauncher.hosted.WidgetRuntime.Pick
import org.junit.Assert.assertEquals
import org.junit.Test

/** #302 ux.md §16.1: grouped by app, sorted, searchable by widget or app label. */
class WidgetPickerModelTest {
    private val info = AppWidgetProviderInfo()
    private fun p(pkg: String, cls: String, label: String, app: String) = Pick(pkg, cls, label, app, 2, 1, info)
    private val picks = listOf(p("b.cal", "b.cal.Month", "Month", "Calendar"), p("a.clock", "a.clock.Digital", "Digital", "Clock"),
        p("b.cal", "b.cal.Agenda", "Agenda", "Calendar"), p("c.notes", "c.notes.N", "Note", "notes"))

    @Test fun groupsByAppAndSorts() {
        val g = WidgetPickerModel.groups(picks, "")
        assertEquals(listOf("Calendar", "Clock", "notes"), g.map { it.app })
        assertEquals(listOf("Agenda", "Month"), g[0].rows.map { it.label })
    }

    @Test fun searchMatchesWidgetOrApp() {
        assertEquals(listOf("Agenda", "Month"), WidgetPickerModel.groups(picks, " calendar ").flatMap { it.rows }.map { it.label })
        assertEquals(listOf("Digital"), WidgetPickerModel.groups(picks, "DIG").flatMap { it.rows }.map { it.label })
        assertEquals(emptyList<Any>(), WidgetPickerModel.groups(picks, "zzz"))
    }
}
