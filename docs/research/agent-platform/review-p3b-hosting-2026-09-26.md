---
type: review
status: complete
date: 2026-09-26
reviewer: Astra
subject: one-repl-language-2026-09-25.md, P3b hosting and section 2.10
design-commit: fee0feb7aeb3a698d46e7e8a0ab1ae0a0dc1e54c
verdict: REVISE
---

# P3b: host the branch's world, not just its connection

Seon already has branch acquisition, a loop-handle constructor, per-agent Flow graphs and a connection-directed settlement writer. The smallest composition is to connect those owners through one branch-local execution world and make the fixture call it; there is no need for another evaluator, graph blueprint, writer or store.

**Broken first:** sharing the parent's routing is incorrect across branches; the proposed constructor does not specify the branch-local SCI/projection inputs that the working fixture supplies; retirement lacks an admission and session-lifetime boundary. The existing write-back regression also failed in this review. These are implementation-shaping findings, so this is **REVISE**, not acceptance with editorial conditions.

I read AGENTS.md and the entire design found by `git show --stat fee0feb7a`. All source `file:line` citations below are at **fee0feb7a**, unless a dependency pin is stated. This matters: the design's `cluster.clj:3457` points into another location at its own commit; the committed `loop-handle` starts at **3383**. I also inspected the checkout, whose `cluster.clj` had another lane's edits, without changing it. The named agent/turn/SCI/fixture/bridge/Flow files are identical between fee0feb7a and the installed archive `0f78240dff52546ca8cce0ec4e8a9272d02c0ff8` used for the test below.

## Findings, most severe first

### 1. P1 — The cluster's routing atom is not a cross-branch routing authority

**Claim:** hosting step 1 shares `:seon.agent/routing` with the parent cluster (design:458–463).

**Evidence:** `src/seon/cluster/agent.clj:595–604` defines `:armed` by agent ID and `:channels` by numeric entity ID. `arm-graph!` inserts both at `:1241–1246`; `stop-graph!` removes the numeric route at `:1424–1429`. Datahike branches inherit the same stored database, changing its branch configuration (`reference-code/datahike/src/datahike/versioning.cljc:251–270`); entity allocation is `(inc (:max-eid db))` (`db/transaction.cljc:58–59`). Different agents created with equivalent transaction shapes on sibling branches can therefore have the same numeric eid. A second arm overwrites the first mailbox route, and retiring either can remove the survivor's route. Unique `mcp/<session-id>` strings do not fix the eid collision. Reusing the same session on two branches also collides in `:armed`: `arm!` returns an existing non-shutdown entry without checking the requested connection (`agent.clj:1138–1158`).

There is a second ownership problem: `release-context!` stops **every** graph in an owning handle's routing and marks that routing released (`agent.clj:856–890`). Giving an owning branch handle the parent's routing turns branch release into cluster-wide teardown. Giving it non-owning flags avoids that teardown but does not release its separately opened connection (`:895–906`).

**Fix:** keep the existing routing value scoped to the branch/owning execution, as isolated acquisition already does (`agent.clj:1085–1098`). Share the parent's fault-channel value and process resources, not its mutable mailbox table. Keep fixture routing under the executing test's ownership. If cross-branch routing is actually required, its identity and every consumer must explicitly include branch custody; that is a larger change than P3b and is not justified here.

**Required proof:** fork two siblings from one captured commit, deliberately create colliding numeric agent eids, run overlapping turns, deliver to each, and retire one while the other still receives. Also test one session addressing two branches, or explicitly refuse that operation before allocation. No new central dispatcher is needed.

### 2. P1 — A branch connection plus the parent's context is not an isolated execution world

**Claim:** `branch-handle` takes the parent process/launcher/projection executor and a branch connection, then calls `loop-handle` (design:458–463).

**Evidence:** `loop-handle` does not fork or repoint context. It copies `env/of ctx`, the context's projection-state and `ctx` itself (`src/seon/cluster.clj:3392–3405`). Its fresh context-state atom is not a replacement for those mutable objects. Once custody is the handle's connection, `acquire-context!` treats that branch as live and uses `source-ctx` directly (`src/seon/cluster/agent.clj:968–978`, `:1045–1059`). `sci.eval/acquire!` mutates the supplied base's program snapshot, projection and SCI environment (`src/seon/sci/eval.clj:3304–3339`). Passing the parent's base would let one branch's acquisition change another branch's base, even though agent contexts are subsequently forked.

