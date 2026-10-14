---
title: Context assembly and first eval request cost
date: 2026-10-14
status: measured research
---

# Context assembly and first eval request cost

Performance research for TASK-CHAIN S2 and S3d, measured 2026-10-14 on default. No implementation change.

`seon.context/model-context` reads exchanges, builds `seon.context/current-state`, then renders help and the block.
The smallest useful change is to produce the requested doc views at the render owners: remove test-host and schema-usage
derivations from that view, and carry already-derived data through the request. Do not add another printer or context cache.

## Findings and limits

- The original 382 ms / 898 MB observation is a useful report, not a stable baseline. Valid warm full-context measurements
  here were **158.52 ms / 269.19 MB** and **135.81 ms / 265.83 MB**, for the same **6,013-token prompt**, 2,069-token
  system message and **zero model exchanges**. The excess is in reference rendering, not this agent's history.
- A separate reference-render probe found **148.81 ms / 461.68 MB** for three schema references and
  **153.41 ms / 239.70 MB** for five test references. These are separate windows, not parts to add to the warm total.
  Query/cache conditions materially affect cost; there was no cache clearing or JVM restart.
- Fresh requests measured **393.5 ms** and **332.1 ms**, not 1.0 s. Their later counterparts measured 281.6 and 379.1 ms.
  Thus the older 1.0 s symptom did not reproduce at that magnitude. There is still disproportionate work for one tiny definition.
- **The task's attribution of `:derive` to context acquisition is incorrect.** `seon.batch/derive-step` times
  `seon.turn.batch/derive-form`: analyzing observed writes and constructing program entities. Context acquisition occurs
  in custody, the fork path and the test step. Moving acquisition to agent start cannot remove the derive phase.
- `seon.profile` records per-function time and calls, **not per-function allocated bytes**.
  Allocation below is measured around named synchronous owners or comes from the existing request's entrance, proc,
  writer and commit meters. Per-function allocation inside asynchronous request phases is **unmeasured**; assigning
  the outer thread's bytes to those functions would be false.

The two assigned existing tasks remain the request-cost owners:
`seon.turn/a-fresh-agents-first-eval-request-costs-in-proportion-to-its-forms` and
`seon.mcp/a-fresh-agents-first-eval-request-acquires-its-branch-cheaply`.
Context findings feed S3d and `seon.context/the-current-state-block-of-a-held-task-fits-a-bounded-token-count`.
No task resources or source files were edited.

## Measurement conditions

Default: pid 2041, start 2026-10-13T10:01:53.730Z, committed archive
`cde025f68be2c15f497891999d1f62ad6b30ee0c`. Checkout HEAD at entry:
`984789a27f4f9f2ff4de92e527287687cf1ea9ba`. The measured context, turn, MCP, agent acquisition, batch,
profile, function graph, schema-render and test-render source files had no diff between those commits.

Both status entrances reported all readiness layers present, zero open turns and zero reported regressions, but health
was `:faulted`: one open fault, three agent-error signatures, 35 errored receipts, 17 untested-hot warnings and a cache
discipline warning. This is a degraded shared baseline, not a clean-room benchmark. No default reset, stop, reload,
cache reset, provider call or SCI-as-root evaluation occurred.

Context probes used MCP JVM mode with explicit root/cluster and connection, reading
`:attempt-341f9a9016cf` for `attempt/341f9a9016cf`. They did not transact domain data; the MCP entrance itself
records its ordinary JVM evaluation. Fresh requests used SCI only on the two owned agents
`context-first-cost-probe` and `context-first-cost-probe-b`. Their disposable program entities were never merged.
Both branches were retired successfully after measurement.

MB means decimal allocated MB, not retained heap. `seon.profile/measure`'s allocation counter is the calling platform
thread; its function deltas, GC and JVM window are process-wide. Nested/inclusive function times are not additive.
Integer per-function times are rounded cumulative differences. An awaiting function's self time can include waiting
while another thread does the work; it is not evidence that its own body burns that CPU.

A later repeat was visibly contaminated: the read-only context window included `seon.db/transact-call` x2 and
`seon.test.cache/input-path?` calls from unrelated work, and the enclosing JVM window allocated 2,134 MB against
786 MB on the evaluation thread. Its total (254.8 ms / 269.35 MB) is not used for function ranking or a claimed gain.
The report therefore presents individual windows, not a statistical before/after improvement.

