#!/usr/bin/env python3
"""Checks for the signed release build (#254). Run from the repo root.

  python3 scripts/release_check.py secrets
      Fails, naming (never printing) any missing RELEASE_* secret in the environment.
  python3 scripts/release_check.py pin [release-cert.sha256]
      Fails unless the pin file holds exactly one SHA-256 certificate fingerprint.
  python3 scripts/release_check.py verify APKSIGNER_OUTPUT [release-cert.sha256]
      APKSIGNER_OUTPUT is the saved output of
      `apksigner verify -v --print-certs --min-sdk-version 24 app-release.apk`. (With minSdk 29,
      apksigner checks only v3 unless --min-sdk-version 24 asks it to check v2 as well.)
      Fails unless v2 and v3 verified, there is exactly one signer, it isn't the public debug
      key, and its SHA-256 equals the pinned fingerprint.

The pin file is public data: the release certificate's SHA-256 (64 hex digits, colons and case
ignored). Lines starting with # are comments. The key itself never enters the repo.
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
DEFAULT_PIN = ROOT / "release-cert.sha256"

# The four secrets in the GitHub `release` environment (README "Release builds: signing key").
SECRETS = ("RELEASE_KEYSTORE_B64", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
# app/debug.keystore (#73). Public; must never sign a release.
DEBUG_CERT_SHA256 = "3a0cb6b59a1140b4b76d6a6fd70fbf3744f3174fd2479d89a3a148810cfd3dca"

FP_RE = re.compile(r"^[0-9a-f]{64}$")
SCHEME_RE = re.compile(r"^Verified using (v[0-9.]+) scheme \([^)]*\): (true|false)\s*$")
# `apksigner verify --print-certs` prints "Signer #1 certificate SHA-256 digest: …"; with -v it prints one line per
# scheme instead ("V2 Signer: certificate SHA-256 digest: …", "V3 Signer: …"). Both are accepted.
SIGNER_RE = re.compile(
    r"^(Signer #\d+|V[0-9.]+ Signer(?: #\d+)?):? certificate SHA-256 digest: ([0-9a-fA-F:]+)\s*$"
)
SIGNER_COUNT_RE = re.compile(r"^Number of signers: (\d+)\s*$")


class ReleaseCheckError(ValueError):
    pass


def normalize_fp(value: str) -> str:
    return value.strip().replace(":", "").lower()


def missing_secrets(env: dict[str, str] | None = None) -> list[str]:
    env = os.environ if env is None else env
    return [name for name in SECRETS if not (env.get(name) or "").strip()]


def read_pin(text: str) -> str:
    fps = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        fp = normalize_fp(line)
        if not FP_RE.fullmatch(fp):
            raise ReleaseCheckError(f"release-cert.sha256: not a SHA-256 fingerprint: {line!r}")
        fps.append(fp)
    if not fps:
        raise ReleaseCheckError(
            "release-cert.sha256 has no fingerprint yet. After generating the release key, commit its "
            "certificate SHA-256 there (README \"Release builds: signing key\")."
        )
    if len(fps) > 1:
        raise ReleaseCheckError("release-cert.sha256 must hold exactly one fingerprint")
    if fps[0] == DEBUG_CERT_SHA256:
        raise ReleaseCheckError("release-cert.sha256 is the public debug key's fingerprint")
    return fps[0]


def parse_apksigner(text: str) -> tuple[bool, dict[str, bool], list[str], int | None]:
    """Return (verifies, {scheme: verified}, signer certificate SHA-256s, "Number of signers")."""
    verifies = False
    schemes: dict[str, bool] = {}
    signers: list[str] = []
    count = None
    for raw in text.splitlines():
        line = raw.strip()
        if line == "Verifies":
            verifies = True
            continue
        m = SCHEME_RE.match(line)
        if m:
            schemes[m.group(1)] = m.group(2) == "true"
            continue
        m = SIGNER_RE.match(line)
        if m:
            signers.append(normalize_fp(m.group(2)))
            continue
        m = SIGNER_COUNT_RE.match(line)
        if m:
            count = int(m.group(1))
    return verifies, schemes, signers, count


def verify(apksigner_output: str, pin_text: str) -> str:
    pin = read_pin(pin_text)
    verifies, schemes, signers, count = parse_apksigner(apksigner_output)
    if not verifies:
        raise ReleaseCheckError("apksigner did not print \"Verifies\": the APK's signature is missing or invalid")
    for scheme in ("v2", "v3"):
        if not schemes.get(scheme):
            raise ReleaseCheckError(f"APK is not verified with the {scheme} scheme (needs v2 + v3)")
    if count is not None and count != 1:
        raise ReleaseCheckError(f"APK has {count} signers; expected exactly 1")
    distinct = sorted(set(signers))
    if len(distinct) != 1:
        raise ReleaseCheckError(f"expected one signer certificate, found {len(distinct)}")
    actual = distinct[0]
    if actual == DEBUG_CERT_SHA256:
        raise ReleaseCheckError("APK is signed with the public debug key; never release that")
    if actual != pin:
        raise ReleaseCheckError(
            f"APK signer SHA-256 {actual} does not match the pinned release-cert.sha256 {pin}"
        )
    return actual


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    try:
        if args[:1] == ["secrets"] and len(args) == 1:
            missing = missing_secrets()
            if missing:
                raise ReleaseCheckError(
                    "missing release secrets: " + ", ".join(missing)
                    + ". Add them to the GitHub `release` environment (README \"Release builds: signing key\")."
                    " The release APK is never signed with the debug key."
                )
            print("release secrets: all 4 set")
        elif args[:1] == ["pin"] and len(args) <= 2:
            pin = Path(args[1]) if len(args) == 2 else DEFAULT_PIN
            print(f"pinned release certificate SHA-256: {read_pin(pin.read_text())}")
        elif args[:1] == ["verify"] and len(args) in (2, 3):
            pin = Path(args[2]) if len(args) == 3 else DEFAULT_PIN
            fp = verify(Path(args[1]).read_text(), pin.read_text())
            print(f"release APK: v2 + v3 verified, signer SHA-256 {fp} matches release-cert.sha256")
        else:
            sys.stderr.write(__doc__)
            return 2
        return 0
    except (ReleaseCheckError, OSError) as e:
        sys.stderr.write(f"release_check.py: {e}\n")
        return 1


if __name__ == "__main__":
    sys.exit(main())
