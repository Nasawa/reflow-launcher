#!/usr/bin/env python3
"""Unit tests for scripts/screen_matrix_drop.py: the edge-pass targets (edge, needs Pillow) and the edge scorer
(score), on point dirs built from the real fixture scripts/screen-matrix/edge.json. No emulator, no clock.
Run from repo root: python3 scripts/test_screen_matrix_drop.py
"""
from __future__ import annotations

import copy
import gc
import io
import json
import os
import pathlib
import sys
import tempfile
import unittest
import unittest.mock
import warnings
from contextlib import redirect_stdout

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import screen_matrix_check as c  # noqa: E402
import screen_matrix_drop as dr  # noqa: E402
from pkg import PKG  # noqa: E402

FIXTURE = json.loads((HERE / "screen-matrix" / "edge.json").read_text())

try:
    import PIL  # noqa: F401
    HAVE_PIL = True
except ImportError:
    HAVE_PIL = False


def moved(cfg, item, x=None, y=None, page="home", drop_from="home"):
    """Copy of cfg with item moved to (x, y) on page (from page drop_from)."""
    cfg = copy.deepcopy(cfg)
    src = next(p for p in cfg["pages"] if p["id"] == drop_from)
    it = next(i for i in src["items"] if i["id"] == item)
    src["items"].remove(it)
    it = dict(it, x=x, y=y)
    next(p for p in cfg["pages"] if p["id"] == page)["items"].append(it)
    return cfg


class Point:
    """A point dir as edge_probe leaves it: edge-cases.txt plus pg-<case>-* files."""

    def __init__(self, root, name="dnd-p411-a51-v1"):
        self.d = os.path.join(root, name)
        os.makedirs(self.d)
        with open(os.path.join(self.d, "meta.txt"), "w") as f:
            f.write(f"id={name.split('-', 1)[1]}\nprobe_only=edge\n")
        self.lines = []

    def case(self, cs, kind, col, row, after=None, before=FIXTURE, crash="", dump="", skipped=False):
        self.lines.append(f"{cs} {kind} {col} {row} 100 200 rot=0" + (" skipped=budget" if skipped else ""))
        if skipped:
            return
        for step, cfg in (("before", before), ("after", FIXTURE if after is None else after)):
            if cfg is not False:
                with open(os.path.join(self.d, f"pg-{cs}-config-{step}.json"), "w") as f:
                    json.dump(cfg, f)
        with open(os.path.join(self.d, f"pg-{cs}-crash.txt"), "w") as f:
            f.write(crash)
        if dump:
            with open(os.path.join(self.d, f"pg-{cs}-1-after.xml"), "w") as f:
                f.write(dump)

    def score(self):
        with open(os.path.join(self.d, "edge-cases.txt"), "w") as f:
            f.write("".join(l + "\n" for l in self.lines))
        with redirect_stdout(io.StringIO()):
            res = dr.score(self.d, PKG)
        return {r["id"]: r for r in res}


