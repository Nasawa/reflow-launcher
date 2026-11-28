#!/usr/bin/env python3
"""Validate launcher.json the way the phone does, and optionally rewrite it canonically.

    python3 scripts/validate.py launcher.json            # check; exit 0 = valid, 1 = invalid
    python3 scripts/validate.py --format launcher.json   # check, then rewrite in canonical form
    python3 scripts/validate.py --format --check f.json  # exit 1 if the file isn't already canonical

Mirrors the app (ARCHITECTURE.md §2, §17 AQ-3; app/.../config/{JsonPreScan,Validator,ConfigCodec}.kt):
  1. byte cap (2 MiB), strict UTF-8, BOM stripped, git conflict markers;
  2. pre-scan: nesting depth <= 16, no duplicate keys, no NUL, no lone surrogates;
  3. JSON syntax, schemaVersion (only 1);
  4. JSON Schema (schema/launcher-config.v1.schema.json) when `jsonschema` is installed;
  5. the phone's semantic rules: unique IDs across the file, no two page items in one cell, cells inside
     `grid`, dock/folder items without x/y, no nested folders, page items need x/y, <= 2000 items.
Error lines use the phone's wording and paths, so they match `.configlauncher/status.json`.

--format writes what the phone writes (AQ-2): 2-space indent, schema key order, `x-` keys after known
keys (sorted), LF, trailing newline, UTF-8 without BOM; the deprecated `unsorted` key is dropped (AF-2).
Item order and unspecified keys like `grid` are kept as written; only a phone edit changes those.
Stdlib only, plus optional `jsonschema`.
"""
import argparse, json, math, pathlib, re, sys
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
from zoneinfo import ZoneInfo

MAX_BYTES = 2 * 1024 * 1024
MAX_DEPTH = 16
MAX_ITEMS = 2000
MAX_STRING = 256
MAX_TAGS = 16
CURRENT = 1
ID = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
PKG = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
CLS = re.compile(r"^[A-Za-z0-9_.$]+$")
CATEGORIES = {"game", "audio", "video", "image", "social", "news", "maps", "productivity", "accessibility", "undefined"}
MAX_ELEMENTS = 32
MAX_OVERRIDES = 32
FL_CARDS_READOUT = 6       # Elements.kt: M1 readout keys on card (#360 RW-1)
FEATURE_LEVEL = FL_CARDS_READOUT  # 1 = #79 container, 2 = title bar, 3 = retired, 4 = card, 5 = appWidget (#298), 6 = FL_CARDS_READOUT
ELEMENT_TYPES = {}         # known element types, name -> class (Elements.types); filled below the classes
PLACEMENT = ("page", "x", "y", "w", "h")
TYPE = re.compile(r"^[a-zA-Z][a-zA-Z0-9]{0,31}$")
SCHEMA = pathlib.Path(__file__).resolve().parent.parent / "schema" / "launcher-config.v1.schema.json"


class Num(str):
    """A JSON number kept as its literal text, so x- values round-trip verbatim (like kotlinx JsonPrimitive)."""
    def is_int(self):
        return re.fullmatch(r"-?\d+", self) is not None


class DupKey(ValueError):
    pass


def _pairs(pairs):
    out = {}
    for k, v in pairs:
        if k in out:
            raise DupKey(k)
        out[k] = v
    return out


# ---------------------------------------------------------------- parsing (ConfigCodec.decode + JsonPreScan)

def git_marker_line(text):
    lines = text.split("\n")
    for i, l in enumerate(lines):
        if l.startswith("<<<<<<<"):
            rest = lines[i + 1:]
            if any(r.startswith("=======") for r in rest) and any(r.startswith(">>>>>>>") for r in rest):
                return i + 1
            return None
    return None


NUMBER = re.compile(r"-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?")   # RFC 8259 §6, ASCII digits
TOKEN_END = ' \t\n\r",:{}[]'


def bad_token(t, line):
    """JsonPreScan.badToken (#120): a bare token must be an RFC 8259 number or true/false/null."""
    if t in ("true", "false", "null") or NUMBER.fullmatch(t):
        return None
    kind = "number" if t[0] in "+-.0123456789" else "value"
    shown = t[:40] + "…" if len(t) > 40 else t
    return f'invalid {kind} "{shown}" (line {line})'


def prescan(text):
    """Line-for-line port of JsonPreScan.check: depth, duplicate keys, NUL, lone surrogates, bare tokens, same messages."""
    stack, expect_key, line, i, n = [], False, 1, 0, len(text)
    esc = {"n": 0x0A, "t": 0x09, "r": 0x0D, "b": 0x08, "f": 0x0C}
    while i < n:
        c = text[i]
        if c == "\n":
            line += 1
        elif c in "{[":
            stack.append(set() if c == "{" else None)
            if len(stack) > MAX_DEPTH:
                return f"nested deeper than {MAX_DEPTH} levels (line {line})"
            expect_key = c == "{"
        elif c in "}]":
            if stack:
                stack.pop()
            expect_key = False
        elif c == ",":
            expect_key = bool(stack) and stack[-1] is not None
        elif c == ":":
            expect_key = False
        elif c in " \t\r":
            pass
        elif c == '"':
            units = []  # UTF-16 code units, as Kotlin sees the string
            i += 1
            while i < n and text[i] != '"':
                ch = text[i]
                if ch == "\\" and i + 1 < n:
                    i += 1
                    e = text[i]
                    if e == "u":
                        h = text[i + 1:i + 5]
                        i += 4
                        try:
                            u = int(h, 16)
                        except ValueError:
                            return f"bad \\u escape (line {line})"
                        units.append(u)
                    else:
                        units.append(esc.get(e, ord(e)))
                else:
                    cp = ord(ch)
                    units += [0xD800 + ((cp - 0x10000) >> 10), 0xDC00 + ((cp - 0x10000) & 0x3FF)] if cp > 0xFFFF else [cp]
                if units and units[-1] == 0:
                    return f"NUL character in a string (line {line})"
                i += 1
            k = 0
            while k < len(units):
                u = units[k]
                if 0xD800 <= u <= 0xDBFF:
                    if k + 1 >= len(units) or not 0xDC00 <= units[k + 1] <= 0xDFFF:
                        return f"unpaired surrogate in a string (line {line})"
                    k += 1
                elif 0xDC00 <= u <= 0xDFFF:
                    return f"unpaired surrogate in a string (line {line})"
                k += 1
            if expect_key:
                keys = stack[-1] if stack else None
                key = tuple(units)
                if keys is not None:
                    if key in keys:
                        name = struct_utf16(units)
                        return f'duplicate key "{name}" (line {line})'
                    keys.add(key)
                expect_key = False
        else:
            start = i
            while i + 1 < n and text[i + 1] not in TOKEN_END:
                i += 1
            b = bad_token(text[start:i + 1], line)
            if b:
                return b
        i += 1
    return None


def struct_utf16(units):
    return b"".join(u.to_bytes(2, "little") for u in units).decode("utf-16-le", "replace")


def parse(raw):
    """Returns (tree, errors)."""
    if len(raw) > MAX_BYTES:
        return None, [f"file is larger than {MAX_BYTES // 1024 // 1024} MiB"]
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError:
        return None, ["not valid UTF-8"]
    text = text.removeprefix("\ufeff")
    gl = git_marker_line(text)
    if gl is not None:
        return None, [f"not valid JSON: git merge conflict markers (line {gl}); finish the merge in git"]
    p = prescan(text)
    if p:
        return None, [f"not valid JSON: {p}"]
    try:
        tree = json.loads(text, object_pairs_hook=_pairs, parse_int=Num, parse_float=Num,
                          parse_constant=lambda c: (_ for _ in ()).throw(ValueError(c)))
    except DupKey as e:
        return None, [f'not valid JSON: duplicate key "{e.args[0]}"']
    except (ValueError, RecursionError) as e:
        return None, [f"not valid JSON: {str(e).splitlines()[0]}"]
    if isinstance(tree, dict):
        v = tree.get("schemaVersion")
        if isinstance(v, Num) and v.is_int():
            v = int(v)
            if v > CURRENT:
                return tree, [f"schemaVersion {v} is newer than this launcher supports ({CURRENT}); update the app"]
            if v < CURRENT:
                return tree, [f"schemaVersion {v} is not supported"]
    return tree, []


# ---------------------------------------------------------------- semantic validation (Validator.kt)

def _is_str(v):
    return isinstance(v, str) and not isinstance(v, Num)


def _cps(s):
    return len(s)  # Python str length is code points, like JSON Schema


