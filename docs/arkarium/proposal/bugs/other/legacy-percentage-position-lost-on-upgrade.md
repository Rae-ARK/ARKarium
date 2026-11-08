# Upgrading from a pre-rung-14 build silently resets in-progress chapters to the top

> **Kind:** proposal · **Status:** open · **Verified:** 2026-11-08 @ 5cced1a
> **Origin:** code read of `ReaderScreen.kt` against `ReadingProgressEntity`/`ReaderPosition.kt`, prompted by an external review of the F9 (reading-position anchor) work.

**Seen:** not yet reported by a user; found reading code. **Tags:** V (the
comment and the restore path were both read directly).
**Where:** `ui/ReaderScreen.kt` (the `initialPosition` doc comment and the
restore effect); `data/ReaderPosition.kt`; `data/Entities.kt`
`ReadingProgressEntity.positionType`.

**What the code does:** before backlog rung 14 (F9), a saved reading
position was a `Float` scroll fraction with `positionType = "PERCENTAGE"`.
Rung 14 replaced this with a character offset snapped to a paragraph start,
`positionType = "CHAR_OFFSET"`. `ReaderScreen`'s own doc comment on
`initialPosition` says this screen **no longer restores into** a row whose
`positionType` is still `PERCENTAGE` — it only resolves `ReaderPosition` from
`CHAR_OFFSET` rows.

**Why it matters:** anyone who was mid-chapter in a build before rung 14 and
updates to one after it will have every such chapter reopen at the top the
first time, with no migration bridging the two representations. The DB
migration (`MIGRATION_13_14`) only adds the new `position_snippet` column; it
does not touch existing `PERCENTAGE` rows or convert them.

**Possible fix:** on restore, when the row's `positionType` is still
`PERCENTAGE`, convert it once: `fraction × raw chapter text length` gives an
approximate character offset, which `paragraphForCharOffset` (already used
for `CHAR_OFFSET` rows) can snap to a paragraph start the same way. Write the
converted `CHAR_OFFSET` row back so the one-time conversion doesn't repeat on
every open.

**Severity:** Low–Medium — no data loss (the row itself isn't deleted,
reading streak and overrides are untouched), but it is a visible regression
for any existing reader who updates mid-chapter, and it is silent: nothing
tells the person their place was lost.