class Score(unittest.TestCase):
    def setUp(self):
        self.t = tempfile.TemporaryDirectory()
        self.p = Point(self.t.name)

    def tearDown(self):
        self.t.cleanup()

    def one(self, *a, **k):
        self.p.case("base-x", *a, **k)
        return self.p.score()["base-x"]

    def test_cell_committed_to_its_cell_passes(self):
        r = self.one("cell", 0, 4, moved(FIXTURE, "a-edge-c", 0, 4))
        self.assertIs(r["ok"], True, r)

    def test_cell_refused_fails(self):
        r = self.one("cell", 3, 0)
        self.assertIs(r["ok"], False)
        self.assertIn("refused", r["detail"])

    def test_cell_on_the_wrong_cell_fails(self):
        r = self.one("cell", 3, 0, moved(FIXTURE, "a-edge-c", 2, 0))
        self.assertIs(r["ok"], False)
        self.assertIn("wrong cell: Edge C -> home (2,0)", r["detail"])

    def test_cell_onto_another_page_fails(self):
        r = self.one("cell", 0, 0, moved(FIXTURE, "a-edge-c", 0, 0, page="second"))
        self.assertIs(r["ok"], False)
        self.assertIn("second (0,0)", r["detail"])

    def test_gutter_refused_passes_and_commit_fails(self):
        self.p.case("base-gutter-L", "gutter", -1, -1)
        self.p.case("base-gutter-R", "gutter", -1, -1, moved(FIXTURE, "a-edge-c", 3, 2))
        r = self.p.score()
        self.assertIs(r["base-gutter-L"]["ok"], True)
        self.assertIsNone(r["base-gutter-L"]["want"])
        self.assertIs(r["base-gutter-R"]["ok"], False)
        self.assertIn("gutter drop committed", r["base-gutter-R"]["detail"])

    def test_outer_edge_column_or_refused_passes_anything_else_fails(self):
        self.p.case("base-outer-L", "outer", 0, 2, moved(FIXTURE, "a-edge-c", 0, 2))
        self.p.case("base-outer-R", "outer", 3, 2)
        self.p.case("flip-outer-R", "outer", 3, 2, moved(FIXTURE, "a-edge-c", 2, 2))
        r = self.p.score()
        self.assertIs(r["base-outer-L"]["ok"], True)
        self.assertIs(r["base-outer-R"]["ok"], True)
        self.assertEqual(r["base-outer-R"]["detail"], "refused")
        self.assertIs(r["flip-outer-R"]["ok"], False)

    def test_our_crash_fails_even_on_the_right_cell(self):
        crash = f"--------- beginning of crash\nE AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: {PKG}, PID: 1\n"
        r = self.one("cell", 0, 4, moved(FIXTURE, "a-edge-c", 0, 4), crash=crash)
        self.assertIs(r["ok"], False)
        self.assertIn("FATAL EXCEPTION", r["detail"])

    def test_our_anr_fails(self):
        r = self.one("gutter", -1, -1, crash=f"I ActivityManager: ANR in {PKG} (x)\n")
        self.assertIs(r["ok"], False)

    def test_another_apps_crash_is_not_ours(self):
        crash = "E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: com.android.systemui, PID: 9\n"
        self.assertIs(self.one("gutter", -1, -1, crash=crash)["ok"], True)

    def test_anr_dialog_in_dump_fails_unless_system_ui(self):
        self.p.case("base-a", "gutter", -1, -1, dump='<node text="Reflow isn&apos;t responding" />')
        self.p.case("base-b", "gutter", -1, -1, dump='<node text="System UI isn&apos;t responding" />')
        r = self.p.score()
        self.assertIs(r["base-a"]["ok"], False)
        self.assertIs(r["base-b"]["ok"], True)

    def test_duplicated_tile_fails(self):
        dup = copy.deepcopy(FIXTURE)
        dup["pages"][0]["items"].append(dict(dup["pages"][0]["items"][0], x=0, y=4))
        r = self.one("cell", 0, 4, dup)
        self.assertIs(r["ok"], False)
        self.assertIn("duplicated", r["detail"])

    def test_lost_tile_fails(self):
        lost = copy.deepcopy(FIXTURE)
        lost["pages"][0]["items"] = [i for i in lost["pages"][0]["items"] if i["id"] != "a-edge-c"]
        self.assertIn("lost", self.one("cell", 0, 4, lost)["detail"])

    def test_another_item_moving_fails(self):
        r = self.one("cell", 0, 4, moved(moved(FIXTURE, "a-edge-c", 0, 4), "a-edge-d", 3, 3))
        self.assertIs(r["ok"], False)
        self.assertIn("a-edge-d", r["detail"])

    def test_missing_config_is_na(self):
        r = self.one("cell", 0, 4, after=False)
        self.assertIsNone(r["ok"])
        self.assertIn("harness", r["detail"])

    def test_unseeded_fixture_is_na(self):
        r = self.one("cell", 0, 4, before=moved(FIXTURE, "a-edge-c", 0, 0))
        self.assertIsNone(r["ok"])
        self.assertIn("not seeded", r["detail"])

    def test_budget_skipped_case_is_na(self):
        r = self.one("cell", 0, 4, skipped=True)
        self.assertIsNone(r["ok"])
        self.assertIn("EDGE_BUDGET_S", r["detail"])

    def test_failed_targets_orientation_is_listed(self):
        with open(os.path.join(self.p.d, "sb-edge-base.txt"), "w") as f:
            f.write("ERR tiles\n")
        r = self.p.score()
        self.assertIsNone(r["base-targets"]["ok"])
        self.assertIn("ERR tiles", r["base-targets"]["detail"])

    def test_writes_json_and_md_and_tally_reads_them(self):
        self.p.case("base-corner-c0r0", "cell", 0, 0, moved(FIXTURE, "a-edge-c", 0, 0))
        self.p.case("base-corner-c3r4", "cell", 3, 4)
        self.p.case("base-gutter-L", "gutter", -1, -1, after=False)
        self.p.score()
        with open(os.path.join(self.p.d, "edge-score.json")) as f:
            j = json.load(f)
        self.assertEqual((j["probe"], j["point"]), ("edge", "p411-a51-v1"))
        with open(os.path.join(self.p.d, "edge-score.md")) as f:
            md = f.read()
        self.assertTrue(md.startswith("# Edge probe (p411-a51-v1): 1 PASS, 1 FAIL, 1 n/a (advisory)"), md)
        self.assertIn("| base-corner-c3r4 | cell | (3,4) | FAIL | refused (no commit) |", md)
        self.assertEqual(c.probe_tally(self.p.d, "edge"), "1 PASS, 1 FAIL, 1 n/a (base-corner-c3r4)")

    def test_no_cases_says_so(self):
        self.assertEqual(self.p.score(), {})
        with open(os.path.join(self.p.d, "edge-score.md")) as f:
            self.assertIn("no cases ran", f.read())
        self.assertIn("NO RESULTS", c.probe_tally(self.p.d, "edge"))

    def test_main_score_exits_0_and_bad_args_2(self):
        with redirect_stdout(io.StringIO()):
            self.assertEqual(dr.main(["x", "score", self.p.d]), 0)
        self.assertTrue(os.path.exists(os.path.join(self.p.d, "edge-score.json")))
        with redirect_stdout(io.StringIO()), unittest.mock.patch("sys.stderr", io.StringIO()):
            self.assertEqual(dr.main(["x", "nope"]), 2)

    def test_no_unclosed_files(self):
        self.p.case("base-x", "cell", 0, 4, moved(FIXTURE, "a-edge-c", 0, 4))
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("always", ResourceWarning)
            self.p.score()
            gc.collect()
        self.assertEqual([str(x.message) for x in w if issubclass(x.category, ResourceWarning)], [])