class Run:
    def __init__(self):
        self.errors, self.ids, self.items, self.columns, self.rows = [], {}, 0, 4, 5
        self.page_cells = {}   # page id -> {(x, y): path}, for grid-placed elements (#79 Stage 1)
        self.singles = {}      # type -> path of the first shown element (one titleBar per file)
        self.shown_ids = {}    # element id -> type, for shown elements
        self.override_entries = []   # (element id, path, value), checked once elements are known

    def err(self, path, msg):
        self.errors.append(f"{path}: {msg}" if path else msg)

    def obj(self, e, path):
        if isinstance(e, dict):
            return e
        self.err(path, "must be an object")
        return None

    def keys(self, o, path, allowed, required, x_keys=True):
        for k in o:
            if k not in allowed and not (x_keys and k.startswith("x-")):
                self.err(path, f'unknown key "{k}"')
        for k in required:
            if k not in o:
                self.err(path, f'missing required "{k}"')

    def str(self, o, k, path, mx=None, mn=0, rx=None):
        if k not in o:
            return None
        s = o[k]
        if not _is_str(s):
            self.err(f"{path}.{k}", "must be a string")
            return None
        if _cps(s) > (mx or MAX_STRING):
            self.err(f"{path}.{k}", f"longer than {mx or MAX_STRING} characters")
        if _cps(s) < mn:
            self.err(f"{path}.{k}", "must not be empty")
        if rx is not None and not rx.match(s):
            self.err(f"{path}.{k}", f'"{s}" is not valid')
        return s

    def int(self, o, k, path, mn, mx=None):
        if k not in o:
            return None
        v = o[k]
        n = int(v) if isinstance(v, Num) and v.is_int() else None
        if n is None:
            self.err(f"{path}.{k}", "must be an integer")
            return None
        if n < mn or (mx is not None and n > mx):
            self.err(f"{path}.{k}", f"{n} is out of range {mn}..{mx if mx is not None else '∞'}")
        return n

    def arr(self, o, k, path, mx=None, mn=0):
        if k not in o:
            return None
        a = o[k]
        if not isinstance(a, list):
            self.err(f"{path}.{k}", "must be an array")
            return None
        if mx is not None and len(a) > mx:
            self.err(f"{path}.{k}", f"has {len(a)} entries, at most {mx} allowed")
        if len(a) < mn:
            self.err(f"{path}.{k}", f"needs at least {mn} entries")
        return a

    def root(self, e):
        o = self.obj(e, "")
        if o is None:
            return
        self.keys(o, "", {"$schema", "schemaVersion", "revision", "grid", "pages", "dock", "drawer", "elements", "unsorted"}, ["schemaVersion", "pages"])
        self.str(o, "$schema", "")
        if "schemaVersion" in o and not (isinstance(o["schemaVersion"], Num) and o["schemaVersion"] == "1"):
            self.err("schemaVersion", "must be 1")
        self.int(o, "revision", "", 0)
        if "grid" in o:
            go = self.obj(o["grid"], "grid")
            if go is not None:
                self.keys(go, "grid", {"columns", "rows"}, [], x_keys=False)
                c = self.int(go, "columns", "grid", 2, 12)
                if c is not None:
                    self.columns = min(max(c, 2), 12)
                r = self.int(go, "rows", "grid", 2, 12)
                if r is not None:
                    self.rows = min(max(r, 2), 12)
        for i, p in enumerate(self.arr(o, "pages", "", mx=20, mn=1) or []):
            self.page(p, f"pages[{i}]")
        for i, it in enumerate(self.arr(o, "dock", "", mx=7) or []):
            self.item(it, f"dock[{i}]", "dock")
        if "drawer" in o:
            self.drawer(o["drawer"])
        for i, e in enumerate(self.arr(o, "elements", "", mx=MAX_ELEMENTS) or []):
            self.element(e, f"elements[{i}]")
        for k, vp, vo in self.override_entries:
            if k in self.shown_ids:
                ELEMENT_TYPES[self.shown_ids[k]].override(self, vo, vp)
        if self.items > MAX_ITEMS:
            self.err("", f"{self.items} items in total, at most {MAX_ITEMS} allowed")
        if "unsorted" in o:
            uo = self.obj(o["unsorted"], "unsorted")
            if uo is not None:
                self.keys(uo, "unsorted", {"mode", "pageTitle"}, [], x_keys=False)
                m = self.str(uo, "mode", "unsorted")
                if m is not None and m not in ("drawer", "page", "hidden"):
                    self.err("unsorted.mode", "must be drawer, page or hidden")
                self.str(uo, "pageTitle", "unsorted", mx=40)

    def bool(self, o, k, path):
        if k not in o:
            return None
        if not isinstance(o[k], bool):
            self.err(f"{path}.{k}", "must be true or false")
            return None
        return o[k]

    def element(self, e, path):
        """#79: id, type, requires for every element; a skipped one (see skip()) gets nothing more."""
        o = self.obj(e, path)
        if o is None:
            return
        for k in ("id", "type"):
            if k not in o:
                self.err(path, f'missing required "{k}"')
        if "id" in o:
            self.id(o, path)
        t = self.str(o, "type", path, rx=TYPE)
        self.int(o, "requires", path, 1)
        self.placement(o, path)
        if t is None or skip(o) is not None or t not in ELEMENT_TYPES:
            return
        kind = ELEMENT_TYPES[t]
        if kind.SINGLE:
            if t in self.singles:
                self.err(path, f"only one {t} is allowed ({self.singles[t]} is already one).")
            else:
                self.singles[t] = path
        kind.validate(self, o, path)
        if _is_str(o.get("id")):
            self.shown_ids.setdefault(o["id"], t)

    def placement(self, o, path):
        """#79 Stage 1: grid placement for every element, before the type check; skipped ones still reserve cells."""
        placed = [k for k in PLACEMENT if k in o]
        self.str(o, "anchor", path, mx=32)
        if "anchor" in o and placed:
            self.err(path, 'has both "anchor" and a grid position; use one')
        if not placed:
            return
        for k in PLACEMENT:
            if k not in o:
                self.err(path, f'missing required "{k}" (grid-placed elements need page, x, y, w and h)')
        page = self.str(o, "page", path, rx=ID)
        x, y = self.int(o, "x", path, 0, 11), self.int(o, "y", path, 0, 11)
        w, h = self.int(o, "w", path, 1, 12), self.int(o, "h", path, 1, 12)
        if None in (page, x, y, w, h):
            return
        cells = self.page_cells.get(page)
        if cells is None:
            self.err(f"{path}.page", f'no page with id "{page}"')
            return
        if x + w > self.columns:
            self.err(path, f"x {x} + w {w} is outside the grid ({self.columns} columns)")
        if y + h > self.rows:
            self.err(path, f"y {y} + h {h} is outside the grid ({self.rows} rows)")
        for cy in range(y, min(y + h, self.rows)):
            for cx in range(x, min(x + w, self.columns)):
                other = cells.get((cx, cy))
                cells[(cx, cy)] = path
                if other is not None:
                    self.err(path, f"overlaps {other} at cell ({cx}, {cy})")
                    return

    def overrides(self, e, path):
        o = self.obj(e, path)
        if o is None:
            return
        if len(o) > MAX_OVERRIDES:
            self.err(path, f"has {len(o)} entries, at most {MAX_OVERRIDES} allowed")
        for k, v in o.items():
            if not ID.match(k):
                self.err(path, f'"{k}" is not a valid element id')
                continue
            vp = f"{path}.{k}"
            vo = self.obj(v, vp)
            if vo is None:
                continue
            self.keys(vo, vp, {"enabled", "content", "style"}, [], x_keys=False)
            if not vo:
                self.err(vp, "needs at least one of enabled, content or style")
            self.bool(vo, "enabled", vp)
            c = self.obj(vo["content"], f"{vp}.content") if "content" in vo else None
            st = self.obj(vo["style"], f"{vp}.style") if "style" in vo else None
            if ("content" not in vo or c is not None) and ("style" not in vo or st is not None):
                self.override_entries.append((k, vp, vo))

    def id(self, o, path):
        s = self.str(o, "id", path, rx=ID)
        if s is None:
            return
        prev = self.ids.get(s)
        self.ids[s] = path
        if prev is not None:
            self.err(f"{path}.id", f'duplicate id "{s}" (also at {prev})')

    def page(self, e, path):
        o = self.obj(e, path)
        if o is None:
            return
        self.keys(o, path, {"id", "title", "items", "overrides"}, ["id", "items"])
        self.id(o, path)
        self.str(o, "title", path, mx=40)
        if "overrides" in o:
            self.overrides(o["overrides"], f"{path}.overrides")
        cells = {}
        if _is_str(o.get("id")):
            self.page_cells.setdefault(o["id"], cells)
        for i, it in enumerate(self.arr(o, "items", path, mx=144) or []):
            ip = f"{path}.items[{i}]"
            self.item(it, ip, "page")
            if not isinstance(it, dict):
                continue
            x, y = it.get("x"), it.get("y")
            x = int(x) if isinstance(x, Num) and x.is_int() else None
            y = int(y) if isinstance(y, Num) and y.is_int() else None
            if x is not None and y is not None:
                if x >= self.columns:
                    self.err(f"{ip}.x", f"{x} is outside the grid ({self.columns} columns)")
                if y >= self.rows:
                    self.err(f"{ip}.y", f"{y} is outside the grid ({self.rows} rows)")
                other = cells.get((x, y))
                cells[(x, y)] = ip
                if other is not None:
                    self.err(ip, f"overlaps {other} at cell ({x}, {y})")

    def item(self, e, path, ctx):
        o = self.obj(e, path)
        if o is None:
            return
        self.items += 1
        t = o.get("type") if _is_str(o.get("type")) else None
        common = {"id", "type", "label", "x", "y"}
        if t == "app":
            self.keys(o, path, common | {"package", "activity", "profile", "tags"}, ["id", "type", "package"])
            self.str(o, "package", path, rx=PKG)
            self.str(o, "activity", path, rx=CLS)
            self.profile(o, path)
            tags = self.arr(o, "tags", path, mx=MAX_TAGS)
            if tags is not None:
                seen = set()
                for i, tg in enumerate(tags):
                    if not _is_str(tg):
                        self.err(f"{path}.tags[{i}]", "must be a string")
                    else:
                        if len(tg) > 40:
                            self.err(f"{path}.tags[{i}]", "longer than 40 characters")
                        if tg in seen:
                            self.err(f"{path}.tags", f'duplicate tag "{tg}"')
                        seen.add(tg)
        elif t == "shortcut":
            self.keys(o, path, common | {"package", "shortcutId", "profile"}, ["id", "type", "package", "shortcutId"])
            self.str(o, "package", path, rx=PKG)
            self.str(o, "shortcutId", path, mx=256, mn=1)
            self.profile(o, path)
        elif t == "folder":
            self.keys(o, path, common | {"items"}, ["id", "type", "items"])
            if ctx == "folder":
                self.err(path, "folders cannot be nested")
            else:
                for i, it in enumerate(self.arr(o, "items", path, mx=48) or []):
                    self.item(it, f"{path}.items[{i}]", "folder")
        elif t is None:
            self.err(f"{path}.type", "must be a string" if "type" in o else 'missing required "type"')
            return
        else:
            self.err(f"{path}.type", f'unknown type "{t}" (app, shortcut or folder)')
            return
        if "id" in o:
            self.id(o, path)
        self.str(o, "label", path, mx=60)
        self.int(o, "x", path, 0, 11)
        self.int(o, "y", path, 0, 11)
        if ctx == "page":
            if "x" not in o:
                self.err(path, 'page items need "x"')
            if "y" not in o:
                self.err(path, 'page items need "y"')
        elif "x" in o or "y" in o:
            self.err(path, f"x/y are not allowed in {'the dock' if ctx == 'dock' else 'folders'}")

    def profile(self, o, path):
        p = self.str(o, "profile", path)
        if p is not None and p not in ("personal", "work"):
            self.err(f"{path}.profile", "must be personal or work")

    def drawer(self, e):
        o = self.obj(e, "drawer")
        if o is None:
            return
        self.keys(o, "drawer", {"sort", "hidden", "rules"}, [], x_keys=False)
        s = self.str(o, "sort", "drawer")
        if s is not None and s not in ("label", "installTime"):
            self.err("drawer.sort", "must be label or installTime")
        h = self.arr(o, "hidden", "drawer")
        if h is not None:
            seen = set()
            for i, p in enumerate(h):
                if not _is_str(p) or not PKG.match(p) or len(p) > MAX_STRING:
                    self.err(f"drawer.hidden[{i}]", f"must be a package name (at most {MAX_STRING} characters)")
                elif p in seen:
                    self.err("drawer.hidden", f'duplicate "{p}"')
                else:
                    seen.add(p)
        for i, r in enumerate(self.arr(o, "rules", "drawer", mx=64) or []):
            rp = f"drawer.rules[{i}]"
            ro = self.obj(r, rp)
            if ro is None:
                continue
            self.keys(ro, rp, {"id", "match", "group"}, ["id", "match", "group"], x_keys=False)
            if "id" in ro:
                self.id(ro, rp)
            self.str(ro, "group", rp, mx=40, mn=1)
            if "match" in ro:
                mo = self.obj(ro["match"], f"{rp}.match")
                if mo is None:
                    continue
                self.keys(mo, f"{rp}.match", {"packages", "packagePrefix", "tag", "category"}, [], x_keys=False)
                if not mo:
                    self.err(f"{rp}.match", "needs at least one condition")
                for j, p in enumerate(self.arr(mo, "packages", f"{rp}.match") or []):
                    if not _is_str(p) or not PKG.match(p) or len(p) > MAX_STRING:
                        self.err(f"{rp}.match.packages[{j}]", f"must be a package name (at most {MAX_STRING} characters)")
                self.str(mo, "packagePrefix", f"{rp}.match", mx=128)
                self.str(mo, "tag", f"{rp}.match", mx=40)
                c = self.str(mo, "category", f"{rp}.match")
                if c is not None and c not in CATEGORIES:
                    self.err(f"{rp}.match.category", f'unknown category "{c}"')


def _plain(v):
    """Num -> int/float for jsonschema."""
    if isinstance(v, Num):
        return int(v) if v.is_int() else float(v)
    if isinstance(v, dict):
        return {k: _plain(x) for k, x in v.items()}
    if isinstance(v, list):
        return [_plain(x) for x in v]
    return v


def schema_errors(tree):
    try:
        import jsonschema
    except ImportError:
        return None
    v = jsonschema.Draft202012Validator(json.loads(SCHEMA.read_text()))
    out = []
    for e in sorted(v.iter_errors(_plain(tree)), key=lambda e: list(map(str, e.absolute_path))):
        path = "".join(f"[{p}]" if isinstance(p, int) else (f".{p}" if i else p) for i, p in enumerate(e.absolute_path))
        out.append(f"schema: {path or '(root)'}: {e.message}")
    return out


# ---------------------------------------------------------------- title bar (TitleBar.kt, Template.kt; #79 level 2)

class Template:
    NAMES = ("page", "pageNumber", "pageCount", "time", "date")
    SHORTHANDS = {"pageTitle": "page", "clock": "time", "date": "date"}
    LETTERS = {"y": 19, "M": 5, "d": 2, "E": 5, "a": 1, "H": 2, "h": 2, "k": 2, "K": 2, "m": 2}
    PUNCTUATION = " .,:/-"
    MAX_TEMPLATE, MAX_TOKENS, MAX_PATTERN = 120, 8, 32

    @staticmethod
    def check(s, path):
        """The ux.md §15.1 message for the first problem in template s, or None."""
        if s in Template.SHORTHANDS:
            return None
        if len(s) > Template.MAX_TEMPLATE:
            return f"{path} is {len(s)} characters; the maximum is {Template.MAX_TEMPLATE}."
        if "\n" in s or "\r" in s:
            return f"{path}: A template is one line. Remove the line break."
        i, tokens, n = 0, 0, len(s)
        while i < n:
            c = s[i]
            if c == "{" and s[i + 1:i + 2] == "{":
                i += 2
            elif c == "}" and s[i + 1:i + 2] == "}":
                i += 2
            elif c == "}":
                return f'{path}: the "}}" at character {i + 1} has no opening "{{". For a literal brace, write }}}}.'
            elif c == "{":
                j = s.find("}", i + 1)
                if j < 0:
                    return f'{path}: the "{{" at character {i + 1} isn\'t closed. For a literal brace, write {{{{.'
                inner = s[i + 1:j]
                name, colon, pattern = inner.partition(":")
                if name not in Template.NAMES:
                    return f"{path}: unknown token {{{name}}}. Use page, pageNumber, pageCount, time or date."
                if colon:
                    if name not in ("time", "date"):
                        return f"{path}: {{{inner}}} doesn't take a pattern. Only {{time}} and {{date}} do."
                    if not pattern:
                        return f"{path}: {{{name}:}} has an empty pattern. Write {{{name}}} for the default format."
                    if len(pattern) > Template.MAX_PATTERN:
                        return f"{path}: the pattern in {{{name}:…}} is {len(pattern)} characters; the maximum is {Template.MAX_PATTERN}."
                    bad = Template.bad_in_pattern(pattern)
                    if bad == "'":
                        return f"{path}: A ' quote isn't closed. Add a closing ' after the text."
                    if bad is not None and (len(bad) > 1 or bad in Template.LETTERS):
                        mx = Template.LETTERS[bad[0]]
                        return f"{path}: {bad} is too long. Use at most {mx}, like {bad[:mx]}."
                    if bad is not None:
                        return (f'{path}: "{bad}" isn\'t allowed in {{{inner}}}. '
                                "Use y M d E a H h k K m; seconds and time zones aren't supported.")
                tokens += 1
                i = j + 1
            else:
                i += 1
        if tokens > Template.MAX_TOKENS:
            return f"{path} has {tokens} tokens; the maximum is {Template.MAX_TOKENS}."
        return None

    @staticmethod
    def bad_in_pattern(p):
        i = 0
        while i < len(p):
            c = p[i]
            if c == "'":
                if p[i + 1:i + 2] == "'":
                    i += 2
                    continue
                j = i + 1
                while True:
                    if j >= len(p):
                        return "'"
                    if p[j] == "'":
                        if p[j + 1:j + 2] == "'":
                            j += 2
                            continue
                        break
                    j += 1
                i = j + 1
            elif c in Template.LETTERS:
                j = i
                while j < len(p) and p[j] == c:
                    j += 1
                if j - i > Template.LETTERS[c]:
                    return p[i:j]
                i = j
            elif c in Template.PUNCTUATION:
                i += 1
            else:
                return c
        return None


