---
type: issue
status: open
severity: blocker
tags: [issue, schema, admission, performance, runtime]
---

# Resolve the declaration population once per admission, not once per node

## Problem

`seon.sci.admit` asks the declaration population **once per value node**.
`identity-only-node` (`src/seon/sci/admit.clj:141-149`) calls
`schema/identity-only-projection` for every map, vector, set, sequence, and
record it walks. That function is
`(identity-only-projection-in (shape-projection) value)`
(`src/seon/schema.clj:2696-2700`), and `shape-projection` resolves the
declaration population through `candidate-forms`
(`src/seon/schema.clj:2610-2630`). With nothing supplied on the calling
thread that falls through to `packaged-forms`, which re-lists and re-merges
every schema resource on the classpath — the exact fallback the
2026-08-07 declaration-population family was closed against
([archived owner issue](archive/packaged-forms-rereads-every-schema-resource-per-call.md)).

The generation atom in front of it does not help: it compares the resolved
forms with `=` AFTER paying for the resolution, so it saves rebuilding the
projection and saves nothing at all on the read.

This is the dominant surviving member of the class, and it sits on the
admission seam that every agent turn result, every effect result, every
recorded error value, and every MCP eval passes through.

## Evidence

Measured live 2026-08-08 on the running `default` cluster JVM (read-only
probes through `mcp__seon__eval_clj`, session `audit`).

One resolution costs a full resource merge:

```clojure
{:declaration-population-ms 13.30
 :declaration-projection-ms 13.78
 :second-population-ms      14.28
 :population-count          1885}
```

`identity-only-projection` on a trivial map pays that in full, per call:

```clojure
{:identity-only-projection-ms 13.95
 :shape-projection-ms         14.92}
```

Admission cost is therefore linear in NODE COUNT, not value size. Counting
the fallback occurrences attributed to `admit.clj:143` around each call:

```clojure
{:scalar  {:ms   0.05, :fallbacks  0}
 :vec50   {:ms  13.72, :fallbacks  1}    ; (vec (range 50))
 :nested  {:ms 382.31, :fallbacks 22}}   ; {:rows [20 small maps]}
```

A twenty-one-map result costs **382 ms of re-reading schema resources**.

The process-wide counter confirms the volume. After roughly one hour of an
ordinary `default` cluster's life, `@#'seon.schema/!fallback-counts` read:

```clojure
{"seon.sci.admit (admit.clj:143)"    54884
 "malli.core (core.cljc:2196)"       23357
 "malli.core (core.cljc:209)"         2238
 "seon.db (db.clj:361)"               1159
 "malli.registry (registry.cljc:58)"  1010
 "seon.sci.admit (admit.clj:405)"      270
 "seon.error (error.clj:310)"          244
 "seon.sci.admit (admit.clj:481)"      136
 "seon.schema.datahike (datahike.clj:265)" 95
 "seon.schema.datahike (datahike.clj:418)" 94
 …20 callers total}
```

At the measured 13.3 ms that is ≈ 18 minutes of CPU spent re-reading 151 EDN
files, in one hour, in one idle-ish cluster — with `admit.clj:143` alone
responsible for two thirds of it.

Second site, same class, same seam: the development MCP's value projection
resolves the population four more times per call —
`seon.cluster/mcp-project`'s `admit/admit-value`, `render.value/artifact`,
`render.value/artifact-edn` and `mcp-valf`'s `admit/canonical-edn`
(`src/seon/cluster.clj:292,298,299,371`) — so **every** orchestrator or agent
MCP eval pays ~54 ms of pure resource merging before its value is rendered.

## Owner

`seon.sci.admit/project` and `identity-only-node`
(`src/seon/sci/admit.clj:141-149`), with `seon.schema/shape-projection` and
`identity-only-projection` (`src/seon/schema.clj:2610-2700`) and the MCP
projection sites in `seon.cluster`.

## Acceptance criteria

- One admission resolves the declaration population **exactly once**,
  whatever its node count — the population (or the projection carrying it)
  is threaded through `project`/`admit-value` the way `seon.db`'s decode
  walkers already thread `read-declarations` (`src/seon/db.clj:362-390`),
  and the per-item entry points that resolve it themselves become
  population-taking (`-in`) questions.
- The class regression counts reads at the one seam and asserts a constant
  count across item counts — the shape
  `test/seon/schema/declaration_population_test.clj` already uses — extended
  to admission, so `{:rows [20 maps]}` performs one resolution rather than 22.
- `seon.cluster/mcp-project` supplies the cluster's projection once for the
  whole projection pass.
- Re-running the counter probe above over a real turn shows
  `admit.clj:143` at single digits rather than tens of thousands.

Root repair (the one that makes the class unwritable rather than merely
cheaper) is the seon.env Phase 3 sweep: admission receives
`:seon.schema/projection` from the environment it is already handed, and
`shape-projection`'s process-global generation atom disappears with the rest
of the derived-state slots
([PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)).
The threading above is worth landing first because it is measurable today
and does not wait on Phase 3.