# --- edge(): a synthetic edit-mode dump + screenshot. 4x5 grid of 270x400 px cells starting at (OX, 100); a tile
# is its clickable cell, a blue gear 100 px square above a 160x40 label. Icon centre = cell centre - 40 px in y.
PW, PH, DENS = 270, 400, 420


def cell_xy(col, row, ox):
    return ox + PW // 2 + PW * col, 100 + PH // 2 + PH * row


def synth(d, W, H, ox, tiles):
    from PIL import Image, ImageDraw
    im = Image.new("RGB", (W, H), (20, 20, 20))
    dr_ = ImageDraw.Draw(im)
    nodes = []
    for label, (col, row) in tiles.items():
        x, y = cell_xy(col, row, ox)
        dr_.rectangle([x - 50, y - 90, x + 50, y + 10], fill=(30, 140, 230))
        l, t = ox + PW * col, 100 + PH * row
        nodes.append(f'<node text="" content-desc="" clickable="true" bounds="[{l},{t}][{l + PW},{t + PH}]">'
                     f'<node text="{label}" content-desc="" clickable="false" bounds="[{x - 80},{y + 60}][{x + 80},{y + 100}]" /></node>')
    xml = os.path.join(d, "e.xml"); png = os.path.join(d, "e.png")
    with open(xml, "w") as f:
        f.write(f'<hierarchy><node text="" content-desc="" clickable="false" bounds="[0,0][{W},{H}]">{"".join(nodes)}</node></hierarchy>')
    im.save(png)
    return xml, png


