# Native widgets: hosting Android AppWidgets (design delta)

**Status:** proposal, 2026-11-18; **decisions D1–D4 settled by Christopher 2026-11-18** (§4); Pragma's #297 review
folded in. Author: Archimedes. **Docs only.** It doesn't create issues or change code.
**Baseline:** main `7dba811` (tag `v1.0.0`).
**Why now:** Christopher's new top priority, ahead of F-Droid and Play. Reflow needs third-party widgets (weather,
calendar, Home Assistant, KWGT…) to be a daily driver for store users.

This is a **delta**. The design itself already exists, and this doc doesn't repeat it:
- [docs/research/widgets.md](../research/widgets.md) §5.1–§5.4: the record shape, sizing rules, the device-local
  binding table, the bind flow, and restore options.
- [docs/ux.md](../ux.md) §16 (Pragma, Q-W1–Q-W5): the picker, placeholder states and copy, the bind explanation,
  resize, the out-of-range cue, sync, and Remove/Move/Undo for elements (§16.7).
- [docs/design/dynamic-widgets.md](dynamic-widgets.md) §4 "Hosted AppWidgets": AppWidgets are **outside** the card
  consent model. Android's bind dialog is their consent, and the review list shows them as `Allowed by Android`.

Below: what main already has (§1), what changes compared with those docs (§2), how much is shared with cards (§3),
Christopher's decisions (§4), and the issue breakdown in build order (§5).

## 1. Exists vs missing on main `7dba811`

