# Multi-relay fallback chain

> **Kind:** proposal · **Status:** design only · **Verified:** 2026-11-06 @ b4fcc2f
> **Today:** single fixed relay host (see [`../foundational/architecture.md`](../foundational/architecture.md), Sync).

If the one relay goes down or fills up, readers have no fallback. Idea: each
relay knows only its successor, so deployments can be added or replaced
without the client knowing the full list.

```text
client → Relay A → (asset missing) → Relay B → Relay C → asset
```

Manifest gains an optional `"next": "https://relay-b.example/"`.

**Open question (decides the design):** pure static hosting cannot notice a
missing asset and redirect. Options: (1) a tiny request-handling Worker per
relay, (2) client-side traversal where the client implements the protocol, or
(3) a hybrid. Keep the protocol simple whichever is chosen.

**Constraints when built:** S2 (untrusted paths) and S3/S4 (ownership, partial
failure) in `architecture.md` apply across relays; the hash check is what
makes a mirror safe to trust.
