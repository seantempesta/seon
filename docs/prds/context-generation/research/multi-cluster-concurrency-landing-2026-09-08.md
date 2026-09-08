---
type: research
status: active
tags: [research, runtime, test]
---

# Multi-cluster concurrency — landing, 2026-09-08


## Resumed proof, 20:02–20:17 UTC

The orchestrator superseded the stop instruction and assigned adoption to
`hook-coalesce` and shared-base construction to `runner-base-cache`. This
section supersedes the initial partial verdict below. No production or held
file was edited in this lane.

| Angle | Current verdict |
|---|---|
| Cohosted clusters | default and beta alive together in PID 36758; explicit MCP selection reaches both |
| Concurrent agents | Proven for ordinary provider-free source turns: three fresh agents per cluster, 60 measured turns, all stored and closed |
| Private definitions | Ordinary `def` in default/parallel-a resolves only in that agent, not its two siblings or any beta agent |
| Installed functions | Contracted `defn` in default's namespace also present in beta resolves in all default agents and no beta agents |
| Result handles | `result/e67342` resolves only in default/parallel-a; no sibling or beta resolution |
| JVM globals | Refuted for independent contracts/program generations: global Malli registry and host Var wrappers remain shared |
| Test worker concurrency | Final isolated gate green: 1 test / 156 assertions, two canonical fixture clusters with concurrent real SCI source turns |
| Second root | gamma fully published and running in PID 43764, separate store and lock, reached by explicit-root MCP; subsequently down and root deleted |
| Development adoption | Handed to hook-coalesce; this lane's current hook refused, so no adoption/browser proof is claimed |

### Turn measurements and recovery

Authorized `bin/seon stop default` / `bin/seon start default` replaced PID
14049 with 36758. Stop exhausted its 30-second prepl silence bound before
SIGTERM; boot reported `:seon.boot/recovered-runs 2`. This resolved the old
open-run boundary. `compact!` is not a close operation: it refuses open turns.

Created `parallel-a`, `parallel-b`, and `parallel-c` in both clusters using
`agent/creation-tx` with explicit cluster projection and connection; the live
armer constructs their ordinary graphs. Unlike `ensure-entity!`, this does not
seed automatic bootstrap/provider work. Existing Juniper and earlier probes
were preserved; the measured six are fresh records, not re-seeded Juniper.

Each wave releases six futures from one CountDownLatch. Each calls
`seon.turn/virtual-turn!`, which uses the ordinary source-submission/per-agent
proc path, then installs a Datahike listener before checking the exact
closing fact. Every event wait uses the canonical fixture's 20-second bound.
The measured source is `(str "<cluster>/<agent>/<ordinal>")`, not a string
literal: the reply reader treats a lone literal string as prose and refuses
it with `:seon.cluster.reply/no-forms`.

Ten waves / 60 turns completed in **20.656833709 seconds = 2.90461 turns/s**.
Per-wave rates, in order, were **5.33630, 6.20140, 0.50603, 6.55069,
6.16177, 5.89694, 6.55884, 6.88977, 6.11161, 5.80951 turns/s**. All 60
stored values exactly matched cluster, agent, and ordinal; no evaluation
contained an error. The slow third wave is retained, not discarded. These
are end-to-end submission + commit + completion-query numbers under concurrent
development activity, not isolated SCI evaluation benchmarks.

One warm-up and two private-def waves brought the census to **13 turns per
agent, 78 total**: exactly 78 opened-at facts, 78 closed-at facts, 78 replies,
**zero provider attempts**. The later function-install and handle probes are
excluded from the throughput sample. Function installation exceeded the
helper's 20-second completion bound, then the durable closing fact appeared;
a subsequent ordinary six-agent wave confirmed default-only visibility.
This is a slow-install finding, not proof of a permanently wedged agent.

Reproduction uses `test/seon/concurrency_test.clj`'s `concurrent-wave!` after
loading that namespace and its canonical `test_support.clj` through MCP JVM
mode. Supply subjects from each explicitly selected running instance and
three fresh agent ids; call ten waves with `(pr-str (list 'str tag))`, measure
`System/nanoTime` around all ten, and inspect `::evaluations`' stored scalar
`:seon.print/value`. The checked-in test retains that same submission and
bounded closing-event mechanism; it does not invoke an evaluator directly.

### Thread picture after the six-agent run

`jcmd 36758 Thread.print` was saved as
`tmp/multi-cluster-concurrency/platform-threads-resumed.txt`. The required
virtual-thread evidence came from:

```sh
jcmd 36758 Thread.dump_to_file -format=json /Users/sean/src/seon/tmp/multi-cluster-concurrency/all-threads-resumed.json
```

