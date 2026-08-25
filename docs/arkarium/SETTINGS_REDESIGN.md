# Settings redesign - one page per option, plus a TTS settings page

> **Status:** Design doc only. **Stage 0** of the plan below (see "Staged
> rollout") - no `app/src` changes in this patch. Written before touching
> code on purpose, same reason `REFACTOR_PLAN.md` stages each Phase as its
> own reviewable step: the page split and the TTS/pill boundary are both
> judgment calls worth settling on paper first, since they decide what
> Stage 2+ actually builds.

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

### New `PreferencesManager` keys (added in Stage 3, not this patch)

| Key | Type | Default | Notes |
|---|---|---|---|
| `tts_default_rate` | `floatPreferencesKey` | `1.0f` | Seeds `ChapterTtsState.speechRate`; pill still overrides per-session. |
| `tts_pitch` | `floatPreferencesKey` | `1.0f` | Applied once at engine init. |
| `tts_engine_package` | `stringPreferencesKey` | `null` = system default | Only added if the on-page engine picker (vs. link-out) is the direction taken - see open questions. |
| `tts_auto_continue` | `booleanPreferencesKey` | `false` | Advances to next chapter on last-chunk completion. |
| `tts_keep_screen_on` | `booleanPreferencesKey` | `false` | Ties to `FLAG_KEEP_SCREEN_ON` while `tts.isSpeaking`. |

`datastore-preferences:1.0.0` (already a dependency, see
`app/build.gradle.kts`) has `floatPreferencesKey` available, so no new
dependency is needed for the rate/pitch keys.

## Staged rollout

Numbered independently from `REFACTOR_PLAN.md`'s own Phase/Stage
sequence - this is a separate, narrower plan (Settings only), not a
continuation of that one.

- **Stage 0 - this doc.** Design only. No `app/src` changes.
- **Stage 1 - navigation scaffolding.** Add the four
  `composable("settings/...")` routes to `MainActivity`'s `NavHost` and the
  four new screen files, initially as thin wrappers that just render the
  moved control code (see Stage 2). Turn `SettingsScreen` into the index/menu
  described in §1, four new `LegalRow` entries alongside the existing three.
- **Stage 2 - mechanical extraction, no behavior change.** Move
  Theme/Library/Splash's existing control code out of `SettingsScreen.kt`
  into `ThemeSettingsScreen.kt`/`LibrarySettingsScreen.kt`/
  `SplashSettingsScreen.kt` verbatim; wire the same callbacks
  `MainActivity` already passes today one level deeper. `SettingsViewModel`
  and `PreferencesManager` untouched.
- **Stage 3 - the new TTS settings page.** Add the `PreferencesManager` keys
  above, build `TtsSettingsScreen.kt`, decide + implement the engine-picker
  question (own control vs. link-out), wire `rememberChapterTts()` to read
  the new defaults, implement auto-continue and keep-screen-on.
- **Stage 4 - tests + doc index.** Extend `SettingsViewModelTest.kt`
  (or add a `TtsSettingsViewModelTest.kt`, depending on whether TTS
  defaults live on `SettingsViewModel` or a new ViewModel - open question
  below) covering the new preference reads/writes. Add this file to
  `docs/README.md`'s `arkarium/` index list.

## Open questions

- **Engine picker: own UI or link out?** Building a picker means querying
  `TextToSpeech.getEngines()` and handling "engine changed mid-session"
  (does the current `ChapterTtsState.engine` need tearing down and
  recreating?). Linking to the system picker is far less code but leaves
  the setting one tap further away and outside the app's own theme. Needs
  a call before Stage 3 starts, since it decides whether the
  `tts_engine_package` key above is needed at all.
- **Does TTS-default state belong on `SettingsViewModel` or a new
  `TtsSettingsViewModel`?** `SettingsViewModel` (Stage 2.2 of
  `REFACTOR_PLAN.md`) currently wraps exactly the `SettingsPreferences`
  interface slice (theme, folder, library URI) - splash and the proposed
  TTS keys are both already outside that interface and read straight off
  `PreferencesManager`, same as `splashAnimationEnabled`/
  `splashMusicEnabled` today. Given `REFACTOR_PLAN.md`'s stated preference
  for splitting ViewModels by feature area rather than growing one, a
  separate `TtsSettingsViewModel` (or just direct `PreferencesManager`
  access from `TtsSettingsScreen`'s call site, same as splash today) both
  seem more consistent with the existing pattern than folding TTS into
  `SettingsViewModel`.
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
