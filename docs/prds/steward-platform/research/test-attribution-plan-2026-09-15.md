---
type: research
status: proposal
date: 2026-09-15
tags: [research, test, steward, wake]
---

# Test attribution and steward alerts — decision page

**Recommend an indexed steward ref on the existing result/adoption transaction, with attribution derived from exact program values. Reachability identifies candidates, never proves “because of.”** Notify both the changed function's namespace steward and the failing/reaching test's namespace steward; this is how A's refactor reaches B. Do not introduce a problem family, kind stamp, detector roster, notification entity, polling loop, or cache.

Read end to end: [AGENTS.md](../../../../AGENTS.md), [program README](../README.md), [ideas](../ideas/stewards-self-improving-2026-09-15.md) (especially §§10–11), [audit A](data-audit-a-2026-09-15.md) (especially chains 1–3 and provenance), `src/seon/test.clj`, all five assigned schema resources, and the assigned fn/runner/wake/error sections at their **current** locations. Source anchors below describe inspected HEAD `5277c52ef134a01c9f91d4df0869f7c2dd493b05`, not the older line numbers in the assignment. Skills used: data-oriented-clojure, data-modeling, datahike, repl, clojure-testing (regression design only).

## Measurements: hypotheses → probes → verdicts

All successful probes used MCP **jvm**, `seon.db/db` over `(seon.operator/connection "default")`, immutable values, and `System/nanoTime`; no tests, writes, SCI evaluation or lifecycle operations. These are single observations, not percentile claims. Default changed during research. At basis **536872496**, distinct holders were **459 runs / 459 program digests / 0 test subjects / 2 namespace stewards / 0 runtime listens**; the assignment's 381 was historical. Stewards were only `my.agents.root → root` and `my.agents.juniper → juniper`. The census returned 24 red tests.

| Probe / hypothesis | Measured breakdown (ms) | Number → verdict; unnecessary work and structural kill |
|---|---:|---|
| P0: raw connection deref suffices | census 2.072; rejected red query 4.763 | Raw `@connection` lacked projection. Use `db/db`'s carried projection (`src/seon/db.clj:174`); no projection reconstruction per query. These are **not** valid red-query timings. |
| P1: current provenance and red rows exist | acquisition 0.218; red query 6.319; five counts 2.330 | Confirmed above; census is research setup, **zero** recurring census work proposed. |
| P2: T's earlier green can be recovered in this lineage | history links 1.021; four as-of pulls 4.263 | Six result-link assertions; green and red recovered. Retain history; no new “previous-green” mirror. |
| P3: the historical bases reproduce exact run programs | history/source candidate query 5.070; existing recursive reach query 7,457.558; two digest derivations 6,505.978; total **13,968.606** | **Falsified exactness**: both recomputed digests differ. Reconstructing/hash-checking a program separately for each attribution is unnecessary (`runner.clj:1351`); capture digest plus exact commit identity once with the acquired program and carry those values. Target **0 digest recomputations per alert**, not a promised zero acquisition cost. |
| P4: demand-bounded reverse edges suffice for P3's reach question | seed + 20 reverse rounds 7.293; test/ns join 4.604; total **11.898** | 225 entities, 164 tests, selected T absent, agreeing with P3. ThreadMXBean delta **19,251,368 bytes** on the evaluating thread (not total process allocation). Replace repeated unrestricted recursive relation work at `fn.clj:786–836` with one bounded, batched reverse derivation in that owner; existing manifest idiom is `test/selection.clj:134`. Target **<20 ms for this measured slice**, provisional, and zero visits to disconnected components. |
| Alert delivery / task instantiation | **not measured: absent** | No fabricated millisecond target. Target one steward datom per recipient per event, no polling, and no graph query inside the listener. |

P3's total covers all three measured intervals, not MCP transport/rendering. P4 is evidence for the selected slice, not a general equivalence or complexity proof. The dependency already has demand-restricted recursive execution (`reference-code/datahike/src/datahike/query/execute.cljc:3623`); first reshape the existing rule call to supply its demand, or reuse the existing reverse-frontier algorithm **in place**, rather than add another reachability API. No JFR was necessary for these directly timed query phases; no claims about virtual-thread page time are made.

