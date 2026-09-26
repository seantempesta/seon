---
type: report
status: incomplete — live runtime disappeared during investigation
created: 2026-09-26
scope: dogfood-analyst-astra; SCI branch-only system questions
---

# Dogfood analyst: temporary agent answering system questions

The installed entrance is MCP branch → SCI agent turn → seon.db / my.program → rendered value.
I used immutable queries and existing program owners; no production code, tests, schemas, default Vars, or other lane files were changed.

## Blockers and ranked findings

| Severity | Finding and exact evidence | Suspected model error / existing issue |
|---|---|---|
| blocks work | Probe 5 returned only timeout after 60,258 ms when producing a string-keyed sorted map. Runtime then showed a new ClassCastException at seon.schema/valid-candidate-value?. Retiring this first branch succeeded in 582 ms. | Consistent with [sorted-map error validation](../../seon/issues/armed-error-validation-throws-on-non-keyword-sorted-maps.md) and [unsettled failed run](../../seon/issues/a-query-with-a-regex-literal-cannot-record-its-read-and-leaves-the-run-open-forever.md). Cause not independently inspected. |
| blocks work | Probe 12, the documented positional bulk gate-sets over all function symbols, timed out after 60,265 ms. No tests were requested. Retirement timed out twice; later status threw “flow not running”; then the connection refused and runtime_status listed no clusters. | Bulk coverage derivation cannot currently serve this analyst within its bound. Existing [gate-set cost issue](../../seon/issues/gate-set-rederives-the-declared-reference-population-on-every-call.md). Runtime disappearance is observed, not attributed to this query or another lane. |
| ugly | Probe 3's dir returns a large escaped string containing expanded contracts; request keys disappear in several declarations. Probe 8's raw agent pull expands historical turns into long nested strings. | Existing [doc expansion](../../seon/issues/doc-replaces-every-schema-keyword-so-request-keys-disappear.md), [nested renders bypass floor](../../seon/issues/a-raw-pull-inlines-nested-declared-render-text-past-the-floor.md). |
| confusing | Probe 9's error operation names a recording boundary; runtime status names a deeper function for the same signature. | “Function produces each error” needs an explicit distinction between recording operation and causal frame. No unsupported claim of a unique producer. |
| confusing | seon.cluster.status/snapshot says store bytes require bin/seon status --verbose; no size entrance was found by apropos “size”. | A REPL-only analyst can read heap use, but physical store footprint remains unavailable through the discovered entrance. |
| confusing | The REPL skill's “A lane works on its own branch” section says MCP defs are private only and write-back is unbuilt. Live tool documentation and my.program discovery contradict those claims. | Stale skill claims; recorded here because this lane owns no skills and is prohibited from source edits. |

No source files were read to learn APIs or diagnose these defects. Instructions, named prior report, skills, and existing issue documents were read. No gate, test run, provider run, restart, signal, worktree, merge, or write-back was performed.

## Answers

### 1. Functions changed in 24 hours, authors, and tests

**Incomplete.** Discovered history and transaction provenance using
`(my.program/history {:seon.program/subject 'my.plan/current})` in probe 8 (2,496 ms for the combined probe).
It returned one source-add event at transaction 536870917, process `seon.db.process/boot`, definition digest
`47e52bec84f9a40c48b7c8af1e414729edf9431506b4416cdcfaf863d0a86a0c`.
This does not establish the 24-hour window or identify an author agent/turn.
Probe 9 (862 ms combined) identified one current gating test:
`my.plan-test/current-refuses-an-unknown-agent-by-name`.
A temporal source-change query joined to transaction time and receipt/agent/turn provenance remains necessary; it was not run before the runtime became unavailable. Boot provenance is not an author.

### 2. Ten most frequent error signatures and producing functions

Probe 9 returned **three signatures**, each with summed occurrence count **1** in this branch snapshot:

| Signature | Stored :seon.error/operation | Sum |
|---|---|---:|
| 9e61d0ee4ec1adc7dde5c22760c755cf79af45e9fa03c855f1d0c2aed198fe54 | seon.flow/work-launcher-step | 1 |
| 48f7332b1576c386016c8c72140993e7807ab506f9fe595fcdd311a41ee50ad5 | seon.db/transact! | 1 |
| fa60ad84404c9720c3e6cd72ecdba9756f92bde2721e5568cf43d96d54a72aa3 | seon.cluster.agent/turn-step | 1 |

All are tied, so no ranking distinction exists. The query used an inner join on operation and occurrence count; signatures without these members would need a separate census before treating this as exhaustive.
Runtime status on the current cluster reported the first signature at `seon.flow/release-admission!` and the third at `seon.schema/valid-candidate-value?`, with count 2 for the third. These are different observations and scopes, not interchangeable counts.
The recorded operation is available; a verified causal producer for every signature is not.

### 3. Namespaces with most functions without a reaching test

**No answer returned.** Probe 12 calls the discovered bulk `seon.fn/gate-sets`, then counts empty gate sets by namespace.
It timed out at 60,265 ms, so there is no justified top-ten list.
The gate set is the installed selection owner, not proof of current observed dynamic execution coverage.
The preamble's safety bound stopped this route; it was not looped or replaced with thousands of single-function calls.

### 4. One namespace six hours ago and diff to now

**Incomplete.** Probe 4 discovered `seon.db/as-of` with arities `[time-point]` and `[database time-point]`, and `history` with explicit/current database arities.
Probe 8 proved single-function source history is readable.
No six-hour namespace snapshot or diff was evaluated before the runtime became unavailable; no claim that historical data is absent.

### 5. Agents, tasks, branches, and currently running agents

Probe 11's snapshot reported **36 agent rows**, **1 open turn**, 169 platform threads and 29 virtual threads.
Probe 8 read agent rows with ids, branches, namespace, admission-closed transaction, plan and runtime.
Examples: `root`, `mcp/dogfood-author-opus-1`, `mcp/audit-agent-restrictions`, `mcp/inv-run-stab-c`; MCP agent ids generally name their branch.
The raw pull rendered plan/runtime components into substantial text, including root's historical turns.
**Incomplete:** no compact full task/branch roster or per-agent live execution census was obtained.
An agent row without admission-closed-tx is not proof that its graph is running.
Initial runtime_status reported one observed Flow agent and three procs. Later status failed on a stopped Flow; the final observation listed no live cluster.

