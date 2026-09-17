---
type: research
status: open
created: 2026-09-18
tags: [errors, instrumentation, contracts]
---

# Wrapper enforcement: recording dependency

**Current status, SCI resume:** host and SCI wrappers share enforcement and
acquired recording. Full verification is not green. The latest section records
SCI acquisition, regressions, timings and the remaining declaration dependencies.
Earlier sections preserve the historical stops, not the current inventory.

Slice 2 is **not implemented**. The lane stopped before production edits at
the missing acquired recording/disposition operation. Source inspection used
`steward-platform` HEAD `1e0e17d717cf50bd0451272407541b352ba44160`.
This is an interface dependency, not a foreign lane's in-flight test failure.

## Seams

The [error-entities PRD](../plan/error-entities-prd-2026-09-17.md) §§4.1/5.2
requires a recorded flat refusal returned under `:record`. Its disposition
helper is described, but its callable interface is not implemented here:

- `src/seon/instrument.clj:507–547`: `wrap-interpreted` receives function,
  contract, projection, mode, caps and original callable. `:record` returns
  the original. Neither it nor `compiled-wrapper` at line 609 receives a
  recording operation.
- `src/seon/instrument.clj:679`: the shared host wrapper captures projection
  and caps. `apply!` preserves shared host wrappers across cluster mode changes.
  A cluster-local mode cannot simply be captured on the shared Var.
- `resources/seon/schemas/seon.env.edn:3` and `src/seon/effect.clj:197` carry
  environment and mode, but no recording operation.
- `src/seon/flow.clj:981` receives `commit-fault!`, `commit-drop!`, `panic!`
  and mode reader as proc args. `start-error-fanout!` at line 1164 supplies
  that custody to the graph, not a record-and-return operation to wrappers.
- Private `src/seon/cluster.clj:2955` `commit-fault!` needs connection,
  cluster, process, caps and fault. Those arguments are not supplied to
  `wrap-interpreted`. Selecting them from a global cluster lookup would
  violate acquired custody.
- `src/seon/error.clj:1552` `recording` prepares transaction data; it does
  not commit it. `commit-call` at line 1475 still selects legacy occurrence
  fields at lines 1499–1514. Preservation of the new base/facet entity is
  recorder-slice work under PRD §4.2.

Dependency source: `reference-code/malli/src/malli/core.cljc:2213–2222`
calls the reporter and continues into the body when it returns. Thus a
nonthrowing reporter is insufficient. Throwing into Flow transports a fault
but does not satisfy this invocation's required flat return under `:record`.
No second recorder, queue, global lookup or callback API was introduced.
The `0a58c769d` `kernel/with-arm` boundary was not changed.

## Live evidence

`bin/seon status` reported default alive at PID 80593. MCP runtime status
answered with plumbing ping replies, three error signatures, six errored
evaluations and eleven failed tests. These were inherited observations;
adoption freshness was not established.

The [retained lexical probe](error-wrapper-record-probe-2026-09-18.clj)
ran in MCP JVM mode on that process, with no definitions or cluster writes:

```clojure
{:probe/identical-original true
 :probe/invalid-input {:probe/body-ran true :probe/arguments ["invalid"]}
 :probe/invalid-arity {:probe/body-ran true :probe/arguments []}}
```

The first attempt incorrectly supplied empty caps and the outer wrapper
refused missing `:seon.config.eval.result/max-bytes`. The retained successful
form uses `config/result-caps` over `config/defaults`. This proves the existing
bypass only, not SCI execution or a new implementation. The script's explicit
requires were added for standalone lint; the live form used the already loaded
namespaces. Its initial write was blocked for missing requires, then corrected.

## Resume options

1. **Recommended: the recorder/environment owner carries the existing
   committer operation into the wrapper.** Guarantee: bounded recording with
   provenance and an acknowledged outcome, plus per-call mode. Cost:
   coordinated environment/boot/admission/recorder changes. Give up landing
   the complete behavior solely within the four assigned source/test files.
2. Expand this assignment to implement that acquisition and recorder seam.
   Guarantee: integrated both-dial proof. Cost: cross-owner work involving
   held acquisition callers. Give up independent bounded slice ownership.
3. Stage only pure matching and panic enforcement. Guarantee: partial code;
   no record-and-return completion. Cost: later integration. Give up the
   requested both-dial acceptance proof. This lane did not silently choose it.

## Verification and downstream consumption

