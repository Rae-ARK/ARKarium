# TTS settings: engine fixes and the voice page

> **Kind:** implementation (ladder) · **Status:** rung 0 done, rung 1 current · **Verified:** 2026-11-07 @ 8e0a937
> **Truth lives in:** `playback/TtsPlaybackService.kt`, `ui/Tts.kt`, `ui/TtsSettingsScreen.kt`, `MainActivity.kt` (the `settings/tts` route), `data/PreferencesManager.kt`
> **Origin:** [`../proposal/tts-voice-picker.md`](../proposal/tts-voice-picker.md), whose steps 1-4 are built, plus a read-through of `settings/tts` against five screenshots of a reference reader app's TTS settings (supplied 2026-11-07).

The voice picker shipped as a single `AlertDialog` on `settings/tts`. Reading
that screen and the code behind it next to the reference screenshots turned up
two kinds of problem: defects in how a chosen voice reaches (and leaves) the
engine, and a picker that gets unwieldy on a device with many voices. This
ladder fixes the first kind first, because a good-looking picker on top of a
voice that doesn't reliably apply is the wrong order.

Evidence tags: **V** = read in the code this pass, **I** = inferred from
platform behavior, not reproduced on a device. Nothing below has been run.

## What the screenshots show

The reference device has English (India), English (US), Japanese and Tamil
voices installed, so the lists are long.

| Screenshot | Shows | ARKarium today |
|---|---|---|
| TTS settings | A flat list: Voice (current value), Speech rate, Timer, Multilingual mode (switch). | Cards with sliders for rate and pitch, two switches, a Voice card, and a "Change voice" button. No timer, no multilingual mode (both go to [`ideas.md`](../proposal/ideas.md), not this ladder). |
| Voice page | A full page: a "Language - English" filter line, then one row per voice with its engine name, a description such as "Google online, India", a favorite star and a radio button. | An `AlertDialog` capped at 420dp, grouped by locale display name, rows with the raw name and a quality label. No favorites, no radio. |
| Language dialog | The device's languages first (English, Japanese, Tamil), then "Another language". | None. |
| Specify language | A full alphabetical list of languages the engine supports. | None. |
| Reader toggles | Six reader switches. Not TTS: see [`../proposal/reader-settings.md`](../proposal/reader-settings.md). | n/a |

The reference lists engine names like `en-in-x-ene-network` and
`en-in-x-enc-local` too, so raw names aren't the problem. The problem is that
the same voice appears in a `-network` and a `-local` variant and nothing in
ARKarium's row says which is which clearly (U1).

## Findings

### Engine side

**D1. The voice list is a snapshot taken too early. V (code), I (timing).**
`ChapterTtsState.attach()` and the seed coroutine in `rememberChapterTts()`
each copy `svc.availableVoices` once. The service fills that list inside the
`TextToSpeech` init callback, and `onAvailabilityChanged` only updates
`isAvailable`; it never re-reads the voices. `settings/tts` binds without
starting the service, so unless read-aloud is already running the service is
created fresh when the page opens and the engine is still initializing. If
init finishes after the copy, the picker opens with only "System default" and
stays that way until the page is reopened.

**D2. The saved voice and pitch can be dropped at startup. V (code), I
(engine behavior).** `setInitialRateAndPitch` sets `defaultsApplied = true`
first, then calls `setSpeechRate`, `setPitch` and `setVoice`. If the engine
hasn't finished init, the setters fail (they return an error code rather than
queueing) and `setVoice` can't resolve the saved name because
`availableVoices` is still empty, so it returns silently. The guard then stops
it ever running again for that service instance. Rate heals itself because
`speakNextChunk` calls `setSpeechRate` before every chunk. Pitch is set in
exactly one place (`TtsPlaybackService.kt`, that one call) and the voice in
exactly one place, so neither ever recovers.

**D3. Choosing "System default" does nothing live. V.** The row calls
`onVoiceChange(null)`, which calls `ChapterTtsState.setVoice(null)`, which
reaches `TtsPlaybackService.setVoice(null)`, which returns immediately on a
null name. The preference is saved as null but the engine stays on the old
voice, and `currentVoiceName` stays on the old name. `MainActivity` passes
`ttsState.currentVoiceName ?: ttsVoiceName.value`, so the old name wins and
the dialog and the Voice card both keep showing the voice you just left until
the page is reopened with a fresh service.

