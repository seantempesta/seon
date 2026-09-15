---
type: research
status: active
tags: [runtime, schema, adoption]
---

# Adoption contract freshness — 2026-09-15

## Assignment and reading

Read AGENTS.md, development-adoption-can-mix-host-and-sci-generations.md,
the last six dated working-edge sections, and the repl,
data-oriented-clojure, and clojure-testing skills end to end.
Scope: isolated `tmp/adoption-freshness-root`, cluster `freshness`;
`default` is never mutated by this lane. Production ownership is
`src/seon/instrument.clj` and the adoption section of `src/seon/cluster.clj`.
`src/seon/cluster/source.clj` is protected.

## Dependency ledger

- Malli `reference-code/malli/src/malli/instrument.clj:8,44`: original
  function and authored schema derive from function/Var metadata.
- SCI `reference-code/sci/src/sci/core.cljc:345`: fork copies SCI environment;
  `src/seon/sci/eval.clj:957` binds forwarding host Vars, so an existing
  context sees replacement host roots.
- Existing real adoption child-JVM fixture:
  `test/seon/schema_redeclare_test.clj:14`; isolates namespace reload from
  the shared test worker, uses canonical published root.
- Adoption owner `src/seon/cluster.clj:1868`; instrumentation owner
  `src/seon/instrument.clj:473`.

## Initial observations

`bin/seon status` reported default alive (PID 23729). MCP runtime status
returned `health unknown`, `flow unknown`, `Read timed out`; this is an
unavailable observation, not cluster health. The owner then reported that
its MCP bridge was reconnected after the app restart. No workaround sender
was created.

Scratch `start freshness` initially refused because its empty root had no
`current-src` publication. Proceeded with scratch `init` before start.

The first baseline adoption reached `development SCI acquisition` and
`development JVM instrumentation`, then refused with
`Source changed during development adoption; the next edit must converge it.`
Publication was `6aa98747-b396-55e2-8811-4e27f67e19f6`. Its host probe Var
already declared `:my.adoption-freshness/old-request`. The shared tree changed
during this run; no cause was attributed to a particular lane. Continued in
`tmp/adoption-freshness-wt` at HEAD `806659e06`, with `reference-code` linked,
as authorized by the assignment. The disposable probe files were moved there
and removed from the shared source tree. Original log:
`tmp/adoption-freshness-baseline.log` (relevant phases will be retained below).

## Reproduction results on the isolated checkout

The side-effect-free function returns `:my.adoption-freshness/value` from
its request. `old-request` accepts a string; `new-request` accepts a string
or integer. The authored output accepts a string or integer. No Juniper
seed or provider request is needed for this contract boundary.

| Variant | Operator elapsed | Result |
|---|---:|---|
| (a) authored old-request → new-request | 75.04 s | Converged; JVM and SCI both accept 42. Initial old contract rejects it on both paths. |
| (b) deterministic source change before loaded definitions | 92.81 s | Refused after SCI acquisition and JVM instrumentation. Database spec names third-request, host metadata names new-request; adopted source stamp remains the previous commit. |
| (b) converging follow-up | 74.21 s | Converged; database and host both name new-request; JVM and SCI both accept 42. |
| (c) schema-resource-only widening of new-request to include keyword | 81.14 s | Converged; reloads my.adoption-freshness. Both paths accept the keyword input and then correctly refuse the unchanged output contract. |

Exact MCP forms/envelopes: [structured transcript](adoption-contract-freshness-2026-09-15.json).
Exact adoption phases (only analyzer warnings omitted):
[phase transcript](adoption-contract-freshness-phases-2026-09-15.log).

Variant (b) was forced through the existing progress event, not timing:
at `development loaded definitions`, a one-shot wrapper around
`seon.cluster/report-source-progress!` restored the previous source bytes
and immediately restored the original reporting function. The published
contract named `third-request`; `require :reload` then read the restored
file declaring `new-request`. The final digest check refused, as intended.

The observed run-9 failure **after convergence has not been reproduced**
by (a), (b)'s converging follow-up, or (c) at this checkout. It would be
incorrect to claim these probes establish its historical cause.

## Established causes and boundaries

- `src/seon/cluster.clj:1883-1918` mutates declarations/program facts before
  loading source; `:1965` reloads from the mutable filesystem. The final
  digest check at `:1990` refuses after those changes. It does not roll
  them back. The mixed state after refusal is deterministically reproduced.