One initial setup omitted `:seon.boot/cluster-name` from a bare `base-ctx` request. It returned
`:seon.render/request-error` (“Rendering a database value requires a carried profile or a context whose environment
names the cluster.”), at 344.01 ms / 801.99 MB. That sample is excluded. Supplying the actual cluster name produced
the valid 6,013-token prompt; the erroneous sample cannot validate the historical 898 MB figure.

## Context decomposition

Same immutable branch database and held block within one JVM evaluation; each row has its own
`seon.profile/measure` window. Result handle `result/e700e82599fd0`.

| Owner / work | ms | thread MB | reads | instrumented calls |
|---|---:|---:|---:|---:|
| `seon.context/exchanges` | 0.48 | 0.48 | 2 | 303 |
| `seon.context/current-state` | 29.59 | 39.18 | 543 | 19,828 |
| Help value through `seon.sci.eval/shown-result` | 4.42 | 5.27 | 3 | 7,702 |
| Held block through `seon.sci.eval/shown-result` | 104.21 | 219.59 | 1,096 | 84,648 |
| Whole `seon.context/model-context` | 135.81 | 265.83 | 1,645 | 112,634 |

The earlier block-only window was 71.58 ms / 95.40 MB; `seon.db/vector-query-call` x90 accounted for 38 ms self.
In the warm whole-context window, `seon.fn/gate-edges` x5 consumed 21 ms self, `seon.fn/gate-set-in` x10 13 ms,
`seon.db/pull-logical` x144 11 ms, and `seon.db/vector-query-call` x130 5 ms.
The prompt alone accounted for 22 ms self in `gate-edges` and 14 ms in `gate-set-in`.

A separate component window (`result/eac64289d31f0`) exposes which render owners allocate:

| Named synchronous operation | ms | thread MB | reads | decisive measured function |
|---|---:|---:|---:|---|
| Eight function must-read renders | 4.62 | 6.45 | 8 | Small beside test/schema work |
| Three schema must-read renders | 148.81 | 461.68 | 12 | `seon.db/vector-query-call` x9, 144 ms self |
| Five test must-read renders | 153.41 | 239.70 | 1,090 | `gate-edges` x5, 53 ms self; `gate-set-in` x10, 20 ms |
| `seon.test/host` for the reference regression | 19.42 | 29.97 | 215 | `gate-edges` x1, 9 ms self |
| `seon.fn/gate-edges` alone | 12.71 | 19.98 | 22 | 9 ms self |
| `seon.turn/turns-left` | 0.93 | 0.81 | 6 | Small here |
| `seon.context/task-sections` | 31.10 | 34.75 | 521 | Query x73, 5 ms self; pull x112, 2 ms |

A further direct `seon.render.schema/usage` probe (`result/e9072dbe86e97`) measured
`:seon.ref/section-error` at 138.66 ms / 373.35 MB,
`:seon.ref/section-missing` at 21.79 ms / 27.88 MB and
`:seon.ref/path` at 20.74 ms / 28.20 MB. Each made three queries; query self times were 137, 20 and 19 ms.
These establish expensive usage reads, but do not isolate which of the three queries or the query engine's internal
planning/materialization caused the allocation. “Three indexed queries” in the usage docstring is not a cost proof.

### What goes beyond the requested work

1. **Test host reports:** `seon.render.test/render-ai` at distance 1 calls `seon.test/host` for every test.
   That calls `seon.test/destructive-reach`, which rebuilds the incoming graph through `seon.fn/gate-sets`.
   `seon.fn/gate-edges` queries the program's function/test identities, all test names, declaration callers,
   unresolved file references and effect handlers, then builds maps. Five references repeat this program-scale
   acquisition five times. A doc view needs name, status and referenced/reached functions; it does not need a host report.
   This is the largest repeatedly measured context waste.
2. **Schema usage reports:** `seon.render.schema/selection` at distance 1 calls `seon.render.schema/usage`.
   It queries previous form, reverse schema references and every matching contract, sorts the full matches,
   then selects eight displayed names. The doc view already holds the schema form and needs its description.
   The full reverse usage report belongs to an explicit detail read. No query-planner change is required to remove
   these nine queries from this context.
