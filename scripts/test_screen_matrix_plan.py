#!/usr/bin/env python3
"""Unit tests for scripts/screen_matrix_plan.py (screen-matrix.yml shard plan) and the probe-only rows in
screen_matrix_check.py summarize. Run from repo root: python3 scripts/test_screen_matrix_plan.py
"""
from __future__ import annotations

import gc
import io
import json
import os
import pathlib
import re
import sys
import tempfile
import unittest
import warnings
from contextlib import redirect_stdout

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
import screen_matrix_check as c  # noqa: E402
import screen_matrix_plan as sp  # noqa: E402


def by_name(m):
    return {e["shard"]: e for e in m["include"]}


class Plan(unittest.TestCase):
    def test_all_without_probes_has_no_dnd_shard(self):
        m = by_name(sp.plan("all", ""))
        self.assertEqual(sorted(m), ["fold", "phones", "small-a", "small-b", "tablets"])
        self.assertTrue(all(e["probes"] == "" for e in m.values()))
        self.assertTrue(all(e["rows"] == n for n, e in m.items()))

    def test_probes_dnd_moves_tablet_probe_to_dnd_shard(self):
        m = by_name(sp.plan("all", "dnd"))
        self.assertEqual(m["tablets"]["probes"], "")
        self.assertEqual(m["dnd"]["probes"], "dnd-only")
        self.assertEqual(m["dnd"]["rows"], "tablets")
        # phones keeps its DnD points inline
        self.assertEqual(m["phones"]["probes"], "dnd")
        self.assertEqual(m["small-a"]["probes"], "dnd")

    def test_dnd_shard_uses_the_tablets_image(self):
        m = by_name(sp.plan("all", "dnd"))
        for k in ("api", "target", "arch", "skin"):
            self.assertEqual(m["dnd"][k], m["tablets"][k], k)

    def test_tablets_alone_with_dnd_adds_dnd(self):
        m = by_name(sp.plan("tablets", " dnd , "))
        self.assertEqual(sorted(m), ["dnd", "tablets"])
        self.assertEqual(m["tablets"]["probes"], "")

    def test_phones_with_dnd_does_not_add_dnd_shard(self):
        m = by_name(sp.plan("phones", "dnd"))
        self.assertEqual(sorted(m), ["phones"])
        self.assertEqual(m["phones"]["probes"], "dnd")

    def test_dnd_by_name(self):
        m = by_name(sp.plan("dnd", ""))
        self.assertEqual(sorted(m), ["dnd"])
        self.assertEqual(m["dnd"]["probes"], "dnd-only")

    def test_other_probes_pass_through_tablets(self):
        m = by_name(sp.plan("tablets", "dnd,future"))
        self.assertEqual(m["tablets"]["probes"], "future")
        self.assertEqual(m["dnd"]["probes"], "dnd-only")

    def test_empty_shards_means_all(self):
        self.assertEqual(len(sp.plan("", "")["include"]), 5)

    def test_unknown_shard_fails(self):
        with self.assertRaises(SystemExit):
            sp.plan("tablet", "")

    def test_main_prints_github_output_line(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            self.assertEqual(sp.main(["x", "fold", ""]), 0)
        line = buf.getvalue().strip()
        self.assertTrue(line.startswith("matrix="))
        self.assertEqual(json.loads(line[7:])["include"][0]["shard"], "fold")

    def test_rows_are_real_points_tsv_shards(self):
        tsv = (HERE / "screen-matrix" / "points.tsv").read_text().splitlines()
        shards = {l.split("\t")[1] for l in tsv if l and not l.startswith("#")}
        self.assertEqual({e["rows"] for e in sp.SHARDS}, shards)

    def test_dnd_shard_has_tablet_dnd_points(self):
        # DND_POINTS in screen-matrix.sh must match at least one tablets row, or the dnd shard runs nothing
        sh = (HERE / "screen-matrix.sh").read_text()
        rx = re.search(r"DND_POINTS=\$\{DND_POINTS:-'([^']+)'\}", sh).group(1)
        tsv = (HERE / "screen-matrix" / "points.tsv").read_text().splitlines()
        ids = [l.split("\t")[0] for l in tsv if l and not l.startswith("#") and l.split("\t")[1] == "tablets"]
        hits = [i for i in ids if re.search(rx, i)]
        self.assertGreaterEqual(len(hits), 2, hits)


class EdgePlan(unittest.TestCase):
    """probes=edge (#243): the edge pass runs in the dnd shard only, edge-only, on a panel that fits every point."""

    sh = (HERE / "screen-matrix.sh").read_text()
    tsv = [l.split("\t") for l in (HERE / "screen-matrix" / "points.tsv").read_text().splitlines()
           if l and not l.startswith("#")]

    def edge_ids(self):
        rx = re.search(r"EDGE_POINTS=\$\{EDGE_POINTS:-'([^']+)'\}", self.sh).group(1)
        return [r for r in self.tsv if re.search(rx, r[0])]

    def test_edge_goes_to_dnd_shard_only(self):
        m = by_name(sp.plan("all", "edge"))
        self.assertEqual(m["dnd"]["probes"], "edge-only")
        for n, e in m.items():
            if n != "dnd":
                self.assertNotIn("edge", e["probes"], n)

    def test_edge_with_dnd_keeps_both_in_dnd_and_dnd_inline_on_phones(self):
        m = by_name(sp.plan("all", "dnd,edge"))
        self.assertEqual(m["dnd"]["probes"], "dnd-only,edge-only")
        self.assertEqual(m["phones"]["probes"], "dnd")
        self.assertEqual(m["tablets"]["probes"], "")

    def test_edge_adds_dnd_shard_whatever_the_shards(self):
        m = by_name(sp.plan("phones", "edge"))
        self.assertEqual(sorted(m), ["dnd", "phones"])
        self.assertEqual(m["phones"]["probes"], "")
        self.assertEqual(m["dnd"]["probes"], "edge-only")  # no dnd-only: dnd was not asked for

    def test_dnd_by_name_with_edge(self):
        m = by_name(sp.plan("dnd", "edge"))
        self.assertEqual(sorted(m), ["dnd"])
        self.assertEqual(m["dnd"]["probes"], "dnd-only,edge-only")

    def test_no_edge_keeps_tablets_panel(self):
        self.assertEqual(by_name(sp.plan("dnd", ""))["dnd"]["skin"], by_name(sp.plan("tablets", ""))["tablets"]["skin"])

    def test_edge_panel_fits_every_edge_point(self):
        w, h = map(int, by_name(sp.plan("all", "edge"))["dnd"]["skin"].split("x"))
        for r in self.edge_ids():
            pw, ph = map(int, r[2].split("x"))
            self.assertLessEqual((pw, ph), (w, h), r[0])
            self.assertLessEqual(ph, h, r[0])

    def test_one_point_per_form_factor(self):
        ids = sorted(r[0] for r in self.edge_ids())
        self.assertEqual(ids, ["fold-open-v1", "p411-a51-v1", "rtl-phone-port-v1", "t10-port-v1"])
        self.assertTrue(all(r[7] == "launcher.json" for r in self.edge_ids()))

    def test_no_flip_by_default(self):
        self.assertIn("EDGE_FLIP_RE=${EDGE_FLIP_RE:-'^$'}", self.sh)

    def test_edge_only_rows_ignore_the_shard_filter_but_dnd_only_keeps_it(self):
        body = re.search(r"probe_only_for\(\) \{\n(.*?)\n\}", self.sh, re.S).group(1)
        dnd_line = next(l for l in body.splitlines() if "dnd-only" in l)
        edge_line = next(l for l in body.splitlines() if "edge-only" in l)
        self.assertIn('"$2" = "$SHARD"', dnd_line)
        self.assertNotIn("SHARD", edge_line)


class WorkflowWiring(unittest.TestCase):
    """screen-matrix.yml must use the plan script and the per-entry rows / probes (no inline shard list)."""

    yml = (ROOT / ".github" / "workflows" / "screen-matrix.yml").read_text()

    def test_plan_step_calls_script(self):
        self.assertIn('python3 scripts/screen_matrix_plan.py "$SHARDS" "$PROBES"', self.yml)

    def test_shard_runs_rows_with_matrix_probes(self):
        self.assertIn("ROWS: ${{ matrix.rows }}", self.yml)
        self.assertIn("MATRIX_PROBES: ${{ matrix.probes }}", self.yml)
        self.assertIn('--shard "$ROWS"', self.yml)
        self.assertIn('--out "screen-matrix-out/$SHARD"', self.yml)
        self.assertIn("name: screen-matrix-${{ matrix.shard }}", self.yml)

    def test_timeout_back_to_60(self):
        self.assertEqual(re.findall(r"timeout-minutes: *(\S+)", self.yml), ["60"])

    def test_plan_checkout_pinned_like_ci(self):
        ci = (ROOT / ".github" / "workflows" / "ci.yml").read_text()
        pin = re.search(r"actions/checkout@([0-9a-f]{40})", ci).group(1)
        plan_job = self.yml.split("\n  plan:\n", 1)[1].split("\n  build:\n", 1)[0]
        self.assertIn(f"actions/checkout@{pin}", plan_job)


class SummarizeProbeOnly(unittest.TestCase):
    def _point(self, root, name, meta, result=None, dnd=None, edge=None):
        d = os.path.join(root, name)
        os.makedirs(d)
        with open(os.path.join(d, "meta.txt"), "w") as f:
            f.write(meta)
        for fn, obj in (("result.json", result), ("dnd.json", dnd), ("edge-score.json", edge)):
            if obj is not None:
                with open(os.path.join(d, fn), "w") as f:
                    json.dump(obj, f)
        return d

    def _run(self, root):
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = c.cmd_summarize(root)
        with open(os.path.join(root, "summary.md")) as f:
            return rc, f.read()

    def test_probe_only_point_is_listed_apart_and_not_counted(self):
        with tempfile.TemporaryDirectory() as t:
            self._point(t, "tablets-t7-land-v1", "id=t7-land-v1\n",
                        {"point": "t7-land-v1", "verdict": "PASS", "checks": []})
            self._point(t, "dnd-t7-land-v1", "id=t7-land-v1\nprobe_only=dnd\n",
                        dnd={"results": [{"id": "grid", "ok": True}, {"id": "out-left", "ok": False},
                                         {"id": "pg-fwd", "ok": None}]})
            rc, md = self._run(t)
            self.assertEqual(rc, 0)
            self.assertIn("1 points, 0 not PASS", md)
            self.assertIn("## Probe-only points (1", md)
            self.assertIn("| dnd-t7-land-v1 | dnd | 1 PASS, 1 FAIL, 1 n/a (out-left) |", md)

    def test_probe_only_without_report_is_not_an_error(self):
        with tempfile.TemporaryDirectory() as t:
            self._point(t, "dnd-rtl-t7-port-v1", "probe_only=dnd\n")
            rc, md = self._run(t)
            self.assertEqual(rc, 0)
            self.assertIn("no dnd.json", md)
            self.assertNotIn("ERROR", md)
            # advisory (exit 0) but visible: flagged in the row and counted above the table
            self.assertIn("**NO RESULTS**", md)
            self.assertIn("**1 of 1 probe-only rows have no results**", md)

    def test_edge_point_reads_edge_score_json(self):
        with tempfile.TemporaryDirectory() as t:
            self._point(t, "dnd-fold-open-v1", "id=fold-open-v1\nprobe_only=edge\n",
                        edge={"results": [{"id": "base-corner-c0r0", "ok": True}, {"id": "base-gutter-L", "ok": False}]})
            rc, md = self._run(t)
            self.assertEqual(rc, 0)
            self.assertIn("| dnd-fold-open-v1 | edge | 1 PASS, 1 FAIL, 0 n/a (base-gutter-L) |", md)
            self.assertNotIn("have no results", md)

    def test_dnd_and_edge_on_one_point_get_a_row_each(self):
        with tempfile.TemporaryDirectory() as t:
            self._point(t, "dnd-x", "probe_only=dnd,edge\n", dnd={"results": [{"id": "grid", "ok": True}]})
            rc, md = self._run(t)
            self.assertEqual(rc, 0)
            self.assertIn("## Probe-only points (2", md)
            self.assertIn("| dnd-x | dnd | 1 PASS, 0 FAIL, 0 n/a |", md)
            self.assertIn("| dnd-x | edge | **NO RESULTS**: no edge-score.json", md)
            self.assertIn("**1 of 2 probe-only rows have no results**", md)

    def test_empty_results_count_as_no_results(self):
        with tempfile.TemporaryDirectory() as t:
            self._point(t, "dnd-y", "probe_only=edge\n", edge={"results": []})
            _, md = self._run(t)
            self.assertIn("edge-score.json has no cases", md)

    def test_summarize_closes_its_files(self):
        with tempfile.TemporaryDirectory() as t:
            self._point(t, "tablets-t7-land-v1", "id=t7-land-v1\n",
                        {"point": "t7-land-v1", "verdict": "PASS", "checks": []})
            self._point(t, "dnd-t7-land-v1", "probe_only=dnd\n", dnd={"results": []})
            with warnings.catch_warnings(record=True) as w:
                warnings.simplefilter("always", ResourceWarning)
                with redirect_stdout(io.StringIO()):
                    c.cmd_summarize(t)
                gc.collect()
            self.assertEqual([str(x.message) for x in w if issubclass(x.category, ResourceWarning)], [])

    def test_regular_point_without_result_still_errors(self):
        with tempfile.TemporaryDirectory() as t:
            self._point(t, "tablets-t7-port-v1", "id=t7-port-v1\n")
            rc, md = self._run(t)
            self.assertEqual(rc, 1)
            self.assertIn("ERROR", md)

    def test_empty_dir_fails(self):
        with tempfile.TemporaryDirectory() as t:
            rc, _ = self._run(t)
            self.assertEqual(rc, 1)


if __name__ == "__main__":
    unittest.main()
