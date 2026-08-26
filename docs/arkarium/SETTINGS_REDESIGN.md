# Settings redesign - one page per option, plus a TTS settings page

> **Status:** Stages 0-2 done (index page, Theme/Library/Splash extracted
> verbatim). **Stage 3** (the new TTS settings page) is in progress and is
> itself split into sub-stages 3.1-3.5 below, same reason `REFACTOR_PLAN.md`
> splits its own Phase 3 into 3.1-3.5 rather than one big navigation swap:
> smallest/lowest-risk slice first, each one independently reviewable.
> Stage 3.0 (docs only), **Stage 3.1** (the four `PreferencesManager` keys,
> no UI yet), **Stage 3.2** (`TtsSettingsScreen.kt`'s controls + the
> link-out engine row), **Stage 3.3** (wiring `rememberChapterTts()` to
> the new rate/pitch defaults), **Stage 3.4** (auto-continue), and
> **Stage 3.5** (keep screen on) are done - all of Stage 3 is now complete.
> This patch is **Stage 3.5**.

## Current state

`SettingsScreen.kt` is one flat, scrolling `Column` with five sections
stacked in it: Theme (radio group + a conditional System Default
sub-choice), Library (custom-folder switch + Select/Change Folder +
Rescan), Splash Screen (two switches), Legal, and About Me/version. Three
of those five items - Privacy Policy, Terms & Conditions, About Me -
already navigate to their own destination via the `LegalRow` composable
and `MainActivity`'s `"privacy_policy"` / `"terms"` / `"about_me"` routes.
The other three (Theme, Library, Splash Screen) render their controls
inline on the same screen instead of following that same pattern, so the
screen is inconsistent about which settings get a page and which don't
today, not just monolithic.

Separately, `ReaderScreen.kt`'s "Reader Preferences" pill
(`showPreferences` panel) holds five controls: font size, line spacing,
read-aloud speed, font family, and reading mode (Light/Sepia/Dark). All
five share one trait that justifies living in the pill instead of
Settings: they're things a reader plausibly adjusts *while looking at the
page*, mid-chapter, and expects to take effect immediately without
leaving the reader. Speed fits that description today only because it's
the *only* TTS control that exists - `ChapterTtsState` (`Tts.kt`) exposes
nothing else. It has no engine selection, no pitch, no auto-continue, and
critically **no persistence**: `speechRate` is a plain
`mutableFloatStateOf(1.0f)` on `ChapterTtsState`, which `rememberChapterTts()`
recreates from scratch every time `ReaderScreen` enters composition. A
reader who sets 1.5x gets 1.0x again next chapter-open, next app launch,
every time - `PreferencesManager` has no TTS keys at all.

## Design

### 1. Settings becomes an index; every option gets its own page

`SettingsScreen` stops rendering controls itself and becomes a menu of
`LegalRow`-style rows - the pattern already proven by Privacy Policy/Terms/
About Me, just applied uniformly instead of to three items out of six:

```
Settings
├── Theme                  -> settings/theme
├── Library                -> settings/library
├── Splash Screen          -> settings/splash
├── Text-to-Speech         -> settings/tts        (new, see §2)
├── Privacy Policy         -> privacy_policy       (unchanged)
├── Terms & Conditions     -> terms                (unchanged)
└── About Me               -> about_me             (unchanged)
```

Each of the four new destinations gets its own screen file
(`ThemeSettingsScreen.kt`, `LibrarySettingsScreen.kt`,
`SplashSettingsScreen.kt`, `TtsSettingsScreen.kt`) and its own
`composable("settings/...")` entry in `MainActivity`'s `NavHost`, mirroring
`"privacy_policy"`/`"terms"`/`"about_me"`'s existing shape: a `TopAppBar`
with a back arrow, one screen's worth of controls, nothing else on it.
`SettingsViewModel` and `PreferencesManager` are unchanged by this
step - the same `onThemeSelected`/`onUseCustomFolderToggle`/
`onSplashAnimationToggle` callback wiring `MainActivity` already passes
into `SettingsScreen` today just gets passed one level further down, into
whichever sub-screen now owns that control. The version string at the
bottom of today's `SettingsScreen` stays on the index page, not any
sub-page, since it's not really "a setting."

This is a pure move for Theme/Library/Splash: their control code
(the `RadioButton`/`Switch`/`Button` blocks already in `SettingsScreen.kt`)
relocates into the new files essentially unchanged, same rule
`REFACTOR_PLAN.md`'s Phase 1 states for extractions - "behavior-preserving,"
not a rewrite.

### 2. A dedicated Text-to-Speech settings page

