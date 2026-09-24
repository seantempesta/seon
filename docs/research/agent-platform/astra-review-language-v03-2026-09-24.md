---
type: research
status: changes required; read-only review, no implementation
created: 2026-09-24
---
# Language v0.3: references are the right entrance; the guarantees are incomplete

Seon already stores source analysis, immutable database values, test evidence and rendered evaluations.
Compose those owners: task references → selected read forms → captured evaluations; fork executable program content and inject each attempt's world.
Keep the three-key task and ordinary Clojure tests. Delete the prototype's second test runner and global vocabulary exclusion.
The proposed walk cannot yet guarantee sufficient, minimal, current context for every requested task kind.

## Evidence boundary

Read `CLAUDE.md`, the complete 220-line v0.3, all named requirements/evidence reports, all three
`experiments/tasks-v02/*.edn`, all 427 lines of `task-scenarios-2026-09-24.md`, and both loop files end to end.
Design history inspected through `4bfc3cf39` (checkpoints) and `341305b4f` (named deftests).
Checkout began at `88289e006`; concurrent commits/edits continued. No other lane was contacted or modified.
Live root `/Users/sean/src/seon`, cluster `default`: PID 90471, start `2026-09-23T22:18:14Z`;
installed archive **ce73846828a5cc32798ef630b5a574f777646f30**, hook publication off.
Both status entrances answered; existing counts were 16 error signatures, 34 errored receipts, one failed run,
one failed test of unknown currency. This is not a healthy-platform certification.
Below, `src/` citations refer to that installed archive unless marked checkout. Dependency pins verified:
SCI `fcbd8862800e638dc0f8f5521111f999279cbcd2`, Datahike `c79cd03a44427ac1734d917c7484c3e529c77716`,
Malli `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d`.
The linked Claude artifact in §6 could not be retrieved; the local write-back/scenario reports were read.

## Ranked findings and smallest fixes

### 1. P0 — §4b confuses a forkable context with an immutable value

