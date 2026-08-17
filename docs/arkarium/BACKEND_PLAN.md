# Backend plan — social features, without owning a backend

> **Status:** Design-only, not yet built. Written up after a design-review
> conversation covering why ARKarium's sync engine deliberately avoids a
> backend, which upcoming features are the genuine exception, and what to
> build for those. Nothing here blocks or changes `docs/arkarium/SYNC_MVP.md` -
> chapter sync stays exactly as designed, static-host-only, no server logic
> involved. This is additive, for a different subset of features.

---

## 1. The actual question isn't "can I avoid a backend forever"

It's "which of my features structurally need one, and which don't."

Walking the existing roadmap (`docs/arkarium/FUTURE_PLANS.md`) against that question:

- **Chapter sync (built)** - static host only, by design, not by budget
  constraint. `docs/arkarium/SYNC_MVP.md` §2 is explicit: manifest + hash diff +
  static host, no dynamic behavior of any kind.
- **TTS** - proposed as an offline, publish-time build step (speaker
  attribution precomputed on the author's own machine, shipped as a static
  JSON segment file). No backend.
- **Multi-relay fallback, background sync, EPUB/PDF ingestion** - static
  hosting and/or client-side work. No backend.
- **Auth / paid content gating** - the one real exception. You cannot gate
  access to a static host's bytes; deciding "has this reader paid" has to
  run server-side, per request.
- **Comments, ratings, reading-position sync** - the other real exception.
  These are per-user *writes* that need to persist, be queried back, and be
  attributed to someone. That's inherently a small backend, not a design
  failure to route around.

So the honest scope of "needs a real backend" is small and specific: gating,
and a handful of small social/sync features. Everything else was never going
to need one, independent of budget.

## 2. Where a backend is genuinely required, "free forever" is achievable

Not a free trial, not a time-boxed credit - a tier that stays $0 indefinitely
under reasonable early-stage usage. Two shapes of "backend" showed up in this
discussion, and they call for different platforms:

### 2a. Per-request authorization (device auth / paid-content gating)

No static host can do this - the decision has to be made per request, which
is inherently dynamic. But it doesn't need a database or a general-purpose
server either; it's just "is this token valid → yes/no."

**Cloudflare Workers** fits this well and is already where `RELAY_HOST`
points: 100K requests/day free, ~10ms CPU per request (a signature check is
microseconds), paired with **Workers KV** for a revocation list (also free,
no schema, just `get`/`put`). No D1, no Postgres - a token-verification
function is not a database problem.

**Deno Deploy** is a close alternative if Cloudflare's tooling is the
friction point rather than the model itself - same edge-function shape, its
own free built-in KV (Deno KV), no card required, no time limit.

Encryption-in-transit (HTTPS) is not the gap here and doesn't need solving -
every option under consideration (Cloudflare, GitHub Pages, Firebase, even
free WordPress hosts) provides TLS by default. The actual gap was always
authorization, not eavesdropping.

**Ruled out:** WordPress (even self-hosted free-tier) as this gating layer.
Two independent problems, not one: (1) it's a CMS being bent into an API
gateway shape it wasn't built for - a token check needs none of WordPress's
media library, post types, or plugin ecosystem; and (2) free PHP hosts
commonly throttle or CAPTCHA-block exactly the traffic pattern a mobile
sync client produces (automated, scheduled, non-browser requests) -
confirmed via multiple live threads on InfinityFree's own support forum
describing API requests getting CAPTCHA-gated or 403'd. An auth gate that
sometimes returns a CAPTCHA page instead of a clean 403 isn't a minor
inconvenience, it's a broken contract.

### 2b. Comments, ratings, reading-position sync

This needs actual persistent, queryable storage with per-user identity -
a real (if small) database, fronted by something that isn't hand-rolled SQL
migrations.

