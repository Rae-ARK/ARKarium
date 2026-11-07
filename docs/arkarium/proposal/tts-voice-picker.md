# In-app TTS voice and language picker

> **Kind:** proposal · **Status:** steps 1-4 built; remaining work moved to [`../implementation/tts-settings-rework.md`](../implementation/tts-settings-rework.md) · **Verified:** 2026-11-07 @ 8e0a937
> **Builds on:** single-voice system TTS already shipped (see
> [`../foundational/architecture.md`](../foundational/architecture.md),
> Text-to-speech) and `settings/tts`
> (see [`../foundational/design-notes.md`](../foundational/design-notes.md),
> "Settings redesign: outcome").
> **Revisits (does not reject):** the "no in-app picker" half of the
> **TTS settings split** decision in
> [`../foundational/design-notes.md`](../foundational/design-notes.md) — see
> "What this keeps from that decision" below.
>
> Not to be confused with [`tts-multivoice.md`](tts-multivoice.md)'s
> per-character voice mapping for dialogue, which is a much larger, separate
> feature. See "Relationship to multi-voice" below.

## The actual gap

> **Written before steps 1-4 shipped.** The preference key, the service's voice
> list and `setVoice()`, the `ChapterTtsState` pass-through and a voice picker on
> `settings/tts` now exist; the picker is a dialog and the "Change voice" button
> still opens the system settings. What is left, and the defects found in what
> was built, are in the implementation ladder linked above. The paragraphs
> below describe the gap as it was.

`TtsSettingsScreen`'s "Change voice" button does not do what its label
implies. It fires an intent to the *system* TTS settings
(`com.android.settings.TTS_SETTINGS`, falling back to
`Settings.ACTION_ACCESSIBILITY_SETTINGS`) — a phone-wide screen for picking
the default TTS engine and that engine's default language, shared by every
app on the device. Two problems follow from that:

1. **Nothing comes back.** ARKarium never reads anything out of that screen.
   There is no `tts_voice_name` (or similar) preference key, and neither
   `Tts.kt` nor `TtsPlaybackService.kt` ever calls
   `TextToSpeech.setVoice()`, `setLanguage()`, or reads `.getVoices()`. The
   "Change voice" row is a dead-end link, not a control.
2. **The engine is always just the system default.** `TtsPlaybackService`
   constructs its engine as `TextToSpeech(applicationContext) { ... }` and
   from then on only ever calls `setSpeechRate()` and `setPitch()` on it
   (`setInitialRateAndPitch`, `setRate`). Whatever voice and language the
   engine was already defaulting to system-wide is what every chapter speaks
   in, with no override and no per-book or per-session choice — regardless
   of what the person does on the system screen the button opens, unless
   they change the actual system-wide default.

So the behavior the button's label promises — choosing a language or voice
model for ARKarium specifically — was never built. This proposal is that
feature.

## What this keeps from that decision

Design-notes' "TTS settings split" bullet says: *"Engine choice links out to
the system TTS setting; no in-app picker, no `tts_engine_package` key."* That
stands. Switching which **engine** ARKarium uses (Google's, Samsung's, a
third-party one) is still out of scope here — same reasoning as before:
engines are installed/managed at the OS level, and `TextToSpeech`'s
constructor-time engine argument interacts with install state and permission
prompts the app would rather not own.

What's new is narrower: once an engine is selected (by the OS default, same
as today), that engine typically exposes several **voices** —
`TextToSpeech.getVoices()` returns a `Set<Voice>`, each with its own
`Locale`, `quality`, and network-requirement flag. Picking *among those* is
in scope. No `tts_engine_package` key, no engine switcher UI — just a
`tts_voice_name` key and a list drawn from whatever engine is already active.

## Decision: service-owned voice list, not a second `TextToSpeech` instance

`TtsSettingsScreen` today has no connection to `TtsPlaybackService` — it's a
leaf screen wired to `PreferencesManager` callbacks only, per
`design-notes.md`'s "flat preference reads/writes... go straight through
`PreferencesManager`" rule. The naive option would be to spin up a throwaway
`TextToSpeech` instance inside the settings screen just to call
`.getVoices()`, then shut it down.

Rejected: `TtsPlaybackService`'s own doc comment is explicit that it is the
sole owner of the engine, precisely so nothing else races it or duplicates
its init. A second concurrent `TextToSpeech` instance is exactly the kind of
thing that doc comment exists to prevent, and its async init callback could
easily outlive the settings screen's composable.

Chosen instead: bind `TtsSettingsScreen` to `TtsPlaybackService` the same way
`rememberChapterTts()` already does for the reader, and let the *service*
expose the voice list and the setter:

- `TtsPlaybackService.availableVoices: List<Voice>` — populated once from
  `engine?.voices?.toList().orEmpty()` inside the existing `onInit`
  callback, alongside where `isAvailable` is already set.
