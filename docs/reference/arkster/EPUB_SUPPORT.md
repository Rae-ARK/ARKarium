# ARKster — EPUB Support (staged plan)

## Status
**Stage 0 — Documentation (this file).** No app code touched. Establishes
the on-disk/URI contract and the stage sequence below, following the same
"doc first, code later" pattern `AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md` used.

## Motivation
ARKster's scanner (`ScannerImpl`) and reader (`ReaderScreen`) are both built
on one load-bearing assumption: **one novel is a folder, one chapter is a
loose `.txt`/`.md` file inside it**, addressed by a plain, directly-openable
SAF `content://` URI (`ChapterEntity.sourcePath`,
`TextChapterContentRepository.getTextContent`).

A large fraction of the fiction people actually have sitting in a library
folder is `.epub`, not a folder of `.txt` files - and an EPUB is the
opposite shape: **one zip file containing many chapters**, ordered by an OPF
manifest/spine rather than by filename. Today those files are silently
invisible to the scanner (`ScannerImpl` only looks for subfolders and
`.txt`/`.md`/`metadata.json`/`cover.*`/`arcs/`/`authors/` - see
`parseChaptersInFolder` and `findCoverUri`). This plan gives EPUB a real,
first-class path through the same scan → Room → reader pipeline text novels
already use, without regressing anything that pipeline does today.

## Why this is a bigger lift than metadata enrichment
Unlike `metadata.json`/`author.json` (purely additive, optional, read once,
never touch the chapter model), EPUB changes a structural assumption three
different layers currently share:

1. **`ChapterEntity.sourcePath` stops being directly openable.** Today it's
   a SAF URI you hand straight to `contentResolver.openInputStream()`. A
   chapter living inside an EPUB has no such URI on its own - it's a zip
   entry path *within* a file. `sourcePath` needs to become a composite
   address, and `ChapterContentRepository` needs an implementation that
   knows how to resolve it.
2. **Chapter *discovery* itself changes shape.** `parseChaptersInFolder`
   walks a folder's files. For an EPUB, "walking chapters" means opening the
   zip once, parsing `META-INF/container.xml` → the OPF's `<spine>`, and
   producing one `ChapterEntity` per spine item - a fundamentally different
   code path, not a filename-pattern tweak to the existing one.
3. **Chapter *content* is XHTML, not plain text.** `ReaderScreen` renders a
   chapter as one `Text()` composable with a fixed serif style, fed
   pre-decoded plain text from `ChapterContent.Text`. EPUB bodies carry
   inline formatting, footnotes, and images the current model has nowhere
   to put.

None of this needs a new dependency - `java.util.zip` and Android's built-in
`android.util.Xml`/`XmlPullParser` are enough to parse an EPUB by hand - but
it's genuinely new code in the scanner, the content repository, and (for
full fidelity) the reader, not a patch to existing functions.

## What EPUB actually is (for reference)
```
SomeBook.epub  (a zip file)
├── mimetype                      <- must be first entry, stored uncompressed
├── META-INF/
│   └── container.xml             <- points at the OPF's path inside the zip
├── OEBPS/  (or any folder name the author chose)
│   ├── content.opf               <- manifest (every file in the book) + spine (read order)
│   ├── toc.ncx                   <- EPUB2 table of contents (nested, chapter titles)
│   ├── nav.xhtml                 <- EPUB3 nav doc (supersedes toc.ncx where present)
│   ├── cover.jpg                 <- declared as the cover in the OPF manifest/metadata
│   ├── chapter-001.xhtml         <- one spine item = one "chapter" for our purposes
│   ├── chapter-002.xhtml
│   └── images/
│       └── illustration-01.jpg
```
Reading order comes from the OPF's `<spine>` (a list of `idref`s into the
manifest), **not** filename order - `chapter-002.xhtml` is not guaranteed to
sort after `chapter-001.xhtml` by name, and some books number spine files
arbitrarily. This is a meaningful departure from `parseChapter`'s
filename-number heuristic and is the reason EPUB needs its own discovery
path rather than a rule bolted onto the existing one.

## Design decisions

### `sourcePath` becomes a composite URI, one scheme per source type
`ChapterEntity.sourcePath` stays a single `String` column (no schema change
to the shape of the column, only to what it may contain), but for EPUB
chapters it's no longer independently openable. Proposed encoding:
```
epub://<epub-file-saf-uri-percent-encoded>#<spine-index>
```
e.g. `epub://content%3A%2F%2F.../SomeBook.epub#3` for the 4th spine item.
`TextChapterContentRepository` stays untouched and keeps handling plain
`content://` sourcePaths exactly as it does today; a new
`EpubChapterContentRepository` (see below) is the only thing that
understands the `epub://` scheme. `ChapterContentRepository` becomes a
small dispatcher that picks the right implementation by scheme - see Stage
1.

