---
type: issue
status: open
severity: friction
tags: [issue, render, datahike, architecture]
---

# The walk's reverse-ref read follows non-ref long attributes

## Problem

`seon.render.walk/reverse-refs` finds "every entity that POINTS AT this one"
with an unbound attribute position:

```clojure
(d/q '[:find ?source ?attribute
       :in $ ?target
       :where [?source ?attribute ?target]]
     db eid)
```

An entity id is an ordinary long. The clause therefore also matches any
`:db.type/long` datom whose **value** happens to equal that entity id, and the
walk then renders that datom as a connection and recurses into an unrelated
entity.

## Evidence

`tmp/render-invalidation/selector_sweep_probe.clj` §1 — one agent, 200 real
`:seon.cluster.message/to` refs, plus a single long datom whose value is the
agent's entity id:

```
agent eid                              157
generic reverse hit count              201
attributes returned                    #{:probe/coincidence :seon.cluster.message/to}
matched the NON-ref long attribute?    true
```

Entity ids are small dense longs and Seon stores plenty of longs — token
counts, sizes, `:seon.render.block/*` numbers, and `:seon.cluster.agent/eid`,
which literally stores an entity id as a long. This is not an exotic
collision.

The same line is also the root of the invalidation problem: its dependency
plan is `:all` (`query.cljc:2549-2566`), so every walk-derived block is stale
on every commit
(`docs/prds/sci-execution-runtime/research/render-invalidation-falsification-2026-07-31.md`
§B4).

## Owner

`src/seon/render/walk.clj:203-235` (`reverse-refs`), used by `refs`
(`:289-315`) at every node of every walk.

## Acceptance criteria

The reverse read names its attributes, derived from the schema population
rather than a hand list: every attribute whose bridge declaration is
`:db/valueType :db.type/ref` (31 in the current population, 26 inside entity
families), intersected with `(:schema db)` at the render's basis. A reverse
pull `{ns/_attr [:db/id]}` over those attributes returns only real refs and
carries a concrete dependency plan (27 attributes, measured). Cost and the
recommended narrowing shape — per-family ref subsets rather than all 26 at
every node — are measured in the falsification report's §C.

A regression asserts that a long datom whose value equals an entity id does
not appear as a connection of that entity.
