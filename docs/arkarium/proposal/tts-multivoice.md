# Character-aware multi-voice TTS

> **Kind:** proposal · **Status:** design only · **Verified:** 2026-11-06 @ b4fcc2f
> **Builds on:** single-voice system TTS already shipped (see [`../foundational/architecture.md`](../foundational/architecture.md), Text-to-speech).

The planned differentiator: a free, automatic, "slightly-less-terrible
audiobook" with no recording effort from the author. ARKarium controls both
the publish format and the reader, so it can know who is speaking.

## Staged ambition

1. Narrator voice, dialogue detection, a second voice for dialogue.
2. Character recognition, persistent per-character voice.
3. Per-character pitch, speed, delivery.
4. Contextual emotion, emphasis, pauses, pronunciation.

## Decision: precompute at publish time, client only plays

Rejected: on-device small model annotating text just ahead of playback
(500 MB–1 GB model on readers' phones, per-play latency, producer/consumer
pipeline). Chosen: run attribution **once on the author's machine**, ship a
static JSON file per chapter through the existing sync path. Devices never run
a model. A chapter may later carry either a segment list (client synthesizes)
or a direct audio file (client plays); same manifest shape.

## Publish side (build-time tool, never in the APK)

Like `tools/gen_fiction_lut.py`: a script running a small instruction-tuned
GGUF model via `llama.cpp` (candidates to test on real chapters: Qwen2.5-0.5B/1.5B-Instruct,
Llama-3.2-1B-Instruct, SmolLM2). Cache the model between runs. Ship the format
first and judge attribution quality on real output afterward; if poor, default
everything to `narrator` or go to recorded audio, with no client change.

## Segment file: `tts/<arc>/<chapter>.json`

```json
{ "version": 1, "chapter": "arc 1/001.txt",
  "segments": [
    { "text": "The rain hadn't stopped in three days.", "speaker": "narrator" },
    { "text": "\"We need to leave now,\"", "speaker": "elena" } ] }
```

- `chapter` ties it to the exact `.txt`, so a stale annotation is detectable.
- `speaker` is a stable lowercase id, never a display name (same principle as `authorId`).
  It is the join key to the client voice map; a mismatch fails silently, so lock the scheme.
- Segments are ordered literal substrings of the source (quotes included): lossless reconstruction.
- Unknown `version` ⇒ treat as "no TTS for this chapter", never crash.
- Uncertain lines default to `narrator`; no `unknown` state.

## Client scope

- **Sync:** route `tts/` manifest entries like `authors/` is routed, but into the novel's own folder (they are chapter-specific, not shared).
- **Voice map:** a novel-scoped `CharacterVoiceEntity`-style table (`speaker` → installed voice) with a default entry; user-overridable and surviving rescan/resync like `chapter_overrides`. UI belongs on the novel detail screen, not global Settings.
- **Playback:** read the JSON off disk like chapter text, loop segments, `TextToSpeech.speak()`, advance on completion. No model, no concurrency.
- **Missing file:** no "Listen" affordance for that chapter; not an error.

## Known risks

- Android exposes few genuinely distinct voices per engine/language; a big cast will overlap unless voice models are bundled later.
- Small-model attribution accuracy is unproven; web fiction's explicit "X said" tags help.
