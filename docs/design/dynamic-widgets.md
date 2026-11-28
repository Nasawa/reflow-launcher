# Dynamic widgets: AI-assisted dashboard cards

> **Amended 2026-11-27 by [reflow-widgets.md](reflow-widgets.md).** Readout widgets are an evolution of these cards, not a parallel system. Follow-ups on this document: `net.fetch` is pulled forward as `data.sources[]` (M2); secrets are generic named secrets declared in a source's `auth` (`bearer`, a named header, or a named query param); **keeping a secret after its last widget leaves the config is an intentional change from the earlier design** (§3 and the row in §6 deleted it); plain http is limited to LAN addresses checked at connect time (IPv4-mapped `::ffff:a.b.c.d` and NAT64 `64:ff9b::/96` are classified as the embedded IPv4), fetches always connect directly (`Proxy.NO_PROXY`; a proxy-only network is not supported in M2), redirects follow at most 3 hops with the transport check on every hop and `https`→`http` always refused, while https with a publicly trusted certificate is allowed whatever IP it resolves to, including a private or CGNAT address; self-signed https is optional (reflow-widgets.md §6.5); and feature levels are symbolic and fixed in merge order, not "likely 6 / 7". A fuller rewrite of §3, §4, §8 and §9 can wait until the design ships. Where this document and reflow-widgets.md differ, reflow-widgets.md wins.

