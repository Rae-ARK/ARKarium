# Future plans

> **Status:** Idea / architecture notes, same spirit as `docs/ARKarium_Idea.md`
> — nothing here is built. Captures a design-review pass over what's missing
> relative to the app's own stated v1 vision, plus a concrete first pass at
> the TTS architecture the idea doc calls out as the project's "killer
> feature." See `docs/NEXT_FIXES.md` for known sync bugs to fix first — this
> file is everything past that, further out.

---

## 1. Gap check against the stated v1 vision

### The big one: TTS is entirely unbuilt

`docs/ARKarium_Idea.md` calls character-aware multi-voice TTS **"the killer
feature"** — the stated reason this isn't just another Royal Road clone.
There is currently no TTS code anywhere in `app/src`: no `TextToSpeech`
usage, no voice/character data model, nothing. Even Stage 1 (narration
voice + basic dialogue-alternate voice) doesn't exist yet. See §2 below for
a concrete architecture proposal.

### Explicitly punted in `SYNC_MVP.md` §6 — still open

- **Multi-relay fallback chain** (Relay A → B → C from the idea doc) — sync
  is single-origin only today. If the one relay host goes down, there's no
  fallback.
- **Auth/paid-content gating** — no concept of gated chapters; everything
  synced is fully public.
- **Background/scheduled sync** — "Check for updates" is a manual,
  foreground-only per-novel action; no periodic check exists.
- **ARKlight as a real publish pipeline** — manifest generation is still a
  throwaway script that walks a folder and writes sha256s, not a maintained
  authoring tool. Not blocking for the client's own v1, but blocking for a
  real multi-author ecosystem later.

### From `app/README.md`'s own "Known limitations"

- **EPUB/PDF ingestion isn't implemented.** The README points at
  `docs/EPUB_SUPPORT.md` for details, but that file doesn't exist in this
  repo — worth resolving (write the doc, or drop the reference) rather than
  leaving a dangling pointer.
- **Search is title/author only** — doesn't search chapter contents. Fine
  at small library sizes, probably not once someone's synced a dozen
  400-chapter novels.

### A documentation gap worth flagging on its own

Several source comments reference docs not actually present in this repo:
`bugs.md` (cited repeatedly around rescan/dedup fixes),
`EPUB_SUPPORT.md`, and `AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md` (cited for the
author-linking resolution order). Either they live outside this repo or
were never committed — worth tracking down or reconstructing, since real
design history is currently invisible to anyone reading only what's checked
in.

### What's already in good shape

Worth stating plainly: the core reading experience is close to solid —
SAF folder scanning, incremental fingerprinted rescans, arc/chapter
detection with sort-tier handling, author pages, chapter overrides,
encoding fallback, crash recovery. The gap is concentrated in TTS (missing
entirely) and sync robustness/scope (see `docs/NEXT_FIXES.md`), not in the
reader itself.

---

## 2. TTS architecture — first pass

### Scope decision: precompute offline, ship a static file, client just plays it

Two architectures were considered:

1. **On-device streaming** — a small model (~26M–1B params) runs on the
   reader's phone, annotating text just ahead of playback, feeding a
   producer/consumer pipeline (`kotlinx.coroutines.channels.Channel`) into
   the TTS engine so nothing beyond the current window is held in memory.
2. **Precompute once, publish-side; client only ever reads a static file.**

**(2) is the chosen direction for MVP.** The text is static once
published (chapter edits are rare, and manual overrides via
`chapter_overrides` never touch body content), so there's no requirement to
run attribution live per-reader, per-play. Running inference once at
publish time instead of once per device removes the two biggest risks of
the on-device approach at a stroke: model footprint on readers' phones (a
1B-class GGUF model is roughly 500MB–1GB), and per-play inference latency.
It also means readers' devices never run a model at all — they download a
plain JSON file through the sync path that already exists and works.

This also leaves room for the plan to eventually ship real recorded/rendered
audio files per chapter, without redesigning the manifest twice — a chapter
can carry either a segment-list JSON (client synthesizes via TTS) or a
direct audio file reference (client just plays it), same manifest shape,
different terminal step.

### Publish-side tooling

Same role `tools/gen_fiction_lut.py` already plays — a build-time script,
not app code, never shipped in the APK:

- Pull a small instruction-tuned model (candidates worth testing directly
  against real chapters before committing: Qwen2.5-0.5B/1.5B-Instruct,
  Llama-3.2-1B-Instruct, SmolLM2) as GGUF weights, run via `llama.cpp` /
  `llama-cpp-python` — boring, CPU-friendly, matches the project's existing
  "keep infrastructure unexciting" philosophy.
- Cache the downloaded model between runs (keyed by name/revision); a flag
  decides whether to keep it after the run or treat it as ephemeral.
