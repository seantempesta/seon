---
type: issue
status: open
severity: friction
tags: [issue, schema, architecture]
---

# Separate declared search metadata from the process index ID

## Problem

`:seon.search/index` now declares an attribute's Lucene indexing mode as the
enum `:text | :symbol`, but the cluster search wiring still uses the same key
for a process-local Lucene index ID string. Those meanings cannot share one
globally declared key without making one side's contract false.

## Evidence

- `resources/seon/schemas/seon.search.edn:3` declares
  `:seon.search/index` as the indexing-mode enum.
- `src/seon/cluster.clj:1462-1465`, `src/seon/cluster.clj:1519`, and
  `src/seon/cluster.clj:1763-1764` put the process-local index ID under the
  same key in Flow state.
- `src/seon/search.clj:431-441` currently consumes that legacy Flow-state
  meaning. The search metadata implementation did not change the protected
  cluster-owned wiring.

The isolated `edgefaces0804` boot on 2026-08-04 exposed the live consequence
immediately after readiness:

```text
SEON CORE FAULT (dev panic): seon.search/apply-report! violated its contract
(invalid-input): [[{:value ".../derived/lucene", :message "invalid type"}]]
```

`src/seon/search.clj:213-217` names the first argument `index-id` but declares
it as `:map`; the running graph supplies the process-local string path/ID. The
schema collision is therefore not only latent registry drift: it produces a
core fault on an otherwise clean scratch boot.

The bare 2026-08-05 gate supplied two more exact consumers of the same broken
`apply-report!` path:

- `seon.search-test/an-exact-transaction-report-advances-the-index-basis`;
- `seon.search-test/message-and-instruction-content-are-searchable-by-family`.

Both errored at `src/seon/search.clj:228` while `long` cast a nil basis value:

```text
NullPointerException: Cannot invoke "java.lang.Number.doubleValue()"
because "x" is null
```

This issue's clean-boot contract-fault evidence already names that exact
function and its declared-versus-actual index-ID shape, so no separate issue
owns the two derivative test errors.

## Owner

The cluster search wiring owner after the stop-retry lane releases
`src/seon/cluster.clj`.

## Acceptance

- Declare a separately named string key such as `:seon.search/index-id` for
  the process-local resource.
- Move the cluster graph and `seon.search/index-step` to that key.
- Keep `:seon.search/index` exclusively as the Malli declaration property
  whose values are `:text | :symbol`.
- Give `seon.search/apply-report!` the declared input shape of that same
  process-local index ID, and prove one post-ready transaction report reaches
  it without a contract fault.
