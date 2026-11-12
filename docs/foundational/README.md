# Foundational

Permanent, true-now knowledge: what ARKarium is, how it is built, and why.
This is the **past, kept because it still matters**. Nothing here is a plan.

| Doc | Holds |
|---|---|
| [`what-is-arkarium.md`](what-is-arkarium.md) | Purpose, principles, clients, sibling projects. |
| [`architecture.md`](architecture.md) | What is built: layers, content format, scan, sync (+ invariants S1–S9), reading, TTS as built. |
| [`design-notes.md`](design-notes.md) | Code philosophy, settled decisions, refactor outcomes, testing strategy. |
| [`pitch.md`](pitch.md) | Stub. Outward-facing pitch, to be written. |

**Rules for this folder**

- Describes only what exists. A plan or "will" belongs in `../proposal/` or `../implementation/`.
- Prefer a pointer to code over a copy of it. Name the file or symbol; do not paste behavior that code already states.
- Work graduates here from `../implementation/` when its last rung is done: keep the *decisions and why*, drop the play-by-play.
- Every doc carries a `Verified:` line; update it only after checking the doc against the code.
