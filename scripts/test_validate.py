#!/usr/bin/env python3
"""Tests for scripts/validate.py (#56). Run from the repo root: python3 scripts/test_validate.py

The phone's answers for every fixture are pinned in test-fixtures/phone-output.json by the JVM test
PhoneOutputGoldenTest; here the script must give the same error lines and the same canonical bytes.
"""
import hashlib, json, re, pathlib, subprocess, sys, tempfile, unittest

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
import validate as V  # noqa: E402

FIX = ROOT / "test-fixtures" / "configs"
PARSER_TEXT = re.compile(r"not valid JSON: (Unexpected JSON token|Cannot read Json element)")
GOLDEN = json.loads((ROOT / "test-fixtures" / "phone-output.json").read_text())


class JsonNumbers(unittest.TestCase):
    """#120: same accept/reject list as the phone's JsonNumbersConformanceTest (test-fixtures/json-numbers.json)."""
    cases = json.loads((ROOT / "test-fixtures" / "json-numbers.json").read_text())

    def doc(self, token):
        return self.cases["template"].replace("@", token).encode("utf-8")

    def test_accepts_every_rfc_number_and_literal(self):
        bad = {t: V.validate(self.doc(t), use_schema=False)[1] for t in self.cases["accept"]}
        self.assertEqual({}, {t: e for t, e in bad.items() if e})

    def test_accepted_numbers_round_trip(self):
        for t in self.cases["accept"]:
            tree, _ = V.validate(self.doc(t), use_schema=False)
            out = V.canonical(tree)
            self.assertEqual([], V.validate(out.encode("utf-8"), use_schema=False)[1], t)
            # #260 review: the token keeps its exact text, as on the phone.
            self.assertIn(f'"x-v": {t}\n', out, t)
            self.assertIn(f'\n    {t},\n', out, t)

    def test_rejects_every_non_rfc_token_with_the_phone_message(self):
        for t in self.cases["reject"]:
            kind = "number" if t[0] in "+-.0123456789" else "value"
            _, errs = V.validate(self.doc(t), use_schema=False)
            self.assertEqual([f'not valid JSON: invalid {kind} "{t}" (line 4)'], errs, t)

    def test_long_token_is_cut_like_the_phone(self):
        t = "0" + "1" * 60
        self.assertEqual(f'invalid number "{t[:40]}…" (line 1)', V.prescan(t))
        e = "\U0001F600" * 50   # cut by code points, as Kotlin's offsetByCodePoints does
        self.assertEqual(f'invalid value "{e[:40]}…" (line 2)', V.prescan("\n" + e))


class RuleOrder(unittest.TestCase):
    def test_drawer_rule_keys_canonical(self):
        tree, errs = V.validate((FIX / "valid" / "drawer-rules-key-order.json").read_bytes(), use_schema=False)
        self.assertEqual([], errs)
        r = json.loads(V.canonical(tree))["drawer"]["rules"][0]
        self.assertEqual(["id", "group", "match"], list(r))
        self.assertEqual(["packages", "packagePrefix", "tag", "category"], list(r["match"]))


class MatchesPhone(unittest.TestCase):
    def test_every_fixture_same_errors_and_canonical_bytes(self):
        problems = []
        for name, want in GOLDEN.items():
            raw = (FIX / name).read_bytes()
            tree, errs = V.validate(raw, use_schema=False)   # phone rules only, like the app
            if "errors" in want:
                if len(want["errors"]) == 1 and PARSER_TEXT.match(want["errors"][0]):
                    # Syntax errors are worded by each JSON library; only the verdict has to match.
                    if len(errs) != 1 or not errs[0].startswith("not valid JSON: "):
                        problems.append(f"{name}: script {errs[:2]} != phone syntax error")
                elif errs != want["errors"]:
                    problems.append(f"{name}: script {errs[:3]} != phone {want['errors'][:3]}")
            else:
                if errs:
                    problems.append(f"{name}: script rejects {errs[:2]}, phone accepts")
                    continue
                sha = hashlib.sha256(V.canonical(tree).encode("utf-8")).hexdigest()
                if sha != want["canonicalSha256"]:
                    problems.append(f"{name}: canonical output differs from the phone's")
                if V.warnings(tree) != want.get("warnings", []):
                    problems.append(f"{name}: script warnings {V.warnings(tree)} != phone {want.get('warnings', [])}")
        self.assertEqual([], problems)

    def test_schema_pass_agrees_with_manifest(self):
        for e in json.loads((FIX / "manifest.json").read_text()):
            _, errs = V.validate((FIX / e["file"]).read_bytes())
            self.assertEqual(e["file"].startswith("valid/"), not errs, (e["file"], errs[:2]))