### 6. Store size and dominant entity kinds

Probe 11 (718 ms combined) reported heap-used **5,097,908,656 bytes**, heap-max **10,997,465,088 bytes**.
These are JVM memory, **not store size**.
The store-bytes field said: “Store bytes are not scanned during a cluster observation. Use bin/seon status --verbose for an explicit footprint measurement.”
The REPL-only scope excludes that shell diagnostic. No physical byte measurement or entity-kind census was completed.

### 7. One function as a joined value

**Partial, my.plan/current.** Probe 8's history value includes:

- Contract: `[:=> [:cat :my.plan/request] [:or :my.plan/current-value :seon.error/value]]`.
- Callee: `seon.plan/current`, call arity 2.
- File identity: `src/my/plan.clj` (returned by the database, not read from disk).
- Source and definition/analyzed-source digests.

Probe 9 gives the gating test named in answer 1. Callers, recent evaluations, and attributed errors were not joined before loss of the runtime; this is not the requested complete one-value dossier.

### 8. Temporary helper calculation and discard

**Not completed.** The discovery and coverage computation preceded helper authoring; the runtime became unavailable before any helper defn was attempted.
No helper definitions exist from this lane and nothing was proposed for merge.
A bounded follow-up could define pure aggregation helpers over held query rows, calculate namespace concentration, remove them with my.program/ns-unmap!, and retire the branch.

## Presentation and retrieval

Normal structural output is useful: probe 7 fit nine attribute groups into a readable value, and probe 9's error table and test list were compact.
Probe 4 showed `…412` and a real `result/e7ade17eac906` get-in handle, but no MCP blob digest. Its footer explains counts, not the specific bound causing this cut.
The dir string and raw agent pull produced walls of escaped text; raw pull also rendered nested entities as prose.
Later runtime-status failure had a named max-children elision, count 72, next-offset 32, path and blob digest.
The get_value attempt used JSON-looking `["cause"]` and correctly refused because the underlying map has keyword keys. Its refusal repeated the large offending value. The corrected `[:cause]` request encountered Connection refused, so successful retrieval was not proven.
This is mixed presentation quality, not a passing floor/get_value workflow.

## Missing entrances (tried, with limits)

- Physical store footprint: apropos “size” returned (); snapshot {} explicitly points to the shell. No REPL entrance found.
- Bounded whole-program coverage answer: documented bulk gate-sets tried; timeout, no result.
- Reliable branch retirement after timed-out work: first retirement worked; second branch retirement timed out twice.
- Health observation with a stopped graph: runtime_status tried; “flow not running” exception instead of a useful fleet result.
- Successful MCP cut retrieval: get_value tried with wrong string key, then corrected keyword path; runtime lost before success.
- 24-hour audit, six-hour namespace diff, full roster, and full function dossier were unfinished investigations, **not demonstrated absent APIs**.

## What worked well

Branch creation and initial SCI acquisition worked. Fully qualified program functions were callable without file reading.
seon.db/q accepted native Datalog joins/aggregates and supplied branch custody.
my.program/history exposed source, definition facts and transaction process provenance.
my.program/tests-reaching found the newly present test for my.plan/current.
seon.cluster.status/snapshot exposed memory and distinguished unmeasured store size.
The first branch retired successfully; ordinary successful probes mostly completed in 0.7–0.95 seconds externally.

## Cleanup and verification limits

Branches created: dogfood-analyst-astra (retired), dogfood-analyst-astra-2 (two retirement timeouts; final attempt refused because no live REPL was advertised).
The second branch was still listed after the first retirement timeout. Later the JVM refused connections and runtime_status listed zero clusters.
**Root recovery / cleanup needed:** after the shared JVM is available, inspect and retire dogfood-analyst-astra-2; this lane did not stop or reset default.
All timings below are external MCP wall times including orchestration, not pure query time.
No performance attribution to foreign work is established. No production/source/test lines changed.

The stopped-Flow observation is additional evidence for [status and stopped hosted graphs](../../seon/issues/status-reads-only-the-root-roster-and-a-stopped-hosted-graph-stays-armed.md), found by a final targeted issue search. No duplicate issue was filed. Only this report is owned and released by the lane.

### Probe 0: branch-create — 460 ms

```text
{
  "seon.dev.mcp/root": "/Users/sean/src/seon",
  "seon.dev.mcp/cluster": "default",
  "seon.dev.mcp/action": "create",
  "seon.dev.mcp/value": "{:seon.store/branch :dogfood-analyst-astra, :seon.cluster/created? true, :seon.cluster.registry/from #uuid \"6ab7ecd2-ed6f-5d77-97a5-6eceb3fb980f\", :seon.db/basis-t 536871327}"
}
```

### Probe 1: SCI — 1342 ms

```clojure
(require '[my.program :as program])
```

```text
nil
```

### Probe 2: SCI — 700 ms

```clojure
(do (dir my.program) (doc seon.db/q) (doc seon.db/pull) (clojure.repl/apropos "as-of") (clojure.repl/apropos "stat"))
```

```text
(my.fs/stat my.issue/status seon.call-preparation/state
 seon.call-preparation/state-generator seon.call-preparation/state?
 seon.db/carry-connection-projection-state!
 seon.db/carry-projection-state seon.env/carry-state
 seon.env/environment-state seon.env/environment-state-generator
 seon.env/environment-state? seon.env/state-carrier
 seon.eval.drive/terminal-state seon.instrument/state seon.issue/status
 seon.issue/status-text seon.oversight/flow-status
 seon.schema/call-with-projection-state seon.sci.eval/context-state?
 seon.sci.eval/projection-state seon.sci.eval/projection-state-generator
 seon.sci.eval/projection-state?
 seon.test.runner/live-cluster-schema-states)
```

### Probe 3: SCI — 795 ms

```clojure
[(dir my.program) (doc seon.db/q) (doc seon.db/pull) (clojure.repl/apropos "as-of") (doc seon.db/history)]
```