This is the one genuinely new page, not a relocation. It holds TTS
controls that are real (or planned as a near-term follow-up, per
`FUTURE_PLANS.md` §2's "client-side scope") but don't belong in the pill,
because they're not the kind of thing a reader adjusts mid-sentence - they're
closer to Theme: set once, rarely revisited, and should hold their value
across sessions rather than reset every time a chapter opens.

**Stays in the pill** (per-session, take-effect-immediately, unchanged by
this doc): speech rate. It's the one TTS control tied to the physical act
of reading right now, same tier as font size/line spacing.

**Moves to `settings/tts`** (global defaults, engine-level, infrequent):

- **Default speech rate.** The actual bug fix buried in this redesign:
  `ChapterTtsState.speechRate` currently has no persisted backing at all
  (see "Current state" above). This page's control writes a
  `TTS_DEFAULT_RATE_KEY` float preference; `rememberChapterTts()` seeds
  `ChapterTtsState`'s initial `speechRate` from it instead of the hardcoded
  `1.0f`. The pill's slider still adjusts the *session's* rate live via
  `ChapterTtsState.setRate()`, same as today - this just changes what it
  starts at, and gives readers a way to change that starting point
  deliberately instead of it always being 1.0x.
- **Pitch.** `android.speech.tts.TextToSpeech.setPitch()` has no caller
  anywhere in `app/src` today. Same treatment as rate: a
  `TTS_PITCH_KEY` float default, applied once at engine init
  (`rememberChapterTts()`'s `TextToSpeech(context) { ... }` callback), no
  pill exposure - unlike rate, there's no read-aloud-speed-style case for
  changing pitch mid-chapter.
- **TTS engine.** `TextToSpeech.getEngines()` lists every engine installed
  on-device; today `rememberChapterTts()` implicitly takes whatever the
  system default resolves to. Worth a row here *if* more than one engine
  is typically installed (open question - see below) - otherwise this page
  just links out to the system's own **Settings > Accessibility >
  Text-to-speech output** picker rather than duplicating Android's own
  engine/voice chooser, matching the doc-comment already in `Tts.kt` about
  deliberately not shipping or managing voices itself.
- **Auto-continue to next chapter.** `ChapterTtsState.onUtteranceFinished`
  currently just drops `isSpeaking` back to `false` once the last chunk
  finishes - read-aloud stops cold at a chapter boundary today. A
  `TTS_AUTO_CONTINUE_KEY` boolean, off by default (silent behavior change
  otherwise), lets `ReaderScreen` fire its existing next-chapter
  navigation automatically instead.
- **Keep screen on while speaking.** A `TTS_KEEP_SCREEN_ON_KEY` boolean
  toggling `FLAG_KEEP_SCREEN_ON` for the duration of `tts.isSpeaking` -
  relevant specifically because TTS is a screen-off-friendly use case
  (listening, not reading) that the rest of the reader isn't.

**Explicitly out of scope for this page:** the multi-voice
character-attribution system `FUTURE_PLANS.md` §2 describes
(`CharacterVoiceEntity`, per-speaker voice mapping, the `tts/*.json`
publish pipeline). None of that exists yet - it depends on manifest/sync
plumbing this page has no reason to wait on. When it lands, per-novel voice
mapping is a `NovelDetail`-scoped concern (it's data about *that novel's
cast*), not a global app setting, so it belongs on a different screen
entirely and isn't part of this redesign.

### New `PreferencesManager` keys (added in Stage 3.1)

| Key | Type | Default | Notes |
|---|---|---|---|
| `tts_default_rate` | `floatPreferencesKey` | `1.0f` | Seeds `ChapterTtsState.speechRate`; pill still overrides per-session. |
| `tts_pitch` | `floatPreferencesKey` | `1.0f` | Applied once at engine init. |
| `tts_auto_continue` | `booleanPreferencesKey` | `false` | Advances to next chapter on last-chunk completion. |
| `tts_keep_screen_on` | `booleanPreferencesKey` | `false` | Ties to `FLAG_KEEP_SCREEN_ON` while `tts.isSpeaking`. |

No `tts_engine_package` key: the engine-picker open question below resolved
to link-out, not an on-page picker, so there's no app-owned engine choice to
persist - the system's own Text-to-speech output setting already persists
whatever engine the reader picks there.

`datastore-preferences:1.0.0` (already a dependency, see
`app/build.gradle.kts`) has `floatPreferencesKey` available, so no new
dependency is needed for the rate/pitch keys.

## Staged rollout

Numbered independently from `REFACTOR_PLAN.md`'s own Phase/Stage
sequence - this is a separate, narrower plan (Settings only), not a
continuation of that one.

- **Stage 0 - done.** This doc. Design only, no `app/src` changes.
- **Stage 1 - done.** Navigation scaffolding: the four
  `composable("settings/...")` routes in `MainActivity`'s `NavHost` and the
  four new screen files, initially as thin wrappers that just render the
  moved control code (see Stage 2). `SettingsScreen` becomes the index/menu
  described in §1, four new `LegalRow` entries alongside the existing three.
- **Stage 2 - done.** Mechanical extraction, no behavior change:
  Theme/Library/Splash's existing control code moved out of
  `SettingsScreen.kt` into `ThemeSettingsScreen.kt`/`LibrarySettingsScreen.kt`/
  `SplashSettingsScreen.kt` verbatim; the same callbacks `MainActivity`
  already passed before Stage 1 now wired one level deeper.
  `SettingsViewModel` and `PreferencesManager` untouched.
- **Stage 3 - the new TTS settings page.** Split into sub-stages, same
  reason `REFACTOR_PLAN.md`'s own Phase 3 is split into 3.1-3.5: ordered
  smallest/lowest-risk first, each one shippable and reviewable on its own
  rather than one patch that touches `PreferencesManager`, a new screen,
  and `Tts.kt`'s engine-init code all at once.
  - **Stage 3.0 - done in this patch.** Docs only: this split, plus
    resolving the engine-picker and ViewModel-ownership open questions that
    were blocking 3.1 from starting (see "Open questions" below). No
    `app/src` changes.
  - **Stage 3.1 - done.** `PreferencesManager` keys, no UI yet. Added the
    four keys in the table above (`tts_default_rate`, `tts_pitch`,
    `tts_auto_continue`, `tts_keep_screen_on`) with their DataStore
    read/write plumbing, same shape as the existing
    `splashAnimationEnabled`/`splashMusicEnabled` keys. Nothing reads them
    yet - `rememberChapterTts()` still hardcodes `1.0f`/no explicit pitch,
    `ChapterTtsState.onUtteranceFinished` still just drops `isSpeaking`.
    Purely additive, so there's nothing for `TtsSettingsScreen.kt` or
    `MainActivity` to wire against yet. No dedicated unit tests added in
    this stage: unlike `theme`/`useCustomFolder`/`libraryUri`, these four
    aren't behind the `SettingsPreferences` interface `SettingsViewModelTest.kt`
    fakes (per the resolved ViewModel-ownership question, they're plain
    `PreferencesManager` reads/writes with no ViewModel in front), and
    `PreferencesManager` itself takes a real `Context` with no Robolectric
    or instrumentation test infra in this project yet to exercise that -
    same reason `splashAnimationEnabled`/`splashMusicEnabled` have no tests
    of their own today. Stage 4 covers this.
  - **Stage 3.2 - done.** `TtsSettingsScreen.kt` controls + a link-out engine
    row. Build the actual controls: a rate slider, a pitch slider, an
    auto-continue switch, a keep-screen-on switch (same `Row` +
    label/description + control shape `SplashSettingsScreen.kt` already
    uses for its two switches), each reading/writing one Stage 3.1 key via
    callbacks `MainActivity`'s `"settings/tts"` composable passes down -
    direct `PreferencesManager` access hoisted at the Activity, same
    pattern splash already uses, per the resolved ViewModel-ownership
    question below (no new `TtsSettingsViewModel`). Plus one more row,
    "Change voice," that opens
    `Settings.ACTION_VOICE_INPUT_SETTINGS`/system Accessibility
    Text-to-speech output rather than any in-app picker, per the resolved
    engine-picker question. This stage is UI-and-persistence only:
    changing these controls updates the stored preference and the screen
    reflects it, but nothing in `Tts.kt` reads that preference yet, so
    reader-facing TTS behavior is still unchanged until Stage 3.3.
  - **Stage 3.3 - done.** Wired `rememberChapterTts()` to the new defaults.
    Reading `tts_default_rate`/`tts_pitch` is a suspend call
    (`Flow.first()`), so engine construction moved from the
    `DisposableEffect` body into a `rememberCoroutineScope()`-launched
    coroutine: it reads both defaults, seeds `ChapterTtsState.speechRate`
    from `tts_default_rate` directly (picked up by the existing
    `speakNextChunk()`/`setRate()` machinery unchanged), then constructs
    the `TextToSpeech(context) { ... }` engine and calls
    `setPitch(tts_pitch)` on it once inside that init callback, replacing
    the hardcoded `1.0f`/implicit default. This is the actual bug fix the
    design doc's "Current state" section flags. The pill's live
    `setRate()` mid-session is untouched - it still only affects that
    session, never writes back to `tts_default_rate`.
  - **Stage 3.4 - done.** Auto-continue. `ChapterTtsState` gained an
    `autoContinueEnabled` flag (kept live via `collectAsState()` on
    `tts_auto_continue` in `rememberChapterTts()`, unlike rate/pitch's
    one-shot `.first()` seed, so a reader flipping the setting mid-session
    doesn't have to leave and reopen the reader for it to take effect) and
    an `onChapterFinished` callback. `ReaderScreen` sets that callback to
    its existing `onNext` on every recomposition, so it always closes over
    whichever `onNext` the screen currently has - the same one
    `Screen.Reader`'s Previous/Next buttons already call against
    `libraryViewModel.chapters`, just invoked automatically instead of by a
    tap, and passing `1f` rather than the live scroll-derived
    `currentProgress()` since a reader who finished a chapter by ear hasn't
    necessarily scrolled to its bottom. `onUtteranceFinished` calls
    `onChapterFinished?.invoke()` once the last chunk finishes, but only
    when `autoContinueEnabled` is true - off by default per the design doc
    (`ttsAutoContinue`'s own `PreferencesManager` default), so this stage
    ships with no behavior change until a reader opts in on `settings/tts`.
  - **Stage 3.5 - done.** Keep screen on. `ReaderScreen` reads
    `tts_keep_screen_on` (same direct-`PreferencesManager`,
    `collectAsState()`-kept-live pattern Stage 3.4 used for
    `tts_auto_continue`) and ties `WindowManager.LayoutParams
    .FLAG_KEEP_SCREEN_ON` on the reader's own `Activity.window` to
    `tts.isSpeaking`, via a `DisposableEffect` keyed on both the
    preference and `isSpeaking` so the flag is added/cleared on every
    transition and guaranteed cleared on final disposal too - no
    read-aloud session can leave the flag set behind it, whether it
    finishes normally, is stopped manually, or the reader screen itself
    is closed mid-chapter. A small `Context.findActivity()` helper
    unwraps `LocalContext.current` down to the `Activity` that owns the
    window, rather than assuming a direct cast holds. Off by default
    (`tts_keep_screen_on`'s own `PreferencesManager` default), so this
    stage ships with no behavior change until a reader opts in on
    `settings/tts`. Smallest of the five sub-stages, as expected - one
    flag, one boolean check - and depended on nothing else in
    Stage 3.1-3.4, so it's the last one shipped. This completes Stage 3.
- **Stage 4 - tests + doc index.** Extend `SettingsViewModelTest.kt`
  (or add a `TtsSettingsViewModelTest.kt` - moot now that the
  ViewModel-ownership question resolved to no new ViewModel; this becomes
  plain `PreferencesManager` read/write tests instead, same shape as
  whatever covers `splashAnimationEnabled` today) covering the Stage 3.1
  preference reads/writes. Add this file to `docs/README.md`'s `arkarium/`
  index list.

## Open questions

- **Engine picker: own UI or link out? Resolved: link out (Stage 3.2).**
  Building a picker means querying `TextToSpeech.getEngines()` and
  handling "engine changed mid-session" (does the current
  `ChapterTtsState.engine` need tearing down and recreating?) for a
  control most readers touch once, if ever - the doc-comment already in
  `Tts.kt` about deliberately not shipping or managing voices itself
  applies just as much to engines. Linking to the system's own
  Settings > Accessibility > Text-to-speech output picker costs one `Intent`
  and no engine-teardown handling, at the price of the setting being one
  tap further away and outside the app's own theme - accepted, same
  tradeoff already made for "Back-stack depth" below. No
  `tts_engine_package` key follows from this (see the keys table above).
- **Does TTS-default state belong on `SettingsViewModel` or a new
  `TtsSettingsViewModel`? Resolved: neither - direct `PreferencesManager`
  access (Stage 3.2), same as splash.** `SettingsViewModel` (Stage 2.2 of
  `REFACTOR_PLAN.md`) wraps exactly the `SettingsPreferences` interface
  slice (theme, folder, library URI); splash was never folded into it
  either; it's read straight off `PreferencesManager` via callbacks
  `MainActivity` hoists and passes down, same shape
  `SplashSettingsScreen.kt` shows today. TTS gets the same treatment
  rather than a new single-screen ViewModel: `REFACTOR_PLAN.md`'s
  preference for splitting ViewModels by feature area is about state with
  real logic on top of the raw preference (theme's system-default
  resolution, library's folder-URI persistence), not a justification for
  a ViewModel wrapping four flat key reads/writes with no logic in
  between - splash already sets that precedent, TTS just follows it.
- **Back-stack depth.** Settings -> Settings/TTS now costs one more
  `popBackStack()` than today's single flat screen. Considered and
  accepted as the right tradeoff for readability/focus per option, same
  as it already was for Privacy Policy/Terms/About - not re-litigated
  here, just flagged so it's a deliberate choice on record rather than an
  unnoticed regression.
- **`tts_default_rate` vs. the pill's live rate - which wins on next app
  launch?** Decided above: the preference seeds the *initial* value only;
  the pill's live `setRate()` during a session never writes back to the
  preference. A reader who bumps the pill to 1.5x for one loud chapter and
  closes the app should not have quietly changed their default. Changing
  the *default* is only possible from the new Settings page.
