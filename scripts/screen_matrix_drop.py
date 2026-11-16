#!/usr/bin/env python3
"""Edge drag-and-drop probe helper (dev8 edge pass, test-plan-screen-matrix §4; #243).

  edge <xml> <png> <rtl 0|1> <density>   targets for edge_probe in screen-matrix.sh (runs on CI, needs Pillow)
  score <point dir>                      score the edge pass -> edge-score.json / edge-score.md (no Pillow)

Fixture edge.json: page 1 "Drag" holds "Edge C" at (1,1) and "Edge D" at (2,3) on a 4x5 grid, so every edge and
corner cell is empty and the column / row pitch come from the two tile centres. A tile's rect is its icon (blue
settings gear found in the screenshot) united with its label; the probe presses the icon centre, and DragController
drops at startRect.center + finger displacement, so a finger target F = wanted drop point + (icon centre - tile
centre). Ported from the QA branch (qa/matrix-243-edge, cfc5772 + 984e4ef); the drop spring-back targets are not.
Runs in the CI dnd shard only (MATRIX_PROBES=edge-only, scripts/screen_matrix_plan.py), probe-only and advisory.
"""
from __future__ import annotations

import glob
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import screen_matrix_check as c  # noqa: E402


def blue_mask(png):
    from PIL import Image
    im = Image.open(png).convert('RGB')
    W, H = im.size
    px = im.load()
    return im, W, H, px


def is_gear(p):
    r, g, b = p
    return b > 200 and r < 110 and 100 < g < 170