The dump has **17 containers, 158 threads, 85 virtual, 73 not marked virtual**.
**63 threads have a `clojure.core.async.flow` stack; all 63 are virtual**.
No platform thread in this snapshot has a Flow stack. Compared with the
initial JVM snapshot (39 virtual Flow threads), the platform count remains
73, but JVM restart and different populations prevent treating this as a
controlled per-agent scaling slope. Flow thread names are empty, so the dump
does not attribute a particular thread to a named agent. Source supplies the
ownership evidence: each agent graph has explicit `:io` mailbox, turn and
schedule procs; the root I/O executor uses virtual threads.

### Root/store proof and cleanup

After `bin/seon --root tmp/other-root init`, gamma forked publication
`6aa0695d-a72d-538f-be72-0be676b63b7e` and served port 7934, prepl 51692.
MCP with root `/Users/sean/src/seon/tmp/other-root`, cluster `gamma` returned
PID **43764**, instances `["gamma"]`, and exactly one held flock:
`/Users/sean/src/seon/tmp/other-root/data/store.lock`. Main-root MCP with
cluster `beta` simultaneously returned PID **36758**, instances
`["default" "beta"]`, and exactly one held flock:
`/Users/sean/src/seon/data/store.lock`. Main ports were 7994 and 7711.
These are distinct stores/JVMs, not two processes opening one store.

Root-scoped `down` completed with `flock free; roster readable (3 branches)`;
`tmp/other-root` was removed with symlink-safe directory deletion. Beta stopped successfully through the prepl; default was left running. Final
gate outcome is recorded below.

### Process-global state audit (source snapshot, 2026-09-08)

Searched every CLJ/CLJC source under `src/` for `defonce`, dynamic Vars,
`ThreadLocal`, atoms, volatiles, and root mutation. Also followed the operator
runtime namespace into `resources/seon/operator/runtime.clj`, and the Malli
and SCI dependency owners. The raw search evidence is under
`tmp/multi-cluster-concurrency/{global-state,mutable-state}.txt`.

| Global owner | State and isolation finding |
|---|---|
| operator runtime | running-instances keyed by cluster; store holders by physical store; flocks by canonical lock path; root executors are shared infrastructure |
| search/owners | indexed by connection and index path; writer, basis and close state belong to the acquired index; no unkeyed cluster slot |
| schema/!database-projections | bounded LRU keyed by Datahike committed-value identity, which includes connection id, generation and commit; sibling branches cannot alias |
| schema/!ambient-shape-projection | one forms/projection pair; one atom read, exact forms equality before reuse, local candidate returned on miss; no torn pair |
| schema registry facade | delegates to dynamically bound projection; structural registry and core predicate registration are process-wide declaration infrastructure; not an independently acquired cluster |
| schema dynamic bindings | projection, projection-state, packaged forms, candidate overlay/admission and visitor are call-scoped; fallback-counts are shared diagnostics, not cluster authority |
| db/effect/render dynamics | connection/read database/read evidence, effect request and walk context are bound per invocation; scoped, but remain migration work under the explicit-values law |
| SCI kernel | shared deadline scheduler; guard stores the evaluation arm in ThreadLocal and removes it on stop/failure; migrated arms travel explicitly and compare interpreter identity |
| source refresh | one monitor serializes publishers; immutable analysis result cached against the complete source/schema snapshot, validated and rejected if source changes during analysis |
| cluster mcp-projection | ThreadLocal transport marker, not a stored cluster schema projection; selected cluster comes from explicit MCP request |
| web/AI HTTP clients | shared transport pools, not cluster payload/connection authority |
| generator ctx/server/mult | schema generator objects, not live cluster allocations |
| predicate defonce markers | registration return values in my.fs, my.edit and blob; no cluster state |
| local atoms/volatiles | environment, Flow launcher, routing, acquired context, render package/interest, evaluation buffers and test-run captures are allocated by their owning construction calls |
| JVM Vars + Malli registry | **DEFECT**: process-wide callable roots and namespace/symbol-keyed compiled function schemas carry one cluster's declaration/policy; not under running-instances |

The last row is the existing issue
[instrumentation-compiles-under-one-clusters-projection.md](../../../seon/issues/instrumentation-compiles-under-one-clusters-projection.md),
reconfirmed at `src/seon/instrument.clj:629` (collect all host Vars) and
`:685` (`apply!`, global instrument/unstrument). Malli's own collection writes
`malli.core/-function-schemas*`; SCI's first-party forwarding functions call
shared host Vars. Thus distinct SCI env atoms alone do not isolate core code
or validator generations. Same-program concurrent passes cannot clear this.

No speculative hunk is supplied for the held acquisition owners: the repair
must coordinate callable generation, compiled contracts and operator policy.
The AGENTS design gate requires a choice before that cross-owner production
change: (1) **recommended**, carry compiled contracts/callables per cluster
(more coordinated work, preserves independent programs); (2) require one core
contract/policy per JVM (smaller, gives up divergent core contracts); or (3)
one JVM per cluster (isolates existing globals, gives up cohosting). This is
an explicit handover, not a claimed fix.

