---
type: research
status: active
tags: [research, database, test, architecture]
---

# N7 residual — ordinary evaluation call edges

## Outcome and boundary

Implementation commit: `f402c5d3d` on `steward-platform`.

The `seon.fn` side is implemented: ordinary submitted forms can use the same
batch analyzer as declarations, and their existing evaluation identity can own
the returned `:seon.fn/calls` refs. Namespace membership comes from program
rows, including accepted declarations in the submitted batch; no prefix filter,
synthetic function, second edge attribute, or alternate analyzer was added.

**The issue remains open.** The protected `src/seon/turn.clj` still excludes
ordinary evaluations from analysis. The exact proposed integration diff below
is not applied, loaded, or verified. No ordinary-turn persistence or full
population-invariant closure is claimed.

The new canonical regression is written but its live run is blocked by the
old contract retained in default's canonical fixture. The existing declaration
regression passes. The isolated gate was refused before test launch by the
orchestrator-only policy; namespace and platform gates are queued.

## Grounding and dependency ledger

Read end to end: AGENTS.md; docs/seon/issues/README.md and its localized
AGENTS.md; the N7 class note; all nine member notes named in the N7 landing
(including the six now archived); the additional archived changed-test-selector
member; the N7 landing; the reaching-tests landing; and src/seon/fn.clj.
Read the class-mining N7 row and structural-kill column: **record the missing
fact, then query it; constructors accept no roster/prefix/count**.
Read the active roadmap entry and turn PRD §§13–15.

Applied data-oriented-clojure, repl, datahike, clojure-testing, and data-modeling
skills. This assignment owns one residual, not the other N7 owners.

- `reference-code/clj-kondo/analysis/README.md:120`: var usages carry resolved
  `:to`/`:name`; `:arity` distinguishes calls from ordinary Var references.
  `src/seon/fn/analyzer.clj:89` preserves these facts. Its stdin path disables
  cache writes; the change reuses that path.
- `src/seon/fn.clj:475`: resolvable runtime targets derive from existing
  program identities and declaration rows in the batch.
- `src/seon/fn.clj:539`: exact source spans assign each analyzer entry to its
  submitted form. The new projection uses that same already-local analysis.
- `reference-code/datahike/src/datahike/db/transaction.cljc:717`: multi-value
  refs and lookup-ref recognition; persisted declaration probes use the existing
  database transaction owner, not a separate graph store.
- `src/seon/turn.clj:1173`: relation assertions already emit call refs.
  `:1672` commits those alongside read evidence. `:158` already carries
  form facts through settlement.
- `src/seon/fn.clj:802`: the existing reach rules and gate-set query serve
  `tests-reaching`; `src/seon/test.clj:150` uses that authority for selection.
  Neither consumer needs a new reachability algorithm.

Archaeology: `1f3c099d2` introduced batched declaration analysis and the current
runtime target query. `5deb40e4e` fixed namespace relevance, not this residual.
Initial read/probe HEAD was `51e1998843d8063654f82e9aaf381674bdb9a4d4`;
the attempted gate snapshot used `d5787c4f8d4a6269e1e28952830d2406a44ec77d`.

## Changes and per-member verdict

Only [agent-form-calls-to-core-namespaces-are-not-indexed](../../../seon/issues/agent-form-calls-to-core-namespaces-are-not-indexed.md)
is assigned here. **Open, narrowed to protected turn integration and its live
persistence proof.**

1. `analyze-forms` admits absence of a declaration row. A supplied row still
   must satisfy the complete declaration contract; explicit nil is not admitted
   in the request map.
2. `analyze-form` translates its existing nullable positional argument into
   that absent key.
3. `analyzed-form` returns ordinary-form call refs in the existing first tuple
   member. Declaration call refs remain solely on their program row. It does
   not manufacture a callable identity for an ordinary evaluation.
4. One canonical regression covers mixed declaration/test/ordinary batches,
   both namespace families, local shadowing, source-span separation, absence
   of an invented declaration, and persisted declaration reachability.

No schema resource change is needed: `:seon.fn/calls` already declares a set
of refs and the settlement writer already accepts the corresponding facts.
This slice does not add missing SCI binding rows or repair other N7 members.

## Exact live evidence

All MCP evaluations used JVM mode on default, PID 69622, root
`/Users/sean/src/seon`. No default lifecycle operation or provider call ran.
The [probe source](n7-eval-call-edges-probe-2026-09-15.clj) and
[complete saved results](n7-eval-call-edges-evidence-2026-09-15.edn) are retained.