*Why not a separate column instead of overloading `sourcePath`?* A new
nullable `epub_entry_index` column was considered, but it would require
every caller of `sourcePath` (there are several - reader, cover/asset
resolution, debugging) to also thread a second nullable field through, for
a distinction the URI scheme already encodes for free. Overloading the
existing single-URI-string contract keeps every non-EPUB code path
completely unaware EPUB exists, matching how `metadata.json`/`author.json`
were kept invisible to code that doesn't care about them.

### Chapter identity across rescans
Text chapters key off their filename-derived `ChapterEntity.id` today
(exact scheme unchanged by this plan). EPUB spine items have no filename of
their own to hash - the manifest `id` attribute (e.g. `id="chapter-003"`)
is the natural stable key, since it's author-assigned and doesn't move even
if the underlying `.xhtml` filename or spine position does. Chapter id
becomes `<novelId>-epub-<manifest-id>`, mirroring the existing
`<novelId>-<slug>` shape closely enough that `ChapterOverrideEntity`/
`ReadingProgressEntity` (both foreign-keyed on `chapter_id`) need no schema
change.

### `sortTier`, `arcId`, and the bonus/closing marker convention
`parseChapter`'s `~`/`!` filename-prefix convention (`bugs.md` Bug 2) has no
analogue in a filename-less spine item. Fallback: match the *resolved
chapter title* (from the NCX/nav, not a filename) against the same
`LEGACY_BONUS_KEYWORDS`/`LEGACY_CLOSING_KEYWORDS` list `parseChapter`
already falls back to for un-prefixed text files - EPUB chapters simply
never reach the marker branch and always take the keyword-sniffing path.
Nested NCX/nav entries (a "Volume 1" `<navPoint>` containing several chapter
`<navPoint>`s) map onto `ArcEntity` the same way a subfolder does for text
novels - one arc per top-level nav grouping that itself contains children,
in spine order.

### Cover art
`findCoverUri` matches a loose `cover.*` file next to the novel folder.
EPUB covers are binary data embedded in the zip, declared either via the
OPF's `<meta name="cover" content="...">` (EPUB2) or an `<item>` with
`properties="cover-image"` (EPUB3). Neither can be pointed at with a SAF
URI directly. `EpubChapterContentRepository`'s discovery pass extracts the
declared cover image, once, to app-private storage
(`context.filesDir/epub-covers/<novelId>.<ext>`) at scan time, and
`NovelEntity.coverUri` is set to a `file://` URI into that cache - the same
"resolve once, store a stable URI" pattern `findCoverUri` already
establishes for loose cover files, just with a cache step in front of it
since the source bytes aren't independently addressable.

### Rendering: plain-text-strip for v1, HTML fidelity out of scope
Two options were weighed for chapter body rendering:
- **Strip XHTML to plain text** (drop tags, keep paragraph breaks) - cheap,
  chapter content still flows through the exact same `ChapterContent.Text`
  → `Text()` composable path text novels use today, so font size / line
  spacing / reading-mode (Light/Sepia/Dark) controls need zero changes.
  Loses italics, bold, footnotes, and inline images.
- **Real HTML rendering** - either a `WebView` fallback specifically for
  EPUB chapters (loses the Compose-native font/spacing/reading-mode
  controls entirely for those chapters, and reintroduces them as a second,
  parallel control surface) or a hand-rolled HTML→`AnnotatedString`
  subset renderer (keeps the existing reader UI, but is a materially larger
  and separate piece of work - inline images alone need zip extraction,
  caching, and a Compose-side async image loader wired into text layout).

**Decision: v1 ships plain-text-strip.** It's the only option that reuses
`ReaderScreen` unmodified and keeps this plan's Stage 3 to "feed it
different bytes" rather than "redesign the renderer." Real HTML/image
fidelity is explicitly deferred - see "Explicitly out of scope."

### `ChapterContent` gains a case, not a rewrite
```kotlin
sealed class ChapterContent {
    data class Text(val body: String) : ChapterContent()
}
```
stays exactly as-is for v1 - `EpubChapterContentRepository.getTextContent`
returns the same `ChapterContent.Text`, just populated from a
tag-stripped XHTML spine entry instead of a raw `.txt` file. This is
deliberate: `ReaderScreen` never needs to learn about EPUB at all in v1,
because by the time content reaches it, it's the same shape it always was.
(A future `ChapterContent.Html` case for the HTML-fidelity work would be
additive whenever that's tackled - not something this plan needs to design
now.)