## The actual red result, and the exact query boundary

T = `seon.fn-test/agent-source-reaches-the-evaluator-through-one-visible-path`:

| Result | Recording tx | Tested basis | Run | Stored digest |
|---|---:|---:|---|---|
| Previous green, 3/0/0 pass/fail/error | 536872481 | 536872480 | `0dcd16ceefec` | `746cf0b81f94ecca14ba0b5ece095b733ff3d74ab8fcc05b4a70eba0b3b1a403` |
| Red, 0/3/0 | 536872496 | 536872495 | `c938001fb2f6` | `b68eaf0a401e6354f4c11c3a7068fe36da60dbb7012ba2ff185133a712a13d09` |

P2 selected these through history, not mixed-time fail/pass/run datoms. A later green run `55b9031a0fda` at tx 536872503 has the **same digest as the red**; digest difference cannot by itself explain a failure. P3 recomputed `49d5132e2e0798e006137bef0c7fbc0854333a08161365edde64a3f808764833` and `20333b9c669791ebeb0574a0ac67e361dd8ec407d26426fbd1a18df9529d9ce7` from those historical bases. This is an **unresolved provenance discrepancy**, not proof of its cause or of a foreign lane defect.

The executed Datalog was the following pipeline (P2/P3); `db` is the explicit carried database, `h = (seon.db/history db)`, `new = (seon.db/as-of db 536872495)`, and `rules = @#'seon.fn/test-reach-rules`:

```clojure
;; P2: newest qualifying green strictly before R, after pulling EACH tx snapshot.
(seon.db/q '[:find ?run ?tx :in $ ?sym :where
             [?test :seon.test/sym ?sym]
             [?test :seon.test/run ?run ?tx true]] h T)
;; Pull counts + run at each tx; require positive passes, zero fails/errors.
;; P3: source-change candidates in this retained interval, including retractions.
(seon.db/q '[:find [?sym ...] :in $ $h ?lo ?hi :where
             [$h ?f :seon.fn/source ?source ?tx]
             [(> ?tx ?lo)] [(<= ?tx ?hi)]
             [$ ?f :seon.fn/sym ?sym]] db h 536872480 536872495)
(seon.db/q '[:find ?sym ?name :in $ % ?test-symbol [?sym ...] :where
             [?test :seon.test/sym ?test-symbol] [?fn :seon.fn/sym ?sym]
             (test-reaches ?test ?fn)
             [?fn :seon.fn/ns ?ns] [?ns :seon.ns/name ?name]]
           new rules T candidates)
```

P3 candidates were `seon.cluster.turn-test/with-cluster`, `seon.turn/system-turn`, `seon.turn/evaluate-sources`; intersection was **empty**, hence no function-owner recipients from that query. This ran against a real red result on default; current-src was not needed to find one. **It does not establish the requested exact-digest delta.** Missing: trustworthy digest→tested-program acquisition, including retained branch/store custody for exported runs. A branch-local integer basis cannot identify a foreign program; some observed runs name temporary `building-source-*` branches. Full rebuild currently preserves latest test stats and run rows, not all result associations (`cluster/source.clj:274`), so “previous green unavailable” must remain an explicit error.

The final query accepts **two validated exact program values**, compares canonical declaration facts using `program/shape` and `program/changed-attributes` (`program.cljc:809`), and supplies their changed function symbols to the reach query above. Derive the compared attributes from that owner, not a hand list; normalize refs by program identity across branches. Use the **union of old and new reach relations** so removing a call/test cannot erase its obligation. Namespace/schema/dependency-only changes need their declared affected scope or a typed widening result; source-only P3 is explicitly a probe, not that complete detector. Missing input, test, history or graph coverage is unknown, never “no work.”

For each returned function namespace, derive its recipient by ` [?ns :seon.ns/steward ?agent] [?agent :seon.agent/id ?id]`; retain unowned namespace rows separately. At commit time resolve ownership against the writer's database, following `error/steward-call` (`src/seon/error.clj:1272`). A null owner is an unowned finding, not successful notification.

## Preflight refactors, the declaration, and the task