def cases(out):
    return {l.split()[1]: l.split()[2:] for l in out if l.startswith("CASE ")}


@unittest.skipUnless(HAVE_PIL, "Pillow not installed")
class EdgeTargets(unittest.TestCase):
    def run_edge(self, W, H, ox, rtl):
        # logical (col,row) -> physical column: RTL mirrors
        phys = (lambda col: 3 - col) if rtl else (lambda col: col)
        with tempfile.TemporaryDirectory() as t:
            xml, png = synth(t, W, H, ox, {"Edge C": (phys(1), 1), "Edge D": (phys(2), 3)})
            return dr.edge(xml, png, rtl, DENS)

    def test_phone_ltr_cells_and_outer_no_gutters(self):
        out = self.run_edge(1080, 2400, 0, False)
        self.assertIn("CX=405", out)  # C's icon centre: column 1, 40 px above the cell centre
        self.assertIn("CY=660", out)
        self.assertIn("PW=270", out)
        self.assertIn("PH=400", out)
        k = cases(out)
        self.assertEqual(sum(v[2] == "cell" for v in k.values()), 14)  # every edge/corner cell of 4x5
        self.assertEqual([k for k in k if k.startswith("gutter")], [])
        self.assertTrue(any(l.startswith("# gutter-L n/a") for l in out))
        # corner (0,0): centre (135,300) pushed 0.25 pitch out -> (67.5,200), finger = drop + (0,-40); 1012.5 rounds to even
        self.assertEqual(k["corner-c0r0"], ["68", "160", "cell", "0", "0"])
        self.assertEqual(k["corner-c3r4"], ["1012", "1960", "cell", "3", "4"])
        self.assertEqual(k["edge-c3r2"], ["1012", "1060", "cell", "3", "2"])
        # outer: 2 dp (5.25 px) inside the window edges on the middle row; L is column 0 in LTR
        self.assertEqual(k["outer-L"], ["5", "1060", "outer", "0", "2"])
        self.assertEqual(k["outer-R"], ["1075", "1060", "outer", "3", "2"])

    def test_capped_grid_has_gutters(self):
        k = cases(self.run_edge(1600, 2560, 260, False))
        self.assertEqual(k["gutter-L"][2:], ["gutter", "-1", "-1"])
        self.assertEqual(k["gutter-R"][2:], ["gutter", "-1", "-1"])
        self.assertLess(int(k["gutter-L"][0]), 260)
        self.assertGreater(int(k["gutter-R"][0]), 260 + 4 * PW)

    def test_rtl_mirrors_columns(self):
        k = cases(self.run_edge(1080, 2400, 0, True))
        # logical column 0 is physically rightmost in RTL
        self.assertEqual(k["corner-c0r0"][:2], ["1012", "160"])
        self.assertEqual(k["corner-c3r0"][:2], ["68", "160"])
        self.assertEqual(k["outer-L"][3], "3")
        self.assertEqual(k["outer-R"][3], "0")

    def test_missing_tile_reports_err(self):
        with tempfile.TemporaryDirectory() as t:
            xml, png = synth(t, 1080, 2400, 0, {"Edge C": (1, 1)})
            self.assertEqual(dr.edge(xml, png, False, DENS), ["ERR tiles"])


class Fixture(unittest.TestCase):
    def test_edge_fixture_leaves_every_edge_and_corner_cell_empty(self):
        home = next(p for p in FIXTURE["pages"] if p["id"] == "home")
        self.assertEqual(home["title"], "Drag")
        self.assertEqual((FIXTURE["grid"]["columns"], FIXTURE["grid"]["rows"]), (4, 5))
        at = {(i["x"], i["y"]): i["id"] for i in home["items"]}
        self.assertEqual(at, {(1, 1): "a-edge-c", (2, 3): "a-edge-d"})


if __name__ == "__main__":
    unittest.main()
