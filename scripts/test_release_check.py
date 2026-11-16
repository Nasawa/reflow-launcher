#!/usr/bin/env python3
"""Tests for scripts/release_check.py (#254). Run from the repo root: python3 scripts/test_release_check.py

Uses canned apksigner output only: no keystore, no secrets, no real clock.
"""
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
import release_check as R  # noqa: E402

FAKE = "ab" * 32  # a made-up release fingerprint
FAKE_COLONS = ":".join(FAKE[i:i + 2] for i in range(0, 64, 2)).upper()
OTHER = "cd" * 32


def apksigner(v2=True, v3=True, fps=(FAKE,), verifies=True, count=None, verbose=True):
    lines = ["Verifies" if verifies else "DOES NOT VERIFY"]
    lines.append("Verified using v1 scheme (JAR signing): false")
    lines.append(f"Verified using v2 scheme (APK Signature Scheme v2): {str(v2).lower()}")
    lines.append(f"Verified using v3 scheme (APK Signature Scheme v3): {str(v3).lower()}")
    lines.append("Verified using v3.1 scheme (APK Signature Scheme v3.1): false")
    lines.append("Verified using v4 scheme (APK Signature Scheme v4): false")
    lines.append(f"Number of signers: {len(fps) if count is None else count}")
    for i, fp in enumerate(fps, 1):
        if verbose:
            for scheme, on in (("V2", v2), ("V3", v3)):
                if on:
                    lines.append(f"{scheme} Signer: certificate DN: CN=Reflow Launcher, O=AniGeek")
                    lines.append(f"{scheme} Signer: certificate SHA-256 digest: {fp}")
                    lines.append(f"{scheme} Signer: public key SHA-256 digest: {'ee' * 32}")
        else:
            lines.append(f"Signer #{i} certificate DN: CN=Reflow Launcher, O=AniGeek")
            lines.append(f"Signer #{i} certificate SHA-256 digest: {fp}")
    return "\n".join(lines) + "\n"


# Real `apksigner verify -v --print-certs` output for a debug APK (build-tools 37), trimmed to the parsed lines.
DEBUG_APK_OUTPUT = """Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v3.2 scheme (APK Signature Scheme v3.2): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
V2 Signer: certificate DN: CN=Config Launcher Debug (not for release), O=Nasawa, C=US
V2 Signer: certificate SHA-256 digest: 3a0cb6b59a1140b4b76d6a6fd70fbf3744f3174fd2479d89a3a148810cfd3dca
V2 Signer: public key SHA-256 digest: 10af55b9fac6dd4836df6ed87afef5019f018e9b592df6f38151c9bbca336f5a
"""


# Real `apksigner verify -v --print-certs` output (build-tools 37) for a release APK signed by this repo's Gradle
# release signingConfig with a throwaway PKCS12 key, trimmed to the parsed lines. AGP writes both v2 and v3 blocks, but
# for minSdk 29 apksigner only checks v3 unless told `--min-sdk-version 24`, so release.yml passes that flag.
THROWAWAY = "097e5ceee244458ccc23392a5a1762491452cf00e89b9fe0f6c77f1176de7cdc"
RELEASE_APK_DEFAULT = """Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): false
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v3.2 scheme (APK Signature Scheme v3.2): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
V3.0 Signer: certificate SHA-256 digest: 097e5ceee244458ccc23392a5a1762491452cf00e89b9fe0f6c77f1176de7cdc
V3.0 Signer: public key SHA-256 digest: a9183a986cf7e3eb87a1e94fd6d5b3a5f3ca00b59ba06ac2cbad0a8cdc0e0ff6
"""
RELEASE_APK_MIN24 = RELEASE_APK_DEFAULT.replace(
    "Verified using v2 scheme (APK Signature Scheme v2): false", "Verified using v2 scheme (APK Signature Scheme v2): true")

