---
type: issue
status: open
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
