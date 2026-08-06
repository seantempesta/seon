---
type: issue
status: resolved
severity: friction
tags: [issue, testing, documentation]
---

# Remove deleted aliases from the Clojure-testing skill

## Problem

The Clojure-testing skill again teaches deleted dependency aliases and cites
line ranges that no longer exist after the 2026-08-05 reset. A skill is loaded
precisely when an agent is about to choose a test surface, so the stale claim
has repository-wide blast radius.

## Evidence

- `.agents/skills/clojure-testing/SKILL.md:18-23` says retained `:cljs`,
  `:writer`, and `:writer-test` aliases are dead or quarry-only and cites
  `deps.edn:101-110,162-184`.
- Current `deps.edn` ends at line 143 and declares only the current `:test`
  gate at `deps.edn:121-134`; none of those three aliases exists.
- The active issue was previously closed as
  `docs/seon/issues/archive/clojure-testing-skill-points-at-deleted-quarry-gates.md`,
  so this is a verified recurrence rather than an untriaged historical note.

## Owner

The `.agents/skills/clojure-testing` instruction package and the current
`bin/test` surface.

## Acceptance

The skill describes only current test commands and carries verified current
file:line evidence. Independent skill verification finds no deleted alias,
deleted source root, or line beyond the end of its cited file.

## Resolution

Resolved by `6f2576a18`. The skill now names only the current `bin/test` fast,
full, and explicit-namespace surfaces, with current citations to `AGENTS.md`,
`bin/test`, and `src/seon/test/runner.clj`. Its package passed the skill
creator's `quick_validate.py` and `git diff --check` before the path-limited
commit. The required independent verification pass remains the next review
boundary and is not claimed here.