def icon_box(img, lb, scan_up):
    """bbox of gear pixels in a column above (or inside, for a desc-only node) label box lb."""
    im, W, H, px = img
    l, t, r, b = lb
    w = r - l
    x0, x1 = max(0, l - w // 2), min(W, r + w // 2)
    y0, y1 = (max(0, t - scan_up), t) if scan_up else (t, b)
    xs, ys = [], []
    for y in range(y0, y1, 2):
        for x in range(x0, x1, 2):
            if is_gear(px[x, y]):
                xs.append(x); ys.append(y)
    if len(xs) < 20:
        return None
    return (min(xs), min(ys), max(xs) + 1, max(ys) + 1)


def node(nodes, rx):
    for n in nodes:
        if re.search(rx, n.text or '') or re.search(rx, n.desc or ''):
            return n
    return None


def tile(nodes, img, label, dpx):
    n = None
    for x in nodes:
        if (x.text or '') == label:
            n = x; break
    hidden = False
    if n is None:
        for x in nodes:
            if (x.desc or '').startswith(label):
                n = x; hidden = True; break
    if n is None:
        return None
    ib = icon_box(img, n.b, 0 if hidden else int(90 * dpx))
    if ib is None:
        return None
    u = ib if hidden else (min(ib[0], n.b[0]), ib[1], max(ib[2], n.b[2]), n.b[3])
    # DragController.drop() uses the tile node's own rect (rects[key] = boundsInRoot of the .tile() node, the
    # same node as the merged clickable), not the icon+label union. On tall cells that centre sits well below
    # the icon (p411-a51-v1-a36: 76 px), so use the smallest clickable node that contains the label.
    best = None
    for x in nodes:
        if x.click and x is not n and x.b[0] <= n.b[0] and x.b[1] <= n.b[1] and x.b[2] >= n.b[2] and x.b[3] >= n.b[3]:
            a = (x.b[2] - x.b[0]) * (x.b[3] - x.b[1])
            if a < W_LIMIT * (n.b[2] - n.b[0]) * (n.b[3] - n.b[1]) and (best is None or a < best[0]):
                best = (a, x.b)
    if hidden and n.click:
        best = (0, n.b)
    if best is not None:
        u = best[1]
    return {'icon': ((ib[0] + ib[2]) // 2, (ib[1] + ib[3]) // 2), 'iw': ib[2] - ib[0],
            'c': ((u[0] + u[2]) // 2, (u[1] + u[3]) // 2), 'rect': u}


W_LIMIT = 60  # a tile node is at most ~60x its label's area (rules out page/pager containers)


def edge(xml, png, rtl, dens):
    """dev8 edge drag-and-drop pass (test-plan-screen-matrix §4). Fixture edge.json: "Edge C" at (1,1) and
    "Edge D" at (2,3) on the 4x5 grid, so every edge and corner cell is empty. Prints `CX CY` (C's press point),
    then one `case fx fy kind col row` line per drop: kind cell = edge/corner cell (col,row) must commit; the drop
    point is the cell centre pushed 0.25 pitch toward the outer side(s), so it is near the grid edge but inside
    the cell. gutter = middle of a side margin (refused). outer = 2 dp from the window / rail edge on C's row
    (info: a Compact fill grid may legitimately reach the window edge; it must commit the edge column of that row
    or be refused, never another cell)."""
    nodes = c.load(xml) or []
    dpx = dens / 160.0
    img = blue_mask(png)
    W, H = img[1], img[2]
    C, D = tile(nodes, img, 'Edge C', dpx), tile(nodes, img, 'Edge D', dpx)
    if not C or not D:
        return ['ERR tiles']
    s = -1 if rtl else 1
    pw = abs(D['c'][0] - C['c'][0])
    ph = (D['c'][1] - C['c'][1]) / 2.0
    dc = (C['icon'][0] - C['c'][0], C['icon'][1] - C['c'][1])
    f = lambda x, y: (int(round(x + dc[0])), int(round(y + dc[1])))
    cx = lambda col: C['c'][0] + s * (col - 1) * pw
    cy = lambda row: C['c'][1] + (row - 1) * ph
    out = [f"CX={C['icon'][0]}", f"CY={C['icon'][1]}", f"PW={int(pw)}", f"PH={int(ph)}", f"W={W}", f"H={H}"]
    cases = []
    for row in range(5):
        for col in range(4):
            if col not in (0, 3) and row not in (0, 4):
                continue
            x, y = cx(col), cy(row)
            if col == 0: x -= s * 0.25 * pw
            if col == 3: x += s * 0.25 * pw
            if row == 0: y -= 0.25 * ph
            if row == 4: y += 0.25 * ph
            corner = col in (0, 3) and row in (0, 4)
            fx, fy = f(x, y)
            cases.append((f"{'corner' if corner else 'edge'}-c{col}r{row}", fx, fy, 'cell', col, row))
    left = min(cx(0), cx(3)) - pw / 2
    right = max(cx(0), cx(3)) + pw / 2
    pad = 8 * dpx + 4 * dpx
    dock = node(nodes, r', dock$')
    rail = None
    if dock is not None and (dock.b[1] + dock.b[3]) // 2 < H * 0.6:
        dcx = (dock.b[0] + dock.b[2]) // 2
        if dcx < W * 0.25:
            rail = ('left', dock.b[2] + int(16 * dpx))
        elif dcx > W * 0.75:
            rail = ('right', dock.b[0] - int(16 * dpx))
    out.append(f"RAIL={rail[0] if rail else ''}")
    lo = rail[1] if rail and rail[0] == 'left' else 0
    hi = rail[1] if rail and rail[0] == 'right' else W
    ry = cy(2)  # middle row, empty in both outer columns
    for side, m in (('L', (lo, left - pad)), ('R', (right + pad, hi))):
        if m[1] - m[0] >= 12 * dpx:
            fx, fy = f((m[0] + m[1]) / 2, ry)
            cases.append((f"gutter-{side}", fx, fy, 'gutter', -1, -1))
        else:
            out.append(f"# gutter-{side} n/a: margin {int(m[1] - m[0])} px < 12 dp")
    # outer: 2 dp inside the window edge (or the rail's inner edge minus 16 dp already applied above)
    rail_l = rail is not None and rail[0] == 'left'
    rail_r = rail is not None and rail[0] == 'right'
    for side, x in (('L', (lo if rail_l else 0) + 2 * dpx), ('R', (hi if rail_r else W) - 2 * dpx)):
        ecol = (0 if not rtl else 3) if side == 'L' else (3 if not rtl else 0)
        fx, fy = f(x, ry)
        cases.append((f"outer-{side}", fx, fy, 'outer', ecol, 2))
    for k in cases:
        out.append('CASE ' + ' '.join(str(v) for v in k))
    return out


# --- scorer (advisory): one result per edge-cases.txt line, from the case's config before/after and crash log.
# kind cell   PASS when Edge C now sits at (col,row) on the Drag page; FAIL when the drop was refused or landed
#             elsewhere. outer: PASS on (col,row) (the edge column of C's row) or a refusal; FAIL anywhere else.
# gutter      PASS when the drop was refused (Edge C still at (1,1)); FAIL when it moved.
# Every kind: FAIL on a crash / our ANR, on Edge C lost or duplicated, or on any other item moving.
# n/a (ok None) when the case left no usable config (harness), or the fixture was not seeded.
C_ID, D_ID, HOME = 'a-edge-c', 'a-edge-d', 'home'
C_START = (1, 1)


def _load(path):
    try:
        with open(path, encoding='utf-8') as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def _read(path):
    try:
        with open(path, encoding='utf-8', errors='replace') as f:
            return f.read()
    except OSError:
        return ''


def placements(cfg):
    """[(where, item id, (x, y) or None)] for every page item and dock item, in file order."""
    out = []
    for pg in cfg.get('pages') or []:
        for it in pg.get('items') or []:
            out.append((pg.get('id'), it.get('id'), (it.get('x'), it.get('y'))))
    for it in cfg.get('dock') or []:
        out.append(('dock', it.get('id'), None))
    return out


def crash_of(text, pkg):
    """Why the crash file counts as our crash / ANR, or None (another process's crash is not ours)."""
    if re.search(r'ANR in ' + re.escape(pkg) + r'\b', text):
        return 'ANR in the launcher'
    if 'FATAL EXCEPTION' in text and pkg in text:
        return 'launcher crash (FATAL EXCEPTION)'
    return None


def score_case(d, cs, kind, col, row, pkg):
    res = {'id': cs, 'kind': kind, 'want': [col, row] if kind != 'gutter' else None}
    before = _load(os.path.join(d, f'pg-{cs}-config-before.json'))
    after = _load(os.path.join(d, f'pg-{cs}-config-after.json'))
    crash = crash_of(_read(os.path.join(d, f'pg-{cs}-crash.txt')), pkg)
    dump = _read(os.path.join(d, f'pg-{cs}-1-after.xml'))
    if crash is None and re.search(r"isn't responding|isn&apos;t responding", dump) and 'System UI' not in dump:
        crash = 'ANR dialog in the after dump'
    if crash:
        return dict(res, ok=False, detail=crash)
    if not isinstance(before, dict) or not isinstance(after, dict):
        return dict(res, ok=None, detail='no config before/after (harness; see run.txt)')
    pb, pa = placements(before), placements(after)
    cb = [(w, xy) for w, i, xy in pb if i == C_ID]
    ca = [(w, xy) for w, i, xy in pa if i == C_ID]
    if cb != [(HOME, C_START)]:
        return dict(res, ok=None, detail=f'fixture not seeded: Edge C before = {cb}')
    if len(ca) != 1:
        return dict(res, ok=False, detail=f'Edge C {"lost" if not ca else "duplicated"} after the drop: {ca}')
    others_b = sorted(((w, i, xy) for w, i, xy in pb if i != C_ID), key=repr)
    others_a = sorted(((w, i, xy) for w, i, xy in pa if i != C_ID), key=repr)
    if others_b != others_a:
        moved = sorted(set(map(repr, others_b)) ^ set(map(repr, others_a)))
        return dict(res, ok=False, detail=f'another item changed: {", ".join(moved)}')
    where, xy = ca[0]
    got = f'{where} ({xy[0]},{xy[1]})' if xy else where
    refused = (where, xy) == (HOME, C_START)
    if kind == 'gutter':
        return dict(res, ok=refused, detail='refused' if refused else f'gutter drop committed: Edge C -> {got}')
    hit = where == HOME and xy == (col, row)
    if kind == 'cell':
        if hit:
            return dict(res, ok=True, detail=f'committed ({col},{row})')
        return dict(res, ok=False, detail='refused (no commit)' if refused else f'wrong cell: Edge C -> {got}')
    if kind == 'outer':
        if hit or refused:
            return dict(res, ok=True, detail=f'committed edge column ({col},{row})' if hit else 'refused')
        return dict(res, ok=False, detail=f'outer drop landed off the edge column: Edge C -> {got}')
    return dict(res, ok=None, detail=f'unknown case kind {kind!r}')


def score(d, pkg):
    """Score the edge pass in point dir d; writes edge-score.json / edge-score.md and returns the result list."""
    results = []
    # an orientation whose targets failed has no CASE lines: say so instead of scoring nothing
    for t in sorted(glob.glob(os.path.join(d, 'sb-edge-*.txt'))):
        o = os.path.basename(t)[len('sb-edge-'):-len('.txt')]
        txt = _read(t)
        if not re.search(r'^CASE ', txt, re.M):
            err = next((l for l in txt.splitlines() if l.startswith('ERR')), 'no CASE lines')
            results.append({'id': f'{o}-targets', 'kind': 'targets', 'want': None, 'ok': None,
                            'detail': f'no edge targets ({err}); see run.txt'})
    for line in _read(os.path.join(d, 'edge-cases.txt')).splitlines():
        f = line.split()
        if len(f) < 4:
            continue
        cs, kind = f[0], f[1]
        try:
            col, row = int(f[2]), int(f[3])
        except ValueError:
            results.append({'id': cs, 'kind': kind, 'want': None, 'ok': None, 'detail': f'bad case line: {line}'})
            continue
        if 'skipped=budget' in f[4:]:
            results.append({'id': cs, 'kind': kind, 'want': [col, row] if kind != 'gutter' else None, 'ok': None,
                            'detail': 'not run: EDGE_BUDGET_S reached (shard time budget)'})
            continue
        results.append(score_case(d, cs, kind, col, row, pkg))
    n = {True: 0, False: 0, None: 0}
    for r in results:
        n[r['ok']] += 1
    meta = {}
    for l in _read(os.path.join(d, 'meta.txt')).splitlines():
        k, _, v = l.partition('=')
        meta.setdefault(k, v)
    point = meta.get('id') or os.path.basename(os.path.normpath(d))
    with open(os.path.join(d, 'edge-score.json'), 'w', encoding='utf-8') as f:
        json.dump({'probe': 'edge', 'point': point, 'results': results}, f, indent=1, ensure_ascii=False)
    head = f'# Edge probe ({point}): {n[True]} PASS, {n[False]} FAIL, {n[None]} n/a (advisory)'
    if not results:
        head += ' - no cases ran; see run.txt'
    md = [head, '', '| case | kind | want | ok | detail |', '|---|---|---|---|---|']
    for r in results:
        ok = {True: 'PASS', False: 'FAIL', None: 'n/a'}[r['ok']]
        want = '-' if not r['want'] else f"({r['want'][0]},{r['want'][1]})"
        md.append(f"| {r['id']} | {r['kind']} | {want} | {ok} | {r['detail']} |")
    with open(os.path.join(d, 'edge-score.md'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(md) + '\n')
    print(head)
    return results


def main(argv):
    if len(argv) >= 6 and argv[1] == 'edge':
        for line in edge(argv[2], argv[3], argv[4] == '1', int(argv[5])):
            print(line)
        return 0
    if len(argv) == 3 and argv[1] == 'score':
        from pkg import PKG
        score(argv[2], PKG)
        return 0  # advisory: the tally goes to the summary's probe-only table, never the exit code
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv))