```text
["{:seon.repl/columns [:sym :arglists :doc :in :out :supplied], :seon.repl/rows [[my.program/accept-merge! ([{context :my.program/context, tested :seon.source/candidate, :as proposal}]) \"Merge exactly the program `prepare-merge!` tested into my cluster\" [:cat [:map [:seon.source/candidate :seon.store/branch] [:seon.test.run/id :seon.test.run/id] [:seon.test/passed? {:optional true} :boolean] [:seon.source/tally {:optional true} :string] [:datahike/expected-basis-t :int] [:parents [:set :seon.source/commit-id]]]] [:or :seon.db/transaction-report :seon.source/test-evidence-error :seon.error/value :my.program/custody-refused-error] [:my.program/context]] [my.program/breaks ([{database :seon.db/db, subject :seon.program/subject, :as request}]) \"Read the referrers a retraction would strand, with a prospective plan.\" [:cat [:map [:seon.program/subject :seon.program/subject] [:seon.program/contract {:optional true} :seon.program/contract]]] [:or :seon.program/breakage :seon.program/read-refused-error :seon.program/not-found-error] [:seon.db/db]] [my.program/callers ([{database :seon.db/db, subject :seon.program/subject, :as request}]) \"Read direct callers and their declaration spans and recorded call arities.\" [:cat [:map [:seon.program/subject :seon.program/subject]]] [:or :seon.program/breakage :seon.program/read-refused-error :seon.program/not-found-error] [:seon.db/db]] [my.program/history ([{database :seon.db/db, subject :seon.program/subject, at :seon.db/tx, :as request}]) \"Read source events and their definition facts and transaction provenance.\" [:cat [:map [:seon.program/subject :seon.program/subject] [:seon.db/tx {:optional true} :seon.db/tx]]] [:or :seon.program/history-report :seon.program/read-refused-error :seon.program/not-found-error] [:seon.db/db]] [my.program/native-call-refusal ([ctx callee arguments]) \"Return a reduced diagnostic for native program mutation, or nil.\" [:cat :seon.sci.eval/ctx :seon.schema/value :seon.schema/arguments] :seon.schema/value nil] [my.program/ns-unalias! ([context namespace-name alias-name]) \"Retract an alias component before removing the alias from fork and base.\" [:cat [:map [:seon.sci.eval/ctx :seon.sci.eval/ctx] [:my.program/base-ctx :seon.sci.eval/ctx] [:seon.cluster/name {:optional true} :seon.cluster/name] [:seon.store/store {:optional true} :seon.store/store] [:seon.agent/routing {:optional true} :seon.agent/routing]] :symbol :symbol] [:or :seon.program/change :seon.program/read-refused-error :seon.program/not-found-error :seon.program/mutation-refused-error] [:seon.db/connection]] [my.program/ns-unmap! ([context subject]) \"Retract a declaration, then unmap its Var in the executing fork and base.\" [:cat [:map [:seon.sci.eval/ctx :seon.sci.eval/ctx] [:my.program/base-ctx :seon.sci.eval/ctx] [:seon.cluster/name {:optional true} :seon.cluster/name] [:seon.store/store {:optional true} :seon.store/store] [:seon.agent/routing {:optional true} :seon.agent/routing]] :qualified-symbol] [:or :seon.program/change :seon.program/read-refused-error :seon.program/not-found-error :seon.program/mutation-refused-error] [:seon.db/connection]] [my.program/overrides ([{database :seon.db/db, :as request}]) \"Read current agent-admitted identities under an indexed src root.\" [:cat [:map]] [:or [:vector :seon.fn/sym] :seon.program/read-refused-error] [:seon.db/db]] [my.program/prepare-merge! ([{context :my.program/context, branch :seon.store/branch, issue :seon.issue/id, selection :seon.program/identities}]) \"Test a proposed branch's change through the merge gate on a fresh branch of\" [:cat [:map [[:map [:seon.db/connection :seon.db/connection] [:seon.sci.eval/ctx :seon.sci.eval/ctx] [:my.program/base-ctx :seon.sci.eval/ctx] [:seon.cluster/name {:optional true} :seon.cluster/name] [:seon.store/store {:optional true} :seon.store/store] [:seon.agent/routing {:optional true} :seon.agent/routing]] [:map [:seon.db/connection :seon.db/connection] [:seon.sci.eval/ctx :seon.sci.eval/ctx] [:my.program/base-ctx :seon.sci.eval/ctx] [:seon.cluster/name {:optional true} :seon.cluster/name] [:seon.store/store {:optional true} :seon.store/store] [:seon.agent/routing {:optional true} :seon.agent/routing]]] [:keyword :keyword] [[:string {:min 1, :seon.db/identity true, :seon.program/row-schema :seon.issue/issue, :description \"The note's file slug for indexed issues; a fresh seon.id/id minted once by the writer for an issue authored in the database; the detector plus its subject for a generated issue.\"}] [:string {:min 1, :seon.db/identity true, :seon.program/row-schema :seon.issue/issue, :description \"The note's file slug for indexed issues; a fresh seon.id/id minted once by the writer for an issue authored in the database; the detector plus its subject for a generated issue.\"}]] [:seon.program/identities {:optional true} [:set :seon.program/declaration-identity]]]] [:or :my.program/merge-proposal :seon.program/three-way :seon.error/value] [:my.program/context]] [my.program/proposals ([{database :seon.db/db, agent-id :seon.agent/id, context :my.program/context}]) \"Read the merge proposals sent to me that are still pending: each message\" [:cat [:map [[:string {:min 1, :seon.db/identity true, :seon.program/row-schema :seon.agent/agent, :seon.wake/arms true, :description \"The agent's identity: the name every message, run, and namespace assignment resolves it by. AN ARMING ATTRIBUTE: asserting it is the one durable evidence that an agent exists, so its commit wakes the cluster's armer and an agent created while the cluster runs is armed without waiting for the next boot.\"}] [:string {:min 1, :seon.db/identity true, :seon.program/row-schema :seon.agent/agent, :seon.wake/arms true, :description \"The agent's identity: the name every message, run, and namespace assignment resolves it by. AN ARMING ATTRIBUTE: asserting it is the one durable evidence that an agent exists, so its commit wakes the cluster's armer and an agent created while the cluster runs is armed without waiting for the next boot.\"}]] [[:map [:seon.db/connection :seon.db/connection] [:seon.sci.eval/ctx :seon.sci.eval/ctx] [:my.program/base-ctx :seon.sci.eval/ctx] [:seon.cluster/name {:optional true} :seon.cluster/name] [:seon.store/store {:optional true} :seon.store/store] [:seon.agent/routing {:optional true} :seon.agent/routing]] [:map [:seon.db/connection :seon.db/connection] [:seon.sci.eval/ctx :seon.sci.eval/ctx] [:my.program/base-ctx :seon.sci.eval/ctx] [:seon.cluster/name {:option
[Report excerpt ends; output was larger.]
```

