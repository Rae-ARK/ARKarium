# ARKarium comment categorization — Stage 1

Scope: every comment (`//` and `/* */`) in `app/src` (109 Kotlin files,
excluding `/** */` KDoc, which `docs/foundational/commenting.md` already
treats as a separate, in-scope-for-a-different-rule category).

**8,630 comment lines total.** Heuristic, keyword-based first pass — built
to be checked by a human, not trusted outright. Full line-by-line result:
`comment_catalog.csv` (sent alongside this report). Methodology at the
bottom.

## Totals

| Category | Lines | % | Meaning |
|---|---:|---:|---|
| **PURGE** | 212 | 2.5% | Pure process/changelog noise. Safe to delete outright. |
| **MIXED** | 4,121 | 47.8% | A changelog/provenance clause **wrapped around** a real fact. Needs surgical editing, not deletion. |
| **KEEP** | 4,195 | 48.6% | Genuine invariant/contract/"why". Keep; candidate to absorb into names/types where possible, otherwise stays as prose (verbose is fine). |
| **LABEL** | 68 | 0.8% | Short, information-free section label. Absorb by naming (extract a function/val), comment disappears. |
| **KDOC** | 34 | 0.4% | Public API doc comment. Out of scope here. |

## The actual finding

The original audit (sync/manifest/scanner cluster, 34% comment density)
undersold the problem by assuming "log" and "keep" were separable per
comment. They mostly aren't. **The single biggest bucket (47.8%) is neither
pure noise nor pure signal — it's both, braided together in the same
comment.** Three real examples, all previously flagged as pure changelog
narrative:

> **`Dao.kt:264`** — *"Bug 2 follow-up: the original fix (`ORDER BY
> sort_tier, number, title`, no arc grouping) sorted sort_tier **globally
> across the whole novel** rather than [within each arc] ..."*

Strip "Bug 2 follow-up" and the play-by-play of what the old, wrong query
looked like, and what's left is a real, permanent invariant: **chapter
ordering must group by arc before sort_tier.** That fact needs to survive;
the bug narrative around it doesn't.

> **`SyncManager.kt:262`** — *"Pre-existing gap fixed here: this branch used
> to return a bare `SyncOutcome` with none of status/announcements/... set
> ..."*

Same shape: the "used to be broken" framing is disposable, but the
sentence is the only place that currently states **which fields this
branch is responsible for populating** — that's a contract, not history.

> **`CatalogueStorage.kt:53`** — *"For Rung 4's C2 hook (a followed
> fiction's cache must not linger), but harmless and idempotent on its own
> ..."*

The "Rung 4's C2 hook" pointer references an implementation ladder that
`docs/README.md`'s own lifecycle rule deletes once the ladder graduates —
so that half will go stale by design. The idempotency guarantee ("harmless
and idempotent on its own") won't, and isn't written down anywhere else.

So Stage 2 per `MIXED` comment isn't "delete" or "leave it" — it's: pull
the fact out, drop the narrative, and decide whether the fact becomes a
name/type or stays as a short "why" comment next to the code it governs.

## PURGE (212 lines) — safe to delete outright

Short, no durable fact attached — pure "what changed and when." Heaviest
in: `AppNavGraph.kt` (34), `TtsPlaybackService.kt` (24), `Tts.kt` (12),
`LibraryViewModel.kt` (12), `PreferencesManager.kt` (10).

```
Dao.kt:81        Backs the Stage 2 author page's fiction list.
ChapterContentRepository.kt:37  ...used to render as [stale narrative]
MainActivity.kt:290  Finishes the extraction: owns the reader route's...
```
(Note: a few of these are borderline — the heuristic trims anything short
that merely cites a ladder/stage/phase, even where the rest of the
sentence has a little remaining value. Worth a quick human skim before a
blanket delete; flagged `PURGE` in the CSV either way for that skim.)

## MIXED (4,121 lines) — the real stage-2 work

Ranked by volume — these files carry nearly all of it:

| File | MIXED lines |
|---|---:|
| `ui/reader/ReaderScreen.kt` | 469 |
| `playback/TtsPlaybackService.kt` | 303 |
| `MainActivity.kt` | 402 |
| `viewmodel/SyncViewModel.kt` | 246 |
| `navigation/AppNavGraph.kt` | 175 |
| `ui/reader/ReaderContent.kt` | 176 |
| `data/ScannerImpl.kt` | 182 |
| `viewmodel/LibraryViewModel.kt` | 160 |
| `data/sync/SyncManager.kt` | 134 |
| `data/PreferencesManager.kt` | 121 |

This mostly confirms the original hunch (sync/scanner/reader/TTS — the
heavily-patched, order-dependent subsystems carry the worst tangles) but
shows it's not confined there: `MainActivity.kt`, `AppNavGraph.kt` and
`ReaderScreen.kt` (all UI/navigation, not sync) carry just as much, because
they're the files the `MainActivity` extraction ladder moved code *out of*
— every extraction left a "this used to live here, here's why it's
structured this way now" comment behind.

## KEEP (4,195 lines) — already doing real work

Heaviest in `Entities.kt` (261), `MainActivity.kt` (266),
`AppNavGraph.kt` (162), `NovelDetailScreen.kt` (171), `ScannerImpl.kt`
(168), `PreferencesManager.kt` (160). These explain invariants, contracts,
and cross-references (`see X.kt`, `docs/foundational/architecture.md`,
specific "must/never/always" rules) with no changelog framing. Content is
sound; Stage 2 here is really Stage 3 of `commenting.md`'s own ladder —
see if any of these can additionally collapse into a name or a type, and
leave the rest as prose (verbose is fine, per your call).

## LABEL (68 lines) — low priority, mechanical

Pure section markers with nothing to lose by deleting the comment and
naming the thing instead:

```
AppDatabase.kt:76   Create arcs table
AppDatabase.kt:88   Create chapter_overrides table
Dao.kt:344          Backs the "Continue Reading" row: most recently read novels first.
GuitarChordSynth.kt:39  Generates one plucked string's waveform via Karplus-Strong.
```
Lowest-value bucket to spend Stage 2 effort on relative to its size (0.8%
of all comment lines) — mentioned for completeness, not urgency.

## Methodology (so Stage 2 can trust or override it)

1. Walked every `.kt` file under `app/src`, pulled out every `//` run and
   `/* */` / `/** */` block as one comment unit.