**Before tests run**, this existing-fact Datalog gives cross-namespace test owners for F. Run it on both exact sides and union by symbolic identity; add the function-owner join above for A. Do not exclude two namespaces merely because they share one agent; deduplicate the final recipient set.

```clojure
[:find ?test-symbol ?test-ns ?recipient
 :in $ % ?function-symbol
 :where [?f :seon.fn/sym ?function-symbol] [?f :seon.fn/ns ?a]
        (test-reaches ?t ?f) [?t :seon.test/sym ?test-symbol]
        [?t :seon.test/ns ?b] [(not= ?a ?b)]
        [?b :seon.ns/name ?test-ns]
        [(get-else $ ?b :seon.ns/steward -1) ?recipient]]
```

`-1` here is a query-only missing-owner sentinel, never stored. P4's executable alternative repeatedly queried `[:find [?caller ...] :in $ [?target ...] :where (or [?caller :seon.fn/calls ?target] [?caller :seon.test/subject ?target])]`, starting with P3's three function IDs, subtracting visited IDs until empty under a 10-second backstop, then joined test/ns facts. This retains explicit subject semantics; the final owner must also retain `gate-set`'s pending-subject case.

**Minimal new wake fact:** proposed `:seon.test/stewards`, a cardinality-many `:seon.db/ref` with `:seon.db/index true`, `:seon.wake/listen true`, `:seon.wake/opens-turn? true`, `:seon.wake/inside true`, declared in `seon.test.edn` on an open transaction-shaped map. Put it on **`:db/current-tx`**, not the mutable test row or shared run row. Existing `:seon.test/run` datoms at that tx identify the result tests; adoption already has `:seon.test/adoption-identities` on its transaction (`cluster.clj:2039`). No copied T/F list is needed. A new result/adoption event has a fresh entity, so the same steward can receive the next event without retract/reassert. One run can deliver several result batches without suppressing its second alert.

`record-tx` (`runner.clj:1408`) receives the exact acquired program/delta as ordinary completion data; it decides recipients at the writer, commits result and directed refs together, and emits no directed datoms for an identical replay. Conflicting results for the same run/test must refuse or use a fresh run identity; otherwise one run cannot identify one observation. Green results do not generate repair wakes. Preflight adoption calls the same pure recipient derivation with changed identities. The listener remains unchanged: indexed attribute lookup and nonblocking offer (`wake.clj:369`); Datahike invokes it before delivering the transaction (`reference-code/datahike/src/datahike/writer.cljc:395`).

The **task trigger is that datom** `(tx, :seon.test/stewards, agent, t)`. A contracted subject/context function reads it, selects the recipient's T/F pairs and run/adoption evidence, and generates ordinary plan-item data keyed with `seon.id` by detector + trigger tx + recipient. Bind `:my.plan.item/subject` to the trigger transaction; derive obligations from its frozen inputs. Existing `done-query` settlement (`seon.plan:536`) calls a total success query: **nonempty required test set AND every required test satisfies `true? (seon.test/verified? db T new-digest)`**. Missing source/test/result, zero assertions, old digest, removed coverage or a query error cannot complete it. Carry the target program identity through the task evaluation; edits require a newly acquired target digest, not perpetual certification of the trigger's old digest. A wake being answered is distinct from its task passing.

## Owner decision: three priced options

| Option | Guarantee / cost / give-up |
|---|---|
| **1. Directed transaction refs, retained same-lineage programs — recommended first** | One new wake attribute; add `:seon.test.run/program-commit-id` (existing UUID value shape) captured with run provenance and retain its store custody. One recipient datom per event; roughly 1–2 engineering days across result/query/adoption owners plus canonical regressions. Exact attribution only when captured input programs and result history resolve; imported/unretained evidence returns unavailable. No claim that a bare new commit field fixes the measured digest discrepancy. |
| 2. Durable cross-publication attribution | Option 1 plus retained exact input commits/store identity and immutable per-run/test result components preserved by publication/GC. Approximately 3–5 days across runner, source and retention owners; storage grows with actual results, not just latest tests. Supports prior-green reconstruction after rebuild/import. Gives up the minimal same-lineage constraint; design this retention boundary before production edits. |
| 3. Authored runtime patterns on fail-count/error-count | At least two patterns per selected test per steward, maintained as graph/ownership changes; broad patterns cost every steward a derivation on every matching result. Approximately 2–4 days including durable eligibility/restart/retraction policy. **Today guarantees only a proc notification, not an unanswered wake**: [existing issue](../../../seon/issues/runtime-listens-do-not-yet-participate-in-turn-eligibility.md). Scalar counts cannot express arbitrary positivity, and identical repeated counts emit no datom. Gives up reliable per-run triggers unless listening also to run changes and extending eligibility. Not recommended. |