3. **Eager work before the printer:** `seon.context/current-state-ai` maps every must-read through its renderer
   before the value printer applies its budget. The measured population is eight functions, three schemas and five tests.
   Source lines and expensive reports are fully prepared even when the final prompt cuts them.
   The companion doc-view study reports 91 elisions. A smaller printed prefix is not a cheaper producer.
4. **Block/reference derivation:** `seon.context/task-sections` accounts for 521 of 543 block reads;
   `seon.ref/inner` is called 17 times. The function-source rendering itself is cheap in this sample, but the block
   resolves callee references that §3a does not require for doc views. S3d should resolve contract schemas once,
   not resolve all callees and discard them afterwards.
5. **History and re-printing:** `seon.context/exchanges` calls `seon.eval/of-agent` once, which pulls and sorts
   every evaluation, then filters to turns with model attempts. Thus non-model evaluations are read before being discarded.
   `seon.repl/entity-emission` and `seon.repl/reply-text` reconstruct reply envelopes on each assembly.
   `seon.repl/value-text` returns saved shown text unchanged: there is no evidence here of re-rendering each historical
   result value. With zero exchanges and 0.48 ms history time, history is a scaling hypothesis, not this sample's cause.
6. **Held values:** help is rendered again on each call (4.42 ms / 5.27 MB here). The block separately reads the held
   task, its sections and its verdict; S2 should carry its new explicit task/results value through those consumers.
   Do not cache a verdict across changed task input or proof evidence. The first optimization is one acquisition and
   value passing; reuse by content/read evidence comes only where the repeated measurements justify it.

## First eval request decomposition

Actual first source, through `seon.mcp/call` inside JVM `seon.profile/measure`, with a fresh named SCI agent:

```clojure
(in-ns 'probe.context-first-cost)
(defn identity-value {:malli/schema [:=> [:cat :int] :int]} [x] x)
```

Later requests on the second agent used the same two-form shape and new names
`identity-value-later` and `identity-value-third`. They had no task and no reaching tests;
these measurements do not stand in for an assigned task's proof execution.

| Reply stage, ms | Fresh A | Fresh B | Later B | Third B |
|---|---:|---:|---:|---:|
| Whole request | 393.5 | 332.1 | 281.6 | 379.1 |
| custody | 86.4 | 86.0 | 0.5 | 0.5 |
| derive | 21.3 | 20.7 | 13.3 | 17.0 |
| evaluate | 29.6 | 28.3 | 24.4 | 29.8 |
| settle | 112.9 | 101.0 | 114.8 | 159.2 |
| test-step | 88.7 | 56.9 | 86.4 | 108.0 |
| fork | 0.2 | 0.3 | 1.3 | 1.9 |
| unattributed | 52.6 | 37.2 | 39.7 | 60.7 |

Read/facts/apply/print make up the remaining 1.4–2.0 ms. The measure windows enclosing the full MCP call were
398.06, 336.18, 285.91 and 383.56 ms. Their JVM-window allocation was 492.96, 440.76, 350.48 and 357.27 MB,
including other threads and unrelated activity. The 332.1/281.6 ratio is 1.18, already inside the task's proposed
2x comparison; that ratio alone would miss the surviving work.

| Existing allocation meter, MB | Fresh A | Fresh B | Later B | Third B |
|---|---:|---:|---:|---:|
| Entrance | 26.0 | 31.2 | 5.4 | 15.5 |
| Proc step (includes forms) | 256.4 | 230.2 | 236.8 | 237.9 |
| Forms only (subset of step) | 42.3 | 42.6 | 23.4 | 23.2 |
| Writer processing | 41.29 | 41.27 | 36.63 | 35.78 |
| Commit | 50.67 | 52.07 | 49.81 | 46.80 |

These meter scopes must remain separate; forms are not additive to the proc step. Allocation for custody alone,
derive alone or each async instrumented function is not available from these counters.

Selected `seon.profile/observation` deltas (calls / inclusive ms), result handles
`result/e4d8e09b55f2d`, `result/ec54106362fc2`, `result/ea6cc3ef7f913`:

