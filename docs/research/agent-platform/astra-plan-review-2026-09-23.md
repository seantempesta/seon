---
type: review
status: independent source review; implementation proofs not run
created: 2026-09-23
reviewer: gpt-6-astra (Codex)
---

# Agent-platform plan review — Astra

The system already has branch custody, source submission, transactions, test evidence and file analysis. Compose those into one working edit→test→accept→export loop before widening them; database atomicity does not extend to filesystem edits or JVM Vars.

**Verdict:** the direction is right; the acceptance and adoption guarantees are not yet sufficient. “Health → speed → cuts” needs a concrete exit: a reliable writer and REPL, then one complete branch-edit loop. Neither the 10k target nor universal sub-second completion follows from the present remainder.

**Boundary:** source/history/document review on `refactor/agent-platform`, HEAD `a0218d70a1e5d44583130bc7599cb13f221f1399`. No JVM, tests, runtime probes or `bin/seon` commands. `cluster.clj`, instrumentation and several other files contain stopped lanes' uncommitted work; adoption references below explicitly concern that working tree, not an installed guarantee. Dependency pins at HEAD: Datahike `c79cd03a`, konserve `5b39fddd`, superv.async `501b4294`, Malli `8725a8cb` (Malli also dirty). Costs below are rough engineering estimates, including focused proof, not measured execution times. Src/test change for this review: **0/0**. The edit hook reported 43 citation errors in other historical landing notes (old dependency pins); those files remain untouched. This is not a clean repository-wide Markdown-lint claim.

Plan citation abbreviations, all under `docs/prds/agent-platform/plan/`: **README** = `README.md`; **A1** = `lane-a1-projection-carried.md`; **B1** = `lane-b1-one-publication-path.md`; **B3** = `lane-b3-errors-tasks-dials.md`; **D1** = `lane-d1-isolation-merge-writeback.md`. Research citations use full paths.

1. **P0 — Merge acceptance checks a green subset, not the required proof.**

   D1:162–179 requires every current reaching test plus explicit task tests, against the immutable combined program. Committed `src/seon/cluster/source.clj:811–838` instead accepts a caller-supplied run if its own members are green and **some** green member reaches each replaced function (`:821–823`). The accept request carries no issue/task obligation, and nothing there establishes that this run is the complete prepare gate. A later passing subset can omit a failing reaching test or required task test. Moving this block into `seon.test` alone preserves the defect.

   **Change:** make D1 O3 start with acceptance correctness, before additions/deletions. Reuse B4's evidence owner to compare the complete required set against positive, current member evidence; bind it to immutable H/C/S and the proposal. `prepare-merge!` captures H at `source.clj:753` but passes a live source to `candidate-gate!` at `:783`; fork the captured H, not a subsequently reread head. First regression: two required tests reach one replacement, one fails, a separate one-test run passes—accept must refuse. Include missing task test and moved-H cases. **Cost:** 1–2 days; one evidence-owner conversion, no new gate registry.

2. **P0 — “Guards only” does not serialize reload, and record R does not certify runtime R.**

   B1:175–184 promises that a failed load leaves record R and safe interpreted differences; D1:354 says the expected-head guard replaces the adoption token. Working-tree `src/seon/cluster.clj:2577–2592` adopts rows, unmaps and reloads before arming (`:2602`) and the record guard (`:2624–2634`). A guard can refuse the last transaction; it cannot undo an earlier Var replacement. Interleaved reloads can leave the winning record naming code that a losing reload subsequently replaced. Seon calls ordinary `require` per namespace (`cluster.clj:2151–2161`); Clojure explicitly distinguishes `serialized-require` from that operation (`reference-code/clojure/src/clj/clojure/core.clj:6219–6226`). Neither is a transaction over load, arm and record. Even one failed namespace reload can leave changed roots, removed interns and top-level effects. “Every declaration is R's or C's” does not make compiled callers or old clusters safe. B1:313 already recognizes the need to establish actual callable identity after partial reload.

   **Change:** correct B1 §2a′ S4/S5 and README §7: distinguish branch acceptance from exclusive JVM convergence. Use the existing evaluation/adoption boundary to exclude conflicting reloads and dependent evaluations through load→arm→record. If that boundary is absent, state the gap; interim adoption has one orchestrator caller. A failed reload leaves affected execution unavailable until convergence is proven, not healthy merely because the record stayed R. First proofs: interleaved A/B adoption, failure halfway through a namespace, and an old cluster's indirect call. A1-1's “keep previous wrapper” also needs a new-Var/reloaded-Var case: `require :reload` has already replaced the root. **Cost:** 1–2 days to establish the boundary and tests; no new loader, token service or replay queue.