Before editing production source, the exact ordinary `analyze-form` request
in the probe script refused in `analyze-forms` at
`[0 :seon.program/row]`: expected a map, got nil.

The three candidate definitions were evaluated from a project-local file via
MCP before editing src/seon/fn.clj. After arming, an immutable candidate
projection was made with the existing
`seon.schema/projection-with-function-contract` owner and the candidate's
authored metadata. This was necessary because the default database then held
the old contract. No default schema transaction or fixture-global mutation
was used. The representative call returned, in **4,824 ms**:

```clojure
[#:seon.fn{:calls #{[:seon.fn/sym "seon.db/q"]
                   [:seon.fn/sym "my.turn/wait"]
                   [:seon.fn/sym "clojure.core/do"]}}
 nil]
```

The single-form call exercises all three changed functions. The initial
projection probe mistakenly asked raw `@connection` for a carried projection;
that nil-projection refusal was corrected with `seon.db/db` and the explicit
projection owner. It is not attributed to the call-edge defect.

Exact regression commands:

```clojure
(seon.test/run
 #'seon.fn-test/ordinary-form-analysis-keeps-call-edges-without-a-declaration
 (seon.operator/connection "default"))

(seon.test/run
 #'seon.fn-test/defining-forms-share-one-form-local-kondo-batch
 (seon.operator/connection "default"))
```

| Run | Pass / fail / error | Recorded run / basis | Result |
|---|---|---|---|
| Candidate, before production edits, 23:22:40.093Z | 1 / 0 / 1 | 65002 / 536872137 | Canonical fixture's old required-row contract refuses request 2. |
| Immediate post-edit attempt, 23:23:23Z | 1 / 0 / 1 | 65005 / 536872139 | Same retained fixture contract; no adoption claim. |
| Existing declaration regression, 23:26:12Z | 5 / 0 / 0 | 65270 / 536872157 | Existing definition behavior preserved; MCP 1,856 ms. |
| Reloaded new regression after live contract observation, 23:27:07.367Z | 1 / 0 / 1 | 65281 / 536872171 | Same fixture boundary; MCP 1,911 ms. |

A later direct call with default's ordinary database value, **without** the
candidate projection, returned the same three edges in **18 ms**.
A four-form mixed batch returned in **50 ms**: core and my.* refs on the
declaration and ordinary form; the test calls the new batch declaration;
the shadowed local map adds no clojure.core/map edge. No declarations from
this analysis-only probe were installed in default.

Persisting the analyzed function and test rows on an isolated canonical
fixture succeeded at both transactions. `tests-reaching` returned exactly
`["seon.db/n7-observed-test"]` for the new function, and that test also
appeared in the transitive reach of `my.turn/wait`. **10,052 ms**, including
fixture acquisition and both queries. This proves declaration graph storage
and reachability; it does not stand in for the protected ordinary-turn proof.

A 6 ms independent query observed the widened spec on default and the old
required-row spec inside the canonical fixture. Filed
[canonical-fixture-retains-old-function-contracts-after-adoption](../../../seon/issues/canonical-fixture-retains-old-function-contracts-after-adoption.md).
No fixture cache or another lane's session was operated.

At that observation, default's adoption record was
`6aa9d0b3-5a57-5862-af74-5f8717513752`, while current-src was
`6aa9d3b4-eabe-5a99-9d3b-8d859cb9b5dc`. Thus the changed contract and callable
were observed, but complete source convergence was not established. An earlier
hook publication refused because source changed during analysis. Hook feedback
contained shadowed-var warnings in fn.clj, no blocking fn.clj finding.

## Protected integration diff — not applied

The owning recorder is in turn.clj, not sci/eval.clj. Widen its existing batch
to all submitted forms and carry the resulting form facts into the existing
settlement relation writer beside read evidence. Including accepted function
declarations supplies same-batch target identities before the settlement
transaction; the pre-install function gate still owns its necessary earlier
candidate analysis. This is the existing analyzer and writer, not an additional
edge store or source parser.

The direct settlement API's existing analysis entrance must also admit ordinary
forms. This patch was generated against the observed protected working file;
it is a proposal requiring the owner's REPL-first integration proof:

