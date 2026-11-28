# Reflow readout widgets: agent-authored cards, live data, and a small built-in set

**Status:** proposal, revised 2026-11-27 with the product answers, the UX rulings, and the later review folded in as decided (§13). Author: Archimedes. **Docs only.** It creates no issues and changes no code.
**Baseline:** main `86b9ab2` (1.1.0 dev builds; feature level 5).
**Pairs with:** Pragma's [polish-audit.md](polish-audit.md) on this same branch and PR (her 17-item ranked audit and her 4 open questions).
**Worked examples are in §8:** a file-fed status card, and a file-fed usage card with a decorative sticker and tick bars, which can later be backed by Home Assistant. The examples may be revised.

**The goal:** an agent should be able to declare a readout in the JSON schema and upload it to the phone, without a special companion app. A generic clock or battery set is secondary.

## 0. Rulings in one screen

1. **Readout widgets are the evolution of dashboard cards. There is no new element type and no parallel system.** [dynamic-widgets.md](dynamic-widgets.md) already has the right shape: a `card` element, no scripts, flow layout, per-widget capabilities, device-local consent, `data.file`, `ui.reload`, `staleAfter`, `source`, and a designed (unbuilt) `net.fetch`. What changes is in §3. The `text`/`clock` element types from research §5.5 and ux §16.6 are **dropped for good**. A clock is a card.
2. **Two milestones, decided (2026-11-27): M1 ships first, then M2.** **M1: agent-written files.** The agent writes JSON (and pictures) into the folder and the card renders it. Both worked examples work in this mode, nothing new is asked of Android, and no secret ever reaches the phone. **M2: live fetching.** The phone reads a JSON URL itself (Home Assistant or any endpoint) with a named, device-local secret. M2 adds the `INTERNET` permission (already agreed, Q-C1) and the secret store.
3. **Bindings are three things and nothing more:** the agent-written file, an HTTP GET of JSON, and Home Assistant as *just a JSON URL with `auth: [{ "secret": "ha", "as": "bearer" }]`* (no HA client, no HA-specific code; this keeps Q-C5 intact). Transport: a normal https host with a public certificate is allowed whatever IP it resolves to; plain http is LAN-only, checked on the resolved address; self-signed https is optional (RW-11, §6.5). **Pictures are `layers[]` of `picture` and `content` (§4.4).** A picture's `src` may be a filename template, and its `scrim` is a property of that layer.
4. **No formula language.** Simple `{data:…}` and `{fetch:…}` bindings, a closed set of five formatters, and **thresholds** (HA gauge `severity` style) replace KWGT formulas and HA Jinja templates.
5. **Secrets never touch the layout file.** There is no `${secret:NAME}` placeholder. A source declares where each secret goes in `auth` (`as: "bearer"`, or `as: "header"` / `as: "query"` with a `name`). The value is typed on the phone, kept in Keystore-wrapped device-local storage, and bound to one origin. It is injected at request time, never stored in `url`, and redacted from every never-reach surface. Secrets are generic: HA is a recipe (`as: "bearer"` with secret `ha`). An optional one-shot `secrets.import.json` lets an agent hand values over without leaving them on shared storage (§6.6).
6. **Battery:** fetch only for cards on the current page, while home is resumed and visible, the screen is on and not covered, and the keyguard is down. One ticker per visible source at `max(every, 60 s)`, paused on `onStop`. At most 60 fetches per hour per origin, and **zero work while home isn't visible**. **No JobScheduler, WorkManager, alarms or services.** Weather needs no built-in fetcher: it is a card with a source.

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
| **Cocoon** ([repo](https://github.com/Nasawa/cocoon-android), `README.md`, `CHANGELOG.md`) | **There is no widget schema to adapt.** Cocoon is an SSH client for [Outer Shell](https://github.com/outergroup/outershell): it opens apps the host serves as **web apps in a WebView** over SSH, one WebView profile per app. As I read the repo, the agent's card (example 1) was such a web app: its HTML and JS are served from the Linux host | Its security posture: non-exportable Keystore key, TOFU host pinning, per-app cookie isolation, cleartext only where explicitly allowed, no backups of secrets. Those map onto our secrets and `net.fetch` rules | The web-app model itself. A readout needs an SSH client, a host daemon and a WebView with arbitrary JS. That is the iappyx anti-example ([dynamic-widgets.md §0](dynamic-widgets.md)). The declarative card replaces it |
| **KWGT / Kustom** ([formulas](https://docs.kustom.rocks/docs/reference/functions/if/), [globals](https://docs.kustom.rocks/docs/reference/globals/), [komponents](https://docs.kustom.rocks/tags/komponent/), [flows](https://docs.kustom.rocks/tags/flows/), [threshold colours](https://forum.kustom.rocks/t/is-it-possible-to-set-a-multicolor-progress-bar-to-different-colors-based-on-battery/1535)) | Absolute-layout modules; any property can be a formula (`$if(bi(level)>90, …)$`); globals; komponents (reusable sub-widgets); flows (WebGet, set a global). Progress bars take a formula level and a section count | **Segmented progress with a formula-driven colour** (as thresholds), a section count, **globals' idea** only as "the agent regenerates the JSON" | Formulas, flows, komponents and globals. They are a scripting language. The agent writes JSON, and configs are cheap. Absolute layout (it broke on a tablet) |
| **HA dashboards** ([gauge](https://www.home-assistant.io/dashboards/gauge/), [tile](https://www.home-assistant.io/dashboards/tile), [glance](https://www.home-assistant.io/dashboards/glance/), [entities](https://www.home-assistant.io/dashboards/entities/)) | Declarative YAML cards. `gauge` has `severity` (`green/yellow/red` from a value) and `segments` (`from`, `color`). `tile` has `state_content` and `time_format` (`relative`, `time`, …). `glance` is a row of entities | **Threshold lists** (`from` + colour) both for the whole fill and per segment. **`time_format`** → our formatters. glance → a row of metrics. entities → `list` | `tap_action`/`hold_action` families, themes per card, features, entity pickers |
| **HA companion widgets** ([docs](https://companion.home-assistant.io/docs/integrations/android-widgets/), [forum](https://community.home-assistant.io/t/android-widget-entity-state-refresh-rate/280553)) | Entity State and Template (Jinja) widgets, as AppWidgets. Update every ~30 min with the screen on. Real-time needs *notification access* and costs battery. "It updates when you turn on your screen" | **Refresh on glance:** update when home becomes visible and data is stale, plus a slow timer while visible. The maintainers' own reasoning is that people glance, not watch | Real-time sockets and the notification-access trick (no background wakeups), Jinja |
| **HA REST API** ([docs](https://developers.home-assistant.io/docs/api/rest/)) | `GET /api/states/<entity_id>` returns `{state, attributes, last_changed, last_updated}`. Auth is `Authorization: Bearer <long-lived token>`. `GET /api/states` returns everything the user may read | One entity = one GET of small JSON. The existing `{data:path}` subset reads `state` and `attributes.x` as is. `last_updated` can drive "as of" | `/api/states` (huge), `POST /api/template` (a POST), the WebSocket API |

### 1.3 Takeaways

1. **Composition beats widget types.** Niagara's clock, Lawnchair's At a Glance and Kvaesitso's clock components are one strip with toggled parts, not separate widgets. A template of text and metrics expresses all of them, and the agent can write that.
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
| Local data file reader (`widgetdata/WidgetData.kt`, `WidgetDataReader.kt`: derived path, 256 KiB, `updatedAt`, `staleAfter`, polled with `launcher.json`) | Yes (#139) | Extend with **images** (local files only, still in the folder). The **fetch snapshot** is not an extension of this reader: it lives in app-private `filesDir/cards/`, never in the synced folder (§6) |
| Card **render**, templates and taps (#140, #141); consent UX (#142); fixtures (#143) | **No.** Release builds draw `CardPlaceholder` (#257); debug has the consent tile | **Prerequisites** (§12) |
| Edits: Remove+Undo, `Move to…`, `Resize…`, drag-resize handles for any grid element (`model/ElementEdits.kt`, `ui/ResizeHandles.kt`, #303/#304) | Yes | Reuse: readouts get them for free |
| Title-bar template tokenizer (`config/Template.kt`: `{time[:p]}`, `{date[:p]}`, allow-listed patterns, no seconds) | Yes | Reuse for the card token grammar |
| Minute ticker for time tokens (title bar), foreground only | Yes | Reuse |
| Network: `INTERNET` | **Not in the manifest** (ARCHITECTURE §1) | M2 only (Q-C1 agreed). RW-8 also adds a `network-security-config` that permits cleartext, guarded by an app-level LAN allowlist (§6.5) |
| Secret store | No | M2 (§6). Add `secrets/` and `cards/` to both exclusions in `data_extraction_rules.xml` |
| `status.json` per-widget state; `apps.json` for agent discovery | Yes (`consent`, `appWidgets`) | Extend with new **display** keys and warning sentences (`warn_secret_missing`, `warn_secret_rejected`, `warn_image_bad`, the header/surface fallback warnings). Never a secret value, an `Authorization` header, or a fetch body |

## 3. The decision, and what changes in the cards design

**Chosen: cards, evolved.** The alternative was new element types per readout (`clock`, `battery`, `weather`, …). That would mean a schema, validator, renderer, picker entry and TalkBack story *per type*, a second vocabulary next to card nodes, and no way to combine readings into one strip. The agent already writes cards, and the worked examples are *cards with data*, not clocks. Delta against [dynamic-widgets.md](dynamic-widgets.md), in the order it matters:

| # | What changes | Was | Becomes | Why |
|---|---|---|---|---|
| C1 | **`net.fetch` is pulled forward** as `data.sources[]` | Stage 5, "later, advanced" (Q-C2, Q-C5) | M2, right after the file milestone | Live Home Assistant and JSON URLs are in scope. `INTERNET` was already agreed (Q-C1). Q-C2's "local first" ordering is kept: M1 is local-only |
| C2 | **HA is a recipe, not a feature** | §8: HA "must not be tightly coupled" | Unchanged in spirit: HA is a JSON URL plus `auth: [{ "secret": "ha", "as": "bearer" }]`. No HA client, no entity picker. The agent kit recommends a **non-admin HA user** for the phone's long-lived token (§9 / RW-6) | Q-C5 holds. The launcher knows HTTP, not Home Assistant |
| C3 | **Generic named secrets, declared in `auth`, bound to `(name, origin)`, shared across widgets** | §3: scoped to `(elementId, origin)`; agents "declare that a secret slot exists" | The `net.fetch` line carries `secrets: ["ha"]`. A source lists `auth` entries that say where each secret goes (`bearer`, a named header, or a named query param). No placeholder syntax. One typed value per name and origin, usable by any approved widget for that origin only. Optional one-shot `secrets.import.json` (§6.6). A secret is kept after its last widget leaves the file | Three cards, one token, one paste. This is the idea of variables injected into URLs and headers, without putting the value in the file. Not HA-specific (§5, §6) |
| C4 | **New token namespace** `{fetch:<source>.<path>}`, **`data.asOf`** | Only `{data:path}` | `{data:…}` = the agent file. `{fetch:…}` = a fetched source. `data.asOf` names the path of a timestamp for "as of", else the fetch time | No key collisions between file and sources. HA gives `last_updated` |
| C5 | **Closed formatters** (5), **thresholds**, segmented progress | None (`progress` took a 0–1 value) | §4.3 | HA states are raw (`"79"`, a boot timestamp). Without formatting, every HA binding needs the agent in the middle, which defeats M2 |
| C6 | **`layers[]` of `picture` and `content`; pictures are local files only** | ux §17.8 rule 6: "no background images in v1" | A card gets ordered `layers` (`picture`, `content`; at most 4). A picture's `src` is a file name or a filename template such as `sticker-{data:state}.webp`, with an optional `default` file. Per-layer: `fit` (including `fixed` + `width`), `align`, `offset`, `opacity`, `overflow` (up to 25% past an edge, under neighbours). `scrim` is `"auto"` (default), `"none"`, or `{color, opacity}` (§4.4) | Example 2 needs a sticker that peeks out of the card and swaps with the agent's state. A scrim on the picture restores the guarantee rule 6 protected, and `"none"` is how the sticker opts out. Remote pictures stay cut |
| C7 | **`header: false` under a testable rule** (§4.5) | ux §17.9: "The header can't be turned off" | **Decided (Pragma confirmed 2026-11-27; confirmed under the file-only rule).** Allowed only when the card has no `ui.openUrl` and no source needing consent. A headerless card always draws its card surface, never `surface: "none"`. A 12 dp `▲` appears in the top-end corner when stale. Pending consent forces the header on silently. `ui.openApp` and `ui.reload` buttons are fine | Example 2's file-only form has no title row. A card that fetches (M2) needs consent, so it always shows the header. Amends ux §17.9 (sentence in §4.5, applied to ux.md in this PR) |
| C8 | **`ui.reload` also refetches sources** | Re-reads the data file; the the agent POC drew a refresh affordance | One tap = re-read the file and refetch all sources (rate-limited). **When the header is shown,** a circular refresh control sits in it (example 1). **When the card is headerless,** there is no header control: put a `button` with `tap: { "kind": "ui.reload" }` in the layout if you want a visible refresh, and the long-press sheet offers `Reload` whenever the card declares `ui.reload` | `ui.reload` buttons are fine on a headerless card (Pragma). Example 1 keeps the header control |
| C9 | **Card keys added at the level of M1 and M2** | Level 4 | Symbolic levels, **not reserved numbers.** M1 keys take **`FL_CARDS_READOUT`**, the next free level when RW-1 merges. `net.fetch`, `sources` and `secrets` take **`FL_FETCH`**, assigned when RW-8 merges, with **`FL_FETCH` > `FL_CARDS_READOUT`**. `windowOverrides` takes **`FL_WINDOW_OVERRIDES`**, the next free number when *it* merges. Levels are fixed in merge order (native-widgets.md §2.1); reserved numbers are proposals. If M1 lands first, windowOverrides takes the next free number after `FL_CARDS_READOUT` (and after `FL_FETCH` if that has also merged), and the other way around if windowOverrides lands first. There is no reserved 6 or 7 | Old phones skip a card whose `requires` is above their `FEATURE_LEVEL` (cells kept) instead of rejecting the file for an unknown key (B2). Root keys like `windowOverrides` still B2-reject on older apps, as layout-overrides.md §8 already says |
| C10 | **`ui.openDrawer` tap kind** (Pragma's search bar) | None | An in-launcher action, silent, needs no consent | §9 |
| C11 | **Transport: https unrestricted by IP, LAN http, optional self-signed** | dynamic-widgets §3: cleartext and private addresses by named approval; TOFU pin as a stretch (Q-C7) | The private-network check applies **only to plain http, never to https**. A normal https host with a publicly trusted certificate is allowed whatever IP it resolves to, including RFC1918 and CGNAT `100.64/10` (this is an RW-8 acceptance case). Plain http is checked on the **resolved address** at connect time (private, link-local, loopback, CGNAT `100.64/10`). A secret over http needs a one-time device-local acknowledgement. Self-signed https is pinned by SHA-256 after the user confirms the fingerprint (**RW-11, optional**, does not block M2) | A a publicly trusted name that only resolves at home or only on a private network must work. No warning may say "private IP denied" for https. §6.5 |
| C12 | **Data-chosen links in `ui.openUrl` are allowed** | dynamic-widgets already allowed `{data:…}` in a tap `url` (Q-D5, Outer Shell's "latest post") | **Decided, confirmed.** `{data:blog.url}` (and `{fetch:…}`) is allowed in `ui.openUrl`. The existing Q-D5 rules apply to the **resolved URL at tap time**: silent only when the host matches the card's `source`; any other host confirms every tap, with the device-local `Always allow <host>` checkbox. A file-only card with no `source` confirms every templated link unless the user has ticked Always allow. A link template can't reference a secret. Links are not limited to the `source` origin: file-only cards have no `source`, and the per-tap confirm already covers other hosts (§5.1) | Example 1's blog tile is this |

**Not changed:** consent tiers, the approval ceiling, `author`, never-offered kinds, `widgets-data/` path rules for the **agent file and pictures** (the fetch snapshot is not stored there), device-local approvals, the 256 KiB cap on the agent file. **`status.json` is extended**, not replaced: it gains display states and warning sentences (`warn_secret_missing`, `warn_secret_rejected`, `warn_image_bad`, the header/surface fallback warnings). It still never holds a secret value, an `Authorization` header, or a fetch body.

## 4. Schema delta (all on `schemaVersion` 1)

### 4.1 M1 keys (feature level `FL_CARDS_READOUT`)

| Where | Key | Meaning |
|---|---|---|
| card | `surface` | `"auto"` (today), `"none"`, or `{ "color": <colour>, "opacity": 50-100 }`. Colours use the title-bar grammar: a token (`light`, `dark`, `onWallpaper`, `primary`, `secondary`, `onSurface`, `surface`), `#RRGGBB`, or `{light, dark}`. Contrast is measured and warned, never an error (reuses `TitleBar` contrast code) |
| card | `layers` | Optional ordered array, back to front, at most 4. Kinds `picture` and `content`. A picture has `src`, optional `default`, `fit` (`cover`\|`contain`\|`fixed`), `width` (with `fixed`), `align`, `offset`, `opacity`, `overflow`, and `scrim` (`"auto"`\|`"none"`\|`{color, opacity}`). §4.4 |
| card | `header` | `false` hides the title row when the §4.5 rule allows it. Default `true`. Decided; see §4.5 |
| card | `summary` | Optional template (≤120 chars) that TalkBack reads as **one sentence**. Default: the nodes in order. Adopts Pragma's "one sentence per widget" |
| `text` | `role: "display"`, `align`, `font`, `status` | `display` is the large tier (sizes for Pragma, §10). `align` is `start`\|`center`\|`end`. `font` is `default`\|`mono` (the system monospace, nothing else). `status` puts a dot before the text. ux §17.9 already says every primitive takes `status`, and `Card.kt` only allows it on `metric`, so this aligns code with ux |
| `progress` | `max`, `segments`, `style`, `count`, `thresholds`, `by` | `max` (default 1) lets `value` be `79` with `max: 100`. `segments` is 2–20 (the cap is 20). `style: "ticks"` draws thin ticks with optional `count` 10–120 (§4.2). `thresholds` and `by`: §4.2 |
| `metric` | `thresholds` | Colours the status dot from the numeric `value` |
| `button` | `icon` | Material symbol name. ux §17.9 already has it, and `Card.kt` doesn't |
| ~~`image`~~ | **cut** | The inline `image` node is dropped. A `picture` layer with `align` and `fit` covers backdrops and badges, and one picture mechanism is less to validate |
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
- **`style: "ticks"`.** Thin ticks instead of a solid or segmented fill. Optional `count` from 10 to 120; the default is **auto-fit**, roughly **1 dp per tick and 1 dp per gap**, then clamped into 10–120. A `count` above 120 is clamped to 120 and warns: `elements[N].layouts…count: 200 was lowered to 120, the most a ticks bar can have.` (A `count` below 10 is raised to 10 with the same shape of warning, my call.) `segments` (cap 20) applies only to the default segmented style, not to ticks. `thresholds` and `by` still colour the ticks: with `by: "position"`, a tick at or above the threshold takes the new tone (the same 74/75/76 boundary as RW-4: with `from: 75`, 74 stays `accent`, 75 and 76 take `warn`). That is how the ctx bar turns yellow-orange near the end.

### 4.3 Bindings, formatters, tokens

- `{data:path}` and `{fetch:source.path}` use the existing strict path subset (dot keys and `[index]`).
- **Missing path vs empty string** (one rule, one table-test row):

| Binding result | Drawn as |
|---|---|
| Path missing or value `null` | `—`. If the token has `default:text`, the default text instead |
| Value is the empty string `""` | Not drawn (the empty-is-not-drawn rule in §4.1). `—` is a visible placeholder, so a missing path *is* drawn |

- **Formatters, a closed set of five**, written as `{fetch:usage.state|round:1}`. One or two per token, never user-defined, never given a variable. They apply **left to right**. `default` is special: if the path is missing or null, the token becomes the default text and later formatters are skipped; if the path is present, `default` is a no-op.

| Formatter | Input → output |
|---|---|
| `round[:n]` | `79.43` → `79` when `n` is omitted (**default `n` is 0**); `round:1` → `79.4` |
| `duration` | seconds → `9h 3m` |
| `since` | ISO-8601 timestamp → elapsed `9h 3m` (the HA `sensor.uptime` / boot-time case) |
| `time[:pattern]` | ISO-8601 timestamp → local time or date, with the title-bar pattern allow-list |
| `default:text` | the text when the path is missing or null |

These cover `state` strings, boot timestamps and `last_updated` without a language. `since` and `time` are the first to cut if cost bites. Existing `{relative}` (`5 min ago`) stays.
- **Ambient tokens**, no consent and no permission, because they read no private data and URLs are literals so they can't leave the phone (§9). This is a product ruling: ux.md §17.1 today lists battery and next-alarm under Phone data that prompts; when Pragma updates that section, these tokens move out of the prompt list (§14). A card that uses only these tokens plus Show-tier kinds (`data.file`, `ui.reload`, `ui.openDrawer`) goes live with no prompt.

| Token | Value |
|---|---|
| `{time[:p]}`, `{date[:p]}` | Existing title-bar grammar |
| `{battery}` | Integer 0–100 |
| `{batteryFraction}` | 0–1, for `progress` |
| `{batteryState}` | `Charging`, `Full`, `Low` (≤15 %), or empty (not drawn) |
| `{nextAlarm[:p]}` | Next alarm as a time; bare `{nextAlarm}` uses the locale medium time, same as `{time}`. Empty (not drawn) when none is set |

- Unknown token, formatter or key in a card at the right level → B2 with a plain sentence, as in the title bar. A card that needs a higher `requires` is skipped before this check.

### 4.4 Layers and local pictures (C6)

A card may have `layers[]`, ordered **back to front**. The kinds are **`picture` and `content` only**. The card's normal content (title row and readout rows) is always exactly one layer, of kind `content`, at the position the author gives it. If no `content` layer is listed it is **implicit at the top**. A card with no `layers` draws as it does today. There is **no `scrim` layer kind**; the scrim is a property on the picture.

| Kind | Keys | Meaning |
|---|---|---|
| `content` | none | The title row and the layout. At most one per card (a second is a validation error) |
| `picture` | `src`, `default`, `fit`, `width`, `align`, `offset`, `opacity`, `overflow`, `scrim` | A local picture. Keys below |

**Per-layer controls** on a picture:

- **`fit`:** `cover` (default), `contain`, or `fixed`. With `fixed`, **`width`** is a fraction of the card width, from **0.1 to 1.5**, and the aspect ratio comes from the image. `width` is ignored (with a warning) on `cover` or `contain`. If `fit` is `fixed` and `width` is omitted, it defaults to `1` (my call).
- **`align`:** one of nine anchors, `topStart` `top` `topEnd` `start` `center` `end` `bottomStart` `bottom` `bottomEnd` (start/end mirror in RTL), default `center`.
- **`offset`:** `{ "x": <frac>, "y": <frac> }`, each from **−0.25 to 0.25** of the card size. Default `{0, 0}`. **+x** is toward the end (mirrors in RTL); **+y** is toward the bottom of the card (my call: a positive `y` with `align: "bottom"` is how a sticker peeks out below). Values outside the range are clamped, with a warning.
- **`opacity`:** 0 to 1, default 1.
- **`overflow`:** if `true`, the picture may draw up to **25% of the card's size past any edge**. The part outside the card is **decorative and does not take taps**. It is **clipped to the page bounds** and **never enters the system-bar insets**. It draws **beneath neighbouring cards and elements**, so it never covers their content or chrome. Taps on that outside area fall through to whatever is under the decoration (a neighbour, or the home). Without `overflow`, the layer is **clipped to the card's rounded shape**. Overflow past 25% is clipped to 25%, even if `width` and `offset` would go further.
- No free x/y/z beyond `align` + `offset`, no size beyond `fit` / `width`, no animation.

`src` is a **file name** or a **filename template**. `default` is an optional literal file name on the layer, used when the resolved `src` is invalid or missing:

```json
"src": "sticker.webp"
"src": "sticker-{data:state}.webp", "default": "sticker-idle.webp"
```
- A template may contain `{data:…}` or `{fetch:…}` tokens (the same grammar as §4.3). After substitution, the name **must** match `[A-Za-z0-9._-]+` with an allowed extension (`.png`, `.jpg`, `.jpeg`, `.webp`). It must not contain `/` or `..`, and must not start with `.`. The resolved name is looked up **only inside the card's pictures folder**. A name that fails this check is invalid.
- **`default`** is a literal file name, not a template, and it must pass the same check.
- If the resolved name is invalid or the file is missing, use `default`. If that is also missing (or there is no `default`), **skip that layer silently at runtime** and keep the other layers. (No runtime warning: "no picture for this state" is normal.)
- **Files** live in `.configlauncher/widgets-data/<elementId>/<name>`, the card's own folder. Like the JSON file, the folder is **derived from the element id** by the launcher. A card can't name a path, only a file inside its own folder.
- **No SVG**, no animated formats, **no web addresses**: a remote `src` is a validation error (`elements[N].layers[I].src must be a file name in the card's pictures folder, not a web address.`).
- **Caps:** 1 MiB per file, 2048 px on the longest side, `src` and `default` each at most 80 characters (my call), at most **8 distinct picture files read per card** across its layers. The phone decodes only the picture that is showing, down to the size it will be drawn, off the main thread, with a total decoded-bitmap budget of 4 MiB per card.
- **Layer cap:** at most **4 layers per card, counting `content`**. A scrim is a property, not a layer, so it does not count. More is a load warning and the extras (the last ones) are skipped: `elements[N].layers: has 6 layers, so the last 2 were skipped. A card can have at most 4.`

**Scrim, optional per layer.** `scrim` takes **`"auto"`** (the default, including when the key is omitted), **`"none"`**, or **`{ "color": <colour or token>, "opacity": 0-1 }`**.

- **`"auto"`** keeps Pragma's rule, **only when the picture sits directly under content**: the launcher applies the default scrim and warns **once per card, at load**, naming the picture's real layer index. If the picture is not directly under content, `"auto"` draws no scrim and does not warn.
- **`"none"`** is an explicit choice: no scrim, and **no warning**, even when the picture sits directly under content. Text-contrast checks (and Pragma's readability rule) are then measured against the **card surface colour**, not the picture. That is the author's choice.
- A `{color, opacity}` object is an author-written scrim; no warning.

Warning text, exactly (the real index in place of `I`):

`elements[N].layers[I]: a picture is right under content, so a default scrim was added to keep text readable. Set this layer's scrim to choose it, or "none" to turn it off.`

The default scrim is a **full-card fill, never a gradient**: the `surface` colour at **60%** in the light theme, **black at 45%** in the dark theme. Contrast is computed against the **scrim colour over the surface** when a scrim is present, and against the **card surface colour** when `scrim` is `"none"` or no scrim is drawn. It is never measured against the picture; artwork inside the picture is not a substitute for the stale ▲ or the TalkBack title.

**While a picture is loading, or after it fails, draw the card surface** behind the content. Content is never drawn on a blank background. A decode failure **keeps the last good bitmap** if there is one (§4.6); only the first failure, or a failure with no last-good, has nothing to show, and then the surface is what you see.

**Load warnings, for what can be checked statically** (listing the card's folder; each is in `status.json`):
- A **fixed** `src` (no tokens) is missing: `elements[N].layers[I].src: "sticker.webp" isn't in this card's pictures folder, so the layer was skipped.`
- A `default` file is missing: `elements[N].layers[I].default: "sticker-idle.webp" isn't in this card's pictures folder, so the default picture was skipped.`
- A file can't be decoded or is too big (`warn_image_bad`), when there is no last-good bitmap to keep: `elements[N].layers[I]: "sticker.webp" can't be shown (too big, or not a PNG, JPEG or WebP), so the layer was skipped.`
- A template `src` is not warned at load for values that don't exist yet. At runtime, an invalid or missing resolved name (including a missing `{data:…}` or `{fetch:…}` path, a `null` value, or a name that fails the file-name check) falls back to `default` or is skipped silently, as above. A missing token in `src` is an invalid name, not a load error.

**Chrome stays on top (decided, kept).** The title row and the stale ▲ are launcher chrome. They are drawn above **every** layer, wherever `content` sits, so no layer can cover them (the §4.5 anti-spoof guarantee). A `picture` layer **above `content`** is shown at **60% opacity at most**, with the warning `elements[N].layers[I]: a layer above content is shown at 60% at most, so text stays readable.` Overflow from a picture still draws **beneath neighbouring cards**, never over their chrome.

Loading never writes to the config folder.

### 4.5 Hidden header (`header: false`)

**Decided.** Pragma confirmed the rule on 2026-11-27, and it was confirmed under the file-only rule (Q4). No pending marker remains.

This amends ux.md §17.9, and the sentence is applied there in this PR:

> **The header can't be turned off,** except with `header: false` on a card that opens no links and needs no consent (see below).

**When `header: false` is honoured.** Only when the card has no `ui.openUrl` and no source needing consent (`net.fetch` is the one that does; Show-tier `data.file` and `ui.reload`, and silent `ui.openApp` / `ui.openDrawer`, do not). `ui.openApp` and `ui.reload` buttons are fine on a headerless card.

**Surface.** Never combined with `surface: "none"`. A headerless card always draws its card surface. If both are set, keep the surface, drop the header, and warn: `elements[N].surface: "none" can't be used with header: false, so the default card surface was used.`

**When it is ignored.** When `header: false` is ignored because of a link or a consent source, show the header and warn: `elements[N].header: false is ignored because this card opens links or needs your OK, so the header is shown.` A pending consent turns the header back on silently, with no warning.

**Stale marker.** A headerless card still shows a 12 dp ▲ in the top-end corner, mirrored in RTL, on the card surface, with no text. TalkBack reads it after the title as `stale, as of 08:38`, or just `stale` when there's no updatedAt. TalkBack always reads the title (the card's `title`, or its element id when there's no title), even when the title row is hidden. The long-press sheet title is the card's title, or its element id when there's no title, with `as of 08:38` as a caption under it.

Each warning string above is an acceptance test (RW-1 and RW-2). Custom `surface.color` and `layers` are allowed on a headerless card. The card surface sits under the layers, so it shows while a picture loads or fails (§4.4), and the stale ▲ is chrome drawn above every layer.

### 4.6 Atomic reads of agent files and pictures

The agent writes with **temp-then-rename** (same directory, then `rename` over the target). The reader must not pick up a half-written file.

- **Ignore** any name that starts with `.` or that ends in `.tmp` or `.part`, in both the card's data file area and its pictures folder.
- **Data file** (`widgets-data/<id>.json`): if the file is unparseable, empty, or mid-sync, **skip it**. Keep the **last good snapshot** and its **as-of time**, and warn **once per file** until it parses again: `elements[N]: the data file for widget "agent" couldn't be read, so the last good values are shown (as of 21:06).` If there is no last-good yet: `elements[N]: the data file for widget "agent" couldn't be read, and there are no last good values yet.` The last-good snapshot lives in app-private **`filesDir/cards/`**, never in the synced folder (the same place as a fetch snapshot). A mid-write or unparseable data file is **never repaired into the synced folder**; the reader only skips it and keeps last-good.
- **Pictures:** a decode failure **keeps the last good bitmap** (in memory, and the same private snapshot area if it must survive a process death). The card does not flash empty and does not fall back to a blank layer.
- The agent kit (RW-6) tells the agent to write this way: write `w-usage.json.tmp` (or `.w-usage.json.tmp`) in the same folder, then rename it to `w-usage.json`. Same for pictures.

## 5. Data bindings

| Mode | Declared as | Needs | Secret on phone | Freshness |
|---|---|---|---|---|
| **A. Agent file** (M1) | `capabilities: [{kind: data.file}]`, file `widgets-data/<id>.json` | nothing new | none | the agent's schedule, shown by `as of` and `stale` |
| **B. JSON URL** (M2) | `net.fetch` with `origins`, plus `data.sources[]` | `INTERNET`, approval of the origin | none (public endpoint) | the card's `every`, §7 |
| **C. HA entity** (M2) | Same as B: the URL is `<ha>/api/states/<entity_id>` and `auth` is `[{ "secret": "ha", "as": "bearer" }]` | as B, plus the token typed once (or imported, §6.6) | yes, one per HA origin | as B |

```json
"data": {
  "staleAfter": "PT15M",
  "asOf": "last_updated",
  "sources": [
    { "name": "usage",
      "url": "https://ha.example.com/api/states/sensor.example_status",
      "every": "PT5M",
      "auth": [ { "secret": "ha", "as": "bearer" } ] },
    { "name": "wx",
      "url": "https://api.example.com/v1/current?units=metric",
      "every": "PT15M",
      "auth": [ { "secret": "wx-key", "as": "query", "name": "appid" } ] }
  ]
}
```
- `sources`: 1-6 per card. `name` is `^[a-z][a-z0-9]{0,15}$`. `url` is a **literal** under one of the card's approved `origins` (the validator checks this offline, after canonicalization). It can't be built from `{data:…}` or `{fetch:…}` (existing rule: data can't steer a **fetch**). A `url` may have a query string; those names are literal.
- **Origin canonicalization** (capability `origins[]`, a source URL's origin, and the secret-store key): lowercase scheme and host, IDN to punycode, default port made explicit (`https` → `:443`, `http` → `:80`), no path, no userinfo, no trailing slash. So `https://ha.example`, `https://ha.example:443/` and `https://HA.EXAMPLE` are the same origin. A path or `userinfo` on `origins[]` is a validation error. A source `url` may have a path (that is the request path); only its origin is canonicalized for the pin and the secret key.
- **`auth`** (optional array, at most 4, my call): each entry names a secret and **where it is injected at request time**. This is the idea of variables injected into URLs and headers, without a placeholder in the file and without storing the value in `url`.

  | `as` | Shape | What is sent |
  |---|---|---|
  | `bearer` | `{ "secret": "ha", "as": "bearer" }` | `Authorization: Bearer <value>` |
  | `header` | `{ "secret": "k", "as": "header", "name": "X-Api-Key" }` | that header, value = the secret |
  | `query` | `{ "secret": "k", "as": "query", "name": "appid" }` | that query parameter, added at request time |

- Every `secret` name is `^[a-z][a-z0-9-]{0,31}$` and must also be listed in the `net.fetch` capability's `secrets`. `name` is required for `header` and `query` (and forbidden on `bearer`). Header names match `^[A-Za-z][A-Za-z0-9-]{0,39}$` and are compared **case-insensitively**. Query names match `^[A-Za-z][A-Za-z0-9_-]{0,39}$` (my call).
- A query parameter is **added only when declared**. It is percent-encoded, **redacted from logs, `status.json`, and every surface on the §6 never-reach list**, and **never stored in `url`**. A request URL is only ever logged as origin plus path (no query).
- **Load errors** that name the field:
  - the declared query name already appears in the `url`'s own query: `elements[N].data.sources[0].auth[1]: query name "appid" is already in the url, so the source wasn't used.`
  - the header name, compared case-insensitively, is a reserved or hop-by-hop name — `Host`, `Content-Length`, `Cookie`, `Cookie2`, `Origin`, `Connection`, `Keep-Alive`, any `Proxy-*`, `TE`, `Trailer`, `Transfer-Encoding`, `Upgrade`, or `Referer`: `elements[N].data.sources[0].auth[0]: header name "Cookie" isn't allowed.` (`host`, `COOKIE`, and `Proxy-Authorization` are the same error). `Authorization` is **not** on this list, because `as: "bearer"` sends it.
  - the secret isn't in the capability: `elements[N].data.sources[0].auth[0]: secret "ha" isn't listed in the net.fetch capability's secrets.`
  - a leftover `${secret:…}` or `{data:…}` / `{fetch:…}` in the `url` (or any attempt to put a secret in the `url` string): `elements[N].data.sources[0].url: the url must be a fixed address. Secrets go in auth, not in the url.`
- GET only (HEAD later). No request body, no cookies. **`Referer` is never sent.** HTTPS, or plain http to a LAN address (§6.5).
- Response: UTF-8 JSON, object or array root, at most 128 KiB, 10 s timeout. **Redirects:** follow at most **3** hops (§6.5). The transport check is re-run on every hop. An `https`→`http` hop is always refused. Header and query secrets are **re-attached only on a same-origin hop** and are **dropped on any other hop**. Anything else is a failed fetch.
- **One entity per source.** If the agent wants one GET for several numbers, it makes an HA template sensor with attributes and reads `attributes.x`. This is the recommended shape (example 2). The launcher never calls `/api/states` (all states).
- `data.asOf`: the path of an ISO-8601 timestamp, applied to the source that carries it. Default: the data file's root `updatedAt`, else the time the fetch finished.
- A file and sources can be used together. The tokens are different (`{data:…}`, `{fetch:…}`), so nothing collides. M1's first step uses the file (`w-usage.json`), because a sensor such as `sensor.example_status` may not exist yet.

### 5.1 Data-chosen links (`{data:blog.url}` in `ui.openUrl`)

**Allowed (C12).** A tap `url` may be a template: `{ "kind": "ui.openUrl", "url": "{data:blog.url}" }`. `{fetch:…}` is also allowed (my call: an M2 card may open a URL from fetched JSON). A link template **can't reference a secret** (no `auth`, no leftover placeholder): `elements[N].layouts…url: a link can't use a secret.`

The existing **Q-D5 rules apply to the resolved URL at tap time**, not to the template string:

1. Resolve the template. If the result is missing or empty, don't open; snackbar.
2. **Validate** the resolved URL: `https` or `http` only, **no `userinfo`**, and it must parse as a hierarchical http(s) URL with a host. If it fails, **don't open it**, and use the existing codes: `warn_tap_scheme` (any other scheme, a scheme-relative `//host` URL, or unparseable as a URL), `warn_tap_userinfo` (a userinfo part is present), `warn_tap_host` (the host is missing or illegal). These resolved values are refused with `warn_tap_scheme` and must each have a failing acceptance row: `intent:`, `javascript:`, `file:`, `content:`, `android-app:`, `data:`, and scheme-relative `//example.com/path`.
3. Normalise scheme + host (IDNA / punycode / lowercase, as today).
4. **Opens silently** only when that host **exactly matches** the card's `source` (no subdomain inference, no silent https→http downgrade).
5. **Any other host** — including every templated link on a **file-only card with no `source`** — **confirms on every tap**, with the device-local **`Always allow <host>`** checkbox. If the user has already ticked Always allow for that host on this card, it opens silently.

**Why links are not limited to the `source` origin.** File-only cards have no `source`, and a blog tile like example 1 is exactly this kind of card. The per-tap confirm (and Always allow) already covers other hosts, which is the Q-D5 phishing defence. Limiting links to `source` would make file-only cards unable to open a post without inventing a source they don't fetch from.

A link that passes the checks is opened **only** as `Intent(Intent.ACTION_VIEW, uri)` with an `http` or `https` scheme and `Intent.CATEGORY_BROWSABLE`. **Never** use `Intent.parseUri` (or `Intent.parseUri`-style URI intents), which would honour `intent:` and other dangerous schemes. It is not a fetch, it does not attach a secret, and it does not imply `net.fetch`. A card that declares `ui.openUrl` cannot use `header: false` (§4.5), whether the `url` is literal or templated.

## 6. Secrets and consent (fits #138 and the net.fetch rules)

### 6.1 Names in the file, values on the phone

`net.fetch` gains a `secrets` array of names. A source declares **where each secret is injected** in `auth` (§5). There is no `${secret:NAME}` placeholder:

```json
{ "kind": "net.fetch", "origins": ["https://ha.example.com"], "secrets": ["ha"] }
```
- **Fingerprint:** `net.fetch` lines are the **canonical** origin (as designed), and each secret is a line `secret ha @ <canonical origin>`. A new secret name, or a new origin, is a broadening → `needsApproval` / `needsApprovalAgain`. The ceiling never shrinks (Q-A9), as today.
- **Value:** typed (or pasted) on the phone in the approval sheet for that line, or imported once (§6.6). It is stored **device-local, wrapped by an `AndroidKeyStore` AES-GCM key**, in `filesDir/secrets/`. The entry field is a password-type field, masked after the last character, and the sheet uses `FLAG_SECURE`. Re-entering a value for the same `(name, origin)` **overwrites the stored value atomically** (temp + rename, like `ConsentStore`) and clears a `rejected` flag.
- **Scope:** keyed by `(name, <canonical origin>)`, not by element id (C3). The same `ha` entered once serves every approved widget for that canonical origin. Each widget still needs its own approval of that origin and that secret name. A widget for another origin gets *another* `ha`, which needs its own entry, so one origin can never receive another origin's secret. The HTTP client attaches it only when the request's canonical origin equals the stored origin, and only as the matching `auth` entry declares (`Authorization: Bearer …`, a named header, or a named query param added at request time). It is never stored in `url`.
- **Fetch snapshot:** the last-good response body lives in app-private **`filesDir/cards/`**, never in the synced config folder. `status.json` reports only the display state and a warning sentence, **never the body**, because the body could contain secret-derived data.
- **Backup exclusions:** add **`secrets/`** and **`cards/`** to **both** exclusions in `data_extraction_rules.xml` (the same pattern as `consent/` and `widgets/`; `allowBackup="false"` already covers older Android).

### 6.2 Surfaces a secret value must never reach

This is the RW-7 acceptance list:
- the config folder (`launcher.json`, drafts, `widgets-data/`, any other synced file), except the transient `secrets.import.json` of §6.6, which the launcher consumes and deletes
- `status.json` (no value, no header, no query value, no response body)
- logs and logcat (including the HTTP client and the ReflowSave tag: `Log.d` stays behind `Log.isLoggable`, hashes only, never the value; a request URL is logged as origin plus path)
- crash reports and bugreports
- backups and device transfer
- the clipboard (the field has no copy action and the app never writes the value to the clipboard; the app does **not** clear the system clipboard after a password-manager paste)
- the UI after entry (masked)
- **autofill**: the Autofill framework, IME suggestions and personalized learning, and password-manager save prompts on the entry field. Implementation notes: `importantForAutofill="no"` (use `noExcludeDescendants` on the sheet), no `autofillHints`, and `noPersonalizedLearning` in the IME options
- intents and saved instance state
- a WebView (cards don't use one; if one is added later, the value must not reach it)
- any URL or query string **as logged, stored or displayed**. The query value is sent on the wire at request time and is redacted everywhere else
- **exception messages, redirect `Location` values, and stack traces** logged anywhere (including crash reports). A thrown message and a logged redirect hop are shown as origin plus path, with the query and any secret redacted. The raw `Location` header is never written to a log

### 6.3 Failure states

- **Why a hostile card gains little:** fetch URLs are literals, requests are GET with no body, and responses feed only the card's display (and the private snapshot). There is no path for a secret, or for the data it unlocks, to reach a third host. The card could display what the secret may read at the origin the user approved. That is the exact trade of approving it, and it is shown in the prompt.
- **401 / 403:** mark that `(name, origin)` **`rejected`**. Stop fetching with it (every card that uses it). Keep the stored value (don't auto-delete) and keep last-good data, shown stale. Long-press offers a **re-enter** prompt. `status.json` gets `warn_secret_rejected`: `elements[3]: widget "agent" has a rejected secret "ha" on this phone. Open it to enter a new one.` A successful re-enter overwrites atomically, clears `rejected`, and fetches resume.
- **Missing secret:** if the card is approved but no value is stored, `warn_secret_missing`: `elements[3]: widget "agent" needs the secret "ha" on this phone.` Last-good (if any) is shown stale; no fetches until the user enters one.

### 6.4 Retention and removal

**Removing an origin or a source from the file:** cancel any in-flight fetches for it and drop their results (a late response is not applied and not written to `filesDir/cards/`). On the next stable good load, GC that card's fetch binding and snapshot. **Keep the secret** unless the user deletes it with **Forget secret**. Re-adding the same `(name, origin)` later can reuse the stored value; the card still needs its approval ceiling to include that line. **Forget approval** of a widget does not delete a secret that other widgets, or a later re-add, might use. There is no automatic delete when the last widget leaves the file. This is an intentional change from the earlier design in dynamic-widgets.md §3 and §6, which deleted secrets for removed origins.

### 6.5 Transport: https, LAN http, self-signed https (C11)

A typical Home Assistant setup uses https with a publicly trusted certificate on a name that may resolve only on a private network. The design is written around that, but it doesn't assume everyone has a public certificate.

**The private-network check applies only to plain http, never to https.** No warning may say "private IP denied" (or anything like it) for an https origin.

**(a) Normal https (RW-8).** The system trust store. A host with a **publicly trusted certificate is allowed whatever IP it resolves to**, including RFC 1918 (`10/8` and the rest) and CGNAT `100.64/10`. This is an **RW-8 acceptance case**: `https://ha.example.com` presents a publicly trusted certificate and resolves to a `10.x` or `100.64/10` address; the fetch proceeds; there is no private-IP warning and no extra prompt beyond the usual origin approval.

**(b) Plain http, checked on the resolved address (RW-8).**
- **The LAN check runs on the resolved address at connect time**, not only on hostname suffixes, so DNS rebinding can't get around it. The connected address must be one of: RFC 1918 (`10/8`, `172.16/12`, `192.168/16`), link-local (`169.254/16`, `fe80::/10`), loopback (`127/8`, `::1`, `localhost`), IPv6 unique-local (`fc00::/7`, my call: it is RFC 1918's IPv6 twin), or **CGNAT `100.64/10`**, which **does count as private** for this check (correction to an earlier call). A `.lan` / `.local` / `.home.arpa` name is allowed only if it resolves to one of those ranges.
- **IPv4-mapped and NAT64 addresses.** An IPv4-mapped address (`::ffff:a.b.c.d`, prefix `::ffff:0:0/96`) is classified as the **embedded IPv4**, on both the allow and deny sides. A NAT64 address in `64:ff9b::/96` is treated the same way (the low 32 bits are the IPv4). So `::ffff:192.168.0.1` and `64:ff9b::c0a8:1` are LAN, `::ffff:169.254.169.254` is the metadata deny, and `::ffff:8.8.8.8` is a public address for http.
- **Two guards.** (1) `network-security-config` permits cleartext (`cleartextTrafficPermitted="true"` in the base config), because that file can't express IP ranges. (2) **The app-level allowlist is the real guard.** It is applied to the **connected address** on every request. The single per-card HTTP client is the only networking code; there is no WebView. Well-known cloud-metadata addresses `169.254.169.254` and `fd00:ec2::254` are refused even though they are link-local (my call).
- **A public address over http is refused**, and the source never fetches. At load, a literal public IP or a well-known public host can warn early: `elements[N].data.sources[0]: "http://example.com" is a public address, so http isn't allowed there. Use https, or a host on your own network.` At connect time, a name that resolves to a public address uses: `elements[N].data.sources[0]: "http://ha.lan" resolved to a public address, so http isn't allowed there. Use https, or a host on your own network.`
- An `http://` origin still needs the existing **named approval** and the "less secure" disclosure (dynamic-widgets §3, Q-C7). That is not a private-IP denial.
- **A source that attaches a secret over http** shows a **one-time, device-local acknowledgement**, per `(secret name, origin)`: `This sends ha unencrypted on your network.` with `Send unencrypted` and `Cancel`. It is never synced, and **Forget secret** also forgets it. Until it is given there is **no fetch**: `elements[N]: widget "agent" is waiting for your OK to send ha unencrypted on your network.`
- **No proxy.** Fetches always connect **directly**, with `Proxy.NO_PROXY`, and **ignore** the system proxy and any Wi-Fi proxy. The LAN check therefore sees the real peer, not a proxy on the LAN in front of a public site. A **proxy-only network is not supported in M2**: if the destination is reachable only through a proxy, the fetch fails as unreachable and is not retried through the proxy.

**Redirects (every hop, RW-8).** Follow at most **3** hops. Re-run the transport check on **every** hop: the scheme, and for http the LAN rule on the **resolved and connected** address (including mapped and NAT64 forms). An **`https`→`http` hop is always refused**. A hop whose origin is not in the card's approved `origins` is refused. Header and query secrets are re-attached **only** when the hop is the same canonical origin; they are dropped on any other hop (and `Referer` is still never sent). A redirect loop, or a 4th hop, is a failed fetch. Each of these failures has a named warning:
- an http 302 to a public address: `elements[N].data.sources[0]: a redirect to "http://example.com/…" is a public address, so http isn't allowed there. Use https, or a host on your own network.`
- an `https`→`http` 302: `elements[N].data.sources[0]: a redirect from https to http was refused, so the widget didn't follow it.`
- a same-origin 302 whose next hop **resolves to a public address**: `elements[N].data.sources[0]: "http://ha.lan/…" resolved to a public address, so http isn't allowed there. Use https, or a host on your own network.`
- a redirect loop, or more than 3 hops: `elements[N].data.sources[0]: the request was redirected too many times, so the fetch was stopped.`

**(c) Self-signed https (RW-11, optional; does not block M2).**
- Planned, and free to land after RW-8 and RW-9. Until it lands, a self-signed origin is a **TLS failure**: last-good data is shown stale, and the certificate is never accepted silently.
- When it lands: a certificate the system doesn't trust makes the fetch fail with `needsPin`. The user is shown the certificate's **SHA-256 fingerprint on the phone** and must confirm it matches their server: `Trust this certificate for https://ha.lan:8123? SHA-256: 3A:9F:… Check it matches your server before you tap Trust.`
- On Trust, the launcher **pins that fingerprint for the canonical origin** (device-local, never synced). Later connections must present that certificate.
- **If the certificate changes, re-prompt.** Fetching stops, last-good shows stale, and `elements[N]: the certificate for https://ha.lan:8123 changed since you trusted it, so the widget "agent" stopped fetching. Open it to check the new fingerprint.` It is never accepted silently.
- Any approved origin can be pinned this way (my call), since the user confirms the fingerprint by hand. The pin follows the same `(canonical origin)` rule as secrets.

### 6.6 Optional one-shot `secrets.import.json`

An agent managing the phone's setup shouldn't have to leave tokens in a permanent synced file, and the user shouldn't have to paste on a phone keyboard. So the launcher supports an **optional one-shot import**:

```json
{ "secrets": [ { "name": "ha", "origin": "https://ha.example.com", "value": "…" } ] }
```
- **Where:** `secrets.import.json` in the config folder root, next to `launcher.json`. At most 16 entries and 4 KiB. `name` follows the secret-name rule, `origin` is canonicalized (§5).
- **When it is looked for:** on load, on `RELOAD`, and when home resumes (not on the 2 s poll).
- **Never a live source.** It is not part of `launcher.json`, never logged, and never mentioned in `status.json` except by the warnings below. It is not treated as consumed until the file has been **successfully deleted**.
- **The phone prompts first.** A sheet lists the **names and origins, never the values**, and marks an entry that would replace a stored one. Buttons: `Import and delete file`, `Delete file without importing`, `Not now`. (Pragma writes the final copy.)
- **On OK:** parse the file **in memory**, with **no plaintext temp file** in the config folder or in cache. Write each secret to the encrypted store as an **idempotent overwrite** by `(name, origin)`, and **only then** delete the import file. The file **stays until that delete succeeds**.
- **An interrupted import is not consumed.** If the process dies after writing some or all values and before the delete, or mid-delete, the file is still there. The prompt comes back on the next load, and re-importing is safe (the same `(name, origin)` overwrites atomically).
- **If the delete fails, keep the loud warning, and the prompt comes back** (a top notice until the file is gone, plus `warn_secret_import_left`): `secrets.import.json is still in your config folder and holds secret values. Delete it now. If this folder is synced or in git, change those secrets.` An unreadable file: `secrets.import.json can't be read, so nothing was imported. Delete it, because it may still hold secret values.`
- **What this does and doesn't buy.** The file never persists as live configuration, and the values end up only in the phone's Keystore-wrapped store. The values are on shared storage from the moment the agent writes it until the phone consumes it, so a synced or git-tracked folder can keep a copy elsewhere. The agent kit says: write it just before asking, add `secrets.import.json` to `.gitignore`, and rotate a token if it was synced. It is a convenience, not a vault. Submitted by a hostile agent, it can only add or replace values the user sees listed by name and origin.
- Implemented as a separate small issue, **RW-7b**, after RW-7.

## 7. Refresh, battery and limits

**Principle: update when someone can see it, and never to wake the phone.**

**When a fetch may run.** All of these must be true. If any is not, the source does not fetch (and its ticker is paused):

1. Home is **resumed** (`onStart`…`onStop`, like `AppWidgetHost` in native-widgets.md §2.3) **and visible**.
2. The **screen is on**.
3. The **keyguard is down**. Nothing fetches while the keyguard is up.
4. Home is **not covered** by the drawer or a sheet.
5. The card is on the **current page**. Off-page cards fetch when their page **settles** (the pager comes to rest on that page). Cards scrolled off-screen but still on the current page may fetch.

**Under Doze / App Standby** the app is foreground-only for this work, so nothing happens there: when home is not resumed, there are zero requests and no alarms.

**No WorkManager, JobScheduler, `AlarmManager`, foreground service or push.** The app has no background execution for readouts, so `dumpsys alarm` and `dumpsys jobscheduler` must show no Reflow wakeup.

**The timer.** One coroutine ticker **per visible source** (a source of a card that passes the five checks above), at **`max(every, 60 s)`**, with jitter ±10%, **paused on `onStop`**. "Refresh on glance": when a source becomes visible and is older than its `every`, it is refetched then. The agent file keeps its existing foreground poll with `launcher.json` (that poll is not a network fetch).

**`every`:** ISO-8601 duration, **minimum `PT1M`**, default `PT15M`. A smaller value is clamped with a load warning, not an error.

**Concurrency.** The **2-in-flight limit is per (canonical) origin**. The **4-connection pool is global** (across all cards). There is also a 5 s minimum gap per origin. A sixth source on a busy origin waits.

**Battery budget.** At most **60 fetches per hour per origin**, and **0 work while home isn't visible**. A glance after hidden time may run one fetch per due source, which counts toward the 60.

**Manual refresh** (`ui.reload`: the header control when the header is shown, a layout `button` or the long-press `Reload` action when it isn't): re-reads the file and refetches every source of that card now. At most one per 10 s per widget (the existing 2 s file limit stays for the file part). The snackbar says `Updated` or `No new data` as ux §17.9 already does. Manual refresh counts toward the 60, and is refused (snackbar, no request) if the origin is already at the cap.

**Backoff:** each failed fetch doubles that source's interval (×2, ×4, ×8, then holds at ×8, or `PT1H` at most), resetting on success or manual refresh. Offline (no validated default network, via a `NetworkCallback` registered only while home is resumed) means no attempts and no backoff growth. Android Data Saver is honoured: a restricted network means no fetch. 401/403 follows §6 (`rejected`), not this ladder.

**Stale:** `staleAfter` (default 2× the smallest `every`, else 30 min) turns `as of 19:41` into `as of 19:41 · stale ▲` (or the §4.5 corner ▲ on a headerless card). Failed fetches keep the last good values, as the file reader does. Never a blank card.

**Redraw only on change:** a source that returns an identical body doesn't recompose. Time tokens share the existing minute ticker. Battery recomposes only when the displayed `%` or state changes.

**A source that is waiting does not fetch:** no value for a secret it needs (`warn_secret_missing`), a `rejected` secret, an unacknowledged secret over http, or, once RW-11 has landed, a certificate waiting for the user to trust or re-trust it (§6).

**Hard caps:** 6 sources per card, 12 sources across all cards, 128 KiB per response, 10 s timeout.

**Honest limit, to put in the user docs:** with home hidden, the screen off, or the keyguard up, nothing updates. The first glance after a while shows the cached last-good values, and a fetch starts at once (the measurable bar is RW-9's: a fetch within 1 s of `ui.reload`, a redraw within 1 s of the response). A readout that must update with the screen off is outside this design. That is a notification or an `appWidget`.

## 8. Worked examples

Both are written so the agent can copy them once the levels are assigned. Example 1 needs only M1 (and works from a file). Example 2 shows M2 and notes its M1 form. In the JSON below, `requires` is the **symbolic level name**; a real file uses the integer assigned when that feature merges (`FL_CARDS_READOUT` at RW-1, `FL_FETCH` at RW-8). Do not copy a guessed 6 or 7.

### 8.1 Example 1: the "Example Co" status card, M1

Screenshot → schema: dark rounded card (`surface: "auto"`), title and `as of 19:41` (automatic header), circular refresh at top right (`ui.reload` adds it), a wide tile with caption, a title and a one-line excerpt ending in an ellipsis (`maxLines: 1`), a row of three tiles each with label, a status dot and a big value (`metric` + `status`), and a wide tile with a green dot and `All healthy`, with `All up` on the right (a `text` with `status`, and a `text` with `align: end`).

```json
{
  "id": "w-status", "type": "card", "requires": "FL_CARDS_READOUT",
  "page": "home", "x": 0, "y": 2, "w": 4, "h": 5,
  "minSpan": { "w": 2, "h": 2 },
  "title": "Example Co", "author": "agent",
  "source": "https://status.example.com",
  "capabilities": [ { "kind": "data.file" }, { "kind": "ui.reload" }, { "kind": "ui.openUrl" } ],
  "data": { "staleAfter": "PT30M" },
  "layouts": {
    "2x2": [
      { "type": "metric", "label": "Monitors", "value": "{data:monitors.summary}", "status": "{data:monitors.status}" }
    ],
    "4x5": [
      { "type": "tile", "tap": { "kind": "ui.openUrl", "url": "{data:blog.url}" }, "children": [
        { "type": "text", "role": "caption", "text": "Latest on status.example.com" },
        { "type": "text", "role": "title", "text": "{data:blog.title}", "maxLines": 2 },
        { "type": "text", "role": "body", "text": "{data:blog.excerpt}", "maxLines": 1 }
      ] },
      { "type": "row", "children": [
        { "type": "metric", "label": "Site A", "value": "{data:site_a.value}", "status": "{data:site_a.status}", "weight": 1 },
        { "type": "metric", "label": "Site B", "value": "{data:site_b.value}", "status": "{data:site_b.status}", "weight": 1, "priority": 4 },
        { "type": "metric", "label": "Monitors", "value": "{data:monitors.summary}", "status": "{data:monitors.status}", "weight": 1 }
      ] },
      { "type": "tile", "children": [
        { "type": "text", "role": "caption", "text": "Unhealthy monitors" },
        { "type": "row", "children": [
          { "type": "text", "text": "{data:unhealthy.text}", "status": "{data:unhealthy.status}", "weight": 1 },
          { "type": "text", "text": "{data:monitors.summary}", "align": "end" }
        ] }
      ] }
    ]
  }
}
```
Data file `.configlauncher/widgets-data/w-status.json` (the agent writes it):
```json
{
  "updatedAt": "2026-11-27T19:41:00-07:00",
  "blog": { "title": "Why the launcher reads a file", "excerpt": "A short story about config, agents and phones that stay…", "url": "https://status.example.com/launcher-file" },
  "site_a": { "value": "up", "status": "ok" },
  "site_b": { "value": "up", "status": "ok" },
  "monitors": { "summary": "All up", "status": "ok" },
  "unhealthy": { "text": "All healthy", "status": "ok" }
}
```
M2 variant: the three monitors come from an unauthenticated JSON endpoint. Add `{ "kind": "net.fetch", "origins": ["https://status.example.com"] }`, `"sources": [{ "name": "mon", "url": "https://status.example.com/summary.json", "every": "PT5M" }]`, set `requires` to `FL_FETCH`, and swap `{data:site_a.value}` for `{fetch:mon.site_a.value}`. The blog tap stays a `source` tap (Q-D5). Because this variant has `ui.openUrl`, `header: false` would be ignored (warning in §4.5); example 1 already shows the header.
- **The blog tile is a data-chosen link (C12, §5.1).** `url` is `{data:blog.url}`. At tap time the resolved URL is validated (https or http, no userinfo, must parse). This card has `source: "https://status.example.com"`, so a post on that host opens silently; any other host confirms, with Always allow. The link template does not reference a secret. The agent writes the data file with temp-then-rename (§4.6); the reader ignores `.tmp` / `.part` / dotfiles and keeps the last good snapshot if a write is mid-flight.

### 8.2 Example 2: the "Agent usage" card (file-only sticker, ticks)

Screenshot → schema: a teal rounded card (`surface.color`) with a **decorative sticker** between the card surface and the content. The sticker is a `picture` layer, `fit: "fixed"`, `width` about `0.4`, `align: "bottom"`, a **positive `y` offset**, `overflow: true`, `opacity` about `0.8`, and `scrim: "none"`. Its `src` is the filename template `sticker-{data:state}.webp`, with a `default`. `up 13m` in monospace at top right (`font: "mono"`, `align: "end"`, formatter `since`), three labelled **tick** bars with percentages (`style: "ticks"`; the `ctx` fill turns yellow-orange near the end through a `warn` threshold at 75 and `by: "position"`), and `status: idle` in monospace at bottom right.

**This card is file-only.** The agent writes the JSON file and the sticker files into the card's folder **once**. There is no `ui.openUrl` and no source needing consent, so under §4.5 **`header: false` is honoured**. It always draws its card surface, and a ▲ appears top-end when the data is stale. `scrim: "none"` is explicit, so there is no default-scrim warning. Refresh is a layout `button` or the long-press `Reload` action (`ui.reload` is declared). The M1 data file is `w-usage.json`, because `sensor.example_status` does not exist yet.

```json
{
  "id": "w-usage", "type": "card", "requires": "FL_CARDS_READOUT",
  "page": "home", "x": 0, "y": 8, "w": 4, "h": 3,
  "title": "Agent usage", "author": "agent",
  "header": false,
  "surface": { "color": "#0F6E6E" },
  "layers": [
    { "kind": "picture",
      "src": "sticker-{data:state}.webp",
      "default": "sticker-idle.webp",
      "fit": "fixed",
      "width": 0.4,
      "align": "bottom",
      "offset": { "x": 0, "y": 0.15 },
      "overflow": true,
      "opacity": 0.8,
      "scrim": "none" },
    { "kind": "content" }
  ],
  "summary": "Agent usage, {data:state}. Context {data:ctx|round} percent",
  "capabilities": [ { "kind": "data.file" }, { "kind": "ui.reload" } ],
  "data": { "staleAfter": "PT15M" },
  "layouts": {
    "4x3": [
      { "type": "text", "role": "caption", "font": "mono", "align": "end", "text": "up {data:started_at|since}" },
      { "type": "progress", "label": "ctx", "value": "{data:ctx}", "max": 100, "style": "ticks", "by": "position",
        "thresholds": [ { "from": 0, "tone": "accent" }, { "from": 75, "tone": "warn" } ] },
      { "type": "progress", "label": "5h", "value": "{data:five_hour}", "max": 100, "style": "ticks", "by": "position",
        "thresholds": [ { "from": 0, "tone": "accent" }, { "from": 75, "tone": "warn" } ] },
      { "type": "progress", "label": "7d", "value": "{data:seven_day}", "max": 100, "style": "ticks", "by": "position",
        "thresholds": [ { "from": 0, "tone": "accent" }, { "from": 75, "tone": "warn" } ] },
      { "type": "text", "role": "caption", "font": "mono", "align": "end", "text": "status: {data:state|default:unknown}" }
    ]
  }
}
```
- **Files** in `.configlauncher/widgets-data/w-usage/`, written once, temp-then-rename (§4.6): `sticker-idle.webp` (the `default`) and `sticker-brain_full.webp`. The template is `sticker-{data:state}.webp`, so the file name is exactly `sticker-` plus the `state` value plus `.webp`. **Data** `widgets-data/w-usage.json`: `{ "updatedAt": "2026-11-27T21:06:00-07:00", "ctx": 79, "five_hour": 31, "seven_day": 8, "started_at": "2026-11-27T20:53:00-07:00", "state": "idle" }`. The token never reaches the phone.
- **Swapping the sticker:** The agent changes `state` in the data file, and the picture follows. `state: "idle"` shows `sticker-idle.webp`. `state: "brain_full"` shows `sticker-brain_full.webp`. An unknown or missing `state` uses the `default` (`sticker-idle.webp`). With no `default` and a missing file, that layer is skipped silently and the card shows its teal surface.
- The sticker sits on the teal surface, peeks up to 25% past the bottom edge (`overflow: true`, `offset.y: 0.15`), does not take taps outside the card, and is drawn beneath any neighbouring card. Inside the card it is clipped to the rounded shape only where it does not overflow. `scrim: "none"` leaves the ticks and text on the teal surface, with no wash over the sticker. Contrast is measured against that surface colour, not the sticker, which is the author's explicit choice.
- The tick bars auto-fit (`count` omitted). On the 79% ctx bar, a tick at 74 stays `accent` and ticks at 75 and 76 take `warn` (`by: "position"`, `from: 75` inclusive, the same 74/75/76 boundary as RW-4). 31% and 8% stay `accent`.
- If `scrim` were omitted (or `"auto"`), this picture sits directly under content, so the default scrim would be applied and the load warning would be: `elements[N].layers[0]: a picture is right under content, so a default scrim was added to keep text readable. Set this layer's scrim to choose it, or "none" to turn it off.`

**M2 form (optional later).** When a `sensor.example_status` template sensor exists, the same card can grow a fetch at `requires: "FL_FETCH"`. The stickers stay local files; only the numbers and the `state` used in the filename come from HA:

```json
"capabilities": [
  { "kind": "data.file" },
  { "kind": "ui.reload" },
  { "kind": "net.fetch", "origins": ["https://ha.example.com"], "secrets": ["ha"] }
],
"data": {
  "staleAfter": "PT15M",
  "asOf": "last_updated",
  "sources": [
    { "name": "usage",
      "url": "https://ha.example.com/api/states/sensor.example_status",
      "every": "PT5M",
      "auth": [ { "secret": "ha", "as": "bearer" } ] }
  ]
}
```
- Tokens become `{fetch:usage.attributes.ctx}`, `{fetch:usage.attributes.started_at|since}`, `{fetch:usage.state|default:unknown}` and so on, and the picture `src` becomes `"sticker-{fetch:usage.state}.webp"`. **The pictures stay local files** in `widgets-data/w-usage/`.
- **Header:** `net.fetch` needs consent, so `"header": false` is **ignored**: the header is shown, with the warning `elements[N].header: false is ignored because this card opens links or needs your OK, so the header is shown.` The JSON can keep `"header": false` so the fallback is testable. A live HA card always has a title row. The sticker look without a title is the M1 form.
- **First load** shows the consent tile, asking for `data.file`, `ui.reload`, `net.fetch https://ha.example.com` and the secret `ha`. Approve, then either type the token once or let the agent hand it over with `secrets.import.json` (§6.6), and the card goes live. If HA were on plain http on the LAN (a resolved `10.x` or `100.64/10` address), the same card would also need the one-time "This sends ha unencrypted on your network." acknowledgement. If the host is https with a publicly trusted certificate on a name that resolves to a private or CGNAT address, that is the RW-8 acceptance case, and there is no private-IP warning.
- **Agent-kit note (RW-6):** recommend a **separate non-admin Home Assistant user** for the phone's long-lived token, because a token can do anything its user can. One-line how-to: in Home Assistant, Settings → People, add a non-admin user for the phone; sign in as that user, open the profile, create a long-lived access token, and use it as `ha`. Don't use an administrator account.

## 9. Built-in readouts and Pragma's opinions (secondary)

The priority is §8. The first-party set is therefore **recipes: ready-made cards**, not types.
- **Clock/date**, **text/note**, **battery** (`{battery}`, `{batteryFraction}` for a `progress`, `{batteryState}`), **next alarm** (`{nextAlarm[:p]}`) and an **At a glance** strip that combines them. Parts resolve empty when uninteresting (`{batteryState}` is `Charging`, `Full`, `Low` (≤15%) or empty, the Kvaesitso rule), so an empty text isn't drawn. Token names are the ones in §4.3.
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
- **Q-T7 (dynamic colour)** is compatible: `surface: "auto"` follows the home theme, tones are fixed. Decided: opt-in, in Settings only (§13).
- **Layers and the ▲/title chrome** (§4.4) mean a picture can never cover the title row or the stale marker, so none of Pragma's top items change the layer design.

## 11. What is explicitly cut

Formulas, expressions, `$if()$`, Jinja, flows, globals, komponents, scripts of any kind; absolute layout; remote pictures, SVG, GIF; the inline `image` node; a separate `scrim` layer kind; the `{bind, map, default}` picture form; `${secret:NAME}` placeholders; free x/y/z beyond `align` + `offset`; picture animation; overflow past 25% or over neighbouring cards or into system-bar insets; more than 4 layers per card; WebView, HTML, JS; any HA client, entity picker or WebSocket; `/api/states`, POST fetches, request bodies, cookies, denied header names (`Host`, `Cookie`, …); secrets in the URL; secrets in a permanent synced file; background wakeups, jobs, alarms, services, push, real-time sockets; built-in weather fetch, provider choice and location permission; media (notification listener); calendar permission (for now); analog or other clock styles; per-widget theme galleries and a phone-side style editor; per-widget secrets (shared by name and origin instead); widget stacks and pop-ups; RSS and news; text/clock/battery as element types.

## 12. Proposed issue breakdown (build order)

Prerequisites, already filed: **#140** (S3-E card render), **#141** (S3-F templates and taps), **#142** (S3-C consent UX). **#143** (fixtures) is extended by RW-1. M1 only needs #140 and #141. Consent #142 is needed for `ui.openUrl` and M2.
Checks marked **R** are hands-on spot checks, coordinated by Grok Bot, on **a phone** or **a tablet**. Grace owns every JVM and emulator check. Until #305 is tagged, "a FEATURE_LEVEL 5 APK" means current main (the 1.1.0 dev line), not a named "v1.1" release.

**M1: agent-written readouts**

**RW-1. Schema at `FL_CARDS_READOUT`: M1 keys.** `surface` object, `layers` (kinds `picture`/`content`, caps, `fit` including `fixed`+`width`, `align`, `offset`, `overflow`, `scrim` auto/none/object, the default scrim), `header`, `summary`, text `display/align/font/status`, `progress` `max/segments` (cap 20)/`style`/`count`/`thresholds`/`by`, `metric.thresholds`, `button.icon`, `color`; the empty-is-not-drawn rule; codec canonical order, `validate.py`, schema, `FEATURE_LEVEL = FL_CARDS_READOUT` (the next free integer when this merges) and docs. *Acceptance:* the example in §8.1 and the M1 form of §8.2 validate in Kotlin and Python with the same messages, once `requires` is the assigned integer. Each §4.4 and §4.5 warning is an exact-string test:
- A card with `header: false` and `ui.openUrl` (or `net.fetch`) is **valid**, the header is **shown**, and the warning is `elements[N].header: false is ignored because this card opens links or needs your OK, so the header is shown.`
- A card with `header: false` and `surface: "none"` is **valid**, the default card surface is **kept**, the header is **dropped** (if otherwise allowed), and the warning is `elements[N].surface: "none" can't be used with header: false, so the default card surface was used.`
- A card whose `picture` layer sits directly under `content` with `scrim` omitted or `"auto"` is **valid**, the full-card default scrim is applied, and the single load warning is `elements[N].layers[I]: a picture is right under content, so a default scrim was added to keep text readable. Set this layer's scrim to choose it, or "none" to turn it off.` with the real index of the picture layer in place of I.
- The same card with `scrim: "none"` is **valid**, draws **no** scrim, and has **no** warning. With a `{color, opacity}` scrim, that scrim is used and there is no warning. `"auto"` on a picture that is not directly under content draws no scrim and does not warn.
- More than 4 layers, a second `content` layer, a remote `src`, a layer above `content` with opacity over 0.6, `width` on a non-`fixed` fit, an `offset` or `width` out of range, a `count` above 120, and each missing-file case (fixed `src`, the `default` file) give the §4.4 / §4.2 sentences. A template `src` that resolves to a missing or invalid name, with no usable `default`, gives **no** load warning and skips the layer at runtime.
- A `ui.openUrl` whose `url` is `{data:blog.url}` is **valid**. A link template that mentions a secret is a load error naming the field.
A card using any new key and `requires` below `FL_CARDS_READOUT` is an error. A FEATURE_LEVEL 5 APK skips a card with `requires` at `FL_CARDS_READOUT`, keeps its cells and shows the "Update" tile. *Grace:* JVM + `scripts/test_validate.py` for each key, invalid fixtures for each cap, the skip case on a FEATURE_LEVEL 5 APK in the emulator.

**RW-2. Renderer additions (extends #140).** Segmented progress with thresholds and `by`, monospace, `display` tier, `align`, status dot on text, the §4.5 header rules, `surface` colours, `layers` drawing (picture `fit` including `fixed`+`width`, `align`, `offset`, `opacity`, `overflow`, `scrim` auto/none/object; the default scrim at 60% `surface` in light and 45% black in dark, a full-card fill, never a gradient; chrome above every layer; overflow at most 25% past an edge, clipped to page bounds, never into system-bar insets, drawn beneath neighbouring cards, no taps on the overflow), tick-style progress (`style: "ticks"`, auto-fit and explicit `count`), TalkBack `summary` and title, and tap-time resolution of a templated `ui.openUrl` (§5.1). *Acceptance:* both §8 examples render in the screen matrix points (including font scale 2.0 and a compact-phone width). Overflow follows ux §17.8: drop by `priority`, then ellipsize — there is no "no clipped text" bar. The `display` tier's sp values are unmeasurable until Pragma sets them; until then, tests use a temporary 22 / 28 / 32 and are re-run when she sets the numbers. Each §4.5 warning string is an acceptance test (same sentences as RW-1). Pending consent forces the header on silently, with no warning. The stale marker is a 12 dp ▲ in the **top-end** corner, mirrored in RTL, on the card surface, with no text; TalkBack reads it after the title as `stale, as of 08:38`, or `stale` when there's no `updatedAt`. The long-press sheet title is the card's title, or its element id when there's no title, with `as of 08:38` as a caption under it. `ui.openApp` and `ui.reload` buttons work on a headerless card; the long-press sheet offers `Reload` when `ui.reload` is declared. While a picture is loading or after it fails, the card surface is drawn behind the content, never a blank background. A template `src` that resolves to a missing or invalid name uses `default`, or is skipped at runtime silently if that is also missing, and the other layers still draw. An overflowing sticker is visible past the card, does not receive taps outside the card, is hidden under a neighbouring card and in the system-bar insets, and is clipped to the rounded card shape when `overflow` is absent. Contrast warnings appear in `status.json` for a custom colour below 4.5:1 and are measured over the scrim colour when a scrim is present. When `scrim` is `"none"`, contrast is measured against the card surface colour, not the picture, which is the author's explicit choice (no contrast warning for the missing scrim). A templated `ui.openUrl` that resolves to `intent:`, `javascript:`, `file:`, `content:`, `android-app:`, `data:`, or a scheme-relative `//example.com/path` is refused with `warn_tap_scheme` and is not opened. A passing link is opened only as `Intent(ACTION_VIEW, uri)` with an `http` or `https` scheme and `CATEGORY_BROWSABLE`; `Intent.parseUri` is never used. *Grace:* Compose tests, screenshot matrix, TalkBack read-through. **R:** a phone for density and legibility over a real wallpaper, and a tablet for large cells.

**RW-3. Layers and local pictures.** The `widgets-data/<id>/` reader for the data file and pictures, including **atomic reads** (§4.6): ignore names that start with `.` or end in `.tmp` or `.part`; an unparseable, empty or mid-sync data file keeps the last-good snapshot and its as-of time and warns once per file until it parses again; a picture decode failure keeps the last good bitmap. Caps, decode-to-size, `layers[]` pipeline (fixed `src` or a filename template such as `sticker-{data:state}.webp`, optional `default`, re-evaluated when the bound value changes, silent skip when nothing usable remains, card surface while loading or failed), `fit`/`width`/`align`/`offset`/`overflow`/`scrim`, the static load warnings, `warn_image_bad`. Loading never writes to the config folder. *Acceptance:* a 5 MiB or 4000 px file is refused with the warning, a PNG, JPEG and WebP of valid size draw, an SVG is refused, an id's folder can't read another's file (`../`, symlinks, a resolved name with `/` or `..` or a leading `.`). Changing `state` in the data file from `idle` to `busy` swaps the sticker (`sticker-idle.webp` → `sticker-busy.webp`; fallback to `default`, and skip when neither is usable). A missing `{data:state}` in `src` is an invalid name and takes `default` or is skipped, the same as any other missing file. A half-written `w-usage.json.tmp` (or `.w-usage.json.tmp`) is ignored and last-good stays on screen, with the once-per-file warning; the mid-write file is never repaired into the synced folder. No file write from loading. After 20 reloads of a card with four max-size pictures, and a GC, `dumpsys meminfo` shows the app's PSS at most **8 MiB** above the first reading. *Grace:* JVM for path, size, template, ignore-rules and layer rules; emulator for decode, loading/failed/last-good states, overflow clipping, and the memory budget.

**RW-4. Tokens, formatters, thresholds engine.** `{fetch:…}` grammar (parse only in M1), the five formatters, `default`, threshold evaluation, `data.asOf`. Pure Kotlin, mirrored in `validate.py`. *Acceptance:* table tests for each formatter at the edges (negative, null, a missing path, a bad timestamp, DST, a 12 vs 24 h locale), **`round` with no argument → n = 0**, two formatters applied left to right, the missing-path (`—` / `default`) vs empty-string (not drawn) row in §4.3, `{batteryFraction}`, threshold boundaries (74/75/76), the same strings in Kotlin and Python. *Grace:* JVM only.

**RW-5. Ambient tokens and recipes (secondary).** `{battery}`, `{batteryFraction}`, `{batteryState}`, `{nextAlarm[:p]}`, registered on `onStart` and unregistered on `onStop`, the recompose-on-change rule, picker group `Reflow Launcher` (recipes as `AddElement`), `ui.openDrawer`. *Acceptance:* no receiver is registered while home is stopped, a battery sticky intent with only voltage changing doesn't recompose, one write per recipe added. A card that uses only these tokens plus Show-tier kinds (`data.file`, `ui.reload`, `ui.openDrawer`) is Show-only and goes live with **no prompt** (must match ux §17.1 once that section is updated). *Grace:* JVM with fake sources, emulator `adb shell dumpsys battery set level 14` / `unplug`, alarm set by an instrumented helper. **R:** a phone, charging and unplugging, and the alarm shown from the real clock app.

**RW-6. Agent kit for M1.** `templates/config-folder/AGENTS.md` and `skills/reflow-launcher/SKILL.md` sections (recipes, §8 examples with the assigned `requires` integers filled in, where the file and pictures go, the **temp-then-rename** write rule, `x-` notes). For the HA recipe: `auth: [{ "secret": "ha", "as": "bearer" }]`, and a **separate non-admin Home Assistant user** for the phone's long-lived token, with this one-line how-to: in Home Assistant, Settings → People, add a non-admin user for the phone; sign in as that user, open the profile, create a long-lived access token, and use it as `ha`. A token can do anything its user can, so don't use an administrator account. Fixtures in `test-fixtures/configs/valid` for both examples, and a README line on committing `widgets-data/`. *Acceptance (Grace / CI):* every snippet in the docs validates with `validate.py`. *Not a JVM or CI gate:* "a fresh agent run, told only the template, produces a valid card from a one-sentence ask" is **the agent's recorded test**. **This is the first moment the agent can use the feature.**

**M2: live data**

**RW-7. Secret store.** Keystore-wrapped store keyed by `(name, canonical origin)`, `auth` placement (`bearer` / named `header` / named `query`) as declared on the source (§5): the value is injected at request time, never stored in `url`, and a leftover placeholder or a secret in any other field is a load error naming the field. A declared query name that already appears in the `url`'s own query, or a header named (case-insensitively) `Host`, `Content-Length`, `Cookie`, `Cookie2`, `Origin`, `Connection`, `Keep-Alive`, any `Proxy-*`, `TE`, `Trailer`, `Transfer-Encoding`, `Upgrade`, or `Referer`, is a load error. `Referer` is never sent. Header and query secrets are re-attached only on a same-origin redirect hop and are dropped on any other. Query values (and bearer/header values) are redacted from logs, `status.json` and every never-reach surface, including exception messages, redirect `Location` values, and stack traces logged anywhere. Atomic overwrite on re-enter, backup exclusion of `secrets/` **and** `cards/` in **both** `data_extraction_rules.xml` lists, entry and forget flows (Pragma writes the copy), `FLAG_SECURE`, masked field, `status.json` display states only. *Acceptance:* the secret value never appears on any surface listed in §6.2, including autofill (`importantForAutofill=no`, `noPersonalizedLearning`: no autofill dropdown, no IME suggestion, no password-manager save prompt), the request URL as logged (origin plus path, no query), exception messages, redirect `Location` values, and stack traces. Re-entering the same `(name, origin)` overwrites atomically and clears `rejected`. Removing an origin or source cancels in-flight fetches, drops their results, and GCs the binding and snapshot on the next stable load; the secret is kept unless the user chooses **Forget secret**. A rotation mid-entry leaves nothing in saved instance state. *Grace:* JVM for the keying, canonicalization, `auth` placement, overwrite and GC rules; instrumented test on the emulator for the Keystore wrap and the surface list. **R:** a phone (Keystore) and a tablet.

**RW-7b. One-shot `secrets.import.json` import.** §6.6: detect on load, `RELOAD` and home resume; parse **in memory** (16 entries, 4 KiB, canonical origins), with **no plaintext temp file**; the phone prompt lists names and origins, never values; on OK write each secret to the RW-7 store as an idempotent overwrite by `(name, origin)`, and **only then** delete the file; the file stays until that delete succeeds. An interrupted import is not consumed: the prompt comes back on the next load and re-importing is safe. If the delete fails, keep the loud warning **and** the prompt comes back. *Acceptance:* values never appear in any §6.2 surface, including `status.json` and logs, and the file is never treated as a live source. The file is not treated as consumed until the delete succeeds, so a crash mid-import leaves the file in place and the next load shows the prompt again. A file that can't be deleted shows the loud warning until it is gone, and the prompt comes back. `Delete file without importing` stores nothing. An entry that would replace a stored value is marked in the prompt. An invalid file imports nothing and warns. The agent kit tells the agent to write it just before asking and to gitignore it. *Grace:* JVM for the parser and the delete-failure path; emulator for the prompt and the SAF delete. **R:** one real import on a phone.

**RW-8. `net.fetch` runtime (stage 5).** `INTERNET`, the per-element isolated client, origin pin, redirects (at most 3 hops; transport check on every hop; `https`→`http` always refused), caps, **normal https and plain http** (§6.5). The private-network check applies **only to plain http, never to https**. `network-security-config` permits cleartext; the app-level LAN allowlist runs on the **resolved / connected address** (RFC1918, link-local, loopback, ULA, and CGNAT `100.64/10`), including IPv4-mapped `::ffff:a.b.c.d` and NAT64 `64:ff9b::/96` classified as the embedded IPv4, at connect time, so DNS rebinding can't get around it. Fetches always use `Proxy.NO_PROXY`. A public http host is refused. A secret over http needs the one-time device-local acknowledgement. `auth` injection is enforced here. *Acceptance:* the existing dynamic-widgets §3 and §6 table, each row with a test, plus origin canonicalization (trailing slash, default port, host case, IDN) so `https://ha.example`, `https://ha.example:443/` and `https://HA.EXAMPLE` are treated as one origin. A request to an unapproved origin never leaves the phone. A redirect to an origin not in the card's approved list fails. Header and query secrets are re-attached only on a same-origin hop and are dropped on any other. A secret is attached only when the request's canonical origin equals the stored origin, and only as the `auth` entry declares, never stored in `url` or written to a log. **https private-address case (required):** `https://ha.example.com` presents a publicly trusted certificate and resolves to a `10.x` or `100.64/10` address; the fetch proceeds; **no warning may say "private IP denied"** (or anything like it) for https. The LAN-http cases are exact-string tests: public `http://example.com` is refused with `elements[N].data.sources[0]: "http://example.com" is a public address, so http isn't allowed there. Use https, or a host on your own network.`; a name that resolves to a public address is refused at connect time with the "resolved to a public address" sentence; `http://192.168.1.5:8123`, `http://10.0.0.2`, `http://[fe80::1]`, `http://127.0.0.1` and **`http://100.64.0.5`** pass the allowlist; the same allow and deny results hold for IPv4-mapped (`::ffff:192.168.1.5` allowed, `::ffff:8.8.8.8` denied, `::ffff:169.254.169.254` refused as metadata) and NAT64 (`64:ff9b::c0a8:105` allowed as `192.168.1.5`, `64:ff9b::808:808` denied as public). Fetches always use `Proxy.NO_PROXY` (a configured system or Wi-Fi proxy is ignored; a proxy-only network is not supported in M2). These redirect failures each produce the named §6.5 warning and fail the fetch: an http 302 to a public address; an `https`→`http` 302; a same-origin 302 whose next hop resolves to a public address; a redirect loop or a 4th hop. No fetch happens until the acknowledgement `This sends ha unencrypted on your network.` is given. Until RW-11 lands, a self-signed origin is a TLS failure, last-good data is stale, and the certificate is never accepted silently. *Grace:* JVM with a local test server (MockWebServer), including an https server whose resolved address is RFC1918 or `100.64/10`; emulator tests against a LAN-style host.

**RW-9. Refresh scheduler.** §7, with the numbers in the acceptance. *Acceptance:* with a fake clock and a test server: glance; tickers at `max(every, 60 s)` per visible source, paused on `onStop`; jitter ±10%; `every` floor `PT1M` (clamp + warning); backoff ×2 / ×4 / ×8 then hold or `PT1H`, reset on success or manual refresh; 2 in flight **per origin**; 4-connection pool **global**; 5 s gap per origin; at most 60 fetches per hour per origin (a 61st is refused). Only current-page cards fetch; an off-page card fetches when that page settles. Zero requests when the keyguard is up, when the drawer or a sheet covers home, when the screen is off, or when home is not resumed (0 work while home isn't visible). Airplane mode means zero attempts and no backoff-ladder growth. `dumpsys alarm` and `dumpsys jobscheduler` show no Reflow wakeup. **Manual refresh is measurable:** after `ui.reload` (not rate-limited), a fetch starts within **1 s** and the card redraws within **1 s** of the response, with a fake clock and server. A source that is waiting (§7) does not fetch. *Grace:* JVM with a fake clock and server, emulator with `svc wifi disable` and a keyguard/screen-off case. **R:** a phone for a battery check (`dumpsys batterystats`, `dumpsys alarm`, `dumpsys jobscheduler`) after a session with home not visible, showing 0 fetch-related work and no background wakeups.

**RW-10. Consent UX for `net.fetch` and secrets (extends #142).** Prompt lines, the private-origin note, the `http://` acknowledgement (`This sends ha unencrypted on your network.`), **Forget secret**, **re-enter** after `rejected`. *Acceptance:* a broadened line prompts only for the new lines, and the approved secret *name* (never the value) appears in the fingerprint. The acknowledgement is per `(secret name, origin)`, device-local, and cleared by **Forget secret**. 401/403 marks the secret `rejected`, stops fetching, keeps last-good stale, offers re-enter on long-press, and does not auto-delete. After **Forget secret** (token removed): `warn_secret_missing`, last-good stale, no further fetches until a value is entered. *Grace:* Compose tests. **R:** one end-to-end approval on a phone, plus a 401 and a Forget-secret check.

**RW-11. TOFU pin for self-signed HTTPS (optional; does not block M2).** §6.5(c): `needsPin` on an untrusted certificate, the on-phone SHA-256 fingerprint prompt, pin per canonical origin (device-local, never synced), re-prompt when the certificate changes, never a silent accept. It depends on RW-8 and can land after RW-8 and RW-9. Until it lands, the failure mode is the TLS one in RW-8, and M2 is still complete for hosts with a public certificate. *Acceptance:* an untrusted certificate stops the fetch with last-good shown stale until Trust; Trust pins the fingerprint, and a later connection with the same certificate works; a different certificate stops fetching and shows `elements[N]: the certificate for https://ha.lan:8123 changed since you trusted it, so the widget "agent" stopped fetching. Open it to check the new fingerprint.`; **Forget** removes the pin. A pin on `https://ha.example:8123` does not apply to a different scheme or port (`http://ha.example:8123` and `https://ha.example:443` are unpinned). *Grace:* JVM with a self-signed MockWebServer; emulator. **R:** a self-signed LAN server on a phone.

**RW-12. QA pass on the signed release APK.** The matrix: reboot; update over a FEATURE_LEVEL 5 APK (a card at `FL_CARDS_READOUT` or `FL_FETCH` is skipped and its cells are kept); file mode and fetch mode; offline; a rotated and a folded screen; token removed (`warn_secret_missing`, last-good stale, no further fetches); TalkBack; the §7 battery budget and no background wakeups. Known gaps listed in the release notes. *Grace:* emulator profiles on debug builds, on the current CI smoke spread (API 29, 35, 36) and any other profiles she already runs. API 31 is not in current CI smoke and is not required here. *Grace does not install release-key-signed APKs on the box.* **R:** the signed APK and hands-on spot checks on a phone and a tablet.

**Order:** #140 → #141 → RW-1 → RW-2 → RW-3 → RW-4 → RW-6 *(M1 ready for the agent; RW-5 can run in parallel)* → RW-7 → RW-7b → RW-8 → RW-9 → RW-10 → (RW-11, optional, does not block M2) → RW-12. Edits (#303/#304) already exist. `Restore previous layout` (audit item 3) still lands before the M1 announcement, and folder editing is sequenced with element edits (§10).

## 13. Open product questions, and what is decided (2026-11-27)

**Nothing is open on the product questions.** Q1 to Q5 and the UX questions on #353 are answered. The later review and the sticker-layer notes are folded in below as decided. (The UX audit file, [polish-audit.md](polish-audit.md), is untouched. The answers to its questions are recorded here instead.)

**Product answers**
1. **Two steps, files first: yes.** M1 (files) ships first, then M2 (live data). §0, §12.
2. **Home Assistant transport:** a normal https host with a publicly trusted certificate is allowed whatever IP it resolves to, including RFC1918 and CGNAT `100.64/10` (this is an RW-8 acceptance case). Plain http is LAN-only, checked on the **resolved address** at connect time; **CGNAT `100.64/10` counts as private** for that check. Self-signed https is **optional** (RW-11) and does not block M2. No warning may say "private IP denied" for https. §6.5.
3. **Background picture: replaced by layers.** `layers[]` of `picture` and `content` only (no separate `scrim` kind); a filename template for `src`; scrim is `"auto"` / `"none"` / `{color, opacity}` on the picture; overflow, `fit: "fixed"`, `offset`. RW-3 is "layers and local pictures". §4.4, §8.2.
4. **`header: false`: yes,** under the file-only rule. §4.5.
5. **Generic named secrets: yes.** A source declares placement in `auth` (`bearer`, named `header`, or named `query`). No `${secret:NAME}` placeholder. HA is a recipe (`as: "bearer"` with secret `ha`). Optional one-shot `secrets.import.json` (RW-7b). §5, §6.

**Pragma's questions: all confirmed**
- **Dynamic colour (Q-T7): opt-in, yes.** It lives in Settings only, under Appearance.
- **The phone handles everyday edits: yes.** Folder editing is sequenced with element edits (§10).
- **Search: apps-only, plus a `Search the web` row: yes.**
- **A real Settings screen: yes,** with the menu rule below. This **closes the open item that was owned by Pragma**.

**The ⋮ menu versus Settings (Pragma's answer 9).** The ⋮ menu keeps actions and on-the-fly toggles, **7 items or fewer**. Setup, storage and agent settings go to Settings. Any toggle in the menu is **mirrored in Settings with the same state**.

The ⋮ menu, in this order:
1. `+ Widget` and `+ Page` (edit mode only)
2. `Review cards · N` with its dot (only when cards are live)
3. `Reload layout`
4. `Label backdrop` (toggle with a trailing check)
5. `Open keyboard with drawer` (toggle with a trailing check)
6. `Settings` (new)
7. `Set as home app` (only when Reflow isn't home)

Moves to Settings:
- Title bar…
- Config folder, Choose folder, Show config location, and Use phone storage (now with a confirm, per audit item 3)
- Ask before merging
- Include hidden apps in apps.json
- Agent instructions (AGENTS.md)
- Version (under About)
- Dynamic colour (in Settings only, under Appearance)

Settings sections: **Appearance, Home & drawer, Storage, Agents & widgets, About.**

**Pragma's default-scrim rule** is still §4.4, now as `scrim: "auto"`: the default scrim is a full-card fill (`surface` at 60% in light, black at 45% in dark), only when the picture sits directly under content. `"none"` turns it off with no warning. The warning ending is: `Set this layer's scrim to choose it, or "none" to turn it off.` The card surface shows while a picture loads or fails. A missing or invalid template name uses `default`, or is skipped silently at runtime.

**the later review, decided (2026-11-27).** These replace earlier wording where they conflict.

1. **Transport.** The private-network check is http-only. Normal https with a public certificate is allowed on any resolved IP, including `10.x` and `100.64/10`. For http, the check is on the resolved address (private, link-local, loopback, CGNAT `100.64/10`) at connect time. RW-11 is optional and does not block M2. No "private IP denied" warning for https.
2. **Layers, simplified, then the sticker controls.** A picture is an ordinary `picture` node. The scrim is a property on it, not a layer kind. `src` is a filename template, with optional `default`. Then the per-layer controls: `fit` including `fixed` + `width` 0.1–1.5, `align`, `offset` ±0.25, `opacity`. `overflow: true` draws up to 25% past any edge, decorative, no taps, clipped to the page, never into system-bar insets, **beneath neighbouring cards**. Without overflow, clip to the card's rounded shape. `scrim` is `"auto"` / `"none"` / object. Chrome (title row and stale ▲) always draws above all layers; a layer above content stays capped at 60% opacity.
3. **Secret placement.** `auth: [{secret, as: bearer|header|query, name?}]`. Each secret stays bound to one origin. A query param is added only when declared, redacted everywhere on the never-reach list, never stored in `url`. Load error if that query name is already in the url, or if the header is `Host`, `Content-Length`, `Cookie`, `Cookie2`, or another reserved hop-by-hop name. The HA recipe is `as: "bearer"` with secret `ha`. This meets the idea of variables injected into URLs and headers. RW-7b stays.
4. **Agent docs.** Recommend a separate non-admin HA user for the phone's long-lived token, with the one-line how-to in RW-6.
5. **Data-chosen links.** `{data:blog.url}` in `ui.openUrl` is allowed. Q-D5 applies to the resolved URL at tap time. Silent only when the host matches `source`; otherwise confirm every tap, with Always allow. A file-only card with no `source` confirms every templated link unless Always allow is already ticked. Validate at tap: https or http, no userinfo, must parse; on failure use `warn_tap_scheme` / `warn_tap_userinfo` / `warn_tap_host`. A link template can't reference a secret. Links are not limited to the `source` origin, because file-only cards have no `source` and the per-tap confirm already covers other hosts.
6. **Atomic reads.** The agent writes temp-then-rename. The reader ignores names starting with `.` or ending in `.tmp` or `.part`. An unparseable, empty or mid-sync data file keeps the last-good snapshot and its as-of time, and warns once per file until it parses again. A picture decode failure keeps the last good bitmap. The M1 example uses `w-usage.json`, because `sensor.example_status` does not exist yet. The agent kit includes the write rule.
7. **Sticker overflow and ticks.** The sticker is a file-only picture layer between the surface and content, with `fit: "fixed"`, `width` about 0.4, `align: "bottom"`, a positive `y` offset, `overflow: true`, `opacity` about 0.8, `scrim: "none"`, and `src: "sticker-{data:state}.webp"` plus a `default`. Progress bars in the example are `style: "ticks"`; `segments` keeps its cap of 20; `count` is 10–120, default auto-fit.

**Ordering notes kept** (§10): `Restore previous layout` before the M1 announcement; folder editing sequenced with element edits.

**Calls I made that nobody specified** (each is flagged where it appears):
- Pictures stay in the card's own folder, `widgets-data/<id>/`, not a shared pictures area (§4.4).
- The inline `image` node is cut. A `picture` layer replaces it (§4.1).
- `{fetch:…}` is also allowed in a picture `src` template and in a `ui.openUrl` template, matching `{data:…}` (§4.4, §5.1).
- `src` / `default` are capped at 80 characters; `auth` is capped at 4 entries; query names match `^[A-Za-z][A-Za-z0-9_-]{0,39}$` (§4.4, §5).
- For `fit: "fixed"` with no `width`, width defaults to `1`. Out-of-range `width` / `offset` are clamped with a warning. **+y** is toward the bottom of the card. A `count` below 10 is raised to 10 with a warning (§4.2, §4.4).
- IPv6 unique-local (`fc00::/7`) counts as private for the http check. Well-known cloud-metadata addresses `169.254.169.254` and `fd00:ec2::254` are refused even though they are link-local (§6.5).
- The last-good snapshot of an M1 data file lives in app-private `filesDir/cards/`, the same place as a fetch snapshot (§4.6).
- `secrets.import.json` has three buttons, including `Delete file without importing`, and is only looked for on load, `RELOAD` and resume (§6.6).
- Self-signed pins, when RW-11 lands, are allowed for any approved origin (§6.5).

Defaults applied without asking: weather and calendar come from agent files or URL sources, not built-in fetchers. No media widget. Digital clocks only. No phone-side style editor.

**Still pending, owned by Pragma:** the `display` tier's sp values (§4.3, RW-2), and the final copy of the secret entry, http acknowledgement, fingerprint and import prompts.

## 14. Follow-ups outside this PR

- [dynamic-widgets.md](dynamic-widgets.md) carries an "amended" note and the wording fixes in this PR (levels, secret retention, `auth` placement, and the https/http private-address rule). A fuller rewrite of its §3, §4, §8 and §9 to match §3's table here can wait until the design ships.
- ux.md §17.9 (the header sentence) and §17.8 rule 6 (pointer to layers) are updated in this PR. Still for Pragma: §16.1 (first-party group), §16.6, §16.7.3 (`text`/`clock` rows), the `display` tier in §17.8, the Settings screen and menu rule of §13, and **§17.1** (ambient `{battery}` / `{nextAlarm[:p]}` tokens are not Phone-data prompts).
- [native-widgets.md](native-widgets.md) §2.1 now uses the merge-order wording of C9.
- [layout-overrides.md](layout-overrides.md) and ARCHITECTURE.md §19 use `FL_WINDOW_OVERRIDES`, `FL_CARDS_READOUT` and `FL_FETCH`, with no reserved 6 or 7.
- research/widgets.md §5.5 points here, and the old `text`/`clock` sketch is marked historical.
- Native-widgets milestone (#298–#305) is unaffected. 1.1.0 (#305) still comes first.

## 15. Sources

Launcher research: Niagara help pages, Lawnchair docs and issue #6322, Kvaesitso docs, Smart Launcher blog and docs, linked in §1.1. Authoring models: Cocoon repo (read locally at `dd11dc7`), Kustom docs and forum, Home Assistant dashboard and REST API docs, HA Companion Android widget docs and forum, linked in §1.2. Android: [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager), [AlarmManager](https://developer.android.com/reference/android/app/AlarmManager), [ConnectivityManager](https://developer.android.com/reference/android/net/ConnectivityManager). Repo: `config/Elements.kt`, `config/Card.kt`, `config/Template.kt`, `consent/Consent.kt`, `widgetdata/*`, `docs/ux.md` §16–§17, `docs/design/dynamic-widgets.md`, `docs/design/native-widgets.md`, `docs/design/title-bar.md`.
