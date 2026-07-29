---
type: issue
status: open
severity: blocker
tags: [issue, program-graph, documentation]
---

# Build-indexing skills still require function contracts

## Problem

Three mandatory Seon skills still teach the retired build-time rule that only
contracted functions become program-graph rows. Commit `7340e2635` deliberately
changed `seon.fn` to admit every declared `defn` and `defn-`, while the
eval-time `seon.sci.eval/program-row` boundary remains contract-selective.

An agent following the skills will restore the exact admission defect that
excluded 808 private helpers or blur the build and eval boundaries. Skills
have repository-wide blast radius, so this is not ordinary documentation
drift.

## Evidence

- `.agents/skills/data-oriented-clojure/SKILL.md:58-62` says program-graph
  function rows require `:malli/schema`.
- `.agents/skills/datahike/SKILL.md:197-203,471` repeats the contracted-function
  rule and describes `src/seon/fn.clj` that way.
- `.agents/skills/data-modeling/SKILL.md:234-240` repeats it a third time.
- `src/seon/program.cljc:64-83` owns the shared policy: build passes `:all`,
  which admits every function event carrying `:seon.fn/sym`, while runtime
  passes `:contracted`.
- `src/seon/fn.clj:22-26` is the build caller of that `:all` policy.
- `src/seon/sci/eval.clj:321-439` still admits eval-time function rows only
  through a valid contract. That distinction is deliberate and independently
  reproduced.

The archived
`archive/schema-skills-teach-the-retired-registration-model.md` corrected an
earlier skill drift and cited the then-current contracted-only indexer. The
new source change invalidated that resolution sentence; this is a new
recurrence, not a reason to reopen its otherwise resolved schema work.

## Owner

`.agents/skills/data-oriented-clojure`, `.agents/skills/datahike`, and
`.agents/skills/data-modeling`.

## Acceptance

All three skills state the two boundaries separately and cite current source:

- build-time indexing records every declared `defn` and `defn-`, public or
  private, with an optional contract; and
- eval-time agent publication records only a valid contracted function.

An independent verification pass checks every changed claim against the
current owners and confirms no adjacent skill repeats the retired rule.