- `TtsPlaybackService.setVoice(voiceName: String?)` — resolves a `Voice` by
  name out of `availableVoices` and calls `engine?.setVoice(voice)`. A null
  name, or a name no longer present (uninstalled language pack, engine
  switched since it was stored), is a silent no-op that leaves the engine on
  its current voice — the same "safe default, never crash" posture
  `setInitialRateAndPitch`'s one-shot-per-instance guard already uses, and
  the same "unknown ⇒ treat as default, never crash" rule
  `tts-multivoice.md` states for its own segment format.
- `ChapterTtsState` mirrors `availableVoices` and adds a pass-through
  `setVoice()`, the same shape as its existing `setRate()`.
- `setInitialRateAndPitch` grows a third seeded value (voice name) from a
  new preference default, applied once per service instance exactly like
  rate and pitch are today.

## New preference key

`PreferencesManager`:

- `ttsVoiceName: Flow<String?>`, default `null`. `null` means "engine/system
  default" — the same "off/unset means default" posture already used by
  `ttsAutoContinue` and `ttsKeepScreenOn`.
- `setTtsVoiceName(name: String?)`.

Storing `Voice.getName()` (a string the engine can re-resolve through
`getVoices()`) rather than trying to persist a `Locale` plus a separate
quality/gender variant, since the name already encodes locale and variant
(e.g. `"en-us-x-sfg#female_1-local"`) and is exactly the key `setVoice()`
needs.

## UI sketch (`TtsSettingsScreen`)

Add a new row above the existing "Change voice" button — `"Voice"`, showing
the current selection's display name (or `"System default"` when unset) and
a quality/offline badge. Tapping it opens a dialog or bottom sheet listing
`availableVoices`:

- Grouped by `Voice.locale` display name (language first, since on a
  single-engine device most people care "which language" more than "which
  voice model" — matching the "language or voice model" framing of the
  original ask), each group's voices beneath it.
- Each row shows the voice's quality tier (`Voice.quality`:
  `VERY_LOW`..`VERY_HIGH`) and a "requires network" badge when
  `Voice.isNetworkConnectionRequired` is true — relevant here specifically,
  since ARKarium's whole pitch is reading synced fiction, plausibly offline.
- Selecting a row calls `ChapterTtsState.setVoice()` immediately if a
  service is attached (same "push live" pattern as `setRate()`), and always
  writes `ttsVoiceName` so the next session picks it up via
  `setInitialRateAndPitch`.

The existing "Change voice" button stays — it's still useful for installing
additional language packs or tuning an engine's own settings, things this
proposal doesn't replace.

## Known risks / open questions

- **Voice list size.** A single engine/language can expose dozens of voice
  entries (Google's on-device TTS routinely does). Grouping by locale is a
  minimum; a flat list would be unusable.
- **Voices can disappear.** A stored voice name can stop resolving after a
  language pack is removed or the engine updates. `setVoice()`'s silent
  fallback (above) handles the single-session case; the picker's "current
  selection" display should also treat a non-resolving stored name as
  "System default" rather than showing a stale string.
- **Offline readers.** Network-only voices should be clearly marked so
  someone doesn't pick one while on wifi and then lose read-aloud on their
  commute.
- **First real consumer of `Voice.getName()` as data.** This would be the
  first code in the app to read or apply a `Voice` at all. That's directly
  relevant to `tts-multivoice.md`'s "voice map" (`speaker` → installed
  voice): this rung is a natural, much smaller first slice of that proposal,
  not throwaway work — see below.

## Relationship to multi-voice (`tts-multivoice.md`)

That proposal's per-character voice map needs exactly this: a way to resolve
and apply an installed `Voice` by name. This proposal ships that
single-voice slice first (one voice for the whole book, chosen by the
reader) with none of multi-voice's publish-time attribution pipeline. If
multi-voice is ever built, its "default entry" in the per-novel voice map is
this proposal's `ttsVoiceName` value, reused rather than redesigned.

## Suggested path

Small enough to be its own implementation ladder, same shape as the now-done
`settings-tts` ladder (see design-notes.md):

1. `PreferencesManager`: `ttsVoiceName` key, flow, setter.
2. `TtsPlaybackService`: `availableVoices`, `setVoice()`, seed-once wiring
   alongside rate/pitch in `setInitialRateAndPitch`.
3. `Tts.kt`: mirror `availableVoices` on `ChapterTtsState`, pass-through
   `setVoice()`.
4. `TtsSettingsScreen.kt`: voice row + picker UI, grouped by locale, quality
   and network badges.
5. A test for this preference's round-trip, same shape as
   `PreferencesManagerTest`'s coverage of the other four `tts_*` keys
   (a pure `ttsVoiceNameOf(prefs: Preferences)`-style function, not a
   Robolectric/instrumented test).