Fast tally: **not run; zero tests executed**. No production or test behavior
changed. Per-call before/after overhead: **unmeasured**, because no facet check
was installed. No green implementation, cold gate or platform proof is claimed.

Slice 3 consumes no new API: `seon.error/facets` remains to be implemented
against the supplied projection and complete owned values. Slice 4 still owns
the per-arity result-position analysis. The recorder owner must supply the
operation above before the complete `:record` requirement can be verified.

Grounding was partial: supplied AGENTS §§0–5, requested program-facts rulings,
wrapper source/tests and the relevant PRD/manifest/review/arm-leak seams were
inspected. Large literal-manifest output was truncated; this record does not
claim the requested complete end-to-end reading. Resume must finish that
grounding before production design.

No default lifecycle, reload or adoption command was issued. Foreign edits
were preserved; no foreign lane was contacted or operated. No test JVM,
worktree or scratch root was created. The
[dependency issue](../../../seon/issues/instrumentation-record-mode-has-no-acquired-fault-recorder.md)
records the unresolved work.

The edit hook automatically queued publication
`b787999a-3451-4660-a18c-3a2c0fa19b1b` for these documentation/probe files.
Its recorded feedback refused during init preflight with
`:seon.operator.subprocess/deadline-exceeded`; no successful adoption is
claimed. The lane issued no publication retry or lifecycle workaround.
The markdown hook also reported 44 existing repository citation issues,
including stale dependency gitlinks in
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`.
Those unrelated files were left unchanged. `git diff --check` passed.

## Resumed assignment: acquired operation accepted, held SCI arm owner

The owner accepted option 2 with a narrower construction rule: acquire the
existing recording operation at arm time; `:record` construction without it
must return a typed refusal, while `:panic` needs none. Successful recording
must preserve the flat refusal and provenance and the invocation must return
that same value. This supersedes the pending options above.

On resume, HEAD was `e0b8146cd53a0df8b8378f5cdc232d2a4ef92f85`.
The specifically requested PRD §5.2 and §6 ownership row 2 were read in full
with bounded `sed` ranges, as were both the historical “What slice 2 consumes”
and the later authoritative “What slice 2 consumes now” sections. The latter
confirms the additive manifest is available; it is not a dependency refusal.

**Next explicit stop boundary: SCI-side arm request in held `eval.clj`.**
The resumed assignment explicitly says to stop at that item if the SCI-side
arm request lives in this file. It does, and `git status` confirmed it dirty.
The exact current hunk is `src/seon/sci/eval.clj:674–685`:

```clojure
(defn- install-function-contract!
  [ctx committed projection db]
  (when-let [spec-edn (:seon.fn/spec committed)]
    (let [function-symbol (:seon.fn/sym committed)
          sci-var (sci/resolve ctx function-symbol)
          {:keys [:seon.config/on-core-error :seon.sci.admit/caps]}
          (instrumentation-config db)]
      (sci/bind-root!
       ctx sci-var
       (instrument/wrap-interpreted
        function-symbol spec-edn projection on-core-error caps @sci-var))))
  nil)