```diff
--- a/src/seon/turn.clj
+++ b/src/seon/turn.clj
@@ -913,8 +913,7 @@
 
 (defn- analyze-settlement
   [database request]
-  (if-let [form (when (:seon.program/row request)
-                  (settlement-form database request))]
+  (if-let [form (settlement-form database request)]
     (let [[form-facts program-row]
           (seon.fn/analyze-form
            database
@@ -4532,24 +4531,23 @@
                                      reader-event
                                      (assoc :seon.sci.eval/event reader-event)))))
                       evaluations)}))
-            defining
+            submitted
             (into []
-                  (keep-indexed
+                  (map-indexed
                    (fn [index {form :seon.turn.loop/admitted-form evaluation :seon.sci.eval/evaluation}]
-                     (when (and (:seon.program/row evaluation)
-                                (not (get-in evaluation [:seon.program/row :seon.fn/sym])))
-                       [index
-                        {:seon.cluster.eval/source
-                         (:seon.cluster.eval/source form)
-                         :seon.cluster.eval/ns
-                         (:seon.cluster.eval/ns form)
-                         :seon.program/row
-                         (:seon.program/row evaluation)}])))
+                     [index
+                      (cond->
+                       {:seon.cluster.eval/source
+                        (:seon.cluster.eval/source form)
+                        :seon.cluster.eval/ns
+                        (:seon.cluster.eval/ns form)}
+                        (:seon.program/row evaluation)
+                        (assoc :seon.program/row (:seon.program/row evaluation)))]))
                   evaluated)
             analyzed
             (cond
               (:seon.error/kind evaluated) evaluated
-              (seq defining) (phase #(seon.fn/analyze-forms database (mapv second defining)))
+              (seq submitted) (phase #(seon.fn/analyze-forms database (mapv second submitted)))
               :else [])]
         (if (:seon.error/kind analyzed)
           (do
@@ -4562,9 +4560,11 @@
           (let [evaluated
                 (reduce
                  (fn [all [[index _] [form-facts row]]]
-                   (-> all
-                       (assoc-in [index :seon.sci.eval/evaluation :seon.program/row] row)
-                       (assoc-in
+                   (cond-> all
+                     row
+                     (assoc-in [index :seon.sci.eval/evaluation :seon.program/row] row)
+                     true
+                     (assoc-in
                         [index :seon.sci.eval/evaluation :seon.turn/form-facts]
                         (assoc form-facts
                                :db/id
@@ -4572,7 +4572,7 @@
                                 (receipt-identity
                                  run-id (:seon.cluster.eval/ordinal (nth all index)))]))))
                  evaluated
-                 (map vector defining analyzed))
+                 (map vector submitted analyzed))
                 gated evaluated
                 requests
                 (mapv
```

After integration, replace the stale no-edge expectation in
`seon.fn-test/settled-form-records-calls-across-every-program-namespace`.
The required full proof uses the real virtual-reply turn harness: install a
contracted function and a test calling it, evaluate an ordinary form calling
a core function and a my.* function, inspect both persisted refs on that
evaluation identity, and require tests-reaching/check selection to find the
installed test. Assert the evaluation and target rows exist before querying
edges. The current fn-only regression does not claim that proof.

## Gates, cleanup, and landing

Attempted, once:

```text
bin/test --paths src/seon/fn.clj test/seon/fn_test.clj -- seon.fn-test
```

Exit **75**, before any test JVM or test assertions. Snapshot phase: **2 s**.
Exact refusal: `test runs are orchestrator-only right now (orchestrator batches
gates; set 2026-09-15 21:05Z)`. No platform invocation was attempted after this
explicit policy refusal. Both serial commands are queued in
`tmp/orchestrator/gate-requests/n7-eval-call-edges.txt`.
No gate success is claimed.

The code delta is **2 files, 77 insertions, 7 deletions**; the output of
`git show --format= --binary f402c5d3d` is **6,036 bytes**. No protected file
was edited. No scratch cluster or worktree was created; the refused gate's
shell exited. Probe scratch files are removed after retaining this evidence.
The refused gate left `tmp/test-runs/run.OtOT2Y`; its recorded launcher PID
68834 was absent, lane status and the JVM process table showed no holder,
and this lane removed only that snapshot without following symlinks.
The shared hook workers and another lane's files are not owned by this lane.
Markdown feedback reports existing gitlink citation errors in the separate
agents-md audit; those documents were not edited.

Final post-commit MCP observation: **56 ms**, the same three ordinary call
refs. Default's adopted source was `6aa9d3fc-9fe2-5348-91c0-53d22e0eaadb` and
current-src was `6aa9d576-f74f-59e8-8ba8-59cfe8540658`; complete convergence
was still not established. The shared development system remained alive.
