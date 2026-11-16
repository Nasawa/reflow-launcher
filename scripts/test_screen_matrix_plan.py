#!/usr/bin/env python3
"""Unit tests for scripts/screen_matrix_plan.py (screen-matrix.yml shard plan) and the probe-only rows in
screen_matrix_check.py summarize. Run from repo root: python3 scripts/test_screen_matrix_plan.py
"""
from __future__ import annotations

import io
import json
import os
import pathlib
import re
import sys
import tempfile
import unittest
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


class SummarizeProbeOnly(unittest.TestCase):
    def _point(self, root, name, meta, result=None, dnd=None):
        d = os.path.join(root, name)
        os.makedirs(d)
        with open(os.path.join(d, "meta.txt"), "w") as f:
            f.write(meta)
        for fn, obj in (("result.json", result), ("dnd.json", dnd)):
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