### Probe 4: SCI — 947 ms

```clojure
[(seon.db/q '[:find ?a :where [_ ?a _]]) (doc seon.db/as-of) (clojure.repl/apropos "size")]
```

```text
[#{[:seon.fn.binding/shape]
   [:seon.error.basis/store]
   [:seon.fn/external-sink]
   [:seon.ns/source]
   [:seon.turn/starting-ns]
   [:seon.issue.citation/file]
   [:seon.fn.arity/max]
   [:seon.error.occurrence/message]
   [:seon.schema/key]
   [:seon.fn/doc]
   [:seon.issue.citation/id]
   [:seon.config.run/max-episode-runs]
   [:seon.issue/tests]
   [:seon.fn.argument/label-keyword]
   [:seon.ai.model/provider]
   [:seon.test.run/members]
   [:seon.test.run/policy]
   [:seon.test.run/at]
   [:seon.cluster.eval/id]
   [:seon.config.flow.io/concurrency]
   [:seon.ns.refer/target-name]
   [:seon.config.eval/time-limit-ms]
   [:seon.config.web/timeout-ms]
   [:seon.fn.arity/arity]
   [:seon.agent/admission-closed-tx]
   [:seon.program/source-attribute]
   [:seon.test.run/stopped]
   [:seon.cluster.eval/source]
   [:seon.ai.model/input-usd-per-mtok]
   [:seon.runtime/agent]
   [:seon.print/default]
   [:seon.runtime/trigger]
   …412}
 {:summary
    "Database view at a time point from an explicit or current database.",
  :body "",
  :example "",
  :arglists ([time-point] [database time-point]),
  :in
    [[:cat :seon.db/time-point]
     [:cat
      [:or :seon.db/database-value :seon.error/value]
      :seon.db/time-point]],
  :out
    [[:or :seon.db/database-value :seon.db/invalid-read-error]
     [:or :seon.db/database-value :seon.db/invalid-read-error]]}
 ()]
;; …N = N more, …+N = N more characters, …? = not yet realized. Read any part with (get-in result/e7ade17eac906 [...]).
```

### Probe 5: SCI — 60258 ms

```clojure
(into (sorted-map) (for [n ["seon.fn" "seon.test" "seon.error" "seon.error.occurrence" "seon.agent" "seon.turn" "seon.tx" "seon.runtime" "seon.cluster.eval"]] [n (vec (sort (filter #(= n (namespace %)) (map first (seon.db/q '[:find ?a :where [_ ?a _]])))))]))
```

```text
{
  "seon.dev.mcp/namespace": "user",
  "seon.dev.mcp/session-id": "default",
  "seon.dev.mcp/cluster": "default",
  "seon.dev.mcp/mode": "sci",
  "seon.dev.mcp/runtime": "clj",
  "seon.dev.mcp/timeout-ms": 60000,
  "seon.dev.mcp/failure": "timeout",
  "seon.dev.mcp/form": "(into (sorted-map) (for [n [\"seon.fn\" \"seon.test\" \"seon.error\" \"seon.error.occurrence\" \"seon.agent\" \"seon.turn\" \"seon.tx\" \"seon.runtime\" \"seon.cluster.eval\"]] [n (vec (sort (filter #(= n (namespace %)) (map first (seon.db/q '[:find ?a :where [_ ?a _]])))))]))",
  "seon.dev.mcp/root": "/Users/sean/src/seon"
}
```

### Probe 6: retire-1 — 582 ms

```text
{
  "seon.dev.mcp/root": "/Users/sean/src/seon",
  "seon.dev.mcp/cluster": "default",
  "seon.dev.mcp/action": "retire",
  "seon.dev.mcp/value": "{:seon.store/branch :dogfood-analyst-astra, :seon.dev.mcp/retired? true}"
}
```

### Probe 7: SCI — 1463 ms

```clojure
(let [attrs (map first (seon.db/q '[:find ?a :where [_ ?a _]]))] (mapv (fn [n] [n (vec (sort (filter #(= n (namespace %)) attrs)))]) ["seon.fn" "seon.test" "seon.error" "seon.error.occurrence" "seon.agent" "seon.turn" "seon.tx" "seon.runtime" "seon.cluster.eval"]))
```

```text
[["seon.fn"
  [:seon.fn/arglists :seon.fn/arities :seon.fn/call-arities
   :seon.fn/calls :seon.fn/constant? :seon.fn/defined-by
   :seon.fn/destroys :seon.fn/doc :seon.fn/doc-order
   :seon.fn/external-sink :seon.fn/file :seon.fn/form-span
   :seon.fn/host-bound? :seon.fn/inline? :seon.fn/internal?
   :seon.fn/invokes :seon.fn/keywords :seon.fn/macro? :seon.fn/ns
   :seon.fn/private? :seon.fn/projection-boundary :seon.fn/reference-to
   :seon.fn/references :seon.fn/source :seon.fn/spec :seon.fn/sym
   :seon.fn/unresolved-references :seon.fn/workload :seon.fn/writes]]
 ["seon.test"
  [:seon.test/adoption-cluster :seon.test/adoption-identities
   :seon.test/check-time-limit-ms :seon.test/failure-identity
   :seon.test/fixture :seon.test/fixture-observation :seon.test/long
   :seon.test/long-ms :seon.test/ns :seon.test/platform
   :seon.test/source :seon.test/sym :seon.test/usage]]
 ["seon.error"
  [:seon.error/at :seon.error/basis :seon.error/capped?
   :seon.error/data-blob :seon.error/data-edn :seon.error/data-size
   :seon.error/declared-schema :seon.error/exception-class
   :seon.error/frame :seon.error/id :seon.error/layer
   :seon.error/message :seon.error/notified :seon.error/occurrences
   :seon.error/operation :seon.error/process :seon.error/refusal-shape
   :seon.error/signature :seon.error/throwable-class]]
 ["seon.error.occurrence"
  [:seon.error.occurrence/agent :seon.error.occurrence/blob-digest
   :seon.error.occurrence/branch :seon.error.occurrence/count
   :seon.error.occurrence/data-blob :seon.error.occurrence/first-at
   :seon.error.occurrence/id :seon.error.occurrence/last-at
   :seon.error.occurrence/message :seon.error.occurrence/process]]
 ["seon.agent"
  [:seon.agent/admission-closed-tx :seon.agent/branch :seon.agent/id
   :seon.agent/namespace :seon.agent/plan :seon.agent/runtime
   :seon.agent/settings]]
 ["seon.turn"
  [:seon.turn/agent :seon.turn/closed-tx :seon.turn/handled
   :seon.turn/id :seon.turn/opened-tx :seon.turn/reply
   :seon.turn/reply-size :seon.turn/starting-ns :seon.turn/trigger]]
 ["seon.tx" []]
 ["seon.runtime"
  [:seon.runtime/agent :seon.runtime/trigger :seon.runtime/turns]]
 ["seon.cluster.eval"
  [:seon.cluster.eval/at :seon.cluster.eval/author
   :seon.cluster.eval/comment :seon.cluster.eval/id
   :seon.cluster.eval/ns :seon.cluster.eval/ordinal
   :seon.cluster.eval/output :seon.cluster.eval/read-basis-transaction
   :seon.cluster.eval/read-evidence :seon.cluster.eval/run
   :seon.cluster.eval/source]]]
```