class Rules(unittest.TestCase):
    base = {"schemaVersion": 1, "grid": {"columns": 4, "rows": 5}, "pages": [{"id": "home", "items": []}]}

    def errs(self, doc):
        return V.validate(json.dumps(doc).encode(), use_schema=False)[1]

    def test_issue_example_duplicate_id_and_same_cell(self):
        d = json.loads(json.dumps(self.base))
        d["pages"][0]["items"] = [{"id": "a", "type": "app", "package": "a.b", "x": 0, "y": 0},
                                  {"id": "a", "type": "app", "package": "c.d", "x": 0, "y": 0}]
        e = self.errs(d)
        self.assertIn('pages[0].items[1].id: duplicate id "a" (also at pages[0].items[0])', e)
        self.assertIn("pages[0].items[1]: overlaps pages[0].items[0] at cell (0, 0)", e)

    def test_ids_unique_across_pages_dock_folders_and_rules(self):
        d = json.loads(json.dumps(self.base))
        d["dock"] = [{"id": "home", "type": "app", "package": "a.b"}]
        self.assertTrue(any("duplicate id" in x for x in self.errs(d)))

    def test_grid_dock_folder_rules(self):
        d = json.loads(json.dumps(self.base))
        d["pages"][0]["items"] = [{"id": "a", "type": "app", "package": "a.b", "x": 4, "y": 0},
                                  {"id": "f", "type": "folder", "x": 1, "y": 0, "items": [
                                      {"id": "g", "type": "folder", "items": []},
                                      {"id": "c", "type": "app", "package": "c.d", "x": 0, "y": 0}]}]
        d["dock"] = [{"id": "d", "type": "app", "package": "e.f", "x": 0, "y": 0}]
        e = self.errs(d)
        self.assertIn("pages[0].items[0].x: 4 is outside the grid (4 columns)", e)
        self.assertIn("pages[0].items[1].items[0]: folders cannot be nested", e)
        self.assertIn("pages[0].items[1].items[1]: x/y are not allowed in folders", e)
        self.assertIn("dock[0]: x/y are not allowed in the dock", e)


class ThemeInfo(unittest.TestCase):
    """#125: theme colours are only checked on the phone; the script says so once."""
    def tree(self, style, **bar):
        doc = {"schemaVersion": 1, "pages": [{"id": "home", "items": []}],
               "elements": [dict({"id": "t-main", "type": "titleBar", "style": style}, **bar)]}
        tree, errs = V.validate(json.dumps(doc).encode(), use_schema=False)
        self.assertEqual([], errs)
        return tree

    def test_info_only_when_a_measured_bar_uses_theme_colours(self):
        self.assertEqual([V.THEME_INFO], V.infos(self.tree({"color": "primary", "backdrop": "scrim"})))
        self.assertEqual([V.THEME_INFO], V.infos(self.tree({"color": {"dark": "onSurface"}, "backdrop": "scrim"})))
        self.assertEqual([V.THEME_INFO], V.infos(self.tree({"backdrop": {"color": "surface"}})))
        self.assertEqual([], V.infos(self.tree({"color": "primary"})))
        self.assertEqual([], V.infos(self.tree({"color": "#FFFFFF", "backdrop": "scrim"})))
        self.assertEqual([], V.infos(self.tree({"color": "primary", "backdrop": "scrim"}, requires=99)))
        t = self.tree({"backdrop": "scrim"})
        t["pages"][0]["overrides"] = {"t-main": {"style": {"color": "primary"}}}   # #118
        self.assertEqual([V.THEME_INFO], V.infos(t))

    def test_cli_prints_one_info_line(self):
        with tempfile.TemporaryDirectory() as d:
            f = pathlib.Path(d) / "launcher.json"
            f.write_text(json.dumps({"schemaVersion": 1, "pages": [{"id": "home", "items": []}], "elements": [
                {"id": "t-main", "type": "titleBar", "style": {"color": "primary", "backdrop": "scrim"}}]}))
            out = subprocess.run([sys.executable, str(HERE / "validate.py"), "--no-schema", str(f)], capture_output=True, text=True).stdout
        self.assertEqual(1, out.count("info: "), out)
        self.assertIn('info: theme colours (primary, secondary, onSurface, surface) are only checked on the phone; '
                      'see "checked on this phone" in .configlauncher/status.json.', out)