- `src/seon/fn.clj:2010-2034` already compares desired rows against the last
  adopted source database, and includes transaction changes. That explains
  why the subsequent full reconciliation reloaded the probe correctly; a
  claim that it only considers the current mutable database is false here.
- Independently, a metadata-only JVM probe verifies that `apply!` preserves
  an existing wrapper even when its Var's current authored schema differs.
  With current metadata changed to `old-request`, `apply!` kept the identical
  wrapper and unscoped `(value {… 42})` still returned 42. Metadata was restored
  in `finally`. This is explicitly **not** an adoption reproduction.
  `src/seon/instrument.clj:498-520` captures authored schema and packaged
  forms, and `:579-600` selects pending work solely by the wrapper marker.
  `:477` also omits authored schema from the compiled-wrapper cache key.

## Required design decision

Re-arming wrappers does not undo database publication before a refused reload.
The owner was asked to select refusal rollback, broader quiesced generation
publication, or a convergence-only scope under AGENTS.md's design gate.
No production edits have been made while that decision is pending.

## Regression work in progress

One child-JVM regression uses a copy of the complete test checkout, canonical
published-root fixture, real `cluster/start!`, `cluster/refresh-source!`, armed
contracts, and `seon.sci.eval/evaluate`. A progress event forces the same
refusal and checks that database and JVM contracts agree afterward.

Initial fixture iterations exposed missing copied docs, then an unhanded
projection in the fixture's `config/effective` call. Both fixture problems
were corrected; neither result is a proof of production behavior. One test
invocation is run at a time. Gates and final regression result remain pending.

### Exact initial probe source

```clojure
(ns my.adoption-freshness)
(defn value
  "Return the request value for the isolated adoption contract probe."
  {:malli/schema [:=> [:cat :my.adoption-freshness/old-request] [:or :string :int]]}
  [request]
  (:my.adoption-freshness/value request))
```

```clojure
{:my.adoption-freshness/value [:or :string :int]
 :my.adoption-freshness/old-request
 [:map [:my.adoption-freshness/value :string]]
 :my.adoption-freshness/new-request
 [:map [:my.adoption-freshness/value [:or :string :int]]]}
```

Variant (a) replaces only `old-request` with `new-request` in the function
contract. Variant (c) adds `:keyword` to the new request's `:or` in the schema
resource only. Variant (b) adds `third-request` with that same widened shape,
publishes a source reference to it, then restores the previous source at the
named progress event. All operator adoption invocations were:

```sh
/usr/bin/time -p bin/seon --root /Users/sean/src/seon/tmp/adoption-freshness-root init --dev freshness
```

They ran from `/Users/sean/src/seon/tmp/adoption-freshness-wt`.

### Armed class regression reproduced the defect

`bin/test-fast --paths test/seon/adoption_contract_freshness_test.clj -- seon.adoption-contract-freshness-test`
completed at 2026-09-15T18:21:42Z. One test / three parent assertions;
two assertions fail because the child exits on the intended assertion:

```text
Assert failed: A refused adoption must leave host and database contracts in the same generation.
```

Before that assertion, the real child adoption accepted 42 through SCI under
the widened contract, refused false with the new schema key in the refusal
value, forced the source-change refusal, and verified the adopted source
commit had not advanced. The class regression is a test-first draft under
`test/seon/adoption_contract_freshness_test.clj`; it is not a passing gate and
is not committed as a completed repair. Its original failing log was
`tmp/adoption-freshness-test-before-3.log`.

The child also emitted `Expected number or lookup ref for entity id, got
"my.agents.root"` during adoption. That log is not the assertion failure;
no cause is attributed from the string alone.

### Cleanup and current delivery boundary

The live scratch JVM PID 32129 was stopped through the operator from its
creating checkout. It reported `flock free; roster readable (3 branches)`.
The scratch root and checkout were then deleted without following their
symlinks. The test subprocess and parent test JVM have exited; its test-fast
snapshot was automatically removed. No default lifecycle operation occurred.
The source root owning a process matters to the operator's process-claim
census: cleanup from the main checkout initially found no claim; cleanup from
the creating checkout correctly stopped the exact process.

No production fix, final `bin/test` gate, or platform gate has been claimed.
The pending owner choice is required by the quoted design gate, not by a
foreign test failure. Shared uncommitted edits to `src/seon/instrument.clj`
are preserved; this lane has not edited that file.
