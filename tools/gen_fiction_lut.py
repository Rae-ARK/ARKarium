#!/usr/bin/env python3
"""
gen_fiction_lut.py - builds app/src/main/assets/fiction_lut.dat, the encrypted
name->slug lookup table FictionLut.kt reads at runtime.

Usage:
    python3 tools/gen_fiction_lut.py

Edit FICTIONS below to add/rename/remove entries, then rerun this and rebuild
the app - the LUT is bundled at build time (see the earlier design decision:
"bundled in APK, update app to add fictions"), so a new fiction needs a new
app build to become discoverable.

The AES-256 key here MUST exactly match the key FictionLut.kt reconstructs
from keyPartA/keyPartB (assembleKey() XORs them back together). If you ever
rotate the key, regenerate both keyPartA/keyPartB in FictionLut.kt and this
KEY_HEX together - they're two views of the same 32 bytes, not independent
secrets.

Reminder (see FictionLut.kt's own docstring): this is light obfuscation, not
real cryptographic protection. The key ships inside the app binary either
way. It just keeps the slug scheme from being readable via `unzip app.apk &&
cat assets/fiction_lut.dat`.
"""
import json
import os
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# --- Edit this to add/remove/rename fictions -------------------------------
# display name (matched case-insensitively, whitespace-collapsed) -> relay slug
FICTIONS = {
    "Summoned By Mistake, I Decided To Learn How To Live": "sbm",
}
# -----------------------------------------------------------------------------

# Must match keyPartA XOR keyPartB in FictionLut.kt exactly.
KEY_HEX = "95a1eeb725f44e32c20b5df0fda74ddcba299a86773259aed4b59841d3308cca"

OUT_PATH = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "fiction_lut.dat"


def main():
    key = bytes.fromhex(KEY_HEX)
    if len(key) != 32:
        raise SystemExit(f"KEY_HEX must decode to 32 bytes (AES-256), got {len(key)}")

    plaintext = json.dumps(FICTIONS, ensure_ascii=False).encode("utf-8")

    iv = os.urandom(12)  # AES-GCM standard IV size
    aesgcm = AESGCM(key)
    ciphertext = aesgcm.encrypt(iv, plaintext, associated_data=None)  # tag is appended automatically

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_bytes(iv + ciphertext)

    print(f"Wrote {OUT_PATH} ({len(FICTIONS)} fictions, {OUT_PATH.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
