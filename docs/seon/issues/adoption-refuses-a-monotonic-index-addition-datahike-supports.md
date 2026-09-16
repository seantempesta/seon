---
type: issue
status: resolved
severity: friction
tags: [issue, schema, adoption, datahike, cluster]
---

# Adoption refuses a monotonic index addition the Datahike fork supports

Observed 2026-09-16 adding `:seon.db/index true` to the already-installed
`:seon.issue/agent` so it could become a listened wake attribute.

## Problem

`bin/seon init --dev default` published `current-src` and then refused:

```
Cluster `default` predates the incompatible schema change for `:seon.issue/agent`
and cannot be reopened in place. `bin/seon init default --force` destroys and
reforks it from `current-src`; use export/import instead to preserve its data.
```

`seon.cluster/declaration-changes` (`src/seon/cluster.clj:888`) compares the
installed and current declaration maps with `=` and calls ANY difference
incompatible. But the vendored Datahike fork explicitly supports this exact
change: `reference-code/datahike/src/datahike/schema.cljc:277-283` —

> An index may be added monotonically to an existing attribute. The
> transactor atomically backfills AVET before publishing the resulting
> database value. Removing an index remains unsupported.

So an ACCRETIVE change (AGENTS §2.5: adding is free) forces a destructive
refork of every existing cluster, and every future listened-attribute
declaration on an existing attribute pays it. The fix is for the comparison
to use the dependency's own accretion rule rather than equality — the same
asymmetry Datahike already encodes for `:db/doc`, `:db/noHistory`,
`:db/isComponent` and `:db/index`.

Evidence and the forced reset:
[start-arms-and-wakes-2026-09-16](../../prds/steward-platform/research/start-arms-and-wakes-2026-09-16.md).

## Resolution (2026-09-16, monotonic-index lane)

`seon.cluster/declaration-changes` now compares facet by facet and derives
"compatible" from the dependency's own acceptance rule
(`accretive-property-change?`, grounded per clause in
`reference-code/datahike/src/datahike/schema.cljc:257`). An accretive change —
`:db/index` added, `:db/doc`/`:db/noHistory`/`:db/isComponent` updated,
`:db/cardinality` widened one→many on a non-unique attribute — is ADOPTED IN
PLACE by transacting the declaration through the existing `seon.db` write path,
where the transactor backfills AVET atomically. A genuinely incompatible change
still refuses, now naming the property and both values
(`… changed :db/valueType from :db.type/long to :db.type/string`) instead of
`predates`. A DROP remains a refusal: transacting the declaration cannot retract
a facet the branch still carries.

Evidence, regressions, and the live AVET probe:
[monotonic-index-adoption-2026-09-16](../../prds/steward-platform/research/monotonic-index-adoption-2026-09-16.md).