**Measured counterexample:** base defines `f=1`; child defines `caller→f`, then `f=2`.
Child returns **[2 1]** for `[(f) (caller)]`. Redefine the *parent's* f to 3: child now returns **[2 3]**.
A child `(swap! cell inc)` also changes the parent's atom to **1**. Exact local probe appears below.
SCI `core.cljc:345` copies the env atom; `impl/utils.cljc:362` copies an inherited Var on binding,
but mutates an owned Var. `lang.cljc:72` declares mutable Var roots/metadata. Persistent maps do not freeze their contents.
The [upstream fork contract](https://github.com/babashka/sci/blob/master/API.md#fork) promises child redefinitions do not appear in the parent;
it does not promise deep object isolation or stable captured callees after later redefinition.

**Smallest fix:** freeze published bases by ownership: never evaluate into or mutate them after publication;
derive successors on new owned forks. Keep checkpoint values free of attempt atoms, result handles and connections.
After a definition change, reinterpret its affected callers from the database before proving behavior; a fresh fork alone is insufficient.
Pin an attempt's adopted *upstream program*, while letting its private branch head and accepted local definitions advance.
Otherwise “attempt never advances” prevents the very writes its next proof must examine (§3 versus §4b).
Dropping scratch on an explicitly chosen new checkpoint is consistent with the owner's ruling; do not preserve it with another regeneration layer.

**Host counterexample:** a copied compiled `f` calling JVM Var `g` observes a later reload of `g`, even if f's root is retained.
A host callback closing over an atom/file/network client is also shared; rebranching undoes none of its effects.
Use the existing host-bound boundary and actual-exit ownership; incompatible host changes require refusal or process isolation,
not a stronger description of `sci/fork`. Evidence: [Astra SCI study](sci-context-efficiency-astra-2026-09-24.md), “Reload requires a JVM-wide boundary”.

### 2. P0 — the loop's green and missing-source evidence are unsound

`tmp/wiki-toy/experiment/scratch/loop.clj:71` implements proofs as `(boolean ((:test (meta (resolve s)))))`.
A body with `(is (= 1 2))` followed by `(is (= 2 2))` returns **true** in the live branch.
The existing `seon.sci.eval/run-test` returns **pass 1 / fail 1 / error 0**, in **35.121 ms**.
Thus the transcript's `[true true]` cannot establish “already green on default's rows”, nor row/runtime equivalence.
Those tests called executable Vars; they did not prove which durable definition digests were executed.

`loop.clj:25` reads shown/error only; it drops `:seon.cluster.eval/output` itself.
Live `(println "review-stdout-marker")` delivered **"review-stdout-marker\n"** in that output member.
§8b's blame on the evaluation entrance is therefore unsupported. Help is also special-cased, not evaluated (`:112`);
seeds are a text tree walk with a hard-coded alias map (`:56`), and existence is checked against default (`:103`).
The 1.3 KB opening omits source; it is not a successful minimal-context measurement.

**Smallest fix:** delete truthiness proof evaluation; use the existing capture/recording path and render its actual failures.
Select exact frozen proof definitions and materialize the tested executable program from the same captured database basis.
Record an intended behavioral red separately from setup/admission failure. Preserve stdout before the value in the existing transcript.
`seon.test/run` still refuses this private test as `:seon.test/identity-unresolved` (measured below): its task-proof entrance remains work.
Named tests in ordinary namespaces do not by themselves specify lifetime: keep task-only proofs out of general coverage/merge;
promotion to a lasting guarantee remains deliberate (requirements 3.2, 3.8, 3b.2).

### 3. P1 — proof references alone lose task subjects; vocabulary is a role, not a blacklist

The actual v0.2 research proof is only `(accepted? task-id)`; it contains neither `tests-reaching` nor `gate-sets`.
The UI proof names observation/acceptance helpers, not `render-note-html`. Removing authored `about` loses those subjects.
Triage may have no proof at all. §7 rejects it although requirements 2.6/4.5 explicitly allow raised work.
Conversely a task fixing `seon.db/q` loses its principal subject because §4.2 categorically suppresses taught symbols.

**Smallest fix:** reuse optional task `must-read` for intent that no proof expresses; producers attach triggering message,
error, measurement or attempt identities. Derive `about` from those references **and** proof references.
Explicit subjects/must-reads win over vocabulary suppression. Suppress incidental tool *uses*, not the symbol globally.
Allow a raised task to bootstrap triage from those references; require a reviewed proof before implementation admission.
Do not revive authored merge scope or a context-function registry. The following table identifies each coverage gap.

| Kind / concrete supplied case | As written? | Smallest addition or correction |
|---|---|---|
| Fix: faults-read-is-a-call | Conditional | Direct calls suffice for this example only; show named proof source, actual red report, input entity and must-reads; use F2. |
| Data model: about-is-a-value | No | Analyze schema/value references, allow intended absent schema/namespace obligations, and show target schema proof. §7 permits only absent functions. |
| Delete: drop-note-row | No | Preserve quoted/lookup identity references; prove row absence at the captured db, not `resolve`. Address incoming calls **and** value/invocation refs from before/after. |
| Rename: tests-reaching-is-gate-set | No | Same as delete, plus old/new identities from proof/must-read and repairs of incoming references. Equal bodies do not prove a rename. |
| Compare: three stale-doc attempts | Individual opener only | Review attempt identities with frozen proof digest, base/delivered heads, diffs, results and costs; reuse one entity read, not another task kind. |
| UI proof: notes-page-shows-subjects | No | Must-read renderer and page/input identity; observation carries URL, program/data basis, time and reviewer decision. Hiccup/test green is not paint. |
| Recurring: nightly-reclaim | No | Seed schedule **fire/attempt** and receipt/measurement; prove this firing, not any previous receipt. Mechanical execution uses the existing schedule's named function. |
| User question: answer-m-7f3a | Partial | Literal message identity can seed the chain; obtain relevant workout data through an explicit named read. Acceptance must cite this reply and reviewer. |
| Research: why-reach-saturates | No | Must-read subject and supplied measurements/dependency revision; accepted? alone identifies no research subject. |
| Decompose: workout tracker | No | Seed originating request and relevant existing model identities; show task-authoring vocabulary. Child count/specification does not identify the domain. |
| Error triage | No | Seed error signature/occurrence with offending input, producer and full cause; permit proofless raised state, then author regression or verdict. |

The other scenarios follow these same additions: setup needs unresolved obligations; namespace improvement needs named
detector/profile reads; dependency bumps need pinned source/diffs; root/owner review and conflicts need attempt/decision/intermediate-head identities.
Cross-namespace UI composition needs the consuming proof plus producer contract, not either whole namespace.
Static analysis cannot infer arbitrary runtime-selected entity identities or a user's unstated relevance criterion.
Allow ordinary named reads and authored minimal references at that boundary; do not promise mathematically minimal context.

### 4. P1 — §8.1 proposes an edge that is already installed; other graph claims need narrowing

**Live:** **29,375 `:seon.fn/calls` edges on 2,106 test rows**, with `:db/index true` on calls.
`src/seon/fn.clj:602,641` already writes them; `resources/seon/schemas/seon.test.edn:108` admits them.
`seon.test/reach` is recorded execution evidence, not the direct-call relation. Delete the proposed second test-call attribute.
Use the existing analyzer/row writer for task proofs, including positive analyzed-source digest and exact replacement/retraction of old edges.
An empty cardinality-many relation cannot distinguish successful empty analysis from missing analysis.

Reuse `:seon.fn/references`, `:seon.fn/invokes`, namespace bindings and file unresolved references too
(`src/seon/fn.clj:1458,1478`). Calls alone miss `(map f xs)`, `'f`, declared render targets and macro/resolver changes.
The graph must distinguish calls, symbol observations and schema references; a quoted subject does not imply a scheduling dependency.
`:seon.fn/keywords` means mentions, not proven reads (`src/seon/fn/analyzer.clj:140`); literal fixtures contain irrelevant keys too.
Datahike reverse traversal is not “free”: indexed bound lookups cost O(log N + matches), then output work.
Only indexed attributes get that guarantee; mixed identities need declared native storage, not heterogeneous EDN-string edges.
The Opus SCI study demonstrates a join-bound attribute query scanning the store; use its owner fix, not a new graph cache.

**§8.2 is real:** contract metadata keys need attribution to their definition, not reader-side string parsing.
Use the existing schema analysis/registry meaning of references, including named predicates and resolver dependencies;
do not collect every keyword in a contract (map keys, properties, enum values and schema identities differ).
**§8.3–4 are justified:** ingest must-read properties and help dependencies once at their producer, with names resolved.
Add typed literal entity/quoted-symbol references where existing analysis cannot represent them; carry the analysis digest.
Help prerequisites need transitive closure, deterministic order and cycle refusal; must-reads need deduplication/cycle handling too.
**§8.5 is editorial policy, not a prerequisite graph gap:** no proven detector can decide from incident wording that intent is wrong.
Keep it a review task; remove it from the implementation dependency chain. Defer see-also ingestion until its address-only walk rule exists.

### 5. P1 — a cached evaluated opening is stale without any program change

At fixed task/proof/program content, a new message, error occurrence, workout row or maintenance firing changes the answer.
§4b advances on cluster *program* changes only, while §4.6 promises current values. Reusing task-level output also shares
attempt-specific handles and observations; its creation may execute arbitrary renderer/read effects.
**Smallest fix:** cache the form plan separately from its evaluations. Capture one attempt head for coherent reads;
use existing `seon.db/read-evidence-current?` and system-turn refresh for changed reads, including empty/negative results.
Retain historical shown bytes. Renderers, profiles, help text and aliases are content dependencies too.
Effects/proofs are not automatically refreshable reads. Explicitly supplied external observations carry provenance/freshness policy.
Bound selected output once at the existing renderer, with counts/requery; two hops alone do not bound 10,000 callers or a large entity.

### 6. P1 — cache content must include the callable world, not merely source text

Two identical `(fn [] captured)` forms capturing different objects have the same source digest and different behavior.
Malli `-instrument-f` closes over **f**, validators and report callback (`reference-code/malli/src/malli/core.cljc:2202`).
Seon's `compiled-wrapper` closes over projection/caps/policy (`src/seon/instrument.clj:638`);
`install-function-contract!` supplies a context fault recorder (`src/seon/sci/eval.clj:708`).
A source-only wrapper cache can call the wrong closure or send a refusal to another cluster.
**Smallest fix:** share compiled schemas/validators by their full dependency content; bind the actual callable and
execution-owned policy/recorder separately. Share whole wrappers only when those captured inputs are identical.

| Level | Required inputs / invalidation | Cost and evidence |
|---|---|---|
| Host base | Loaded program/dependency revision, resolver/macro/class facts, projection/predicate content; reload requires an execution fence | O(program) initially. Prior studies: 76–140 ms / 252–347 MB for no override; not freshly remeasured. |
| Cluster executable layer | Base identity plus changed/retracted declarations and affected closure, schema/resolver content; equal content shares | Target O(changes + reached edges + interpreted source). Prior measured miss: 907 ms / 3.31 GB for one override; v0.3 has not removed this cost. |
| Task form plan | Frozen proof definitions and helpers, task references, help/render content/profile; db reads retain evidence | O(reached graph + selected source/output). Cached evaluated opening removed; historical 10/107 ms prototypes are not end-to-end correctness proofs. |
| Attempt / proof | Frozen origin plus current private head, exact execution/observation inputs, own custody/kernel objects | Raw SCI fork here 0.00131 ms mean (100); first branch SCI evaluation 871 ms / 1.681 GB, warm 6 ms / 10.264 MB. Different measured scopes. |

The prior deftest study measures branch create 21.8–25.6 ms, open 5.3–5.8 ms, release 8.3–8.8 ms;
these are separate from SCI fork, not a promise that every attempt costs microseconds.
Proof reuse additionally needs tested data/read evidence, fixtures, seed, relevant config and observation identity;
`(proof digest, program digest)` alone reuses green after input data changes.

### 7. P1 — a retained commit id is not a retention root

After checkpoint branch unlink and an eligible GC, `(branch! old-commit new-name)` can refuse commit-not-found.
Holding its UUID or lazy database value in an SCI cache does not mark its store nodes.
Datahike `versioning.cljc:224` requires the stored commit; `:287` requires release before branch deletion;
`gc.cljc:22,144` marks roster branches/retained ancestry and explicit reachable extensions.
**Smallest fix:** retain an ordinary branch head for each live checkpoint or use the existing complete reachability extension.
Release execution/connection ownership after actual exit, then unlink when no attempt/checkpoint needs it.
Persist accepted findings, decisions and exact evidence needed after unlink through their existing writers before retirement.
A TTL/LRU of SCI values alone provides neither durable reset nor evidence retention. No shared-store GC was run in this review.

### 8. P2 — keep authored decisions; delete invented derivations and contradictory refusals

Correctly derived: graph backlinks, diff, suggested prerequisites, topological order and current status from current evidence.
Necessarily authored/produced: intent, irreducible must-reads, semantic prerequisite choices, oracle/acceptance decision,
schedule, comparison count, nondefault merge function, provenance and frozen reviewed inputs.
Deliveries are *proposals* derived from diff; accepted subset is root's decision. Equal-body retract/add is at most a rename hint:
two existing functions can have identical bodies; real renames can change body, contract and recursion together.
Help `about`/`needs` expresses teaching intent; it cannot be inferred reliably from a helper's call graph.
Do not add `query → task` solely to force aesthetic order (v0.3:161); use a true prerequisite or the specified stable tie-break.

§7's blanket refusal of contract-invalid literal arguments rejects legitimate tests deliberately checking invocation refusal,
such as `(is (thrown? Exception (integer-only "wrong")))`. Validate test syntax/contracts; execute expected-refusal assertions.
Allow intended missing schema/namespace/data obligations, without treating misspelled ordinary references as obligations.
Distinguish already-satisfied work from invalid task data; the writer should record that finding, not both refuse and settle it (§3/§7).
For recurrence, “red on base” is indexed to this firing/input. For human decisions, acceptance must name reviewed evidence,
not merely a task id whose old acceptance makes every retry green.
Keep EDN targets as qualified symbols; inline one-line callables belong in code. Delete §2's ambiguous merge-body-in-task example.

## Reproduction and landing evidence

All live probes used explicit root/cluster above. JVM mode session `astra-review-language-v03`, namespace `user`,
used lexical locals only. SCI mode used the single owned branch `astra-review-language-v03-20260924`,
namespace `scratch.astra-review-language`. No default Vars, files, providers, reloads or other branches were changed.

```clojure
;; JVM, local dependency contexts: returns [[2 1] [2 3] 1].
(let [b (sci.core/init {})
      _ (sci.core/eval-string* b "(defn f [] 1) (def cell (atom 0))")
      a (sci.core/fork b)
      _ (sci.core/eval-string* a "(defn caller [] (f)) (defn f [] 2)")
      x (sci.core/eval-string* a "[(f) (caller)]")
      _ (sci.core/eval-string* b "(defn f [] 3)")
      y (sci.core/eval-string* a "[(f) (caller)]")
      _ (sci.core/eval-string* a "(swap! cell inc)")]
  [x y (sci.core/eval-string* b "@cell")])
;; SCI, each form on the owned branch:
(require '[clojure.test :refer [deftest is]])
(deftest mixed-proof (is (= 1 2)) (is (= 2 2)))
{:body-return (boolean ((:test (meta #'mixed-proof))))
 :printed (do (println "review-stdout-marker") :printed)}
;; JVM graph census: 29,375 pairs / 2,106 distinct tests, 501.897 ms including sorting/schema read.
(seon.db/q '[:find ?s ?c :where [?e :seon.test/sym ?s] [?e :seon.fn/calls ?c]]
           @(seon.cluster.boot/connection "default"))
```

Resolve the owned handle using the existing MCP acquisition shape (`script/seon/dev/mcp.clj:509`):
`i = (get @@(ns-resolve 'seon.cluster 'running-instances) "default")`;
`c = (assoc (:seon.turn.loop/cluster i) :seon.store/store (:seon.store/store i))`;
`h = (seon.cluster.agent/acquire-context! c nil {:seon.agent/branch :astra-review-language-v03-20260924})`.
`run-test` request was `{:seon.sci.eval/ctx (:seon.sci.eval/ctx h), :seon.test/var
(sci.core/resolve (:seon.sci.eval/ctx h) 'scratch.astra-review-language/mixed-proof),
:seon.sci.eval/time-limit-ms 1000, :seon.db/connection (:seon.db/connection h)}`: diagnostic capture, not recorded green.
One `seon.test/run` request used execution h, recording connection from h, policy `:named`, identities
`#{'scratch.astra-review-language/mixed-proof}`, check-time-limit-ms 2000. It refused as identity-unresolved in **1,147.529 ms**.
Inclusive armed profile: run 1,147; selection-admission/select 1,144; db/q 1,132 ms ×10.
This exceeds the ordinary bound: the test-selection owner must remove whole-population work before implementation acceptance;
this report-only assignment does not authorize that fix. No test gate was run or claimed green.
Timing harness used `System/nanoTime`; allocation used ThreadMXBean current-thread allocated-byte deltas.
Local SCI probe plus 100 forks: **1.254 ms / 245,816 B**. Allocation is not retained heap; heap retention was not measured.
Samples are diagnostics under concurrent work, not percentiles. An initial census form referenced nonexistent `seon.fn/index-file`;
it failed compilation, was corrected, and is excluded. Initial compound SCI setup returned namespace text; separate evaluation confirmed the result.
MCP retirement returned `retired? true`; subsequent roster listing excluded the owned branch. No live child execution remained.
Changed path: this report only; source/test delta **0/0**. File Markdown and whitespace validation passed.
Automatic repository-wide lint reported 57 foreign dependency-pin citations; those files are outside this assignment.
No publication, browser paint, real task settlement/merge, destructive retention proof or production improvement is claimed.