class CardKinds(unittest.TestCase):
    """#137: the phone skips a card with an unknown capability kind (warning); the script (CI) calls it an error."""
    doc = {"schemaVersion": 1, "pages": [{"id": "home", "items": []}], "elements": [
        {"id": "w-1", "type": "card", "requires": 4, "page": "home", "x": 0, "y": 0, "w": 1, "h": 1,
         "capabilities": [{"kind": "data.file"}, {"kind": "net.fetch", "origins": ["https://x.org"]}],
         "layouts": {"1x1": [{"type": "text", "text": "hi"}]}}]}

    def test_phone_rules_warn_ci_errors(self):
        raw = json.dumps(self.doc).encode()
        tree, errs = V.validate(raw, use_schema=False)
        self.assertEqual([], errs)
        self.assertEqual(['elements[0]: capability kind "net.fetch" isn\'t supported by this Reflow Launcher, so the widget was skipped. '
                          'It stays in the file. Update the app, or check the kind for a typo.'], V.warnings(tree))
        _, errs = V.validate(raw, use_schema=False, ci=True)
        self.assertEqual(['elements[0].capabilities[1].kind: unknown capability "net.fetch". Use data.file, ui.reload, ui.openApp, ui.openUrl.'], errs)

    def test_cli_rejects_unknown_kind(self):
        with tempfile.TemporaryDirectory() as d:
            f = pathlib.Path(d) / "launcher.json"
            f.write_text(json.dumps(self.doc))
            p = subprocess.run([sys.executable, str(HERE / "validate.py"), "--no-schema", str(f)], capture_output=True, text=True)
        self.assertEqual(1, p.returncode, p.stdout)
        self.assertIn('unknown capability "net.fetch"', p.stdout)


class AppWidgetLevel(unittest.TestCase):
    """#298 NW-1: appWidget is feature level 5 (level 3 retired); a level-4 app (v1.0.0) skips it and keeps its cells."""
    def test_level_matches_phone(self):
        kt = (ROOT / "app/src/main/java/dev/nasawa/configlauncher/config/Elements.kt").read_text()
        self.assertEqual(6, V.FEATURE_LEVEL)
        self.assertEqual(6, V.FL_CARDS_READOUT)
        self.assertIn("const val FL_CARDS_READOUT = 6", kt)
        self.assertIn("const val FEATURE_LEVEL = FL_CARDS_READOUT", kt)
        self.assertEqual(5, V.ELEMENT_TYPES["appWidget"].LEVEL)
        self.assertEqual({"titleBar", "card", "appWidget"}, set(V.ELEMENT_TYPES))

    def test_level_four_app_skips_and_reserves(self):
        raw = (FIX / "valid" / "appwidget-personal.json").read_bytes()
        old = V.FEATURE_LEVEL
        V.FEATURE_LEVEL = 4
        self.addCleanup(setattr, V, "FEATURE_LEVEL", old)
        tree, errs = V.validate(raw, use_schema=False)
        self.assertEqual([], errs)
        self.assertEqual(["elements[0] needs feature level 5, but this Reflow Launcher supports up to 4, so it was skipped. "
                          "It stays in the file. Update the app to show it."], V.warnings(tree))
        # Its cells stay reserved: an app placed there is B2 even though the widget is skipped.
        plain = json.loads(raw)
        plain["pages"][0]["items"].append({"id": "a-drop", "type": "app", "package": "com.android.settings", "x": 1, "y": 1})
        _, errs = V.validate(json.dumps(plain).encode(), use_schema=False)
        self.assertTrue(any("overlaps" in e for e in errs), errs)


