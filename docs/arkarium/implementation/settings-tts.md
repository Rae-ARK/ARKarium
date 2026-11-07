# Settings redesign + TTS settings page

> **Kind:** implementation (ladder) · **Status:** 1 rung left · **Verified:** 2026-11-06 @ b4fcc2f
> **Truth lives in:** `ui/Settings*.kt`, `ui/TtsSettingsScreen.kt`, `ui/Tts.kt`, `data/PreferencesManager.kt`

Goal: Settings becomes an index with one page per option, plus a new
Text-to-Speech page so TTS defaults persist instead of resetting every chapter.
Design decisions are already recorded in
[`../foundational/design-notes.md`](../foundational/design-notes.md).

## Ladder

| Rung | State | What |
|---|---|---|
| 0 | done | Design doc. |
| 1 | done | `settings/*` routes and screen files; `SettingsScreen` becomes the index. |
| 2 | done | Theme/Library/Splash controls moved verbatim into their own screens. |
| 3.1 | done | `PreferencesManager` keys: `tts_default_rate`, `tts_pitch`, `tts_auto_continue`, `tts_keep_screen_on`. |
| 3.2 | done | `TtsSettingsScreen.kt` controls + "Change voice" link-out. |
| 3.3 | done | `rememberChapterTts()` seeds rate and pitch from the defaults. |
| 3.4 | done | Auto-continue: `onChapterFinished` fires `ReaderScreen`'s `onNext` (progress `1f`). |
| 3.5 | done | Keep screen on while `tts.isSpeaking`. |
| **4** | **current** | Tests for the four `tts_*` preference reads/writes. |

## Rung 4: remaining work

`PreferencesManager` takes a real `Context` and there is no Robolectric or
instrumented test infra, so these keys (like the splash keys) have no tests.
Pick one:

- Add a small `SettingsPreferences`-style seam for the TTS keys and test with a fake, or
- Adopt an instrumented/Robolectric test for `PreferencesManager`.

**Done when:** defaults (`1.0f`, `1.0f`, `false`, `false`) and a write/read
round-trip are covered by a test, and this file moves to `foundational/` as a
two-line outcome (already summarized in `design-notes.md`) or is deleted.

## On completion

Delete this file, update `../README.md`, and repoint the source comments that
cite it (`grep -rn "implementation/settings-tts.md" app/`).
