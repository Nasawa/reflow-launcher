# Reflow readout widgets: agent-authored cards, live data, and a small built-in set

**Status:** proposal, 2026-11-27. Author: Archimedes. **Docs only.** It creates no issues and changes no code.
**Baseline:** main `86b9ab2` (1.1.0 dev builds; feature level 5).
**Pairs with:** [polish-audit.md](polish-audit.md) (see polish-audit.md (Pragma)): her 17-item ranked audit and her 4 open questions land on this same branch and PR.
**Christopher's examples have arrived** (§8): a card Claw built with Cocoon, and a KWGT widget backed by Home Assistant. More may follow, and this doc may be revised.

**The goal, in Christopher's words (relayed by Grok Bot):** his AI (Claw) should be able to "make it do this, using the JSON schema, and upload it to my phone", and simple readouts shouldn't need a special app like Cocoon. A generic clock or battery set is now secondary.

## 0. Rulings in one screen

1. **Readout widgets are the evolution of dashboard cards. There is no new element type and no parallel system.** [dynamic-widgets.md](dynamic-widgets.md) already has the right shape: a `card` element, no scripts, flow layout, per-widget capabilities, device-local consent, `data.file`, `ui.reload`, `staleAfter`, `source`, and a designed (unbuilt) `net.fetch`. What changes is in §3. The `text`/`clock` element types from research §5.5 and ux §16.6 are **dropped for good**. A clock is a card.
2. **Two milestones.** **M1: agent-written files.** Claw writes JSON (and an optional picture) into the folder and the card renders it. Both of Christopher's examples work, nothing new is asked of Android, and no secret ever reaches the phone. **M2: live fetching.** The phone reads a JSON URL itself (Home Assistant or any endpoint) with a named, device-local secret. M2 adds the `INTERNET` permission (already agreed, Q-C1) and the secret store.
3. **Bindings are three things and nothing more:** the agent-written file, an HTTP GET of JSON, and Home Assistant as *just a JSON URL with a bearer secret* (no HA client, no HA-specific code; this keeps Q-C5 intact).
4. **No formula language.** Simple `{data:…}` and `{fetch:…}` bindings, a closed set of five formatters, and **thresholds** (HA gauge `severity` style) replace KWGT formulas and HA Jinja templates.
5. **Secrets never touch the layout file.** The file names a secret (`"ha-token"`). The value is typed on the phone, kept in Keystore-wrapped device-local storage, and bound to one origin. It can only be sent to that origin, only for a widget the user approved for that origin.
6. **Battery:** fetch only while home is visible, on "glance" (stale-on-show) plus a floor-limited timer, with backoff. **No JobScheduler, WorkManager, alarms or services.** Weather needs no built-in fetcher: it is a card with a source.

## 1. Research

### 1.1 How launchers do built-in widgets