**Status:** on main via [#117](https://github.com/Nasawa/reflow-launcher/pull/117) (`971c2eb`); Archimedes follow-ups below.  
**Christopher settled Q-C1–C5, Q-C7** (2026-10-19); **Q-C6 already decided**.  
**Q-A9 (Pragma #116 / agent review):** http allowed when approved by name; approval ceiling = cumulative union. **An outside review folded in** (PR #117): structural `data.file`, tap example, Q-C7/Q-C3, `ui.reload` + relative time, narrowing ceiling model. *(The exact-host `ui.openUrl.hosts` allowlist from #117 is superseded by Q-D5 below.)*  
**Q-D5 settled (Christopher via Pragma, 2026-10-19):** optional card-level **`source`**; capability `ui.openUrl` is **bare** (no `hosts`); taps to `source` open **silently**; taps anywhere else **confirm every time** with **Always allow this site** (device-local, per element id). `source` is part of the capability fingerprint. Supersedes the hosts-based `ui.openUrl` design.  
**Archimedes (this revision):** **Turn off** keeps the approval ceiling; **Forget approval** (widget detail) clears it; stage-5 TOFU **certificate changed** pauses `net.fetch` and shows last-good data stale.  
**Pragma S3-C:** consent strings settled at [#116](https://github.com/Nasawa/reflow-launcher/pull/116) tip / §17.13 — Sprite may build from that branch. C3 http disclosure + C4 hide-author applied.

**Opened with:** product reframe from Christopher (via Grok Bot), screenshots in `/workspace/cl-input/` (not committed), Pragma UX [#116](https://github.com/Nasawa/reflow-launcher/pull/116) (`docs/ux.md` §17), schema answers Q-A1–Q-A8 on that PR.  
**Related:** [docs/research/widgets.md](../research/widgets.md) (updated by this PR where superseded), [docs/design/title-bar.md](title-bar.md), elements container (#112, merged), Stage 1 cell reservation.

## 0. Product reframe

Reflow Launcher's end vision is: **make your home screen into an AI-assisted dashboard.**

Apps, folders, and direct user control stay. Widgets come in two kinds; **(b) is the core**:

| Kind | Who renders | Who composes | Data |
|---|---|---|---|
| **(a) Hosted AppWidget** | Third-party app (HA companion, KWGT, …) via `AppWidgetHost` | That app's UI | That app's own network/storage |
| **(b) Dynamic config widget** | **This launcher** (Compose) | An AI (or human) writing `launcher.json` | Local data files and/or opt-in HTTPS fetch the launcher runs **on the widget's behalf** |

### Reference inputs (research only)

1. **A status card (target).** A sample status card: title "Example Co", `as of 08:38`, refresh affordance, latest-post tile, status chips (`up`, `All up`), unhealthy-monitors list. Full-width on a 5-column home grid.
2. **Absolute layout (anti-example).** A hand-built absolute layout; **sizing broke on a tablet**. Absolute px/sp inside the card is forbidden. (This is the class of layout a tool such as KWGT makes easy.)
3. **A scripted-bridge anti-example.** Every widget got a full JS bridge (SMS, contacts, SSH, ~40 permissions) with **no per-widget capability list**. We must not repeat that: **no scripts, no code execution, no shared bridge.**

## 1. Declarative component vocabulary (no scripts, ever)

Dynamic widgets are type `card` (name TBD at schema time; UX §17 says `card`) in `elements[]`.

### Non-goals (hard)

- No JavaScript, Lua, expressions-as-code, WebView, HTML, or downloadable script.
- No `window.*` bridge, reflection, or "plugin APK".
- No coordinates (`x`/`y`/dp/px/%) **inside** a widget (UX §17.8). Placement is only the element's grid `page,x,y,w,h`.
- No arbitrary remote images in v1. If images arrive later: no SVG, with byte and dimension caps.

### Layout (flow only)

Containers: `column`, `row` (children may have `weight`), `tile` (inset sub-surface).

Responsive: up to 3 `layouts` keyed by `"WxH"` with a **lowercase** `x` (e.g. `"4x3"`, not `"4X3"`; pattern `^[1-9][0-9]*x[1-9][0-9]*$`). Launcher picks the **largest that fits**; else smallest + drop by `priority` (0–9, low first) + ellipsis. Optional `showIn` size-class filters. Optional `minSpan: { "w", "h" }` (amber outline if undersized; still shows — §16.5).

### Primitives (v1)

| type | Role |
|---|---|
| `text` | Template string; `role` caption/body/title; `maxLines` |
| `metric` | Label + value (+ unit) + optional `status` dot |
| `list` | Rows from a data **array**; leading dot/icon, primary, trailing |
| `icon` | Named Material symbol or installed app icon (no bitmap-with-text) |
| `progress` | 0–1 bar (or ring at 1×1) |
| `button` | Label; runs a declared action (`ui.openUrl`, `ui.openApp`, or `ui.reload`) |

**Card chrome (automatic):** title + `as of` from data `updatedAt`; `stale` after `staleAfter`.

### Templating and binding

- Reuse title-bar token grammar where useful (`{time:…}`, `{date:…}`, `{page}`, …).
- **Relative time (cards):** token name `{relative}` (optional path `{relative:data.updatedAt}`; default = data file root `updatedAt`). Renders **`5 minutes ago` / `just now`** only — **no pattern** argument. Closed formatter, no scripts. Title bar stays absolute-only in v1.
- Data: `{data:path}` with a **strict JSON-path subset** (dot keys + `[index]`). Nothing is evaluated.
- Missing path → `—` (never throw). Lists require arrays.

## 2. Cell-relative sizing

- Grid placement stays Stage 1 (`page,x,y,w,h`); skipped grid elements **still reserve cells** (#112).
- Inside the card, tokens scale from cell size: `k = clamp(cell/80, 0.85, 1.5)` (UX §17.8). Text tiers by span class, not by "phone vs tablet" device id.
- Same `launcher.json` on a tablet: larger cells → larger `k`, more `list auto` rows, next `layouts` entry when span grows. No second layout language.

## 3. Data model

### Local (v1 of dynamic widgets)

- **One file per widget, structurally:** always `.configlauncher/widgets-data/<elementId>.json`. The path is **derived from the element `id`** — there is no free `path` field. Capability is `{ "kind": "data.file" }` only.
- Why: prevents cross-widget reads by construction (confused deputy); agents cannot point card A at card B's file.
- Size cap (proposal): 256 KiB per file; UTF-8 JSON only.
- Agent writes → phone polls / reload (same spirit as `launcher.json`; exact interval later).
- Root may include `updatedAt` (ISO-8601). Element may set `staleAfter` (ISO-8601 duration).

### Opt-in HTTPS fetch (stage after local)

- Capability `net.fetch` with **exact origin** pins (`https://host[:port]`).
- Launcher fetches; widget never sees a socket.
- **No cookies**, no shared `CookieJar`, no HTTP cache keying across element ids.
- Caps: response bytes, timeout, min refresh interval.
- **HTTP client:** one isolated client per element id (no shared cache, no cookies, fixed `User-Agent` naming Reflow Launcher).
- **Redirects (reflow-widgets.md §6.5):** follow at most **3** hops. Re-run the transport check (scheme, and for http the LAN rule on the resolved and connected address) on **every** hop. An `https`→`http` hop is always refused. A hop whose origin is not in the card's approved list fails. Header and query secrets are re-attached only on a same-origin hop and are dropped on any other.
- **`net.fetch` request URLs are literals** in the element (fixed under a pinned origin). They must **not** be built from `{data:…}`.
- **`ui.openUrl` tap `url` MAY use `{data:…}`** (Outer Shell needs this for "latest post"). At tap time resolve the template, reject `userinfo` / non-http(s) schemes (including `intent:`, `javascript:`, `file:`, `content:`, `android-app:`, `data:`, and scheme-relative `//host`) with `warn_tap_scheme`, normalise scheme + host (IDNA/punycode/lowercase), open only as `Intent(ACTION_VIEW, uri)` with `CATEGORY_BROWSABLE` (never `Intent.parseUri`), then (Q-D5): if scheme+host **exactly** matches the card's `source` (no subdomain inference), open **silently**; if it matches a device-local **Always allow** entry for this element, open silently; otherwise show a **per-tap confirm** naming the destination (with **Always allow this site**). There is **no `hosts` allowlist** on the capability. See §4 *card.source and ui.openUrl taps*. `ui.openUrl` only hands the URL to the browser/intent — it is **not** a fetch.
- **Connect-time IP check (http only, reflow-widgets.md §6.5):** the private-network check applies only to plain http, never to https. For http, check the **resolved / connected address** at connect time (RFC1918, link-local, loopback, ULA, and CGNAT `100.64/10`). An IPv4-mapped address (`::ffff:a.b.c.d`) and a NAT64 address in `64:ff9b::/96` are classified as the **embedded IPv4**, on both the allow and deny sides. NAT64 is accepted so that IPv6-only home networks work, and the embedded IPv4 is what gets classified. Fetches always connect directly with `Proxy.NO_PROXY` and ignore the system and Wi-Fi proxy, so the LAN check sees the real peer. A proxy-only network is not supported in M2: a direct-connect failure is `warn_fetch_unreachable` (`Can't reach <origin>. Reflow connects directly and doesn't use network proxies.`), keeps last-good data, follows the normal backoff, and never falls back to the proxy. `169.254.169.254` and `fd00:ec2::254` (and their mapped and NAT64 forms, including `64:ff9b::a9fe:a9fe`) are always refused, on both http and https, even though link-local is otherwise allowed for LAN http. A public http address is refused. **HTTPS** with a publicly trusted certificate is allowed whatever IP it resolves to, including a private or CGNAT address (the always-denied metadata addresses above are the exception); no warning may say "private IP denied" for https. **DNS rebinding:** re-check the connected IP on each http request, not only at approval time.
- **Cleartext / LAN (decided — Q-C3 / Q-C7, Christopher 2026-10-19):** users own what they knowingly approve. `http://` is allowed when the user knowingly approves it by name — an `http://…` card `source`, an **Always allow** for an `http://` site, or an `http://…` `net.fetch` origin — show `http://` in the prompt; **no silent https→http downgrade**. Disclosure / consent copy must say that **http is less secure**, that some local setups make https difficult for little gain, and that **the user is responsible for what they approve**. Self-signed: **TOFU certificate pinning per origin** (pin on first approve / explicit re-pin if the cert changes; stern copy about remembering the pin) — not disabling TLS validation. **Certificate changed (stage 5):** while a re-pin / "certificate changed" prompt is unanswered, **pause `net.fetch`** for that origin (and that widget's fetches that need it); keep showing **last-good data marked stale**. **Never** silently accept the new cert. An http origin still needs the named approval and the "less secure" disclosure. Agent-written local JSON remains the simplest LAN path. Self-signed https pinning (RW-11) is optional and does not block M2.
- **Secrets:** never in synced files. Device Keystore / encrypted prefs, entered on phone, scoped to `(elementId, origin)`. Agents declare that a secret slot exists; user fills it. **When the pinned origin set changes, delete secrets for origins that were removed** before any new fetch runs. **Amended 2026-11-27 (reflow-widgets.md §6):** secrets are named, keyed by `(name, canonical origin)` and shared across widgets, and are **kept** after the last widget leaves the file unless the user chooses Forget secret. A source declares placement in `auth`; the value is injected at request time and is never stored in the `url`.

### What stays out of the synced folder

Approvals, widget instance bindings (AppWidget ids), Keystore secrets, last-good render cache.

## 4. Capability and consent model

Normative kinds (Archimedes; supersede §17.11 sample names):

| kind | targets | UX tier | v1? |
|---|---|---|---|
| `data.file` | *(none — file is always `widgets-data/<elementId>.json`)* | Show | yes |
| `ui.reload` | *(none)* — tap re-reads this widget's data file | Show | yes |
| `ui.openApp` | package name(s) | Open | yes |
| `ui.openUrl` | *(none — destination via card `source` + device-local Always allow)* | Open (consent for the kind; off-source taps confirm — Q-D5) | yes |
| `net.fetch` | exact HTTPS origin(s) | Internet | later stage |
| `phone.*` | — | Phone data | **not in v1** |
| Act kinds | — | Act | **not in v1** (buttons = Open only) |



### Capability field names (normative)

Prefer **kind-specific keys**, not a generic `targets` array (easier for schema and agents):

| kind | Fields |
|---|---|
| `data.file` | *(no fields)* — path derived from element `id` |
| `ui.reload` | *(no fields)* |
| `ui.openApp` | `packages` (string array) |
| `ui.openUrl` | *(no fields)* — `{ "kind": "ui.openUrl" }` only; **no `hosts`** (Q-D5) |
| `net.fetch` | `origins` (exact `https://host[:port]` or `http://host[:port]`) |

**Card-level `source`** (optional string, on the card element — **not** inside `capabilities[]`): the widget's origin, either scheme-qualified (`https://status.example.com`, `http://nas.example`) or a bare host (= `https://`). It decides which `ui.openUrl` taps open silently. It is **not** a fetch origin and does **not** imply `net.fetch`.

Fingerprint serializes `kind` + those fields (sorted), plus the canonical `source` (see *Fingerprint and prompts*). Pragma's illustrative `targets` is **not** the schema shape.


### `card.source` and `ui.openUrl` taps (Q-D5)

Decided by Christopher (via Pragma, 2026-10-19). Replaces the #117 `ui.openUrl.hosts` allowlist.

**Normalising `source`:**

| Form of `source` | Canonical |
|---|---|
| bare host (`status.example.com`) | `https://status.example.com` |
| scheme-qualified (`https://nas.example`, `http://nas.example`) | that **scheme + host** |

- Unicode → lowercase / **punycode** (IDNA). v1: host only, default ports (no path, query, or `userinfo` in `source`; reject if present).
- **No implied subdomains:** `example.com` ≠ `status.example.com`.
- **No silent https→http downgrade:** an `https://` (or bare) `source` never silently opens `http://`. An `http://` `source` is shown with **`http://` visible** in consent and carries the Q-C3/C7 http disclosure.
- Default for agents: prefer scheme-qualified `https://…` (or bare). Use `http://…` only when cleartext is knowingly needed (homelab).

**At tap time** (after the card is allowed for `ui.openUrl`):

1. Resolve `{data:…}` in `url`; reject `userinfo` and any scheme other than `http`/`https` → snackbar + `warn_tap_scheme`.
2. Normalise scheme + host as above.
3. **Matches `source` exactly** → open **silently** (no confirm).
4. **Matches this element's Always-allow list** → open silently.
5. **Anything else** → **confirm on every tap**, naming the full scheme + host (with `http://` visible and the http disclosure when cleartext). Buttons: **Open**, **Cancel**, and **Always allow this site**.
6. Cancel / unresolvable / malformed URL → no open; snackbar + `warn_tap_host` as needed.

**Always allow this site:** stores the canonical scheme + host in a **device-local** list keyed by **element id**. Phone-only — **never synced**, never written to the folder or `status.json`. It is **separate** from the approval ceiling (not a `(kind, target)` ceiling line). Cleared by **Forget approval** for that widget and GC'd when the element leaves the file. Exact match only (no subdomains, no scheme upgrade/downgrade).

`ui.openUrl` only hands the URL to the browser / an intent. Neither `source` nor Always-allow grants or implies `net.fetch`; stage 3 (local data) still performs **no network fetch**.

`net.fetch` `origins` are always full origins (`https://host[:port]` or `http://host[:port]`) and follow the same approve-by-name / no-downgrade / http-disclosure rules (plus TOFU for self-signed — §3).

**Tap actions** on a node: `{ "kind": "ui.openUrl", "url": "…" }` or `{ "kind": "ui.openApp", "package": "…" }`. The element must declare the matching capability (`ui.openUrl` / `ui.openApp` with that package). For `ui.openUrl` the destination rule is the Q-D5 source / confirm model above, not a capability host list.

**Open-tier split (Pragma #116):** `ui.openApp` stays **silent** (installing an app is already a user act). `ui.openUrl` (bare kind) still participates in consent and the approval ceiling **as a kind**: a card that declares it goes through consent on first enable like any new ceiling line. Once allowed, taps to the card's `source` are silent; off-source taps get the **per-tap confirm** + **Always allow this site** (phishing defence lives at tap time, not in a host list). **Q-D5 settled** (Christopher via Pragma, 2026-10-19). Show-tier `data.file` and `ui.reload` stay silent.
**Never offered** (absent from schema entirely): SMS, calls, contacts, messages, mic, camera, location, VPN/tunnels, run code.

### Fingerprint and prompts

- Fingerprint = canonical sorted **capabilities + their fields**, plus the canonical card **`source`** (Q-D5) — not layout/style/`author`/span. Changing `source` (add, remove, or different scheme/host) is a fingerprint change → `needsApprovalAgain` / re-prompt like any other broaden. Device-local Always-allow entries are **not** part of the fingerprint.
- File load **never** opens a dialog (Pragma §17.2). Broadened → dashed consent tile `Needs your OK` / `Needs your OK again`, content not rendered, cells kept.
- See **Approval ceiling** below (union set; never shrinks).
- Cosmetic / layout / data-shape changes with capabilities unchanged → **stay live, no prompt, no info dot**. A quiet history entry only (Pragma §17 / agent review #3).

### Approval ceiling (agent review #3 / Q-A9) — one set per widget, never shrinks

The device stores one **approved capability set** per element id: a union of every `(kind, target)` line the user has ever approved for that widget on this phone.

- Approvals at **different times combine** (union). Approving bare `ui.openUrl` (card `source` `https://status.example.com`) today and `data.file` tomorrow yields both in the ceiling. The approved `source` is recorded with the ceiling; a different `source` later is outside it and re-prompts. Per-site **Always allow** taps are a **separate** device-local store, not ceiling lines.
- The stored set **never shrinks** on file edits (narrowing does not delete lines).
- **File is live with no prompt** iff every declared `(kind, target)` in the file is ∈ the stored set (file ⊆ ceiling).
- **Anything outside the ceiling** → prompt only for the **new** lines (`needsApproval` / `needsApprovalAgain`); on approve, union them into the ceiling.
- **Turn off** is a sticky device-local switch: the widget stays off and **keeps** the approval ceiling. **Allow** turns it back on with the same set (no re-prompt if the file is still ⊆ ceiling).
- **Forget approval** is a separate action on the widget **detail** page (Widget permissions → detail): it **clears** the approval ceiling; the widget stays **off**; the next **Allow** is a **fresh** full prompt. (Aligns with Pragma #116 — there is no single "Forget" on the list row; Turn off alone does not clear the set.)
- Approvals are also **GC'd** when the element leaves the file (unchanged).

**Exception — Phone data + Internet combo:** even if `phone.*` and `net.fetch` were each approved alone earlier, the first time a widget declares **both**, show the combination prompt once (Pragma §17.1). After that combo approval, the pair is in the ceiling.

This replaces a single “fingerprint equality / subset of last fingerprint” model: the ceiling is cumulative across prompts.

- Approvals **device-local**, keyed by `elementId + capabilityFingerprint`.
- Optional / **recommended** `author` string (≤40): quoted in UI (`says "agent"`), **never** trusted for auto-approve (Q-C4). If omitted, **hide** the author chrome — do not crash and do not show empty quote UI.

### `status.json`

Consent map shape (per element id). **Never** store the approval secret / ceiling in `status.json` — only a display state agents can read:

```json
"consent": {
  "<elementId>": {
    "state": "needsApproval" | "needsApprovalAgain" | "off" | "allowed",
    "warning": "optional §15.1-style sentence"
  }
}
```

Normative warning **name keys** (English sentences built in code; Pragma §17.13): `warn_consent_pending`, `warn_consent_again`, `warn_consent_off`, `warn_data_missing` (missing/unreadable `widgets-data/<id>.json` — **not** a consent state). Capability-skip and tap failures reuse the same status channel as elsewhere (`warn_capability_kind`; tap `warn_tap_host` / `warn_tap_scheme` already named above).

Enforcement: runtime can only perform APIs listed in the **approved** manifest for that id. Unknown `capabilities[].kind` on this launcher → **skip that element** with a warning (consent tile / "Update Reflow Launcher" or "Can't show", cells kept; `warn_capability_kind`) — **not** a whole-file B2 reject. Writers of a new kind MUST bump the feature level and set `requires` on every element that uses it so older phones hit the requires-skip path cleanly. `validate.py` / CI at the current feature level may still treat unknown kinds as errors so agents fail fast on typos. Undeclared use at runtime is impossible by construction.

### Hosted AppWidgets

**Outside this model** (Pragma A5 / Q-A8). Android bind/configure only; review list shows them as "Allowed by Android".

## 5. INTERNET and ARCHITECTURE §1

Today §1: **No INTERNET** — launcher never fetches; security property against malicious config exfiltration.

**Decided (Q-C1, Christopher 2026-10-19):** add `INTERNET` to the APK for **stage 5** (fetch runtime) only. He assumes it is necessary for `net.fetch`. Real control remains per-widget `net.fetch` approval (origin pins, no cookies, private-IP deny by default). Isolate fetch behind a feature flag / `requires`.

| Path | Needs our INTERNET? |
|---|---|
| Hosted AppWidgets (HA, KWGT) | No — those apps use their own |
| Dynamic widget + `data.file` only | No |
| Dynamic widget + approved `net.fetch` | Yes — launcher GET/HEAD to pinned origins |

Plain language for users/docs: *Reflow Launcher can use the network only for dashboard cards you explicitly allow, and only to the sites listed on that card. It still does not browse the web or sync your config for you.*

APK-level `INTERNET` is coarse (see §7). Capability approval is the real control plane; OS permission is the on/off master.

## 6. Threat model and mitigations

| Threat | Mitigation |
|---|---|
| Compromised agent / sync pushes hostile card | Consent tile before first render; never auto-approve; `author` not trusted |
| Exfiltration via fetch URL (path/query) | Origin pin; optional path allowlist later; show full URL in prompt; re-prompt on new origin |
| Tracking pixels / beacon `icon` URLs | v1: no arbitrary image URLs; icons = Material / installed app only |
| **SSRF to LAN** (homelab monitors) | Default **deny** RFC1918, link-local, metadata IPs for `net.fetch` unless user enables a stern "allow private origin" pin per origin; document that agent-written **local** JSON is the preferred path for LAN data |
| **DNS rebinding** (public name → LAN IP later) | Re-validate the connected peer IP on **every** connection, not only when the origin was approved |
| **TOFU cert change / MITM** (stage 5) | Pin on approve; on change show re-pin prompt — **pause `net.fetch`** for that origin until answered; show last-good data **stale**; never silently accept the new cert |
| **Stale secret after origin edit** | **Amended 2026-11-27:** the fetch binding and snapshot are dropped, but the secret is **kept** until the user taps Forget secret (reflow-widgets.md §6.4). It still can't be sent to another origin, because it is bound to `(name, origin)`. *(Original rule: delete Keystore entries for removed origins before the next fetch.)* |
| Cross-widget data read | File path derived from element id only — no `path` field to misuse |
| Secret leakage across widgets | Keystore entries keyed by element id + origin |
| OpenURL phishing | Card `source` in fingerprint (consent + re-prompt on change); silent open only for exact `source` match; every off-source tap confirms showing the destination, with device-local **Always allow this site**; no `userinfo`, http(s) only |
| Refresh DoS against homelab | Min interval + jitter; cap concurrent fetches |
| iappyx-style over-privilege | Closed enum; never-offered absent; no script host |

## 7. Challenges / pushback on the speculative direction

Honest holes in "local files + opt-in URL fetch + per-widget manifest":

1. **APK `INTERNET` is all-or-nothing.** Once granted, a launcher bug or future capability mistake is higher stakes. Mitigation: keep fetch code tiny, origin-pin in one module, default deny private IPs, feature-flag fetch behind `requires`.
2. **Path/query on taps:** `{data:…}` openUrl is checked at tap time against `source` / Always-allow, else per-tap confirm (Q-D5, §3–§4). Path/query are not pinned — the confirm shows the destination host. Fetch URLs stay literal. Secret-bearing fetches should still avoid query strings; path prefix pins remain a later option.
3. **LAN allow prompts will be tempting to click through** for homelab users. Prefer agent → local file for Home Assistant or status-page data; treat private `net.fetch` as advanced.
4. **Shared refresh schedules** can still DDoS a Pi. Enforce global and per-origin rate limits, not only per-widget.
5. **Approvals don't sync** (correct for security) → every tablet re-consents (Pragma). Accept the friction.
6. **"Phone data" tier** without v1 kinds: keep the UX tier for later; don't invent battery/SMS "just in case."
7. **System notifications need `POST_NOTIFICATIONS` on Android 13+.** Prefer launcher-only signals for v1 (B9 + ⋮ badge + consent tile). A system notification is optional later and needs its own permission prompt.
8. **Homelab cleartext / self-signed:** follow §3 (decided Q-C3/C7: `http://` visible in prompt; TOFU pins; disclosure that http is less secure and the user owns the choice). On cert change, pause fetch + show stale last-good until re-pin (never silent accept). Still easy to click through — keep copy stern.
9. **`widgets-data/` git policy:** agents may want these files synced; they can contain semi-sensitive status text. Document in README whether to commit or gitignore (proposal: commit is OK if no secrets; never put tokens there).

## 8. HA Q-C5 (decided, Christopher 2026-10-19)

| Approach | Role |
|---|---|
| Host HA companion **AppWidgets** | Kind (a) — unchanged, no our INTERNET |
| Agent writes HA-shaped JSON into `widgets-data/<id>.json` | Kind (b) v1 — **recommended** for Outer Shell–class cards |
| `net.fetch` to HA URL + Keystore token | Kind (b) later / **advanced** — pinned origin, stern private-IP rules |

**Confirmed:** companion AppWidgets + agent-written local JSON are enough for v1 of kind (b). HA must **not** be tightly coupled to the launcher. Building your own HA-reading card (agent JSON or later `net.fetch`) is an advanced usage. Distant future: optional first-class widgets shipped in the app is OK as a far goal, not v1. Still **against** a built-in HA API client with broad rights. the agent (or any agent) owns the integration glue; the launcher owns safe display + consent.

## 9. Staged plan (schemaVersion 1 throughout)

| Stage | What | Feature level (proposal) |
|---|---|---|
| 0 | Elements container + Stage 1 reservation | 1 — **done (#112)** |
| 1 | Title bar type | 2 |
| 2 | `appWidget` host + tap-to-bind | **5** (3 is retired, never used; native-widgets.md §2.1, #298) |
| 3 | `card` + local `data.file` + consent UX (§17) | 4 |
| 4 | Richer primitives if needed (parity with Outer Shell) | 4 |
| 5 | `net.fetch` + Keystore + INTERNET permission | next free level in merge order (`FL_FETCH` in reflow-widgets.md; `appWidget` took 5, and `windowOverrides` takes its own symbolic level) |
| 6 | Optional path pins, private-origin allow, phone.* | later |

**Hard gates:** Stage 0 before any skip of grid elements (met). Consent tile before any `card` renders data. Local data before fetch (**Q-C2 decided:** ship stage 3 local-data-only first; fetch is a later release). Feature levels are fixed in merge order (native-widgets.md §2.1): `appWidget` took **5** (#298), `windowOverrides` (#186) and `net.fetch` take the next free numbers in merge order when they land, named `FL_WINDOW_OVERRIDES` and `FL_FETCH` (reflow-widgets.md C9); none is reserved.

## 10. Open questions

### Product answers — product answers landed (2026-10-19)

- **Q-C1:** *(decided)* **Yes** — APK `INTERNET` for stage 5, gated by per-widget `net.fetch` approval; assumed necessary for fetch.
- **Q-C2:** *(decided)* **Yes** — ship dynamic widgets **local-data-only** first (stage 3); fetch in a later release.
- **Q-C3:** *(decided)* **Yes** — users own what they knowingly approve; explicit per-origin allow including private/`http://`; clear prompt labelling. Consent/disclosure copy must note that http is less secure, that some local setups make https difficult for little gain, and that the user is responsible for their approvals.
- **Q-C4:** *(decided)* **Keep** `author` as optional/recommended (cheap quote signal). If omitted, **hide** it — do not crash or show empty chrome.
- **Q-C5:** *(decided)* **Yes** — companion AppWidgets + agent-written local JSON are enough for v1 of (b). No tight HA coupling; own HA-reading widgets are advanced. Distant optional first-class in-app widgets OK as a far goal, not v1.
- **Q-C6:** *(decided)* Show-tier `ui.reload`; token `{relative}` → `5 minutes ago` / `just now`, no pattern. Pragma OK.
- **Q-C7:** *(decided)* Scheme-qualified `http://` when approved by name — a card `source`, an Always-allow site, or a `net.fetch` origin (the `ui.openUrl.hosts` form is superseded by Q-D5); **TOFU** for self-signed (stern pin-on-approve / re-pin-on-change copy); no silent downgrade. **Archimedes:** while the re-pin prompt is unanswered, pause `net.fetch` for that origin and show last-good data stale.
- **Q-D5:** *(decided — Christopher via Pragma, 2026-10-19)* Optional card **`source`**; `ui.openUrl` is **bare** (`hosts` dropped); taps matching `source` open **silently**; any other site **confirms every tap** with **Always allow this site** (device-local per element id, not synced); `source` is in the capability fingerprint; `source` is not fetch and does not imply `net.fetch`.

### For Pragma

See answers on [#116](https://github.com/Nasawa/reflow-launcher/pull/116#issuecomment-6270653131) (Q-A1–Q-A8). Please align §17.11 sample kinds to `data.file` / `ui.openApp` / `ui.openUrl` / `net.fetch` and `author` instead of `x-by`.

### For Sprite

- Codec + validate.py for `card`, capabilities enum, layouts, consent status fields.
- Consent store device-local; never write approvals into the folder.
- Fetch module isolation when stage 5 lands.

## 11. Worked example (normative kinds)

```json
{
  "id": "w-status",
  "type": "card",
  "requires": 4,
  "page": "home", "x": 0, "y": 4, "w": 4, "h": 3,
  "minSpan": { "w": 3, "h": 2 },
  "title": "Example Co",
  "author": "agent",
  "surface": "auto",
  "source": "https://status.example.com",
  "capabilities": [
    { "kind": "data.file" },
    { "kind": "ui.openUrl" }
  ],
  "data": { "staleAfter": "PT30M" },
  "layouts": {
    "2x1": [
      { "type": "metric", "label": "Monitors", "value": "{data:monitors.summary}", "status": "{data:monitors.status}" }
    ],
    "4x3": [
      { "type": "tile", "children": [
        { "type": "text", "role": "caption", "text": "Latest on status.example.com" },
        { "type": "text", "role": "body", "text": "{data:blog.title}", "maxLines": 2 }
      ], "tap": { "kind": "ui.openUrl", "url": "{data:blog.url}" } },
      { "type": "row", "children": [
        { "type": "metric", "label": "Site A", "value": "{data:site_a.value}", "status": "{data:site_a.status}" },
        { "type": "metric", "label": "Site B", "value": "{data:site_b.value}", "status": "{data:site_b.status}", "priority": 4 },
        { "type": "metric", "label": "Monitors", "value": "{data:monitors.summary}", "status": "{data:monitors.status}" }
      ] },
      { "type": "list", "label": "Unhealthy monitors", "items": "{data:unhealthy}", "maxItems": "auto", "emptyText": "All monitors up" }
    ]
  }
}
```

Data file: `.configlauncher/widgets-data/w-status.json` (agent-written). First load prompts because the card declares `ui.openUrl` (consent for that kind, showing `source` `https://status.example.com`); Show-only cards with just `data.file` go live with no prompt. The sample tap uses `{data:blog.url}` only (no redundant `host` field): if it resolves to `https://status.example.com/…` it opens **silently**; a link to `example.com` or any other site gets the per-tap confirm (with **Always allow this site**). Changing `source` later re-prompts.
