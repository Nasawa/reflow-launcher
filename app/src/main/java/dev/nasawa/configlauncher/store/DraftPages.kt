package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.Elements
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.Edits
import dev.nasawa.configlauncher.model.LauncherConfig
import dev.nasawa.configlauncher.model.NewPage

/**
 * #329 ux.md §4.2 `+ Page`: pages added in edit mode live here, not in the file, until something lands on one
 * ("nothing is written for an empty page"). The home screen shows the file's model with them inserted ([overlay]); an
 * edit onto one goes out as [Edit.OnNewPages], so the page and the first drop are the one write ([carry]).
 */
object DraftPages {
    /** [c] with the drafts it doesn't have yet inserted after their page (a draft after a draft follows it). */
    fun overlay(c: LauncherConfig, drafts: List<NewPage>): LauncherConfig =
        if (drafts.isEmpty()) c else Edits.withPages(c, drafts) ?: c

    /** The id for the next `+ Page`: `page-N`, lowest unused N (§14 R9); null when home already has 20 pages. */
    fun nextId(shown: LauncherConfig): String? {
        if (shown.pages.size >= Edits.MAX_PAGES) return null
        val taken = shown.pages.map { it.id }.toSet()
        return generateSequence(1) { it + 1 }.map { "page-$it" }.first { it !in taken }
    }

    /** The page ids [e] puts something on. */
    fun pageRefs(e: Edit): Set<String> = when (e) {
        is Edit.MoveToCell -> setOf(e.pageId)
        is Edit.Add -> setOfNotNull(e.pageId)
        is Edit.MoveElement -> setOf(e.pageId)
        is Edit.AddElement -> setOfNotNull(Elements.placement(e.element)?.page, e.newPage)
        is Edit.OnNewPages -> pageRefs(e.edit)
        else -> emptySet()
    }

    /**
     * [e] as it must go to the engine ([file] = the engine's model): wrapped in [Edit.OnNewPages] with the draft it lands
     * on, anchored after the nearest page before it that the file has (so an empty draft in between isn't written
     * either); unchanged when it touches no draft.
     */
    fun carry(file: LauncherConfig, drafts: List<NewPage>, e: Edit): Edit {
        val live = drafts.filter { d -> file.pages.none { it.id == d.id } }
        val refs = pageRefs(e)
        if (live.none { it.id in refs }) return e
        val order = overlay(file, live).pages.map { it.id }
        val inFile = file.pages.map { it.id }.toSet()
        val pages = live.filter { it.id in refs }.map { d ->
            NewPage(d.id, order.subList(0, order.indexOf(d.id).coerceAtLeast(0)).lastOrNull { it in inFile })
        }
        return Edit.OnNewPages(pages, e)
    }

    /** The drafts still not in [file] (the ones carried by a kept edit, or an agent's same-id page, are gone). */
    fun prune(file: LauncherConfig, drafts: List<NewPage>): List<NewPage> = drafts.filter { d -> file.pages.none { it.id == d.id } }
}
