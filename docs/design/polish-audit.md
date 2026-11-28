# Post-1.1 polish audit: Reflow as a daily launcher

**Status:** proposal. Author: Pragma (UX). Written for Archimedes' post-1.1 plan; Christopher decides the order.
**Checked against:** `origin/main` at `86b9ab2` (after 1.0.0, with the 1.1 native-widgets work merged), plus the open issues on 2026-11-27.

## How I checked, and what that means

- I read `docs/ux.md`, the README, the 1.0 release notes, `strings.xml`, and the Kotlin UI and store code. I also read the open issues.
- **I didn't run the app on a device for this audit.** Anything about how it *feels* (speed, animation) is marked *inferred*.
- Each item says how I know it:
  - **Code:** I found it, or failed to find it, in the source. A missing feature means I searched for it and found no code path. Please correct me if I missed one.
  - **Issue:** an open GitHub issue says so.
  - **Docs:** it's in the spec or release notes only.
- Sizes: **S** is about a day or less, **M** is a few days, **L** is a week or more, or needs a design pass first.

**What's already good** (keep it): a bad file never blanks the home screen. Remove and Hide have Undo. The drag feels deliberate (#282). Search matches on word starts, and Enter launches the first hit. TalkBack has custom actions for every drag. Home resets edit mode and goes to page 1. Back on home does nothing, which is what a launcher should do. The one-gesture-one-write rule makes edits predictable.

## Tier 1: Blocks daily use

| # | Rough edge | Evidence | Fix idea | Size |
|---|---|---|---|---|
| 1 | **You can't make or change folders on the phone.** The spec says you drop an app on another app to make a folder, drag into or out of an open folder, and tap the title to rename it (§3, §4.2). I found no edit for create, add-to-folder or rename, and the open folder draws its tiles with `canDrag = false`. Folders from the file open and launch fine. | Code: `model/Edit.kt` has no folder edit; `ui/DragController.kt` has no folder logic; `ui/HomeScreen.kt:1058` | Add `CreateFolder`, `MoveIntoFolder`, `MoveOutOfFolder` and `RenameFolder` edits, wire the drop table in §4.2, and make folder tiles draggable. | L |
| 2 | **Work-profile apps can't be placed or launched from home, and the message points at a setting that doesn't exist.** A work app on home shows `Turn on "Include work apps in config" to use this.`, but the ⋮ menu has no such switch. The drawer's `Add to home` never appears for work apps either. | Code: `Reconcile.resolve(shown.config, inst)` in `ui/HomeViewModel.kt:31` always uses the default `includeWork = false`; `DrawerAppSheet(includeWork = false, …)` at `HomeScreen.kt:846`; no string or pref for the switch. Related: #289 | Add the `Include work apps in config` switch (default off, as §8 says) and pass it through. Until then, change the toast so it doesn't name a missing setting. | S–M |
| 3 | **There's no way to get an older layout back from the phone.** ux.md §8 promises `Restore previous layout` (last 10 snapshots), but no menu item or string exists. A moved app has no Undo either: only Remove and Hide do. The one-tap `Use phone storage` in the ⋮ menu moves the layout out of your synced folder and drops the folder grant, with no confirm. | Code: `HomeScreen.kt:564-565`, `store/ConfigStore.kt:221`; no restore UI in `ui/` | (a) Confirm dialog on `Use phone storage` (S). (b) `Undo` snackbar after a drag move or page change (M). (c) A `Restore previous layout` list as in §8 (M). | S + M + M |

## Tier 2: Noticeable every day

