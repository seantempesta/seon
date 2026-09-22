---
type: reference
status: review of f4d3f7648 complete; dropped rules restored in the same commit; two items left to root
created: 2026-09-21
tags: [agent-platform, instructions, testing, review]
---

# Fable review of the shared-instruction activation (`f4d3f7648`)

Read end to end: old and new root `AGENTS.md` (1,330 → 297), old and new
`clojure-testing` skill, `docs/seon/architecture/vocabulary.md`, the repl skill diff,
plan §6/§8 and the activation note. Every enforcement citation in the skill's table was
checked against source; symlinks `CLAUDE.md -> AGENTS.md` and `.claude/skills ->
../.agents/skills` are intact.

## 1. Dropped binding rules, restored into the root (owner, 2026-09-21: agents rarely
load skills, so evergreen traps live in the root)

| Old rule | Where it went | Restored at |
|---|---|---|
| verify / falsify / probe, never adversarial verbs | only `codex-lanes` skill | Operation and delegation |
| Skills carry `file:line`; unverifiable claim deleted; stale skill = defect | nowhere | Start here |
| Docs never publish or widen a gate; inputs are declared | nowhere | Start here |
| No publication-path slice lands without its measurement row | nowhere | Start here |
| Symbol never string, with the `(str sym)` sighting | weakened to "a symbol stays a symbol" | Data |
| The `contains?` trap | nowhere (not in data-oriented-clojure either) | Data |
| Direct `datahike.api` only in `seon.db`, store/registry, custody owners, listeners | weakened to "`seon.db` owns" | Data |
| clj-kondo "Unresolved var" = stale cache; repopulate; never the `.` entry | nowhere | Operation |
| Schema resource and loaded consumer in one publication | nowhere | Operation |
| Opus never for implementation | nowhere | Operation |
| Launch cites the entry it extends; one lane per class; raw evidence, never attribution | partly ("verify before naming a cause") | Operation |
| At most four prepl probers on `default` | only `codex-lanes` skill | Operation |
| Lanes never edit the issues index | nowhere | Operation |

Retired mechanism detail, correctly dropped: launcher choreography, `bin/test`
tiers, phase-line timing, hook payload fields, the vocabulary table (moved whole).

## 2. Enforcement table

All rows verified against source. One citation drifted: `run-vars!` is at
`src/seon/test/runner.clj:655`, not `:657`. The limits column is honest on every row;
the "no hand-written fixture map" row correctly says there is no detector.

## 3. The six scenarios

Each leads to honest evidence: a core edit during a cut routes to plan §6 (focused
requests, platform at the checkpoint); a refused fixture write fails through
`transacted!` only when used, and the skill says so; a body over bound fails at
completion and the skill says it is not a kill; a reload stripping a wrapper is named
as author responsibility with the arming owner to call; a nonzero competitor exit is
explicitly insufficient (skill §Bounds); a recorded green reused without execution is
labelled reuse, not proof.

## 4. Left to root

- `AGENTS.md:40-41` names B1b's temporary breakage exception. It is a dated claim in
  an evergreen file; delete it at B1b integration.
- Fix the `:657` citation in the skill.
- `AGENTS.md:26` already names `seon.cluster.boot/connection`, which exists at the tip.