3. **P1 — D1's file exceptions recreate the gridlock; per-file fences do not install a file set atomically.**

   D1 §2d:219–240 requires a staged complete file set and callable proof before integration. §2e:308–318 instead puts host-bound/schema/new-namespace edits directly in the shared checkout before acceptance; :348–350 then claims files change only through accepted write-back. O6 removes Clojure file holds despite those exceptions. `src/seon/fs/jvm.clj:677–705` serializes its own writer and rechecks one file before an atomic move; shell editors do not share that lock, and successive moves are not a multi-file transaction. A second-file failure can leave the first installed. `adopt-then-test!` (`src/seon/cluster.clj:2729–2749`) returns red without rollback. `git revert` cannot undo JVM classes, `defonce`, database effects or running graphs.

   **Change:** narrow first O4 to existing interpreted function/test replacements with unambiguous provenance. Keep whole-file holds for every remaining file-edit path. Stage captured bytes outside live source paths; the source owner integrates the complete set while reload admission is closed, then verifies file bytes, publication, arming and behavior separately. Explicitly describe recovery after each installed file and before/after the Git commit. Host-bound changes retain B4's existing platform proof or remain deferred; do not describe post-adoption red as safe rollback.

   **Smallest interim protocol:** hooks lint only; file-disjoint lane work continues; lanes never independently adopt. At one orchestrator integration boundary, holders finish the affected namespace/schema closure, the orchestrator verifies its exact bytes, publishes/adopts once, then requests named tests. If a required dependent is dirty, defer that integration—do not restart with arbitrary WIP. Keep digest verification until exclusive integration is real. No filesystem token is needed with one caller. **Cost:** protocol now; 1–2 days for the narrow exporter and interrupted-install proofs; broader declaration export separately priced.

4. **P1 — The durability fix closes the reproduced body-error hole, not “every Throwable” or crash durability.**

   `docs/seon/issues/published-head-references-never-written-index-nodes.md:119–134` correctly shows that the per-key path has the same Error→closed-channel→nil hole as multi-key. Keep both the multi-key revert and Throwable handling. However, `reference-code/superv.async/src/superv/async.cljc:200–205` executes `finally` outside the catch; `:235–243` also calls supervisor tracking/unregistration outside protection from that catch. A failure there can still escape the promised result channel. This is a source-level remaining case, not a reproduced second store incident.

   **Change:** extend the existing durability issue/regression, not the storage architecture: inject Error at node/schema/commit/head writes and cleanup, in sync and async operation; require explicit failure, settled pending requests, and a fresh reader able to read the prior head. Separately qualify process-death persistence; an AssertionError test does not establish fsync/power-loss behavior. Correct `reference-code/datahike/src/datahike/writing.cljc:507–519`, which still calls non-atomic multi-key ordering safe. Delete the issue's “sub-second either way” claim at :155: checking all descendants scales with reachable index nodes, and 6,188 stats on depth-one trees proves only that specimen. **Cost:** 0.5–1 day focused failure matrix; crash qualification is a separate authorized platform drill.

5. **P1 — Bounded output is not bounded evaluation; `:every` also weakens the contract.**

   B3:192–197 promises a total floor for arbitrary values. `src/seon/render/value.clj:59–61` realizes a lazy collection with `take` and `bounded-count`; one element can block forever or perform effects before any character is written. `causes` calls `.getCause` outside a catch at :97, and `floor` calls `ex-cause` again at :129. The original error-floor investigation acknowledged the pre-print computation risk; the folded guarantee should not erase it.

   Separately, A1:238–242 mandates `:every` for non-countable seq arguments. Malli deliberately checks only a prefix there (`reference-code/malli/src/malli/core.cljc:1490–1513`, `:3036`). An invalid later member can pass; a nonterminating first element still hangs. This neither satisfies complete input validation nor solves termination.

   **Change:** in B3 E1, show opaque identity/type for unrealized sequences at the emergency floor; do not run their producers. Guard cause access. In A1 G7, distinguish explicitly prefix-checked streaming inputs from fully validated bounded finite inputs; never mechanically substitute `:every` while claiming the old guarantee. First tests: invalid element after the checked prefix, non-realizing floor rendering, throwing cause accessor. **Cost:** two small owner edits and regressions, 0.5 day; fewer permitted computations, no timeout worker.

