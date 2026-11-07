# Bug reports

> **Kind:** proposal · **Status:** open reports only · **Verified:** 2026-11-07 @ b777145

Open, unfixed bugs, one file per bug. A fixed bug's file is deleted; if the
cause taught a rule, it goes in
[`../../foundational/`](../../foundational/README.md) (see sync invariants
S6–S9 in `architecture.md` for the pattern), otherwise the commit message is
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
| [`critical/scan-reconcile-treats-errors-as-absence.md`](critical/scan-reconcile-treats-errors-as-absence.md) | critical |
| [`other/notify-toggle-confirmation.md`](other/notify-toggle-confirmation.md) | other |