| # | Rough edge | Evidence | Fix idea | Size |
|---|---|---|---|---|
| 4 | **Apps open without a launch animation** (*inferred*). The launch call passes no source bounds or options, so Android has nothing to zoom from. It will probably feel abrupt next to stock launchers. I didn't time launches. | Code: `apps/InstalledApps.kt` `startMainActivity(…, null, null)` | Pass the tapped icon's bounds and a scale-up `ActivityOptions`. Check reduced-motion. | S–M |
| 5 | **The drawer is a plain A–Z list.** No fast-scroll strip or letter jump. No recent or frequent apps. `drawer.sort: installTime` and `drawer.rules` groups are in the spec (§2), but I found no code that applies either: the drawer always sorts by label. The drawer is also always 4 columns, where the spec says it follows `grid.columns`. | Code: `model/Reconcile.kt:63`; `ui/HomeScreen.kt:1109`. Docs: §2 | Apply the sort and groups the file already holds (M). Add an A–Z side rail (S). Follow `grid.columns` (S). Recents later (M). | M |
| 6 | **Drawer long-press is slow and can't drag.** The app menu opens on release, not on hold (no buzz). You can't keep holding to drag the app onto home, so `Add to home` drops it in the first free cell and you then drag it again. There are no app shortcuts (the long-press jump-to-action items stock launchers have) and no `Uninstall`. | Issue: #283, #284. Code: `DrawerMenu` has three actions only | Do #283 first (S). Then #284 (M). Add app shortcuts from `LauncherApps` (M). `Uninstall` is one line (S). | M |
| 7 | **Settings are a long flat ⋮ menu of about 14 items.** Everyday switches (`Label backdrop`, `Open keyboard with drawer`) sit beside file plumbing (`Config folder`, `Include hidden apps in apps.json`, `Agent instructions`). The home-menu `Settings` just opens that same menu. `Grid` size and `Hidden apps` (spec §8) aren't in it: Hidden apps is reachable only from the drawer's ⋮. | Code: `HomeScreen.kt:559-605`, `HomeAction.SETTINGS` comment "No Settings screen yet". Docs: §8, §14 R10 | Build the Settings screen §8 describes, in two groups: `Home` and `Folder and agents`. Keep ⋮ to 3–4 items. | M |
| 8 | **Rotate, fold or resize resets edit mode, the drawer, an open folder and open sheets.** The page is kept. | Issue: #258. Code: manifest comment (no `configChanges`, on purpose) | `rememberSaveable` for the UI state the issue lists. | S–M |
| 9 | **Holding an icon on home gives no feedback until your finger moves.** That's a "known gap" in the 1.0 notes. People think the hold didn't register. | Issue: #294. Docs: release notes 1.0 | Fire the haptic and a small lift at the hold timeout, before movement. | S |
| 10 | **Search finds apps only, and it ignores what you use.** It ranks by label match, then drawer order. There's no usage ranking, no calculator, no contacts or web fallback. Contacts would need a permission, which Reflow avoids. | Code: `ui/DrawerSearch.kt` | Add a last row `Search the web for "…"` that hands off to the browser (no permission). Rank by launch count, kept on the phone. | S + M |
| 11 | **Pages can only be added, not reordered, renamed or removed while they hold items.** `+ Page` (1.1) covers adding. | Docs: §1.3, §4.2 "Adding and removing pages" | `Remove page` with confirm, then reorder in the Pages list, then rename. | M |
| 12 | **Resizing a widget takes an extra step.** Long-press offers `Edit layout`, and only then can you resize. Other launchers put `Resize` on the long-press menu, so this feels slower every time you tweak a widget. TalkBack already has a `Resize…` action (§16.4). | Docs: §16.4, §16.7.3. Christopher's preference. | Add `Resize` to the long-press menu for `appWidget` and `card` only (not apps, folders or shortcuts). It enters normal edit mode (§4.1) with that item already selected and its resize handles showing, and reuses the existing handle and cell-snap path (§16.4). You leave it the usual §4.1 ways: `Done`, Back, Home, or 30 s idle. When TalkBack is on, the menu's `Resize` opens the stepper sheet instead. | S–M |

## Tier 3: Occasional or polish