| Function | Fresh B | Later B | Third B |
|---|---:|---:|---:|
| `seon.mcp/execution` | 1 / 86 | 1 / 0 | 1 / 0 |
| `my.agent/create-branch!` | 1 / 51 | 0 | 0 |
| `seon.cluster.host/register!` | 3 / 20 | 0 | 0 |
| `seon.cluster.host/host!` | 1 / 14 | 1 / 0 | 1 / 0 |
| `seon.cluster.agent/acquire-context!` | 3 / 46 | 1 / 57 | 1 / 77 |
| `seon.sci.eval/acquire!` | 4 / 19 | 2 / 43 | 2 / 61 |
| `seon.sci.eval/fork-for-turn` | 3 / 5 | 2 / 0 | 2 / 1 |
| `seon.turn/settle-batch!` | 1 / 91 | 1 / 104 | 1 / 146 |
| `seon.turn/test-step` | 1 / 55 | 1 / 86 | 1 / 107 |
| `seon.test/request-changes` | 1 / 4 | 1 / 16 | 1 / 9 |
| `seon.turn/run-reaching` | 1 / 15 | 1 / 11 | 1 / 18 |
| `seon.test/select` | 1 / 15 | 1 / 10 | 1 / 17 |
| `seon.db/transact-call` | 3 / 124 | 1 / 93 | 1 / 132 |

In the third request, `seon.turn.batch/derive-form` x2 totaled 17 ms, matching the derive stage.
`seon.cluster.agent/adopt-cluster-declarations!` x1 totaled 17 ms.
`seon.schema/projection-with-declarations` x7 totaled 22 ms; `seon.schema/retained-population` x20 totaled 11 ms.
The ranked measure counted 19,074 `seon.schema.datahike/declared-storable?` entries and 8,920
`seon.schema.internal/entity-maps` entries for the one new definition.
`seon.turn.batch/program-facts-call` and `seon.turn.batch/program-facts` each ran once and were below the
integer-millisecond resolution. Blaming repeated fact construction for the measured settlement would be unsupported.

### What each phase requires and what to change

**Custody.** It must route the request, create the fresh branch and durably establish the agent, then host its graph.
The measured extra is 86 ms, with branch creation 51 ms, registration 20 ms and hosting 14 ms (nested).
`seon.cluster.registry/branch!` x2 had 33 ms self in the fresh window.
A branch shares indexes but still publishes durable branch/roster information; “pointer” does not prove a
single-digit total over file storage. The existing MCP task should measure the dependency branch operation and
registration transaction independently before redesigning their order. Reuse the already-owned branch connection
and base context when starting the next chain agent; S3a already specifies that path. Moving this cost earlier is
latency relocation, not a total-work gain. S2/S3d do not eliminate fresh external-agent custody.

**Derive.** Analyze the form's observed writes once, join source spans and produce the changed entities.
The 208 ms older number was not reproduced (13–21 ms here).
`seon.fn.analyzer/invoke-kondo` runs once at roughly 4–5 ms self.
Keep source analysis and changed declarations carried through the fold; verify any repeated analysis before fixing it.
This data does not justify a new analyzer, cache or startup phase. S2's task-input change should not widen this phase.

**Settle.** Build the recording transaction, validate it and synchronously commit before replying.
The writer and commit allocate roughly 36–41 and 47–52 MB for the definition, while the waiting
`seon.db/transact-call` takes 93–132 ms. Most measured self time is the wait; the direct fact builder is cheap.
Schema projection derivations repeatedly scan populations: `seon.schema/retained-population` reduces over every
identity even when nothing is removed; `projection-with-declarations` computes whole-map declaration differences.
Optimize at `seon.schema/projection-advanced-by` / `projection-with-declarations` using the held base and admitted
changed identities, preserving the retained compiled Malli schemas. This survives both S2 and S3d.
It remains a candidate: the measured scans establish repeated work, but their removal's end-to-end gain is not yet proven.

**Test step.** For this new isolated function, determine that no test reaches it and return.
Today `seon.turn/test-step` acquires a context before `run-reaching` discovers the empty selection.
Later B spends 57 of 86 ms in `acquire-context!`; Third B spends 77 of 107 ms.
Select tests from the held immutable database/task input first; acquire the execution context only as part of executing
the selected work. That removes unused execution setup without refusing, skipping or weakening any required test.
S2b must absorb this ordering into its whole-branch selection, with S2a's explicit task value carried into selection,
run identity and result reads. Do not optimize the soon-deleted tag/obligation wrappers in S2c.
Once a test really must run, reuse program-content-valid acquisition rather than reacquiring several equivalent values.
That acquisition improvement survives S2; S2 must still select the whole branch and run the task's proofs last.