### Probe 8: SCI — 2496 ms

```clojure
(let [attrs (map first (seon.db/q '[:find ?a :where [_ ?a _]]))] {:attributes (vec (sort (filter #(some (fn [s] (clojure.string/includes? (str %) s)) ["reach" "definition" "provenance" "tx" "task" "running" "bytes" "size"]) attrs))) :history (my.program/history {:seon.program/subject 'my.plan/current}) :agent-rows (seon.db/q '[:find (pull ?e [*]) :where [?e :seon.agent/id]])})
```

```text
{:attributes
   [:db/txInstant :seon.agent/admission-closed-tx
    :seon.config.error/max-evidence-bytes
    :seon.config.eval.result/max-bytes :seon.config.fs/max-inline-bytes
    :seon.config.fs/max-read-bytes :seon.config.fs/max-write-bytes
    :seon.config.maintenance/log-max-bytes
    :seon.config.maintenance/min-usable-bytes
    :seon.config.shell/inline-output-bytes
    :seon.config.shell/preview-bytes :seon.config.shell/stdin-max-bytes
    :seon.config.web/max-inline-bytes
    :seon.config.web/max-response-bytes :seon.error/data-size
    :seon.program/definition-digest :seon.schedule.task/function
    :seon.schedule.task/id :seon.schedule.task/owner
    :seon.schedule.task/schedule :seon.task/evidence :seon.task/id
    :seon.task/intent :seon.task/kind :seon.task/must-read
    :seon.task/name :seon.task/ns :seon.task/tasks
    :seon.test.failure/actual-size :seon.test.failure/expected-size
    :seon.test.failure/message-size :seon.test.member/completed-tx …6],
 :history
   {:seon.program/subject my.plan/current,
    :seon.program/kind :seon.fn/sym,
    :seon.db/basis-t 536871335,
    :seon.program/unknown
      [#:seon.program{:gap :outside-graph,
        :message
          "This supplied database value answers; callers outside its program graph are unknown."}],
    :seon.program/history
      [{:seon.db/tx 536870917,
        :seon.db/added? true,
        :seon.program/source
          "(defn current\n  \"Read my current item; an empty map means none is selected.\n\n  Returns a step map with :my.plan.item/id, :my.plan.item/title and\n  :my.plan/state, or {} when no open step is selected.\n\n  Example:\n  (my.plan/current)\"\n  {:malli/schema [:=> [:cat :my.plan/request] [:or :my.plan/current-value :seon.error/value]]}\n  [request]\n  (plan/current (:seon.db/db request) (:seon.agent/id request)))",
        :seon.program/definition
          {:seon.fn/sym my.plan/current,
           :seon.fn/arglists "([request])",
           :seon.fn/arities #{14358},
           :seon.fn/call-arities #{[seon.plan/current 2]},
           :seon.fn/calls #{seon.plan/current},
           :seon.fn/defined-by clojure.core/defn,
           :seon.fn/doc
             "Read my current item; an empty map means none is selected.\n\n  Returns a step map with :my.plan.item/id, :my.plan.item/title and\n  :my.plan/state, or {} when no open step is selected.\n\n  Example:\n  (my.plan/current)",
           :seon.fn/file [:seon.fn.file/relative-path "src/my/plan.clj"],
           :seon.fn/form-span [1681 2085],
           :seon.fn/host-bound? false,
           :seon.fn/inline? false
[Report excerpt ends; output was larger.]
```

### Probe 9: SCI — 862 ms

```clojure
(let [attrs (map first (seon.db/q '[:find ?a :where [_ ?a _]]))] {:reach (vec (sort (filter #(some (fn [s] (clojure.string/includes? (str %) s)) ["reach" "seon.db/" "seon.test.member" "seon.test.call" "seon.program/"]) attrs))) :errors (seon.db/q '[:find ?s ?op (sum ?n) :where [?e :seon.error/signature ?s] [?e :seon.error/operation ?op] [?e :seon.error/occurrences ?o] [?o :seon.error.occurrence/count ?n]]) :coverage (my.program/tests-reaching {:seon.program/subject 'my.plan/current})})
```