| # | Rough edge | Evidence | Fix idea | Size |
|---|---|---|---|---|
| 13 | **Look and feel is the Material 3 baseline purple.** The home uses fixed `darkColorScheme()` / `lightColorScheme()`. App icons are the full-colour ones, with no themed (monochrome) icons on Android 13+, no icon packs, and no notification badges. These are the first things people compare. | Code: `ui/HomeTheme.kt`; `apps/InstalledApps.kt` `getBadgedIcon(0)`. Docs: ux.md "Not in the MVP" | Dynamic colour as an opt-in (Q-T7, below). Themed icons when the app ships one (M). Icon packs and badges: later, need a design pass (L each). | M / L |
| 14 | **The open folder is a small dialog with a `Close` button, always 4 columns.** The spec says 3 columns when the grid has 3. Long-press on a folder app closes the folder first, then opens the sheet. | Code: `HomeScreen.kt:1049-1062` | Draw the folder as the §3 centred card, follow `grid.columns`, keep it open under the sheet. | S–M |
| 15 | **Widget picker.** Samsung previews can be blank (no icon fallback), and the landscape dock hint wraps to 5 lines. | Issue: #331 | Fall back to the app icon. Shorten or hide the hint in the rail. | S |
| 16 | **Small visible nits already filed.** The title-bar date shrinks to `O…` on narrow screens (#195). A top notice can cut labels at 320 dp (#204). The red ⋮ dot has 2.45:1 contrast (#192). The drawer jumps when the keyboard opens (#286). Enter on the widget sheet closes it after 3 repeats (#190). | Issue: as listed | Batch them as one "small fixes" pass. | S each |
| 17 | **Short multi-window panes lose 48 dp touch targets.** | Issue: #208, #204 | As filed. | M |
| 18 | **Fold hinge and half-open posture.** Menus can overlap the hinge. | Issue: #226, #224 | As filed. Only matters to foldable owners. | M |

## Built-in Reflow widgets: short opinions

Archimedes owns the design. These are only UX asks, kept to the tokens in ux.md.

- **Start with four:** clock and date, battery, search bar, and one "next event" or note-style text widget fed from the folder. They need no permission and no internet. This matches the "no risky permissions" promise in the README.
- **Search bar** is the one that matters most for daily use. Tapping it should open the drawer with the keyboard up (the same path as `Apps`), not a second search.
- **Now-playing needs a notification-access permission.** That breaks the "no risky permissions" line, so I'd leave it out, or make it a separate opt-in later.
- **Weather needs data.** With no internet permission in the app, it can only show what an agent writes into the folder, with the `as of 08:38` header the card chrome already uses (§17.9). I'd call it a card, not a built-in.
- **Look:** use the same rules as cards. The surface is `surfaceContainerHigh` at 12 dp corners (the drag plate), the header is the §17.9 title row, and everything is on a 4 dp rhythm. Text straight on the wallpaper stays white with the label shadow (§12.1). Targets stay at 48 dp or more. A built-in widget should still follow the light and dark theme.
- **Sizes:** offer 1×1 up to 4×2 with one clear layout per size. Don't add a style editor in v1.
- **TalkBack:** one spoken sentence per widget, for example `Battery, 82 percent, charging`. Not a read-out of each piece.
- **Don't draw it differently from third-party AppWidgets** in edit mode. Same frame, handles, Move and Remove (§16.4, §16.7).

## Open questions for Christopher

1. **Q-T7: should Reflow take its colours from your wallpaper (Android 12+)?** It would make menus, sheets and banners match your wallpaper. Warning and error colours would stay fixed. *Default: yes, as an opt-in `theme: "dynamic"`, off until you choose it, built after the Tier 1 fixes.* (Still open in ux.md.)
2. **Should the phone be able to do everything the file can, or is the file the main way to edit?** Today folders, drawer groups and grid size are file-only. *Default: the phone does the everyday jobs (folders, restore, grid size, work apps). Rare ones stay in the file.*
3. **Should search stay apps-only?** Adding contacts needs a permission. *Default: apps only, plus a `Search the web` row that opens your browser. No contacts.*
4. **Do you want a real Settings screen?** It replaces the long ⋮ menu (item 7). *Default: yes, in the polish pass, right after Tier 1.*

## Suggested order

1. Tier 1: items 1 to 3. Item 2's toast fix and item 3's `Use phone storage` confirm are quick and can go first.
2. Then items 6 (drawer long-press), 9 (hold feedback) and 4 (launch animation). They are the cheapest "feels like a real launcher" wins.
3. Then 7 and 5, which need a little design.
4. Tier 3 as time allows.