### Reading progress
`ReadingProgressEntity.positionType` already lists `EPUB_CFI` as one of its
documented-but-unused values (see the comment above the entity in
`Entities.kt`) - evidence this was anticipated, not a new concept. v1 does
**not** need it: since chapter body is flattened to plain text same as
`.txt` chapters, `PERCENTAGE`/`CHAR_OFFSET` positioning works unchanged.
`EPUB_CFI` (a location *within* the original XHTML) only becomes relevant
if/when the HTML-fidelity renderer happens, since only then does a
position need to survive a re-render of real markup rather than a flat
string.

## Staging order

### Stage 0 — Documentation (this change)
This file. No app code touched.

### Stage 1 — Zip/OPF/NCX parsing + data layer
- New `EpubParser` (or similar) under `data/`: given a SAF URI for an
  `.epub` file, opens it with `java.util.zip.ZipInputStream` /
  `ZipFile`-equivalent over a `ContentResolver` stream, locates
  `META-INF/container.xml`, resolves the OPF path, parses the OPF
  (`android.util.Xml.newPullParser()`) for manifest + spine + declared
  cover + title/author/description metadata, then parses whichever of
  `toc.ncx`/`nav.xhtml` is present for chapter titles and nesting.
- `ChapterContentRepository` becomes scheme-dispatching (see "composite
  URI" above); `EpubChapterContentRepository` added alongside the existing
  `TextChapterContentRepository`, implementing tag-stripping for chapter
  bodies.
- `ScannerImpl` gains an EPUB discovery path: when a novel folder (or the
  scan root itself, per open question 1 below) contains a `.epub` file,
  route it through `EpubParser` instead of `parseChaptersInFolder`,
  producing `ChapterEntity`/`ArcEntity` rows per the id/tiering/arc rules
  above, and a `NovelEntity` seeded from OPF metadata (falling back to
  existing `metadata.json`/filename-derived title exactly where OPF is
  itself missing a field, same "never blocks a scan" guarantee every other
  optional-metadata path in `ScannerImpl` already gives).
- Cover extraction to app-private cache, as described above.
- No UI changes this stage - purely data layer, verifiable by inspecting
  the resulting Room rows for a handful of sample EPUB files.

### Stage 2 — Reader wiring
- `ReaderScreen` needs no rendering changes (v1 stays plain-text), but the
  Previous/Next neighbor-resolution logic in `MainActivity` (added by
  `AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md` Stage 3) and any other code that
  currently assumes `sourcePath` is a bare openable URI needs to route
  through the scheme-dispatching `ChapterContentRepository` instead of
  calling `contentResolver.openInputStream()` directly anywhere it still
  does so.
- Verify page-splitting-by-character-count (`NovelDetailScreen`) keeps
  working unchanged, since it only operates on the resolved chapter list,
  same as the doc's earlier estimate assumed.

### Stage 3 — Scanner integration polish
- Sort-tier keyword fallback (bonus/closing) wired against NCX/nav titles,
  per "sortTier, arcId, and the bonus/closing marker convention" above.
- Incremental-rescan fingerprinting (`ScanFingerprintEntity`, currently
  aggregated over "every file under the novel folder") extended to treat
  an `.epub` file's own `lastModified`/`length` as the fingerprint input
  instead of walking a folder's children, since there are no children to
  walk.
- Soft-warning surfacing (`onProgress`) for corrupt/malformed EPUBs
  (missing `container.xml`, unparseable OPF, empty spine) - never a scan
  failure, matching `readLocalMetadata`'s fail-soft precedent.

### Stage 4 — QA / polish
Exercise cases analogous to `AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md`'s Stage 5
pass, EPUB-specific:
- EPUB2 (`toc.ncx`) vs EPUB3 (`nav.xhtml`) table-of-contents parsing, and
  books that ship both (nav.xhtml should win, per spec).
  a book with no declared cover.
- Spine items with no matching NCX/nav entry (title falls back to the
  manifest item's own filename, same "never fail on missing optional
  metadata" guarantee used throughout this app).
- Deeply nested nav trees (more than one level of grouping) - decide
  whether to flatten past one level of `ArcEntity` nesting or genuinely
  extend the arc model; likely flatten for v1, revisit only if a real
  example demands otherwise.
- Non-UTF-8 XHTML content (mirrors `TextChapterContentRepository`'s
  existing UTF-8 → ISO-8859-1 fallback decoder).
- A `.epub` file placed directly at the scan root vs. one sitting inside a
  novel folder alongside a `metadata.json`/`cover.jpg` - confirm the
  precedence rules decided in Stage 1 behave as documented (see open
  question 1).
- Mixed library: some fictions are folders of `.txt` files, others are
  single `.epub` files, in the same scan root - confirm neither path
  regresses the other.

## Explicitly out of scope
- **Real HTML/inline-formatting/inline-image rendering.** v1 flattens
  every chapter to plain text; italics, bold, footnotes, and images
  embedded in chapter bodies are dropped. This is a materially larger,
  separate project (WebView vs. hand-rolled HTML→`AnnotatedString`
  renderer) and deserves its own design doc the way this one does, not a
  late addition folded into this plan's stages.
- **EPUB writing/export.** This plan is read-only, matching how the app
  treats `.txt`/`.md` novels and `metadata.json`/`author.json` today -
  ARKster never writes back into a user's library folder.
- **DRM-protected EPUBs.** Out of scope entirely; these aren't plain zip
  files in the way this plan assumes and would need a different mechanism
  the app has no reason to build (ARKster is offline/account-free per
  `README.md`, with no store integration to justify it).
- **`EPUB_CFI`-precision reading position.** v1 keeps `PERCENTAGE`/
  `CHAR_OFFSET` positioning against the flattened text, per "Reading
  progress" above. True CFI only matters once real HTML rendering exists.

## Open questions
1. Where does a `.epub` file live relative to the existing "one folder = one
   novel" convention - as a loose file directly in the scan root (so the
   `.epub`'s own filename becomes the novel identity, no folder needed at
   all), or does it still need to sit inside a novel folder (enabling it to
   sit alongside a hand-authored `metadata.json`/`cover.jpg`/`authorId`
   override the way text novels already can)? Leaning toward supporting
   both - a bare root-level `.epub` for the common case, with folder
   placement still honored for anyone who wants to layer local overrides on
   top of OPF metadata - but this needs to be pinned down before Stage 1's
   `ScannerImpl` changes, since it determines where the new discovery path
   hooks into `scanRoot` vs. the existing per-novel-folder loop.
2. Does OPF-declared metadata (title/author/description) take precedence
   over an EPUB-adjacent `metadata.json`, or the reverse? Precedent
   (`readLocalMetadata` already exists to let local `metadata.json`
   *override* scanner-derived state for text novels) suggests
   `metadata.json` should win when both are present, with OPF only filling
   gaps `metadata.json` leaves - but this should be stated explicitly
   before Stage 1, not decided implicitly by whichever code path happens to
   run last.
3. Multi-level nav nesting (see Stage 4) - flatten to one `ArcEntity` level
   for v1, or extend the arc model to support nesting? *(Still open - not
   needed to start Stage 1, only Stage 3/4.)*
4. Should `EpubParser` be written by hand against `java.util.zip`/
   `XmlPullParser` as scoped above, or is pulling in a small, permissively
   licensed EPUB-parsing library (trading a new dependency for meaningfully
   less hand-rolled OPF/NCX edge-case handling) worth reconsidering once
   real-world malformed EPUBs are tested against the hand-rolled parser in
   Stage 4? *(Still open - hand-rolled is the Stage 1 starting assumption,
   not a final commitment.)*

Remaining open items should be resolved before the Stage they block begins.

##Extra I Added.

epublib (psiegman/epublib on GitHub) is exactly the profile you're describing: mature, functionally complete, and essentially in maintenance mode rather than active churn.

github
epublib.readthedocs
Image unavailable
epublib (psiegman/epublib)
A Java library for reading, writing, and manipulating EPUB files, with a core module that runs on both Android and plain JVM. It's been stable for years — infrequent commits, no breaking API churn, 1.1k+ stars, LGPL license. Gives you Book/Metadata/Spine/TableOfContents/Resource model classes so you can open an .epub, walk its manifest and TOC, and pull out chapter HTML + the cover image with a handful of calls (EpubReader().readEpub(inputStream) is the whole entry point). Trade-off: it's plain Java, not Kotlin-idiomatic, and it's not actively maintained — you're taking on a dependency that won't get new EPUB3-spec features, so anything unusual (complex media overlays, some malformed real-world EPUBs) may need you to patch it yourself. For your use case (scanning local folders, extracting chapter text/covers into your Room DB) that's a good trade: you don't want a library that changes its API under you every few months.
github.com

A few practical notes on integrating it:

Gradle: it's not on Maven Central, but the author publishes a small maven repo:
kotlin
  repositories {
      maven { url = uri("https://github.com/psiegman/mvn-repo/raw/master/releases") }
  }
  dependencies {
      implementation("nl.siegmann.epublib:epublib-core:4.0") {
          exclude(group = "org.slf4j")
          exclude(group = "xmlpull")
      }
      implementation("org.slf4j:slf4j-android:1.7.25")
  }
License is LGPL — fine to use as a dependency (dynamic linking), but worth knowing if you ever plan to close-source or distribute ARKster commercially; LGPL doesn't require you to open-source your own app, just that the library itself (and any modifications to it) stay under LGPL.
Since it's unmaintained, expect to hit occasional edge cases with modern/malformed EPUB3 files (some webnovel-to-epub exporters produce slightly nonstandard OPF/NCX). You'd likely want a small wrapper/adapter layer in your scanner rather than calling EpubReader directly everywhere, so if you ever do need to patch or swap it out, it's contained to one file.
