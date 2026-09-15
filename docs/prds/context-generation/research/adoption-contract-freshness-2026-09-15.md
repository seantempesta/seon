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

### Cleanup and delivery boundary at the first checkpoint

The live scratch JVM PID 32129 was stopped through the operator from its
creating checkout. It reported `flock free; roster readable (3 branches)`.
The scratch root and checkout were then deleted without following their
symlinks. The test subprocess and parent test JVM have exited; its test-fast
snapshot was automatically removed. No default lifecycle operation occurred.
The source root owning a process matters to the operator's process-claim
census: cleanup from the main checkout initially found no claim; cleanup from
the creating checkout correctly stopped the exact process.

At that checkpoint, no production fix, final `bin/test` gate, or platform gate had been claimed.
The then-pending owner choice was required by the quoted design gate, not by a
foreign test failure. Shared uncommitted edits to `src/seon/instrument.clj`
are preserved; this lane had not yet edited that file.

## Owner decision and implementation

The orchestrator selected convergence-only freshness with one immediate retry
of the final source-change refusal. No rollback or quiescence was requested.

The instrumentation owner now compares the current authored form with the
form stored on the wrapper through one `current-wrapper?` predicate, used by
both pending selection and `arm-var!`. A replacement wrapper is installed by
`alter-var-root`, retaining the original callable. The compiled-wrapper cache
key includes the authored form, so retaining a callable object cannot reuse
a validator compiled for its old declaration. Unchanged arming retains the
wrapper identity. Named-contract refusal messages include the schema key.

The adoption owner marks only its final filesystem-digest refusal with
`:seon.error/diagnostic-cause :seon.cluster/source-changed-during-adoption`.
`refresh-source!` catches that precise refusal and reruns publication/adoption
once, obtaining fresh source and schema inputs. A second such refusal is
reported unchanged; other failures are never retried by this change.

Rollback and quiescing are unnecessary for the selected convergence guarantee:
`seon.fn/index!` reconciles from the last **adopted** source database and includes
actual transaction changes, so another full reconciliation already repairs
the observed mismatch. The retry closes the ordinary wait for the next edit
without introducing a second reconciliation or publication mechanism.
It does **not** make concurrent agent/render calls observe an atomic whole
program, nor guarantee convergence when files change during both attempts.
Those remain in the open adoption issue.

Two regressions cover this decision: metadata-only re-arming against the same
projection and original callable, plus the real child-JVM adoption sequence.
The latter verifies one forced source change heals automatically, the adopted
stamp matches the returned publication. The retry bound is explicit in the
production loop: its sole recur changes `retry?` from true to false. An explicit namespace is now supplied to SCI evaluation
in this fixture while checking the previously observed lookup log.

### First fixed iteration

The metadata-only regression passed on 2026-09-15 at 18:28:45Z. The child
sequence initially included resource-only and repeated-refusal probes as well
as the requested regression. Its buffered subprocess exceeded the runner
300-second reporter-silence bound (exit 124). Those extra adoptions were
removed from the recurring regression; the recorded live probes retain the
resource-only evidence. The final regression focuses on changed SCI contracts
and one forced refusal followed by automatic convergence.

### Final source ownership

- `src/seon/instrument.clj:526`: compiled-wrapper cache identity includes the authored form.
- `src/seon/instrument.clj:545`: one current-wrapper predicate compares Var and authored form.
- `src/seon/instrument.clj:575`: wrapper metadata records that form; `:633` uses the same predicate for pending selection.
- `src/seon/cluster.clj:1992`: only the final digest refusal receives the retry cause.
- `src/seon/cluster.clj:2042`: the existing refresh lock encloses the two-attempt loop.
- `src/seon/cluster.clj:1876,1914` and `src/seon/fn.clj:2010–2034`: reconciliation uses the adopted database and actual changed transaction entities, explaining why the next attempt heals.

No changes were made to the protected `src/seon/cluster/source.clj`.

The first isolated gate hit its 270-second task-exchange bound during real
source analysis; a thread probe identified `stable-manifest` under the forced
retry call. Its redundant standalone adoption was combined with the refusal
sequence: old boot contract → refused third contract → automatically adopted
new contract. This keeps both required behavioral assertions in one sequence.
The confirmation run was stopped through its own launcher (exit 143).

### First completed fixed child proof

The combined child regression passed in **206,207 ms** in gate `run.2pMe5C`
(18:47:57–18:51:24Z). This is whole child setup/adoption/cleanup time, not
one adoption latency. The gate remained red because an existing projection
test lost a filestore key during shared-base acquisition; isolated confirmation
passed. This repeats [the existing fixture issue](../../../seon/issues/parallel-test-base-connect-can-lose-a-filestore-key.md),
not a demonstrated contract failure. The final named-path gate uses
`SEON_TEST_WORKERS=1`; no foreign session or source file was changed.

## Passing named-path gate

```sh
SEON_TEST_WORKERS=1 bin/test --paths src/seon/instrument.clj src/seon/cluster.clj test/seon/instrument_test.clj test/seon/adoption_contract_freshness_test.clj -- seon.instrument-test seon.adoption-contract-freshness-test
```

Completed 2026-09-15 18:58Z: **26 tests / 124 assertions / zero failures /
zero errors**, exit 0. Child setup/adoption/cleanup took **221,788 ms**; the
coordinator-and-tests phase took 258 seconds. The successful root
`run.Wnv41k` was automatically removed. The earlier failed root `run.2pMe5C`
was removed only after checking that no JVM held it.

## Platform boundary and isolated continuation

The owned-path platform gate ran **85 tests / 514 assertions**, with six
failures in the existing launcher fixture and no errors. Its exact subprocess
refusal was `bin/test: line 405: bin/_test-slot: No such file or directory`.
Isolated confirmation reproduced it. This is the existing
[missing-helper issue](../../../seon/issues/test-launcher-fixtures-omit-required-helpers.md);
the test-provenance lane already has a shared uncommitted correction, which
was excluded from this lane’s snapshot. Persistent-result recording also
reported the operator’s 30-second prepl-response silence bound.

To continue without modifying that lane’s file or session, a detached checkout
`tmp/adoption-freshness-gate-wt` at `ca5edcec0` received the four owned files,
a reference-code symlink, and one fixture-only addition: copy `bin/_test-slot`
next to `bin/test` in the synthetic repository. The shared fixture was not
edited, and that temporary correction is not part of the production commit.
The isolated platform result below is conditional on this named fixture
correction, not a claim that uncorrected HEAD is green.

### Isolated platform result

```sh
SEON_TEST_WORKERS=1 bin/test --paths src/seon/instrument.clj src/seon/cluster.clj test/seon/instrument_test.clj test/seon/adoption_contract_freshness_test.clj test/seon/test_runner_test.clj --platform
```

Executed in the disposable checkout: **85 tests / 514 assertions / zero
failures / zero errors**, exit 0, coordinator-and-tests 176 seconds. The
missing-helper regression passed with its original assertions. The runner
reported `persistent results NOT recorded: :seon.cluster.source/refused Test
recording requires a published current-src.` This is not durable result-fact
evidence; the complete gate log is committed alongside this note.

Final cleanup: the scratch cluster was stopped through its operator, all
lane test subprocesses exited, retained failed roots were checked for live
JVM holders before deletion, and the disposable checkout was removed. No
`default` stop, restart, refork, or adoption was performed by this lane.