| | Built-in types | Data and permissions | Configured | Refresh | Themeable |
|---|---|---|---|---|---|
| **Niagara** | One clock widget (time, date, battery, weather, upcoming events), calendar agenda, minimal weather, media player; widget stacks and pop-ups hold third-party widgets ([clock](https://help.niagaralauncher.app/article/23-custom-clock), [Pro](https://help.niagaralauncher.app/article/40-niagara-pro-features), [media](https://help.niagaralauncher.app/article/11-media-widget)) | Weather from one provider, OpenWeather, and the provider is deliberately not selectable ([weather](https://help.niagaralauncher.app/article/105-weather-widget)). Media shows only while Android shows a player notification, with notification access needed. Calendar permission: not stated on the pages I read | In-app settings, long-press the clock | Weather about every 30 min, shorter when unstable or when the agenda sheet opens | Clock styles (Pro). Any other widget can replace the clock |
| **Lawnchair** | *At a Glance* strip: date/time, weather, battery, now playing, notifications; Smartspacer can be the provider ([docs](https://docs.lawnchair.app/integrations/smartspacer), [issue #6322](https://github.com/LawnchairLauncher/lawnchair/issues/6322)) | Weather needed the Google app. Battery shows only when low (under 15%, per a maintainer). Now playing needs notification access | Settings → At a Glance, toggles per section | Not stated on the pages I read | Alignment, icon style and background, as reported by [one review](https://en.androidayuda.com/news/applications/lawn-chair-at-a-glance-widget/) |
| **Kvaesitso** | Clock, weather, music, calendar, favorites, notes ([clock](https://kvaesitso.mm20.de/docs/user-guide/widgets/clock.html), [weather](https://kvaesitso.mm20.de/docs/user-guide/widgets/weather-widget.html), [music](https://kvaesitso.mm20.de/docs/user-guide/widgets/music-widget.html), [calendar](https://kvaesitso.mm20.de/docs/user-guide/widgets/calendar-widget), [notes](https://kvaesitso.mm20.de/docs/user-guide/widgets/notes-widget.html)) | Weather: network, a provider choice, and location permission *or* a typed fixed location ([integration](https://kvaesitso.mm20.de/docs/user-guide/integrations/weather)). Music needs notification access, and on sideloaded Android 13+ builds the user must first flip "Allow restricted settings" ([media](https://kvaesitso.mm20.de/docs/user-guide/integrations/mediacontrol.html), [restricted](https://kvaesitso.mm20.de/docs/user-guide/troubleshooting/restricted-settings.html)). Calendar reads the Android calendar provider | Settings plus per-widget tune icon. **Conditional parts:** the clock shows alarm only if one rings within 12 h, battery only when charging or under 15% | Not stated | Six clock styles, compact mode |
| **Smart Launcher** | Clock+weather, music, battery, notes, calendar, compass, photo, plus RSS feed ([6.1](https://www.androidheadlines.com/2022/10/smart-launcher-6-1-update-smart-widgets.html), [6.4](https://www.smartlauncher.net/blog/smart-launcher-6-4-is-here), [6.5](https://www.smartlauncher.net/blog/introducing-smart-launcher-6-5)) | Calendar read and location permissions, per its support docs (found through search, not opened page by page). Updates depend on Android background limits ([FAQ](https://docs.smartlauncher.net/products/faq/performance-and-battery/widget-updates-stop-working)) | Long-press a widget. More than 64 responsive sizes | Android widget updates | Material You, translucent, widget stacks |
| **Smartspacer** (plugin model) | Targets pushed by other apps; the host only renders them ([Lawnchair doc](https://docs.lawnchair.app/integrations/smartspacer)) | Per plugin | Its own app | Per plugin | Appearance in its app |

### 1.2 Authoring models to adapt (config-defined widgets)

| Source | What it is | Adopt | Cut |
|---|---|---|---|
| **Cocoon** ([repo](https://github.com/Nasawa/cocoon-android), `README.md`, `CHANGELOG.md`) | **There is no widget schema to adapt.** Cocoon is an SSH client for [Outer Shell](https://github.com/outergroup/outershell): it opens apps the host serves as **web apps in a WebView** over SSH, one WebView profile per app. As I read the repo, Claw's card (example 1) was such a web app: its HTML and JS are served from the Linux host | Its security posture: non-exportable Keystore key, TOFU host pinning, per-app cookie isolation, cleartext only where explicitly allowed, no backups of secrets. Those map onto our secrets and `net.fetch` rules | The web-app model itself. A readout needs an SSH client, a host daemon and a WebView with arbitrary JS. That is the iappyx anti-example ([dynamic-widgets.md §0](dynamic-widgets.md)). The declarative card replaces it |
| **KWGT / Kustom** ([formulas](https://docs.kustom.rocks/docs/reference/functions/if/), [globals](https://docs.kustom.rocks/docs/reference/globals/), [komponents](https://docs.kustom.rocks/tags/komponent/), [flows](https://docs.kustom.rocks/tags/flows/), [threshold colours](https://forum.kustom.rocks/t/is-it-possible-to-set-a-multicolor-progress-bar-to-different-colors-based-on-battery/1535)) | Absolute-layout modules; any property can be a formula (`$if(bi(level)>90, …)$`); globals; komponents (reusable sub-widgets); flows (WebGet, set a global). Progress bars take a formula level and a section count | **Segmented progress with a formula-driven colour** (as thresholds), a section count, **globals' idea** only as "Claw regenerates the JSON" | Formulas, flows, komponents and globals. They are a scripting language. Claw writes JSON, and configs are cheap. Absolute layout (it broke on a tablet) |
| **HA dashboards** ([gauge](https://www.home-assistant.io/dashboards/gauge/), [tile](https://www.home-assistant.io/dashboards/tile), [glance](https://www.home-assistant.io/dashboards/glance/), [entities](https://www.home-assistant.io/dashboards/entities/)) | Declarative YAML cards. `gauge` has `severity` (`green/yellow/red` from a value) and `segments` (`from`, `color`). `tile` has `state_content` and `time_format` (`relative`, `time`, …). `glance` is a row of entities | **Threshold lists** (`from` + colour) both for the whole fill and per segment. **`time_format`** → our formatters. glance → a row of metrics. entities → `list` | `tap_action`/`hold_action` families, themes per card, features, entity pickers |
| **HA companion widgets** ([docs](https://companion.home-assistant.io/docs/integrations/android-widgets/), [forum](https://community.home-assistant.io/t/android-widget-entity-state-refresh-rate/280553)) | Entity State and Template (Jinja) widgets, as AppWidgets. Update every ~30 min with the screen on. Real-time needs *notification access* and costs battery. "It updates when you turn on your screen" | **Refresh on glance:** update when home becomes visible and data is stale, plus a slow timer while visible. The maintainers' own reasoning is that people glance, not watch | Real-time sockets and the notification-access trick (no background wakeups), Jinja |
| **HA REST API** ([docs](https://developers.home-assistant.io/docs/api/rest/)) | `GET /api/states/<entity_id>` returns `{state, attributes, last_changed, last_updated}`. Auth is `Authorization: Bearer <long-lived token>`. `GET /api/states` returns everything the user may read | One entity = one GET of small JSON. The existing `{data:path}` subset reads `state` and `attributes.x` as is. `last_updated` can drive "as of" | `/api/states` (huge), `POST /api/template` (a POST), the WebSocket API |

### 1.3 Takeaways

1. **Composition beats widget types.** Niagara's clock, Lawnchair's At a Glance and Kvaesitso's clock components are one strip with toggled parts, not separate widgets. A template of text and metrics expresses all of them, and Claw can write that.
2. **Conditional parts are universal.** Battery only when low or charging, alarm only if soon, media only when playing. So: **a text that resolves to empty isn't drawn**, and tokens like `{batteryState}` are empty when uninteresting (§9).
3. **Permissions and data sources are where every launcher hurts.** Weather needs network plus location or a typed location plus a provider and API key. Media needs notification access (extra friction on sideloaded builds, which are *our* channels: GitHub, F-Droid). Calendar needs a runtime permission. Time, date, battery and next-alarm need none. So the built-in set stays permission-free, and everything else is data an agent or a URL supplies.
4. **Every launcher configures with settings screens.** Ours is the file, so settings UIs are cut. Phone UI is add, move, resize, remove, plus consent.
5. **The scripting-language trap is real.** KWGT's power is formulas, and HA's Template widget is Jinja. Each needs a language, a sandbox and an editor. Thresholds, formatters and bindings cover the readouts in both examples.
6. **Refresh should follow the glance.** HA and KWGT both document that background refresh is where battery goes (KWGT disabled per-second updates for that reason).
7. **A plugin model already exists for us:** Smartspacer's "others push, host renders" is `widgets-data/<id>.json` plus an agent.

## 2. What main has, and what first-party readouts reuse

| Need | On main `86b9ab2` | State |
|---|---|---|
| Element container, grid placement, reserved cells, `requires`/`FEATURE_LEVEL` (`config/Elements.kt`, now 5) | Yes | Reuse as is. A new key or kind sets `requires` to the next free level, so older phones skip the card and keep its cells (§3, C9) |
| Card schema, codec, validator, `validate.py` (`config/Card.kt`) | Yes (#137 code merged; the issue is still open) | Extend (§4) |
| Consent store, fingerprint, approval ceiling, `status.json` `consent` (`consent/Consent.kt`, `store/StatusJson.kt`) | Yes (#138) | Extend with `net.fetch` + secret lines |
| Local data file reader (`widgetdata/WidgetData.kt`, `WidgetDataReader.kt`: derived path, 256 KiB, `updatedAt`, `staleAfter`, polled with `launcher.json`) | Yes (#139) | Extend with images and the fetch snapshot |
| Card **render**, templates and taps (#140, #141); consent UX (#142); fixtures (#143) | **No.** Release builds draw `CardPlaceholder` (#257); debug has the consent tile | **Prerequisites** (§12) |
| Edits: Remove+Undo, `Move to…`, `Resize…`, drag-resize handles for any grid element (`model/ElementEdits.kt`, `ui/ResizeHandles.kt`, #303/#304) | Yes | Reuse: readouts get them for free |
| Title-bar template tokenizer (`config/Template.kt`: `{time[:p]}`, `{date[:p]}`, allow-listed patterns, no seconds) | Yes | Reuse for the card token grammar |
| Minute ticker for time tokens (title bar), foreground only | Yes | Reuse |
| Network: `INTERNET` | **Not in the manifest** (ARCHITECTURE §1) | M2 only (Q-C1 agreed) |
| Secret store | No | M2 (§6) |
| `status.json` per-widget state; `apps.json` for agent discovery | Yes (`consent`, `appWidgets`) | Extend |

## 3. The decision, and what changes in the cards design

**Chosen: cards, evolved.** The alternative was new element types per readout (`clock`, `battery`, `weather`, …). That would mean a schema, validator, renderer, picker entry and TalkBack story *per type*, a second vocabulary next to card nodes, and no way to combine readings into one strip. Claw already writes cards, and Christopher's two examples are *cards with data*, not clocks. Delta against [dynamic-widgets.md](dynamic-widgets.md), in the order it matters:

| # | What changes | Was | Becomes | Why |
|---|---|---|---|---|
| C1 | **`net.fetch` is pulled forward** as `data.sources[]` | Stage 5, "later, advanced" (Q-C2, Q-C5) | M2, right after the file milestone | Christopher wants HA and JSON URLs directly. `INTERNET` was already agreed (Q-C1). Q-C2's "local first" ordering is kept: M1 is local-only |
| C2 | **HA is a recipe, not a feature** | §8: HA "must not be tightly coupled" | Unchanged in spirit: HA is a JSON URL plus a bearer secret. No HA client, no entity picker | Q-C5 holds. The launcher knows HTTP, not Home Assistant |
| C3 | **Secrets: named, bound to `(name, origin)`, shared across widgets** | §3: scoped to `(elementId, origin)`; agents "declare that a secret slot exists" | The `net.fetch` line carries `secrets: ["ha-token"]`. One typed value per name and origin, usable by any approved widget for that origin only | Three cards, one token, one paste. Security is the same: the value can only go to its origin, and URLs are literals, so it can't be steered elsewhere (§6) |
| C4 | **New token namespace** `{fetch:<source>.<path>}`, **`data.asOf`** | Only `{data:path}` | `{data:…}` = the agent file. `{fetch:…}` = a fetched source. `data.asOf` names the path of a timestamp for "as of", else the fetch time | No key collisions between file and sources. HA gives `last_updated` |
| C5 | **Closed formatters** (5), **thresholds**, segmented progress | None (`progress` took a 0–1 value) | §4.3 | HA states are raw (`"79"`, a boot timestamp). Without formatting, every HA binding needs Claw in the middle, which defeats M2 |
| C6 | **Images: local files only, and `background`** | ux §17.8 rule 6: "no background images in v1" | Raster files in the folder, never remote, with a **scrim** the launcher draws so launcher-drawn text keeps its contrast | Example 2 needs the lobster. A scrim restores the guarantee rule 6 was protecting. Remote images stay cut (tracking pixels) |
| C7 | **Header can be hidden when the card has no `ui.openUrl`** | ux §17.9: "The header can't be turned off" | `header: false` allowed unless the card declares `ui.openUrl`. With it off, a stale or failed card still shows a 12 dp `▲` in the corner | Example 2 has no title row. The rule existed so a card can't pass for a system dialog or a link-bait notification. Without `ui.openUrl` it can't open anything. **Pragma must confirm** |
| C8 | **`ui.reload` also refetches sources**, and puts a refresh button in the header | Re-reads the data file; the Claw POC drew a refresh affordance | One tap = re-read the file and refetch all sources (rate-limited) | Example 1 shows a circular refresh at top right. It costs the agent nothing to declare |
| C9 | **Card keys added at the level of M1 and M2** | Level 4 | M1 keys → next free level (6 now). `net.fetch`, `sources`, `secrets` → the level after (7 now). Numbers are fixed in merge order | Old phones skip the card cleanly (cells kept) instead of rejecting the file for an unknown key (B2) |
| C10 | **`ui.openDrawer` tap kind** (Pragma's search bar) | None | An in-launcher action, silent, needs no consent | §9 |

**Not changed:** consent tiers, the approval ceiling, `author`, `source` and the Q-D5 `ui.openUrl` model, never-offered kinds, `widgets-data/` path rules, device-local approvals, `status.json` shape, the 256 KiB cap.

## 4. Schema delta (all on `schemaVersion` 1)

### 4.1 M1 keys (next free feature level, 6 as of `86b9ab2`)

| Where | Key | Meaning |
|---|---|---|
| card | `surface` | `"auto"` (today), `"none"`, or `{ "color": <colour>, "opacity": 50-100 }`. Colours use the title-bar grammar: a token (`light`, `dark`, `onWallpaper`, `primary`, `secondary`, `onSurface`, `surface`), `#RRGGBB`, or `{light, dark}`. Contrast is measured and warned, never an error (reuses `TitleBar` contrast code) |
| card | `background` | `{ "image": "<file>", "fit": "cover"\|"contain", "scrim": 0-70 }`. `scrim` defaults to 45 (a black overlay under launcher-drawn content). Picture rules in §4.4 |
| card | `header` | `false` hides the title row (C7). Default `true` |
| card | `summary` | Optional template (≤120 chars) that TalkBack reads as **one sentence**. Default: the nodes in order. Adopts Pragma's "one sentence per widget" |
| `text` | `role: "display"`, `align`, `font`, `status` | `display` is the large tier (sizes for Pragma, §10). `align` is `start`\|`center`\|`end`. `font` is `default`\|`mono` (the system monospace, nothing else). `status` puts a dot before the text. ux §17.9 already says every primitive takes `status`, and `Card.kt` only allows it on `metric`, so this aligns code with ux |
| `progress` | `max`, `segments`, `thresholds`, `by` | `max` (default 1) lets `value` be `79` with `max: 100`. `segments` is 2-20 (visual only). `thresholds` and `by`: §4.2 |
| `metric` | `thresholds` | Colours the status dot from the numeric `value` |
| `button` | `icon` | Material symbol name. ux §17.9 already has it, and `Card.kt` doesn't |
| `image` | new node | `{ "type": "image", "file": "<name>", "fit": … }`, same file rules as `background` |
| any node | `color` | Text, icon and fill colour (the title-bar colour grammar). The `status` tones stay the fixed ones in ux §17.8, and **red is never text** (ux §17.8) |
| text/metric | empty = not drawn | A text or metric that resolves to an empty string is not drawn. A row with nothing left disappears. This is what makes conditional parts (§1.3) cost nothing |

All sizes follow the existing cell-relative rule (`k`, ux §17.8). The `display` tier's sp values are for Pragma to set (§9).

### 4.2 Thresholds (replaces formulas and Jinja)

```json
"thresholds": [ { "from": 0, "tone": "accent" }, { "from": 75, "tone": "warn" }, { "from": 90, "tone": "error" } ]
```
- Ascending `from` values on the `value / max` scale in percent (0-100), 1-5 entries, the first `from` must be 0.
- `tone` is one of `ok`, `warn`, `error`, `accent` (the card's own text colour) or `muted`. They map to the ux §17.8 tones (`ok #4CD964`, `warn #FFC400`, `error #FF6363`) and a hex is also allowed. Dots and bars pair with the ▲/■ glyph rule that exists for status.
- **`by: "value"`** (default): the **whole fill** takes the tone of the highest entry the value reached. **`by: "position"`**: each filled segment takes the tone for its own position, which is the HA gauge `segments` behaviour and gives "the bar turns orange near the end" (example 2). Cut `position` if Sprite finds it costly. It is one enum.
- No thresholds given: the fill uses `accent`.

### 4.3 Bindings, formatters, tokens

- `{data:path}` and `{fetch:source.path}` use the existing strict path subset (dot keys and `[index]`). A missing path renders `—`, or the formatter `default`.
- **Formatters, a closed set of five**, written as `{fetch:claw.state|round:1}`. One or two per token, never user-defined, never given a variable:

| Formatter | Input → output |
|---|---|
| `round[:n]` | `79.43` → `79` (n decimals) |
| `duration` | seconds → `9h 3m` |
| `since` | ISO-8601 timestamp → elapsed `9h 3m` (the HA `sensor.uptime` / boot-time case) |
| `time[:pattern]` | ISO-8601 timestamp → local time or date, with the title-bar pattern allow-list |
| `default:text` | the text when the path is missing or null |

These cover `state` strings, boot timestamps and `last_updated` without a language. `since` and `time` are the first to cut if cost bites. Existing `{relative}` (`5 min ago`) stays.
- **Ambient tokens**, no consent and no permission, because they read no private data and URLs are literals so they can't leave the phone (§9): `{time[:p]}`, `{date[:p]}` (existing grammar), `{battery}`, `{batteryState}`, `{nextAlarm[:p]}`.
- Unknown token, formatter or key in a card at the right level → B2 with a plain sentence, as in the title bar. A card that needs a higher `requires` is skipped before this check.

### 4.4 Pictures (local files, C6)

- Live in `.configlauncher/widgets-data/<elementId>/<name>`. Like the JSON file, the folder is **derived from the element id**, so one card can't read another's pictures. Declaring `data.file` covers both. No new capability.
- `<name>`: `^[a-z0-9][a-z0-9._-]{0,63}\.(png|jpg|jpeg|webp)$`. **No SVG**, no animated formats, no remote URLs.
- Caps: 1 MiB per file, 2048 px on the longest side, at most 4 files read per card. The phone decodes down to the card's size, so the cap protects memory.
- A bad or missing picture means no picture and a load warning (`warn_image_bad`, in `status.json`). The card never fails to draw.
- **Contrast:** the scrim sits under every launcher-drawn node, and contrast is computed against the scrim colour over a worst-case picture. Text *inside* the picture (the lobster's "Brain full") is the author's artwork and is not measured.

## 5. Data bindings

| Mode | Declared as | Needs | Secret on phone | Freshness |
|---|---|---|---|---|
| **A. Agent file** (M1) | `capabilities: [{kind: data.file}]`, file `widgets-data/<id>.json` | nothing new | none | the agent's schedule, shown by `as of` and `stale` |
| **B. JSON URL** (M2) | `net.fetch` with `origins`, plus `data.sources[]` | `INTERNET`, approval of the origin | none (public endpoint) | the card's `every`, §7 |
| **C. HA entity** (M2) | Same as B, the URL is `<ha>/api/states/<entity_id>`, `auth.bearer` names a secret | as B, plus the token typed once | yes, one per HA origin | as B |

```json
"data": {
  "staleAfter": "PT15M",
  "asOf": "last_updated",
  "sources": [
    { "name": "claw", "url": "https://ha.anigeek.com/api/states/sensor.claw_status", "every": "PT5M", "auth": { "bearer": "ha-token" } }
  ]
}
```
- `sources`: 1-6 per card. `name` is `^[a-z][a-z0-9]{0,15}$`. `url` is a **literal** under one of the card's approved `origins` (the validator checks this offline). It can't be built from `{data:…}` or `{fetch:…}` (existing rule, and it stops data from steering a request).
- GET only (HEAD later). No request body, no cookies, no custom headers except the optional `auth`. The first release supports `auth: { "bearer": "<secret name>" }` only (a custom header name is cut).
- Response: UTF-8 JSON, object or array root, at most 128 KiB, 10 s timeout. Redirects stay on the same origin (existing rule). Anything else is a failed fetch.
- **One entity per source.** If Claw wants one GET for several numbers, it makes an HA template sensor with attributes and reads `attributes.x`. This is the recommended shape (example 2). The launcher never calls `/api/states` (all states).
- `data.asOf`: the path of an ISO-8601 timestamp, applied to the source that carries it. Default: the data file's root `updatedAt`, else the time the fetch finished.
- A file and sources can be used together. The tokens are different (`{data:…}`, `{fetch:…}`), so nothing collides.

## 6. Secrets and consent (fits #138 and the net.fetch rules)

**The layout file holds only names.** `net.fetch` gains a `secrets` array of names, and a source says which one it uses:

```json
{ "kind": "net.fetch", "origins": ["https://ha.anigeek.com"], "secrets": ["ha-token"] }
```
- **Fingerprint:** `net.fetch` lines are `(origin)` as designed, and each secret is a line `secret ha-token @ origin`. A new secret name, or a new origin, is a broadening → `needsApproval` / `needsApprovalAgain`. The ceiling never shrinks (Q-A9), as today.
- **Value:** typed (or pasted) on the phone in the approval sheet for that line. It is stored **device-local, wrapped by an `AndroidKeyStore` AES-GCM key**, in `filesDir/secrets/`, excluded from backup and device transfer in `data_extraction_rules.xml`, like `consent/` and `widgets/`. It is **never** in `launcher.json`, `status.json`, `apps.json`, logs, an intent, a saved instance state or a screenshot (the sheet uses `FLAG_SECURE`).
- **Scope:** keyed by `(name, origin)`, not by element id (C3). The same `ha-token` entered once serves every approved widget for `https://ha.anigeek.com`. Each widget still needs its own approval of that origin and that secret name. A widget for another origin gets *another* `ha-token`, which needs its own entry, so one origin can never receive another origin's token. The HTTP client attaches it only when the request's origin equals the stored origin.
- **Why a hostile card gains little:** fetch URLs are literals, requests are GET with no body, and responses feed only the card's display. There is no path for the token, or for the HA data, to reach a third host. The card could display what the token may read at the origin the user approved. That is the exact trade of approving it, and it is shown in the prompt.
- **Removing:** deleting an origin or a secret name from every card, or **Forget approval** (widget detail), deletes the stored value once no approved widget uses it. A separate **Forget secret** row in widget permissions clears it explicitly. Existing rule kept: removed origins delete their secrets *before* any new fetch.
- **Cleartext and LAN (Q-C3, Q-C7, unchanged):** `http://` and private addresses (HA at `homeassistant.local`) need the explicit named approval with the "less secure" disclosure. A bearer token over `http://` is sent in clear on the LAN, and **the prompt says so on the secret line**. Self-signed HTTPS needs TOFU pinning (RW-9). Prefer M1's file mode when HA is reachable only over plain http.
- `status.json` shows only display states: `consent.<id>.state` plus a warning sentence (`warn_secret_missing`: `elements[3]: widget "Claw" needs its Home Assistant token on this phone.`). Never the value.

## 7. Refresh, battery and limits

**Principle: update when someone can see it, and never to wake the phone.**
- **Sources run only while home is visible** (`onStart` → `onStop`, like `AppWidgetHost` in native-widgets.md §2.3). **No WorkManager, JobScheduler, `AlarmManager`, foreground service or push.** The app has no background execution for readouts, so `dumpsys alarm` and `dumpsys jobscheduler` must show nothing for it.
- **Refresh on glance:** when home becomes visible, any source older than its `every` is refetched (concurrently, at most 2 in flight, 5 s minimum gap per origin). While home stays visible, one coroutine re-checks on a timer. The agent file keeps its existing foreground poll with `launcher.json`.
- **`every`:** ISO-8601 duration, **minimum `PT1M`**, default `PT15M`. A smaller value is clamped with a load warning, not an error. Jitter ±10%.
- **Manual refresh** (`ui.reload`, header button): re-reads the file and refetches every source now. At most one per 10 s per widget (the existing 2 s file limit stays for the file part). The snackbar says `Updated` or `No new data` as ux §17.9 already does.
- **Backoff:** each failed fetch doubles that source's interval (×2, ×4, ×8, then holds at ×8, or `PT1H` at most), resetting on success or manual refresh. Offline (no validated default network, via a `NetworkCallback` registered only while home is visible) means no attempts and no backoff growth. Android Data Saver is honoured: a restricted network means no fetch.
- **Stale:** `staleAfter` (default 2× the smallest `every`, else 30 min) turns `as of 19:41` into `as of 19:41 · stale ▲`. Failed fetches keep the last good values, as the file reader does. Never a blank card.
- **Redraw only on change:** a source that returns an identical body doesn't recompose. Time tokens share the existing minute ticker. Battery recomposes only when the displayed `%` or state changes.
- **Hard caps:** 6 sources per card, 12 sources and 4 concurrent connections across all cards, 128 KiB per response.
- **Honest limit, to put in the user docs:** with home hidden, nothing updates. The first glance after a while shows the cached last-good values and refreshes in a second. A readout that must update with the screen off is outside this design. That is a notification or an `appWidget`.

## 8. Worked examples

Both are valid against the proposed schema. They are written so Claw can copy them. Example 1 needs only M1 (and works from a file). Example 2 shows M2 and notes its M1 form.

### 8.1 Example 1: the "Anigeek LLC" card (Claw via Cocoon), M1

Screenshot → schema: dark rounded card (`surface: "auto"`), title and `as of 19:41` (automatic header), circular refresh at top right (`ui.reload` adds it), a wide tile with caption, a title and a one-line excerpt ending in an ellipsis (`maxLines: 1`), a row of three tiles each with label, a status dot and a big value (`metric` + `status`), and a wide tile with a green dot and `All healthy`, with `123 up` on the right (a `text` with `status`, and a `text` with `align: end`).

```json
{
  "id": "w-anigeek", "type": "card", "requires": 6,
  "page": "home", "x": 0, "y": 2, "w": 4, "h": 5,
  "minSpan": { "w": 2, "h": 2 },
  "title": "Anigeek LLC", "author": "Claw",
  "source": "https://blog.anigeek.com",
  "capabilities": [ { "kind": "data.file" }, { "kind": "ui.reload" }, { "kind": "ui.openUrl" } ],
  "data": { "staleAfter": "PT30M" },
  "layouts": {
    "2x2": [
      { "type": "metric", "label": "Monitors", "value": "{data:monitors.summary}", "status": "{data:monitors.status}" }
    ],
    "4x5": [
      { "type": "tile", "tap": { "kind": "ui.openUrl", "url": "{data:blog.url}" }, "children": [
        { "type": "text", "role": "caption", "text": "Latest on blog.anigeek.com" },
        { "type": "text", "role": "title", "text": "{data:blog.title}", "maxLines": 2 },
        { "type": "text", "role": "body", "text": "{data:blog.excerpt}", "maxLines": 1 }
      ] },
      { "type": "row", "children": [
        { "type": "metric", "label": "PicSpin site", "value": "{data:picspin.value}", "status": "{data:picspin.status}", "weight": 1 },
        { "type": "metric", "label": "letmeaithatforyou", "value": "{data:lmaitfy.value}", "status": "{data:lmaitfy.status}", "weight": 1, "priority": 4 },
        { "type": "metric", "label": "Homelab monitors", "value": "{data:monitors.summary}", "status": "{data:monitors.status}", "weight": 1 }
      ] },
      { "type": "tile", "children": [
        { "type": "text", "role": "caption", "text": "Unhealthy monitors" },
        { "type": "row", "children": [
          { "type": "text", "text": "{data:unhealthy.text}", "status": "{data:unhealthy.status}", "weight": 1 },
          { "type": "text", "text": "{data:monitors.upCount}", "align": "end" }
        ] }
      ] }
    ]
  }
}
```
Data file `.configlauncher/widgets-data/w-anigeek.json` (Claw writes it):
```json
{
  "updatedAt": "2026-11-27T19:41:00-07:00",
  "blog": { "title": "Why the launcher reads a file", "excerpt": "A short story about config, agents and phones that stay…", "url": "https://blog.anigeek.com/launcher-file" },
  "picspin": { "value": "up · 173 ms", "status": "ok" },
  "lmaitfy": { "value": "up · 961 ms", "status": "ok" },
  "monitors": { "summary": "123/123 up", "upCount": "123 up", "status": "ok" },
  "unhealthy": { "text": "All healthy", "status": "ok" }
}
```
M2 variant: the three monitors come from an unauthenticated JSON endpoint. Add `{ "kind": "net.fetch", "origins": ["https://status.anigeek.com"] }`, `"sources": [{ "name": "mon", "url": "https://status.anigeek.com/summary.json", "every": "PT5M" }]`, set `requires` to the M2 level, and swap `{data:picspin.value}` for `{fetch:mon.picspin.value}`. The blog tap stays a `source` tap (Q-D5).

### 8.2 Example 2: the KWGT "Brain full" widget, backed by Home Assistant, M2

Screenshot → schema: teal rounded card (`surface.color`), the lobster picture behind (`background.image` + `scrim`), `up 9h 3m` in monospace at top right (a `text` with `font: "mono"`, `align: "end"`, formatter `since`), three labelled **segmented** bars with percentages (`progress` with `segments: 10`, `max: 100`), the `ctx` fill turning yellow-orange near the end (`by: "position"` with a `warn` threshold at 75), and `status: idle` in monospace at bottom right. There is no title row (`header: false`).

HA side (Claw does this in HA, once): one template sensor `sensor.claw_status` with state `idle` and attributes `ctx`, `five_hour`, `seven_day`, `started_at`. If the numbers are separate entities instead, list one source per entity (up to 6).

```json
{
  "id": "w-claw", "type": "card", "requires": 7,
  "page": "home", "x": 0, "y": 8, "w": 4, "h": 3,
  "title": "Claw", "author": "Claw",
  "header": false,
  "surface": { "color": "#0F6E6E" },
  "background": { "image": "lobster.webp", "fit": "cover", "scrim": 35 },
  "summary": "Claw, {fetch:claw.state}. Context {fetch:claw.attributes.ctx|round} percent",
  "capabilities": [
    { "kind": "data.file" },
    { "kind": "ui.reload" },
    { "kind": "net.fetch", "origins": ["https://ha.anigeek.com"], "secrets": ["ha-token"] }
  ],
  "data": {
    "staleAfter": "PT15M",
    "asOf": "last_updated",
    "sources": [
      { "name": "claw", "url": "https://ha.anigeek.com/api/states/sensor.claw_status", "every": "PT5M", "auth": { "bearer": "ha-token" } }
    ]
  },
  "layouts": {
    "4x3": [
      { "type": "text", "role": "caption", "font": "mono", "align": "end", "text": "up {fetch:claw.attributes.started_at|since}" },
      { "type": "progress", "label": "ctx", "value": "{fetch:claw.attributes.ctx}", "max": 100, "segments": 10, "by": "position",
        "thresholds": [ { "from": 0, "tone": "accent" }, { "from": 75, "tone": "warn" } ] },
      { "type": "progress", "label": "5h", "value": "{fetch:claw.attributes.five_hour}", "max": 100, "segments": 10, "by": "position",
        "thresholds": [ { "from": 0, "tone": "accent" }, { "from": 75, "tone": "warn" } ] },
      { "type": "progress", "label": "7d", "value": "{fetch:claw.attributes.seven_day}", "max": 100, "segments": 10, "by": "position",
        "thresholds": [ { "from": 0, "tone": "accent" }, { "from": 75, "tone": "warn" } ] },
      { "type": "text", "role": "caption", "font": "mono", "align": "end", "text": "status: {fetch:claw.state|default:unknown}" }
    ]
  }
}
```
- The lobster is a file at `.configlauncher/widgets-data/w-claw/lobster.webp` (hence `data.file`). Claw writes it once.
- **M1 form of the same card:** drop `net.fetch`, `sources`, `asOf` and `secrets`. Claw writes `widgets-data/w-claw.json` with `{ "updatedAt": …, "ctx": 79, "five_hour": 31, "seven_day": 8, "since": "9h 3m", "state": "idle" }`, and the tokens become `{data:ctx}` and so on. The token never reaches the phone. `requires` is the M1 level.
- Because `{fetch:…}` is read with `by: "position"` and thresholds on `0-100`, the 79% bar fills 7 accent segments and the 8th in `warn`. 31% and 8% stay `accent`.
- First load shows the consent tile, asking for `data.file`, `ui.reload`, `net.fetch https://ha.anigeek.com` and the secret `ha-token`. Approve, paste the token once, and the card goes live.

## 9. Built-in readouts and Pragma's opinions (secondary)

Christopher's priority is §8. The first-party set is therefore **recipes: ready-made cards**, not types.
- **Clock/date**, **text/note**, **battery** (`{battery}`, `{batteryFraction}` for a `progress`, `{batteryState}`), **next alarm** (`{nextAlarm}`) and an **At a glance** strip that combines them. Parts resolve empty when uninteresting (`{batteryState}` is `Charging`, `Full`, `Low` (≤15%) or empty, the Kvaesitso rule), so an empty text isn't drawn.
- **Sources and lifecycle:** time via the existing minute ticker. Battery via the sticky `ACTION_BATTERY_CHANGED` ([BatteryManager](https://developer.android.com/reference/android/os/BatteryManager), [battery monitoring](https://developer.android.com/training/monitoring-device-state/battery-monitoring)), registered dynamically on `onStart` and unregistered on `onStop`. Next alarm via `AlarmManager.getNextAlarmClock` plus `ACTION_NEXT_ALARM_CLOCK_CHANGED` ([AlarmManager](https://developer.android.com/reference/android/app/AlarmManager)), no permission. None of these can leave the phone, because fetch URLs are literals.
- **Picker:** a `Reflow Launcher` group inserts these recipes as card elements (a normal `AddElement`, one write). There is no phone-side style editor. Look is set in the file.
- **Weather and agenda:** cards with a `source` (§5), or an `appWidget` for store users. Not built in. **Now playing:** cut (notification access, restricted-settings friction on sideloaded builds). **Calendar:** later, as a `phone.calendar` capability, only if wanted. **Analog clock:** cut for now.

**Pragma's opinions in [polish-audit.md](polish-audit.md), point by point:**

| Pragma | Ruling | Reason |
|---|---|---|
| Start with four: clock/date, battery, search bar, next-event/note text from the folder | **Adopt, narrowed.** Clock, battery, text and the At a Glance strip are recipes. "Next event" is an agent-file card | No permission, no internet |
| Search bar matters most; tapping opens the drawer with the keyboard up, the same path as `Apps` | **Adopt** as a card whose tap is `ui.openDrawer` (C10): an in-launcher action, silent, no consent, `keyboard: true` by default | One tap kind, no second search |
| Leave out now-playing | **Adopt** | Same reason as the research |
| Weather is a card fed by an agent, with the `as of` header | **Adopt, and generalize** to M2's `sources` | Same reason, and §8 is now the main work |
| Look: `surfaceContainerHigh`, 12 dp corners, 4 dp rhythm | **Override the numbers.** Use the ux §17.8 card tokens (16 dp card radius, 12 dp tile, `k` scaling). Keep: follows light/dark, white text with label shadow on wallpaper, 48 dp targets | `surfaceContainerHigh` at 12 dp is the *drag plate* (ux §4.2). A built-in card and an agent card must look the same, so there is one set of tokens |
| Sizes 1×1 to 4×2, one clear layout per size, no style editor in v1 | **Adopt** (via `layouts`, largest that fits) | |
| TalkBack: one spoken sentence per widget | **Adopt** as card `summary` (§4.1) | |
| Don't draw differently from third-party AppWidgets in edit mode | **Adopt.** Same edits (#303/#304) | |

## 10. Pragma's top polish items against this plan

- **Folder editing missing on the phone (item 1): does not block or shape this work.** Widgets live in `elements[]`, not folders, and dock and folders can't hold widgets. One practical point: folder edits will touch `Edit.kt` and `DragController.kt`, as element edits do, so the two efforts will conflict in the same files and should be sequenced, not run in parallel.
- **Work apps can't be placed or launched (item 2): shapes one rule.** A card `ui.openApp` and `button` target a **personal-profile** package only, until item 2 is fixed. A `ui.openApp` for a work package behaves like a work app on home does today. Cards that mention work apps are out of scope here, as `appWidget` work widgets were (D3). It does not block.
- **No restore or undo (item 3): shapes the recommended order.** Readouts raise the stakes, because an agent can now write a layout that loads fine but looks wrong (a covering background, a card in the way). The recovery path today is Undo for Remove plus the last-good layout. **I recommend 3(c) `Restore previous layout` (ux §8, 10 snapshots) lands before M1 is announced** (not before it is built). 3(a) and 3(b) are unrelated. Device-local approvals and secrets are **not** part of a snapshot, by design, so restoring a layout never revives an approval or leaks a secret.
- **#258 (rotate resets sheets, item 8)** shapes one detail: the secret-entry sheet must not put the typed token in saved instance state. Rotating loses the half-typed token, which is acceptable.
- **Q-T7 (dynamic colour)** is compatible: `surface: "auto"` follows the home theme, tones are fixed.

## 11. What is explicitly cut

Formulas, expressions, `$if()$`, Jinja, flows, globals, komponents, scripts of any kind; absolute layout; remote images, SVG, GIF; WebView, HTML, JS; any HA client, entity picker or WebSocket; `/api/states`, POST fetches, custom request headers (bearer only); background wakeups, jobs, alarms, services, push, real-time sockets; built-in weather fetch, provider choice and location permission; media (notification listener); calendar permission (for now); analog or other clock styles; per-widget theme galleries and a phone-side style editor; per-widget secrets (shared by name and origin instead); widget stacks and pop-ups; RSS and news; text/clock/battery as element types.

## 12. Proposed issue breakdown (build order)

Prerequisites, already filed: **#140** (S3-E card render), **#141** (S3-F templates and taps), **#142** (S3-C consent UX). **#143** (fixtures) is extended by RW-1. M1 only needs #140 and #141. Consent #142 is needed for `ui.openUrl` and M2.
Checks marked **R** are hands-on spot checks by Christopher, coordinated by Grok Bot, on **a phone (A51, A53 or S25 Ultra)** or **the onn tablet**. Grace owns every JVM and emulator check.

**M1: agent-written readouts**

**RW-1. Schema level 6: M1 keys.** `surface` object, `background`, `header`, `summary`, text `display/align/font/status`, `progress` `max/segments/thresholds/by`, `metric.thresholds`, `button.icon`, `image` node, `color`; the empty-is-not-drawn rule; codec canonical order, `validate.py`, schema, `FEATURE_LEVEL` and docs. *Acceptance:* the example in §8.1 and the M1 form of §8.2 validate in Kotlin and Python with the same messages. A card with `header: false` and `ui.openUrl` is **valid** and drawn *with* the header plus a warning (the same fallback pattern as `surface: none`). A card using any new key and `requires` below the level is an error. A v1.1 phone (level 5) skips a `requires: 6` card, keeps its cells and shows the "Update" tile. *Grace:* JVM + `scripts/test_validate.py` for each key, invalid fixtures for each cap, the skip case on the previous release APK in the emulator.

**RW-2. Renderer additions (extends #140).** Segmented progress with thresholds and `by`, monospace, `display` tier, `align`, status dot on text, hidden header with the stale `▲`, `surface` colours and scrim, TalkBack `summary`. *Acceptance:* both §8 examples render in the screen matrix points (including font scale 2.0 and the small A51-shaped width) with no clipped text. Contrast warnings appear in `status.json` for a custom colour below 4.5:1 and are measured over the scrim. *Grace:* Compose tests, screenshot matrix, TalkBack read-through. **R:** a phone (A51, A53 or S25 Ultra) for density and legibility over a real wallpaper, and the onn tablet for large cells.

**RW-3. Local pictures.** `widgets-data/<id>/` reader, caps, decode-to-size, `warn_image_bad`, backup exclusion check. *Acceptance:* a 5 MiB or 4000 px file is refused with the warning, a PNG, JPEG and WebP of valid size draw, an SVG is refused, an id's folder can't read another's file (`../`, symlinks). No file write from loading. *Grace:* JVM for path and size rules, emulator for decode and memory (`dumpsys meminfo` before and after 20 reloads).

**RW-4. Tokens, formatters, thresholds engine.** `{fetch:…}` grammar (parse only in M1), the five formatters, `default`, threshold evaluation, `data.asOf`. Pure Kotlin, mirrored in `validate.py`. *Acceptance:* table tests for each formatter at the edges (negative, null, a missing path, a bad timestamp, DST, a 12 vs 24 h locale), threshold boundaries (74/75/76), the same strings in Kotlin and Python. *Grace:* JVM only.

**RW-5. Ambient tokens and recipes (secondary).** `{battery}`, `{batteryFraction}`, `{batteryState}`, `{nextAlarm}`, registered on `onStart` and unregistered on `onStop`, the recompose-on-change rule, picker group `Reflow Launcher` (recipes as `AddElement`), `ui.openDrawer`. *Acceptance:* no receiver is registered while home is stopped, a battery sticky intent with only voltage changing doesn't recompose, one write per recipe added. *Grace:* JVM with fake sources, emulator `adb shell dumpsys battery set level 14` / `unplug`, alarm set by an instrumented helper. **R:** a phone, charging and unplugging, and the alarm shown from the real clock app.

**RW-6. Agent kit for M1.** `templates/config-folder/AGENTS.md` and `skills/reflow-launcher/SKILL.md` sections (recipes, §8 examples, where the file and pictures go, `x-` notes), fixtures in `test-fixtures/configs/valid` for both examples, and a README line on committing `widgets-data/`. *Acceptance:* a fresh agent run, told only the template, produces a valid card from a one-sentence ask (Claw's test, recorded). *Grace:* validates every snippet in the docs with `validate.py`. **This is the first moment Claw can use the feature.**

**M2: live data**

**RW-7. Secret store.** Keystore-wrapped store keyed by `(name, origin)`, backup exclusion, entry and forget flows (Pragma writes the copy), `FLAG_SECURE`, `status.json` states. *Acceptance:* no secret in any log, file or instance state. Deleting an origin deletes its secrets before a fetch. A rotation mid-entry leaves nothing saved. *Grace:* JVM for the keying and GC rules, instrumented test on the emulator for the Keystore wrap. **R:** a phone (Keystore on a Samsung device) and the onn tablet.

**RW-8. `net.fetch` runtime (stage 5).** `INTERNET`, the per-element isolated client, origin pin, same-origin redirects, private/link-local/metadata IP deny unless approved, rebinding re-check, cleartext only by name, caps. *Acceptance:* the existing dynamic-widgets §3 and §6 table, each row with a test. A request to an unapproved origin never leaves the phone. A redirect to another origin fails. The token is sent only to its origin. *Grace:* JVM with a local test server (MockWebServer), emulator tests against a LAN-style host.

**RW-9. Refresh scheduler.** §7: glance, `every` floor, jitter, backoff, connectivity callback, manual refresh, concurrency caps, stale and as-of. *Acceptance:* with a fake clock, 20 failures give the ×2/×4/×8 ladder and a manual refresh resets it. Home hidden means zero requests (counted on the test server). Airplane mode means zero attempts and no ladder growth. *Grace:* JVM with a fake clock and server, emulator with `svc wifi disable`. **R:** a phone for a battery sanity check (`dumpsys batterystats`, `dumpsys alarm`, `dumpsys jobscheduler`) after a day.

**RW-10. Consent UX for `net.fetch` and secrets (extends #142).** Prompt lines, the private-origin note, the `http://` clear-token warning, **Forget secret**. *Acceptance:* a broadened line prompts only for the new lines, and the approved secret name appears in the fingerprint. *Grace:* Compose tests. **R:** one end-to-end approval on a phone.

**RW-11 (only if Christopher needs it). TOFU pin for self-signed HTTPS** (Q-C7 stern copy, re-pin pause). Depends on question Q2 in §13.

**RW-12. QA pass on the signed release APK.** The matrix: reboot, update over the previous release (a level-5 phone skips a level-6/7 card and keeps cells), file mode and fetch mode, offline, a rotated and a folded screen, token removed, TalkBack, battery sanity. Known gaps listed in the release notes. *Grace:* the emulator profiles and API spread (29, 31, 35). **R:** spot checks by Christopher on a phone (A51, A53 or S25 Ultra) and the onn tablet.

**Order:** #140 → #141 → RW-1 → RW-2 → RW-3 → RW-4 → RW-6 *(M1 ready for Claw; RW-5 can run in parallel)* → RW-7 → RW-8 → RW-9 → RW-10 → (RW-11) → RW-12. Edits (#303/#304) already exist.

## 13. Open product questions for Christopher

(Pragma's four are in [polish-audit.md](polish-audit.md), starting with Q-T7.)

1. **Two steps: files first, then live data?** Step one: Claw writes the readout file and picture to your folder, and the card shows them (both of your examples work, nothing new asked of Android, no token on the phone). Step two: the phone fetches from Home Assistant or a web address itself (needs the Internet permission and a token typed on the phone). *Recommended: yes, files first.* Why: step one gets Claw building widgets soonest and is the low-risk part.
2. **How does your Home Assistant answer: plain `http` on your home network, `https` with a normal certificate, or `https` with a self-signed one?** *Recommended: assume the first two, and only build the extra self-signed-certificate check (RW-11) if you say self-signed.* Why: that check is the biggest piece of step two, and a token sent over plain `http` can be read on your Wi-Fi, which the prompt will say.
3. **Allow a background picture like the lobster, from a file in your folder?** The phone darkens it automatically so text stays readable, and pictures can't be loaded from the internet. *Recommended: yes.* Why: it's the look of your KWGT widget, and local-only pictures can't track you.
4. **Allow a card to hide its small title row?** Today every card shows one, so a card can't pass for a system message. Your lobster card has none. *Recommended: yes, for any card that can't open a web link. A small ▲ still appears if its data is stale.* Why: the picture reproduces only without a title row, and without links a card can't lure you anywhere. (Pragma confirms the look.)
5. **Enter the Home Assistant token once for all widgets?** You paste it once on the phone, and every widget you approve for that Home Assistant address can use it. It can't be sent to any other address. *Recommended: yes.* Why: three cards shouldn't mean three pastes, and the token still can't go anywhere else.

Defaults applied without asking, because Christopher's new scope settles them: weather and calendar come from agent files or URL sources, not built-in fetchers. No media widget. Digital clocks only. No phone-side style editor.

## 14. Follow-ups outside this PR

- Update [dynamic-widgets.md](dynamic-widgets.md) §3, §4, §8, §9 to match §3's table when the design is accepted, and ux.md §16.1 (first-party group), §16.6, §16.7.3 (`text`/`clock` rows) and §17.8/§17.9 (images, header, `display`) (Pragma owns ux.md).
- research/widgets.md §5.5 now points here.
- Native-widgets milestone (#298–#305) is unaffected.

## 15. Sources

Launcher research: Niagara help pages, Lawnchair docs and issue #6322, Kvaesitso docs, Smart Launcher blog and docs, linked in §1.1. Authoring models: Cocoon repo (read locally at `dd11dc7`), Kustom docs and forum, Home Assistant dashboard and REST API docs, HA Companion Android widget docs and forum, linked in §1.2. Android: [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager), [AlarmManager](https://developer.android.com/reference/android/app/AlarmManager), [ConnectivityManager](https://developer.android.com/reference/android/net/ConnectivityManager). Repo: `config/Elements.kt`, `config/Card.kt`, `config/Template.kt`, `consent/Consent.kt`, `widgetdata/*`, `docs/ux.md` §16–§17, `docs/design/dynamic-widgets.md`, `docs/design/native-widgets.md`, `docs/design/title-bar.md`.