class Format(unittest.TestCase):
    hand = ('{"pages":[{"items":[{"y":0,"x":1,"x-note":"hi","package":"a.b","type":"app","id":"a"}],"id":"home"}],'
            '"x-z":1.50,"schemaVersion":1,"revision":3,"unsorted":{"mode":"page"}}')

    def test_canonical_order_xkeys_last_lf_newline(self):
        tree, errs = V.validate(self.hand.encode(), use_schema=False)
        self.assertEqual([], errs)
        out = V.canonical(tree)
        self.assertEqual(out, """{
  "schemaVersion": 1,
  "revision": 3,
  "pages": [
    {
      "id": "home",
      "items": [
        {
          "id": "a",
          "type": "app",
          "package": "a.b",
          "x": 1,
          "y": 0,
          "x-note": "hi"
        }
      ]
    }
  ],
  "x-z": 1.50
}
""")

    def test_cli_format_is_idempotent_and_check_mode(self):
        with tempfile.TemporaryDirectory() as d:
            f = pathlib.Path(d) / "launcher.json"
            f.write_text(self.hand)
            run = lambda *a: subprocess.run([sys.executable, str(HERE / "validate.py"), "--no-schema", *a, str(f)], capture_output=True, text=True)
            self.assertEqual(1, run("--format", "--check").returncode)
            self.assertEqual(0, run("--format").returncode)
            first = f.read_bytes()
            self.assertFalse(first.startswith(b"\xef\xbb\xbf"))
            self.assertTrue(first.endswith(b"}\n") and b"\r" not in first)
            self.assertEqual(0, run("--format", "--check").returncode)
            self.assertEqual(first, f.read_bytes())

    def test_invalid_file_is_never_rewritten(self):
        with tempfile.TemporaryDirectory() as d:
            f = pathlib.Path(d) / "launcher.json"
            f.write_text('{"schemaVersion":1,"pages":[]}')
            r = subprocess.run([sys.executable, str(HERE / "validate.py"), "--no-schema", "--format", str(f)], capture_output=True, text=True)
            self.assertEqual(1, r.returncode)
            self.assertIn(".pages: needs at least 1 entries", r.stdout)
            self.assertEqual('{"schemaVersion":1,"pages":[]}', f.read_text())


class SchemaId(unittest.TestCase):
    """#242: old GitHub and new reflowlauncher.com $schema both validate; round-trip preserves them."""
    OLD = "https://github.com/Nasawa/config-launcher/schema/launcher-config.v1.schema.json"
    NEW = "https://reflowlauncher.com/schema/launcher-config.v1.schema.json"

    def _doc(self, schema):
        return json.dumps({"$schema": schema, "schemaVersion": 1, "pages": [{"id": "home", "items": []}]}).encode()

    def test_old_github_url_loads_clean(self):
        tree, errs = V.validate(self._doc(self.OLD), use_schema=False)
        self.assertEqual([], errs)
        self.assertEqual(self.OLD, tree["$schema"])

    def test_new_reflow_url_loads_clean(self):
        tree, errs = V.validate(self._doc(self.NEW), use_schema=False)
        self.assertEqual([], errs)
        self.assertEqual(self.NEW, tree["$schema"])

    def test_round_trip_preserves_old(self):
        tree, errs = V.validate(self._doc(self.OLD), use_schema=False)
        self.assertEqual([], errs)
        out = V.canonical(tree)
        self.assertIn(self.OLD, out)
        self.assertNotIn(self.NEW, out)
        tree2, errs2 = V.validate(out.encode(), use_schema=False)
        self.assertEqual([], errs2)
        self.assertEqual(self.OLD, tree2["$schema"])

    def test_round_trip_preserves_new(self):
        tree, errs = V.validate(self._doc(self.NEW), use_schema=False)
        self.assertEqual([], errs)
        out = V.canonical(tree)
        self.assertIn(self.NEW, out)
        tree2, errs2 = V.validate(out.encode(), use_schema=False)
        self.assertEqual([], errs2)
        self.assertEqual(self.NEW, tree2["$schema"])


