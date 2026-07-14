---
type: issue
status: resolved
severity: friction
tags: [issue, database]
---

# Datahike find-pull loses a scalar input entity

## Problem

A find-pull query returns no rows when the entity is supplied as a scalar
`:in` binding, even though the equivalent ordinary find proves the entity and
attribute match.

## Evidence

During the 2026-07-14 resource-budget work, maintained Datahike returned `[]`
for `[:find (pull ?e [:x :y]) :in $ ?e :where [?e :x]]` with the database and
eid `1` as inputs. The equivalent ordinary find returned `#{[1 0]}`, and an
unbound find-pull query returned the expected pull row. The budget inheritance
test now uses a unique constant predicate so it does not hide this independent
query defect.

## Owner

The maintained Datahike query find-pull post-processing path.

## Acceptance

Scalar and collection `:in` entity bindings produce the same find-pull value
as the equivalent constant-predicate query on planned and legacy paths. Add a
cross-platform regression and prove ordinary find, scalar find-pull, and
unbound find-pull agree without weakening resource-budget inheritance.

## Resolution

Maintained Datahike commit `6f90b339` prevents the planner's direct-relation
fast path from handling a fully ground group. Its zero-width tuple emitter
intentionally omits output, but a ground clause that exists must preserve one
empty relation row so constant-bound projections such as `pull` can return the
`:in` value. Those queries now fall back to the existing relation engine; no
second execution path was added.

The portable regression covers scalar and collection entity inputs with both
planned and legacy execution. The focused JVM checkpoint passes three tests
and 12 assertions, and the Node ClojureScript checkpoint passes 105 tests and
825 assertions. The fork commit is pushed on `sync-upstream`, and both Seon
dependency surfaces are pinned to its full SHA.
