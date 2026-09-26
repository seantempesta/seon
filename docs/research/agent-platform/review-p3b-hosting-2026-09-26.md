---
type: review
status: complete
date: 2026-09-26
reviewer: Astra
subject: one-repl-language-2026-09-25.md, P3b hosting and section 2.10
design-commit: d65bca33392abb176c61ecf9cb340979f272f0f5
design-followup: 07d4ccf4333d88c8b00693db2be5082e481b5e39
law-commit: 76302cccd22fbb51b0c079552234e8b887c20dd8
verdict: REVISE
---

# P3b re-review: ownership must have one authority

Seon already acquires branch executions, constructs loop handles, runs per-agent Flow graphs and settles through an explicit connection. P3b should compose those owners once and make test-support a caller; no second evaluator, writer, scheduler or store is necessary.

**Broken first:** the revised design still places host routing and admission state in an atom, contrary to this assignment's explicit constraint. Retirement does not yet identify a transaction-safe admission boundary or a retryable stop transition, and the narrow wake route promises a refusal its current dependency cannot produce. The focused write-back test now passes submission and replacement assertions, but fails at merge acceptance. Verdict: **REVISE**.

I read AGENTS.md end to end, the original design identified by `git show --stat fee0feb7a`, and the revised §2.6, reprice, §2.10 and slice/option prices end to end. During this re-review the author committed an additional atoms-law amendment, `07d4ccf43`; it is included here, rather than reviewing superseded text. The original seven finding numbers are preserved. **All production/test/bridge citations below are at d65bca333**, unless explicitly stated otherwise; **design citations are at 07d4ccf43**. Dependency pins and installation limits appear below. Other lanes continued changing the shared tree; no foreign file or session was edited or contacted.

## Disposition of all seven original findings

“Resolved” here means the design correction is adequate, not that P3b is installed.

| Original finding | Status | Ruling verification |
|---|---|---|
| 1. Parent routing collides across branches | **RESOLVED in design** | §2.6:461 selects separate branch routing and refuses a second branch per session before allocation. Source and the sibling probe support separation. The atom location remains new finding 8; overlapping turns remain a landing proof. |
| 2. Parent SCI/projection is not a branch world | **RESOLVED for SCI/base/projection; state-location part OPEN under 8** | §2.6:462,471–475 supplies the acquired execution and its own projection executor. Distinct contexts and projections are reproduced. Its context-state is actually the parent's shared atom; distinct SCI contexts do not prove otherwise. |
| 3. Retirement/session lifetime incomplete | **OPEN** | §2.6:463,476–488 moves durable identity out of transport and states the right exit guarantee, but the stopping-delay and submission transaction seams still need decisions. |
| 4. Missing ordinary branch wake listener | **OPEN** | §2.6:464 names the right listener and its lifetime. The single-agent policy, missing-recipient refusal and route dependencies remain unspecified. |
| 5. Unnecessary change to agent branch semantics | **RESOLVED in design** | §2.6:465 withdraws it, keeps custody semantics and checks agent/connection agreement. Canonical creation writes the correct branch in source and in the probe. |
| 6. Red regression and missing hosting proofs | **OPEN for proof; test-plan correction accepted** | §2.6:466 adds the missing behavior classes. The submission helper is fixed in source. The focused request still fails, now at the merge/test-recording boundary, and no hosted constructor is installed. |
| 7. False deletion credit and incomplete costs | **OPEN; deletion-credit correction resolved** | §2.6:492–501 removes shared-adapter credit and prices lifecycle/tests. Aggregate option prices remain stale; measured acquisition alone does not establish reuse or hosted-run cost. |

## Open findings, most severe first

### 8. P1 — Reusing a routing atom does not satisfy the new owner constraint

**Evidence.** AGENTS.md at 76302cccd:28–30 requires state in its owner's value and a reason no owner can hold any atom. This assignment is more explicit: host routing, ownership and retirement must live in the acquired execution value or Flow proc state, **not new or reused atoms**. The follow-up design:476–480 expressly puts graph handles, channels and admission state in the execution's existing routing entry, justifying that atom because graphs/executors have no database form. That explains why they cannot be database facts; it does not explain why the acquired execution value or Flow proc state cannot hold them. “No new atom” does not meet the instruction.