```

The foreign diff changes the function-symbol binding at line 677 in this same
hunk. No edit, restoration or lane message was made. An isolated HEAD overlay
would exclude that edit but would not grant ownership of this call site.

The actual existing committer in this snapshot is private
`seon.cluster/commit-fault!` at `src/seon/cluster.clj:2955`, not
`seon.error/commit-fault!`. Development acquisition already constructs its
closure at `cluster.clj:2344–2358` and supplies it as
`:seon.flow/commit-fault!`; the Flow fault path supplies the same owner at
`cluster.clj:3270–3273`. This is the operation to reuse.

The propagation gap is precise: `seon.sci.eval/acquire!` at lines 2101–2117
accepts that callback but calls `base-ctx` with only the database. It uses the
callback for `record-acquisition-refusals!` only after generation has completed.
`base-ctx` at lines 2083–2099 constructs/installs the program without the
callback. `install-function-contract!` callers at lines 710, 873 and 2925
likewise supply only ctx, row, projection and database. The held owner must
thread acquired recording custody through construction and subsequent
installation before requiring it at the `:record` wrapper seam.

The canonical armed-cluster test was read at
`test/seon/cluster/armed_test.clj:338–379`: it injects a proc failure through
`turn/next-agent-work`, wakes the real graph and awaits committed error facts.
`test/seon/test_support.clj` has no named `commit-fault!` helper in this
snapshot. No fake committer was substituted and neither held test file was
edited. The environment declarations and `env.clj` also do not currently
carry the named committer operation; the callback presently travels through
acquisition and Flow requests.

The host arm call at `cluster.clj:2505–2509` is within the expanded ownership,
but was left unchanged at the explicit SCI-side stop. No production subset
is presented as completion of items 1–4. Fast tally remains **not run** and
before/after overhead remains **unmeasured**. `git diff --check` is the
verification for this documentation-only update; cold/platform proof remains
orchestrator-owned. No new runtime probe, default operation, worktree, test
JVM or scratch root was created during this resumed turn.

## Host-only implementation — 2026-09-18

The owner subsequently deferred SCI and authorized completing the host lane.
Commits `09869c8f1` and `e42f494ab` contain the implementation below. This is
a dependency checkpoint, **not a green landing**.

### Implemented seams

- `src/seon/error.clj:1693,1712`: contracted `facet-keys` and `facets` derive
  canonical base extensions and validate complete values. Catalogue, narrowed
  candidate projection and validators are memoised on the supplied projection;
  no value-membership cache or global facet registry was added.
- `src/seon/instrument.clj:596,702`: result-position references, aliases,
  `:or`/`:orn`, `:and`, `:multi` entries, `:schema` and `:maybe` derive per-arity
  permissions. Declared sets and base permission are captured once by the
  compiled callable. Maps/collections are terminals, so a nested facet name
  does not grant permission. Actual base-shaped returns undergo facet checking
  even when ordinary Malli output validation accepts them. Exact arity precedes
  variadic selection; another arity's permission is not used.
- `src/seon/instrument.clj:648,823,871`: input/arity reports abort before the
  body. Boundary values validate against `:seon.instrument/refusal-result`,
  with separate contract, arity and undeclared-return observations. They never
  re-enter the body's result contract. Panic throws the flat value; record
  invokes its acquired operation and returns the identical value only after
  `:seon.flow/committed`. A recording failure throws with that refusal retained;
  it is not presented as successfully recorded. Public record-mode acquisition
  refuses a missing operation before mutating roots.
- `src/seon/cluster.clj:2505`: host rearming supplies the existing private
  `commit-fault!` closure with connection, cluster, caps and advertisement-derived
  process identity. `src/seon/test/arm.clj:167` forwards an operation present on
  its decision. The shipped worker decision is panic and needs none; there is
  no fabricated worker connection. `seon.instrument/request` now admits the
  existing `:seon.flow/commit-fault!` member. No environment member was invented.
- `src/seon/error.clj:1485`: the existing writer transaction preserves the
  source's validated base/facet members on the occurrence. Replacing an
  occurrence retracts prior facet attributes with Datahike's existing
  `:db.fn/retractAttribute`, including owned components
  (`reference-code/datahike/src/datahike/db/transaction.cljc:1073`).
- `src/seon/sci/admit.clj:536`: the generic semantic reconstruction helper now
  explicitly enumerates base and the 63 canonical facets. This free source
  path was a necessary scope addition: its previous arbitrary-value contract
  refused reconstructed errors during recording. A regression compares the
  declaration with the canonical projection to fail on drift. No SCI wrapper
  installation or `kernel/with-arm` hunk was changed.

The host regression uses a real interned host Var, both dials, the canonical
database fixture and the same private cluster committer with that fixture's
connection. It verifies pre-body input/arity refusal, matching boundary schemas,
declared versus undeclared per-arity returns, base-only refusal through a map,
and identity of the value handed to and returned from the recorder. It also
asserts positive recording outcomes and stored function/arity facts; those
assertions exposed the dependency below instead of accepting an unrecorded
refusal as success.

### Named dependency and remaining scope

On recurrence, `error/commit-call` reads the previous occurrence through
`src/seon/db.clj:2032–2054` `pull`. Every arity declares
`[:or :nil :map :seon.error/value]`, which does not declare the new base or
facets. The actual writer reported:

```text
seon.db/pull returned undeclared error facets #{:seon.instrument/contract-error}.
seon.db/pull returned undeclared error facets #{}.
```

The empty set in the second message is a base-only value without explicit base
permission. The failure path then crosses `src/seon/error/refusal.clj:4–8`,
whose generic `[:or :nil :map]` output likewise refuses the returned
`:seon.instrument/undeclared-error` or contract error. These generic contracts
must enumerate what they return under §1q. Neither a blanket base arm, a
generic error-union alias, nor disabling enforcement around the recorder is
an acceptable repair. `db.clj` was clean at the observation, but outside the
explicit host ownership; no foreign source or session was changed. This is
an integration declaration dependency, not an excuse to stop on dirty code.

Further proof remains owed after that dependency: record-mode acquisition of
all host Vars with real custody, stored complete-facet validation on recurrence,
and isolation when distinct clusters arm the same JVM Vars. The present root
captures one arm request's policy; preserving the right acquired operation
when a later cluster replaces that shared root needs explicit verification.

The result traversal is still the wrapper's provisional derivation, not the
slice-4 analyzer. It rejects cycles and `:merge`, but positive unsupported
bare-predicate/unknown-extension findings and the full grammar matrix remain
unproven. Slice 4 must supply the single complete per-arity derivation and its
published analysis findings; do not describe this checkpoint as that completed
analysis. SCI recorder threading, both-dial SCI regressions and any SCI deadline
refusal changes remain the explicitly deferred `install-function-contract!` /
`base-ctx` item. No host deadline mechanism was added.

### Fast evidence and downstream consumption

The requested `--paths` run refused an incomplete overlay naming dirty caller
paths, including held SCI/kernel/runner/turn files. Per the assignment, iteration
continued with plain `bin/test-fast`, without adding those files to ownership.
The first facet-only run passed **77 tests / 398 assertions**. The first host
draft produced **78 / 416 / 19 failures / 1 error**. Its allocation regression
was repaired by using the declared fault-evidence byte bound rather than the
general result bound; canonical schema fingerprinting was corrected to receive
the projection's definitions and predicate bindings.

The subsequent complete command was:

```sh
bin/test-fast seon.instrument-test seon.error-test seon.sci.kernel-arm-carriage-test
```

It completed **79 tests / 424 assertions / 3 failures / 1 error**. Three failures
are the host recording regression's outcome/storage assertions; one error is
the existing fault-normalization regression crossing `error.refusal/refusal`.
All `seon.sci.kernel-arm-carriage-test` tests passed. These are fast iterations,
not cold/platform proof. The final timing-only follow-up is recorded below.

Slice 3 consumes the public projection-bound facet derivation and observation
projection; complete owned-value acquisition and constructor migration remain
its work. Slice 4 consumes the per-arity enforcement seam and must replace the
provisional result analysis with its complete published derivation. The recorder
owner consumes the preserved occurrence members and recurrence failure above.

No cold gate, default lifecycle, explicit default reload/adoption, or foreign
lane operation was run. Automatic edit hooks queued publication; no live
adoption success is claimed. The unchanged `with-arm` return boundary passed
the fast regressions. Cold/platform and the reset-boundary live proof remain
orchestrator-owned. The requested focused PRD §5.2/§6 and both manifest consume
sections were read in full; the historical note's broader end-to-end grounding
limitation is not retroactively represented as a completed read.

### Measured hot-call overhead

The retained executable probe is
`test/seon/instrument_test.clj:1034`, `hot-host-facet-check-measurement`.
`bin/test-fast seon.instrument-test` armed 1,234 contracts and completed
**33 tests / 195 assertions / 3 failures / 0 errors**; the same recorder
dependency accounts for all three failures. The benchmark's three result
checks passed. This follow-up includes the final cluster process-identity
change and the timing test added after the preceding JVM loaded its namespace.

It compares the previous Malli input/output/guard callable with the new compiled
host callable, using the same identity body, contracts, values and canonical
projection. It measures the changed compiled seam, **not** the additional
unchanged outer Var/projection lookup. Each batch warms 3,000 calls and times
20,000 calls, three batches per case, using `System/nanoTime`. These are an
armed fast JVM's measurements under shared machine load, not an isolated
JMH result or a production percentile.

| Return | Before µs/call, three batches | After µs/call, three batches | Difference of medians |
|---|---|---|---|
| Scalar integer | 0.3330042, 0.2699625, 0.2283854 | 0.3245896, 0.2970896, 0.3119021 | +0.0419396 µs |
| Ordinary map | 0.23611665, 0.25008335, 0.24565 | 0.3303271, 0.34430205, 0.31011665 | +0.0846771 µs |
| Declared agent error | 0.2471958, 0.23031665, 0.22321665 | 38.8611125, 36.8567875, 34.721125 | +36.62647085 µs |

The declared-error path exceeds a few microseconds. Its declared-facet set is
already memoised per compiled function/arity in the wrapper captured by the
Var, with the compiler cache on its projection; it is never a global set.
Facet candidate metadata and validators are likewise projection-owned. Each
returned error must still be validated to derive its actual facets; these
measurements do not justify caching value membership or claim that the
remaining 36.6 µs has been optimized away.

All lane test launchers exited. No scratch worktree or cluster was created.
Only this lane's temporary logs and thread dump were removed after their
tallies, dependency messages and timings were retained here. `git diff --check`
passed. The markdown hook continued to report the same 44 pre-existing
repository citation errors recorded above, without edits to their owners.

## SCI implementation — 2026-09-18

The owner released `eval.clj` after `e088cc0f9`. `1278dfa16` had already
enumerated the generic refusal helper's facets. This resume implements the
SCI half of slice 2, without changing `kernel/with-arm` or its return boundary.

`instrument/wrap-interpreted` now calls the same `compiled-wrapper` as the
host path. Both dials enforce input and arity before invoking the body, then
independently validate the body's actual error facets against that arity's
declared result facets. The wrapper's own refusals bypass the body contract.
Record mode requires an acquired `:seon.flow/commit-fault!`; its absence is a
typed registration refusal, not an uninstrumented function. Installation must
not turn this refusal into a JVM fallback. Panic mode needs no recorder.

`base-ctx` accepts the operation; the program snapshot carries it through
acquisition, lazy installation, agent forks and regeneration. `acquire!`
passes its existing operation into base construction. `cluster-ctx` and
`fork-cluster-ctx` accept the same arm request, and the cold cluster caller
supplies its existing `cluster/commit-fault!` with connection, cluster,
process identity and caps. A sovereign fork replaces the source recorder and
re-arms interpreted roots with the receiving projection and policy. SCI's
generation-aware `bind-root!` preserves the source context. Grounding:
`reference-code/sci/src/sci/core.cljc:344` (`fork`),
`src/seon/sci/kernel.clj:108` (`cache-program!` replaces the snapshot).

Cold acquisition also needed to carry the declared `:seon.fn/arglists` and
`:seon.fn/private?` into `install-row!`; the previous incomplete request was
refused before an agent function could be installed. The real acquisition
tests now construct analyzed function rows with `program-fn-row`. Owned SCI
fixtures were updated for symbol identities and their canonical config.

`error/latest-fact`, a generic projection of error occurrences, now explicitly
enumerates the base and all 63 facets, as §1q requires. A regression compares
its declaration to the projection's discovered facets, alongside the existing
generic admission and refusal helpers.

The real SCI regression installs an interpreted multi-arity function, counts
body executions, and exercises both dials: invalid input, wrong arity,
undeclared returned facet, and a facet declared only by arity two. Recording
uses the canonical fixture connection and the cluster's actual committer;
assertions verify committed provenance and the exact returned refusal object.
The sovereign-fork regression checks recorder/policy separation.

### SCI measurement and verification boundary

`hot-sci-facet-check-measurement` in `test/seon/instrument_test.clj` is the
retained executable probe. It compares the old Malli callable and the new
wrapper around the same real SCI identity function under `kernel/with-arm`.
Each of three batches warms 3,000 calls and measures 20,000. Parsing and
context acquisition are outside the timed seam. Shared-machine fast-JVM
measurements are not production percentiles.

The second full run measured these µs/call:

| Return | Before, three batches | After, three batches | Difference of medians |
|---|---|---|---|
| Scalar | 0.24540625, 0.17691045, 0.17049585 | 0.22451665, 0.1994479, 0.19882085 | +0.02253745 |
| Ordinary map | 0.1755896, 0.17653125, 0.17575835 | 0.2255625, 0.21419585, 0.2182625 | +0.04250415 |
| Declared error | 0.1723646, 0.17464585, 0.1743083 | 17.019325, 16.63647915, 16.63929585 | +16.46498755 |

The error case exceeds a few microseconds. Declared facet sets are already
memoised per compiled function/arity with the supplied projection and captured
in the wrapper, never globally. Actual returned-value validation remains the
measured cost; no value-membership cache or unmeasured speed claim is made.

The requested `--paths` fast overlay refused dirty callers outside this lane
(including render/walk, test runner and cluster source tests). Per the task,
iteration used plain `bin/test-fast seon.instrument-test seon.sci.eval-test
seon.error-test seon.sci.kernel-arm-carriage-test`. No cold gate or default
lifecycle operation was run. The second run was **157 tests / 849 assertions /
22 failures / 10 errors**, before the cold-acquisition correction and final
fixture corrections; its new direct SCI enforcement regression and all
arm-carriage tests passed. A final run is recorded below when complete.

The new exact declaration dependencies are `seon.render.value/transacted`
(`src/seon/render/value.clj:29`, both results `:map`) and
`seon.sci.kernel/failure-value` (`src/seon/sci/kernel.clj:519`, result
`:seon.error/value`). The former refuses preserved contract/arity facets
during rendering; the latter refuses the contract facet while normalizing a
thrown SCI wrapper refusal. These are named integration dependencies outside
the assigned owners, not an attribution to another lane's uncommitted work.
The previously accepted three DB failures remain: base-only `db/pull` and
arity-two `db/transact-call` returning an undeclared-error refusal.

Slice 3 consumes the same projection-owned pure facet derivation and occurrence
preservation as the host checkpoint. Slice 4 consumes one enforcement path
for host and SCI and must complete the provisional result-position analysis.
Generic helpers need explicit error declarations before the full evaluation
and rendering paths are green. Cold/platform and live adoption proof remain
orchestrator-owned.

### Final full fast run and construction follow-up

The third four-namespace run completed **157 tests / 850 assertions /
6 failures / 8 errors**. Direct SCI enforcement, sovereign-fork recorder
separation, cold function acquisition, provenance regeneration, error tests
and every kernel-arm-carriage regression passed. The remaining findings were:

- Three accepted host DB failures, unchanged: `db/pull` and arity-two
  `db/transact-call` at the recorder recurrence boundary.
- Four rendering errors in `refusal-value-projection-obeys-the-profile-and-html-keeps-the-whole-value`
  and `a-sci-only-arity-miss-names-its-program-graph-arglists`, all naming
  `render.value/transacted` and a contract/arity facet.
- Two normal-evaluation errors in `an-instrumented-multi-arity-miss-reads-like-clojure`
  and `agent-contracts-apply-on-acquire-and-cold-recovery`, naming
  `kernel/failure-value` and an arity/contract facet.
- `a-foreign-armed-context-is-refused-as-a-value`: `kernel/with-arm` throws
  `already-armed` at acquisition in `evaluate`, before its failure conversion.
  The existing return/release boundary was not changed to address this.
- `overrides-follow-current-admission-and-survive-lost-file-coordinates`:
  the writer refused retraction of function file coordinates, reporting
  `[:seon.schema.admission/source]`, expected `:core` or `:agent`, actual
  `:core`, on `seon.id/id`. This observation is not a diagnosed writer cause.
- `base-context-injections-have-program-rows`: the published population
  omits the injected `clojure.test` declarations.
- The existing allocation checks observed **2,265,795,280 bytes** for schema
  declaration against 67,108,864, and **3,588,873,808 bytes** for through-SCI
  public walk against 1,073,741,824. No limits were relaxed. The schema class
  is already tracked in
  [the allocation issue](../../../seon/issues/guarded-schema-declarations-still-exceed-the-allocation-regression-bound.md).

The final full run repeated the benchmark under different shared load:

| Return | Before µs/call | After µs/call | Difference of medians |
|---|---|---|---|
| Scalar | 0.54933335, 0.39376875, 0.3601375 | 0.50015625, 0.43353125, 0.48008955 | +0.0863208 |
| Ordinary map | 0.38208125, 0.3856021, 0.3537042 | 0.56166875, 0.46739585, 0.4645979 | +0.0853146 |
| Declared error | 0.32594795, 0.39449165, 0.3998104 | 43.0163021, 42.2806854, 42.80256875 | +42.4080771 |

After that JVM loaded, acquisition's outer catch was also changed to propagate
typed registration failures. Otherwise it could return a context containing
a function whose arming failed. The sovereign-fork test now additionally
asserts that record-mode `base-ctx` without a recorder refuses construction.
The focused `seon.instrument-test` rerun could not construct its fixture:
`test/seon/test_runner_test.clj:48:20` still referred to
`dev-cache/digest-file!`, which a concurrent edit removed. No test tally is
claimed for that launch. An owned thread dump showed fixture population at
the writer, then the launcher exited with the named unresolved Var.

Per the assignment, verification continued in `tmp/error-wrapper-sci-wt`,
detached at `eb2d9a20cee90b991d503dc617e2a1e44adfba79`, with only the six owned
source/test diffs applied and `reference-code` linked to the checkout's
vendored dependencies. It runs the complete four-namespace fast command;
neither foreign source nor another lane's session is repaired or operated.