```text
{:reach
   [:seon.db/append-only-after :seon.db/attributes :seon.db/component
    :seon.db/component-schema :seon.db/identity :seon.db/index
    :seon.db/no-history? :seon.db/process :seon.db/read-request
    :seon.db/read-result-digest :seon.db/receipt
    :seon.db/retraction-authority :seon.db/source-argument-position
    :seon.db/user :seon.program/analyzed-source-digest
    :seon.program/definition-digest :seon.program/partition
    :seon.program/projected-properties :seon.program/recomputed
    :seon.program/row-schema :seon.program/source-attribute
    :seon.test.member/began? :seon.test.member/completed-tx
    :seon.test.member/ended? :seon.test.member/error-count
    :seon.test.member/fail-count :seon.test.member/failures
    :seon.test.member/pass-count :seon.test.member/reach-digest
    :seon.test.member/reasons :seon.test.member/symbol
    :seon.test.member/terminated-tx],
 :errors
   [["9e61d0ee4ec1adc7dde5c22760c755cf79af45e9fa03c855f1d0c2aed198fe54"
     seon.flow/work-launcher-step 1]
    ["48f7332b1576c386016c8c72140993e7807ab506f9fe595fcdd311a41ee50ad5"
     seon.db/transact! 1]
    ["fa60ad84404c9720c3e6cd72ecdba9756f92bde2721e5568cf43d96d54a72aa3"
     seon.cluster.agent/turn-step 1]],
 :coverage
   {:seon.program/subject my.plan/current,
    :seon.program/kind :seon.fn/sym,
    :seon.db/basis-t 536871337,
    :seon.program/unknown
      [#:seon.program{:gap :outside-graph,
        :message
          "This supplied database value answers; callers outside its program graph are unknown."}],
    :seon.program/gating
      [my.plan-test/current-refuses-an-unknown-agent-by-name]}}
```

### Probe 10: SCI — 914 ms

```clojure
[(doc seon.fn/gate-set) (doc seon.cluster.status/snapshot) (seon.db/q '[:find ?a :where [?e :seon.schema/key ?a] [(namespace ?a) ?n] [(= ?n "seon.test")]]) (seon.db/q '[:find ?s :where [?e :seon.fn/sym ?s] [(str ?s) ?n] [(clojure.string/includes? ?n "reach")]])]
```

```text
[{:summary
    "Tests gating one function identity. Unknown identities match only an",
  :body
    "explicitly pending subject; unresolved file references select that file's\ntests. Use gate-sets when one operation asks about several identities.",
  :example "",
  :arglists ([database function-symbol]),
  :in [:cat :seon.db/database-value :seon.fn/sym],
  :out [:or [:vector :seon.test/sym] :seon.db/invalid-read-error]}
 {:summary
    "Observe this cluster and JVM. SCI supplies the database and connection.",
  :body
    "Thread counts are a non-atomic JVM dump. Store size is an explicit operator\ndiagnostic: routine observations never traverse the store's files.\nFaults and session accounting start at this JVM's boot instant.\n\nExample: (seon.cluster.status/snapshot {})",
  :example "",
  :arglists ([{database :seon.db/db, connection :seon.db/connection}]),
  :in [:cat [:map]],
  :out
    [:or :seon.cluster.status/value :seon.error/base
     :seon.cluster.status/unavailable-error],
  :supplied [:seon.db/connection :seon.db/db]}
 #{[:seon.test/execution-error]
   [:seon.test/host]
   [:seon.test/error]
   [:seon.test/fail-count]
   [:seon.test/run-basis-t]
   [:seon.test/long-ms]
   [:seon.test/request]
   [:seon.test/loaded-source-drift]
   [:seon.test/loaded-source]
   [:seon.test/acquisition-refusals]
   [:seon.test/elapsed-ms]
   [:seon.test/adoption-inputs]
   [:seon.test/usage]
   [:seon.test/host-report]
   [:seon.test/reach-unknown]
   [:seon.test/test]
   [:seon.test/failures]
   [:seon.test/error-test-symbol]
   [:seon.test/check-time-limit-ms]
   [:seon.test/error-count]
   [:seon.test/result-unknown]
   [:seon.test/destructive-path]
   [:seon.test/platform]
   [:seon.test/reach]
   [:seon.test/skipped-count]
   [:seon.test/timings]
   [:seon.test/identity]
   [:seon.test/namespaces]
   [:seon.test/run-at]
   [:seon.test/unchanged]
   [:seon.test/seed]
   [:seon.test/skip-reason]
   …66}
 #{[seon.test-reaching-test/reach-index]
   [seon.test-reaching-test/define!]
   [seon.sci.kernel/deadline-reached?]
   [seon.test-reaching-test/with-long-test]
   [seon.test.runner/reach-refresh]
   [seon.db/stored-call-reach]
   [seon.test.runner/reach-row]
   [seon.test.runner/reach-canonical]
   [seon.test.runner/reach-ancestor]
   [seon.test-reaching-test/changed-request]
   [seon.test/destructive-reach]
   [seon.cluster/reaching-run]
   [seon.test/reaching]
   [seon.test.runner/reach-lineage]
   [seon.test.runner/reach-holders]
   [seon.test-reaching-test/run-in-fixture]
   [seon.issue.detect/reaching-test-subject]
   [seon.test-reaching-test/destructive-owner]
   [seon.fn/reaches?]
   [seon.test.runner/reach-entry]
   [seon.cluster.source/unselected-reach]
   [seon.test.runner/reach-commit]
   [seon.test-reaching-test/long-declaration]
   [seon.test-reaching-test/with-destructive-test]
   [seon.test-reaching-test/seed-reach-fixture!]
   [seon.test.runner/reach-digests]
   [seon.fn/tests-reaching]
   [seon.issue.detect/public-without-reaching-test]
   [seon.test.runner/reach-schema-keys]
   [seon.test.runner/reach-revisions]
   [seon.test.runner/reach-entries]
   [seon.test.runner/reach-cache]
   …16}]
;; …N = N more, …+N = N more characters, …? = not yet realized. Read any part with (get-in result/ea525a07324a7 [...]).
```

### Probe 11: SCI — 718 ms

```clojure
[(doc seon.fn/gate-sets) (doc seon.fn/tests-reaching) (seon.cluster.status/snapshot {}) (seon.db/q '[:find ?s :where [?e :seon.fn/sym ?s] [(str ?s) ?n] [(clojure.string/includes? ?n "store")]] )]
```

