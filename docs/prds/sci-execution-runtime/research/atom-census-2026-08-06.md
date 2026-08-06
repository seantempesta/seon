---
type: research
status: complete
tags: [research, runtime, state, database]
---

# Mutable Process State Census — 2026-08-06

## Verdict

The census found **77 real mutable constructors** in the requested production
and operator scope: 48 `atom`, 28 `volatile!`, one `ref`, and no Clojure
`agent`. Sixty-three are sanctioned process-local artifacts or
invocation-local coordination, two are database/durable-fact violations, and
twelve should be deleted because they are dead or ordinary derivations. The
separate `defonce` census found **45 forms**: nine hold atoms, fourteen hold
ordinary values/resources, and twenty-two are registration sentinels.

The four resource registries exposed by the live drive are correctly mutable:
`running-instances`, `root-store-holder`, `held-flocks`, and `seon.search/owners`
own live handles whose loss is harmless because database facts and on-disk
index metadata reconstruct the system. The clearest violations are elsewhere:

- `seon.schema/!schema-state` makes one database-derived projection
  process-global even though co-hosted clusters are sovereign.
- `seon.cluster` records a dropped core fault only in `drops`, an atom lost on
  restart.

The complete ranked list and issue destinations are in
[[#Ranked findings]].

## Scope and method

I read the complete active program ledger at
[docs/prds/sci-execution-runtime/plan/README.md](../plan/README.md), its complete
working edge at
[docs/prds/sci-execution-runtime/plan/unsettled.md](../plan/unsettled.md), and
the complete localized runbook at
[docs/prds/sci-execution-runtime/AGENTS.md](../AGENTS.md) before verdicting the
tree. The `data-oriented-clojure` skill and its program-state reference supplied
the test: durable facts belong in the database; a mutable reference is allowed
only for a live process artifact, compiler/analysis artifact, or genuinely
invocation-local coordination.

The mechanical scope was every `*.clj`/`*.cljc` under `src/`, `script/`, and
`resources/`. Exact constructor searches covered `atom`, `volatile!`, `agent`,
and `ref`; a separate exact search covered every `defonce`; mutation searches
covered `swap!`, `reset!`, `compare-and-set!`, `vreset!`, `vswap!`, `send`,
`send-off`, `alter`, and `ref-set`. I then read every owning function and every
mutation site. The sole raw `agent` search hit was prose inside the string at
`src/seon/cluster/agent.clj:553`, so the structural count is zero.

Dependency ledger:

- Clojure 1.12.5 supplies Atom, Volatile, Ref, dynamic bindings, and the
  `clojure.test` report-counter Ref.
- core.async/Flow is vendored at `dc35f3e0d7bc2eef502e77982f48641f025c8051`;
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj` and
  `flow/impl.clj` establish proc-local state, channels, and disposable graph
  handles.
- Datahike is vendored at `10540578248eaa686c1f88a7fe57644ee4c9f993`;
  `reference-code/datahike/src/datahike/writer.cljc` establishes the database
  writer rather than an application atom as the durable serialization owner.
- SCI is vendored at `2db3358cba913b6fbbe49c7b5b34d7ac72715924`;
  `reference-code/sci/src/sci/core.cljc` establishes mutable interpreter
  contexts and forked per-turn execution artifacts.
- Malli is vendored at `80138076960e7820523b4cb932c5b5d1936d4e7f`;
  its callback walker and compiled registries explain the sanctioned
  invocation-local collectors and compiler caches, but do not justify a global
  database-derived projection.

## Runtime falsifier

I created the repository-local isolated root
`tmp/atom-census-operator`, republished and forked its scratch `default`
cluster, and used the repository MCP evaluation owner against prepl port 56443.
The live default cluster in the repository root was never addressed. The probe
observed:

```clojure
{:runtime
 {:running ["default"]
  :root-store-keys
  [".../tmp/atom-census-operator/data/clusters/store"]
  :flock-paths
  [".../tmp/atom-census-operator/data/clusters/store.lock"]}
 :search
 {:ids [".../default/derived/lucene"]
  :connections 1
  :basis {".../default/derived/lucene" 536870987}}
 :schema
 {:candidate-count 1842
  :predicate-count 32
  :projection-fingerprint 788199205}
 :source-files {:count 1817}
 :analysis-cache {:present? false}
 :drops 0}
```

`running-instances` held the complete live instance map, including the store,
connection, graphs, SCI context, web service, search owner, and error-drop
counter. `root-store-holder`, `held-flocks`, and `search/owners` held the exact
live resources claimed by their docstrings. `source-analysis-cache` was nil on
this boot because startup consumed the already published program rather than
reanalyzing source. The isolated JVM was stopped through `bin/seon --root
tmp/atom-census-operator down`; the operator confirmed the flock was free.

## Complete mutable-constructor census

Verdict codes are:

- **A — SANCTIONED:** the named process-local or invocation-local exception.
- **B — VIOLATION:** database facts or a durable committer must own it.
- **C — DELETE:** dead or derivable mutable state.

### Long-lived and owner-scoped references

The count column counts constructors, including multiple references in one
logical owner.

| Count | Name and construction | Holds | Mutation sites | Verdict and evidence |
|---:|---|---|---|---|
| 3 | `seon.operator.runtime/running-instances`, `root-store-holder`, `held-flocks` — `resources/seon/operator/runtime.clj:11-15` | Live cluster instance maps; open process-root stores plus holder counts; JVM-owned `FileLock`s | Instances: `src/seon/cluster.clj:528-535,2120,2135,2176,2312-2321`, operator reservation cleanup at `resources/seon/operator/state.clj:118-124` and `script/seon/fresh_operator.clj:2313-2325`. Stores: `src/seon/cluster.clj:632-656`. Flocks: `src/seon/cluster/store.clj:201-230`. | **A — resource handles.** These values are needed only while the process owns the corresponding server/store/lock. Cluster/run/store truth remains in database facts and process records. The scratch probe showed one instance, one store key, and one exact flock path; shutdown emptied them by releasing the resources. |
| 1 | `seon.dev.mcp/clj-sessions` — `script/seon/dev/mcp.clj:31` | `[root cluster session-id]` to live socket, reader, writer, and endpoint | Remove/close at `:308-315`; open/associate at `:317-333`; replacement at `:338-360` | **A — connection/resource handles.** No database truth is mirrored; losing a session loses only REPL-local process state. |
| 1 | `seon.search/owners` — `src/seon/search.clj:70-72` | Index id/connection to Lucene directory, analyzer, writer, search manager, lock, basis ref, close ref | Register `:331-335`; unregister `:349-355` | **A — resource handles.** The database is truth and the index is reconstructable. The scratch probe observed one owner keyed by its derived Lucene path. |
| 2 | Per-search-owner `:basis` and `:closed?` — `src/seon/search.clj:327,329` | Last Lucene commit basis and idempotent close state | Basis after writer commit/refresh at `:221-228`; close CAS at `:349` | **A — derived-index resource state.** Basis is also written into Lucene commit metadata (`:221-227`), and mismatch rebuilds from the database. Close state coordinates destruction of opaque resources. Neither is recovery authority. |
| 1 | `seon.schema.edn/!source-files` — `src/seon/schema/edn.clj:57` | Accumulated schema key to classpath resource path | Merge on every `load!` at `:379-385`; read for refusals at `:416-435` | **C — DELETE.** `resource-population` already returns immutable `::files-by-key` at `:338-349`; thread it through admission. Global accumulation can retain stale fixture provenance. See [the issue](../../../seon/issues/schema-source-provenance-accumulates-in-a-global-atom.md). |
| 2 | SCI base-ctx `::kernel/installed-functions` and `::kernel/program-snapshot` — `src/seon/sci/eval.clj:234-235` | Installed SCI Vars; immutable acquired function/namespace rows used for lazy installation | `src/seon/sci/kernel.clj:93-109,139-145` | **A — interpreter/compiler state.** Each base ctx is rebuilt from program facts; these refs coordinate mutable SCI installation and disappear with the ctx. |
| 1 | SCI cluster-ctx `::projection-state` — `src/seon/sci/eval.clj:1442-1443` | One cluster's latest immutable schema projection and basis transaction | Monotonic replacement at `:651-663` | **A — per-cluster compiler state.** Unlike the global schema atom, this is correctly scoped to the cluster ctx and reconstructs from its database value. |
| 1 | `seon.schema/!schema-state` — `src/seon/schema.clj:536-539` | Process-global candidate forms, predicate function cache, and active database-derived projection | Predicate cache `:597-608`; candidate updates `:627-631`; projection activation `:2172-2185`; eval-delta commit `:2308-2323`; test restore `:2350-2360` | **B — VIOLATION.** Only the predicate callable cache qualifies as a compiler artifact. Candidate forms and an active database projection cannot be process-global when clusters have sovereign bases. Use the per-cluster acquired projection mechanism. See [the blocker](../../../seon/issues/process-global-schema-state-crosses-cluster-bases.md). |
| 1 | `seon.schema/!shape-generation` — `src/seon/schema.clj:2483-2487` | Compiled validators/explainers keyed by the identical immutable projection | Generation reset `:2641-2649`; compiler cache insert `:2652-2663` | **A — compiler cache.** Disposable, identity-fenced, and reconstructable from the explicit projection. It never decides which projection is authoritative. |
| 1 | `seon.schema/!identity-only-generation` — `src/seon/schema.clj:2571-2573` | Compiled identity-only descriptors for one immutable projection | Replace on projection identity change at `:2609-2619` | **A — compiler cache.** Same exception and fence as `!shape-generation`. |
| 1 | `seon.cluster/source-analysis-cache` — `src/seon/cluster.clj:851-855` | Static clj-kondo program manifest paired with the complete source snapshot | Read/identity check `:1007-1020`; replace only after before/after snapshot equality `:1022-1034` | **A — analysis cache.** This is the explicit compiler/analysis exception. The digest-bearing snapshot prevents cross-source reuse; database publication remains the authority. |
| 1 | Render view `latest-packages` — `src/seon/cluster.clj:1733,1738` | Latest complete render package per registered page | Equality-guarded replacement at `src/seon/render/web.clj:753-775` | **A — losable transport cache.** It gives new SSE tabs the current keyframe; reconnect can re-render from facts, so recovery needs none of it. |
| 1 | Render view `:seon.render.web/registration` — `src/seon/cluster.clj:1737` | Live tab refcounts by registration key | Register/deregister at `src/seon/render/web.clj:930-945`; connection lifecycle at `:1036,1085-1090` | **A — connection coordination.** It describes current sockets only and correctly vanishes with them. |
| 1 | Cluster graph `drops` / `:seon.error/drops` — `src/seon/cluster.clj:1757` | Count of core faults rejected by the bounded fault channel | Increment and stderr output at `:1776-1787` | **B — VIOLATION.** A dropped core fault is durable forensic truth. Preserve nonblocking producer behavior by handing the observation to the fault committer, which commits `:seon.error` facts. See [the blocker](../../../seon/issues/dropped-core-fault-count-is-not-durable.md). |
| 1 | `seon.cluster.agent/routing` return atom — `src/seon/cluster/agent.clj:324-332` | Armed graph/resource entries, recipient entity ids to live wake channels, and cluster fault channel | Arm `:430-460`; fault-channel install `src/seon/cluster.clj:1795-1798`; disarm `src/seon/cluster/agent.clj:583-596` | **A — graph/channel handles.** The database owns agents/messages; this ref owns the disposable running graphs and channels derived from them. |
| 1 | `seon.flow/generator-values` member `:atom-reference` — `src/seon/flow.clj:55-66` | One globally shared mutable sample returned by every atom-reference generation | The generator exposes the same atom; contract consumers can mutate it | **C — DELETE.** A generator must make a fresh sample or declare the opaque handle nongenerative. Existing issue: [Generate fresh Flow contract values](../../../seon/issues/flow-generators-reuse-one-mutable-sample.md). |

Long-lived subtotal: **20 constructors** — 16 sanctioned, two violations, two
deletions.

### Invocation-local and protocol-local references

| Count | Name and construction | Holds | Mutation sites | Verdict and evidence |
|---:|---|---|---|---|
| 3 | Markdown `results`, `result`, `fixes` — `script/seon/dev/markdown.clj:168,675,895` | Extracted links, rewritten lines, number of changed passes | `:176,183,199`; `:686-692`; `:899` | **C — DELETE.** Ordinary `reduce`/loop outputs; no callback, thread, or resource crosses the mutation. |
| 1 | Render-call `captured` — `src/seon/render.clj:394` | Database read-evidence events emitted dynamically during one renderer call | Captured through `db/*read-evidence-sink*` at `src/seon/db.clj:155-159`; consumed at `src/seon/render.clj:407-414` | **A — invocation-local callback coordination.** The dynamic database-read observer cannot thread a return accumulator through arbitrary renderer calls. |
| 1 | Filesystem write `moved?` — `src/seon/fs/jvm.clj:580` | Whether the staged file was atomically moved | Set at `:584-586`; read by `finally` at `:598-603` | **A — invocation-local resource cleanup.** The flag crosses try/finally after a filesystem side effect and prevents deleting the committed target. |
| 1 | Filesystem glob `state` — `src/seon/fs/jvm.clj:693-695` | Returned paths, examined count, completion flag | Recursive walk mutations `:629-664`; result read `:697-704` | **C — DELETE.** `glob-walk!` can return immutable traversal state. |
| 1 | Restored desk atom — `src/seon/sci/eval.clj:1394` | One turn-local Atom initialized from the last settled `:seon.def` value | Mutated only by evaluated agent code; resnapshotted at settlement | **A — interpreter artifact.** Durable atom state is the database desk fact; the Atom is the faithful per-turn runtime representation. |
| 1 | Effect `:seon.effect/counter` — `src/seon/sci/eval.clj:1697` | Ordinal within one evaluated form's capability requests | Increment at `src/seon/effect.clj:434` | **A — invocation-local coordination.** Durable request/receipt identities are committed; the counter only assigns ordinals while that one form runs. |
| 1 | Schema `!references` — `src/seon/schema.clj:37` | Direct canonical refs found by one Malli walk | Callback `vswap!` at `:38-47` | **A — third-party callback coordination.** The volatile is scoped to the synchronous Malli walker and does not escape. |
| 1 | Schema `!reference-advisories` — `src/seon/schema.clj:1201` | Delayed validation results memoized during one `build-projection` | Atomic install/read at `:1203-1226` | **A — invocation-local compiler state.** It shares recursive validation work inside one build and is discarded afterward. |
| 1 | Registration delta `:seon.schema.delta/candidate-forms` — `src/seon/schema.clj:2252` | One eval's isolated candidate map initialized from its opening projection | Dynamic overlay mutations `:627-631,1074`; discard/reset `:2326-2347`; successful result later becomes transaction data | **A — invocation-local transaction coordination.** It is the private speculative candidate, never durable truth. |
| 2 | Registry GC `heads`, `inventory` — `src/seon/cluster/registry.clj:433-434` | Results delivered by two Datahike/konserve GC callbacks during one dry run | Callback resets `:437-460`; result assembly `:463-475` | **A — dependency-callback coordination.** These bridge callback results out of one synchronous dry-run invocation. |
| 1 | Test liveness `fired?` — `src/seon/test/runner.clj:339` | One-shot watchdog firing state | Scheduled-thread CAS at `:347-356` | **A — invocation/thread coordination.** It prevents repeated diagnostics during one runner process. |
| 1 | `clojure.test/*report-counters*` Ref — `src/seon/test/runner.clj:414` | Standard clojure.test STM report counters | Mutated through `clojure.test/inc-report-counter` while `test/test-vars` runs (`:411-435`) | **A — dependency protocol state.** This is the report-counter mechanism Clojure's test library requires. |
| 2 | Test `capture`, `reported-signatures` — `src/seon/test/runner.clj:445-446` | One run's ordered results and deduplicated throwable signatures | `:97-121,142-153`; consumed at `:459-475` | **A — dynamic callback coordination.** `clojure.test/report` is a callback protocol; both refs die with the request. |
| 1 | Test `progress` — `src/seon/test/runner.clj:624-626` | Last runner phase and monotonic timestamp shared with the watchdog | Announce/reset `:155-165`; scheduled read `:337-356` | **A — cross-thread invocation coordination.** It is deliberately process-local liveness evidence, not a test result. |
| 1 | Render-ns `references` — `src/seon/render/ns.clj:115` | Qualified schema refs found by one Malli walk | Callback swap at `:117-127` | **A — third-party callback coordination.** Synchronous and nonescaping. |
| 1 | Render-ns schema-row cache — `src/seon/render/ns.clj:310` | Schema rows already pulled during one namespace render | Read/update at `:78-89`; dies with `render-data` | **A — invocation-local query memo.** Database facts remain authority. |
| 1 | Flow compute `started-at` — `src/seon/flow.clj:274` | First observed work start nanoseconds | One-shot set at `:275-288` | **A — invocation-local timing coordination.** It spans the callback handed to a work function. |
| 2 | Flow dummy `(atom {})` values — `src/seon/flow.clj:339,634` | Nothing observable; stand-ins for `active-work` on refusal paths | `io-terminal!` calls `swap! dissoc` at `:314-333` | **C — DELETE.** Make untracked refusal explicit in the helper interface; do not allocate mutable no-op arguments. |
| 3 | Work launcher `active-work`, `io-submissions`, `accepting?` — `src/seon/flow.clj:545-547` | Running task handles/diagnostics, accepted IO work, and stop-admission state | Work lifecycle `:250-373,584-607,610-650,695-702` | **A — task/resource coordination.** Durable receipts own recovery; these refs own cancelable Futures, completions, and the exact current process workload. |
| 3 | Per-IO-work `::status`, `::active?`, `::task` — `src/seon/flow.clj:622-624` | Atomic lifecycle state and Future handle for one submission | CAS/reset at `:314-373,584-602` | **A — invocation/task coordination.** Required to settle/cancel one in-flight external call exactly once. |
| 1 | Compute submission `status` — `src/seon/flow.clj:672` | Queued/running/completed/cancelled state for one synchronous submission | `:192,253,289,300,697` | **A — cross-thread invocation coordination.** It arbitrates queue refusal, execution, and time-limit observation. |
| 3 | Cluster boot `server`, `published`, `progressed` — `src/seon/cluster.clj:2088,2130-2131` | Partially opened prepl server; latest publishable partial instance; last emitted boot phase | Set/read through boot and cleanup at `:2088-2142` | **A — invocation-local resource cleanup/readiness coordination.** These values exist only so a failed start can expose/stop exactly what it opened. |
| 2 | Web-render `captured-calls` — `src/seon/render/web.clj:330,782` | Render-call cache entries captured during one HTML or AI render pass | Filled by `src/seon/render.clj:413-414`; consumed at `src/seon/render/web.clj:762-767,790` | **A — invocation-local callback coordination.** The render walk calls independent producers and returns one immutable retained-call map. |
| 1 | SSE `painting` — `src/seon/render/web.clj:1028` | Whether one connection's virtual writer should continue | Read in writer loop `:1053`; cleared on close `:1085-1090` | **A — socket/thread coordination.** It has exactly the connection's lifetime. |
| 1 | Function-index `completed` — `src/seon/fn.clj:1314` | Committed row count for progress text | Incremented at `:1326-1331` | **C — DELETE.** Derive cumulative count from batch ordinals or thread it through reduce. |
| 1 | Bounded HTTP `read-count` — `src/seon/web/jvm.clj:125` | Bytes read through one `FilterInputStream` proxy | Proxy methods `:135-150` | **A — protocol/resource state.** InputStream reads are inherently sequential methods on one opaque stream; the counter enforces the external byte ceiling. |
| 3 | Eval-drive `instance*`, `store*`, `grading-branch*` — `src/seon/eval/drive.clj:373-375` | Resources that may be acquired before a later step fails | Set `:380-403`; read and retire in `finally` at `:405-414` | **A — invocation-local resource cleanup.** The volatiles bridge partial success into guaranteed teardown. |
| 1 | Program `!references` — `src/seon/program.cljc:262` | Canonical refs found during one Malli callback walk | `:263-273` | **A — third-party callback coordination.** Nonescaping and synchronous. |
| 2 | `TextSink` and `HiccupSink` state — `src/seon/print.cljc:121,185` | Output chunks/column/depth; Hiccup stack/roots | Text mutations `:80,102,114`; Hiccup mutations `:133,165,178` | **A — invocation-local sink/resource state.** These implement imperative append protocols for bounded printing; a new sink is allocated per render. |
| 1 | Schema-shape `seen` — `src/seon/fn/schema_shape.clj:255` | Fingerprints emitted by one recursive encoding | `:256-280` | **C — DELETE.** Return `[row seen]` or reduce immutable traversal state. |
| 2 | Render-walk `remaining`, `rendered-eids` — `src/seon/render/walk.clj:310-311` | Node budget and cycle/fan-in set for one neighborhood | Recursive mutations `:351-377` | **C — DELETE.** Both values are the walk's return state and can be threaded through child traversal. |
| 1 | Schema-internal `advisories` — `src/seon/schema/internal.cljc:71` | Nonterminal findings from one Malli walk | Callback append `:75-148` | **A — third-party callback coordination.** It is a local result collector imposed by the walk callback. |
| 2 | Kernel arm `::built-in-calls`, `::outcome` — `src/seon/sci/kernel.clj:198,200` | Built-in observations and time-limit outcome for one armed invocation | Observer `:72-75`; interrupt outcome `:49-60`; read in record `:180-188` | **A — per-invocation interpreter instrumentation.** The arm is thread-local and disappears at stop. |
| 1 | Kernel invoke `arm-state` — `src/seon/sci/kernel.clj:328` | Successfully acquired arm for catch/finally cleanup | Set `:331-334`; read by total failure/stop path below `:350` | **A — invocation-local cleanup coordination.** |
| 4 | Eval `arm-state`, `ending-namespace`, `print-options`, `session-observation` — `src/seon/sci/eval.clj:1661,1683-1685` | Acquired interrupt arm; dynamic SCI namespace and print bindings; pre-eval session snapshot | Sets `:1699,1710-1714,1731,1738-1740,1771-1772`; terminal read/cleanup later in `evaluate` | **A — invocation-local interpreter coordination.** Each crosses SCI dynamic-binding or try/finally boundaries and is settled into ordinary return/database data. |
| 2 | Admission `:nodes`, `:capped?` — `src/seon/sci/admit.clj:476-478` | Remaining node budget and whether the one recursive projection elided data | Mutations `:97-116,185-224,389`; result read `:479-484` | **A — invocation-local bounded traversal.** These coordinate a single total walk over arbitrary, possibly lazy/cyclic runtime values and never escape as authority. |

Invocation subtotal: **57 constructors** — 47 sanctioned and ten deletions.
Together with the long-lived table this accounts for all **77** constructors.

## Complete `defonce` census

### `defonce` values and mutable holders

This table explicitly distinguishes a `defonce` whose root is an Atom from a
`defonce` holding an ordinary value, delay, ThreadLocal, executor, client, or
monitor.

| Name and line | Root kind and held value | Mutation/use | Verdict |
|---|---|---|---|
| `seon.operator.runtime/running-instances` — `resources/seon/operator/runtime.clj:11` | **Atom**, live instance/resource maps | See long-lived table | **A — resource registry.** |
| `seon.operator.runtime/root-store-holder` — `resources/seon/operator/runtime.clj:13` | **Atom**, open stores plus holder counts | See long-lived table | **A — resource registry/refcount.** |
| `seon.operator.runtime/held-flocks` — `resources/seon/operator/runtime.clj:15` | **Atom**, exact `FileLock` objects | See long-lived table | **A — resource registry required by fcntl close semantics.** |
| `seon.operator.runtime/root-executor-pair` — `resources/seon/operator/runtime.clj:17-22` | **Value:** delay constructing root compute/IO executors | Realized by `root-executors` at `:24-28`; executor internals mutate | **A — process resource handles.** |
| `seon.search/owners` — `src/seon/search.clj:70-72` | **Atom**, Lucene resource owners | See long-lived table | **A — derived-index resource registry.** |
| `seon.cluster/generator-server` — `src/seon/cluster.clj:91-95` | **Value:** delay constructing one bound `ServerSocket` | Returned forever by generator | **C — DELETE.** Existing [opaque generator issue](../../../seon/issues/opaque-contract-generators-share-live-process-objects.md). |
| `seon.cluster/mcp-projection` — `src/seon/cluster.clj:173` | **Value:** `ThreadLocal` for one-shot prepl projection | `.set`/`.remove` at `:180-186` | **A — invocation/thread coordination.** |
| `seon.cluster/source-refresh-monitor` — `src/seon/cluster.clj:845-849` | **Value:** lock monitor | `locking` around publication paths | **A — process-local publication synchronization.** Cross-process correctness remains the database expected-head fence. |
| `seon.cluster/source-analysis-cache` — `src/seon/cluster.clj:851-855` | **Atom**, snapshot-keyed static manifest | See long-lived table | **A — analysis cache.** |
| `seon.web.jvm/client` — `src/seon/web/jvm.clj:21-24` | **Value:** JDK `HttpClient` | Dependency manages connection pools internally | **A — process resource client.** |
| `seon.schema.edn/packaged-base-forms` — `src/seon/schema/edn.clj:43-47` | **Value:** immutable snapshot of ambient registered schemas | Merged into every `packaged-forms` at `:360-365` | **C — DELETE.** It freezes load order as a second schema authority; see [registration issue](../../../seon/issues/load-time-schema-sentinels-bypass-basis-acquisition.md). |
| `seon.schema.edn/!source-files` — `src/seon/schema/edn.clj:57` | **Atom**, accumulated derived provenance | See long-lived table | **C — DELETE.** |
| `seon.schema/!schema-state` — `src/seon/schema.clj:536-539` | **Atom**, mixed compiler cache plus global forms/projection | See long-lived table | **B — VIOLATION.** Split compiler artifacts from per-cluster acquired projections. |
| `seon.schema/seon-registry` — `src/seon/schema.clj:653-666` | **Value:** stable Malli Registry facade dereferencing global schema state | Installed globally by `relink-registry!` at `:668-680` | **B — VIOLATION as currently backed.** A process facade cannot select one database-derived projection for all clusters; part of [the same blocker](../../../seon/issues/process-global-schema-state-crosses-cluster-bases.md). |
| `seon.schema/!shape-generation` — `src/seon/schema.clj:2483-2487` | **Atom**, compiled shape caches | See long-lived table | **A — compiler cache.** |
| `seon.schema/!identity-only-generation` — `src/seon/schema.clj:2571-2573` | **Atom**, compiled descriptor cache | See long-lived table | **A — compiler cache.** |
| `seon.flow/generator-values` — `src/seon/flow.clj:55-66` | **Value:** delay holding executor, Atom, Future, graph, channel, launcher | Same objects returned by all generators | **C — DELETE.** Existing [Flow generator issue](../../../seon/issues/flow-generators-reuse-one-mutable-sample.md). |
| `seon.sci.kernel/deadline-timer` — `src/seon/sci/kernel.clj:36-43` | **Value:** scheduled executor | Schedules/cancels one task per arm at `:190-219` | **A — process resource handle.** |
| `seon.sci.kernel/process-guard` — `src/seon/sci/kernel.clj:81` | **Value:** delay holding one ThreadLocal guard and observers | Arm set/remove at `:190-222` | **A — interpreter/thread resource.** |
| `seon.cluster.store/branch-open-monitor` — `src/seon/cluster/store.clj:373` | **Value:** lock monitor | Serializes branch connection opening | **A — process-local resource synchronization.** |
| `seon.sci.eval/generator-ctx` — `src/seon/sci/eval.clj:142-147` | **Value:** delay holding one mutable SCI ctx | Same ctx returned forever | **C — DELETE.** Existing [opaque generator issue](../../../seon/issues/opaque-contract-generators-share-live-process-objects.md). |
| `seon.render.web/generator-server` — `src/seon/render/web.clj:91-100` | **Value:** delay holding one bound http-kit server | Same server returned forever | **C — DELETE.** Existing opaque generator issue. |
| `seon.render.web/generator-mult` — `src/seon/render/web.clj:112-117` | **Value:** delay holding one mult/channel | Same mult returned forever | **C — DELETE.** Existing opaque generator issue. |

### Registration sentinels

All 22 rows are **D — REGISTRATION SENTINEL**. The six predicate sentinels have
a sound purpose—make a host predicate available to schema compilation—but an
unsound `defonce` implementation: they cache a function value once and miss a
later `defn` reload. The fifteen schema-declaration sentinels are unsound in
principle because load-time mutation competes with EDN/database acquisition.
`_registry-init` is a legitimate process-bootstrap integration only if its
registry is cluster-neutral; the current facade is not, so the current pattern
also fails. The shared issue is
[Replace load-time schema registration sentinels with acquired declarations](../../../seon/issues/load-time-schema-sentinels-bypass-basis-acquisition.md).

| Sentinel and line | One-time side effect | Judgment |
|---|---|---|
| `my.fs/_content-predicate` — `src/my/fs.clj:52-53` | `register-core-predicate!` | Purpose sound; `defonce` stale-callable pattern unsound. Resolve/cache the Var by qualified symbol. |
| `my.fs/_write-precondition-predicate` — `src/my/fs.clj:55-58` | `register-core-predicate!` | Same. |
| `my.edit/_form-operation-predicate` — `src/my/edit.clj:41-43` | `register-core-predicate!` | Same. |
| `seon.blob/_input-stream-predicate` — `src/seon/blob.clj:28-30` | `register-core-predicate!` | Same. |
| `seon.schema/_byte-array-predicate` — `src/seon/schema.clj:620-621` | `register-core-predicate!` | Same. |
| `seon.schema/_malli-form-predicate` — `src/seon/schema.clj:739-740` | `register-core-predicate!` | Same. |
| `seon.schema/_registry-init` — `src/seon/schema.clj:680` | Installs `seon-registry` as Malli's process-global default | Explicit bootstrap integration may be sound; current cluster-specific backing is not. |
| `seon.schema/_inst-type` — `src/seon/schema.clj:685-686` | Merges primitive forms into candidates | Unsound declaration authority; declare/acquire as data. |
| `seon.schema/_lookup-ref-value-type` — `src/seon/schema.clj:690-692` | Adds `:seon.db/lookup-ref-value` | Unsound declaration authority. |
| `seon.schema/_ref-type` — `src/seon/schema.clj:699-704` | Adds `:seon.db/ref` | Unsound declaration authority. |
| `seon.schema/_registry-key-type` — `src/seon/schema.clj:716-717` | Adds `:seon.schema/registry-key` | Unsound declaration authority. |
| `seon.schema/_malli-form-type` — `src/seon/schema.clj:742-747` | Adds `::malli-form` | Unsound declaration authority. |
| `seon.schema/_definition-type` — `src/seon/schema.clj:749-750` | Adds `:seon.schema/definition` | Unsound declaration authority. |
| `seon.schema/_value-type` — `src/seon/schema.clj:751-752` | Adds `:seon.schema/value` | Unsound declaration authority. |
| `seon.schema/_explanation-type` — `src/seon/schema.clj:753-754` | Adds `:seon.schema/explanation` | Unsound declaration authority. |
| `seon.schema/_namespace-name-type` — `src/seon/schema.clj:755-756` | Adds `:seon.schema/namespace-name` | Unsound declaration authority. |
| `seon.schema/_kvs-type` — `src/seon/schema.clj:757-758` | Adds `:seon.schema/kvs` | Unsound declaration authority. |
| `seon.schema/_discarded-keys-type` — `src/seon/schema.clj:759-761` | Adds discarded-key set schema | Unsound declaration authority. |
| `seon.schema/_projection-row-type` — `src/seon/schema.clj:762-767` | Adds projection-row tuple | Unsound declaration authority. |
| `seon.schema/_projection-rows-type` — `src/seon/schema.clj:768-772` | Adds projection-row collection | Unsound declaration authority. |
| `seon.schema/_projection-input-type` — `src/seon/schema.clj:773-788` | Adds projection input map | Unsound declaration authority. |
| `seon.schema/_projection-type` — `src/seon/schema.clj:789-790` | Adds projection map predicate | Unsound declaration authority. |

## Ranked findings

1. **Blocker — process-global database-derived schema state.** One mutable
   projection cannot serve co-hosted sovereign clusters. The repair owner and
   proof are in
   [docs/seon/issues/process-global-schema-state-crosses-cluster-bases.md](../../../seon/issues/process-global-schema-state-crosses-cluster-bases.md).
2. **Blocker — dropped core-fault truth is process-only.** Preserve the
   nonblocking overflow path but commit the observation through the fault
   committer. See
   [docs/seon/issues/dropped-core-fault-count-is-not-durable.md](../../../seon/issues/dropped-core-fault-count-is-not-durable.md).
3. **Friction — load-time registration is a second schema authority and goes
   stale across reload.** See
   [docs/seon/issues/load-time-schema-sentinels-bypass-basis-acquisition.md](../../../seon/issues/load-time-schema-sentinels-bypass-basis-acquisition.md).
4. **Cleanup — schema source provenance is an accumulated global derivation.**
   See
   [docs/seon/issues/schema-source-provenance-accumulates-in-a-global-atom.md](../../../seon/issues/schema-source-provenance-accumulates-in-a-global-atom.md).
5. **Cleanup — ten mutable constructors are plain accumulators or dummy refs.**
   See
   [docs/seon/issues/local-mutable-accumulators-are-derivable.md](../../../seon/issues/local-mutable-accumulators-are-derivable.md).
6. **Existing cleanup — opaque contract generators share mutable process
   objects.** The Flow sample is tracked in
   [docs/seon/issues/flow-generators-reuse-one-mutable-sample.md](../../../seon/issues/flow-generators-reuse-one-mutable-sample.md);
   cluster/web/SCI samples are tracked in
   [docs/seon/issues/opaque-contract-generators-share-live-process-objects.md](../../../seon/issues/opaque-contract-generators-share-live-process-objects.md).

Every new issue above has exactly one row in
[docs/seon/issues/index.md](../../../seon/issues/index.md).

## Calibration: mutation that is exactly right

This is not an atom-elimination verdict. Several refs are the simplest honest
representation of process reality:

- `held-flocks` must retain the exact `FileLock`; reopening merely to ask about
  ownership can release the process's fcntl fence.
- `root-store-holder` must retain the open store and reference count so the last
  co-hosted cluster releases it once.
- `running-instances`, search owners, agent routing, and MCP sessions own opaque
  resources that no database value can contain. Their maps are cleanup handles,
  not durable lifecycle facts.
- the source analysis, shape-validator, identity-only, and SCI installation
  caches are reconstructable compiler artifacts with explicit snapshot or
  projection identity fences.
- work-submission state, watchdog state, try/finally cleanup volatiles, stream
  byte counts, and callback collectors exist only while an invocation or opaque
  protocol is active.
- render keyframes and tab registrations are intentionally losable channel/web
  transport state; settled facts repaint them after restart.

The governing question is therefore not “is it local?” but “would losing it
lose truth, and does an immutable return path or existing fact already express
it?” The sanctioned rows answer no to the first and no to the second. The
violation and deletion rows fail one of those tests.