class CardReadout(unittest.TestCase):
    """#360 RW-1: M1 readout keys; Kotlin and validate.py agree on every message."""
    def _doc(self, card):
        return {"schemaVersion": 1, "grid": {"columns": 4, "rows": 12}, "pages": [{"id": "home", "items": []}], "elements": [card]}

    def _raw(self, card):
        return json.dumps(self._doc(card)).encode()

    def test_examples_validate(self):
        for name in ("card-status.json", "card-usage.json"):
            raw = (FIX / "valid" / name).read_bytes()
            tree, errs = V.validate(raw, use_schema=False)
            self.assertEqual([], errs, name)
            self.assertEqual([], V.warnings(tree), name)

    def test_new_keys_need_level(self):
        card = {"id": "w-1", "type": "card", "requires": 4, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                "header": False, "layouts": {"1x1": [{"type": "text", "text": "hi"}]}}
        _, errs = V.validate(self._raw(card), use_schema=False)
        self.assertIn("elements[0].requires: a card that uses readout keys needs feature level 6 or higher", errs)

    def test_level_five_app_skips_readout_card(self):
        raw = (FIX / "valid" / "card-usage.json").read_bytes()
        old = V.FEATURE_LEVEL
        V.FEATURE_LEVEL = 5
        self.addCleanup(setattr, V, "FEATURE_LEVEL", old)
        tree, errs = V.validate(raw, use_schema=False)
        self.assertEqual([], errs)
        self.assertEqual(["elements[0] needs feature level 6, but this Reflow Launcher supports up to 5, so it was skipped. "
                          "It stays in the file. Update the app to show it."], V.warnings(tree))

    def test_header_false_openurl_warning(self):
        card = {"id": "w-1", "type": "card", "requires": 6, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                "header": False, "capabilities": [{"kind": "ui.openUrl"}],
                "layouts": {"1x1": [{"type": "text", "text": "hi"}]}}
        tree, errs = V.validate(self._raw(card), use_schema=False)
        self.assertEqual([], errs)
        self.assertEqual(['elements[0].header: false is ignored because this card opens links or needs your OK, so the header is shown.'], V.warnings(tree))

    def test_header_false_surface_none_warning(self):
        card = {"id": "w-1", "type": "card", "requires": 6, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                "header": False, "surface": "none", "layouts": {"1x1": [{"type": "text", "text": "hi"}]}}
        tree, errs = V.validate(self._raw(card), use_schema=False)
        self.assertEqual([], errs)
        self.assertEqual(['elements[0].surface: "none" can\'t be used with header: false, so the default card surface was used.'], V.warnings(tree))

    def test_secret_url_and_data_link(self):
        base = {"id": "w-1", "type": "card", "requires": 6, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                "capabilities": [{"kind": "ui.openUrl"}]}
        ok = {**base, "layouts": {"1x1": [{"type": "text", "text": "go", "tap": {"kind": "ui.openUrl", "url": "{data:blog.url}"}}]}}
        _, errs = V.validate(self._raw(ok), use_schema=False)
        self.assertEqual([], errs)
        bad = {**base, "layouts": {"1x1": [{"type": "text", "text": "go", "tap": {"kind": "ui.openUrl", "url": "https://example.com/${secret:ha}"}}]}}
        _, errs = V.validate(self._raw(bad), use_schema=False)
        self.assertIn("elements[0].layouts.1x1[0].tap.url: a link can't use a secret.", errs)
        brace = {**base, "layouts": {"1x1": [{"type": "text", "text": "go", "tap": {"kind": "ui.openUrl", "url": "https://example.com/{secret:ha}"}}]}}
        _, errs = V.validate(self._raw(brace), use_schema=False)
        self.assertIn("elements[0].layouts.1x1[0].tap.url: a link can't use a secret.", errs)

    def test_from_75_accepted(self):
        card = {"id": "w-1", "type": "card", "requires": 6, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                "layouts": {"1x1": [{"type": "progress", "value": 79, "max": 100,
                                     "thresholds": [{"from": 0, "tone": "accent"}, {"from": 75, "tone": "warn"}]}]}}
        _, errs = V.validate(self._raw(card), use_schema=False)
        self.assertEqual([], errs)

    def test_width_offset_count_warning_sentences(self):
        card = {"id": "w-1", "type": "card", "requires": 6, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                "layers": [{"kind": "picture", "src": "a.webp", "fit": "cover", "width": 2,
                            "offset": {"x": -0.4, "y": 0.3}}, {"kind": "content"}],
                "layouts": {"1x1": [{"type": "progress", "value": 0.5, "style": "ticks", "count": 200, "segments": 8}]}}
        tree, errs = V.validate(self._raw(card), use_schema=False)
        self.assertEqual([], errs)
        ws = V.warnings(tree)
        self.assertIn('elements[0].layers[0].width is ignored unless fit is "fixed", so the picture is sized with "cover". Set fit to "fixed" to use width, or drop width.', ws)
        self.assertIn("elements[0].layers[0].offset.x: -0.4 is outside -0.25 to 0.25, so it was set to -0.25. Use a value in that range.", ws)
        self.assertIn("elements[0].layers[0].offset.y: 0.3 is outside -0.25 to 0.25, so it was set to 0.25. Use a value in that range.", ws)
        self.assertIn("elements[0].layouts.1x1[0].count: 200 is outside 10 to 120, so it was set to 120. A ticks bar can have at most 120 ticks.", ws)
        self.assertIn('elements[0].layouts.1x1[0].segments is ignored when style is "ticks", so the bar uses ticks. Drop segments, or drop style to use a segmented bar.', ws)
        wide = {"id": "w-1", "type": "card", "requires": 6, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                "layers": [{"kind": "picture", "src": "a.webp", "fit": "fixed", "width": 2, "scrim": "none"},
                           {"kind": "content"}],
                "layouts": {"1x1": [{"type": "text", "text": "hi"}]}}
        tree, errs = V.validate(self._raw(wide), use_schema=False)
        self.assertEqual([], errs)
        self.assertIn("elements[0].layers[0].width: 2 is outside 0.1 to 1.5, so it was set to 1.5. Use a value in that range.", V.warnings(tree))
        contain = {**wide, "layers": [{"kind": "picture", "src": "a.webp", "fit": "contain", "width": 0.4, "scrim": "none"}, {"kind": "content"}]}
        tree, errs = V.validate(self._raw(contain), use_schema=False)
        self.assertEqual([], errs)
        self.assertIn('elements[0].layers[0].width is ignored unless fit is "fixed", so the picture is sized with "contain". Set fit to "fixed" to use width, or drop width.', V.warnings(tree))
        low = {"id": "w-1", "type": "card", "requires": 6, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
               "layouts": {"1x1": [{"type": "progress", "value": 0.5, "style": "ticks", "count": 5}]}}
        tree, errs = V.validate(self._raw(low), use_schema=False)
        self.assertEqual([], errs)
        self.assertEqual(["elements[0].layouts.1x1[0].count: 5 is outside 10 to 120, so it was set to 10. A ticks bar needs at least 10 ticks."], V.warnings(tree))
        ignored = {"id": "w-1", "type": "card", "requires": 6, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                   "layouts": {"1x1": [{"type": "progress", "value": 0.5, "count": 40}]}}
        tree, errs = V.validate(self._raw(ignored), use_schema=False)
        self.assertEqual([], errs)
        self.assertEqual(['elements[0].layouts.1x1[0].count is ignored unless style is "ticks", so the tick count isn\'t used. Set style to "ticks" to use count, or drop count.'], V.warnings(tree))
        above = {"id": "w-1", "type": "card", "requires": 6, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                 "layers": [{"kind": "content"}, {"kind": "picture", "src": "over.webp", "opacity": 0.9}],
                 "layouts": {"1x1": [{"type": "text", "text": "hi"}]}}
        tree, errs = V.validate(self._raw(above), use_schema=False)
        self.assertEqual([], errs)
        self.assertEqual(["elements[0].layers[1].opacity is above 0.6 on a layer above content, so it's shown at 0.6 to keep text readable. Use 0.6 or less, or move the layer under the content."], V.warnings(tree))

    def test_default_scrim_uses_real_index(self):
        card = {"id": "w-1", "type": "card", "requires": 6, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                "layers": [{"kind": "picture", "src": "photo.webp"}, {"kind": "content"}],
                "layouts": {"1x1": [{"type": "text", "text": "hi"}]}}
        tree, errs = V.validate(self._raw(card), use_schema=False)
        self.assertEqual([], errs)
        self.assertEqual([
            'elements[0].layers[0]: a picture is right under content, so a default scrim was added to keep text readable. Set this layer\'s scrim to choose it, or "none" to turn it off.'
        ], V.warnings(tree))
        not_under = {"id": "w-1", "type": "card", "requires": 6, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                     "layers": [{"kind": "picture", "src": "a.webp"}, {"kind": "picture", "src": "b.webp", "scrim": "auto"}, {"kind": "content"}],
                     "layouts": {"1x1": [{"type": "text", "text": "hi"}]}}
        tree, errs = V.validate(self._raw(not_under), use_schema=False)
        self.assertEqual([], errs)
        self.assertEqual([
            'elements[0].layers[1]: a picture is right under content, so a default scrim was added to keep text readable. Set this layer\'s scrim to choose it, or "none" to turn it off.'
        ], V.warnings(tree))

    def test_extra_layers_and_above_content_opacity(self):
        layers = [{"kind": "picture", "src": f"p{i}.webp"} for i in range(1, 6)] + [{"kind": "content"}]
        card = {"id": "w-1", "type": "card", "requires": 6, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                "layers": layers, "layouts": {"1x1": [{"type": "text", "text": "hi"}]}}
        tree, errs = V.validate(self._raw(card), use_schema=False)
        self.assertEqual([], errs)
        ws = V.warnings(tree)
        self.assertEqual("elements[0].layers: has 6 layers, so the last 2 were skipped. A card can have at most 4.", ws[0])
        self.assertIn(
            'elements[0].layers[3]: a picture is right under content, so a default scrim was added to keep text readable. Set this layer\'s scrim to choose it, or "none" to turn it off.',
            ws,
        )
        above = {"id": "w-1", "type": "card", "requires": 6, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                 "layers": [{"kind": "content"}, {"kind": "picture", "src": "over.webp", "opacity": 0.9}],
                 "layouts": {"1x1": [{"type": "text", "text": "hi"}]}}
        tree, errs = V.validate(self._raw(above), use_schema=False)
        self.assertEqual([], errs)
        self.assertEqual([
            "elements[0].layers[1].opacity is above 0.6 on a layer above content, so it's shown at 0.6 to keep text readable. Use 0.6 or less, or move the layer under the content."
        ], V.warnings(tree))
        above_ok = {"id": "w-1", "type": "card", "requires": 6, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                    "layers": [{"kind": "content"}, {"kind": "picture", "src": "over.webp", "opacity": 0.5}],
                    "layouts": {"1x1": [{"type": "text", "text": "hi"}]}}
        tree, errs = V.validate(self._raw(above_ok), use_schema=False)
        self.assertEqual([], errs)
        self.assertEqual([], V.warnings(tree))

    def test_nested_light_dark_color_is_rejected(self):
        raw = json.dumps({"schemaVersion": 1, "pages": [{"id": "home", "items": []}],
                          "elements": [{"id": "t-main", "type": "titleBar",
                                        "style": {"color": {"light": {"light": "primary", "dark": "dark"}, "dark": "dark"},
                                                  "backdrop": "scrim"}}]}).encode()
        _, errs = V.validate(raw, use_schema=False)
        self.assertIn("elements[0].style.color.light must be #RRGGBB or one of light, dark, onWallpaper, primary, secondary, onSurface, surface.", errs)

    def test_missing_file_sentences(self):
        card = {"id": "w-1", "type": "card", "requires": 6, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                "layers": [{"kind": "picture", "src": "sticker.webp", "default": "sticker-idle.webp", "scrim": "none"}, {"kind": "content"}],
                "layouts": {"1x1": [{"type": "text", "text": "hi"}]}}
        ws = V.Card.load_warnings(card, "elements[0]", files=set())
        self.assertEqual([
            'elements[0].layers[0].src: "sticker.webp" isn\'t in this card\'s pictures folder, so the layer was skipped.',
            'elements[0].layers[0].default: "sticker-idle.webp" isn\'t in this card\'s pictures folder, so the default picture was skipped.',
        ], ws)



