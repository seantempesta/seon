---
type: issue
status: open
severity: friction
tags: [issue, skills, schema, documentation]
---

# Teach the live split schema registry in schema skills

## Problem

Four curated schema and database skills still direct agents to the deleted
monolithic `resources/seon/schema.edn`. The live authority is the merged set
of declarations under `resources/seon/schemas/`, so the instructions point
schema work at a nonexistent owner.

## Evidence

- `.agents/skills/data-modeling/SKILL.md:3`,
  `.agents/skills/data-oriented-clojure/SKILL.md:7`,
  `.agents/skills/datahike/SKILL.md:6`, and
  `.agents/skills/clojure-testing/SKILL.md:118` name the deleted monolithic
  resource.
- `src/seon/schema/edn.clj:360-385` discovers and merges the live declaration
  resources from `resources/seon/schemas/`.
- This search-metadata slice had to follow the implementation rather than the
  selected skill directions to find each owning declaration.

## Owner

The schema skill dependency-ledger repair wave.

## Acceptance

- Update all four skills to name the current split registry and its loader.
- Verify every touched claim against current source and the selected
  dependencies.
- Run an independent verification pass over the substantially changed skill
  instructions before closing this issue.

