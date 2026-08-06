---
type: issue
status: resolved
severity: blocker
tags: [issue, skills, documentation, sci, schema]
---

# Re-ground mandatory skills after the desk and schema-directory waves

## Problem

Eight maintained skills teach at least one mechanism deleted or renamed on
2026-08-05: a shared mutable SCI context, a durable session image, the
monolithic `resources/seon/schema.edn`, or an in-tree `src-old/` quarry. The
flow and REPL skills are mandatory at exactly the runtime boundary these claims
misdescribe, so an agent following them can rebuild a deleted mechanism.

This is a recurrence of the defects previously closed in
`archive/flow-skill-teaches-overturned-runtime-facts.md` and
`archive/schema-skills-teach-deleted-monolithic-resource.md`.

## Evidence

- `.agents/skills/seon-flow-architecture/SKILL.md:74-86,478-492` restores a
  durable session image into one shared live context and says definitions
  accumulate there. Current `src/seon/sci/eval.clj:1309-1367` forks the
  program-only base for each turn and rehydrates only the selected agent's
  `:seon.def/*` desk; `src/seon/cluster/loop.clj:1493-1514,1643-1658` uses and
  settles that exact path.
- `.agents/skills/repl/SKILL.md:15-21,50-61` marks the shared context as current
  and the fork as an unbuilt per-run target. That reverses the same current
  source.
- `.agents/skills/datahike/SKILL.md:256-259,633-634` and
  `.agents/skills/data-modeling/SKILL.md:243-247` still name a session image;
  the Datahike skill points to deleted
  `test/seon/sci/session_image_test.clj`. Current acceptance is
  `test/seon/sci/desk_test.clj`, and current facts are declared in
  `resources/seon/schemas/seon.def.edn:1-45`.
- `.agents/skills/data-modeling/SKILL.md:45-54`,
  `.agents/skills/datahike/SKILL.md:221-234`,
  `.agents/skills/llm-providers/SKILL.md:17-29,94-98,145-148`, and
  `.agents/skills/seon-context-config/SKILL.md:2-27` direct readers to the
  absent `resources/seon/schema.edn`. Current
  `src/seon/schema/edn.clj:1-15,49-51` loads one directory-backed population
  from `resources/seon/schemas/`.
- `.agents/skills/clojurescript/SKILL.md:3,13-14,48-61`,
  `.agents/skills/data-oriented-clojure/SKILL.md:19-22`, and
  `.agents/skills/seon-context-config/SKILL.md:19-20` instruct readers to open
  `src-old/`. Root `AGENTS.md:250-254` records that both old trees were deleted
  and quarrying now uses `git show` or `git log`.
- `.agents/skills/datahike/SKILL.md:39-43` pins Datahike at `c152727...`; the
  current gitlink is `56f1c62105b7087f0cac13162f9fd54b1690986e`.

## Owner

The complete `.agents/skills/` packages for `clojurescript`,
`data-oriented-clojure`, `data-modeling`, `datahike`, `llm-providers`, `repl`,
`seon-context-config`, and `seon-flow-architecture`, including directly linked
references.

## Acceptance

- Every skill distinguishes the program-only base, fresh per-turn fork, and
  agent-scoped `:seon.def/*` desk using current source citations.
- Schema guidance names the directory population and its current loader; no
  maintained skill cites the deleted monolith.
- Historical quarry instructions use `git show`/`git log`, never an absent
  checkout directory.
- Dependency pins and all cited first-party paths match the checked-out tree.
- An independent skill verification pass reads every changed skill and linked
  reference in full and validates the complete skill corpus.

## Resolution

Resolved by `6f2576a18`. The nine affected skill packages now cite the current
program-only base, per-turn fork, agent desk, split schema population, Git
history quarry, and selected dependency sources. All nine packages passed the
skill creator's `quick_validate.py`, the canonical skill symlinks still resolve
to `.agents/skills/`, and the changed files passed `git diff --check` before the
path-limited commit. The required independent verification pass remains the
next review boundary and is not claimed here.