if __name__ == "__main__":
    unittest.main()


class Formatters(unittest.TestCase):
    """#361 RW-4: Bindings and Thresholds match the Kotlin strings."""

    def setUp(self):
        from datetime import datetime
        from zoneinfo import ZoneInfo
        self.zone = ZoneInfo("America/Denver")
        self.now = datetime.fromisoformat("2026-11-28T16:38:00+00:00")
        self.env = {
            "now": self.now,
            "zone": self.zone,
            "locale": "en_US",
            "is24Hour": False,
            "data": {
                "ctx": V.Num("79.43"),
                "started_at": "2026-11-28T07:35:00-07:00",
                "state": "idle",
                "updatedAt": "2026-11-28T09:33:00-07:00",
                "blank": "",
            },
            "fetch": {},
            "batteryPercent": 80,
            "charging": False,
            "full": False,
            "nextAlarm": datetime.fromisoformat("2026-11-28T18:00:00+00:00"),
        }

    def text(self, s, env=None):
        return V.Bindings.expand(s, env or self.env)["text"]

    def value(self, s, env=None):
        return V.Bindings.expand(s, env or self.env)

    def test_round_default_n_is_zero(self):
        self.assertEqual("79", self.text("{data:ctx|round}"))
        self.assertEqual("79.4", self.text("{data:ctx|round:1}"))
        self.assertEqual("—", self.text("{data:missing|round}"))

    def test_two_formatters_left_to_right(self):
        self.assertEqual("79", self.text("{data:ctx|round:1|round}"))
        self.assertEqual("1m 19s", self.text("{data:ctx|round|duration}"))

    def test_since_and_duration(self):
        self.assertEqual("9h 3m", V.Bindings.format_duration(9 * 3600 + 3 * 60))
        self.assertEqual("2h 3m", self.text("{data:started_at|since}"))

    def test_missing_versus_empty_versus_default(self):
        missing = self.value("{data:missing}")
        self.assertEqual("—", missing["text"])
        self.assertFalse(missing["empty"])
        self.assertEqual("offline", self.value("{data:missing|default:offline}")["text"])
        empty = self.value("{data:blank}")
        self.assertEqual("", empty["text"])
        self.assertTrue(empty["empty"])
        empty_def = self.value("{data:blank|default:offline}")
        self.assertTrue(empty_def["empty"])

    def test_battery_and_relative(self):
        self.assertEqual("80", self.text("{battery}"))
        self.assertEqual("0.8", self.text("{batteryFraction}"))
        self.assertEqual("5 min ago", self.text("{relative}"))
        env = dict(self.env)
        env["data"] = dict(self.env["data"])
        env["data"]["updatedAt"] = "2026-11-28T09:37:30-07:00"
        self.assertEqual("just now", self.text("{relative}", env))

    def test_time_12_and_24(self):
        self.assertEqual("9:38 AM", self.text("{time}"))
        env = dict(self.env, is24Hour=True)
        self.assertEqual("9:38", self.text("{time}", env))
        self.assertEqual("09:38", self.text("{time:HH:mm}", env))

    def test_threshold_74_75_76(self):
        entries = [(0, "accent"), (75, "warn"), (90, "error")]
        self.assertEqual("accent", V.Thresholds.tone(74, entries))
        self.assertEqual("warn", V.Thresholds.tone(75, entries))
        self.assertEqual("warn", V.Thresholds.tone(76, entries))
        self.assertEqual("accent", V.Thresholds.tick_tone(74, 100, entries))
        self.assertEqual("warn", V.Thresholds.tick_tone(75, 100, entries))
        self.assertEqual("warn", V.Thresholds.tick_tone(76, 100, entries))

    def test_load_errors_and_as_of(self):
        card = {"id": "w-1", "type": "card", "requires": 6, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                "layouts": {"1x1": [{"type": "text", "text": "{foo}"}]}}
        doc = {"schemaVersion": 1, "grid": {"columns": 4, "rows": 12},
               "pages": [{"id": "home", "items": []}], "elements": [card]}
        _, errs = V.validate(json.dumps(doc).encode(), use_schema=False)
        self.assertTrue(any(e.startswith("elements[0].layouts.1x1[0].text: unknown token {foo}") for e in errs), errs)
        card2 = dict(card)
        card2["layouts"] = {"1x1": [{"type": "text", "text": "hi"}]}
        card2["data"] = {"asOf": "bad path!"}
        _, errs = V.validate(json.dumps({**doc, "elements": [card2]}).encode(), use_schema=False)
        self.assertTrue(any(e.startswith('elements[0].data.asOf: "bad path!" isn\'t a data path') for e in errs), errs)

    def test_as_of_is_a_readout_key(self):
        card = {"id": "w-1", "type": "card", "requires": 4, "page": "home", "x": 0, "y": 1, "w": 2, "h": 2,
                "data": {"asOf": "last_updated"},
                "layouts": {"1x1": [{"type": "text", "text": "hi"}]}}
        doc = {"schemaVersion": 1, "grid": {"columns": 4, "rows": 12},
               "pages": [{"id": "home", "items": []}], "elements": [card]}
        _, errs = V.validate(json.dumps(doc).encode(), use_schema=False)
        self.assertIn("elements[0].requires: a card that uses readout keys needs feature level 6 or higher", errs)
