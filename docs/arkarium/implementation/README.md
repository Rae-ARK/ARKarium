# Implementation

Work **in progress**, as a staged rung ladder. This is the **present**.

Each doc is one ladder: a table of rungs, each `done`, `current`, `next`,
`queued`, or `blocked`. Exactly one rung per ladder is `current`.

| Ladder | Status | Current rung |
|---|---|---|
| [`tts-playback-controls.md`](tts-playback-controls.md) | rungs 1-2a done | 3: chunk-level progress indicator |
| [`test-coverage.md`](test-coverage.md) | not started | 4.1 (next): `MetadataViewModelTest` |
| [`engineering-backlog.md`](engineering-backlog.md) | 12 of 18 rungs done (1–11, 14) | 12 (next, own ladder when started): F3 novel identity |
| [`multi-document-reader.md`](multi-document-reader.md) | rung 0 done | 1: loud (not silent) TTS listener collision |

**Rules for this folder**

- Entry: a [`proposal/`](../proposal/README.md) item is promoted here when someone commits to build it. Move it (`git mv`), then break it into rungs, each small enough to ship and revert alone.
- Keep done rungs to one line. The code and `git log` are the detail.
- Exit: when the last rung is done, move the *decisions and why* into [`foundational/`](../foundational/README.md) and delete the ladder.
- Update the rung state in the same commit that changes the code.