Persistent private object identity remains a target: current
`sci/eval.clj:1821` still forks and rehydrates stored defs. The scalar `def`
and handle visibility probes prove isolation, not persistent object identity.
No live beta-message wake observation was performed, so that requested
behavior remains unproven; source routes each connection's report only into
that instance's channels (`cluster/wake.clj:395–450`).

### Resumed gate and publication evidence

First isolated snapshot `e3efb12cc`, run `run.4d6fZb`, built its base and ran
the test, exposing this lane's missing projection-state handle input. Fixed
by carrying the context's state explicitly. The armed fast loop then passed
**1 test / 114 assertions**, mode panic, 912 registered / 911 instrumented /
908 program-armable Vars. Added private-def visibility and opened/reply facts.
The next fast loop encountered `Unable to resolve symbol: nitialization`
while compiling `seon/test/fast.clj:0:0`; no foreign file/session was changed.
The final isolated gate snapshots only this test over HEAD `79326a2d2`.
The isolated gate passed: **1 test / 156 assertions**, worker execution
**24,319 ms**; the successful root `run.ONhvir` was removed by the runner.
Base preparation took 191,754 ms. After the snapshot base succeeded,
`bin/test --platform` was launched; its result is recorded below.

Hook feedback is not a success claim: one test publication could not locate
`seon/test_support` on the development classpath; a later publication refused
with `:seon.sci.eval/install-delete-mismatch` for
`seon.test.runner/arm-contracts!`, residual definition attribute
`:seon.schema.admission/source`. These adoption boundaries belong to the
assigned adoption owner. The live measurements exercised the existing loaded
procs and explicitly loaded test helper, not a claimed successful adoption.

## Initial pass (historical; superseded above)

Stopped at the assignment's explicit foreign-gate boundary. This is not a
green concurrency proof. No production files or held files were changed.

## Verdict by angle

| Angle | Observation | Verdict |
|---|---|---|
| Two clusters in one root/JVM | default and beta both alive, PID 14049 | Proven for boot and MCP JVM access |
| Separate cluster carriers | SCI ctx, projection state, connection, routing, render view, and plumbing graph were distinct objects | Proven object ownership; behavior isolation remains unproven |
| Concurrent agent turns | Two probe agents created in each cluster; five-way wave included existing Juniper | Incomplete: source submission refused while probe agents had open runs |
| Process-global state audit | Inspected root holders, SCI forks, schema caches, search registry, thread-local guard, and render allocation | Partial; no exhaustive clearance |
| Development adoption | Explicit init and actual edit hook both refused with two instances | Refuted |
| Regression gate | Both requested gate forms failed before this regression ran | Blocked |
| Second root/store | gamma reached a separate JVM, PID 16200, but lacked current-src | Partial boot only; no running-cluster or MCP proof |

## Measurements and exact boundaries

`bin/seon status` reported:

```text
beta     14049 alive 50007 http://127.0.0.1:7711
default  14049 alive 58925 http://127.0.0.1:7994
2/2 clusters alive
root footprint: 10.41 GiB; filesystem usable: 555.31 GiB
```

The initial status command waited roughly 200 seconds on the lifecycle lock,
first behind an existing `init --dev default --changed src/seon/flow.clj`
(PID 27725), then behind this probe's beta start. Neither holder was displaced.
Beta boot reached ready at approximately 19:45:45 UTC. MCP JVM evaluation
selected both `root=/Users/sean/src/seon` and each explicit cluster name.

The object comparison used `identical?` between the default and beta instance
members: `:seon.sci.eval/ctx`, `:seon.flow/graph`,
`:seon.cluster.agent/routing`, `:seon.render.web/view`, and
`:seon.boot/cluster-connection`; all returned false. The contexts'
`:seon.sci.eval/projection-state` comparison also returned false. This does
not prove every nested value is isolated. Default's queried source commit
was `6aa063cd-d528-5e8c-94b1-2c408fdf4001`; beta had no matching cluster
`:seon.source/commit-id` fact in this query. Its fork commit was not recovered;
absence is not proof that it had no source publication.

`bin/seon init --dev default --changed src/seon/operator.clj` exited 1:

```text
Development updates require their own running JVM.
:seon.error/kind :seon.boot/refused
:seon.boot/offense {:seon.boot/cluster-name "default"}
```

The real edit hook reproduced the refusal on the new regression. The held
refresh owner rejects `(not= 1 (count @running-instances))` at
`src/seon/cluster.clj:2024`. No edit was adopted, and no browser repaint or
beta page-serving claim is made.