`routing` actually returns an atom (`src/seon/cluster/agent.clj:595–604`); `armed` dereferences it (:612); arm/disarm reserve mutable delays in it (:1138–1145,1373–1385). The schema requires an atom for both routing and context-state (`resources/seon/schemas/seon.agent.edn:122–133`). Putting those references inside an execution map does not move the state out of the atoms.

There is also a narrower factual error in describing all these members as branch-owned. Named-branch acquisition sets `held-contexts` to the parent's context-state unless `:seon.agent/isolate?` was requested (`agent.clj:1011–1014`). The exact MCP-style request uses an existing named branch, not that option. My sibling probe returned `contexts-parent-identical? true`, `contexts-siblings-identical? true`, and both context-state and routing are atoms. Separate branch keys protect many operations; this result alone does **not** prove cross-branch corruption. It does falsify a claim of separate context-state ownership and disqualifies it as an unexamined place for new host lifecycle state.

At d65bca333 the proposed session owner lived in that context-state. The follow-up correctly moves durable identity to the agent row. That supersedes the earlier proposal, whose extra execution-map fields also would not survive automatic reacquisition: `acquire-context!` builds a fresh selected map (:1075–1100), and `acquire-once!` replaces the retained entry (:929–939).

**Proposed fix.** Keep durable agent identity/custody as facts. For the explicitly single-agent host, carry its known agent, graph, ports and immutable route in the acquired execution value; carry transitions in the owning Flow proc state and durable admission decisions at the writer. Extend the existing lifecycle owner rather than creating a parallel host registry or armer. Name how callers obtain the current execution value after a transition. Reprice the necessary owner/schema/caller changes: unchanged `arm!` and a thin wrapper cannot satisfy the stated atom-free host-state requirement. My first review's advice to “reuse the branch's routing” is superseded to this extent by the new instruction.

### 3. P1 — Retaining a stopping reservation is not yet safe, retryable retirement