**Firebase Spark plan** is the pick: genuinely free with no time limit and
no card required, generous daily quotas (50K Firestore reads/day, 20K
writes/day, 20K deletes/day, 1 GiB stored, 10 GiB/month transfer), free
anonymous Auth (a stable per-install device ID, no login screen, unlimited
and free) - which maps directly onto "sync my own ratings/position across
my own devices" without building an account system. Schemaless documents
mean no migrations to manage.

**Supabase was considered and passed over specifically because of the "free
forever, low maintenance" requirement**, not because its free tier is
stingy (it isn't - 500 MB DB, 1 GB storage, 50K MAUs, 500K edge-function
invocations, all free). The dealbreaker: free-tier projects **pause after 7
days of no database activity** and go offline until manually resumed. For
an early app with sparse, bursty usage, that's a real risk of "opens the
app after a quiet week, comments/ratings just don't load."

**Important nuance on Firebase's quota scope:** it's per-*project*, not
per-account or per-app. One Google account can hold several Firebase
projects, each with its own independent free quota - there's no shared pool
being divided up. But within one project, the quota is shared across every
app registered to it (so Android + any future iOS/web client draw from the
same daily allowance). And exceeding a product's free quota in a given
month shuts that product off project-wide for the rest of the month, not a
soft per-user throttle - worth designing around explicitly (see §3).

## 3. What happens when the free quota is hit

Two design decisions here, both aimed at making a quota ceiling invisible
to the reader rather than a wall:

1. **Don't block-and-apologize with a static message.** Firestore's quota
   resets daily (around midnight Pacific); other Firebase products meter
   monthly. A single hardcoded "try again in N days" message would be wrong
   for whichever case it wasn't written for. Branch the message off which
   specific call/error actually failed instead.