6. **P1 — Error classification by Malli report key hides core bugs; the `:refuse` fork is now unnecessary for the ruled behavior.**

   B3:190 says every input/arity failure is an agent mistake and never reaches `fault!`. A valid agent call can enter core function F, which incorrectly calls G; G's invalid-input report is a system bug. Report key/member/layer alone cannot distinguish that from malformed boundary input. This repeats schedule row 24ai's classification problem (`docs/research/agent-platform/fix-schedule-2026-09-23.md:113`). A1:232 simultaneously specifies returning a `:refuse` value from instrumentation and restricts that return to the evaluation boundary.

   **Change:** B3 G6 classifies the rejected invocation using the existing evaluation/call provenance: caller-supplied boundary input versus a failure inside accepted core execution. Test both with the same G contract. For A1-8b, supply a throwing callback to Malli's existing `:report` hook for the declared refusal, and catch it once at evaluation. That already prevents the body and obeys the latest ruling; drop the return-value fork unless a surviving caller actually requires it. Keep validator-exception handling scoped to validator calls, not `f`. **Cost:** 0.5–1 day; deletion of the special return path, plus two classification regressions.

7. **P1 — The leak fix should delete the effect cache; cache sharing is not proof of release.**

   `docs/research/agent-platform/datahike-memory-retention-2026-09-23.md:49–65` gives strong root-path evidence for two Seon retainers; it does not attribute every byte to one cause. Current `src/seon/sci/eval.clj:2606–2638` still memoizes “recorded refusal” by program and recording target. It remembers an effect separately from database truth; a reused/reset branch can lose the fact while the cached success survives. The fallback target at :2622 is a callback, whose captures also require inspection. The ledger already queues cache deletion—make that the fix, not another key revision.

   **Change:** record/deduplicate through the existing error identity owner and inspect current branch facts. Then repeat failed and successful acquisition/release cycles over unchanged and changed programs; count retained released connections/DB roots and live threads, alongside heap. A plateau in shared node-cache count cannot establish that obsolete DB values or callbacks are gone. The earlier three-cycle “HEAP GATE PASSED” was followed by renewed growth (schedule:214,231); retain that falsification in the acceptance criterion. **Cost:** 0.5 day plus an observed mixed-workload interval; net deletion expected.

8. **P1 — Shrink then re-fork is preferable, but R8 before R9–R11 is not a loadable migration.**

   `docs/research/agent-platform/fork-audit-datahike-2026-09-23.md:342–370` removes the fork APIs at R8, then converts live validation, cache/read-evidence and bounds/GC consumers in R9–R11. R1–R7 do not eliminate those consumers. A clean upstream pin cannot be an independent landing while Seon still calls the removed APIs. The audit also explicitly ran no upstream tests (:39–43); “19 KEEP / 74 RIP” is a hypothesis inventory, not a qualified distribution.

   **Change:** retain R1–R7's dependency-independent deletions; prepare R9–R11 consumer conversions and qualification against the proposed upstream source, then land their pin and callers as one loadable cut. Split preparatory work by file ownership, not by pretending incompatible intermediate HEADs work. Do not port more cache machinery into the dying fork unless current health requires it. **Cost:** several days, dominated by consumer/proof work; substantially less wasted conflict resolution than the audited 141-hunk merge, but not merely “19 cherry-picks.”