class TitleBar:
    """TitleBar.kt: the first element type (feature level 2). Widgets will register the same way."""
    LEVEL, SINGLE = 2, True
    KEYS = ("id", "type", "requires", "enabled", "anchor", "content", "style")
    SLOTS = ("start", "center", "end")
    STYLE_KEYS = ("size", "weight", "color", "align", "padding", "backdrop")
    COLOR_TOKENS = ("light", "dark", "onWallpaper", "primary", "secondary", "onSurface", "surface")
    HEX = re.compile(r"^#[0-9A-Fa-f]{6}$")
    SIZES = {"small": 14, "medium": 18, "large": 24}
    WEIGHTS = {"regular": 400, "medium": 500, "bold": 700}
    STATIC = {"light": "#FFFFFF", "onWallpaper": "#FFFFFF", "dark": "#1B1B1F"}

    @staticmethod
    def validate(r, o, path):
        r.keys(o, path, set(TitleBar.KEYS), [])
        r.bool(o, "enabled", path)
        a = r.str(o, "anchor", path)
        if a is not None and a != "top":
            r.err("", f'{path}.anchor must be "top".')
        if "content" in o:
            TitleBar.content(r, o["content"], f"{path}.content")
        if "style" in o:
            TitleBar.style(r, o["style"], f"{path}.style")

    @staticmethod
    def override(r, o, path):
        if "content" in o:
            TitleBar.content(r, o["content"], f"{path}.content")
        if "style" in o:
            TitleBar.style(r, o["style"], f"{path}.style")

    @staticmethod
    def content(r, e, path):
        o = r.obj(e, path)
        if o is None:
            return
        r.keys(o, path, {"slots"}, [], x_keys=False)
        if "slots" not in o:
            return
        slots = r.obj(o["slots"], f"{path}.slots")
        if slots is None:
            return
        r.keys(slots, f"{path}.slots", set(TitleBar.SLOTS), [], x_keys=False)
        for k in TitleBar.SLOTS:
            if k not in slots:
                continue
            if not _is_str(slots[k]):
                r.err(f"{path}.slots.{k}", "must be a string")
                continue
            m = Template.check(slots[k], f"{path}.slots.{k}")
            if m:
                r.err("", m)

    @staticmethod
    def _color(r, v, path):
        if not _is_str(v) or (v not in TitleBar.COLOR_TOKENS and not TitleBar.HEX.match(v)):
            r.err("", f"{path} must be #RRGGBB or one of " + ", ".join(TitleBar.COLOR_TOKENS) + ".")

    @staticmethod
    def style(r, e, path):
        o = r.obj(e, path)
        if o is None:
            return
        r.keys(o, path, set(TitleBar.STYLE_KEYS), [], x_keys=False)
        if "size" in o:
            v = o["size"]
            ok = v in TitleBar.SIZES if _is_str(v) else isinstance(v, Num) and 10.0 <= float(v) <= 40.0
            if not ok:
                r.err("", f"{path}.size must be small, medium or large, or a number from 10 to 40.")
        if "weight" in o:
            v = o["weight"]
            ok = v in TitleBar.WEIGHTS if _is_str(v) else (
                isinstance(v, Num) and v.is_int() and 300 <= int(v) <= 800 and int(v) % 100 == 0)
            if not ok:
                r.err("", f"{path}.weight must be regular, medium or bold, or 300 to 800 in steps of 100.")
        if "color" in o:
            v = o["color"]
            if isinstance(v, dict):
                r.keys(v, f"{path}.color", {"light", "dark"}, [], x_keys=False)
                if not v:
                    r.err("", f"{path}.color needs light or dark.")
                for k in ("light", "dark"):
                    if k in v:
                        TitleBar._color(r, v[k], f"{path}.color.{k}")
            else:
                TitleBar._color(r, v, f"{path}.color")
        if "align" in o:
            a = r.obj(o["align"], f"{path}.align")
            if a is not None:
                r.keys(a, f"{path}.align", set(TitleBar.SLOTS), [], x_keys=False)
                for k in TitleBar.SLOTS:
                    s = r.str(a, k, f"{path}.align")
                    if s is not None and s not in TitleBar.SLOTS:
                        r.err("", f"{path}.align.{k} must be start, center or end.")
        if "padding" in o:
            pd = r.obj(o["padding"], f"{path}.padding")
            if pd is not None:
                r.keys(pd, f"{path}.padding", {"horizontal", "vertical"}, [], x_keys=False)
                r.int(pd, "horizontal", f"{path}.padding", 0, 32)
                r.int(pd, "vertical", f"{path}.padding", 0, 24)
        if "backdrop" in o:
            v = o["backdrop"]
            if isinstance(v, dict):
                r.keys(v, f"{path}.backdrop", {"color", "opacity", "radius"}, ["color"], x_keys=False)
                if "color" in v:
                    TitleBar._color(r, v["color"], f"{path}.backdrop.color")
                r.int(v, "opacity", f"{path}.backdrop", 0, 100)
                r.int(v, "radius", f"{path}.backdrop", 0, 24)
            elif not (_is_str(v) and v in ("auto", "none", "scrim")):
                r.err("", f"{path}.backdrop must be auto, none or scrim, or an object with color.")

    @staticmethod
    def canonical(o):
        def order(d, keys, inner=lambda k, v: v):
            return {k: inner(k, d[k]) for k in keys if k in d}

        def style(k, v):
            if not isinstance(v, dict):
                return v
            return order(v, {"color": ("light", "dark"), "align": TitleBar.SLOTS, "padding": ("horizontal", "vertical"),
                             "backdrop": ("color", "opacity", "radius")}.get(k, tuple(v)))
        out = {}
        for k in TitleBar.KEYS:
            if k in o:
                v = o[k]
                if k == "content":
                    v = order(v, ("slots",), lambda _, s: order(s, TitleBar.SLOTS))
                elif k == "style":
                    v = order(v, TitleBar.STYLE_KEYS, style)
                out[k] = v
        out.update(_x(o))
        return out

    @staticmethod
    def _hex(s):
        if not _is_str(s):
            return None
        return s if TitleBar.HEX.match(s) else TitleBar.STATIC.get(s)

    @staticmethod
    def contrast(style):
        b = style.get("backdrop")
        if _is_str(b):
            if b != "scrim":
                return None
            bg, opacity = "#000000", 60
        elif isinstance(b, dict):
            bg = TitleBar._hex(b.get("color"))
            if bg is None:
                return None
            op = b.get("opacity")
            opacity = int(op) if isinstance(op, Num) and op.is_int() else 100
        else:
            return None
        col = style.get("color")
        if col is None:
            texts = ["light"]
        elif isinstance(col, dict):
            texts = [col.get("light", "light"), col.get("dark", "light")]
        else:
            texts = [col]
        texts = [h for h in map(TitleBar._hex, texts) if h is not None]
        if not texts:
            return None
        a = opacity / 100.0
        walls = ["#FFFFFF"] if opacity >= 100 else ["#FFFFFF", "#000000"]
        rgb = lambda h: [float(int(h[i:i + 2], 16)) for i in (1, 3, 5)]

        def lum(c):
            l = [(v / 12.92) if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in (x / 255.0 for x in c)]
            return 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2]
        m = None
        for w in walls:
            back = [bb * a + wl * (1 - a) for bb, wl in zip(rgb(bg), rgb(w))]
            for t in texts:
                la, lb = lum(rgb(t)), lum(back)
                r = (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
                m = r if m is None else min(m, r)
        return m

    THEME = ("primary", "secondary", "onSurface", "surface")

    @staticmethod
    def uses_theme(o):
        """#125: the phone measures this bar's contrast with its theme colours (TitleBar.phoneContrast); we can't."""
        st = o.get("style")
        if not isinstance(st, dict):
            return False
        b = st.get("backdrop")
        if b != "scrim" and not isinstance(b, dict):
            return False
        col = st.get("color")
        texts = [col.get("light", "light"), col.get("dark", "light")] if isinstance(col, dict) else [col]
        if isinstance(b, dict):
            texts.append(b.get("color"))
        return any(t in TitleBar.THEME for t in texts if _is_str(t))

    @staticmethod
    def warnings(o, path):
        st = o.get("style")
        return TitleBar.style_warnings(st, path) if isinstance(st, dict) else []

    @staticmethod
    def override_style(base, ov):
        """#118: TitleBar.overrideStyle: the merged style when the override touches colours and doesn't hide the bar."""
        if ov.get("enabled") is False:
            return None
        over = ov.get("style")
        if not isinstance(over, dict) or ("color" not in over and "backdrop" not in over):
            return None
        st = base.get("style") if isinstance(base.get("style"), dict) else {}
        return {**st, **over}

    @staticmethod
    def override_warnings(base, ov, path):
        st = TitleBar.override_style(base, ov)
        return TitleBar.style_warnings(st, path) if st is not None else []

    @staticmethod
    def style_warnings(st, path):
        r = TitleBar.contrast(st)
        if r is None or r >= 4.5:
            return []
        t = math.floor(r * 10)
        return [f"{path}.style.color: contrast {t // 10}.{t % 10}:1 on the backdrop (4.5:1 recommended). Shown anyway."]


ELEMENT_TYPES["titleBar"] = TitleBar


class Thresholds:
    """#361 (RW-4): evaluate a card threshold list. Inclusive lower bound (74/75/76)."""
    DEFAULT = "accent"

    @staticmethod
    def tone(percent, entries, default=None):
        t = Thresholds.DEFAULT if default is None else default
        for fr, tone in entries:
            if percent >= fr:
                t = tone
            else:
                break
        return t

    @staticmethod
    def percent(value, maxv):
        return 0.0 if maxv == 0 else (value / maxv) * 100.0

    @staticmethod
    def tick_percent(index, count):
        return 0.0 if count <= 0 else index * 100.0 / count

    @staticmethod
    def tick_tone(index, count, entries, default=None):
        return Thresholds.tone(Thresholds.tick_percent(index, count), entries, default)

    @staticmethod
    def segment_percent(index, segments):
        return 0.0 if segments <= 0 else (index + 1) * 100.0 / segments

    @staticmethod
    def segment_tone(index, segments, entries, default=None):
        return Thresholds.tone(Thresholds.segment_percent(index, segments), entries, default)


class Bindings:
    """#361 (RW-4): card binding grammar, the five formatters, {relative}, ambient tokens, evaluation."""
    MISSING = "—"
    MAX_FORMATTERS = 2
    MAX_ROUND = 10
    SEG = re.compile(r"^([A-Za-z0-9_-]+)((\[[0-9]+])*)$")
    IDX = re.compile(r"\[([0-9]+)]")
    SOURCE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_-]*$")
    AMBIENT = ("time", "date", "battery", "batteryFraction", "batteryState", "nextAlarm", "relative")
    FORMATTERS = ("round", "duration", "since", "time", "default")

    @staticmethod
    def legal_path(path):
        return bool(path) and all(Bindings.SEG.fullmatch(p) for p in path.split("."))

    @staticmethod
    def check(s, path):
        msg, _ = Bindings.parse(s, path)
        return msg

    @staticmethod
    def parse(s, path="elements[0]"):
        if "\n" in s or "\r" in s:
            return f"{path}: A template is one line. Remove the line break.", None
        parts, lit, tokens, i, n = [], [], 0, 0, len(s)
        while i < n:
            c = s[i]
            if c == "{" and i + 1 < n and s[i + 1] == "{":
                lit.append("{"); i += 2; continue
            if c == "}" and i + 1 < n and s[i + 1] == "}":
                lit.append("}"); i += 2; continue
            if c == "}":
                return f"{path}: the \"}}\" at character {i + 1} has no opening \"{{\". For a literal brace, write }}}}.", None
            if c == "{":
                j = s.find("}", i + 1)
                if j < 0:
                    return f"{path}: the \"{{\" at character {i + 1} isn't closed. For a literal brace, write {{{{.", None
                inner = s[i + 1:j]
                err, tok = Bindings._token(inner, path)
                if err:
                    return err, None
                if lit:
                    parts.append(("lit", "".join(lit))); lit.clear()
                parts.append(("tok", tok))
                tokens += 1
                i = j + 1
                continue
            lit.append(c); i += 1
        if lit:
            parts.append(("lit", "".join(lit)))
        if tokens > Template.MAX_TOKENS:
            return f"{path} has {tokens} tokens; the maximum is {Template.MAX_TOKENS}.", None
        return None, parts

    @staticmethod
    def check_file_token(inner, path):
        err, tok = Bindings._token(inner, path)
        if err:
            return err
        if tok["kind"] not in ("data", "fetch"):
            return f"{path}: \"{{{inner}}}\" isn't a file name token, so this card wasn't loaded. Use {{data:…}} or {{fetch:…}} in a picture src."
        if tok["formatters"]:
            return f"{path}: {{{inner}}} has a formatter, so this card wasn't loaded. A picture file name can only use {{data:…}} or {{fetch:…}}."
        return None

    @staticmethod
    def _token(inner, path):
        bits, buf = [], []
        for ch in inner:
            if ch == "|":
                bits.append("".join(buf)); buf.clear()
            else:
                buf.append(ch)
        bits.append("".join(buf))
        if not bits or bits[0] == "":
            return (
                f"{path}: unknown token {{{inner}}}, so this card wasn't loaded. "
                "Use {data:…}, {fetch:…}, {time}, {date}, {battery}, {batteryFraction}, {batteryState}, {nextAlarm} or {relative}."
            ), None
        if len(bits) - 1 > Bindings.MAX_FORMATTERS:
            return (
                f"{path}: {{{inner}}} has {len(bits) - 1} formatters, so this card wasn't loaded. "
                f"A token can have at most {Bindings.MAX_FORMATTERS}."
            ), None
        fmts = []
        for f in bits[1:]:
            err, fmt = Bindings._formatter(f, inner, path)
            if err:
                return err, None
            fmts.append(fmt)
        return Bindings._head(bits[0], inner, path, fmts)

    @staticmethod
    def _pattern_err(name, arg, inner, path):
        if arg == "":
            return f"{path}: {{{name}:}} has an empty pattern. Write {{{name}}} for the default format."
        length = len(arg)  # code points: templates are BMP in practice; match Kotlin codePointCount for BMP
        length = len(arg.encode("utf-16-le")) // 2  # close to codePointCount for BMP and non-BMP
        if length > Template.MAX_PATTERN:
            return f"{path}: the pattern in {{{name}:…}} is {length} characters; the maximum is {Template.MAX_PATTERN}."
        bad = Template.bad_in_pattern(arg)
        if bad is None:
            return None
        if bad == "'":
            return f"{path}: A ' quote isn't closed. Add a closing ' after the text."
        if len(bad) > 1 or bad[0] in Template.LETTERS:
            mx = Template.LETTERS[bad[0]]
            return f"{path}: {bad} is too long. Use at most {mx}, like {bad[:mx]}."
        return f"{path}: \"{bad}\" isn't allowed in {{{name}:{arg}}}. Use y M d E a H h k K m; seconds and time zones aren't supported."

    @staticmethod
    def _formatter(f, inner, path):
        colon = f.find(":")
        name, arg = (f, None) if colon < 0 else (f[:colon], f[colon + 1:])
        if name == "round":
            if arg is None:
                n = 0
            else:
                try:
                    n = int(arg)
                    if str(n) != arg or n < 0:
                        n = None
                except ValueError:
                    n = None
            if n is None or not (0 <= n <= Bindings.MAX_ROUND):
                return (
                    f"{path}: the n in {{{inner}}} isn't a whole number from 0 to {Bindings.MAX_ROUND}, "
                    "so this card wasn't loaded. Write {…|round} or {…|round:1}."
                ), None
            return None, ("round", n)
        if name == "duration":
            if arg is not None:
                return f"{path}: duration in {{{inner}}} doesn't take an argument, so this card wasn't loaded. Write {{…|duration}}.", None
            return None, ("duration", None)
        if name == "since":
            if arg is not None:
                return f"{path}: since in {{{inner}}} doesn't take an argument, so this card wasn't loaded. Write {{…|since}}.", None
            return None, ("since", None)
        if name == "time":
            if arg is not None:
                err = Bindings._pattern_err("time", arg, inner, path)
                if err:
                    return err, None
            return None, ("time", arg)
        if name == "default":
            if arg is None:
                return f"{path}: default in {{{inner}}} needs a text value, so this card wasn't loaded. Write {{…|default:text}}.", None
            return None, ("default", arg)
        return (
            f"{path}: unknown formatter \"{name}\" in {{{inner}}}, so this card wasn't loaded. Use round, duration, since, time or default."
        ), None

    @staticmethod
    def _head(head, inner, path, fmts):
        colon = head.find(":")
        name, arg = (head, None) if colon < 0 else (head[:colon], head[colon + 1:])
        tok = {"raw": inner, "kind": name, "path": None, "source": None, "pattern": None, "formatters": fmts}
        if name == "data":
            if arg is None or not Bindings.legal_path(arg):
                return (
                    f"{path}: {{{inner}}} isn't a data path, so this card wasn't loaded. "
                    "Use a path of dots and [index] like {data:updatedAt}."
                ), None
            tok["path"] = arg
            return None, tok
        if name == "fetch":
            dot = arg.find(".") if arg else -1
            source = arg[:dot] if dot > 0 else None
            rest = arg[dot + 1:] if dot > 0 else None
            if arg is None or source is None or not Bindings.SOURCE.fullmatch(source) or not rest or not Bindings.legal_path(rest):
                return (
                    f"{path}: {{{inner}}} isn't a fetch path, so this card wasn't loaded. "
                    "Write {fetch:source.path}, with a source name and a path of dots and [index]."
                ), None
            tok["path"], tok["source"] = rest, source
            return None, tok
        if name in ("time", "date"):
            if arg is not None:
                err = Bindings._pattern_err(name, arg, inner, path)
                if err:
                    return err, None
            tok["kind"] = name
            tok["pattern"] = arg
            return None, tok
        if name in ("battery", "batteryFraction", "batteryState"):
            if arg is not None:
                return (
                    f"{path}: {{{name}}} doesn't take a path, so this card wasn't loaded. Write {{{name}}}."
                ), None
            return None, tok
        if name == "nextAlarm":
            if arg is not None:
                err = Bindings._pattern_err("nextAlarm", arg, inner, path)
                if err:
                    return err, None
            tok["pattern"] = arg
            return None, tok
        if name == "relative":
            if arg is None or arg == "":
                p = "updatedAt"
            elif arg.startswith("data.") and Bindings.legal_path(arg[5:]):
                p = arg[5:]
            elif Bindings.legal_path(arg):
                p = arg
            else:
                return (
                    f"{path}: {{relative}} doesn't take a pattern, so this card wasn't loaded. Write {{relative}} or {{relative:updatedAt}}."
                ), None
            tok["path"] = p
            return None, tok
        return (
            f"{path}: unknown token {{{inner}}}, so this card wasn't loaded. "
            "Use {data:…}, {fetch:…}, {time}, {date}, {battery}, {batteryFraction}, {batteryState}, {nextAlarm} or {relative}."
        ), None

    @staticmethod
    def check_as_of(s, path):
        p = s[5:] if s.startswith("data.") and Bindings.legal_path(s[5:]) else s
        if Bindings.legal_path(p):
            return None
        return f"{path}: \"{s}\" isn't a data path, so this card wasn't loaded. Use a path of dots and [index] like updatedAt."

    @staticmethod
    def as_of_path(s):
        return s[5:] if s.startswith("data.") and Bindings.legal_path(s[5:]) else s

    @staticmethod
    def lookup(root, path):
        if root is None:
            return None
        cur = root
        for seg in path.split("."):
            m = Bindings.SEG.fullmatch(seg)
            if m is None or not isinstance(cur, dict):
                return None
            cur = cur.get(m.group(1))
            if cur is None:
                return None
            for ix in Bindings.IDX.finditer(m.group(2)):
                i = int(ix.group(1))
                if not isinstance(cur, list) or i >= len(cur):
                    return None
                cur = cur[i]
        return cur

    @staticmethod
    def scalar(root, path):
        """None if missing/null/structured. ('empty', '') if empty string. ('num', n, text) or ('text', s)."""
        v = Bindings.lookup(root, path)
        if v is None:
            return None
        if isinstance(v, (dict, list)):
            return None
        if v is None:
            return None
        if _is_str(v):
            return ("empty", "") if v == "" else ("text", v)
        if isinstance(v, bool):
            return ("text", "true" if v else "false")
        if isinstance(v, Num) or isinstance(v, (int, float)):
            try:
                n = float(v)
            except (TypeError, ValueError):
                return ("text", str(v))
            return ("num", n, str(v) if isinstance(v, Num) else (str(int(v)) if isinstance(v, int) and not isinstance(v, bool) else str(v)))
        return None

    @staticmethod
    def round(n, digits):
        q = Decimal(1).scaleb(-digits)
        v = Decimal(str(n) if not isinstance(n, float) else format(n, ".15g")).quantize(q, rounding=ROUND_HALF_UP)
        s = format(v, "f")
        if "." in s:
            s = s.rstrip("0").rstrip(".")
        return "0" if s in ("-0",) else s

    @staticmethod
    def format_duration(seconds):
        neg = seconds < 0
        s = abs(int(seconds))
        h, rem = divmod(s, 3600)
        m, sec = divmod(rem, 60)
        if h > 0 and m > 0:
            body = f"{h}h {m}m"
        elif h > 0:
            body = f"{h}h"
        elif m > 0 and sec > 0:
            body = f"{m}m {sec}s"
        elif m > 0:
            body = f"{m}m"
        else:
            body = f"{sec}s"
        return f"-{body}" if neg else body

    @staticmethod
    def relative(at, now):
        sec = int((now - at).total_seconds())
        if sec < 0:
            ahead = -sec
            if ahead < 60:
                return "just now"
            if ahead < 3600:
                return f"{ahead // 60} min from now"
            if ahead < 86400:
                return f"{ahead // 3600} h from now"
            return f"{ahead // 86400} d from now"
        if sec < 60:
            return "just now"
        if sec < 3600:
            return f"{sec // 60} min ago"
        if sec < 86400:
            return f"{sec // 3600} h ago"
        return f"{sec // 86400} d ago"

    @staticmethod
    def battery_state(env):
        b = env.get("batteryPercent")
        if b is None:
            return ""
        if env.get("full") or (env.get("charging") and b >= 100):
            return "Full"
        if env.get("charging"):
            return "Charging"
        if b <= 15:
            return "Low"
        return ""

    @staticmethod
    def parse_time(text, zone):
        if not text:
            return None
        from datetime import datetime as dt
        try:
            return dt.fromisoformat(text.replace("Z", "+00:00"))
        except ValueError:
            pass
        try:
            return dt.fromisoformat(text).replace(tzinfo=zone) if dt.fromisoformat(text).tzinfo is None else dt.fromisoformat(text)
        except ValueError:
            return None

    @staticmethod
    def _as_aware(at, zone):
        if at.tzinfo is None:
            return at.replace(tzinfo=zone)
        return at

    @staticmethod
    def format_instant(at, pattern, env, time=True):
        zone = env["zone"]
        loc = env.get("locale") or "en_US"
        zdt = Bindings._as_aware(at, zone).astimezone(zone)
        if pattern is None:
            if time:
                pattern = "H:mm" if env.get("is24Hour") else "h:mm a"
            else:
                pattern = "MMM d, yyyy"
        try:
            return Bindings._java_pattern(zdt, pattern, loc)
        except Exception:
            return Bindings.MISSING

    @staticmethod
    def _java_pattern(zdt, pattern, loc):
        """A small DateTimeFormatter subset: y M d E a H h k K m and quoted literals."""
        import calendar
        out, i, n = [], 0, len(pattern)
        # locale weekday / month / ampm: use English for en_* to match typical JVM Locale.US
        am = "AM"; pm = "PM"
        months_full = ["January","February","March","April","May","June","July","August","September","October","November","December"]
        months_abbr = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"]
        days_full = ["Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"]
        days_abbr = ["Mon","Tue","Wed","Thu","Fri","Sat","Sun"]
        while i < n:
            c = pattern[i]
            if c == "'":
                j = i + 1
                buf = []
                while j < n:
                    if pattern[j] == "'":
                        if j + 1 < n and pattern[j + 1] == "'":
                            buf.append("'"); j += 2; continue
                        break
                    buf.append(pattern[j]); j += 1
                out.append("".join(buf))
                i = j + 1
                continue
            if c in "yMdEaHhKkmt":
                j = i
                while j < n and pattern[j] == c:
                    j += 1
                w = j - i
                if c == "y":
                    ys = f"{zdt.year:0{w}d}"
                    out.append(ys[-w:] if w < 4 else f"{zdt.year:0{w}d}")
                elif c == "M":
                    mo = zdt.month
                    out.append(str(mo) if w == 1 else (f"{mo:02d}" if w == 2 else (months_abbr[mo-1] if w <= 3 else months_full[mo-1])))
                elif c == "d":
                    out.append(str(zdt.day) if w == 1 else f"{zdt.day:02d}")
                elif c == "E":
                    # Java E is day-of-week; Monday=0 in Python weekday()
                    wd = zdt.weekday()
                    out.append(days_full[wd] if w >= 4 else days_abbr[wd])
                elif c == "a":
                    out.append(am if zdt.hour < 12 else pm)
                elif c == "H":
                    out.append(str(zdt.hour) if w == 1 else f"{zdt.hour:02d}")
                elif c == "h":
                    h = zdt.hour % 12 or 12
                    out.append(str(h) if w == 1 else f"{h:02d}")
                elif c == "k":
                    k = 24 if zdt.hour == 0 else zdt.hour
                    out.append(str(k) if w == 1 else f"{k:02d}")
                elif c == "K":
                    k = zdt.hour % 12
                    out.append(str(k) if w == 1 else f"{k:02d}")
                elif c == "m":
                    out.append(str(zdt.minute) if w == 1 else f"{zdt.minute:02d}")
                else:
                    out.append(c * w)
                i = j
                continue
            out.append(c); i += 1
        return "".join(out)

    @staticmethod
    def expand(s, env, path="elements[0]"):
        err, parts = Bindings.parse(s, path)
        if err or parts is None:
            return {"text": Bindings.MISSING, "empty": False, "number": None}
        return Bindings.expand_parts(parts, env)

    @staticmethod
    def expand_parts(parts, env):
        if not parts:
            return {"text": "", "empty": True, "number": None}
        if len(parts) == 1 and parts[0][0] == "tok":
            return Bindings._expand_tok(parts[0][1], env)
        sb, any_ = [], False
        for kind, val in parts:
            if kind == "lit":
                sb.append(val)
                if val:
                    any_ = True
            else:
                v = Bindings._expand_tok(val, env)
                if not v["empty"]:
                    sb.append(v["text"]); any_ = True
        text = "".join(sb)
        if not any_ and text == "":
            return {"text": "", "empty": True, "number": None}
        return {"text": text, "empty": False, "number": None}

    @staticmethod
    def _fraction_text(n):
        s = format(Decimal(str(n)).normalize(), "f")
        if s == "-0":
            return "0"
        return s

    @staticmethod
    def _expand_tok(tok, env):
        missing = empty = False
        number = None
        text = ""
        kind = tok["kind"]
        if kind == "data":
            s = Bindings.scalar(env.get("data"), tok["path"])
            if s is None:
                missing = True
            elif s[0] == "empty":
                empty = True
            elif s[0] == "num":
                number, text = s[1], s[2]
            else:
                text = s[1]
        elif kind == "fetch":
            src = (env.get("fetch") or {}).get(tok["source"])
            s = Bindings.scalar(src, tok["path"])
            if s is None:
                missing = True
            elif s[0] == "empty":
                empty = True
            elif s[0] == "num":
                number, text = s[1], s[2]
            else:
                text = s[1]
        elif kind == "time":
            text = Bindings.format_instant(env["now"], tok.get("pattern"), env, True)
        elif kind == "date":
            text = Bindings.format_instant(env["now"], tok.get("pattern"), env, False)
        elif kind == "battery":
            b = env.get("batteryPercent")
            if b is None:
                missing = True
            else:
                number, text = float(b), str(int(b))
        elif kind == "batteryFraction":
            b = env.get("batteryPercent")
            if b is None:
                missing = True
            else:
                number = max(0, min(100, int(b))) / 100.0
                text = Bindings._fraction_text(number)
        elif kind == "batteryState":
            text = Bindings.battery_state(env)
            if text == "":
                empty = True
        elif kind == "nextAlarm":
            at = env.get("nextAlarm")
            if at is None:
                empty = True
            else:
                text = Bindings.format_instant(at, tok.get("pattern"), env, True)
        elif kind == "relative":
            raw = Bindings.scalar(env.get("data"), tok["path"])
            at = Bindings.parse_time(raw[1] if raw and raw[0] == "text" else (raw[2] if raw and raw[0] == "num" else None), env["zone"])
            if at is None:
                missing = True
            else:
                text = Bindings.relative(Bindings._as_aware(at, env["zone"]), Bindings._as_aware(env["now"], env["zone"]))
        for f in tok["formatters"]:
            name, arg = f
            if missing or empty:
                if name == "default" and missing:
                    text = arg
                    missing = False
                    empty = text == ""
                    number = None
                    break
                continue
            if name == "default":
                continue
            if name == "round":
                n = number if number is not None else _to_float(text)
                if n is None:
                    missing, text, number = True, "", None
                else:
                    text = Bindings.round(n, arg)
                    number = float(text)
            elif name == "duration":
                n = number if number is not None else _to_float(text)
                if n is None:
                    missing, text, number = True, "", None
                else:
                    text = Bindings.format_duration(round(n))
                    number = None
            elif name == "since":
                at = Bindings.parse_time(text, env["zone"])
                if at is None:
                    missing, text, number = True, "", None
                else:
                    sec = int((Bindings._as_aware(env["now"], env["zone"]) - Bindings._as_aware(at, env["zone"])).total_seconds())
                    text = Bindings.format_duration(sec)
                    number = None
            elif name == "time":
                at = Bindings.parse_time(text, env["zone"])
                if at is None:
                    missing, text, number = True, "", None
                else:
                    text = Bindings.format_instant(at, arg, env, True)
                    number = None
        if empty:
            return {"text": "", "empty": True, "number": None}
        if missing:
            return {"text": Bindings.MISSING, "empty": False, "number": None}
        return {"text": text, "empty": False, "number": number}