| Area | On main | Missing for native widgets |
|---|---|---|
| Element container, grid placement block | `Elements.Placement` and `placement()` (`config/Elements.kt:25-33`). The schema base block is `$defs/element` (`schema/launcher-config.v1.schema.json:450+`). | Nothing |
| Stage 1 cell reservation | `Elements.reserved()` (`Elements.kt:35-40`) reserves cells for shown **and** skipped elements. It's used by edits (`model/Edit.kt:240-250`) and undo merge (`model/UndoMerge.kt:53`). Strict overlap and out-of-grid checks for elements (`config/Validator.kt:157-177`, B2). | Nothing. v1.0 phones already skip an unknown `appWidget` and keep its cells free of apps. |
| Feature levels | `FEATURE_LEVEL = 4` (`Elements.kt:20`, `scripts/validate.py:36`). Level 3 is "reserved for appWidget". Docs reserve 5 for `windowOverrides` and 6 for `net.fetch`. Neither is built. | A level for `appWidget`. **Not 3** (§2.1). |
| `appWidget` type | None. `Elements.types` = `TitleBar`, `Card` (`Elements.kt:66`). `AppWidgetHost` is used nowhere in `app/`. | Schema, codec, `validate.py`, fixtures |
| Host, bindings, bind flow | None. No `AppWidgetHost`/`AppWidgetHostView`/`bindAppWidgetIdIfAllowed` anywhere. No `filesDir/widgets/`. | All of it |
| Device-local store pattern | `consent/ConsentStore.kt:17-40`: `filesDir/consent/consent.json`, temp + rename, GC of IDs that left the file after each good load. `data_extraction_rules.xml` excludes `consent/` from backup and transfer. | A twin for bindings (same pattern, separate file). `widgets/` needs the same backup exclusion. |
| Sizing a widget host in cells | `HomeGeometry.widgetHostSizeDp` / `widgetHostOffsetDp` (`ui/HomeGeometry.kt:226-240`). They cap each span at 80 dp × 1.5 per cell and centre it. They're used by cards (`ui/HomeScreen.kt:1062-1086`). | Report that capped size to the provider (`updateAppWidgetSize` / `OPTION_APPWIDGET_SIZES`) |
| Grid drawing of elements | `PageGrid` draws consent tiles, live targets and #257 placeholders in their cells, and counts their cells as occupied (`HomeScreen.kt:924-1086`). | A fourth list: hosted widgets and their placeholders |
| Placeholder tile | `CardPlaceholderTile` (`ui/ConsentTile.kt:120`), `CardPlaceholder` (#257/PR #265, `config/CardPlaceholder.kt`). Release builds use it while `CARDS_LIVE=false` (`app/build.gradle.kts:77,84`). | ux.md §16.2 states (`Tap to set up`, `Not installed`, `Tap to allow`, …). Reuse the frame. |
| Widget long-press sheet | `WidgetItemSheet` (`ConsentTile.kt:238`) with `REVIEW`, `PERMISSIONS`, `EDIT_LAYOUT` only (`consent/ConsentUi.kt:58`). Opened from `HomeScreen.kt:705-713`. | ux.md §16.7.3 actions: `Reconfigure`, `App info`, **`Remove from home`, `Move to…`, `Resize…`** (missing for cards too) |
| Element edits | `Edit.AddElement`, `Edit.SetElement` (`Edit.kt:42-52`). | Element remove + Undo hold (ux §16.7.1), move with a span collision check (§16.7.2), resize (§16.4). **None exist for any element.** |
| Widget picker | None. The home menu has Edit layout / Pages / Review / Reload / Settings / Wallpaper (`ui/ItemSheet.kt:97-99`). | ux §16.1 `Widgets` entry and picker |
| Agent discovery | `apps.json` lists launchable apps (`store/AppsJson.kt`). The template `AGENTS.md:16` says "no `widget`". | A list of installed widget providers so an agent can write a correct `appWidget` (§2.4) |
| Manifest | `<queries>` only covers `MAIN/LAUNCHER` (`AndroidManifest.xml:6-11`). `allowBackup="false"`. | A `<queries>` entry for `android.appwidget.action.APPWIDGET_UPDATE`, so `getInstalledProviders*` sees providers on Android 11+ (to verify, NW-2 (#299)). No new permission: `BIND_APPWIDGET` is never requested. |

**In one line:** the file side (placement, reservation, skip rules) is done. The phone side of hosting is entirely
missing. So are the grid-element edits (remove/move/resize) that ux.md §16.7 specs for *all* elements.

## 2. What changes vs the existing docs

### 2.1 Feature level: `appWidget` takes **5**, not the reserved 3
A feature level means "this app understands everything up to N". v1.0.0 shipped at **4** without `appWidget`. So
`requires: 3` would pass the level check on v1.0 and then fall to "unknown type". Worse, an agent reading level 4
would wrongly assume widgets work. **Ruling:** a level number is fixed when the feature *merges*, in merge order.
Reserved numbers are proposals. **3 is retired, never used.** `appWidget` = **5**. `windowOverrides` and `net.fetch`
(designed, not built) take the next free numbers **in merge order** when they land. They are named symbolically (`FL_WINDOW_OVERRIDES`,
`FL_CARDS_READOUT`, `FL_FETCH`, see reflow-widgets.md C9), and no number is reserved. No shipped app knows 5 or above, so
renumbering costs nothing. NW-1 (#298) updates `Elements.kt`, `validate.py`, the schema description, and the level
mentions in ARCHITECTURE §19, layout-overrides.md §4.1/§8, dynamic-widgets.md §9 and ux.md Q-A6.

**Release gate:** don't tag a release at level 5 until NW-4 (#301) (tap-to-set-up) is merged. Otherwise a level-5 phone
shows widgets it can't bind. No build flag is needed: main isn't released per PR.

### 2.2 Schema: one new element type, `schemaVersion` stays 1
`$defs/appWidget`: grid-placed, strict keys `id, type, requires, enabled, page, x, y, w, h, content, x-*`;
`requires` ≥ 5. `content`: `provider {package, class}` (required), `profile` (`personal` | `work`, default
`personal`), `label` (optional, max 40; used only on the placeholder before the provider is known).
**Change from research §5.1:** drop `style` for `appWidget` in the first release. Frame-only styling (padding,
backdrop) has no foreseeable value worth its validator and phone UI. It can come back later as an optional key at
the next level. Because unknown keys are errors, `style` gets plain copy an agent can act on (Pragma, #297):
`elements[N]: appWidget "<id>" doesn't support "style" yet. Remove it.` The phone and `validate.py` use the same
sentence. Older phones (v1.0, level 4) skip it with "needs feature level 5… Update the app", and the cells stay
reserved. This path is already shipped and tested.

### 2.3 Load rules (unchanged principles, made explicit)
- The launcher loads the file it's given. **It never rewrites positions or sizes.** A size outside the provider's
  min/max renders anyway, with a phone-only warning (ux §16.5). `validate.py` can't know providers and says so, as it
  does for contrast.
- Overlap and out-of-grid stay **B2** on main, as for every element today. On a `windowOverrides` layout (when built),
  an element that doesn't fit is **not shown there**, with a warning (layout-overrides.md §6.1). There's nothing
  widget-specific about that.
- A missing provider or an unbound widget → a placeholder in its cells. A `profile: "work"` widget is valid in the
  file but not hosted in this milestone (D3): its placeholder reason is `Work widgets come later`. Tapping it does
  nothing, long-press opens the usual sheet, and TalkBack reads `<Label>, widget, Work widgets come later`. The element is kept verbatim. A file load **never** opens a system dialog.
- **No polling.** `AppWidgetHost.startListening` runs on home `onStart` and `stopListening` on `onStop`. Providers
  push RemoteViews. Provider install, update and removal come from the existing `LauncherApps.Callback`
  (`apps/InstalledApps.kt:96-106`). Binding GC runs on the existing successful-load event, never on a timer.

### 2.4 What agents see (new, small)
- **`apps.json` gains `widgets[]`**, the installed providers visible to this phone (same work-profile gate as apps):
  `{package, class, label, profile, size: {w, h}, minSize, maxSize, resize: "none"|"h"|"v"|"both", configure: "none"|"optional"|"required"}`,
  with sizes **in cells for this phone's current main grid**. This lets an AI place a widget correctly with no picker.
  It's one more array in an existing file, not a new file.
- **`status.json` gains `appWidgets: {"<elementId>": "<state>"}`.** States: `bound`, `needsSetup`, `needsConfig`,
  `notInstalled`, `unsupported` (a work-profile widget, D3; not `paused`), `failed`. `paused` is reserved for NW-9.
  status.json reports only resolved states, never a bind in progress. Display only, like the consent map. Never the `appWidgetId`.
- Template `AGENTS.md` and `skills/reflow-launcher/SKILL.md` get a short `appWidget` section. A widget that the file
  adds binds silently when Android already allows it and the widget needs no configure screen (D1). Otherwise it
  shows `Tap to set up` until someone taps it on that phone.

### 2.5 Device-local state
`filesDir/widgets/bindings.json`: `elementId → {appWidgetId, provider, profile, state}`, kept like `ConsentStore`
(temp + rename, GC after a stable good load, held during the Remove-Undo window per ux §16.7.1). A provider change in
the file → delete the old ID → `needsSetup`. The `AppWidgetHost` is **app-scoped** (in `ConfigLauncherApp`), so
activity recreation on rotate, fold or resize (no `configChanges`, `AndroidManifest.xml:19-21`) rebuilds views, not
bindings. Add `widgets/` to both exclusions in `data_extraction_rules.xml`: a widget ID is meaningless on another
device unless D2 is chosen.

### 2.6 Superseded or dropped
- Research §5.5 / ux §16.6 first-party `text`/`clock` widgets: **dropped from this milestone.** Cards (S3) cover
  config-defined widgets. The picker lists only Android providers.
- Research §6 stage table and dynamic-widgets.md §9 row 2 "level 3": superseded by §2.1.
- Research §7 Q-C1–Q-C3 (bind policy, sync, restore) were never answered: their IDs collided with the design doc's
  Q-C set. They're re-asked below as D1–D2.

## 3. Sharing with dashboard cards (S3, #137, #140–#143)
**Share exactly one thing: grid-element edits, NW-6 (#303).** Remove + Undo with a device-local hold, `Move to…` with a
span collision check, the `Resize…` sheet, drag-move of a span, and the NW-8 (#304) drag-resize handles. ux.md §16.7 already specs these for every
grid-placed element, none exist yet, and cards need them anyway. Building them once is clearly cheaper. The
placeholder **frame** composable is reused too (a refactor, not a feature).
**Don't share** rendering, consent, data files or `CARDS_LIVE`. Hosted widgets are RemoteViews in an
`AndroidView`. Cards are Compose with a consent store. Coupling their pipelines buys nothing. S3-E/F/G stay on their
own track and can land before or after this milestone.

## 4. Decisions (Christopher, 2026-11-18)
All four are decided. D1–D3 took the recommended default; D4 changed.

- **D1. Widgets an AI adds: Decided: automatic when Android allows it and there's no configure screen; otherwise
  `Tap to set up`.** A file load never opens a dialog. **No flash (Pragma, #297):** while a load is trying to bind
  silently, show the frame with only the icon and label, no reason line. Show `Tap to set up` only once the silent bind
  has failed or isn't allowed. See NW-4 (#301).
- **D2. Phone move / system restore: Decided: no restore support.** After a phone move or reinstall, each widget shows
  `Tap to set up` once; layout and positions come from the file. `widgets/` stays out of backup and device transfer.
  Restore remap stays follow-up NW-10.
- **D3. Work-profile widgets: Decided: personal widgets only for now.** Work widgets keep their cells and show
  `Work widgets come later` (Pragma's copy, matching #265's "arrive in a later version"); status.json state
  `unsupported`. Work-profile hosting stays follow-up NW-9.
- **D4. Phone resizing: Decided (changed from the recommendation): drag corner handles ship in the first release.**
  Architect's split: NW-6 (#303) keeps the long-press `Resize…` sheet with − / + steppers (the accessible path, also exposed
  as TalkBack custom actions), so NW-6 (#303) stays reviewable. **NW-8 (#304) "Drag-resize handles"** is built right after NW-6 (#303),
  inside the milestone and before the NW-7 (#305) QA pass, and needs real-device testing.

## 5. Issue breakdown (build order)
Prefix `NW-` (native widgets). **Device:** **E** means an emulator or JVM covers it; Grace owns these, and the
generic phone/7"/10" emulator profiles stay the gate. **R** means a hands-on spot check on real hardware by
Christopher, coordinated by Grok Bot (not Grace's work): a test phone and/or a 10-inch tablet. R checks are spot checks,
not the definition of done. No foldable is needed: fold/hinge cases are covered by
the existing emulator matrix (and the hinge check stays waived).

**NW-1 (#298). `appWidget` schema at feature level 5 (no host yet).** *Schema change: yes. New element type, level 5,
`schemaVersion` 1.*
Scope: `$defs/appWidget`; `config/AppWidget.kt` `ElementType` (strict keys per §2.2); `validate.py` mirror and level 5;
fixtures (valid, `profile: "work"` valid, provider missing class, `style` rejected with
`elements[N]: appWidget "<id>" doesn't support "style" yet. Remove it.`, `requires: 4` rejected); renumber level mentions (§2.1);
template `AGENTS.md`/skill section.
Acceptance: phone and `validate.py` agree on every fixture. A v1.0.0 APK loading a level-5 widget file skips it with
the "Update the app" warning and keeps the cells (no B2). Write-back round-trips the element verbatim.
Grace: run `test_validate.py` plus a fixture parity run. Install v1.0.0 and load a file with an `appWidget`, then try
to drop an app on its cells. **E.**

**NW-2 (#299). Widget host and device-local bindings.**
Scope: app-scoped `AppWidgetHost` (fixed host ID), start/stop with home visibility; `widgets/bindings.json` store
(ConsentStore pattern); GC after a stable good load; Undo-hold API for NW-6 (#303); provider lookup per profile; manifest
`<queries>` for `APPWIDGET_UPDATE`; `data_extraction_rules` exclusion.
Acceptance: unit tests for the store (load/save/GC/hold/provider change). A provider is visible via
`getInstalledProvidersForProfile` on API 30+ with no `QUERY_ALL_PACKAGES`. Bindings survive process death and
rotation. A bad file (B2) never deletes a binding. Removing an element from the file frees its ID only after the
next stable load.
Grace: JVM tests; emulator API 29 + 35 provider listing; `adb shell dumpsys appwidget` shows our host and
no orphan IDs after a remove and reload. **E**, plus one **R** (Christopher, via Grok Bot): provider listing on a test phone, since One UI hides some providers.

**NW-3 (#300). Render hosted widgets and placeholders.**
Scope: `AppWidgetHostView` via `AndroidView` in `PageGrid` cells, using `widgetHostSizeDp` and reporting sizes; ux
§16.2 placeholder states on the shared frame, plus D3's `Work widgets come later` (tap does nothing; status
`unsupported`); long-press on a live widget opens the widget sheet (intercept like
Launcher3's long-press helper); the pager swipe must not fight scrollable widgets; `status.json` `appWidgets` map;
§16.5 out-of-range cue and warning; `Not installed` from `LauncherApps.Callback`.
Acceptance: a bound widget draws at the right size on phone and tablet grids and updates without polling.
Uninstalling or reinstalling the provider flips the state live. Long-press always opens the sheet, never the widget's
own click. A horizontal swipe on a non-scrolling widget changes page. Placeholders announce
`<Label>, widget, <reason>` in TalkBack (`<Label>, widget, Work widgets come later` for a work widget).
Grace: emulator with AOSP Clock/Calendar widgets plus the screen matrix (phone, t7, t10, land). **R** (Christopher, via Grok Bot) on
a phone and a tablet: Google Calendar (scrolling list), a weather widget, a third-party widget host such as KWGT, and a Home Assistant companion widget if installed.
Check long-press vs tap vs swipe, and rotation.

**NW-4 (#301). Tap to set up: bind and configure (incl. D1 silent bind).**
Scope: placeholder tap → `bindAppWidgetIdIfAllowed` → explanation sheet once per package (ux §16.3) →
`ACTION_APPWIDGET_BIND` → required configure activity → bound. Cancel paths (`Tap to allow`, `Tap to finish setup`,
`deleteAppWidgetId`). `Reconfigure` in the sheet. Binds only while home is resumed. D1: silent bind on load when
allowed and no required configure, never any dialog from a load; during the attempt the frame shows only icon and
label, no reason line, and `Tap to set up` appears only once the silent bind has failed or isn't allowed.
Acceptance: every §16.2 transition is reachable and reversible. Cancelling never leaks an ID (checked with
`dumpsys appwidget`). An agent adding six widgets causes zero dialogs. The first widget from a package shows the
explanation once. No `Tap to set up` flash on a silent bind.
Grace: emulator for the state machine (AOSP providers with and without configure). **R** (Christopher, via
Grok Bot) on a test phone: Samsung's bind dialog, "Always allow", Samsung or Google configure screens, e.g. Calendar
account pick; and on a 10-inch tablet.

**NW-5 (#302). Widget picker and `apps.json` providers.**
Scope: home long-press `Widgets` (+ edit-mode `+ Widget`) → picker per ux §16.1 (grouped by app, search, previews,
size chip). Placing at the first free rectangle takes one `AddElement` write, then the NW-4 (#301) flow. `apps.json`
`widgets[]` per §2.4.
Acceptance: picking a provider writes exactly one element with provider/page/x/y/w/h and default size from
`targetCell*` (else rounded `minWidth/minHeight`). No room on a page → next page → new page (cap 20). `apps.json`
sizes match what the picker would place.
Grace: emulator plus unit tests for size-to-cells and free-rectangle search on the screen-matrix grids. **R** (Christopher, via
Grok Bot): open the picker on a test phone with ~50 providers (scroll performance, preview images) and add 3 widgets end to end.

**NW-6 (#303). Grid-element edits (shared with cards).**
Scope: ux §16.7.1 `Remove from home` + Undo with the device-local hold (bindings *and* consent), §16.7.2 `Move to…`
for spans, §16.4 `Resize…` sheet with − / + steppers (bounded by provider min/max for widgets, by `minSpan`/layouts
for cards, and by free cells; also TalkBack custom actions), and edit-mode drag-move of a span. No drag handles;
those are NW-8 (#304). Sheet actions per §16.7.3. New `Edit` ops replay by ID at
write time.
Acceptance: each action is one write that changes only that element's keys. Undo restores a widget with no rebind and
a card with no re-prompt. A collision at write time → `move_element_taken`, no write. TalkBack custom actions match
§16.7.3.
Grace: JVM `Edits`/replay tests; emulator for sheets and TalkBack. **R** (Christopher, via Grok Bot): drag-move of a 4×2 widget on a test phone
(the same hands-on check style as #253).

**NW-8 (#304). Drag-resize handles (D4).**
Scope: ux §16.4 edit-mode frame: select a widget or card → white 24 % frame with edge handles (corners when both axes
resize); size snaps to whole cells with a soft haptic per step; stops at occupied cells (red edge; outline only with
reduced motion) and at provider min/max; release → one `w`/`h` write through the NW-6 (#303) resize op. No reflow.
Acceptance: one write per release; never crosses min/max or an occupied cell; the handle hit target is ≥ 48 dp; a
cancel (rotate, window resize) writes nothing; the `Resize…` sheet still works.
Grace: JVM for the snapping math; emulator for the flow. **R** (Christopher, via Grok Bot) on a test phone and a 10-inch tablet (fingers, small cells, edge
handles near screen edges and the dock).

**NW-7 (#305). Release QA pass for native widgets.**
Scope: the matrix on a signed release APK. Reboot; launcher update over v1.0.0 (cells were reserved, now live);
reinstall (everything → `Tap to set up`, positions intact); provider update and uninstall; tablet vs phone sizes from
one file; battery sanity (no wakeups while home is hidden).
Acceptance: no lost or orphaned IDs after each scenario (`dumpsys appwidget`); no file write from any bind,
configure or load; the release notes list known gaps (no restore remap, work widgets come later).
Grace: release-variant QA on the emulator profiles and the API spread (29, 31, 35). **R** (Christopher, via Grok
Bot): hands-on spot checks on a test phone and a 10-inch tablet.

**Build order:** NW-1 (#298) → NW-2 (#299) → NW-3 (#300) → NW-4 (#301) → NW-5 (#302) → NW-6 (#303) → NW-8 (#304) → NW-7 (#305) (QA last).

**Follow-ups, outside the milestone:** NW-9 work-profile widgets (D3); NW-10 system-restore remap via `ACTION_APPWIDGET_HOST_RESTORED` (D2); optional frame `style` for
`appWidget` (next level).

NW-6 (#303) can start in parallel after NW-2 (#299) and also unblocks cards; NW-8 (#304) needs NW-6 (#303). NW-7 (#305) needs all of NW-1 (#298)–NW-6 (#303) and NW-8 (#304).