**D4. An engine error is treated as "chunk finished". V.** Both `onError`
overloads in the service call `onUtteranceFinished`, which advances to the
next chunk. If a chunk fails (an online voice with no connection, a voice
whose data isn't installed, a synthesis error), every following chunk fails
the same way within moments, the chapter "completes" without a sound, and
with auto-continue on the next chapter does the same. Online and local
variants of the same voice are listed side by side, so picking an online one
and then losing signal is an ordinary path to this.

**D5. Voices that aren't installed are listed as ordinary choices. V
(picker), I (device).** `getVoices()` can include voices whose data isn't
downloaded; their `Voice.getFeatures()` set contains
`TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED`. The picker neither filters
nor marks them, so selecting one leads straight to D4.

### Screen side

**U1. The online badge uses the wrong icon. V.** `VoicePickerRow` draws
`Icons.Filled.CloudOff` with the description "Requires a network connection".
That glyph reads as "offline". `voiceSummary` also writes "offline" for local
voices but says nothing for online ones, so the two cases aren't stated the
same way.

**U2. One language is split across groups. V.** Rows are grouped by
`locale.displayName`, so "English (India)" and "English (United States)" are
separate headings in a single 420dp-tall dialog. In the reference screenshot
the first nine voices are all English (India) with the US ones after them,
which is the shape of list this grouping handles badly. The reference filters
by language and puts the region in each row's description.

**U3. "Change voice" is now misleading. V.** The Voice card directly above it
opens the in-app picker, while the "Change voice" button below it opens the
*system* TTS settings. It is still worth having (installing language packs,
engine settings) but it no longer changes ARKarium's voice.

**U4. A stale doc claim. V.** [`../proposal/tts-voice-picker.md`](../proposal/tts-voice-picker.md)
still said "design only" and described the "Change voice" button as a
dead end, and `design-notes.md` still said "no in-app picker". Both are
corrected in the same commit as this file.

## Ladder

| Rung | State | What |
|---|---|---|
| 0 | done | This doc. |
| **1** | **current** | Init order and "System default" (D1, D2, D3). No UI change. |
| 2 | next | Stop on engine error and say why (D4, D5's runtime half). |
| 3 | queued | Voice list model: pure, unit-tested functions that turn `List<Voice>` into language options and rows. |
| 4 | queued | Voice page: a full `settings/tts/voice` route replacing the dialog (U1, U2, U3). |
| 5 | queued | Favorites: a star per voice, favorites listed first. |

### Rung 1: init order and "System default"

The service stops treating "engine not ready yet" as a case that can lose
data. The seed values (rate, pitch, voice name) are kept and applied when init
completes, or immediately if it already has; `defaultsApplied` only flips once
they have actually been applied. The service reports "voices are ready" to its
listener, and `ChapterTtsState` mirrors the new list, so a picker opened early
fills in on its own. `setVoice(null)` applies the engine's own default voice
(`TextToSpeech.getDefaultVoice()`) and sets `currentVoiceName` to null.

Pure helper worth extracting so it can be unit-tested without a `Voice`
object (which the plain-JVM test setup, with no Robolectric, probably can't construct; I): a function from (stored
name, list of available names) to the name that should be applied, or null.

**Done when:** a saved voice and pitch are applied whichever of "service
bound" and "engine initialized" happens first; opening `settings/tts` on a
cold service ends with the voice list filled; choosing "System default" after
a custom voice changes what the next utterance sounds like and what the card
shows.

### Rung 2: stop on engine error

`onError(utteranceId, errorCode)` stops the session instead of advancing, and
the service reports the code to its listener. The reader shows a short message
(for the network codes, one that suggests a local voice), and auto-continue
does not carry on past a failed chapter. Use the `TextToSpeech.ERROR_*`
constants by name. Whether to retry once before stopping is an open question
below.

**Done when:** with an online voice selected and no connection, starting
read-aloud stops with a message instead of finishing silently.

### Rung 3: voice list model

Pure functions over a small data class (name, language tag, region, quality,
online, installed), not over `android.speech.tts.Voice` itself, so they run in
plain JVM tests like `SplitForTtsTest`:

- the languages present in the list, the device's language first;
- the rows for one language, sorted by region, then local before online;
- a one-line description such as "India · online · high quality";
- installed versus not-installed, so rung 4 can mark or hide the latter.

**Done when:** the three behaviors above are covered by a `*Test` file and the
dialog's grouping code is replaced by calls to these functions.

### Rung 4: voice page

A new route `settings/tts/voice`. A "Language - English" line at the top opens
a dialog of the languages from rung 3. Rows show name, description and a
radio button; the online badge uses a cloud icon (not `CloudOff`) and the word
"online" is stated the same way "offline" is. The Voice card on `settings/tts`
navigates there. "Change voice" is renamed "System speech settings".

Not copied from the reference: its "Specify language" list of every language
the engine supports. ARKarium can only offer what `getVoices()` returns, so a
language with no voice would be an empty page.

**Done when:** the `AlertDialog` and `VoicePickerDialog` are gone and a device
with many voices shows one language at a time.

### Rung 5: favorites

A `tts_favorite_voices` string-set key in `PreferencesManager` with a pure
`...Of(prefs)` reader and a round-trip test like the other `tts_*` keys. A
star on each row toggles membership; favorites sort first within a language.

## Open questions for the owner

- Should rung 2 retry a failed chunk once before stopping? A single dropped
  request on a weak connection would otherwise end a chapter.
- Should the voice page have a "play a sample" button? The reference
  screenshots don't show one, so it isn't in the ladder.
- Is "System default" kept as the first row of the voice page, or folded into
  the language filter? It has no language of its own.

## Not in this ladder

- **Engine choice** stays a link out to the system, as `design-notes.md` says.
- **Sleep timer** and **multilingual mode** (rows in the reference's TTS
  settings) are in [`../proposal/ideas.md`](../proposal/ideas.md).
- **Reader toggles** from the last screenshot are in
  [`../proposal/reader-settings.md`](../proposal/reader-settings.md).

## On completion

Delete this file. Move the decision "the voice is chosen in-app, applied after
engine init, and an engine error stops the session" into
[`../foundational/design-notes.md`](../foundational/design-notes.md), and
delete [`../proposal/tts-voice-picker.md`](../proposal/tts-voice-picker.md).