def _to_float(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None



class Card:
    """Card.kt (#137 S3-A + #360 RW-1): dashboard card schema, M1 readout keys at FL_CARDS_READOUT."""
    LEVEL, SINGLE = 4, False
    KEYS = ("id", "type", "requires", "page", "x", "y", "w", "h", "minSpan", "title", "author", "source", "surface", "header", "summary", "layers", "capabilities", "data", "layouts")
    KINDS = {"data.file": (), "ui.reload": (), "ui.openApp": ("packages",), "ui.openUrl": ()}
    TAP_KINDS = {"ui.openUrl": ("url",), "ui.openApp": ("package",), "ui.reload": ()}
    NODES = {"column": ("children",), "row": ("children",), "tile": ("children",),
             "text": ("text", "role", "maxLines", "align", "font", "status"),
             "metric": ("label", "value", "unit", "status", "thresholds"),
             "list": ("label", "items", "maxItems", "emptyText"), "icon": ("name", "package"),
             "progress": ("label", "value", "max", "segments", "style", "count", "thresholds", "by"),
             "button": ("label", "icon")}
    NODE_REQUIRED = {"column": ("children",), "row": ("children",), "tile": ("children",), "text": ("text",),
                     "metric": ("value",), "list": ("items",), "progress": ("value",), "button": ("label", "tap")}
    COMMON = ("type", "priority", "weight", "tap", "color")
    ROLES = ("caption", "body", "title", "display")
    ALIGN = ("start", "center", "end")
    FONT = ("default", "mono")
    PICTURE_KEYS = ("kind", "src", "default", "fit", "width", "align", "offset", "opacity", "overflow", "scrim")
    FIT = ("cover", "contain", "fixed")
    LAYER_ALIGN = ("topStart", "top", "topEnd", "start", "center", "end", "bottomStart", "bottom", "bottomEnd")
    TONES = ("ok", "warn", "error", "accent", "muted")
    BY = ("value", "position")
    LAYOUT = re.compile(r"^([1-9][0-9]?)x([1-9][0-9]?)$")
    HOST = re.compile(r"^(https?://)?[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$")
    DURATION = re.compile(r"^P(?=[0-9]|T[0-9])([0-9]+D)?(T(?=[0-9])([0-9]+H)?([0-9]+M)?([0-9]+S)?)?$")
    URL = re.compile(r"^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)")
    FILE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*\.(png|jpg|jpeg|webp)$")
    HEX = re.compile(r"^#[0-9A-Fa-f]{6}$")
    SECRET_REF = re.compile(r"\$\{secret:|\{secret:")
    TOKEN = re.compile(r"\{(data|fetch):[^}]*}")
    MIN_REQUIRES, MAX_CAPABILITIES, MAX_TARGETS, MAX_LAYOUTS = 4, 8, 8, 3
    MAX_CHILDREN, MAX_DEPTH, MAX_NODES, MAX_TEXT, MAX_URL = 12, 4, 48, 200, 2048
    MAX_SUMMARY, MAX_LAYERS, MAX_FILE, MAX_THRESHOLDS, MAX_SEGMENTS = 120, 4, 80, 5, 20
    MIN_TICKS, MAX_TICKS, MIN_WIDTH, MAX_WIDTH, MAX_OFFSET, ABOVE_OPACITY = 10, 120, 0.1, 1.5, 0.25, 0.6

    @staticmethod
    def unknown_capability(o):
        caps = o.get("capabilities")
        if not isinstance(caps, list):
            return None
        for c in caps:
            k = c.get("kind") if isinstance(c, dict) else None
            if _is_str(k) and k not in Card.KINDS:
                return k
        return None

    @staticmethod
    def _full(rx, s):
        return _is_str(s) and rx.fullmatch(s) is not None

    @staticmethod
    def _num(o, k, path, r):
        if k not in o:
            return None
        v = o[k]
        if isinstance(v, bool) or not isinstance(v, Num):
            r.err(f"{path}.{k}", "must be a number")
            return None
        try:
            n = float(v)
        except ValueError:
            r.err(f"{path}.{k}", "must be a number")
            return None
        if n != n or n in (float("inf"), float("-inf")):
            r.err(f"{path}.{k}", "must be a number")
            return None
        return n

    @staticmethod
    def _num_of(o, k):
        v = o.get(k)
        if isinstance(v, bool) or not isinstance(v, Num):
            return None
        try:
            n = float(v)
        except ValueError:
            return None
        if n != n or n in (float("inf"), float("-inf")):
            return None
        return n

    @staticmethod
    def _fmt_num(n):
        """Whole numbers without a trailing ".0", so "width 2" matches the §4.4 sentence."""
        i = int(n)
        return str(i) if n == i else str(n)

    @staticmethod
    def _color(r, v, path):
        if isinstance(v, dict):
            r.keys(v, path, {"light", "dark"}, [], x_keys=False)
            if not v:
                r.err("", f"{path} needs light or dark.")
            for k in ("light", "dark"):
                if k in v:
                    TitleBar._color(r, v[k], f"{path}.{k}")
        else:
            TitleBar._color(r, v, path)

    @staticmethod
    def validate(r, o, path):
        r.keys(o, path, set(Card.KEYS), ["requires", "page", "layouts"])
        q = o.get("requires")
        if isinstance(q, Num) and q.is_int() and int(q) < Card.MIN_REQUIRES:
            r.err(f"{path}.requires", f"a card needs feature level {Card.MIN_REQUIRES} or higher")
        elif isinstance(q, Num) and q.is_int() and int(q) < FL_CARDS_READOUT and Card.uses_readout_keys(o):
            r.err(f"{path}.requires", f"a card that uses readout keys needs feature level {FL_CARDS_READOUT} or higher")
        if "minSpan" in o:
            m = r.obj(o["minSpan"], f"{path}.minSpan")
            if m is not None:
                r.keys(m, f"{path}.minSpan", {"w", "h"}, ["w", "h"], x_keys=False)
                r.int(m, "w", f"{path}.minSpan", 1, 12)
                r.int(m, "h", f"{path}.minSpan", 1, 12)
        s = r.str(o, "title", path, mx=40)
        if s is not None:
            Card.binding(r, s, f"{path}.title")
        r.str(o, "author", path, mx=40, mn=1)
        src = r.str(o, "source", path)   # Q-D5: the site taps open silently
        if src is not None and (len(src) > 253 + 8 or not Card._full(Card.HOST, src)):
            r.err("", f"{path}.source must be a lowercase hostname, optionally with http:// or https://.")
        Card.surface(r, o, path)
        r.bool(o, "header", path)
        s = r.str(o, "summary", path, mx=Card.MAX_SUMMARY)
        if s is not None:
            Card.binding(r, s, f"{path}.summary")
        if "layers" in o:
            if not isinstance(o["layers"], list):
                r.err(f"{path}.layers", "must be an array")
            else:
                Card.layers(r, o["layers"], f"{path}.layers")
        caps = Card.capabilities(r, o, path)
        if "data" in o:
            d = r.obj(o["data"], f"{path}.data")
            if d is not None:
                r.keys(d, f"{path}.data", {"staleAfter", "asOf"}, [], x_keys=False)
                r.str(d, "staleAfter", f"{path}.data", mx=32, rx=Card.DURATION)
                asof = r.str(d, "asOf", f"{path}.data", mx=64, mn=1)
                if asof is not None:
                    msg = Bindings.check_as_of(asof, f"{path}.data.asOf")
                    if msg:
                        r.err("", msg)
        if "layouts" in o:
            ls = r.obj(o["layouts"], f"{path}.layouts")
            if ls is not None:
                Card.layouts(r, ls, f"{path}.layouts", caps)

    @staticmethod
    def surface(r, o, path):
        if "surface" not in o:
            return
        e = o["surface"]
        if isinstance(e, dict):
            r.keys(e, f"{path}.surface", {"color", "opacity"}, ["color"], x_keys=False)
            if "color" in e:
                Card._color(r, e["color"], f"{path}.surface.color")
            r.int(e, "opacity", f"{path}.surface", 50, 100)
        elif _is_str(e):
            if e not in ("auto", "none"):
                r.err("", f'{path}.surface must be "auto", "none", or an object with color.')
        else:
            r.err("", f'{path}.surface must be "auto", "none", or an object with color.')

    @staticmethod
    def layers(r, arr, path):
        contents = 0
        for i, e in enumerate(arr):
            lp = f"{path}[{i}]"
            o = r.obj(e, lp)
            if o is None:
                continue
            kind = r.str(o, "kind", lp)
            if kind is None:
                if "kind" not in o:
                    r.err(lp, 'missing required "kind"')
                continue
            if kind == "content":
                r.keys(o, lp, {"kind"}, ["kind"], x_keys=False)
                contents += 1
                if contents > 1:
                    r.err(lp, "a card can have only one content layer")
            elif kind == "picture":
                Card.picture(r, o, lp)
            else:
                r.err(f"{lp}.kind", "must be picture or content")

    @staticmethod
    def picture(r, o, path):
        r.keys(o, path, set(Card.PICTURE_KEYS), ["kind", "src"], x_keys=False)
        s = r.str(o, "src", path, mx=Card.MAX_FILE, mn=1)
        if s is not None:
            Card.file_or_template(r, s, f"{path}.src", True)
        d = r.str(o, "default", path, mx=Card.MAX_FILE, mn=1)
        if d is not None:
            Card.file_or_template(r, d, f"{path}.default", False)
        fit = r.str(o, "fit", path)
        if fit is not None and fit not in Card.FIT:
            r.err(f"{path}.fit", "must be cover, contain or fixed")
        if "width" in o:
            Card._num(o, "width", path, r)
        al = r.str(o, "align", path)
        if al is not None and al not in Card.LAYER_ALIGN:
            r.err(f"{path}.align", "must be topStart, top, topEnd, start, center, end, bottomStart, bottom or bottomEnd")
        if "offset" in o:
            off = r.obj(o["offset"], f"{path}.offset")
            if off is not None:
                r.keys(off, f"{path}.offset", {"x", "y"}, [], x_keys=False)
                if "x" in off:
                    Card._num(off, "x", f"{path}.offset", r)
                if "y" in off:
                    Card._num(off, "y", f"{path}.offset", r)
        if "opacity" in o:
            n = Card._num(o, "opacity", path, r)
            if n is not None and not (0 <= n <= 1):
                r.err(f"{path}.opacity", "must be a number from 0 to 1")
        r.bool(o, "overflow", path)
        if "scrim" in o:
            Card.scrim(r, o["scrim"], f"{path}.scrim")

    @staticmethod
    def scrim(r, e, path):
        if isinstance(e, dict):
            r.keys(e, path, {"color", "opacity"}, ["color"], x_keys=False)
            if "color" in e:
                Card._color(r, e["color"], f"{path}.color")
            if "opacity" in e:
                n = Card._num(e, "opacity", path, r)
                if n is not None and not (0 <= n <= 1):
                    r.err(f"{path}.opacity", "must be a number from 0 to 1")
        elif _is_str(e):
            if e not in ("auto", "none"):
                r.err("", f'{path} must be "auto", "none", or an object with color.')
        else:
            r.err("", f'{path} must be "auto", "none", or an object with color.')

    @staticmethod
    def looks_remote(s):
        return s.startswith("http://") or s.startswith("https://") or s.startswith("//") or "/" in s

    @staticmethod
    def file_name_msg():
        return "must be a file name (letters, digits, '.', '_' or '-') ending in .png, .jpg, .jpeg or .webp"

    @staticmethod
    def legal_template(s):
        stripped = Card.TOKEN.sub("x", s)
        if "{" in stripped or "}" in stripped:
            return False
        if ".." in s or "/" in s or s.startswith("."):
            return False
        return Card.FILE.fullmatch(stripped) is not None

    @staticmethod
    def file_or_template(r, s, path, template):
        if Card.looks_remote(s):
            r.err("", f"{path} must be a file name in the card's pictures folder, not a web address.")
            return
        if ".." in s or s.startswith("."):
            r.err(path, Card.file_name_msg())
            return
        if "{" not in s:
            if not Card._full(Card.FILE, s):
                r.err(path, Card.file_name_msg())
            return
        if not template:
            r.err(path, "must be a file name, not a template")
            return
        msg = Card.file_token_error(s, path)
        if msg:
            r.err("", msg)
            return
        if not Card.legal_template(s):
            r.err(path, "must be a file name template ending in .png, .jpg, .jpeg or .webp")

    @staticmethod
    def capabilities(r, o, path):
        kinds, packages = set(), set()
        for i, e in enumerate(r.arr(o, "capabilities", path, mx=Card.MAX_CAPABILITIES) or []):
            cp = f"{path}.capabilities[{i}]"
            co = r.obj(e, cp)
            if co is None:
                continue
            kind = r.str(co, "kind", cp)
            if kind is None:
                if "kind" not in co:
                    r.err(cp, 'missing required "kind"')
                continue
            if kind not in Card.KINDS:
                continue
            fields = Card.KINDS[kind]
            r.keys(co, cp, {"kind", *fields}, list(fields), x_keys=False)
            if kind in kinds:
                r.err(f"{cp}.kind", f'"{kind}" is listed twice')
            kinds.add(kind)
            for j, pk in enumerate(r.arr(co, "packages", cp, mx=Card.MAX_TARGETS, mn=1) or []):
                if not Card._full(PKG, pk):
                    r.err(f"{cp}.packages[{j}]", "must be a package name")
                elif pk in packages:
                    r.err(f"{cp}.packages[{j}]", f'"{pk}" is listed twice')
                else:
                    packages.add(pk)
        return kinds, packages

    @staticmethod
    def layouts(r, ls, path, caps):
        if not ls:
            r.err(path, "needs at least 1 entries")
        if len(ls) > Card.MAX_LAYOUTS:
            r.err(path, f"has {len(ls)} entries, at most {Card.MAX_LAYOUTS} allowed")
        for k in ls:
            m = Card.LAYOUT.fullmatch(k)
            if m is None or int(m.group(1)) > 12 or int(m.group(2)) > 12:
                r.err(path, f'"{k}" is not a size like "4x3" (lowercase x, 1 to 12)')
                continue
            count = [0]
            for i, n in enumerate(r.arr(ls, k, path, mx=Card.MAX_CHILDREN, mn=1) or []):
                Card.node(r, n, f"{path}.{k}[{i}]", 1, count, f"{path}.{k}", caps)

    @staticmethod
    def node(r, e, np, depth, count, lp, caps):
        count[0] += 1
        if count[0] == Card.MAX_NODES + 1:
            r.err(lp, f"has more than {Card.MAX_NODES} nodes")
        o = r.obj(e, np)
        if o is None:
            return
        t = r.str(o, "type", np)
        if t is None:
            if "type" not in o:
                r.err(np, 'missing required "type"')
            return
        if t not in Card.NODES:
            r.err(f"{np}.type", f'unknown node type "{t}". Use ' + ", ".join(Card.NODES) + ".")
            return
        fields = Card.NODES[t]
        r.keys(o, np, {*Card.COMMON, *fields}, list(Card.NODE_REQUIRED.get(t, ())), x_keys=False)
        r.int(o, "priority", np, 0, 9)
        r.int(o, "weight", np, 1, 12)
        if "color" in o:
            Card._color(r, o["color"], f"{np}.color")
        has = lambda k: k in fields
        if has("text"):
            s = r.str(o, "text", np, mx=Card.MAX_TEXT)
            if s is not None:
                Card.binding(r, s, f"{np}.text")
        if has("role"):
            ro = r.str(o, "role", np)
            if ro is not None and ro not in Card.ROLES:
                r.err(f"{np}.role", "must be caption, body, title or display")
        if has("maxLines"):
            r.int(o, "maxLines", np, 1, 6)
        if has("align"):
            al = r.str(o, "align", np)
            if al is not None and al not in Card.ALIGN:
                r.err(f"{np}.align", "must be start, center or end")
        if has("font"):
            fo = r.str(o, "font", np)
            if fo is not None and fo not in Card.FONT:
                r.err(f"{np}.font", "must be default or mono")
        if has("label"):
            s = r.str(o, "label", np, mx=40)
            if s is not None:
                Card.binding(r, s, f"{np}.label")
        if has("icon") and t == "button":
            r.str(o, "icon", np, mx=64)
        if t == "progress":
            mxv = Card._num(o, "max", np, r) if "max" in o else None
            if mxv is not None and mxv <= 0:
                r.err(f"{np}.max", "must be a number greater than 0")
            if "value" in o:
                v = o["value"]
                if _is_str(v):
                    s = r.str(o, "value", np, mx=Card.MAX_TEXT)
                    if s is not None:
                        Card.binding(r, s, f"{np}.value")
                else:
                    try:
                        n = float(v) if isinstance(v, Num) and not isinstance(v, bool) else None
                    except ValueError:
                        n = None
                    hi = mxv if mxv is not None and mxv > 0 else 1
                    if n is None or not (0 <= n <= hi):
                        if mxv is not None and mxv > 0:
                            r.err(f"{np}.value", f"must be a template or a number from 0 to {mxv}")
                        else:
                            r.err(f"{np}.value", "must be a template or a number from 0 to 1")
            r.int(o, "segments", np, 2, Card.MAX_SEGMENTS)
            st = r.str(o, "style", np)
            if st is not None and st != "ticks":
                r.err(f"{np}.style", 'must be "ticks"')
            if "count" in o:
                cv = o["count"]
                if not (isinstance(cv, Num) and cv.is_int()):
                    r.err(f"{np}.count", "must be an integer")
            by = r.str(o, "by", np)
            if by is not None and by not in Card.BY:
                r.err(f"{np}.by", "must be value or position")
        elif has("value"):
            s = r.str(o, "value", np, mx=Card.MAX_TEXT)
            if s is not None:
                Card.binding(r, s, f"{np}.value")
        if has("unit"):
            s = r.str(o, "unit", np, mx=16)
            if s is not None:
                Card.binding(r, s, f"{np}.unit")
        if has("status"):
            s = r.str(o, "status", np, mx=Card.MAX_TEXT)
            if s is not None:
                Card.binding(r, s, f"{np}.status")
        if has("items"):
            s = r.str(o, "items", np, mx=Card.MAX_TEXT)
            if s is not None:
                Card.binding(r, s, f"{np}.items")
        if has("maxItems") and "maxItems" in o and o["maxItems"] != "auto":
            r.int(o, "maxItems", np, 1, 20)
        if has("emptyText"):
            s = r.str(o, "emptyText", np, mx=80)
            if s is not None:
                Card.binding(r, s, f"{np}.emptyText")
        if t == "icon":
            r.str(o, "name", np, mx=64)
            r.str(o, "package", np, rx=PKG)
            if ("name" in o) == ("package" in o):
                r.err(np, 'needs exactly one of "name" or "package"')
        if has("thresholds") and "thresholds" in o:
            Card.thresholds(r, o["thresholds"], f"{np}.thresholds")
        if "tap" in o:
            Card.tap(r, o["tap"], f"{np}.tap", caps)
        if has("children") and "children" in o:
            if depth >= Card.MAX_DEPTH:
                r.err(np, f"is nested too deep (at most {Card.MAX_DEPTH} levels)")
            else:
                for i, n in enumerate(r.arr(o, "children", np, mx=Card.MAX_CHILDREN, mn=1) or []):
                    Card.node(r, n, f"{np}.children[{i}]", depth + 1, count, lp, caps)

    @staticmethod
    def thresholds(r, e, path):
        if not isinstance(e, list):
            r.err(path, "must be an array")
            return
        if not (1 <= len(e) <= Card.MAX_THRESHOLDS):
            r.err(path, f"needs 1 to {Card.MAX_THRESHOLDS} entries")
        prev = None
        for i, item in enumerate(e):
            tp = f"{path}[{i}]"
            o = r.obj(item, tp)
            if o is None:
                continue
            r.keys(o, tp, {"from", "tone"}, ["from", "tone"], x_keys=False)
            fr = Card._num(o, "from", tp, r)
            if fr is not None:
                if not (0 <= fr <= 100):
                    r.err(f"{tp}.from", "must be 0 to 100")
                if i == 0 and fr != 0:
                    r.err(f"{tp}.from", "must be 0")
                if prev is not None and fr <= prev:
                    r.err(f"{tp}.from", "must be greater than the previous from")
                prev = fr
            tone = r.str(o, "tone", tp)
            if tone is not None and tone not in Card.TONES and not Card.HEX.fullmatch(tone):
                r.err(f"{tp}.tone", "must be ok, warn, error, accent, muted, or #RRGGBB")

    @staticmethod
    def tap(r, e, tp, caps):
        kinds, packages = caps
        o = r.obj(e, tp)
        if o is None:
            return
        kind = r.str(o, "kind", tp)
        if kind is None:
            if "kind" not in o:
                r.err(tp, 'missing required "kind"')
            return
        if kind not in Card.TAP_KINDS:
            r.err(f"{tp}.kind", "must be ui.openUrl, ui.openApp or ui.reload")
            return
        fields = Card.TAP_KINDS[kind]
        r.keys(o, tp, {"kind", *fields}, list(fields), x_keys=False)
        if kind not in kinds:
            r.err(tp, f"{kind} isn't in this card's capabilities")
            return
        pk = r.str(o, "package", tp, rx=PKG)
        if pk is not None and pk not in packages:
            r.err(f"{tp}.package", f'"{pk}" isn\'t in the ui.openApp packages')
        url = r.str(o, "url", tp, mx=Card.MAX_URL, mn=1)
        if url is not None:
            if Card.mentions_secret(url):
                r.err(f"{tp}.url", "a link can't use a secret.")
            elif "{" not in url:
                p = Card.url_problem(url)
                if p:
                    r.err(f"{tp}.url", p)
            else:
                Card.binding(r, url, f"{tp}.url")

    @staticmethod
    def mentions_secret(s):
        return Card.SECRET_REF.search(s) is not None

    @staticmethod
    def binding(r, s, path):
        msg = Bindings.check(s, path)
        if msg:
            r.err("", msg)

    @staticmethod
    def file_token_error(s, path):
        i, n = 0, len(s)
        while i < n:
            if s[i] == "{":
                j = s.find("}", i + 1)
                if j < 0:
                    return Bindings.check(s, path)
                msg = Bindings.check_file_token(s[i + 1:j], path)
                if msg:
                    return msg
                i = j + 1
            else:
                i += 1
        return None

    @staticmethod
    def bind(s, env, path="elements[0]"):
        return Bindings.expand(s, env, path)

    @staticmethod
    def url_problem(url):
        """Card.urlProblem: http or https, no user info; the host is decided at tap time (Q-D5)."""
        m = Card.URL.match(url)
        if m is None or m.group(1).lower() not in ("http", "https"):
            return "must be an http or https URL"
        if "@" in m.group(2):
            return "must not contain user info"
        return None

    @staticmethod
    def empty_not_drawn(resolved):
        return resolved == ""

    @staticmethod
    def uses_readout_keys(o):
        if "header" in o or "summary" in o or "layers" in o:
            return True
        data = o.get("data")
        if isinstance(data, dict) and "asOf" in data:
            return True
        sf = o.get("surface")
        if isinstance(sf, dict) or (_is_str(sf) and sf != "auto"):
            return True
        def walk(n):
            if not isinstance(n, dict):
                return False
            typ = n.get("type")
            if "color" in n:
                return True
            if typ == "text" and ("align" in n or "font" in n or "status" in n or n.get("role") == "display"):
                return True
            if typ == "progress" and any(k in n for k in ("max", "segments", "style", "count", "thresholds", "by")):
                return True
            if typ == "metric" and "thresholds" in n:
                return True
            if typ == "button" and "icon" in n:
                return True
            for ch in n.get("children") or []:
                if walk(ch):
                    return True
            return False
        ls = o.get("layouts")
        if isinstance(ls, dict):
            for nodes in ls.values():
                if isinstance(nodes, list):
                    for n in nodes:
                        if walk(n):
                            return True
        return False

    @staticmethod
    def has_open_url(o):
        caps = o.get("capabilities")
        if not isinstance(caps, list):
            return False
        return any(isinstance(c, dict) and c.get("kind") == "ui.openUrl" for c in caps)

    @staticmethod
    def honors_header_false(o):
        return o.get("header") is False and not Card.has_open_url(o)

    @staticmethod
    def override(r, o, path):
        pass

    @staticmethod
    def warnings(o, path):
        return Card.load_warnings(o, path, files=None)

    @staticmethod
    def load_warnings(o, path, files=None):
        out = []
        header_false = o.get("header") is False
        if header_false and Card.has_open_url(o):
            out.append(f'{path}.header: false is ignored because this card opens links or needs your OK, so the header is shown.')
        if header_false and o.get("surface") == "none":
            out.append(f'{path}.surface: "none" can\'t be used with header: false, so the default card surface was used.')
        arr = o.get("layers")
        if isinstance(arr, list):
            if len(arr) > Card.MAX_LAYERS:
                out.append(f"{path}.layers: has {len(arr)} layers, so the last {len(arr) - Card.MAX_LAYERS} were skipped. A card can have at most {Card.MAX_LAYERS}.")
            kept = arr[:Card.MAX_LAYERS]
            content_at = next((i for i, e in enumerate(kept) if isinstance(e, dict) and e.get("kind") == "content"), len(kept))
            for i, e in enumerate(kept):
                if not isinstance(e, dict) or e.get("kind") != "picture":
                    continue
                lp = f"{path}.layers[{i}]"
                fit = e.get("fit") if _is_str(e.get("fit")) else "cover"
                if "width" in e and fit != "fixed":
                    out.append(f'{lp}.width is ignored unless fit is "fixed", so the picture is sized with "{fit}". Set fit to "fixed" to use width, or drop width.')
                w = Card._num_of(e, "width")
                if w is not None and fit == "fixed" and (w < Card.MIN_WIDTH or w > Card.MAX_WIDTH):
                    set_to = min(max(w, Card.MIN_WIDTH), Card.MAX_WIDTH)
                    out.append(f"{lp}.width: {Card._fmt_num(w)} is outside {Card._fmt_num(Card.MIN_WIDTH)} to {Card._fmt_num(Card.MAX_WIDTH)}, so it was set to {Card._fmt_num(set_to)}. Use a value in that range.")
                off = e.get("offset")
                if isinstance(off, dict):
                    for axis in ("x", "y"):
                        v = Card._num_of(off, axis)
                        if v is None:
                            continue
                        if v < -Card.MAX_OFFSET or v > Card.MAX_OFFSET:
                            set_to = min(max(v, -Card.MAX_OFFSET), Card.MAX_OFFSET)
                            out.append(f"{lp}.offset.{axis}: {Card._fmt_num(v)} is outside {Card._fmt_num(-Card.MAX_OFFSET)} to {Card._fmt_num(Card.MAX_OFFSET)}, so it was set to {Card._fmt_num(set_to)}. Use a value in that range.")
                under = i == content_at - 1
                above = i > content_at
                scrim = e.get("scrim", None)
                scrim_auto = "scrim" not in e or scrim == "auto"
                if under and scrim_auto:
                    out.append(f'{lp}: a picture is right under content, so a default scrim was added to keep text readable. Set this layer\'s scrim to choose it, or "none" to turn it off.')
                if above:
                    opacity = Card._num_of(e, "opacity")
                    if opacity is None:
                        opacity = 1.0
                    if opacity > Card.ABOVE_OPACITY:
                        out.append(f"{lp}.opacity is above {Card._fmt_num(Card.ABOVE_OPACITY)} on a layer above content, so it's shown at {Card._fmt_num(Card.ABOVE_OPACITY)} to keep text readable. Use {Card._fmt_num(Card.ABOVE_OPACITY)} or less, or move the layer under the content.")
                if files is not None:
                    src = e.get("src")
                    if _is_str(src) and "{" not in src and src not in files:
                        out.append(f'{lp}.src: "{src}" isn\'t in this card\'s pictures folder, so the layer was skipped.')
                    default = e.get("default")
                    if _is_str(default) and default not in files:
                        out.append(f'{lp}.default: "{default}" isn\'t in this card\'s pictures folder, so the default picture was skipped.')
        def walk(n, np):
            if not isinstance(n, dict):
                return
            if n.get("type") == "progress":
                style = n.get("style")
                if "count" in n:
                    cv = n["count"]
                    if isinstance(cv, Num) and cv.is_int():
                        k = int(cv)
                        if style != "ticks":
                            out.append(f'{np}.count is ignored unless style is "ticks", so the tick count isn\'t used. Set style to "ticks" to use count, or drop count.')
                        elif k > Card.MAX_TICKS:
                            out.append(f"{np}.count: {k} is outside {Card.MIN_TICKS} to {Card.MAX_TICKS}, so it was set to {Card.MAX_TICKS}. A ticks bar can have at most {Card.MAX_TICKS} ticks.")
                        elif k < Card.MIN_TICKS:
                            out.append(f"{np}.count: {k} is outside {Card.MIN_TICKS} to {Card.MAX_TICKS}, so it was set to {Card.MIN_TICKS}. A ticks bar needs at least {Card.MIN_TICKS} ticks.")
                if style == "ticks" and "segments" in n:
                    out.append(f'{np}.segments is ignored when style is "ticks", so the bar uses ticks. Drop segments, or drop style to use a segmented bar.')
            for i, ch in enumerate(n.get("children") or []):
                walk(ch, f"{np}.children[{i}]")
        ls = o.get("layouts")
        if isinstance(ls, dict):
            for k, nodes in ls.items():
                if isinstance(nodes, list):
                    for i, n in enumerate(nodes):
                        walk(n, f"{path}.layouts.{k}[{i}]")
        return out

    @staticmethod
    def image_bad_warning(path, file_name):
        return f'{path}: "{file_name}" can\'t be shown (too big, or not a PNG, JPEG or WebP), so the layer was skipped.'

    @staticmethod
    def canonical(o):
        def order(obj, keys, inner=None):
            out = {}
            for k in keys:
                if k in obj:
                    out[k] = inner(k, obj[k]) if inner else obj[k]
            return out
        def color_obj(obj, keys):
            return order(obj, keys, lambda k, v: order(v, ("light", "dark")) if k == "color" and isinstance(v, dict) else v)
        def node(e):
            if not isinstance(e, dict):
                return e
            typ = e.get("type")
            keys = list(Card.COMMON) + list(Card.NODES.get(typ, ()))
            def inner(k, v):
                if k == "color" and isinstance(v, dict):
                    return color_obj(v, ("light", "dark"))
                if k == "thresholds" and isinstance(v, list):
                    return [order(t, ("from", "tone")) if isinstance(t, dict) else t for t in v]
                if k == "tap" and isinstance(v, dict):
                    return order(v, ("kind", *Card.TAP_KINDS.get(v.get("kind"), ())))
                if k == "children" and isinstance(v, list):
                    return [node(ch) for ch in v]
                return v
            return order(e, keys, inner)
        out = {}
        for k in Card.KEYS:
            if k not in o:
                continue
            v = o[k]
            if k == "minSpan":
                v = {kk: v[kk] for kk in ("w", "h") if kk in v}
            elif k == "surface" and isinstance(v, dict):
                v = color_obj(v, ("color", "opacity"))
            elif k == "layers" and isinstance(v, list):
                def layer(lo):
                    if not isinstance(lo, dict):
                        return lo
                    if lo.get("kind") == "picture":
                        def lin(key, ev):
                            if key == "offset" and isinstance(ev, dict):
                                return {kk: ev[kk] for kk in ("x", "y") if kk in ev}
                            if key == "scrim" and isinstance(ev, dict):
                                return color_obj(ev, ("color", "opacity"))
                            return ev
                        return order(lo, Card.PICTURE_KEYS, lin)
                    return order(lo, ("kind",))
                v = [layer(x) for x in v]
            elif k == "capabilities":
                v = [{kk: c[kk] for kk in ("kind", *Card.KINDS[c["kind"]]) if kk in c} for c in v]
            elif k == "layouts" and isinstance(v, dict):
                v = {lk: [node(n) for n in nodes] for lk, nodes in v.items()}
            out[k] = v
        out.update(_x(o))
        return out
ELEMENT_TYPES["card"] = Card


class AppWidget:
    """AppWidget.kt (#298 NW-1, feature level 5): hosted AppWidget schema only (docs/design/native-widgets.md §2.1-§2.2). No host yet."""
    LEVEL, SINGLE = 5, False
    KEYS = ("id", "type", "requires", "enabled", "page", "x", "y", "w", "h", "content")
    CONTENT_KEYS = ("provider", "profile", "label")
    PROVIDER_KEYS = ("package", "class")
    PROFILES = ("personal", "work")
    MIN_REQUIRES, MAX_LABEL = 5, 40

    @staticmethod
    def style_message(i):
        """Pragma (#297): the same sentence as the phone, after "elements[N]: "."""
        return f'appWidget "{i if _is_str(i) else ""}" doesn\'t support "style" yet. Remove it.'

    @staticmethod
    def validate(r, o, path):
        if "style" in o:
            r.err(path, AppWidget.style_message(o.get("id")))
        r.keys({k: v for k, v in o.items() if k != "style"}, path, set(AppWidget.KEYS), ["requires", "page", "content"])
        q = o.get("requires")
        if isinstance(q, Num) and q.is_int() and int(q) < AppWidget.MIN_REQUIRES:
            r.err(f"{path}.requires", f"an appWidget needs feature level {AppWidget.MIN_REQUIRES} or higher")
        r.bool(o, "enabled", path)
        if "content" not in o:
            return
        cp = f"{path}.content"
        c = r.obj(o["content"], cp)
        if c is None:
            return
        r.keys(c, cp, set(AppWidget.CONTENT_KEYS), ["provider"], x_keys=False)
        if "provider" in c:
            p = r.obj(c["provider"], f"{cp}.provider")
            if p is not None:
                r.keys(p, f"{cp}.provider", set(AppWidget.PROVIDER_KEYS), list(AppWidget.PROVIDER_KEYS), x_keys=False)
                r.str(p, "package", f"{cp}.provider", rx=PKG)
                r.str(p, "class", f"{cp}.provider", rx=CLS)
        pr = r.str(c, "profile", cp)
        if pr is not None and pr not in AppWidget.PROFILES:
            r.err(f"{cp}.profile", "must be personal or work")
        r.str(c, "label", cp, mx=AppWidget.MAX_LABEL)

    @staticmethod
    def override(r, o, path):
        pass

    @staticmethod
    def warnings(o, path):
        return []

    @staticmethod
    def canonical(o):
        out = {}
        for k in AppWidget.KEYS:
            if k not in o:
                continue
            v = o[k]
            if k == "content":
                v = {ck: v[ck] for ck in AppWidget.CONTENT_KEYS if ck in v}
                if "provider" in v:
                    v["provider"] = {pk: v["provider"][pk] for pk in AppWidget.PROVIDER_KEYS if pk in v["provider"]}
            out[k] = v
        out.update(_x(o))
        return out


ELEMENT_TYPES["appWidget"] = AppWidget


def _requires(o):
    v = o.get("requires")
    return int(v) if isinstance(v, Num) and v.is_int() else None


def skip(o):
    """Elements.skip: ("level", n) when requires > FEATURE_LEVEL, ("type", t) for an unknown type, else None."""
    r = _requires(o)
    if r is not None and r > FEATURE_LEVEL:
        return ("level", r)
    t = o.get("type")
    if not _is_str(t):
        return None
    if t not in ELEMENT_TYPES:
        return ("type", t)
    k = getattr(ELEMENT_TYPES[t], "unknown_capability", lambda o: None)(o)
    return ("kind", k) if k is not None else None


def warnings(tree):
    """Elements.warnings (ux.md §15.1): never reject the file; the phone puts them in status.json `warnings`."""
    out, els = [], tree.get("elements") or []
    for i, e in enumerate(els):
        s = skip(e)
        if s and s[0] == "level":
            out.append(f"elements[{i}] needs feature level {s[1]}, but this Reflow Launcher supports up to {FEATURE_LEVEL}, "
                       "so it was skipped. It stays in the file. Update the app to show it.")
        elif s and s[0] == "kind":
            out.append(f'elements[{i}]: capability kind "{s[1]}" isn\'t supported by this Reflow Launcher, so the widget was skipped. '
                       "It stays in the file. Update the app, or check the kind for a typo.")
        elif s:
            out.append(f'elements[{i}]: type "{s[1]}" isn\'t supported by this Reflow Launcher, so it was skipped. It stays in the file.')
        elif e.get("type") in ELEMENT_TYPES:
            out.extend(ELEMENT_TYPES[e["type"]].warnings(e, f"elements[{i}]"))
    ids = {e["id"] for e in els if _is_str(e.get("id"))}
    by_id = {e["id"]: e for e in els if isinstance(e, dict) and not skip(e) and _is_str(e.get("id"))}
    for j, p in enumerate(tree["pages"]):
        for k, v in (p.get("overrides") or {}).items():
            if k not in ids:
                out.append(f'pages[{j}].overrides: "{k}" doesn\'t match any element, so it was ignored.')
            elif k in by_id and isinstance(v, dict) and by_id[k].get("type") in ELEMENT_TYPES:
                # #118: an override that changes colours gets the same contrast check as the element itself.
                out.extend(ELEMENT_TYPES[by_id[k]["type"]].override_warnings(by_id[k], v, f"pages[{j}].overrides.{k}"))
    return out


THEME_INFO = ('theme colours (primary, secondary, onSurface, surface) are only checked on the phone; '
              'see "checked on this phone" in .configlauncher/status.json.')


def infos(tree):
    """#125 (Pragma): one info line when a shown title bar's contrast depends on theme colours."""
    shown = [e for e in tree.get("elements") or [] if isinstance(e, dict) and not skip(e) and e.get("type") == "titleBar"]
    styles = list(shown)
    by_id = {e.get("id"): e for e in shown}
    for p in tree.get("pages") or []:   # #118: overrides that change colours are measured too
        for k, v in ((p.get("overrides") or {}) if isinstance(p, dict) else {}).items():
            st = TitleBar.override_style(by_id[k], v) if k in by_id and isinstance(v, dict) else None
            if st is not None:
                styles.append({"style": st})
    return [THEME_INFO] if any(TitleBar.uses_theme(e) for e in styles) else []


def ci_errors(tree):
    """#137: the phone skips a card whose capability kind it doesn't know (a warning); CI calls it an error so agents
    catch typos. Writers of a new kind bump the feature level and set requires instead."""
    out = []
    for i, e in enumerate(tree.get("elements") or []):
        s = skip(e) if isinstance(e, dict) else None
        if s and s[0] == "kind":
            j = next(j for j, c in enumerate(e["capabilities"]) if isinstance(c, dict) and c.get("kind") == s[1])
            out.append(f'elements[{i}].capabilities[{j}].kind: unknown capability "{s[1]}". '
                       "Use " + ", ".join(Card.KINDS) + ".")
    return out


def validate(raw, use_schema=True, ci=None):
    """Returns (tree or None, errors). Semantic (phone) errors first, then schema-only findings. [ci] (default: with
    the schema) adds the checks that are stricter than the phone (ci_errors)."""
    tree, errors = parse(raw)
    if errors:
        return tree, errors
    r = Run()
    r.root(tree)
    errors = r.errors
    _too_large(tree, "", errors)
    if (use_schema if ci is None else ci) and not errors:
        errors = ci_errors(tree)
    if use_schema and not errors:
        s = schema_errors(tree)
        errors = s or []
    return tree, errors


# ---------------------------------------------------------------- canonical encoding (ConfigCodec.encode)

LONG = (-(1 << 63), (1 << 63) - 1)
ULONG_MAX = (1 << 64) - 1


def _as_int(text):
    """A number that fits Long (or ULong), as kotlinx reads it; any other number must fit a double (_too_large)."""
    if re.fullmatch(r"-?\d+", text):
        n = int(text)
        if LONG[0] <= n <= LONG[1] or 0 <= n <= ULONG_MAX:
            return n
    return None


def _num_text(v):
    """A number as the phone writes it: its text from the file, as is (ConfigCodec.exactNumbers, #260 review).
    Typed fields (revision, grid, x, y) were already normalized by _num."""
    return str(v)


def _too_large(tree, path, out):
    """#113: a number outside the double range is an error (any key), as on the phone (Validator.tooLarge)."""
    if isinstance(tree, Num):
        if _as_int(tree) is None and not math.isfinite(float(tree)):
            out.append(f"{path}: {tree} is too large for a number" if path else f"{tree} is too large for a number")
    elif isinstance(tree, dict):
        for k, x in tree.items():
            _too_large(x, f"{path}.{k}" if path else k, out)
    elif isinstance(tree, list):
        for i, x in enumerate(tree):
            _too_large(x, f"{path}[{i}]", out)


def _enc_str(s):
    out = ['"']
    for ch in s:
        o = ord(ch)
        if ch == '"':
            out.append('\\"')
        elif ch == "\\":
            out.append("\\\\")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ch == "\b":
            out.append("\\b")
        elif ch == "\f":
            out.append("\\f")
        elif o < 0x20:
            out.append("\\u%04x" % o)
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def dump(v, ind=0):
    pad, inner = "  " * ind, "  " * (ind + 1)
    if isinstance(v, dict):
        if not v:
            return "{}"
        return "{\n" + ",\n".join(f"{inner}{_enc_str(k)}: {dump(x, ind + 1)}" for k, x in v.items()) + "\n" + pad + "}"
    if isinstance(v, list):
        if not v:
            return "[]"
        return "[\n" + ",\n".join(inner + dump(x, ind + 1) for x in v) + "\n" + pad + "]"
    if isinstance(v, Num):
        return _num_text(v)
    if v is True:
        return "true"
    if v is False:
        return "false"
    if v is None:
        return "null"
    return _enc_str(v)


def _x(o):
    return {k: o[k] for k in sorted(o, key=lambda s: s.encode("utf-16-be")) if k.startswith("x-")}


def _num(v):
    return Num(str(int(v)))


def _item(o):
    out = {"id": o["id"], "type": o["type"]}
    t = o["type"]
    if t == "app":
        out["package"] = o["package"]
        for k in ("activity", "profile", "label"):
            if k in o:
                out[k] = o[k]
        if "tags" in o:
            out["tags"] = list(o["tags"])
    elif t == "shortcut":
        out["package"] = o["package"]
        out["shortcutId"] = o["shortcutId"]
        for k in ("profile", "label"):
            if k in o:
                out[k] = o[k]
    else:
        if "label" in o:
            out["label"] = o["label"]
        out["items"] = [_item(i) for i in o["items"]]
    for k in ("x", "y"):
        if k in o:
            out[k] = _num(o[k])
    out.update(_x(o))
    return out


def _ordered(o, keys):
    out = {k: o[k] for k in keys if k in o}
    out.update({k: v for k, v in o.items() if k not in keys})
    return out


def _rule(r):
    """ARCHITECTURE.md §20.3: id, group, match; inside match packages, packagePrefix, tag, category."""
    out = _ordered(r, ("id", "group", "match"))
    if isinstance(out.get("match"), dict):
        out["match"] = _ordered(out["match"], ("packages", "packagePrefix", "tag", "category"))
    return out


def canonical(tree):
    """Valid tree -> canonical text, as the phone writes it."""
    out = {}
    if "$schema" in tree:
        out["$schema"] = tree["$schema"]
    out["schemaVersion"] = Num("1")
    if "revision" in tree:
        out["revision"] = _num(tree["revision"])
    if "grid" in tree:
        g = tree["grid"]
        out["grid"] = {"columns": _num(g.get("columns", 4)), "rows": _num(g.get("rows", 5))}
    pages = []
    for p in tree["pages"]:
        po = {"id": p["id"]}
        if "title" in p:
            po["title"] = p["title"]
        po["items"] = [_item(i) for i in p["items"]]
        if "overrides" in p:
            po["overrides"] = p["overrides"]   # verbatim (#79)
        po.update(_x(p))
        pages.append(po)
    out["pages"] = pages
    if "dock" in tree:
        out["dock"] = [_item(i) for i in tree["dock"]]
    if "drawer" in tree:
        d, do = tree["drawer"], {}
        for k in ("sort", "hidden"):
            if k in d:
                do[k] = d[k]
        if "rules" in d:
            do["rules"] = [_rule(r) for r in d["rules"]]
        out["drawer"] = do
    if "elements" in tree:
        # File order; a shown title bar in canonical key order, anything skipped verbatim (#79).
        out["elements"] = [ELEMENT_TYPES[e["type"]].canonical(e) if skip(e) is None and e.get("type") in ELEMENT_TYPES else e
                           for e in tree["elements"]]
    out.update(_x(tree))
    return dump(out) + "\n"


# ---------------------------------------------------------------- CLI

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("files", nargs="+", type=pathlib.Path)
    ap.add_argument("--format", action="store_true", help="rewrite valid files in the phone's canonical form")
    ap.add_argument("--check", action="store_true", help="with --format: don't write, fail if not canonical")
    ap.add_argument("--no-schema", action="store_true", help="skip the JSON Schema pass")
    a = ap.parse_args(argv)
    if not a.no_schema:
        try:
            import jsonschema  # noqa: F401
        except ImportError:
            print("note: jsonschema not installed; only the phone-side rules were checked (pip install jsonschema)", file=sys.stderr)
    status = 0
    for f in a.files:
        raw = f.read_bytes()
        tree, errors = validate(raw, use_schema=not a.no_schema, ci=True)
        if errors:
            status = 1
            print(f"{f}: invalid ({len(errors)} problem{'s' if len(errors) != 1 else ''})")
            for e in errors:
                print(f"  {e}")
            continue
        ws = warnings(tree)
        notes = [f"\n  warning: {w}" for w in ws] + [f"\n  info: {i}" for i in infos(tree)]
        say = lambda msg: print(msg + "".join(notes))
        if a.format:
            text = canonical(tree).encode("utf-8")
            if text != raw:
                if a.check:
                    status = 1
                    say(f"{f}: valid, not canonical (run with --format)")
                    continue
                f.write_bytes(text)
                say(f"{f}: valid, reformatted")
                continue
        say(f"{f}: valid")
    return status


if __name__ == "__main__":
    sys.exit(main())