The working fixture does more than build channels: it first calls `support/fork-cluster-ctx` on the candidate connection and constructs a projection executor from **that** context (`test/seon/cluster/write_back_test.clj:79–94`). The production seam already exists: `sci.eval/fork-cluster-ctx` creates receiving custody, environment-state and program-snapshot objects, and carries them onto the receiving connection (`eval.clj:3424–3430`, `:3456–3496`). Parent `projection-executor` captures its supplied projection-state (`cluster.clj:3436–3451`); it is not a neutral executor that can simply stand for the branch's projection.

**Fix:** specify the constructor's input as an acquired branch execution, or explicitly compose the existing branch acquisition/fork with `loop-handle`. Use the branch base, projection-state, environment, context-state and routing consistently. Do not implement a second fork, cache or regeneration mechanism. Share only the resources whose world is actually process-wide. Preserve caller-supplied fixture ports and dials through the thin test adapter.

**Reload:** keep the JVM loaded-program identity shared, not branch-local. `call-detecting-program-change` checks start/end identity (`eval.clj:1391–1427`); `evaluate` uses it at `:4187–4188`; publication marks the identity unknown before reload and records the new identity only on success (`cluster.clj:2165–2193`). `fork-cluster-ctx` preserves the loaded-connection reference in the snapshot (`eval.clj:3478–3483`), whose purpose is documented at `:1429–1439`. Branch isolation is not permission to replace this identity with a frozen branch commit.

**Required proof:** hold private atoms and different program rows on two branches, reacquire each repeatedly, and verify unchanged parent and sibling contexts/custody. Overlap an evaluation with the existing adoption boundary and require the named refusal; separately show unrelated sibling data writes do not trigger a reload refusal. Existing source supplies the mechanism; P3b's listed proofs omit this boundary.

### 3. P1 — Joining disarm is sound; the surrounding retirement protocol is incomplete

**Claim:** the session records what it armed; retire disarms, releases and unlinks, retaining everything if the executor is live (design:444–447, 474–483).