class Secrets(unittest.TestCase):
    ALL = {name: "x" for name in R.SECRETS}

    def test_names_match_the_docs(self):
        self.assertEqual(R.SECRETS, ("RELEASE_KEYSTORE_B64", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD"))
        readme = (ROOT / "README.md").read_text()
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        for name in R.SECRETS:
            with self.subTest(name=name):
                self.assertIn(name, readme)
                self.assertIn(f"secrets.{name}", workflow)

    def test_all_set(self):
        self.assertEqual(R.missing_secrets(self.ALL), [])

    def test_none_set(self):
        self.assertEqual(R.missing_secrets({}), list(R.SECRETS))

    def test_blank_counts_as_missing(self):
        env = dict(self.ALL, RELEASE_KEY_ALIAS="  ")
        self.assertEqual(R.missing_secrets(env), ["RELEASE_KEY_ALIAS"])

    def test_cli_names_missing_but_never_prints_values(self):
        env = {k: v for k, v in os.environ.items() if k not in R.SECRETS}
        env["RELEASE_STORE_PASSWORD"] = "s3cret-value-123"
        r = subprocess.run([sys.executable, str(HERE / "release_check.py"), "secrets"],
                           cwd=ROOT, env=env, capture_output=True, text=True)
        self.assertEqual(r.returncode, 1)
        self.assertIn("RELEASE_KEYSTORE_B64, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD", r.stderr)
        self.assertNotIn("RELEASE_STORE_PASSWORD,", r.stderr)
        self.assertNotIn("s3cret", r.stdout + r.stderr)

    def test_cli_all_set(self):
        env = dict(os.environ, **{k: "v" for k in R.SECRETS})
        r = subprocess.run([sys.executable, str(HERE / "release_check.py"), "secrets"],
                           cwd=ROOT, env=env, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.strip(), "release secrets: all 4 set")


class Pin(unittest.TestCase):
    def test_committed_pin_file_has_only_comments_until_rc1(self):
        # Flip this when Christopher commits the fingerprint: then read_pin must return it.
        text = (ROOT / "release-cert.sha256").read_text()
        try:
            fp = R.read_pin(text)
        except R.ReleaseCheckError as e:
            self.assertIn("no fingerprint yet", str(e))
        else:
            self.assertRegex(fp, r"^[0-9a-f]{64}$")

    def test_reads_one_fingerprint_with_comments(self):
        self.assertEqual(R.read_pin(f"# comment\n\n{FAKE}\n"), FAKE)

    def test_colons_and_case_ignored(self):
        self.assertEqual(R.read_pin(FAKE_COLONS), FAKE)

    def test_empty_fails_clearly(self):
        with self.assertRaises(R.ReleaseCheckError) as e:
            R.read_pin("# nothing yet\n")
        self.assertIn("no fingerprint yet", str(e.exception))

    def test_rejects_bad_and_multiple(self):
        for text in ("abc\n", FAKE + "0\n", "zz" * 32, f"{FAKE}\n{OTHER}\n"):
            with self.subTest(text=text):
                with self.assertRaises(R.ReleaseCheckError):
                    R.read_pin(text)

    def test_rejects_the_debug_fingerprint(self):
        with self.assertRaises(R.ReleaseCheckError):
            R.read_pin(R.DEBUG_CERT_SHA256)


class Verify(unittest.TestCase):
    def test_good_release_verbose_and_plain(self):
        self.assertEqual(R.verify(apksigner(), FAKE), FAKE)
        self.assertEqual(R.verify(apksigner(verbose=False), FAKE_COLONS), FAKE)

    def test_needs_v2_and_v3(self):
        for kwargs in ({"v2": False}, {"v3": False}):
            with self.subTest(**kwargs):
                with self.assertRaises(R.ReleaseCheckError) as e:
                    R.verify(apksigner(**kwargs), FAKE)
                self.assertIn("scheme", str(e.exception))

    def test_unsigned_or_invalid_fails(self):
        with self.assertRaises(R.ReleaseCheckError):
            R.verify(apksigner(verifies=False), FAKE)
        with self.assertRaises(R.ReleaseCheckError):
            R.verify("DOES NOT VERIFY\nERROR: Missing META-INF/MANIFEST.MF\n", FAKE)
        with self.assertRaises(R.ReleaseCheckError):
            R.verify("", FAKE)

    def test_wrong_signer_fails(self):
        with self.assertRaises(R.ReleaseCheckError) as e:
            R.verify(apksigner(fps=(OTHER,)), FAKE)
        self.assertIn("does not match the pinned", str(e.exception))

    def test_two_signers_fail(self):
        with self.assertRaises(R.ReleaseCheckError):
            R.verify(apksigner(fps=(FAKE, OTHER), verbose=False), FAKE)
        with self.assertRaises(R.ReleaseCheckError):
            R.verify(apksigner(count=2), FAKE)

    def test_debug_key_is_never_a_release(self):
        with self.assertRaises(R.ReleaseCheckError):
            R.verify(DEBUG_APK_OUTPUT, FAKE)
        out = apksigner(fps=(R.DEBUG_CERT_SHA256,))
        with self.assertRaises(R.ReleaseCheckError) as e:
            R.verify(out, FAKE)
        self.assertIn("debug key", str(e.exception))

    def test_real_debug_output_parses(self):
        verifies, schemes, signers, count = R.parse_apksigner(DEBUG_APK_OUTPUT)
        self.assertTrue(verifies)
        self.assertEqual((schemes["v2"], schemes["v3"], schemes["v1"]), (True, False, False))
        self.assertEqual(signers, [R.DEBUG_CERT_SHA256])
        self.assertEqual(count, 1)

    def test_real_release_output_with_min_sdk_24(self):
        self.assertEqual(R.verify(RELEASE_APK_MIN24, THROWAWAY), THROWAWAY)

    def test_real_release_output_without_the_flag_fails_on_v2(self):
        # Locks why release.yml passes --min-sdk-version 24: without it v2 isn't checked, so it can't be shown.
        with self.assertRaises(R.ReleaseCheckError) as e:
            R.verify(RELEASE_APK_DEFAULT, THROWAWAY)
        self.assertIn("v2", str(e.exception))

    def test_release_workflow_forces_the_v2_check(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        self.assertIn("verify -v --print-certs --min-sdk-version 24", workflow)
        self.assertIn("release_check.py verify", workflow)
        self.assertIn("--expect-tag", workflow)

    def test_release_workflow_actions_are_sha_pinned(self):
        # #268: every uses: must be a 40-char commit SHA, with the version in a comment.
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        uses = []
        for i, line in enumerate(workflow.splitlines(), 1):
            stripped = line.lstrip()
            if stripped.startswith("#"):
                continue
            if stripped.startswith("- "):
                stripped = stripped[2:].lstrip()
            if stripped.startswith("uses:"):
                uses.append((i, stripped[len("uses:"):].strip()))
        self.assertTrue(uses)
        pin = re.compile(r"^[A-Za-z0-9_.+/-]+@[0-9a-f]{40}\s+#\s+v\d+\.\d+\.\d+$")
        for i, value in uses:
            with self.subTest(line=i, uses=value):
                self.assertRegex(value, pin)

    def test_keystore_decode_disables_xtrace(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        # The decode step is the only place the keystore bytes are in a shell command.
        start = workflow.find("Decode the keystore to the runner's temp dir")
        self.assertGreater(start, 0)
        step = workflow[start:]
        nxt = step.find("\n      - ")
        self.assertGreater(nxt, 0)
        step = step[:nxt]
        decode_at = step.find("base64 -d")
        self.assertGreater(decode_at, 0)
        self.assertIn("set +x", step[:decode_at])
        commands = [ln for ln in step.splitlines() if ln.strip() and not ln.lstrip().startswith("#")]
        self.assertTrue(any(ln.strip() == "set +x" for ln in commands))
        self.assertFalse(any("set -x" in ln for ln in commands))
        # The keystore env is piped into base64 -d (redirected to a file), never echoed.
        self.assertIn("printf '%s' \"$RELEASE_KEYSTORE_B64\" | base64 -d >", step)
        self.assertNotRegex(step, r"echo\s+.*\$RELEASE_KEYSTORE_B64")

    def test_debug_cert_matches_ci_yml(self):
        self.assertIn(R.DEBUG_CERT_SHA256, (ROOT / ".github/workflows/ci.yml").read_text())

    def test_cli_verify(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "verify.txt"
            pin = Path(tmp) / "pin"
            out.write_text(apksigner())
            pin.write_text(FAKE + "\n")
            r = subprocess.run([sys.executable, str(HERE / "release_check.py"), "verify", str(out), str(pin)],
                               cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            pin.write_text(OTHER + "\n")
            r = subprocess.run([sys.executable, str(HERE / "release_check.py"), "verify", str(out), str(pin)],
                               cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(r.returncode, 1)
            self.assertIn("does not match", r.stderr)

    def test_cli_usage(self):
        r = subprocess.run([sys.executable, str(HERE / "release_check.py")], cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(r.returncode, 2)


if __name__ == "__main__":
    unittest.main()
