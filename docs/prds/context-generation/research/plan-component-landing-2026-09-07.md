---
title: Plan component landing evidence
date: 2026-09-07
type: research
status: active
---

# Plan component landing evidence

## Recovery checkpoint

I read `AGENTS.md`, the entity/debug curation PRD, both named plan research
notes, the four named Clojure/Datahike/testing skills, and Datahike's
`transaction.cljc` and `pull_api.cljc` end to end before implementation.

The first schema edit replaced `my.plan.edn` and `my.plan.item.edn` before the
source contracts moved with them. That removed still-referenced registry keys
and made the merged schema population unresolvable. I restored both files
byte-for-byte to `HEAD` before continuing. The recovery gate was:

```text
bin/test my.plan-test
Ran 13 tests containing 152 assertions.
0 failures, 0 errors.
```

The edit hook reported successful `:current-src` publication after the
restoration. During the earlier failed edit it also reported `ADVISORY —
current-src publication failed`; `logs/current-source-failure.log` identified a
managed-root creator mismatch between concurrent process identities. I did not
change or restart the shared development root.

## Dependency ledger

- Datahike's `retractEntity` transaction operation discovers outgoing component
  datoms and recursively retracts their referenced entities
  (`reference-code/datahike/src/datahike/db/transaction.cljc`). A precise
  `:db/retract` of a component edge is therefore the operation needed before a
  child is moved or preserved while its former parent is retracted.
- Datahike pull expands a forward component ref when no explicit subpattern is
  present; explicit nested selectors keep the renderer input contract visible.
  Reverse refs use AVET and return the owning entity maps
  (`reference-code/datahike/src/datahike/pull_api.cljc`).
- `src/seon/render.clj` currently consults `attribute-producer` only for
  `:seon.render/form`. Its AI and HTML schema stage calls `schema-producers`,
  which only discovers producers when the rendered value is a map. The plan
  unit is the cardinality-many value of `:my.plan/steps`, so the requested
  attribute defaults cannot be selected for AI or HTML by the protected owner
  as currently written.

## Protected-path requests

These requests are not implemented in this lane:

1. In `src/seon/render.clj`, make `declared-producer`, `schema-stage`, and
   `render-invocation-argument` use an attribute's declared producer for all
   authored projections, not only `:seon.render/form`. When
   `:seon.render.walk/attribute` is `:my.plan/steps`, both AI and HTML must pass
   the pulled value of that attribute to the selected producer. Add a protected
   renderer-selection regression proving the selected function and invocation
   value for a cardinality-many component attribute.
2. Keep `:my.plan/intent-subjects` registered with its existing definition and
   preserve `my.plan/ready-subjects`; `src/seon/bootstrap.clj:517`,
   `src/seon/render/walk.clj:679`, and `test/seon/bootstrap_test.clj:293` consume
   that contract.
3. Before unregistering `:my.plan.item/agent`, migrate
   `test/seon/render_simplification_test.clj:98-118` to construct and pull a
   component-owned item, and migrate the debug-datom fixtures in
   `test/seon/render/web_test.clj:1134-1161` to a surviving ref attribute.
   Until those protected consumers land, the old attribute declaration must
   remain registered unchanged even after this lane stops writing it.

The whole-tree reference sweep found no protected executable reference to
`:my.plan/anchor` or `:my.plan.item/parent`; their executable references are in
the owned plan source, plan tests, fixture, and schema files. Historical PRDs,
research, and issue evidence remain dated records and are not rewritten.

## Rendered fixture evidence

Pending the component implementation and its executed test. The exact AI text
and exact HTML Hiccup value will be pasted here without normalization.