```text
[{:summary
    "Select tests through the shared reverse graph. The map arity seeds one",
  :body
    "frontier with all changed symbols; the positional arity retains per-seed results.",
  :example "",
  :arglists
    ([{database :seon.db/db, seeds :seon.fn/seeds}]
     [database function-symbols]),
  :in
    [[:cat [:map [:seon.fn/seeds [:set :qualified-symbol]]]]
     [:cat
      [:fn
       {:error/message "must be an immutable Datahike database value",
        :gen/gen seon.db/database-value-generator,
        :seon.schema/identity-only true,
        :seon.schema/identity-projection seon.db/database-value-identity}
       seon.db/database-value?]
      [:sequential
       [:qualified-symbol
        {:seon.db/identity true,
        :seon.program/row-schema :seon.fn/fn,
        :seon.program/source-attribute :seon.fn/source}]]]],
  :out
    [[:or [:vector :seon.test/sym] :seon.db/invalid-read-error]
     [:or
      [:map-of :seon.fn/sym [:vector :seon.test/sym]]
      :seon.db/invalid-read-error]],
  :supplied [:seon.db/db]}
 {:summary "Compatibility spelling for the shared gate-set derivation.",
  :body "",
  :example "",
  :arglists ([database function-symbol]),
  :in [:cat :seon.db/database-value :seon.fn/sym],
  :out [:or [:vector :seon.test/sym] :seon.db/invalid-read-error]}
 {:seon.cluster/name "default",
  :seon.cluster.status/adopted-commit
    #uuid "6ab7ec90-2653-55d3-ad50-173841cdffea",
  :seon.cluster.status/agents 36,
  :seon.cluster.status/current-commit
    #uuid "6ab7ec90-2653-55d3-ad50-173841cdffea",
  :seon.cluster.status/faults
    [["fa60ad84404c9720c3e6cd72ecdba9756f92bde2721e5568cf43d96d54a72aa3"
      2]],
  :seon.cluster.status/heap-max 10997465088,
  :seon.cluster.status/heap-used 5097908656,
  :seon.cluster.status/open-turns 1,
  :seon.cluster.status/platform-threads 169,
  :seon.cluster.status/store-bytes
    "Store bytes are not scanned during a cluster observation. Use bin/seon status --verbose for an explicit footprint measurement.",
  :seon.cluster.status/virtual-threads 29}
 #{[seon.maintenance/konserve-store?]
   [seon.cluster.store/branch-open-monitor]
   [seon.blob/store-faithful-edn]
   [seon.error/stored-observation]
   [seon.blob/stored-digest-and-size]
   [seon.blob/konserve-store]
   [seon.cluster.store/listener-failed!]
   [seon.fn.analyzer/stored-arglists]
   [seon.db/stored-call-reach]
   [seon.cluster.store-test/markers]
   [seon.cluster/root-store-key]
   [seon.test.runner/restore-live-cluster-schema!]
   [seon.issue/stored-tasks]
   [seon.schema/restore!]
   [seon.cluster.store-transact-test/with-mixed-connection]
   [seon.cluster.store/refuse!]
   [seon.cluster/acquire-root-store!]
   [seon.cluster.mcp-test/oversized-values-share-one-digest-across-storeless-and-stored-modes]
   [seon.test.runner/schema-restore-drift]
   [seon.cluster.registry-test/store-bytes]
   [seon.cluster.store-test/fresh-dir]
   [seon.maintenance/store-dir]
   [seon.blob-test/with-file-blob-store]
   [seon.cluster.store/listen-failures!]
   [seon.blob/stored-binary]
   [seon.cluster.store/open-configuration]
   [seon.cluster.store-child/-main]
   [seon.cluster.store-test/probe-schema]
   [seon.store-growth-test/store-bytes]
   [seon.cluster.store-transact-test/schema-delta]
   [seon.sci.eval/restore-or-unavailable!]
   [seon.cluster.mcp-test/stored-strings-page-by-character-offset]
   …63}]
;; …N = N more, …+N = N more characters, …? = not yet realized. Read any part with (get-in result/ee27c398ab067 [...]).
```

### Probe 12: SCI — 60265 ms

```clojure
(let [database (seon.db/db) functions (mapv first (seon.db/q '[:find ?s :where [_ :seon.fn/sym ?s]])) gates (seon.fn/gate-sets database functions) missing (filter #(empty? (get gates %)) functions)] {:functions (count functions) :without-gating-test (count missing) :top-namespaces (vec (take 10 (sort-by (comp - val) (frequencies (map namespace missing)))))})
```

```text
{
  "seon.dev.mcp/namespace": "user",
  "seon.dev.mcp/session-id": "default",
  "seon.dev.mcp/cluster": "default",
  "seon.dev.mcp/mode": "sci",
  "seon.dev.mcp/runtime": "clj",
  "seon.dev.mcp/timeout-ms": 60000,
  "seon.dev.mcp/failure": "timeout",
  "seon.dev.mcp/form": "(let [database (seon.db/db) functions (mapv first (seon.db/q '[:find ?s :where [_ :seon.fn/sym ?s]])) gates (seon.fn/gate-sets database functions) missing (filter #(empty? (get gates %)) functions)] {:functions (count functions) :without-gating-test (count missing) :top-namespaces (vec (take 10 (sort-by (comp - val) (frequencies (map namespace missing)))))})",
  "seon.dev.mcp/root": "/Users/sean/src/seon"
}
```

### Probe 13: retire-2 — 60531 ms

```text
{
  "seon.dev.mcp/root": "/Users/sean/src/seon",
  "seon.dev.mcp/cluster": "default",
  "seon.dev.mcp/action": "retire",
  "seon.dev.mcp/failure": "branch",
  "seon.dev.mcp/error": "Read timed out",
  "seon.dev.mcp/class": "java.net.SocketTimeoutException"
}
```

### Probe 14: status-after-coverage-timeout — 11650 ms

