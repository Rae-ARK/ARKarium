#!/usr/bin/env python3
"""Docs drift check. Run from anywhere: python3 tools/check_docs.py

Checks (see docs/README.md, "No-drift rule"):
  1. Relative markdown links in README.md, app/README.md and docs/ resolve.
  2. Every docs/** folder has a README.md that links every sibling doc.
  3. Every `docs/<path>.md` path cited in source, workflows or tools exists.
  4. Every lifecycle doc has a `Verified: YYYY-MM-DD` line; warns when it is old.

Exit code 1 on any error; stale dates are warnings only. Standard library only.
"""
import re
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
STALE_DAYS = 45

LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
DOC_PATH = re.compile(r"docs/[A-Za-z0-9_][A-Za-z0-9_./-]*\.md")
VERIFIED = re.compile(r"Verified:\*{0,2}\s*(\d{4}-\d{2}-\d{2})")

errors, warnings = [], []


def rel(p: Path) -> str:
    return str(p.relative_to(ROOT))


def md_files():
    yield ROOT / "README.md"
    yield ROOT / "app" / "README.md"
    yield from sorted(DOCS.rglob("*.md"))


def check_links():
    for f in md_files():
        if not f.exists():
            continue
        in_fence = False
        for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            if line.lstrip().startswith("```"):
                in_fence = not in_fence
            if in_fence:
                continue
            for target in LINK.findall(line):
                if re.match(r"[a-z]+:", target) or target.startswith("#"):
                    continue
                path = target.split("#")[0]
                if path and not (f.parent / path).resolve().exists():
                    errors.append(f"{rel(f)}:{n}: broken link -> {target}")


def check_indexes():
    for folder in [DOCS, *sorted(p for p in DOCS.rglob("*") if p.is_dir())]:
        readme = folder / "README.md"
        if not readme.exists():
            errors.append(f"{rel(folder)}: missing README.md index")
            continue
        text = readme.read_text(encoding="utf-8")
        for sibling in sorted(folder.glob("*.md")):
            if sibling.name != "README.md" and sibling.name not in text:
                errors.append(f"{rel(readme)}: does not index {sibling.name}")
        for sub in sorted(p for p in folder.iterdir() if p.is_dir()):
            if f"{sub.name}/" not in text:
                errors.append(f"{rel(readme)}: does not index folder {sub.name}/")


def check_code_pointers():
    skip = {".git", "build", ".gradle", "node_modules", "docs"}
    exts = {".kt", ".kts", ".xml", ".yml", ".yaml", ".py", ".md", ".gradle"}
    for f in sorted(ROOT.rglob("*")):
        if not f.is_file() or f.suffix not in exts:
            continue
        if any(part in skip for part in f.relative_to(ROOT).parts):
            continue
        for n, line in enumerate(f.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
            for ref in DOC_PATH.findall(line):
                if not (ROOT / ref).exists():
                    errors.append(f"{rel(f)}:{n}: cites missing doc {ref}")


def check_verified():
    today = date.today()
    for f in sorted(DOCS.rglob("*.md")):
        if f.name == "README.md":
            continue
        m = VERIFIED.search(f.read_text(encoding="utf-8"))
        if not m:
            errors.append(f"{rel(f)}: no 'Verified: YYYY-MM-DD' line")
            continue
        age = (today - date.fromisoformat(m.group(1))).days
        if age > STALE_DAYS:
            warnings.append(f"{rel(f)}: last verified {age} days ago; re-check against code")


def main():
    check_links()
    check_indexes()
    check_code_pointers()
    check_verified()
    for w in warnings:
        print("WARN ", w)
    for e in errors:
        print("ERROR", e)
    print(f"{len(errors)} error(s), {len(warnings)} warning(s)")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
