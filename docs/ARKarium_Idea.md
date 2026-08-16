# ARKarium — Author-Controlled Reader & Direct-to-Reader Platform

> **Working name:** ARKarium  
> **Status:** Idea / architecture notes  
> **Purpose:** Preserve the idea before humanity inevitably invents twelve other distractions.

## Core idea

ARKarium is a vertically integrated reading ecosystem designed around **direct author-to-reader distribution**.

The goal is not to build another Royal Road clone. The goal is to control the entire chain from authoring and publishing to distribution and the final reading experience, while keeping the underlying infrastructure as simple and dependency-light as possible.

### Guiding principle

> **Flatten the dependency graph. Control the experience. Make the substrate boring.**

Instead of:

```text
Author
  ↓
Platform
  ↓
Database
  ↓
API
  ↓
CDN
  ↓
Platform app
  ↓
Reader
```

ARKarium aims for:

```text
Author
  ↓
Git repository
  ↓
Static assets
  ↓
HTTP distribution / relay chain
  ↓
ARKarium client
  ↓
Local library
  ↓
Reader
```

---

# The reader ecosystem

Planned clients:

- **Android:** successor/rebrand of ARKster
- **Desktop:** ARKium-derived client
- **iOS:** possible future client
- **Web:** optional discovery / complementary experience, not necessarily the primary reading experience. but there is a separate repo which handles Horizon ARK Studio.

The clients should consume the same underlying content/distribution format.

The UI/UX can be inspired by successful web-novel readers such as Royal Road, while the implementation, branding, assets, and overall product identity become ARKarium's own adapted from ARKster.

---

# Direct-to-reader publishing

A creator maintains their own source repository.

Example:

```text
Fiction Name/
├── metadata.json
├── cover.png
├── author.json
└── arcs/
    ├── Arc 1/
    │   ├── metadata.json
    │   ├── cover.png
    │   ├── 001.txt
    │   ├── 002.txt
    │   └── 003.txt
    └── Arc 2/
        ├── metadata.json
        └── 001.txt
```

The actual prose can remain plain `.txt`.

JSON is used for machine-readable information:

- novel metadata
- author metadata
- manifests
- versions
- hashes
- relay information
- optional semantic/audio information

The content format should remain simple and human-readable.

---

# Static relay architecture

No conventional backend is required for the core distribution system.

A deployment acts as a **relay/storage block**.

ARKarium initially knows only the first relay.

```text
ARKarium 
  ↓
Relay A
  ↓ if asset unavailable
Relay B
  ↓ if asset unavailable
Relay C
  ↓
asset
```

Each relay only needs to know its successor.

```text
Relay A
  NEXT = Relay B

Relay B
  NEXT = Relay C

Relay C
  NEXT = Relay D
```

ARKarium does not need a complete list of all relays.

This allows additional deployments to be added when an existing block becomes full or otherwise needs to be replaced.

The canonical source remains the creator's repository.

### Important implementation distinction

Pure static hosting cannot dynamically inspect a missing asset and then redirect to the next relay.

Possible implementations:

1. A very small request-handling layer/Worker at each relay.
2. Client-side relay traversal where the client understands the protocol.
3. A hybrid approach.

The protocol should remain simple regardless of implementation.

---

# Manifests and synchronization

> See `docs/SYNC_MVP.md` for the cut-down, currently-being-built version of
> this: single-origin only, no relay chain, no auth — just manifest fetch,
> hash diff, and reusing the existing folder scanner.

A manifest can describe available assets and versions.

Example:

```json
{
  "version": 184,
  "files": {
    "fiction-a/arcs/arc-1/001.txt": {
      "size": 18472,
      "sha256": "..."
    }
  },
  "next": "https://relay-b.example/"
}
```

The client can use hashes to determine whether its local copy is already current.

Desired behavior:

```text
New manifest
    ↓
Compare local hashes
    ↓
Download only missing/changed files
    ↓
Verify hash
    ↓
Store locally
```

The reader should be **offline-first**.

The network should primarily answer:

> "Do I have the newest content?"

rather than:

> "Am I allowed to read this chapter right now?"

---

# The killer feature: semantic multi-voice TTS

A major planned differentiator is a built-in **text-to-speech audiobook-like mode**.

The goal is not professional audiobook quality.

The goal is:

> **A free, automatically generated, slightly-less-terrible audiobook experience that requires essentially no recording effort from the author.**

