---
type: issue
status: resolved
severity: friction
tags: [issue, flow]
---

# Markdown linter tag vocabulary was two drifted hand-maintained lists

## Problem

`seon.dev.markdown` validated frontmatter tags against a literal name set
that existed twice — the registered `::tag` enum and a private
`valid-tags` set that had grown extras (`:pod :wasm :cljs :mcp`) the enum
never learned. Neither copy knew the live vocabulary (`diffusion`,
`context`, `gym`, `ui`, `config`, `render`, `runtime`), producing 96
standing warnings that were linter debt, not doc debt, and violating the
owner's no-hand-maintained-lists rule.

## Fix

Replaced membership-in-a-literal-set with a corpus-derived rule: the
vocabulary is computed from live vault frontmatter usage — a tag or type
value belongs once at least two documents carry it, so a singleton is
flagged as either a typo or vocabulary nothing else adopted. Memoized per
vault root; a fresh process re-derives it from the files. The `::tag`
schema became the structural shape (`:keyword`), and `rule-valid-tags` /
`rule-valid-type` take the vault root (no vault, no membership check).

## Proof

- Linting every `docs/**/*.md` (1508 files): tag+type findings dropped
  96 → 12, and each survivor is a genuine singleton (e.g. `honesty`,
  `worktree`, malformed `tags:` lines).
- `seon.dev.markdown-test`: 22 tests, 341 assertions, 0 failures
  (including a new no-vault and singleton-tag case).