`:current-src` is non-executing; recording there alone cannot wake default. Option 1 acts in the executing cluster's result/adoption transaction and refuses unsupported imported attribution. Option 2 must carry evidence through the existing adoption owner and rederive recipients there, never open a second listener/connection to current-src. These are design choices for the owner, not authorization requested by this research lane.

## Exact regressions and ownership

* `seon.cluster.wake-test/cross-namespace-test-failure-wakes-both-stewards`: canonical `with-database`, real SCI admission of F in A and T in B with its actual call edge, armed contracts, passing baseline; change F and run T through the real runner to obtain a failing result. Assert B **and A** have an unanswered `:seon.test/stewards` wake whose transaction-derived context contains exactly T, F and R; unrelated C has none. Listen before commit; use the canonical event backstop. Replay R creates no new wake; a fresh red run does; same-run distinct test batches remain visible; green/zero-result batches do not wake. Current code fails because the directed fact does not exist.
* `seon.fn-test/reach-selection-does-not-expand-disconnected-components`: canonical graph with a relevant cyclic component, disconnected component, explicit and pending subjects, and removed-call old/new cases; assert exact test sets and that demand/iteration evidence never includes disconnected identities. This proves the structural kill rather than relying on a timing threshold. Preserve one reach owner.
* `seon.test-runner-test/attribution-requires-the-exact-tested-program`: capture real green/red runs; verify stored digest against the captured input commit, then attribution after later edits; mismatched/unavailable/foreign bases return typed unavailable. Assert one acquired identity per batch and **zero digest derivations in alert generation**. Include two different outcomes at one digest: return evidence, never a causal verdict.
* `seon.plan-test/test-trigger-completes-only-on-current-positive-results`: missing T, empty obligations, zero assertions, old digest and error all fail; all reaching obligations positively verified on the new acquired program pass. Mere answering of the wake does not pass.

Implementation ownership if approved: `src/seon/fn.clj`, `src/seon/test.clj`, `src/seon/test/runner.clj`, `src/seon/cluster.clj`, `src/seon/plan.clj`, `resources/seon/schemas/seon.test.edn`, `seon.test.run.edn`, corresponding `test/seon/fn_test.clj`, `test_runner_test.clj`, `cluster/wake_test.clj`, `plan_test.clj`; option 2 additionally owns `src/seon/cluster/source.clj` and the existing retention owner. No wake-dispatch implementation change for option 1. **This lane owns only this note.** Initial protected edits were `src/seon/sci/eval.clj`, `src/seon/turn.clj`, `test/seon/cluster/turn_test.clj`, `test/seon/fn_test.clj`; final pre-write status had no modified tracked paths, with foreign untracked `build/` and `workers/` preserved. Recheck ownership before implementation.

Operational boundary: initial status showed PID 69622 alive and search ping unknown. Two named MCP sessions later returned `session-lost`/“cluster restart”; fresh session IDs answered. No restart cause is inferred and no lifecycle action was taken. Oversized MCP result projection produced retrievable blobs; probe forms themselves made no writes. The projection refusal, provenance discrepancy and runtime-listen gap are recorded here rather than hidden. No test JVM, worktree, background shell or scratch file was created. Stop condition: plan written; commit this path only and stop.

Landing check: whitespace check passed. The Markdown hook reported foreign gitlink-citation errors in `docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`; those are outside this note's verification boundary and were not edited. A concurrent untracked `docs/prds/context-generation/research/suite-efficiency-plan-2026-09-15.md` was also preserved. Source moved during research: the final read placed `program-digest` at `runner.clj:1358`, `record-tx` at `:1420`, and `commit-results!` at `:1487`; earlier anchors above identify the same inspected functions.