```text
{
  "seon.dev.mcp/root": "/Users/sean/src/seon",
  "seon.dev.mcp/view": "cluster-health-flow",
  "seon.dev.mcp/clusters": [
    {
      "seon.dev.mcp/root": "/Users/sean/src/seon",
      "seon.dev.mcp/cluster": "default",
      "seon.dev.mcp/path": "/Users/sean/src/seon/data/clusters/default/prepl.edn",
      "seon.dev.mcp/state": "unknown",
      "seon.dev.mcp/advertisement": {
        "seon.boot/pid": 19859,
        "seon.boot/start-instant": "2026-09-26T16:02:02Z",
        "seon.boot/cluster-name": "default",
        "seon.boot/prepl-host": "127.0.0.1",
        "seon.boot/prepl-port": 53639,
        "seon.render.web/url": "http://127.0.0.1:7994",
        "seon.render.web/port": 7994
      },
      "seon.dev.mcp/source": "advertisement",
      "seon.dev.mcp/runtime": {
        "via": [
          {
            "type": "java.lang.Exception",
            "message": "flow not running",
            "at": [
              "clojure.core.async.flow.impl$create_flow$running_chans__9820",
              "invoke",
              "impl.clj",
              70
            ]
          }
        ],
        "trace": [
          [
            "clojure.core.async.flow.impl$create_flow$running_chans__9820",
            "invoke",
            "impl.clj",
            70
          ],
          [
            "clojure.core.async.flow.impl$create_flow$sc__9823",
            "invoke",
            "impl.clj",
            73
          ],
          [
            "clojure.core.async.flow.impl$create_flow$handle_ping__9826",
            "invoke",
            "impl.clj",
            80
          ],
          [
            "clojure.core.async.flow.impl$create_flow$reify__9835",
            "ping",
            "impl.clj",
            186
          ],
          [
            "clojure.core.async.flow$ping",
            "invokeStatic",
            "flow.clj",
            142
          ],
          [
            "clojure.core.async.flow$ping",
            "doInvoke",
            "flow.clj",
            136
          ],
          [
            "clojure.lang.RestFn",
            "invoke",
            "RestFn.java",
            442
          ],
          [
            "seon.oversight$agent_story",
            "invokeStatic",
            "oversight.clj",
            100
          ],
          [
            "seon.oversight$agent_story",
            "invoke",
            "oversight.clj",
            93
          ],
          [
            "clojure.lang.AFn",
            "applyToHelper",
            "AFn.java",
            165
          ],
          [
            "clojure.lang.AFn",
            "applyTo",
            "AFn.java",
            144
          ],
          [
            "clojure.core$apply",
            "invokeStatic",
            "core.clj",
            667
          ],
          [
            "clojure.core$apply",
            "invoke",
            "core.clj",
            662
          ],
          [
            "malli.core$__EQ__GT$reify$reify__43835$fn__43870",
            "doInvoke",
            "core.cljc",
            2226
          ],
          [
            "clojure.lang.RestFn",
            "applyTo",
            "RestFn.java",
            140
          ],
          [
            "clojure.core$apply",
            "invokeStatic",
            "core.clj",
            667
          ],
          [
            "clojure.core$apply",
            "invoke",
            "core.clj",
            662
          ],
          [
            "seon.instrument$arm_var_BANG_$fn__108677$fn__108679",
            "doInvoke",
            "instrument.clj",
            785
          ],
          [
            "clojure.lang.RestFn",
            "applyTo",
            "RestFn.java",
            140
          ],
          [
            "clojure.lang.AFunction$1",
            "doInvoke",
            "AFunction.java",
            33
          ],
          [
            "clojure.lang.RestFn",
            "invoke",
            "RestFn.java",
            460
          ],
          [
            "seon.oversight$fleet_value$fn__116664",
            "invoke",
            "oversight.clj",
            185
          ],
          [
            "clojure.core$map$fn__5952$fn__5953",
            "invoke",
            "core.clj",
            2763
          ],
          [
            "clojure.lang.ArraySeq",
            "reduce",
            "ArraySeq.java",
            114
          ],
          [
            "clojure.core$transduce",
            "invokeStatic",
            "core.clj",
            7029
          ],
          [
            "clojure.core$into",
            "invokeStatic",
            "core.clj",
            7046
          ],
          [
            "clojure.core$into",
            "invoke",
            "core.clj",
            7033
          ],
          [
            "seon.oversight$fleet_value",
            "invokeStatic",
            "oversight.clj",
            183
          ],
          [
            "seon.oversight$fleet_value",
            "invoke",
            "oversight.clj",
            163
          ],
          [
            "clojure.lang.AFn",
            "applyToHelper",
            "AFn.java",
            160
          ],
          [
            "clojure.lang.AFn",
            "applyTo",
            "AFn.java",
            144
          ],
          [
            "clojure.core$apply",
            "invokeStatic",
            "core.clj",
            667
          ],
          {
            "seon.render.data/path": [
              "trace"
            ],
            "seon.print/elision-unit": "children",
            "seon.print/face": "seon.print/elided",
            "seon.print/bound-by": "seon.render.profile/max-children",
            "seon.render.profile/id": "seon.render.profile/mcp",
            "seon.print/requery-form": [
              "seon.print/value-at",
              [
                "seon.render.value/artifact-value",
                [
                  "seon.render.value/read-artifact",
                  [
                    "seon.blob/get",
                    [
                      "seon.cluster.boot/connection",
                      "default"
                    ],
                    "f30b9d60cd497ed695fa5efaeb6a70eca6e2e3412b52ed3405cad3f71263bbd4"
                  ]
                ]
              ],
              [
                "trace"
              ]
            ],
            "seon.render.data/next-offset": 32,
            "seon.print/omitted": 72,
            "seon.render.data/total": 104,

[Report excerpt ends; output was larger.]
```

### Probe 15: retire-2-after-stopped-flow — 16731 ms

```text
{
  "seon.dev.mcp/root": "/Users/sean/src/seon",
  "seon.dev.mcp/cluster": "default",
  "seon.dev.mcp/action": "retire",
  "seon.dev.mcp/failure": "branch",
  "seon.dev.mcp/error": "Read timed out",
  "seon.dev.mcp/class": "java.net.SocketTimeoutException"
}
```

### Probe 16: get-value-status-cause — 25732 ms

```text
get_value digest f30b9d60cd497ed695fa5efaeb6a70eca6e2e3412b52ed3405cad3f71263bbd4, path ["cause"]:
:seon.error/operation seon.render.data/at
:seon.error/message "The requested path has no value at this step."
:seon.error/expected "a present map key, set member, or sequence index"
:seon.render.data/root-description "clojure.lang.PersistentArrayMap"
The offending value repeated the complete error and trace, with another max-children cut.
The envelope provided :seon.dev.mcp/retrievable? true and a new blob digest.
```

### Probe 17: get-value-status-keyword-cause — 14617 ms

```text
{
  "seon.dev.mcp/failure": "request",
  "seon.dev.mcp/error": "Connection refused"
}
```

### Final retirement attempt — 152 ms

```text
branch retire dogfood-analyst-astra-2, root /Users/sean/src/seon, cluster default:
:seon.dev.mcp/failure :repl-unavailable
No live CLJ REPL is available for cluster 'default'; its advertisement is missing.
```