2. **Queue locally instead of failing the write.** Consistent with the
   app's existing local-first design (chapter sync already treats the local
   folder/`SyncedFileEntity` table as ground truth, Firebase as a mirror -
   see `docs/arkarium/SYNC_MVP.md` §4): a comment or rating write goes into a local
   Room table immediately, marked `pending`, and shows up in the UI right
   away regardless of whether the remote write has landed yet. On a
   quota/`RESOURCE_EXHAUSTED`-style failure specifically (not a generic
   network error), leave it `pending` and show a calm status line ("Comments
   will sync once available") rather than an error dialog. Retry on next
   app open or manual sync - same "manual, foreground sync only" pattern
   already established for chapters, no new background-sync machinery
   needed.

   Any such message must be scoped clearly to comments/ratings/sync - never
   implied to affect reading, since chapter content lives on the completely
   separate static relay and is untouched by any of this.

## 4. Portability - don't let Firebase own the data model

The instinct to make data portable *before* it's needed, not after, is
correct. Two things worth separating:

- **Google's own managed Firestore export/import service requires the
  project to be on the Blaze (billing-enabled) plan** - confirmed directly
  from Firebase's docs. Its output format is also not a friendly one:
  LevelDB/protobuf-oriented, and it doesn't include index definitions. Not
  something to build a portability strategy around.
- **The fix is a custom exporter using plain reads, not the managed
  service.** A script that pages through collections with the ordinary
  Firestore SDK (`where updatedAt > cursor`, `limit`, page) and writes plain
  JSON/NDJSON stays entirely on the free Spark plan - no billing account
  ever needed. This is *also* the thing that keeps you off Blaze
  permanently, which is worth being explicit about: the "nicer, more
  portable" choice and the "never gets billed" choice are the same choice
  here.

This script is **ops tooling, not app code** - it doesn't ship inside
ARKarium. It runs on a schedule outside the app (see §5).

**On the "four layers of representation" question:** local/remote/export
representations are a real distinction, but ARKarium mostly already has
this shape for free. Chapter sync already treats the local Room table as
ground truth and the remote host as a mirror (`docs/arkarium/SYNC_MVP.md`'s framing
throughout). Comments/ratings/position just need the same treatment - a
local Room table is on-device ground truth, Firestore is the sync target,
and the "export representation" doesn't need to be a different shape from
the Room schema itself, since SQLite rows are already portable data. The
only genuinely new artifact is the offline collection-dump script, since no
single device's local DB holds *other* readers' comments.

**On conflict resolution:** smaller than it sounds for these three data
kinds specifically, and not worth generalizing past what's actually needed
(the project's own stated philosophy - see `docs/arkarium/SYNC_MVP.md`'s "why cut it
down" - applies here too):

- Reading position, rating: one scalar per (user, novel). Last-write-wins by
  an `updatedAt` timestamp is correct, not a compromise - nobody wants a
  merge of "chapter 12" and "chapter 13," they want whichever device they
  read on most recently.
- Comments: not a conflict at all - inserts, not competing edits to the
  same record. No merge logic needed.

No CRDTs, no vector clocks - one timestamp column covers both mutable
fields.

## 5. Warm standby - a second backend "just in case," without automatic failover

A natural extension of §4's exporter: instead of (or alongside) a static
JSON archive, land the same periodic pull into a second *live, queryable*
database, so there's something ready to promote if Firebase becomes
unavailable for an extended period, rather than only a cold file to
manually reload.

**Where the job runs:** a scheduled **GitHub Actions workflow** (cron
trigger). Public-repo workflows run on standard hosted runners for free
with no minute cap; even on a private repo, the free allowance (2,000
Linux minutes/month) comfortably covers a job that takes a couple of
minutes and runs daily. No infrastructure of its own to host or patch.

**What it does:** Firebase Admin SDK (service account key stored as a repo
secret) queries each collection for documents changed since the last
cursor, pages through, upserts into the mirror. Same free-Spark-plan reads
as the exporter in §4 - this is the same script, with an additional write
target.

**Where the mirror lives:** Supabase, using the same schema shape as the
JSON export. This incidentally solves Supabase's own 7-day inactivity pause
(§2b) as a side effect: a daily upsert *is* database activity, so the
mirror job doubles as Supabase's keep-alive. No separate ping hack needed -
the backup process **is** the heartbeat.

**Direction is one-way, and failover is manual, not automatic.** This is
the one place to deliberately not automate further. If the app
autodetected "Firebase looks unreachable" and started reading or writing to
the mirror on its own, a flaky-but-not-fully-down period could produce a
genuine split-brain - a comment posted during the wobble existing on one
backend but not the other, permanently. Instead: the mirror stays
strictly read-only, populated only by the nightly job, until a human
deliberately promotes it (a remote-config flip or an app update pointing at
the mirror's URL) - a decision made with actual context ("Firebase's status
page says 6 hours"), not a client guessing from a failed request.

**Client-side, this wants an interface, not a rewrite.** A
`SocialBackendClient` interface with a `FirebaseSocialClient` implementation
today; if the mirror is ever promoted, a `SupabaseSocialClient` implements
the same interface and `SyncManager` swaps which one it constructs. The
local pending-write queue from §3 is unaffected by which backend is
primary - a promotion event is "swap the client, then flush the queue," not
a rearchitecture of how writes work.

## 6. Summary of the actual plan

| Concern | Answer | Cost |
|---|---|---|
| Chapter content sync | Static host (Cloudflare Pages/R2, GitHub Pages) | Free, no change from current design |
| Device auth / paid-content gating | Cloudflare Worker + KV (token issue/verify/revoke) | Free (100K req/day) |
| Comments, ratings, reading-position | Firebase Spark (Firestore + anonymous Auth) | Free (no time limit, no card) |
| Local resilience against quota limits | Room-backed pending-write queue, retried on next sync | No cost, client-side only |
| Data portability | Custom JSON/NDJSON exporter via plain Firestore reads (not managed export/import) | Free, avoids Blaze entirely |
| Disaster-recovery standby | GitHub Actions cron → Supabase mirror, promoted manually | Free (Actions minutes + Supabase free tier) |

Nothing in this table requires billing to be enabled anywhere, and nothing
in it is a time-boxed trial - every piece is a tier that's free
indefinitely under ARKarium's current and near-term scale.
