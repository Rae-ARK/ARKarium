# Bug reports

> **Kind:** proposal · **Status:** open reports only · **Verified:** 2026-11-08 @ (this patch)

Open, unfixed bugs, one file per bug. A fixed bug's file is deleted; if the
cause taught a rule, it goes in
[`../../foundational/`](../../foundational/README.md) (see sync invariants
S6–S10 in `architecture.md` for the pattern), otherwise the commit message is
the record.

Split by severity into two folders:

| Folder | Criteria |
|---|---|
| [`critical/`](critical/README.md) | Crashes, data loss, or a core flow (sync, reading, TTS playback) completely broken for everyone. |
| [`other/`](other/README.md) | Everything else: wrong or undiscoverable behavior, edge cases, items that need on-device confirmation before they can be closed. |

Format per bug file: `# <short title>`, then **Seen** · **Where** · **Repro**
· **Expected / Actual**, and **Cause** / **Status** when known.

Historic ARKster-era bug history is frozen in
[`../../../reference/arkster/bugs.md`](../../../reference/arkster/bugs.md).

## Open bugs

| Bug | Severity |
|---|---|
| [`other/legacy-percentage-position-lost-on-upgrade.md`](other/legacy-percentage-position-lost-on-upgrade.md) | other — upgrading from a pre-rung-14 build silently resets in-progress chapters to the top |

The two previously tracked here are both fixed and their files deleted per
the rule above:

- Scan reconcile cascade-deleting a novel's history on an ordinary scan —
  see [`../../implementation/engineering-backlog.md`](../../implementation/engineering-backlog.md) rung 1.
- "Notify me" toggle undiscoverability and the sync-detection bug it
  surfaced — see [`../../implementation/engineering-backlog.md`](../../implementation/engineering-backlog.md) rung 11,
  and sync invariant S10 in [`../../foundational/architecture.md`](../../foundational/architecture.md).