- For MVP: **skip the model-quality validation step and ship the JSON
  format now.** The open question ("is a small model accurate enough at
  speaker attribution on this specific author's prose?") gets resolved
  empirically once real output exists to look at, rather than gating the
  schema/pipeline work on it up front. If attribution quality turns out to
  be poor, the fallback (default everything to narrator, or move straight
  to the recorded-audio path already planned) doesn't require touching the
  client at all — only the publish-side generation.

### Segment file format

One file per chapter, synced like any other manifest entry
(`tts/arc-1/001.json`), routed the same special-cased way `authors/`
entries already are in `downloadManifestFiles` — except scoped to the
novel's own folder (chapter-specific), not the shared library root
(`authors/` is shared across novels; `tts/` isn't).

```json
{
  "version": 1,
  "chapter": "arc 1/001.txt",
  "segments": [
    { "text": "The rain hadn't stopped in three days.", "speaker": "narrator" },
    { "text": "\"We need to leave now,\"", "speaker": "elena" },
    { "text": "Elena said, already moving toward the door.", "speaker": "narrator" }
  ]
}
```

- `chapter` ties the annotation back to the exact `.txt` it covers, so a
  stale annotation (text edited, annotation not regenerated) is at least
  detectable.
- `speaker` is a stable lowercase id, not a display name — same principle
  as `authorId` linking a novel to `authors/<id>.json` rather than matching
  on free-text names. This id is the join key against the client-side voice
  mapping.
- `segments` is ordered and played sequentially; text spans are literal
  source substrings (quote marks included) so they reconstruct the
  original chapter losslessly.
- The file's own top-level `version` (distinct from the outer sync
  manifest's `version`) lets the schema evolve later without breaking
  chapters already annotated under an older shape — an unrecognized version
  should make the client treat the chapter as "no TTS available," not
  crash.
- Sentinel for uncertain/unattributed lines: default to `"narrator"` rather
  than inventing an `"unknown"` state the client has to special-case.

### Client-side scope (the part actually being built now)

- **Sync:** teach `SyncManager` to recognize `tts/` manifest entries, same
  special-casing pattern as `authors/`, written under the novel's own
  folder.
- **Voice mapping:** a `CharacterVoiceEntity`-style table, `speaker` id →
  installed TTS voice, novel-scoped, with one default entry covering
  anything unmapped. User-overridable, and — same pattern
  `ChapterOverrideEntity` already establishes — the override should survive
  a rescan/resync rather than being clobbered by it.
- **Playback:** no model in the loop. Read the chapter's `tts/*.json`
  directly off disk (same as `TextChapterContentRepository` reads chapter
  `.txt` directly rather than through Room), loop `segments` in order, look
  up each segment's voice, call `TextToSpeech.speak()`, advance on the
  completion callback. Plain sequential code — the real-time/streaming
  complexity from architecture (1) above doesn't apply once annotation is
  precomputed.
- **Missing-file case:** a chapter without a `tts/` file simply doesn't
  show a "Listen" affordance — not an error state, just an absent feature
  for that chapter (older content, or not yet annotated).

### Known constraints worth stating up front, not discovering later

- **Voice variety is a separate bottleneck from the pipeline itself.**
  Android's `TextToSpeech` API selects among installed voices, but the
  number of genuinely distinct-sounding voices per language/engine is
  often limited — a large cast may not get one clearly distinguishable
  voice each without either accepting some overlap or bundling additional
  voice models later.
- **Attribution accuracy is genuinely unproven at this model size.** Web
  fiction's heavy use of explicit dialogue tags ("X said") helps a small
  model more than literary fiction would, but this needs checking against
  real chapters from this author specifically before trusting it broadly —
  see "publish-side tooling" above.

---

## 3. How the pieces fit together (end to end)

```
Layer 1 — Publish-side (your machine, off-device, build-time only)
  Your prose (.txt)
     -> small-model annotation script (Python, llama.cpp/GGUF)
     -> tts/arc-1/001.json  (speaker-tagged segments, per chapter)

Layer 2 — Relay (static host, no logic — SYNC_MVP.md §2)
  novels.horizonarkstudio.workers.dev/<slug>/
    manifest.json, metadata.json, cover.png,
    arcs/arc-1/001.txt, tts/arc-1/001.json  <- rides the manifest like any other file

Layer 3 — Sync (SyncManager.kt)
  manifest fetched -> version compared -> hash diff against SyncedFileEntity
     -> tts/ entries get authors/-style special-case routing,
        written under the novel's own folder

Layer 4 — Local storage + Room (on-device, persistent)
  SAF folder tree (arcs/, tts/) scanned but not parsed into rows —
  read directly at playback time, same as chapter text itself.
  Only CharacterVoiceEntity (speaker -> voice, user-overridable) is a
  real DB table, since it's the one piece of this system that's actual
  mutable local state rather than synced content.

Layer 5 — Playback (runtime, in-memory only)
  Load tts/<chapter>.json -> for each segment, look up voice by speaker
     -> TextToSpeech.speak() -> next segment on completion callback.
  No model, no concurrency — the hard part (attribution) already
  happened at Layer 1, off the runtime path entirely.
```

The one convention that has to stay consistent across every layer:
`speaker` in the segment JSON and the key in `CharacterVoiceEntity` must
agree on the same id scheme (stable lowercase id, not a display name) —
same principle as `authorId` today. This is the seam between something
generated offline and something read on-device; getting it wrong produces
a silent no-match rather than a build error, so it's worth locking down
explicitly rather than left implicit.