2. `/** */` → `KDOC`, out of scope.
3. Else, keyword-matched against two regex sets: a **provenance** set
   (bug/fix/followup/used to/pre-existing/legacy/rung N/stage N/phase
   N/moved from/TODO/FIXME/...) and a **substance** set
   (must/never/always/because/invariant/thread-safe/see
   X/deliberately/rather than/.../`https://`/...).
4. Provenance hit + long (>200 chars) + also a substance hit → `MIXED`.
   Provenance hit, otherwise → `PURGE`. No provenance hit, short (≤2
   lines, ≤70 chars) and no substance hit → `LABEL`. Everything else →
   `KEEP`.
5. This is pattern-matching, not comprehension — it will mis-sort some
   individual comments (a few examples above are noted as borderline).
   Treat every row in `comment_catalog.csv` as a starting guess for
   Stage 2's actual edit, not a final verdict.

## Suggested Stage 2 shape (not started — confirm before I do this)

Given the size (4,100+ `MIXED` lines across ~80 files), I'd suggest
tackling it in the same tiers `docs/foundational/design-notes.md` already
uses for code changes — smallest, lowest-risk first, each independently
shippable:

1. **`PURGE`** — mechanical, low-risk: delete the 212 pure-noise lines,
   file by file, nothing else touched.
2. **One `MIXED`-heavy file at a time**, biggest first (`ReaderScreen.kt`,
   `TtsPlaybackService.kt`, `MainActivity.kt`, ...): for each comment,
   extract the durable fact, drop the changelog framing, and where the
   fact is a short invariant, turn it into a name/type per
   `commenting.md` rather than a trimmed-down comment.
3. **`LABEL`** — batch, mechanical, any time (low value, low risk).
4. **`KEEP`** — opportunistic naming/type absorption, no urgency.

Let me know if you want to start on (1) now, or go straight at a specific
file in (2).