**Unattributed work.** 37–61 ms remains outside the reported phase clocks. It is included in the end-to-end measurement,
not assigned to a guessed owner. The current probe cannot honestly charge it to history or SCI. Follow the request's
setup/reporting boundaries in `seon.turn/step` before setting a phase-specific budget.

## Changes for the owning slices and their proofs

All proposed performance commits require the same probe on parent and candidate on a quiet JVM, with the same source,
task, branch program and render profile. Record wall time, thread allocation where available, proc/writer/commit
allocation for async work, GC and output correctness. Counts support the time proof; they do not replace it.

| Change / ownership | TASK-CHAIN relation | Red behavior/cost proof to add before implementation |
|---|---|---|
| Doc views do not derive test host reports or schema usage | **S3d absorbs** in `seon.render.test/render-ai`, `seon.render.schema/selection`, `seon.context/must-read-ai` | Extend planned `seon.context-test/a-referenced-function-shows-its-doc-view` to the three kinds; measure whole `model-context`, assert doc data/read forms and zero `seon.test/host` / `seon.render.schema/usage` calls for doc depth; retain full-detail behavior. Wall time and allocation must fall from the same parent sample. |
| Resolve only the contract schemas needed for doc views, once | **S3d absorbs**, at `seon.ref/inner` and reference assembly | Same end-to-end probe, schemas cited once, no callee-only entries; assert fewer producer reads, no lost required references, and a measured whole-context gain. |
| Share graph acquisition for explicit full host reports | **Survives S3d**, but lower priority after those reads leave the block | Measure `seon.test/host` for the same five names in one operation; batch shared graph derivation or reuse it by program read evidence. Prove relevant edge changes move the answer; unrelated writes do not rebuild it. |
| Carry task, selected tests and current results once | **S2a/S2b absorb** | Measure the real assigned task's first request and next `model-context`, with two tasks sharing a template proof. Preserve distinct task input identities and invalidate on plan/proof changes. Use S2a's planned `seon.task-test/a-tasks-proof-is-recorded-and-read-under-its-task`. |
| Select before execution acquisition | **S2b absorbs**; general content-valid acquisition **survives** | Measure the fresh two-form request below and one with an actual reaching test. Empty selection performs no extra test-execution acquisition; the actual test still runs on the right branch. Whole-request wall/step allocation must fall; first/later ratio alone is insufficient. |
| Projection work follows changed declarations | **Survives S2/S3d**; existing `seon.schema/a-function-contract-changes-the-schema-projection` is related | Measure the contracted definition request on parent/candidate, vary unrelated program population while holding changed reach fixed, preserve projection equivalence and Malli retained schema identity. No proposed encoding change without measured benefit. |
| Read model exchange history once and reuse immutable printed units | **Survives**, conditional on a nonempty-history profile | Measure `model-context` for an agent with N and 2N exchanges, plus non-model evaluations; select model evaluations before pulling, retain saved shown values, and test byte equality of replay. Current zero-history probe cannot prove this gain. |
| Reuse help render by content/profile/printer inputs | **S1d absorbs** its help rewrite; general render reuse **survives** | Measure whole `model-context`; repeat unchanged help and then change a cited schema/doc to prove correct invalidation. The present 4.42 ms is a small opportunity, not the priority. |

Do not set an arbitrary target from the old 382 ms. The first budget follows the work removed: zero host/usage
derivations in a doc view, one resolution of its required references, one read of the selected exchange history.
The user-visible proof is a faster complete `model-context` with complete doc data, not a smaller truncated prompt.
Likewise, the request proof includes startup plus first response as well as the response latency; moving work before
the timer is not a performance win.

## Dependency seams