Because ARKarium controls the publishing format and the reader, it can understand more about the text than a generic TTS reader.

Potential semantic data:

```json
{
  "characters": {
    "john": {
      "name": "John",
      "voice": "voice_03"
    },
    "mary": {
      "name": "Mary",
      "voice": "voice_07"
    }
  }
}
```

Possible progression:

### Stage 1
- narration voice
- dialogue detection
- basic alternate voice for dialogue

### Stage 2
- character recognition
- persistent voice assignment

### Stage 3
- per-character voice parameters
- pitch
- speed
- delivery

### Stage 4
- contextual emotion
- emphasis
- pauses
- improved pronunciation

The result could resemble a lightweight automatically generated audiobook without requiring the author to record anything.

This is a potential **killer feature** because a normal web novel platform can easily provide text, but reproducing a deeply integrated multi-character reading mode is substantially harder.

---

# Experience over infrastructure

The infrastructure should deliberately remain boring.

Preferred primitives:

- Git
- HTTP
- JSON
- TXT
- PNG / ordinary assets
- manifests
- hashes
- local storage
- simple relay addressing

Complexity should live primarily in:

- reader experience
- synchronization
- presentation
- accessibility
- semantic content
- TTS
- authoring/publishing tools

Not in a giant always-on backend.

---

# Existing project relationship

ARKarium is conceptually related to the existing Rae ARK's projects.

## ARKster

Existing Android reader project.

Current project is GPLv3.

Potential future role:

- Android foundation
- local library
- reading UX
- synchronization client

## ARKium

Existing desktop reader project.

Current project is GPLv3.

Potential future role:

- desktop foundation
- reader/library UX
- desktop publishing/management tools

If a proprietary/commercial edition is ever desired, copyright ownership and third-party dependency licensing must be audited carefully. A separate license from the relevant copyright holders may be necessary.

# Licensing philosophy

The application implementation may ultimately be proprietary if the necessary rights are obtained.

Important distinction:

- Creator-owned novels remain the creator's works.
- Creator assets remain under their chosen terms.
- Distribution infrastructure can have its own license.
- Client software has its own license.
- External dependencies retain their own licenses.

Therefore ARKarium and Rae ARK's other projects have different licenses suited for their purposes.

---

# Long-term vision

The ideal experience is:

```text
Creator
   ↓
Writes normally
   ↓
Publishes to Git
   ↓
Automatic build/deployment
   ↓
Static distribution
   ↓
Reader discovers work
   ↓
Reader installs ARKarium
   ↓
Library syncs locally
   ↓
Reader gets the complete experience
```

The web is primarily the **discovery layer**.

The native application is the **experience layer**.

The repository/static distribution system is the **ownership and delivery layer**.

The client should make using ARKarium worthwhile by offering experiences that ordinary web readers cannot easily reproduce.

---

# Product philosophy

### 1. Direct-to-reader

Creators should not need a giant centralized platform to deliver their work.

### 2. Local-first

Once content is synchronized, reading should not require constant connectivity.

### 3. Static-first

Prefer static files and simple protocols over unnecessary backend infrastructure.

### 4. Dependency minimization

Every external service is a potential failure, cost, policy change, or point of control.

Flatten the graph.

### 5. Experience as the moat

The value should be in what the client can do with the content.

### 6. Creator-controlled content

The creator's repository should remain the canonical source.

### 7. Progressive sophistication

Start with:

```text
TXT + JSON + PNG
```

and only add complexity when it creates a meaningful reader benefit.

---

# Possible future features

- Character-aware TTS
- Multiple voices
- Automatic audiobook mode
- Illustrations integrated into chapters
- Maps
- Character profiles
- Lore/reference panels
- Author notes
- Interactive fiction elements
- Audio assets
- Pronunciation dictionaries
- Custom typography
- Chapter-specific presentation
- Offline downloads
- Incremental synchronization
- Content integrity verification
- Versioned editions
- Optional creator-specific experiences

---

# The strategic goal

ARKarium is intended to be an **Apple-like vertically integrated experience for independent fiction**, not by copying Apple's infrastructure, but by applying the principle of controlling the stack that matters.

The ambition:

> **Give readers who are willing to take the jump an unmatched reading experience, while giving the author direct control over how their work reaches those readers.**

The technology should disappear behind the experience.

The reader should simply feel:

> "This is better than reading it on a website."