9. **P1 — Change KEEP/RIP decisions by guarantee, not textual similarity.**

   Concrete amendments to that audit:

   | Row | Required disposition/change | First proof / cost |
   |---|---|---|
   | `fbd1ad2d`, :141 | Candidate to drop from KEEP: return the supplied immutable commit id in Seon's existing wrapper after successful `force-branch!`; no dependency patch solely for that return convenience. | Confirm failure behavior and existing caller inputs; hours. |
   | Eight KEEP-verify planner commits, :225–240; other KEEP+PR rows | Run each regression on upstream first, including the eleven PR candidates. “Only eight conflict” is not a proven merge guarantee. Keep reproducing behavior fixes, not obsolete implementations. | Fork qualification, roughly 1–2 days within #8. |
   | `73afe782`, :108 | Retain until tx-pred conversion proves branch-local `db-after` validation, final components/refs and every admitted raw write path. A store-level callback is not automatically equivalent to the current report contract. | Existing write-refusal classes; part of #8. |
   | `1e78cb9c`, :204 | Retain the required guarantee until redesigned: selector limits plus elapsed timeout do not replace query-work limits, and truncation must remain explicit. | Expensive small-result query and nested truncated pull; part of #8. |
   | `56f1c621`, :131 | Replace permits with upstream durable roots/store refs only when all held old DBs and blobs have demonstrated protection. A sweep-age floor alone cannot protect an arbitrarily old retained value. | Collection concurrent with old-context read and publication; platform qualification. |
   | `41c79c1a`, :249 | Keep complete-pull semantics; prefer the proposed explicit option. “Refuse exactly 1000” is not a complete replacement specification for nested selectors and legitimate 1000-member collections. | Nested/wildcard/explicit-limit cases; hours. |

   The no-op secondary-release removals are sound for today's no-secondary-index configuration. Preserve the owner's future upstream Proximum capability, not its unused fork (schedule:253). Do not replace speculative-value cache identity with commit id alone: `d/with` values need their own content/dependency evidence, already acknowledged by the fork audit:166.

10. **P1 — R3's proposed listener replacement reverses the safety ordering.**

    The fork audit:110,362 proposes **catch → record fault → unlisten by key**. Current `reference-code/datahike/src/datahike/writer.cljc:382–412` removes only the captured callback, **before** calling its failure handler. Recording first can invoke the same failing listener again; unlistening by key can remove a newer replacement. The smaller wrapper is not equivalent.

    **Change:** retain `2cc313a6` temporarily or prove an existing upstream listener seam preserves detach-before-record and replacement identity. Preserve the already-delivered transaction result too. First regression replaces the callback under the same key while its old invocation fails; the new callback survives, and the fault transaction does not recursively trigger the old one. **Cost:** 0.5 day, within R3; no new fault transport.

11. **P1 — Make the narrow self-edit loop the critical path; its MCP door still needs lifecycle semantics.**

    README:220–223 mixes save-gate/hook restoration with replacing that same lane workflow, and puts task-family/profiling expansion ahead of export. D1:339–346 already measures settlement at 0.7–0.8 s **plus** installation at 0.7 s; neither separate sub-second phase makes the operation sub-second. README:256–263 honestly prices roughly 55k source, not 10k. No further namespace-agent existence guarantee supplies the missing 45k deletion plan.

    **Change:** order the executable milestone: writer/REPL health → O0 → O1/O2 → #1 → narrow O4 → two isolated agents edit, test, merge, export, reload and call their changes. Defer additions/deletions, task renaming and nonessential profiling expansion until that loop works. Retire definition-time gating only when explicit acceptance is safe; D1:371's “independent” is too loose.

    For O1 (:287–306), specify listener installation before submission, exact run-id reconciliation, unknown outcome on disconnect, retry using the same admitted request identity, and release only after actual termination. “~50 lines” is an estimate, not proof those semantics or no-provider continuation exist. Reuse `submit-source!` and its run facts, not a second evaluator. Measure end-to-end leaf submission and export, with closure size and retained memory. Broad reaching-test runs cannot honestly promise sub-second completion regardless of body count. **Cost:** one spec edit now; approximately 2–4 days for the narrow loop after blockers, not including broad export or the separately unpriced 10k product tradeoffs.

12. **P2 — Remove contradictory instructions from the owning sections, not another ruling appendix.**

    | Evidence | Concrete correction |
    |---|---|
    | README §7 (`79f0b5204`, `59a908e55`) versus B1:198–213 | Remove the obsolete per-row load/S2/S3 and “Open” alternatives; retain ordinary `require :reload` and explicitly price #2's convergence boundary. |
    | B1:389–390 versus D1:296–303 | Delete the planned independent SCI evaluator arm when `submit` owns settled agent execution. |
    | A1:91,219 versus root “No stamps”; A1:125–130 and B1:453–480 versus one-JVM rules | Rewrite active metadata-carriage targets as derivation from explicit immutable inputs; remove scratch-JVM/hand-reload procedures and schema-reset acceptance requirements. Preserve old measurements only as historical evidence. |
    | D1:225,238 versus §2e:330–337 | Remove the obsolete isolated-checkout/publication-monitor language; specify captured staging plus controlled integration. |
    | README:209–218 versus schedule:250–252 | Replace the claim that landed source is loaded on pid 90963 with dated evidence and the last observed partial runtime. “Landed” is not adopted, armed or green. |
    | `tmp/orchestrator/file-ownership.md:8–24` | CURRENT HOLDS itself overlaps `cluster.clj`, repeats m4-n1, and grants “deps.edn lines only”; correct it to whole-file ownership before resumption. Its Waiting paragraph still queues `Compiler/load`, and :25 prescribes the rejected token. |

    **Cost:** 1–2 hours, docs only. Do not re-ask already answered floor/get_value/loading questions. A1-8b and B3 G6 need the substantive corrections above, not just re-anchored citations.

