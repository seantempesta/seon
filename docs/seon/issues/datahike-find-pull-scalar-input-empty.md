---
type: issue
status: open
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
