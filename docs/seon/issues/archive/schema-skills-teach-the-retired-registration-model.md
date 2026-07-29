---
type: issue
status: resolved
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

## Resolution

Resolved by `818128ff2`. The datahike, data-modeling, and
data-oriented-clojure skills now teach the current schema system. The
clojure-testing skill and the datahike querying reference were corrected in
the same change because they are loaded by the review hook or linked from the
primary skills and repeated the retired mechanics.

The corrected guidance is grounded in the current owners:

- `src/seon/schema/edn.clj:220-240` loads the first-party schema EDN under
  `resources/seon/schema/`, and `src/seon/schema/edn.clj:87-110` derives all
  config composite schemas from the one set of config dial registrations.
- `src/seon/instrument.clj:180-220` is the live collection and
  instrumentation operation.
- `src/seon/fn.clj:21-39` admits contracted functions, schema registrations,
  and tests while omitting other function events; namespace rows remain the
  structural source container.
- `reference-code/datahike/src/datahike/api/impl.cljc:30-48` accepts both a
  transaction argument map containing `:tx-data` and a raw vector or
  sequence.

The skills state the omission ruling verbatim: "`[:maybe]` is allowed in
in-memory function RETURN contracts (stored attributes stay nil-free — the
bridge forces absence there)." They retain presence-not-kinds as the entity
model and no longer teach a fresh `seon.db` facade, direct schema
registration, or absent instrumentation.

## Proof

`quick_validate.py` passed for all four updated skill directories. A focused
JVM probe against a fresh in-memory test database returned
`{:map-form true, :vec-form true}` after calling `datahike.api/transact` once
with `{:tx-data []}` and once with `[]`.
