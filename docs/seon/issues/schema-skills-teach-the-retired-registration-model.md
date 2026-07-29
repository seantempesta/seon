---
type: issue
status: open
severity: friction
tags: [issue, docs, schema, database]
---

# Re-ground schema skills in the fresh EDN and instrumentation system

## Problem

The three mandatory Clojure/data skills teach the pre-fresh-tree
`schema/register!` authoring workflow and two claim runtime instrumentation
does not exist. An agent following them will put durable attribute schemas in
code instead of `resources/seon/schema/*.edn`, may recreate a retired `seon.db`
boundary, and will reason incorrectly about legal in-memory omission.

## Evidence

- `.agents/skills/datahike/SKILL.md:3,8-13,88-106,126-139` calls
  `schema/register!` the single source of truth and makes direct registration
  its quick start. Fresh attribute/entity schemas are classpath EDN maps loaded
  by `src/seon/schema/edn.clj:173-191`.
- `.agents/skills/data-modeling/SKILL.md:8-15,43-81,288-310` makes the same
  code registration the entire authoring workflow.
- `.agents/skills/data-modeling/SKILL.md:234-237` and
  `.agents/skills/data-oriented-clojure/SKILL.md:87-92` say the fresh tree is
  not instrumented. `src/seon/instrument.clj:180-220` implements the live
  collector/instrumenter and fresh boot reports its applied count.
- `.agents/skills/data-oriented-clojure/SKILL.md:99-102` states a startup ban
  on every `[:maybe X]`, while `resources/seon/schema/context.edn:101-120` correctly
  uses omission-by-nil for explicitly in-memory render results. The ban belongs
  to stored attributes, not all function returns.
- `.agents/skills/data-oriented-clojure/SKILL.md:123-127` cites fresh
  `seon.db/transact!` and an `async-unwrappable?` exemption that do not exist
  in `src/`.

The skills' presence-not-kinds and omit-stored-values guidance is otherwise
consistent with the fresh schemas (`datahike` lines 42-66 and
`data-modeling` lines 23-41).

## Owner

`.agents/skills/datahike`, `.agents/skills/data-modeling`, and
`.agents/skills/data-oriented-clojure`.

## Acceptance

All three skills teach EDN schema authoring through `schema.edn/load!`, the
actual activation/instrumentation path, direct fresh Datahike mechanics, and
the stored-vs-in-memory omission distinction. Their examples point to current
fresh source and contain no nonexistent facade or retired instrumentation
claim.