**Resolved portion.** A durable `mcp/<session-id>` row survives transport closure; adding explicit `session_id` to the branch tool removes its one-shot-session ambiguity. The stop/join mechanism should be reused. Flow stop sends a command, not an exit acknowledgment (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:174–183`); `seon.flow/join-graph!` waits for the graph executor's termination (`src/seon/flow.clj:140–166`). `stop-graph!` raises on failed join before route removal/channel closure (`agent.clj:1401–1424`).

**Still open, with concrete source consequences.**

- Submission stages and commits the run before waking it (`agent.clj:701–726`). It has no stopping check. Adding a pre-read of a stopping reservation still permits: submit reads open → retire closes and joins → submit commits on the released execution. §2.6 says admission closes but does not name a writer-time validation or serialization with admitted submissions. The relevant producer is `turn/system-run-call` (`src/seon/turn.clj:727`) and its caller, not a bridge-side check.
- The proposed “existing stopping reservation, held until release” is a **delay of the stop attempt**, not a boolean lifecycle state (`agent.clj:1373–1385`). A delay retains its result, including failure (`reference-code/clojure/src/jvm/clojure/lang/Delay.java:42–61`). Today `stop-graph!` clears it in `finally` (:1425), allowing another join attempt. Merely retaining that same failed delay until release makes the promised “retry after exit completes” rethrow the earlier failure without joining again. Retaining closure of admission and retaining one failed attempt are different responsibilities.
- `release-context!` already calls `stop-owned-graphs!` (:887–890), whose released mark precedes joining in-flight arms and every owned graph (:856–874). Repeating a separately owned stopping protocol around it adds another ordering problem. Extend the existing owner once, while addressing finding 8.
- Agent-row **presence** is not branch-exclusive ownership. A descendant branch inherits that row; `creation-tx` records explicit custody with `branch-call` (:196–206,208–234). The new retirement wording “branch holding that session's agent row” must validate the row's custody against the requested branch and retained execution, just as arming does. The second-branch refusal also needs an authority before allocation, not uniqueness inferred from branch-local agent IDs.

**Proposed fix.** Specify one admission decision covering the run transaction and rearming. At the writer, either the submission wins before closure and retirement owns its completion/interruption, or it loses with a named refusal and creates no run. Separate durable closure/current lifecycle state from a retryable join attempt, retaining the execution on every failed join. Validate session plus branch custody, including inherited agent rows. Show reverse setup unwinding without releasing a started graph until exit. Implement at these existing owners, not through an extra lock, transport registry or scheduler.

**Required proof.** Race submission/rearm against retirement; keep a finite host call alive past cancellation; show that no replacement execution or unlink occurs; retry retirement after actual exit; test a descendant inheriting the host row. None of the author's two-sibling identity probes exercises these transitions.

### 4. P2 — The narrow host's promised message refusal is not a capability of wake/route!

**Evidence.** The listener addition is correct: Datahike stores listeners on a connection (`reference-code/datahike/src/datahike/core.cljc:200–218`), and the probe sees only `:seon.sci.eval/program-identity` on each acquired branch, versus three listeners on the parent. Direct submission's explicit wake (`agent.clj:716–720`) does not prove ordinary message delivery.

But `wake/route!` takes armer and render channels plus render interest (`src/seon/cluster/wake.clj:489–493`), unconditionally dereferences interest (:503), and sends an unknown recipient to the **armer channel** (:532–535). It also wakes the armer on declaration changes (:527–528). It has no “message to any other agent is a named refusal” branch. Its callback observes a transaction report after publication; it cannot refuse the originating message transaction by throwing after the fact. P3b lists no branch armer consumer or complete explicit rendering inputs. Supplying an unconsumed channel would silently lose required work.

**Proposed fix.** Keep the narrower one-agent host, but state how the existing listener is parameterized for that scope and who consumes each requested port. Enforce any recipient restriction at the message/admission writer, or define a durable, delivered post-commit refusal with its exact semantics; do not describe a committed message as transactionally refused. Derive the listener key from the host identity as proposed, register before deriving work, and remove only after admission closes and in-flight execution exits. Preserve explicit fixture ports. Price this owner/caller change; do not copy `wake/route!` or introduce a second dispatcher.

**Required proof.** An ordinary message wakes an idle host without calling submit; a sibling with the same numeric eid does not receive it; another recipient gets the declared outcome; no owned port or listener survives successful retirement.

### 6. P2 — The diagnostic fix landed, but the focused write-back proof remains red

**Verified correction.** `test/seon/cluster/write_back_test.clj:34–48` retains the submission result, binds the candidate connection's custody, asserts the result and waits only when it has a run id. This fixes the original nil-id/20-second-wait symptom. The new test plan (design:466) now names unchanged parent, colliding siblings, ordinary wake, retirement races, timeout ownership and reload overlap. Those are the right behavior classes.

**Current evidence.** One installed `seon.test/run` request on `:astra-p3b-rereview-test` returned run `8dd71f2c596f`: **1 executed, 0 reused, 2 assertions passed, 0 assertion failures, 1 error; passed? false; 7972.157375 ms**. Submission and candidate replacement progressed. Merge acceptance returned:

```text
operation seon.cluster.source/accept-merge!
message "The named run was not recorded on the tested branch."
refused-test-run "b0fac1a98ea7"
```

The fixture then passed that refusal as an acceptance report to `write-back!`, whose armed input contract rejected missing `:db-before` and other transaction-report members at `write_back_test.clj:120`. Source explains the masking: `prepare-merge!`, `accept-merge!`, and `write-back!` all execute in the `let` before the assertions at :121–122. This run does not establish the deeper cause of the missing test record; assigning one would exceed the evidence.

**Proposed fix.** Have the merge/test-recording owner resolve the named refused run; preserve the full proposal and acceptance refusal before attempting write-back, just as the submission helper now does. Run the same regression after fixture conversion. It still lacks a pre-merge assertion that the parent's head/program is unchanged (:112–122). Add the revised P3b cases through the canonical harness. A green candidate replacement assertion proves branch settlement in that fixture; it is not proof of hosted MCP lifecycle, concurrency or the complete write-back.

The original failed request remains documented in commit `cb5a9550c77a36422187ec13b9baf76f7abbd1b1` of this file: run `0553a130a656`, 24713.504 ms, nil run-id and a 20000 ms missing-close wait. It is historical evidence, superseded as the current failure by the result above.

### 7. P2 — Corrected local credit still disagrees with the option prices and measured claims

**Resolved portion.** §2.6:492–498 now gives source +85/−87 and tests about +120. It credits the 17-line structural fixture body, not the whole definition, and no longer deletes `shown-event` or `decoded-projection-event` while their callers remain. This is a material correction.

**Still open.** The aggregate options at design:985–987 retain the old +285/−567/−282 for A. The revised rows for A actually sum to **+502/−537/−35** (P1 +40/−26; P2 +55/−25; P3a +197/−21; P3b +85/−87; P3m +45/−100; P4 +80/−278). On those same estimates B is **+664/−537/+127** and C **+774/−808/−34**, before separately estimated tests and any unpriced owner changes. These are arithmetic corrections to the table, not measured diffs. P3b's −2 is source-only; including its quoted +120 tests gives approximately +118. For comparison, `git show --stat a690aad68` reports P3a's complete source/schema/test commit as +268/−21, so the table's +197 also must be labelled as a subset rather than the complete cut.

The author's 132.5/8.2 ms acquisition samples are plausible but their exact forms, cache-miss counts, allocations and retained memory are not in the changed design. A faster second acquisition does not by itself demonstrate content-valid derivation reuse. The independent probe below measured 8.891334/8.109458 ms on fresh siblings in an already-warm process; it did not establish a cold 132.5 ms sample, unchanged-head misses, or any hosted turn's cost.

§2.10:591–632 correctly rejects performance acceptance and requires same-probe parent/change measurements. Its 5 ms baseline is still explicitly historical; the 184–231 ms run result and allocation/memory comparison were not reproduced here. Assigning the writer regression to another lane does not grant P3b an exception. Named branch acquisition may call `fork-cluster-ctx`, which walks installed functions (`src/seon/sci/eval.clj:3489–3494`); cheap `d/branch!` is not a proof of constant-cost context construction.

**Proposed fix.** Update the aggregate prices from the revised rows and distinguish source, schema, tests and unpriced work. Reprice findings 8, 3 and 4 before implementation; keep the smallest one-agent constraint. Record exact timed forms, stage times, allocations/retained memory and derivation misses for first/warm acquisition, arm, one-/two-form runs, concurrency and retirement. Keep the writer/refresh dependencies explicit until root has their proof. Do not book moved code or another slice's deletions.

## Resolved findings and unchanged source claims

### 1. Separate branch routing resolves the collision design

My probe created two agents with **the same eid 34898** on two siblings from one captured commit. Their routing was neither the parent's nor each other's. That reproduces the relevant claim (the author's historical eid was 34623, not expected to repeat). Source selects previous/branch-owner routing or allocates branch routing at `agent.clj:1085–1091`. This removes the original shared-table design defect and the danger of releasing every parent graph through `stop-owned-graphs!`. This acceptance is about scope; finding 8 governs state placement. No overlapping turns or retirement isolation were executed in this identity probe.

### 2. An acquired execution is the right constructor input

`loop-handle` at `src/seon/cluster.clj:3395–3446` copies context/environment/projection and reads structural dials from the supplied connection; it does not create an isolated SCI world. Its current context-state allocation (:3410) must not become a redundant host allocation. The revised constructor now takes the existing acquired execution and builds its projection executor from that execution's projection, matching `projection-executor`'s actual capture (:3448–3458). My probe independently observed separate parent/sibling SCI contexts and sibling projection objects. The fixture already constructs a branch context and corresponding executor (`write_back_test.clj:82–99`).

Preserving the JVM loaded-program identity is also correct: `call-detecting-program-change` compares before/after identities (`src/seon/sci/eval.clj:1391–1427`), the fork inherits loaded-connection (:3478–3483), and evaluation invokes the checker (:3888). These source mechanisms support the plan; context identity probes do not prove reload overlap or private-def retention. Those stay required landing tests. Shared context-state is addressed under 8, without claiming it invalidates the demonstrated SCI separation.

### 5. Keep the existing branch key's custody meaning

`resources/seon/schemas/seon.agent.edn:1` declares custody. `creation-tx` uses `branch-call` at the transaction authority (`agent.clj:196–234`); my two new agent rows correctly named their respective branches. For a newly created hosted agent, existing `acquire-context!` already selects that row's branch (:972–978). There is no need to change every agent's acquisition semantics or reinterpret the attribute as history.

The design still has an editorial contradiction at :1022: “a run of root's” followed by the session's own agent, and old namespace-continuation wording inconsistent with :427–433. Correct that requirement in the owning document. It does not reopen the resolved custody choice.

### Settlement and dependency composition remain sound

`settle-batch!` takes the connection from the supplied cluster handle (`src/seon/turn.clj:3611–3612`) and transacts through it (:3651–3655). `settle!` does the same (:3844,3860–3867); evaluation requests carry the selected connection (:4765–4788). **No second settlement writer is needed.** Correctly supplying custody, base, projection and execution is the fix.

Datahike's branch operation publishes shared immutable roots under a new branch and publishes the roster last (`reference-code/datahike/src/datahike/versioning.cljc:251–274`). Use `seon.cluster.registry/branch!`, not a store copy or fresh boot. Flow remains the executor; no central scheduler is justified.

## Smallest composition and complete duplicate retirement

The smallest production host in scope is one existing acquired branch execution plus one explicitly named agent and its Flow graph. Its execution value carries the world and live handles; its lifecycle owner controls admission/exit. `branch-handle` composes `loop-handle` and existing owners with explicit overrides, returns data and starts no graph. The fixture becomes its caller. The general MCP run adapter receives custody/agent as data; settlement stays unchanged.

**Anything smaller?** Reusing inherited root would avoid a new row, but P-RB and its stored custody refute that option. Direct retained SCI evaluation is smaller in lines but violates the required run/receipt path. Reusing generic routing atoms unchanged is smaller than changing their owner, but violates this assignment. A narrow single-agent route carried in the execution is smaller than adding another cluster graph, armer, registry or dispatcher; that is the constraint to retain while resolving the open seams.

| Duplicate path (d65bca333) | What P3b must delete or convert |
|---|---|
| `test/seon/test_support.clj:390–406`, structural fixture construction (17 lines) | Call the production constructor; retain only fixture custody lookup and explicit overrides. Avoid allocating discarded default atoms/channels. |
| `script/seon/dev/mcp.clj:382–424`, `sci-evaluation-form` (43 lines) | Delete the anonymous retained-branch evaluator after hosted SCI uses the ordinary agent run. Retain private SCI state at its existing owner. |
| `mcp.clj:474–480`, branch SCI/root SCI execution dispatch (7 lines) | Replace the two evaluation paths with custody selection and one run adapter. Keep mode validation. |
| `mcp.clj:503–530`, held-context scan, handmade refusal, release/unlink sequencing (about 20 credited lines) | Move host lifetime decisions into the production execution owner; keep branch create-name/discovery policy and registry idempotence. |
| `mcp.clj:426–455`, `root-run-form` | Generalize in place by agent and custody; no parallel hosted-run form and no full-function deletion credit. |
| `mcp.clj:295–304`, `shown-event` | Remove its branch-SCI use; the JVM caller at :665 survives until its conversion in P4. No full P3b deletion credit. |
| `mcp.clj:282–293`, `decoded-projection-event` | One-shot (:315) and fault-window (:595) consumers survive. Delete only with their owning conversions, not in P3b's price. |

Do not delete or duplicate `creation-tx`, acquisition/release, graph definition/arming/join, `evaluate-source!`, receipt readers or settlement. Preserve write-back behavior coverage. A constructor that leaves the fixture's independent structural defaults in place has not delivered the claimed convergence.

## Reproducible evidence and limits

Dependency pins: Datahike `4157916b5a1b8d80d7f809c889bce1b098b1d75d`; core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`; SCI `409888f61fa1f3bbade8d7e3eecb9b8e09c8004a`; Clojure `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`. Dependency seams above were read from those sources. No replacement library is proposed.

Initial `bin/seon status` and MCP `runtime_status` reported default pid **87270**, started `2026-09-26T00:23:17.031Z`, archive source root `data/source/2c9d6aa3b4af79a579a2fe748a90596d163663ab`, publication off. Health was faulted: one loaded-builder fault signature, 59 failed tests, one unowned namespace; readiness 31905 ms. These are foreign runtime conditions, not a pass or a cost approved by this review. The installed agent acquisition/retirement code matches the cited seams; the archive differs from d65bca333 by the later removal of error-fanout joins in arm-graph. The write-back test matches. P3b itself is absent: `(ns-resolve 'seon.cluster 'branch-handle)` returned nil.

### Independent sibling probe

Executed through MCP `eval_clj`, explicit root `/Users/sean/src/seon`, cluster `default`, mode `jvm`, session `astra-p3b-rereview`, bound 10000 ms. No graph was started. Both branches were released and unlinked in `finally`. The exact successful form was:

```clojure
(let [instance (get @seon.operator.runtime/running-instances "default")
      parent (assoc (:seon.turn.loop/cluster instance) :seon.store/store (:seon.store/store instance))
      store (:seon.store/store instance)
      from (seon.db/commit-id @(:seon.db/connection parent))
      branches [:rereview-p3b-a :rereview-p3b-b]
      _ (doseq [branch branches]
          (seon.cluster.registry/branch! {:seon.store/store store :seon.store/branch branch :seon.cluster.registry/from from}))
      start (System/nanoTime)
      a (seon.cluster.agent/acquire-context! parent nil {:seon.agent/branch (first branches)})
      after-a (System/nanoTime)
      b (seon.cluster.agent/acquire-context! parent nil {:seon.agent/branch (second branches)})
      after-b (System/nanoTime)]
  (try
    (let [ca (:seon.db/connection a) cb (:seon.db/connection b)
          write-agent (fn [connection agent-id]
                        (seon.db/call-with-custody {:seon.db/connection connection}
                          #(seon.db/transact! connection
                            (seon.cluster.agent/creation-tx {:seon.cluster/name (:seon.cluster/name parent) :seon.agent/id agent-id :seon.ns/name 'my.p3b_review_probe}))))
          ra (write-agent ca "p3b-review-a") rb (write-agent cb "p3b-review-b")]
      {:from from
       :acquisition-ms [(/ (- after-a start) 1e6) (/ (- after-b after-a) 1e6)]
       :writes-ok [(boolean (:db-after ra)) (boolean (:db-after rb))]
       :agents [(seon.db/pull @ca '[:db/id :seon.agent/id :seon.agent/branch] [:seon.agent/id "p3b-review-a"])
                (seon.db/pull @cb '[:db/id :seon.agent/id :seon.agent/branch] [:seon.agent/id "p3b-review-b"])]
       :routing-parent-identical? (identical? (:seon.agent/routing parent) (:seon.agent/routing a))
       :routing-siblings-identical? (identical? (:seon.agent/routing a) (:seon.agent/routing b))
       :routing-is-atom? (instance? clojure.lang.Atom (:seon.agent/routing a))
       :contexts-parent-identical? (identical? (:seon.agent/context-state parent) (:seon.agent/context-state a))
       :contexts-siblings-identical? (identical? (:seon.agent/context-state a) (:seon.agent/context-state b))
       :contexts-is-atom? (instance? clojure.lang.Atom (:seon.agent/context-state a))
       :ctx-parent-identical? (identical? (:seon.sci.eval/ctx parent) (:seon.sci.eval/ctx a))
       :ctx-siblings-identical? (identical? (:seon.sci.eval/ctx a) (:seon.sci.eval/ctx b))
       :projection-siblings-identical? (identical? (:seon.sci.eval/projection-state (:seon.sci.eval/ctx a))
                                                  (:seon.sci.eval/projection-state (:seon.sci.eval/ctx b)))
       :listener-keys (mapv #(vec (keys @(:listeners (meta (:seon.db/connection %))))) [parent a b])
       :branch-handle-installed? (boolean (ns-resolve 'seon.cluster 'branch-handle))})
    (finally
      (seon.cluster.agent/release-context! b)
      (seon.cluster.agent/release-context! a)
      (doseq [branch (reverse branches)]
        (seon.cluster.registry/retire-branch! {:seon.store/store store :seon.store/branch branch})))))
```

Complete native result fields:

```clojure
{:from #uuid "6ab71368-55c1-5d26-9c60-a4473233c74c"
 :acquisition-ms [8.891334 8.109458]
 :writes-ok [true true]
 :agents [{:db/id 34898 :seon.agent/branch :rereview-p3b-a
           :seon.agent/id "p3b-review-a"}
          {:db/id 34898 :seon.agent/branch :rereview-p3b-b
           :seon.agent/id "p3b-review-b"}]
 :routing-parent-identical? false
 :routing-siblings-identical? false
 :routing-is-atom? true
 :contexts-parent-identical? true
 :contexts-siblings-identical? true
 :contexts-is-atom? true
 :ctx-parent-identical? false
 :ctx-siblings-identical? false
 :projection-siblings-identical? false
 :listener-keys [[:seon.sci.eval/program-identity :seon.agent/route
                  #uuid "29f643f2-a64b-4c03-a009-f3265391bc0e"]
                 [:seon.sci.eval/program-identity]
                 [:seon.sci.eval/program-identity]]
 :branch-handle-installed? false}
```

MCP reported **629 ms** for the whole form, including writes and cleanup. Two earlier attempts were reviewer probe mistakes: an unmatched delimiter rejected before execution, then a missing required `:seon.cluster/name` in the creation request rejected by the armed contract; the latter's `finally` cleaned its branches. Neither is a product finding. The successful form includes that required member.

These results corroborate the author's eid-collision, routing separation, context separation, branch assignment and listener-count observations. They **do not** verify the author's exact historical 132.5/8.2 ms sample, derivation-cache reuse, overlapping turns, timeout retirement, reload overlap, or installation. The design's “each … probed” introduction (:456–457) must not turn source observations and future tests in its table into completed behavioral proof.

### One focused installed test request

MCP `branch create` created `:astra-p3b-rereview-test` from the same commit above, basis-t 536871852. Through JVM mode, session `astra-p3b-rereview-test`, bound 120000 ms, the exact single harness request was:

```clojure
(let [instance (get @seon.operator.runtime/running-instances "default")
 parent (assoc (:seon.turn.loop/cluster instance) :seon.store/store (:seon.store/store instance))
 execution (seon.cluster.agent/acquire-context! parent nil {:seon.agent/branch :astra-p3b-rereview-test})
 start (System/nanoTime)]
 (try
  (let [result (seon.test/run {:seon.test/execution execution
      :seon.test/recording-connection (:seon.db/connection execution)
      :seon.test/policy :named
      :seon.test/namespaces #{'seon.cluster.write-back-test}
      :seon.test/include-long? true
      :seon.test/check-time-limit-ms 90000})]
    {:elapsed-ms (/ (- (System/nanoTime) start) 1e6)
     :passed? (:seon.test/passed? result) :tally (seon.test/tally result)})
  (finally (seon.cluster.agent/release-context! execution))))
```

The test declares its existing long bound (`write_back_test.clj:50–51`); this review did not invent a larger test limit or run a gate. Returned tally and relevant diagnostic excerpt:

```text
run 8dd71f2c596f / executed 1 / reused 0 / pass 2 / fail 0 / error 1 /
7972 ms / passed? false
red seon.cluster.write-back-test/an-agent-replacement-merges-and-writes-back-its-exact-span
seon.cluster.source/write-back! refused argument 0:
[:seon.source/accepted :db-before] is missing
underlying acceptance refusal:
"The named run was not recorded on the tested branch."
:seon.source/refused-test-run "b0fac1a98ea7"
at seon.cluster.write-back-test (write_back_test.clj:120)
```

The native result's elapsed measurement was 7972.157375 ms; MCP reported 7984 ms. Armed inclusive profile totals included `seon.test/run` 8454 ms/2 calls, `seon.schema/call-with-projection` 7371 ms/137, `seon.schema/call-with-projection-state` 7279 ms/15, `seon.test/member-result` 5040 ms/1 and `seon.db/with-declarations` 4520 ms/15075. Totals include concurrency and nested calls; they do not identify this request's exclusive causal cost. The reported owners and foreign merge/test-recording boundary need investigation; this documentation-only lane grants no performance exception. Allocation and retained memory were **not measured**, so no memory or hot-path acceptance is claimed.

After the result, an external replacement started pid **94554** at `2026-09-26T00:40:37Z`. The first retire call hit the bridge's existing dereference-before-missing-layer-check (`mcp.clj:501,511`) while the replacement had no cluster connection, returning NullPointerException. I did not reset or stop it. Once readiness returned, the same branch retire succeeded with `:seon.dev.mcp/retired? true`. Both probe siblings had already been released/unlinked. All review-owned branches are cleaned.

**Foreign boundary / landing scope.** The test-recording/merge refusal, faulted archive runtime, subsequent JVM replacement, and concurrent source edits belong outside this file-only review. Work continued against pinned source and the available installed committed archive; no private JVM, git worktree, foreign-session access or `bin/test` gate was used. This document adds no executable input, so there is no changed-code installation or snapshot load claim. Existing archive evidence does not prove the moving shared HEAD. Root owns cold/platform proof.

Only this review file changes; net source/test lines **0/0**. The review uses the data-oriented Clojure, REPL, Datahike, Flow and testing skills already read for the original pass. The earlier review's noted stale REPL-skill claim that all MCP SCI bypasses turns remains outside the file-only assignment; branchless SCI uses `root-run-form` at `mcp.clj:426–455`. No unrelated skill or issue-index edit is included.

**Verdict: REVISE.** Accept the corrected named-agent custody, branch-local SCI/projection and deletion-credit decisions. Before implementation, put host state in the permitted owner, define transaction-safe admission and retryable retirement, and specify the narrow listener's refusal/port behavior. Reconcile prices and obtain the named positive proofs before landing.