Pins in HEAD (also the installed archive's relevant source seam): Datahike
`c9e57c6fb65365d5ce92b59854fb02ea59054fe7`, SCI
`eb3290d5c5ed72133ae1947492ecd324413868ed`, Malli
`dd13ff81139159778edb0a67b491eccc0baa991d`.

- `datahike.versioning/branch!`: takes source commit/branch and destination, shares copy-on-write indexes and publishes
  branch/roster state. Called only for actual new branches; cost includes durable I/O, not copying the program.
- `sci.core/fork`: takes the held context and makes a generation-isolated environment. Recompute on actual fork;
  no namespace traversal in its body. Use this mechanism, not a custom context copier.
- `datahike.query/source-context-unchanged?` is a **private maintained-fork seam**, not a proposed public call.
  It checks connection id, generation, conservative revision and relevant attribute revisions. Use Seon's
  `seon.db/read-evidence-current?` for consumers; equal revisions across different connections are not proof.
- `datahike.pull-api/pull-many` returns input-aligned pulls; where all selected references are needed, batch their
  acquisition. For a prefix consumer, keep the reads lazy within the consumer's scope.
- `malli.core/validator` uses `malli.core/-cached` on the compiled schema object. Retaining that object preserves
  its compiled validator; repeatedly rebuilding an equivalent registry does not buy that reuse.

## Reproduction

These are probe expressions, not production definitions. Read-only context probes run as JVM root with explicit
cluster `default`. No source loading or Var replacement is involved. The context setup is outside the measured
assembler; its cost is not claimed as part of `model-context`. Check errors and shown-result diagnostics, not only timing.

### Whole context and components

```clojure
(let [c (seon.cluster.boot/connection "default")
 d (seon.db/branch-database @c :attempt-341f9a9016cf)
 h (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "default"))
 ctx (seon.sci.eval/base-ctx d)
 req {:seon.boot/cluster-name "default" :seon.db/connection c :seon.sci.admit/caps (:seon.sci.admit/caps h) :seon.sci.eval/ctx ctx :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms h)}
 p (seon.profile/measure #(seon.context/model-context d req "attempt/341f9a9016cf"))
 v (:seon.profile/value p)]
 (assoc (dissoc p :seon.profile/value :seon.profile/called :seon.profile/ranked-by-calls)
 :shape (if (:seon.error/at v) v {:system-tokens (seon.ai.tokens/estimate (:seon.ai/system v)) :prompt-tokens (seon.ai.tokens/estimate (:seon.ai/prompt v)) :exchanges (count (:seon.context/exchanges v))})))
```

The component probe reuses one database, context and block:

```clojure
(let [c (seon.cluster.boot/connection "default") d (seon.db/branch-database @c :attempt-341f9a9016cf)
h (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "default"))
ctx (seon.sci.eval/base-ctx d)
req {:seon.boot/cluster-name "default" :seon.db/db d :seon.db/connection c :seon.agent/id "attempt/341f9a9016cf" :seon.sci.admit/caps (:seon.sci.admit/caps h) :seon.sci.eval/ctx ctx :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms h)}
block (seon.context/current-state d "attempt/341f9a9016cf")
fs [[:exchanges #(#'seon.context/exchanges d "attempt/341f9a9016cf")]
[:block #(seon.context/current-state d "attempt/341f9a9016cf")]
[:help #(seon.sci.eval/shown-result (seon.bootstrap/help-value d) req nil)]
[:prompt #(seon.sci.eval/shown-result block (assoc req :seon.render.call/function 'seon.context/current-state) nil)]
[:total #(seon.context/model-context d req "attempt/341f9a9016cf")]]]
(mapv (fn [[label f]] (let [p (seon.profile/measure f)] [label (assoc (select-keys p [:seon.profile/ms :seon.profile/alloc-bytes :seon.profile/reads :seon.profile/calls :seon.profile/gc-ms]) :hot (take 5 (:seon.profile/ranked-by-self p)))])) fs))
```

Per-kind and direct owner allocation probe:

```clojure
(let [c (seon.cluster.boot/connection "default") d (seon.db/branch-database @c :attempt-341f9a9016cf)
 h (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "default"))
 req {:seon.boot/cluster-name "default" :seon.db/db d :seon.db/connection c :seon.agent/id "attempt/341f9a9016cf" :seon.sci.admit/caps (:seon.sci.admit/caps h) :seon.sci.eval/ctx (seon.sci.eval/base-ctx d) :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms h)}
 block (seon.context/current-state d "attempt/341f9a9016cf")
 brief (fn [p] (assoc (select-keys p [:seon.profile/ms :seon.profile/alloc-bytes :seon.profile/reads :seon.profile/calls]) :hot (take 3 (:seon.profile/ranked-by-self p))))
 groups (group-by #(cond (:seon.test/sym %) :test (:seon.fn/sym %) :function :else :schema) (:seon.context/must-reads block))]
 {:counts (update-vals groups count)
 :renders (into {} (map (fn [[k xs]] [k (brief (seon.profile/measure #(mapv (partial #'seon.context/must-read-ai req) xs)))])) groups)
 :host (brief (seon.profile/measure #(seon.test/host d 'seon.ref-test/a-reference-with-no-path-is-a-missing-file)))
 :gate-edges (brief (seon.profile/measure #(#'seon.fn/gate-edges d)))
 :turns (brief (seon.profile/measure #(seon.turn/turns-left d "attempt/341f9a9016cf")))
 :sections (brief (seon.profile/measure #(seon.context/task-sections d "attempt/341f9a9016cf")))})
```

### Fresh request and instrumented function deltas

Use a new agent name for each first-request run; reusing the name measures a later request. The outer JVM request
only observes the nested ordinary SCI request. The nested request owns its branch. The last reply item is the request
cost map for this installed MCP version; production tests should use the shared content-based reply reader.

```clojure
(let [c (seon.cluster.boot/connection "default")
 selected '#{seon.turn.batch/derive-form seon.turn.batch/program-facts-call seon.turn.batch/program-facts seon.schema/projection-with-declarations seon.schema/retained-population seon.sci.eval/fork-cluster-ctx seon.cluster.agent/adopt-cluster-declarations! seon.test/changes seon.test/reaching seon.sci.eval/program-key seon.schema/range-differences seon.mcp/execution seon.cluster.host/register! my.agent/create-branch! seon.cluster.host/host! seon.cluster.agent/acquire-context! seon.sci.eval/acquire! seon.sci.eval/fork-for-turn seon.turn/next-agent-work seon.turn/settle! seon.turn/settle-batch! seon.turn/test-step seon.turn/run-reaching seon.turn/step-reds seon.test/request-changes seon.test/run-request seon.test/select seon.fn/gate-edges seon.db/loaded-builder seon.db/transact-call seon.turn.world/acquire}
 snapshot (fn [] (reduce (fn [m cell] (let [o (seon.profile/observation cell) s (:seon.profile/sym o)] (if (selected s) (update m s #(merge-with + (or % {}) (select-keys o [:seon.profile/calls :seon.profile/total-ms :seon.profile/self-ms]))) m))) {} (seon.profile/cells)))
 before (snapshot)
 p (seon.profile/measure #(seon.mcp/call "{\"name\":\"eval_clj\",\"arguments\":{\"cluster\":\"default\",\"agent\":\"context-first-cost-probe-b\",\"mode\":\"sci\",\"code\":\"(in-ns 'probe.context-first-cost) (defn identity-value {:malli/schema [:=> [:cat :int] :int]} [x] x)\"}}"))
 after (snapshot)
 reply (clojure.data.json/read-str (:seon.profile/value p))]
 {:measure (dissoc p :seon.profile/value :seon.profile/called :seon.profile/ranked-by-calls)
 :request (clojure.edn/read-string (get (last (get reply "content")) "text"))
 :error (get reply "isError")
 :functions (into {} (keep (fn [[s v]] (let [d (merge-with - v (get before s))] (when (pos? (:seon.profile/calls d)) [s d])))) after)})
```

Do not sum inclusive function deltas or use the outer thread's allocation as the asynchronous request's allocation.
Save the inner request cost map, including its proc/writer/commit scopes, and verify the source returned without an error.

## Delivery and foreign boundary

Only this Markdown file is claimed by lane `context-first-cost`; source/test delta is 0/0.
The shared tree contains other lanes' changes (including `resources/seon/tasks/my.program.edn` and untracked research),
which are outside this candidate. Existing claims `ns-unmap` and `world-refusal` were left alone.
The sampled read-only repeat exposed concurrent work, so no claim of a quiet paired improvement is made.

Full requested lint: `clj-kondo --lint src script test --cache false`, 8,982 ms, **0 errors**, 1,662 warnings.
No test reaches a Markdown research document. The named candidate check is the measurement seam's existing regression
`seon.profile-test/calls-of-counts-a-function-past-the-hot-limit`; it does not prove any proposed optimization.
Candidate check/commit result is recorded below after execution.