Agent creation used `seon.cluster/ensure-entity!`, in each cluster's carried
projection, with ids `concurrency-a` and `concurrency-b` and namespaces
`my.agents.concurrency.concurrency-a` and `.concurrency-b`. Both default
bootstrap runs closed at 19:47:35–36 UTC; both beta bootstrap runs closed at
19:47:33–34 UTC. A later wave submitted one literal vector to Juniper and
these four agents concurrently through `submit-source!`, using the new
regression helper. It raised `Concurrent source submission refused` with
`:seon.error/kind :seon.cluster.run/refused`. Follow-up facts showed open
non-source runs on default's probe agents. This was not diagnosed as a wedge;
the full refusal data was not preserved by MCP's exception projection.

**Throughput: unavailable.** No complete successful wave was observed. The
four creation calls took 618 ms together; that is creation time, not turns/s.
Private def, installed defn, result-handle, and message-wake isolation were
not reached. No claim of provider-free completion is made for the automatic
work that followed boot/agent creation.

## Thread picture

At 19:47:50 UTC, while both clusters and probe agents existed:

```sh
jcmd 14049 Thread.print > tmp/multi-cluster-concurrency/platform-threads.txt
jcmd 14049 Thread.dump_to_file -format=json /Users/sean/src/seon/tmp/multi-cluster-concurrency/all-threads.json
```

The JSON dump contained 15 thread containers and 132 threads: 59 explicitly
virtual and 73 without the virtual flag. Of those, 39 had a stack frame
containing `clojure.core.async.flow`; **all 39 were virtual**. No platform
thread in that snapshot carried a Flow stack. This is a snapshot, not a
measured scaling slope or an attribution of each thread to one named agent.
`Thread.print` alone omits virtual threads and is insufficient for this proof.

Source grounding: `agent/graph-definition` constructs mailbox, turn, and
schedule with explicit `:io`; `cluster/projection-executor` delegates to the
root I/O executor while carrying projection state. The pinned core.async
`impl/dispatch.clj:82` uses `Thread.startVirtualThread` when available. SCI's
`core.cljc:345` forks the env atom; `fork-cluster-ctx` additionally reconstructs
connection custody, projection state, and supplied-default state.

## Gate evidence and scope left unfinished

`bin/test --platform` exited 1 at load 119/152:

```text
Syntax error compiling var at (seon/render/web_prompt_test.clj:26:6).
Unable to resolve var: seon.render.web/prospective-prompt in this context
```

Its retained root is `tmp/test-runs/run.Spfc7z`, HEAD `38d2fc91b`.
`bin/test --paths test/seon/concurrency_test.clj -- seon.concurrency-test`
also exited 1, earlier, reading a missing
`:seon.dev-cache/test-classpath-file` from the selection. The script/cache
interface straddled HEAD and the foreign cache edit. Neither is an assertion
failure of this regression. No retry or foreign-session intervention followed.

The added namespace currently covers four concurrent source turns per wave
on two booted fixture clusters, three waves, closure, evaluation presence,
and exact cluster/agent-tagged stored values. It uses the canonical published
base, real SCI and real agent procs, no mocks. It remains **unverified and
incomplete against the full assignment**: private objects, installed function
visibility, handles, messages, and adoption are not yet regression assertions.

The state audit found root-store holders keyed by store directory, a search
registry keyed by index/connection, and schema projection caching keyed by
Datahike committed-value identity. Those are not automatically leaks because
they are process-global. Source refresh and JVM instrumentation need further
analysis: shared host Var mutation is the unresolved cross-cluster boundary.
No speculative guard-removal hunk is offered as a safe fix.

Named skills read: flow architecture, REPL, data-oriented Clojure, and Clojure
testing. AGENTS.md was supplied in full; the requested PRD sections were read.
The broader requested source/authority reading was not completed before the
stop boundary; this note does not claim an end-to-end audit.

See the [adoption issue](../../../seon/issues/development-adoption-refuses-cohosted-clusters.md)
and [gate boundary evidence](../../../seon/issues/bin-test-shared-base-compiles-other-lanes-half-edits.md).

## Cleanup and changed files

Beta was stopped using `bin/seon stop beta`. Gamma's incomplete publication
was cancelled by terminating this lane's exact init CLI PID 37010, followed
by root-scoped `down --force`; default was never stopped or reset.
Root-scoped down exited successfully with `flock free; roster readable
(1 branches)`. `tmp/other-root` was deleted without following symlinks.
Final `bin/seon status` reported default alone, PID 14049, alive, and no
orphan JVMs. All owned operator and test shell commands exited. The two
probe agent records in default remain; Juniper was not reseeded or reset.

Owned changes: `test/seon/concurrency_test.clj`, this landing note,
`docs/seon/issues/development-adoption-refuses-cohosted-clusters.md`, and the
additional observation in
`docs/seon/issues/bin-test-shared-base-compiles-other-lanes-half-edits.md`.
