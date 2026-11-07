# Manifest signing: the sync manifest proves integrity, not authenticity

> **Kind:** proposal · **Status:** key placement and custody decided (D5); scheme (ECDSA vs Ed25519) and implementation still open · **Verified:** 2026-11-07 @ b777145
> **Origin:** [`engineering-review.md`](engineering-review.md) F4.

**Severity:** Medium. **Tags:** V (manifest handling read in code), E (signature-scheme guidance not re-checked this pass).
**Where:** `data/SyncManager.kt` (`sha256Hex`, manifest parsing, `sync()`).

**What the code does:** `sync()` fetches `manifest.json` from the relay,
reads a `sha256` per file entry, and after downloading each file re-hashes it
and compares against the manifest's claimed value (`actualHash != entry.sha256`
→ rejected). A sync is skipped only when the fetched manifest's version equals
the version already stored on the novel; any other version — including one
*lower* than what's stored — triggers a full sync that overwrites local files.

**Why that's not enough:** every value being checked — the file hashes and the
manifest itself — comes from the same relay. Hashing proves the files match
what the manifest says, not that the manifest itself was produced by someone
authorized to publish for that fiction. A relay (or a compromised/malicious
mirror in the planned [`relay-chain.md`](relay-chain.md) design) can serve a
self-consistent manifest plus matching files and every check here passes. The
missing-version-ordering check means a malicious or just-stale relay can also
roll a novel back to an older manifest version, since nothing rejects
`version < stored`, and nothing like a signed `expires` field catches a relay
that keeps serving a manifest long after a newer one exists (a freeze attack).

This is also the correction noted in `engineering-review.md` C1:
`relay-chain.md` said "the hash check is what makes a mirror safe to trust" —
it doesn't, for the same reason above. Signing is a prerequisite for that
proposal, not an independent improvement to make afterward.

**Direction:**
1. Sign `manifest.json`; embed the corresponding public key in the APK.
2. Reject any fetched manifest whose `version` is lower than the version
   already stored for that novel (closes the rollback case).
3. Add a signed `expires` field to the manifest and reject an expired one
   (closes the freeze case).
4. Accept multiple public keys at once (an array in the APK, any one valid
   signature accepted) so a key can be rotated without an app update being a
   hard dependency of rotation.

**Dependency note (E — re-check before committing to a scheme):** ECDSA
P-256 verification is available in the Android platform crypto APIs from API
24 (this app's `minSdk`), so it needs no new library. Ed25519 (the
minisign-style option) needs API 33 on-platform; below that it requires a
library (Tink or BouncyCastle were the ones considered). This tradeoff should
be re-checked against current platform docs before the scheme is picked, not
assumed from this note.

**Decided (D5):** key custody and placement, settled ahead of the scheme
details above.

- **Sign at publish time, in the author's own publishing pipeline** (see
  [`ideas.md`](ideas.md)'s "Publisher pipeline (ARKlight)" row) — not inside
  the Cloudflare Worker `RELAY_HOST` points to. If the Worker held the
  signing key, a compromised Worker could sign whatever it likes and the
  signature would stop proving anything; signing only buys security if the
  thing doing the signing is harder to compromise than the thing serving
  files. A relay or mirror that only ever serves already-signed static files
  needs no key at all — which also means a future mirror in
  [`relay-chain.md`](relay-chain.md) can run with zero secrets.
- **Key stays offline** (on the author's own machine, used only at publish
  time), not loaded into any always-on service.
- **A long `expires`, or none at first.** The freeze-attack protection
  (direction step 3 above) is worth having, but rollback protection (step 2
  — rejecting `version < stored`) is where most of the value is; don't block
  shipping steps 1–2 on picking an exact expiry policy for step 3.

Rotation procedure and the ECDSA-vs-Ed25519 choice (the **E**-tagged
dependency note above) are still open and should be re-checked against
current platform docs before implementation starts.