13. **P2 — Today's process rewarded local patches before checking the owner and dependency.**

    | Observed rework | Rule that prevents recurrence |
    |---|---|
    | Six parallel design documents rediscovered existing specs; orchestrator admits not reading them (schedule:230; fold `8d79ecc41`). | Every assignment names the owning section and a concrete missing guarantee. Review changes that section; no competing target document. |
    | Collector blamed, boot-only GC recommended, then no collector activity found; source publication hunk later shown a no-op (schedule:215–221). | Label hypotheses; require the owning dependency operation's result and persisted state before prescribing repair or cleanup policy. |
    | New shared-node-cache patch proposed before upstream `d2b9e525` was found (schedule:235–236); HTTP stub proposed before the existing completion-function seam (schedule:242–243). | Before relaying options, the orchestrator reads the dependency and first caller and states the smallest existing composition. |
    | Process-file grant crossed into another lane's `cluster.clj`; restart loaded dirty WIP and later remained partial (schedule:238,248–252). | Check actual whole-file holds on every follow-up; no implicit grant of callees. A restart is recovery, never proof of that WIP's acceptance. |
    | Test-load study proposes a capacity-two permit from a short workload observation (`docs/research/agent-platform/test-load-vs-dev-system-2026-09-23.md:168–211`). | First remove per-member recording/acquisition work and retained roots. Do not introduce a new admission cap contrary to the ruling; if later required, price an owner decision using peak live work and actual exit, including timed-out bodies. |

    **Cost:** no new mechanism; about an hour to reconcile assignments. Source-line deletion is useful evidence, but “test-only” alone is not retirement authority for public callable functions (`docs/research/agent-platform/cut-analysis-2026-09-23.md:244–255`; root AGENTS says every program function is callable).

**First five changes, in order**

1. Close the durability failure matrix and preserve writer failure visibility (#4); do not resume broad work on unqualified storage health.
2. Correct exact merge obligations before widening acceptance (#1).
3. Establish one JVM-convergence owner and honest failed-reload state; apply the interim integration protocol (#2–3).
4. Delete the refusal effect cache and remove unsafe lazy evaluation from the error floor; prove release and diagnostics (#5, #7).
5. Complete the narrow submit→test→merge→write-back loop (#11), then begin the paired upstream migration (#8–10).

**Owner decisions still needed—not permission requests from this docs-only review**

| Decision | Options, recommendation first |
|---|---|
| Host-bound first-loop scope | **A — Defer host-bound export initially (recommended):** existing interpreted replacements only; roughly 2–4 days for the narrow loop after blockers; preserves one JVM and gives up full-language self-editing temporarily. **B — Include existing isolated platform proof:** add roughly 1–2 days integration plus measured process cost; proves compiled behavior before live installation, gives up sub-second platform proof. **C — Adopt then test:** smallest immediate implementation, but no isolation/rollback guarantee; requires explicitly accepting that weaker behavior. |
| Upstream qualification | **A — Authorize the bounded dependency/platform JVM proof for the paired migration (recommended):** roughly 1–2 days qualification inside a several-day migration; retains behavior evidence, gives up immediate pin replacement. **B — Hold the current pin after safe deletions:** little immediate cost, preserves the fork burden and delays upstream fixes. **C — Hand-merge upstream into the fork:** audited 141 conflict hunks, several days, highest redundant work; no demonstrated advantage. |

Exclusive reload at an evaluation boundary is already a standing law, not a new product choice. If “guards only” is intended to forbid even that existing exclusion, #2 requires correcting the ruling before implementation. The 10k capability tradeoffs remain an owner decision after a measured deletion inventory; this review does not infer authorization to drop typed errors, read currency or complete validation.
