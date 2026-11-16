#!/usr/bin/env python3
"""Shard plan for .github/workflows/screen-matrix.yml (docs/test-plan-screen-matrix.md §5).

  screen_matrix_plan.py <shards> <probes>    prints `matrix=<json>` for $GITHUB_OUTPUT

<shards> is a comma list of shard names or `all`; <probes> is the workflow's `probes` input (comma list, may be
empty). Each matrix entry carries:
  shard   artifact / output name (screen-matrix-<shard>)
  rows    points.tsv shard whose rows it runs (screen-matrix.sh --shard)
  probes  MATRIX_PROBES for that job
  api, target, arch, skin   emulator image and AVD panel

The live DnD probe (`dnd`, #187) used to run inside the tablets shard and pushed it past the 60 min timeout
(PR #246 raised it to 90). It now has its own `dnd` shard on the tablets image: `dnd-only` runs the probe on the
tablet DnD points without re-running F3-F7 or the checker, and the tablets shard no longer runs the probe. Phone
DnD points stay in the phones shard (it has headroom). The dnd shard is added whenever `dnd` is in <probes> and
the tablets shard is selected, or when it is asked for by name.
"""
from __future__ import annotations

import json
import sys

# skin = AVD panel (portrait). Android clamps `wm size` overrides to a multiple of the panel,
# so each panel is at least as big as every point in its shard (run 1 used the 320x640 default).
SHARDS = [
    {"shard": "small-a", "rows": "small-a", "api": 29, "target": "default", "arch": "x86", "skin": "720x1440"},
    {"shard": "small-b", "rows": "small-b", "api": 29, "target": "default", "arch": "x86", "skin": "720x1440"},
    {"shard": "phones", "rows": "phones", "api": 35, "target": "google_apis", "arch": "x86_64", "skin": "1344x2992"},
    {"shard": "tablets", "rows": "tablets", "api": 35, "target": "google_apis", "arch": "x86_64", "skin": "1600x2560"},
    {"shard": "dnd", "rows": "tablets", "api": 35, "target": "google_apis", "arch": "x86_64", "skin": "1600x2560"},
    {"shard": "fold", "rows": "fold", "api": 36, "target": "google_apis", "arch": "x86_64", "skin": "2208x2400"},
]
NAMES = [s["shard"] for s in SHARDS]


def split(s):
    return [p.strip() for p in (s or "").split(",") if p.strip()]


def plan(shards, probes):
    want = set(split(shards)) or {"all"}
    unknown = sorted(want - set(NAMES) - {"all"})
    if unknown:
        raise SystemExit(f"unknown shard(s) {unknown}; known: {', '.join(NAMES)} or all")
    pr = split(probes)
    dnd = "dnd" in pr
    rest = [p for p in pr if p != "dnd"]
    inc = []
    for s in SHARDS:
        name = s["shard"]
        if name == "dnd":
            # by name, or implied by probes=dnd with the tablets shard in the run
            if not ("dnd" in want or (dnd and ("all" in want or "tablets" in want))):
                continue
            p = ["dnd-only"]
        else:
            if "all" not in want and name not in want:
                continue
            # tablets hands its DnD points to the dnd shard; every other shard keeps the probe inline
            p = rest if name == "tablets" else pr
        inc.append(dict(s, probes=",".join(p)))
    if not inc:
        raise SystemExit(f"no shard matches {sorted(want)}")
    return {"include": inc}


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    print("matrix=" + json.dumps(plan(argv[1], argv[2])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
