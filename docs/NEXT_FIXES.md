# Next fixes — sync robustness

> **Status:** Known issues, not yet built. Found during a design-review pass
> over `SyncManager.kt` / `FictionLut.kt` / `ScannerImpl.kt` after the
> single-origin-by-name sync landed (see `docs/SYNC_MVP.md`). None of these
> block what's already shipped; all four are worth fixing before treating
> sync as production-solid, since sync is the mechanism the whole
> direct-to-reader model depends on being trustworthy.

---
