---
type: issue
status: open
severity: blocker
tags: [issue, schema, admission, performance, runtime]
---

> **2026-08-08 — the per-node shape is dead at the admission seam.**
> `seon.sci.admit` now resolves ONE declaration projection per admission and
> threads it to `schema/identity-only-projection-in`; the class regression is
> `test/seon/sci/admit/declaration_population_test.clj`. Measured below.
> Two remainders keep this issue OPEN, both belonging to owners this lane does
> not hold: the identity-only descriptor cache still lives in a process atom
> (`seon.env` Phase 3's declared work) and `seon.cluster/mcp-project` still
> resolves the population four times per call.

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

## Repair landed 2026-08-08 — one projection per admission

`src/seon/sci/admit.clj`. `admit*` puts one `admission-declarations` **delay**
in the walk state and `identity-only-node` asks
`schema/identity-only-projection-in` with it. A delay for the same reason
`seon.db/read-declarations` is one: the commonest admission is a scalar, which
asks no identity question at all, and resolving eagerly would have made every
result pay a population for a question it never asks. The delay resolves to a
projection already supplied on the calling thread when there is one
(`schema/current-projection`), which is what makes the target state free
rather than merely bounded — the same object across admissions keeps the
compiled descriptors warm too.

No `seon.schema` change: `identity-only-projection-in` already existed, and
`schema.clj` was held uncommitted by a sibling lane at the time.

### Measured

Load-only, `clojure -M:dev`, reads counted at the one read seam
(`schema.edn/read-schema-resource`); one unbound population is 152 reads.
Wall times from `tmp/repro/admit_population_cost.clj`, read counts from the
class regression run against both shapes.

| Admitted value | Before | After |
|---|---|---|
| scalar | 0 reads / 0.15 ms | 0 reads / 0.08 ms |
| vector of 50 ints | 6,384 reads | **152 reads** |
| `{:rows [20 maps]}` | 3,344 reads / **374.68 ms** | **153 reads / 23.95 ms** |
| vector of 100 maps | 9,880 reads / **1,019.42 ms** | **153 reads / 22.05 ms** |
| set of 40 maps | 6,273 reads | **153 reads** |
| lazy sequence of 60 maps | 9,333 reads | **153 reads** |
| six-deep nested maps | 1,071 reads | **153 reads** |
| `{:rows [20 maps]}`, projection supplied | 0 reads | 0 reads |

Per-node cost of the identity question itself, isolated (200 iterations):
ambient with nothing supplied **15.263 ms**, threaded through one stable
projection **0.012 ms**.

The identity projections are unchanged — `:seon.db/database-value` and
`:seon.db/connection` produce byte-identical identity data through the
threaded arity as through the ambient one.

### The class regression

`test/seon/sci/admit/declaration_population_test.clj` counts reads at the one
read seam and asserts every admission performs exactly ONE resolution
whatever its node count, that an admission asking no identity question
resolves nothing, and that a supplied projection makes it free. Non-vacuous by
construction and by observation: reverting `identity-only-node` to the ambient
ask fails five of its six node-count cases with the read counts in the table
above.

### Remainders (why this issue stays open)

1. **The identity-only descriptor cache is still a process atom.**
   `!identity-only-generation` (`src/seon/schema.clj:2647-2695`) keys on
   `identical?` projection, so an admission that had to build its own
   projection rebuilds the descriptors — ~1 ms, against the ~14 ms population
   it is already paying, and zero once a projection is supplied. Moving that
   cache onto the projection value is the PRD's own declared Phase 3 work
   ("the compiled-shape and identity-only caches move onto the projection
   value"; `!shape-generation` / `!identity-only-generation` are on its
   deletion list), so it belongs to that owner, not to a second fix here.
2. **`seon.cluster/mcp-project` still resolves four times per call**
   (`src/seon/cluster.clj:292,298,299,371`). One
   `schema/call-with-projection` around the whole projection pass closes it;
   `seon.cluster` was not this lane's owned path.
3. **`seon.sci.eval/evaluation-projection` resolves one per evaluation when
   the ctx carries no projection** (`src/seon/sci/eval.clj:648-651`) — it
   falls back to `(schema/build-projection (schema/registered-schemas))`,
   which is a complete population AND a complete projection build. A live
   cluster's ctx holds projection state so this is the test path, but it is
   the same class: `bin/test seon.sci.eval-test` logged it reaching 100
   occurrences (2026-08-08), alongside `seon.schema/valid-candidate-value?`
   asked per generated evaluation at `test/seon/sci/eval_test.clj:1331`.
   Neither is this lane's owned path.

Root repair (the one that makes the class unwritable rather than merely
cheaper) is the seon.env Phase 3 sweep: admission receives
`:seon.schema/projection` from the environment it is already handed, and
`shape-projection`'s process-global generation atom disappears with the rest
of the derived-state slots
([PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)).
The threading above is worth landing first because it is measurable today
and does not wait on Phase 3.