**Verified part:** Flow stop alone is not a join (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:174–183`). Seon creates a graph-owned executor and awaits its termination (`src/seon/flow.clj:82–101`, `:140–166`). `stop-graph!` raises on failed join **before** dropping routing/channels, and records interruption only after exit (`src/seon/cluster/agent.clj:1404–1432`). This is the right mechanism; no replacement join is needed.

**Missing boundary:** `submit-source-in-projection` commits first and then selects a mailbox/fallback wake; it checks no stopping/released ownership state (`agent.clj:701–726`). `disarm!`'s stopping reservation is temporary and is cleared on return (`:1380–1392`, `:1432`). A concurrent submission can therefore create a run after retirement's last join, or a fresh arm can race the subsequent release. Sequentially calling these functions is not a complete admission fence.

The proposed ownership record also lives in a transport entry that is deleted on socket timeout: `close-clj-session!` dissociates the entry before closing (`script/seon/dev/mcp.clj:195–202`), and evaluation timeout calls it (`:689–701`). Losing that entry while the host call continues loses the proposed evidence of which graph the session owns. Finally, the `branch` tool has no `session_id` input and uses a new one-shot session (`:306–318`, `:533–542`, `:859–867`). It cannot implement “disarm only what this session armed” merely by looking up its current transport session.

**Fix:** make hosted ownership survive socket closure, using the existing execution/custody owner rather than a second bridge registry. Define the session-to-host association passed to retire (explicit session identity, or an unambiguous existing branch owner). Atomically stop new hosting/submission admission before disarm; retain the owner, connection and branch through a failed join, and prevent rearming until release is complete. Extend the current lifecycle's admission mechanism; do not add a scheduler or replay queue. Specify failure unwinding for connection acquisition, agent creation and graph arming too.

**Required proof:** race submit and rearm against retire; simulate transport timeout while a finite host call ignores interruption; verify no unlink, no replacement execution and no lost ownership until actual exit. Test retrying retirement after exit and refusal to retire another session's live host.

### 4. P2 — The proposed host has no ordinary branch wake listener

**Evidence:** Datahike listeners belong to one connection, not a store (`reference-code/datahike/src/datahike/core.cljc:200–218`). Parent setup calls `wake/route!` on the parent connection (`src/seon/cluster.clj:3607–3618`). Neither `loop-handle` nor `arm!` installs it on the branch. P3b specifies fresh wake/stream/completion channels and no graph beyond the agent graph, but names no listener or consumer for the host's armer wake channel.

Direct submission can still work because it explicitly wakes an already-armed agent (`src/seon/cluster/agent.clj:716–720`); arm also primes once (`:1247–1248`). That is insufficient evidence for ordinary agent behavior: a later message/listen-pattern transaction on the branch will not invoke the parent's listener. `wake/route!` owns that delivery (`src/seon/cluster/wake.clj:499–538`), while the armer derives agents only from its own handle's connection (`agent.clj:1522–1536`). Sharing the parent's armer channel would therefore not repair it either.

**Fix:** state and implement the branch listener's ownership, registration-before-derivation and removal after admission closes. Reuse `wake/route!` and the existing agent lifecycle, with branch-local routes. Name how unarmed recipients and branch-created agents are handled. A narrower, always-armed single-session host is smaller than another cluster graph, but it must explicitly constrain additional agent creation and retain ordinary wake delivery to its hosted agent. Do not quietly present direct-submit-only behavior as a production cluster's behavior.

**Required proof:** after the agent becomes idle, transact an ordinary wake-producing message on its branch without calling `submit-source!`; observe its graph consume it. A sibling's message must not wake the wrong numeric-eid route. Confirm branch shutdown removes the listener and consumes/closes every host-owned port.

### 5. P2 — Reclassifying `:seon.agent/branch` as history is a broader, unnecessary change for the named-agent path

**Evidence:** the new `mcp/<session-id>` agent is created **on** the lane connection (design:474). `creation-tx` already writes that connection's branch through `branch-call` (`src/seon/cluster/agent.clj:196–206`, `:223–234`). For that new row, existing acquisition selects the same branch as the hosted handle. This is exactly why the write-back fixture can create its agent on C and arm it (`test/seon/cluster/write_back_test.clj:90–101`). Rewriting all default acquisition semantics is not needed to support this case.

The stored key currently means custody, not creation history (`resources/seon/schemas/seon.agent.edn:1`). Creation allows an explicit branch, and isolated acquisition rewrites it (`agent.clj:232–233`, `:1038–1044`). The design's claim that it merely records where creation occurred is false for those paths. Also, the broad claim that an agent row on default pointing to a lane must settle on default (design:451–453) omits `arm-graph!`'s merge of the acquired execution into the graph handle (`agent.clj:1184–1203`): that merge carries the acquired connection. Ordinary mismatched routing refuses earlier (`:1185–1189`). Submission and graph custody must agree; the row alone does not determine the described failure.

**Fix:** the smallest P3b uses newly created branch agents and validates agreement between the host connection and the acquired execution, keeping the existing explicit/isolate options. If custody-first acquisition is the intended general law, revise its schema description, writer behavior and all callers together; name how explicit branch selection and inherited rows behave, without silently changing a stored key's meaning. Keep that larger semantic decision out of a +45-line hosting estimate.

Reconcile the contradictory design passages in place: the table at `:401`, M1 at `:720–721`, P3 row at `:942`, requirement 8.8 at `:1004`, and verification limits at `:1025` still say inherited root/P-RB. The reviewed implementation must have one identity/custody contract.

### 6. P2 — The claimed regression is not positive proof, and it misses the important hosting cases

**Measured here:** one ordinary `seon.test/run` request on the review branch executed `seon.cluster.write-back-test/an-agent-replacement-merges-and-writes-back-its-exact-span`. Result: **1 executed, 0 reused, 0 pass, 1 fail, 1 error**, run `0553a130a656`, 24,713.504 ms. `run-id` was nil at `write_back_test.clj:39`; the subsequent wait failed after 20,000 ms for `:seon.cluster.write-back-test/turn-closed`. Full request and returned diagnostic are below.

The helper extracts only the turn ID from `virtual-turn!` (`test/seon/cluster/write_back_test.clj:34–43`), discarding the refusal that would explain a missing ID and then waiting on nil. An existing issue describes this class of fixture symptom: `docs/seon/issues/virtual-loop-fixture-submission-can-race-an-armed-turn.md`. This run did not retain the submission's refusal, so I cannot attribute it to that issue's particular cause.

Even green, this test asserts the candidate digest changes and then merges (`write_back_test.clj:107–123`); it does **not** assert the parent's pre-merge head is unchanged. It has no concurrent siblings, timeout/retire race, shared-routing collision or reload-overlap test. Its launcher/fault channel are fixture-owned (`:82–96`), so it does not prove the proposed sharing arrangement.

**Fix:** retain and assert the complete submission result before waiting; settle the current red with its owner, then run the converted fixture unchanged in behavior. Add the P3b boundary cases above through the real acquisition, armed contracts and Flow graph. Positively compare branch and parent program state before any merge. No wholesale test triage or invented fixture world is warranted. The foreign runtime/source boundary is documented below; no other lane was edited or contacted.

### 7. P2 — Deletion credit and hosting cost are not yet an honest price

The direction of section 2.10 is good: it calls +180–225 ms a regression, names writer owners and requests a ruling (design:589–610). It is not the required same-environment parent/change comparison: its 5 ms baseline is explicitly historical. It supplies no allocation or retained-memory results, and no branch creation, branch acquisition, first arm, warm hosted run, sibling concurrency or retirement measurements. `fork-cluster-ctx` walks installed functions to rearm interpreted bindings (`src/seon/sci/eval.clj:3489–3494`), so cheap Datahike branching does not prove cheap hosting. That work is proportional to installed functions, even when a branch contains no new declarations.

The deletion estimate also removes functions with surviving callers. `decoded-projection-event` is used by `one-shot-events!` and the fault window (`script/seon/dev/mcp.clj:315`, `:595`); `shown-event` still wraps JVM-mode events (`:662–670`). P3b cannot simply delete both while P3m/P4 and other one-shot adapters remain unconverted. The fixture's construction body is 17 lines (`test_support.clj:386–402`), not 30 lines of independent machinery; the full definition is 36 lines including its long docstring. Moved code earns no net-deletion credit.

**Fix:** reprice the actual P3b cut, including contracts, lifecycle ownership, changed adapters and regressions. Record setup separately from warm execution; time the same finite one-/multi-form requests on parent and change, with allocation and retained memory. Measure branch-head changes versus unchanged program acquisition, and count derivation misses. Resolve the already reported writer regression with root before integration; this review does not grant a performance exception. Delete shared adapters only with conversion of every remaining caller, in their owning slice.

## Claim disposition and smallest composition

| Claim | Verdict and evidence |
|---|---|
| A Datahike branch shares immutable indexes rather than copying the store | Supported: `versioning.cljc:251–274` publishes the stored root under a new branch; roster publication is last. Use `registry/branch!` (`src/seon/cluster/registry.clj:192–237`), not another branch implementation. |
| One `branch-handle` beside `loop-handle` | Appropriate owner and shape, conditional on branch-local inputs and ownership. It should compose existing functions and return data, starting no new cluster/runner. Findings 1–4 change its current proposed inputs. |
| Fixture `cluster-handle` becomes a caller | Supported. Delete the duplicate structural constructor, preserve explicit caller inputs, and derive defaults from the branch's config rather than `config/defaults` (`test_support.clj:386`). The thin test adapter still locates the executing member; that is legitimate fixture adaptation. |
| Settlement uses the hosted connection; no new turn writer | Supported by `settle-batch!` (`src/seon/turn.clj:3607`, `:3647–3650`), `settle!` (`:3839`, `:3855–3862`), and evaluation requests (`:4760–4771`). Keep submission, graph execution, elided DB calls and settlement on that same world. |
| Branch facts govern dials | Supported for structural dials at construction by `loop-handle` (`cluster.clj:3392`); AI settings are read per turn, as its docstring says (`:3386–3388`). This is not a promise that all structural dials hot-update. Config-read refusals must propagate, not become a handle with nil members. |
| Inherited root is acquired on the inherited row's branch today | Supported by `agent.clj:972–978`; that is why the named-agent alternative is smaller. It is not proof that every graph ignores acquired custody. |
| Disarm never treats cancellation as exit | Supported for newly created graph executors by `flow.clj:140–166` and `agent.clj:1408–1429`; complete retirement still needs finding 3. |
| Concurrent siblings and reload overlap are already proved | Not proved. SCI forks and loaded-identity checks exist; shared routing and missing world wiring violate the proposed composition, and the listed regression does not exercise them. |

The smaller viable cut is: acquire one branch execution through the existing entrance; construct its loop handle from that execution's world; create one named agent there; use existing `arm!`, `evaluate-source!`, receipts and settlement; release through the execution owner only after admission closes and its graphs exit. Reuse the branch's routing and the parent's fault fan-out endpoint. Generalize `root-run-form`'s transport adapter by supplied custody/agent rather than adding a third evaluation form. A constructor does not need to start another cluster graph, rebuild the program, copy a store or redefine `:seon.agent/branch` as history.

That smaller cut still owes ordinary wake delivery and session-lifetime ownership. Omitting them makes a smaller demonstration, not a complete production host. The existing owner functions should be extended only at those missing seams.

## Duplicate paths that must retire, counted once

| Existing path | Required disposition |
|---|---|
| `test/seon/test_support.clj:386–402`: fresh structural channels, caps and default dials | Replace with the production constructor; retain only fixture custody lookup and explicit caller overrides. Move shared construction once, rather than counting moved lines as deletion. |
| `script/seon/dev/mcp.clj:382–424`: `sci-evaluation-form`, including anonymous `[branch nil]` retained-context evaluation | Delete after branch SCI uses the same ordinary agent-run adapter as branchless SCI. Preserve context retention in the agent owner, not a second bridge cache. |
| `mcp.clj:474–480`: branch SCI versus root SCI execution dispatch | Collapse the execution choice to custody selection plus the one run form. Keep wire-mode validation. |
| `mcp.clj:295–304`: `shown-event` reconstruction of already-rendered SCI text | Remove the branch SCI consumer in P3b; delete the function once the remaining JVM consumer is converted (P4). No replacement text decoder. |
| `mcp.clj:282–293`: `decoded-projection-event` | No full P3b deletion credit while one-shot and fault-window consumers remain. Retire with their conversion in P3m/P4 and the relevant transport adapters. |
| `mcp.clj:503–530`: held-context scan, hand-built custody refusal and manual release/unlink sequencing | Move custody decisions to the existing production lifecycle. Keep create-name policy, registry idempotence and discovery policy at their current owners; these are not duplicate settlement code. |
| `mcp.clj:426–455`: `root-run-form` | Generalize its custody inputs; do not leave it beside a copied hosted-run form. Its evaluation and receipt logic already calls production owners. |

`loop-handle`, `creation-tx`, `arm!`/`disarm!`, `acquire-context!`/`release-context!`, `graph-definition`, `evaluate-source!` and the turn settlement functions are the reused owners, not deletion targets. The write-back fixture's behavior stays as regression coverage. P3m's fault-window and P4's JVM printer deletions cannot also be credited to P3b.

## Evidence, costs and verification limits

Dependency pins from `git ls-tree HEAD`: Datahike `4157916b5a1b8d80d7f809c889bce1b098b1d75d`; core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`; SCI `409888f61fa1f3bbade8d7e3eecb9b8e09c8004a`. The dependency seams above were read in those checkouts. No replacement library or algorithm is proposed.

Initial `bin/seon status` and MCP `runtime_status` reported pid 76020, faulted health, 10 fault signatures, 102 failed tests and 51 unknown failed tests. A later read-only JVM probe found only the process/store layers in `running-instances`; `branch-handle` was absent. A subsequent call returned `session-lost` because the JVM had been replaced externally. I did not stop, reset, resume or message another lane.

The replacement was pid 84287, started `2026-09-26T00:16:02.595Z`, running the committed archive `data/source/0f78240dff52546ca8cce0ec4e8a9272d02c0ff8`; publication was off because it was an archive runtime. Status reported 49,813 ms readiness, 2 failed tests and 39 unknowns. Those are external observations, not timings caused or accepted by this review. Checkout changes were not installed there; the reviewed files matched the archive as stated above. There was no need to create a worktree, modify that archive or widen a gate for this documentation-only diff.

Created `:review-p3b-hosting-20260926` through MCP `branch`, from commit `6ab70f5c-629d-59fb-8972-12d4624f46f5`, basis-t `536871065`. The exact focused request, through MCP JVM mode with explicit root `/Users/sean/src/seon`, cluster `default`, session `review-p3b-proof-20260926`, was:

```clojure
(let [instance (get @seon.operator.runtime/running-instances "default")
      parent (assoc (:seon.turn.loop/cluster instance)
                    :seon.store/store (:seon.store/store instance))
      execution (seon.cluster.agent/acquire-context!
                 parent nil {:seon.agent/branch :review-p3b-hosting-20260926})
      start (System/nanoTime)
      result (seon.test/run
              {:seon.test/execution execution
               :seon.test/recording-connection (:seon.db/connection execution)
               :seon.test/policy :named
               :seon.test/namespaces #{'seon.cluster.write-back-test}
               :seon.test/include-long? true
               :seon.test/check-time-limit-ms 90000})]
  {:elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)
   :passed? (:seon.test/passed? result)
   :tally (seon.test/tally result)})
```

Returned value (outside the MCP projection envelope):

```text
:elapsed-ms 24713.504
:passed? false
run 0553a130a656 / executed 1 / reused 0 / pass 0 / fail 1 / error 1 / 24713 ms / passed? false
red seon.cluster.write-back-test/an-agent-replacement-merges-and-writes-back-its-exact-span:
fail test/seon/cluster/write_back_test.clj:39
the agent accepted the submission
expected: run-id
actual: nil

error
Uncaught exception, not in assertion.
expected: nil
actual: clojure.lang.ExceptionInfo: "The test channel did not publish its required event."
  :seon.test-support/event :seon.cluster.write-back-test/turn-closed
  :seon.test-support/timeout-ms 20000
  at seon.test-support/await-event! (test_support.clj:483)
  at seon.test-support/await-event! (test_support.clj:425)
  at seon.test-support/await-event! (test_support.clj:445)
  at seon.test-support/await-event! (test_support.clj:425)
  at seon.test-support/await-event! (test_support.clj:431)
  at seon.test-support/await-event! (test_support.clj:425)
```

The MCP event reported 24,825 ms including surrounding work. Its armed profile listed `seon.schema/call-with-projection-state` 73,670 ms/37 calls, `seon.test/run` 73,428 ms/2, `seon.db/call-with-custody` 56,789 ms/72 and `seon.test/member-result` 42,706 ms/29. Those totals explicitly include concurrent work and are not this single test's exclusive cost. The local 20-second absent-event wait is the concrete dominant delay established by the returned diagnostic; no claim of sub-second operation or accepted long-running cost follows. Allocation, retained memory and independent arming-wrapper inventory were not measured; the request used the canonical installed harness, not a hand-armed subset. No passing installation, concurrent-host or reload-overlap claim is made.

The review branch was retired through MCP after the request completed; the tool returned `{:seon.store/branch :review-p3b-hosting-20260926, :seon.dev.mcp/retired? true}`. Raw failure evidence survives above. No production/source/test/skill file changed, no gate ran, and no private JVM or worktree was created. The sole changed path is this review; net source/test lines are **0/0**. The REPL skill's historical statement that all MCP SCI bypasses turns must not be treated as the current branchless path: `root-run-form` now calls `evaluate-source!` (`mcp.clj:426–455`). Skill edits are outside the explicitly assigned review-file-only scope.

**Verdict: REVISE.** Keep the one production constructor and fixture convergence. Revise world isolation, routing, wake ownership and retirement admission before implementation; reconcile the custody semantics and deletion/cost accounting, then obtain positive focused proof.
