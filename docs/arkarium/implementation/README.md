# Implementation

Work **in progress**, as a staged rung ladder. This is the **present**.

Each doc is one ladder: a table of rungs, each `done`, `current`, `next`,
`queued`, or `blocked`. Exactly one rung per ladder is `current`.

| Ladder | Status | Current rung |
|---|---|---|
| [`settings-tts.md`](settings-tts.md) | 1 rung left | 4: tests for `tts_*` preferences |
| [`tts-playback-controls.md`](tts-playback-controls.md) | 2 rungs done | 3: chunk-level progress indicator |
| [`test-coverage.md`](test-coverage.md) | not started | 4.1 (next): `MetadataViewModelTest` |

**Rules for this folder**

- Entry: a [`proposal/`](../proposal/README.md) item is promoted here when someone commits to build it. Move it (`git mv`), then break it into rungs, each small enough to ship and revert alone.
- Keep done rungs to one line. The code and `git log` are the detail.
- Exit: when the last rung is done, move the *decisions and why* into [`foundational/`](../foundational/README.md) and delete the ladder.
- Update the rung state in the same commit that changes the code.
