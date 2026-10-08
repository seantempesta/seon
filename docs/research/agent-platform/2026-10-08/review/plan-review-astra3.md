---
type: research
status: reviewed
date: 2026-10-08
lane: plan-review-astra3
---

# Review of the revised agent-loop plan

Reviewed commit: `088c62000`. Lane: `plan-review-astra3`. Verdict: **revise before launching the rows described as ready**. The plan contains the right goals, but its early milestones do not yet deliver a working model loop, several launch contracts are incomplete, and the proposed live-result weights cannot bound retained memory.

Authority read in full: `owner-rulings.md`, `AGENTS.md`, `docs/seon/schedule/agent-loop-ledger.md`, `docs/seon/specs/agent-loop.md`, and `docs/seon/specs/merge.md`. Also read the Clojure, REPL and testing skills. I did not read `plan-review-opus3.md`, change the ledger or specs, contact another lane, or create a worktree. References to ledger lines below mean the reviewed commit, not a subsequent revision.

Default was reachable but faulted before probing: six open faults, 54 errored records, eleven over-bound warnings; heap live about 1.03 GB, configured maximum about 11.0 GB. Its archive was `75aa6990b229bac568cf83d885588f1bcf43f5ef`, so source at HEAD is not evidence of installation. All live probes used MCP `eval_clj`, cluster `default`, agent `plan-review-astra3`, JVM mode, with no application writes or reloads. MCP's own recording is still an effect of calling the entrance.

## Highest severity

### 1. Printed-size weights do not bound the live-result cache

**Where:** ledger SR-H, line 482; agent-loop §3.4.

SR-H weighs the full retained object using `:seon.eval/value-tokens × 4`. That number comes from the printer's `whole-characters`, not retained object size (`src/seon/sci/eval.clj`, `shown-result`; `src/seon/render/value.clj:1511`, `omitted-characters`). The latter explicitly calls its estimate a lower bound, with unknown tails counted as zero. Opaque host objects and containers holding them need not expose their retained payload to the printer at all.

Measured through the installed printer: a 1,024-byte array and a 1,048,576-byte array both report 22 whole characters, charging **24 bytes** under this proposal. Both have zero printer diagnostics. Thus even the proposed “20 values of 100 KB” proof depends on which values the implementer chooses. Caffeine correctly enforcing those weights would still permit excessive retention.

**Fix:** keep Caffeine as the sole eviction owner, but replace the asserted byte guarantee with a measured, explicit weighing policy. Use cheap native sizes where available; bring the exact unknown-size case to the owner instead of pretending printed size measures it or adding an arbitrary handle count. Include opaque payloads and lazy values in the retention proof, check strong references in SCI after eviction, and measure retained heap. Do not add a second cache or an eager walk of every result.

### 2. The first milestone builds current-state data but does not put it in a model call

**Where:** ledger CS-01a..CS-02, lines 281–285; CS-03a, line 297; critical path steps 1 and 7, lines 643–671.

“Tasks show their current state (first)” ends at the block renderer. The model-message adapter waits for S4-04 and is placed at step 7, after merge groundwork, task identity conversion and state/rename work. The existing call still obtains the old prompt and passes its text into `ai/complete` (`src/seon/turn.clj:4622–4646`; `src/seon/cluster/prompt.clj:403`; `src/seon/ai.clj:564–578`). A pure `seon.ai/messages` test does not prove that this caller uses it. The row names only `ai.clj` and its test, leaving that integration ownership unclear.

**Fix:** make the early milestone a fixture-provider call whose actual request contains the current task, current proof source/status and unread items exactly once. Pull forward the required prefix/history/message assembly and its production caller conversion; delete the replaced route in that same loadable slice. Give the wire test an exact name and file. Put the debug page, additional wire formats and unrelated renames after that working path. Do not introduce a temporary second context assembler.

### 3. Automatic tests can lose unfinished relevant work or finish a task while checks remain unknown

**Where:** ledger F4/F5, lines 332–335; agent-loop §1.7 and §3.5.

F4 selects tests reaching the latest request's change, allows unfinished tests to continue, and says a newer change supersedes the queued tests of the older program. It does not say how an unrun test reaching an earlier, still-present change survives that replacement. F5 permits a request when proofs are green and no broken test is red; pending reaching tests are not expressly a blocker. For example: change `a`, hit the test-step bound, then change unrelated `b`. Replacing the queue with only tests reaching `b` can forget `a`'s pending test, and “no red” can be true before that test has run.

The same text promises the next call waits no longer than the step bound while requiring a started test to run to actual exit. A host test can exceed that bound; the spec cannot promise both.

**Fix:** derive pending relevant tests against the current branch's cumulative change and current positive evidence. A newer program replaces obsolete executions, not obligations still relevant to the final program. Require current evidence for the complete required selection before requesting a merge. Add a named two-change regression with a bound leaving the first change's test pending, plus a stopped/restarted proc case. Describe the step bound as an admission bound, with actual-exit ownership stated separately; no new scheduler or dirty flag is needed.

### 4. Automatic merge subscribes too late

**Where:** ledger MA-12, line 367; merge.md §5.

Both specify deriving open requests at start, **then** listening. A request committed between those operations is absent from the initial read and produces no observed wake. It can remain open indefinitely. This directly reverses AGENTS.md's “Listen before deriving work.” The named restart test covers a request written while down, not the read/listen gap.

**Fix:** register the existing Datahike listener before reading open requests. Process replaceable wakes by re-deriving current facts on root's existing serialized path. Add a deterministic test committing a request during initial acquisition, with no later transaction needed to trigger it. No new queue or polling loop.

## High severity

### 5. Latest owner answers make several blocked rows and budgets obsolete

**Where:** ledger RP-9, F5, §6 questions 3–5; budget table and RP-0; agent-loop §2. These are **post-review-commit updates**, not faults in the author's knowledge at `088c62000`.

Commit `6548ab9d3` answers nested definitions, no-change tasks and subtree counts. RP-9 still recommends making every nested definition live-only; F5 still waits on the no-change case; §6 still asks all three questions. Commit `4707b9546`, which arrived during this review, also explicitly rejects invented MB/ms targets, including the 60 MB target invoked by RP-0. The owner retains the stated model-pass allowance, no-GB rule, 16 GB machine requirement, raw-return bound, and AGENTS.md's ordinary-operation bounds.

**Fix:** save a nested definition from its own captured span unless it closes over locals; test both cases without replaying enclosing effects. Complete a no-change task after verifying main still holds the tested program. Add the sorted-set insert/delete count-maintenance slice with its actual fork files and proof. Remove those three owner questions. Replace arbitrary target numbers with the work count, expected scaling, parent measurements and explanation of excess cost. Do not erase actual measurements or use this ruling to excuse repeated whole-program work.

### 6. One row restores measurements that its dependency deletes

**Where:** ledger S5-02, line 308; RO-10, line 410; agent-loop §6 decision 2.

S5-02 deletes stored profile measurements so health reads the live profile. RO-10 depends on S5-02 but then settles output samples “with the definition's measurement” in `src/seon/profile/measurement.clj`. RO-11 depends on that restored storage. This is a direct producer conflict, not merely two rows sharing a file.

**Fix:** put output observations in the existing live per-function profile cells, which the atom ruling expressly keeps. Have health read those cells, and remove the transaction/storage work from RO-10. Keep the full returned value unchanged and use the existing value printer for display. Prove output observation does not write profile datoms.

### 7. Norms still specify a second implementation without an approved library gap

**Where:** MA-8a..MA-8c; merge.md §4, paragraph beginning “Seon takes the concept and its rule”.

The spec explicitly elects to copy Datahike's norm recording/skip behavior instead of using `ensure-norms!`. There is a real mismatch: at pinned Datahike `86c3301a`, `norm/norm.clj:140–161` calls each norm with a connection and transacts separately, while Seon wants pure database-to-transaction functions and one guarded merge transaction. The library's public `ensure-norms!` at line 204 reads resources. The spec cites the older `e5453d96` revision and treats the mismatch as permission to build around it. The owner's approval of norms as a feature is not a recorded decision approving this replacement mechanism.

**Fix:** present that exact gap to the owner before launching the norm implementation. Prefer a small maintained-fork seam that computes ordered norm transaction data and preserves the dependency's installed-norm tracking, then compose it with the existing guarded merge. If the owner chooses a Seon implementation, record that exception explicitly. Do not call the current resource-loading API a drop-in solution; it is not one. Expand §3.22 into executable rows: the reinvention census was already committed at `9246a9ac0`, before this plan, so “rows arrive” leaves already-available findings unscheduled.

### 8. Ready rows are not yet bounded implementation assignments

**Where:** F4, MA-11, NK-7, NK-8b, CS-01c, AT-1/AT-4/AT-6, PR-20a-d, PR-A/PR-B, and the rename rows.

Examples: F4 covers selection, time bounds, restart, supersession, model and MCP entrances and reply reporting; MA-11 combines test policy, request supersession, abort/no-commit, provenance and replacement of multiple public APIs; NK-7 includes 543 task entries, identities and seven test files; NK-8b spans fourteen source files and 157 test references. PR-A/PR-B say “per checkpoint” for files, proof and budget. Several renames leave the final names to a “design check.” Error and merge tables omit file and budget columns altogether. These are work packages, not the owner's roughly fifteen-minute slices with no design decisions left.

**Fix:** mark these as awaiting a split, preserving their position in the plan. For each launch, list exact source/schema/test paths, input/output contracts, one observable change, fully qualified proof Vars, deletion scope and a cost measurement proportional to that operation. Split large mechanical sweeps by loadable ownership boundaries, never by leaving old and new APIs alive. The errors cut already correctly says it needs this treatment; apply the same standard elsewhere.

## Medium severity

### 9. Statuses and proof names still disagree with committed evidence

**Where:** RED-2/RED-3, lines 572–573; EN-7, line 318; S7-01, line 330; PB-02, line 145.

- RED-3 schedules the nine stale `code-evaluates-as-one-ordinary-run-of-its-agent` assertions for conversion. `ad831095f` already converted them, and `review/red-triage.md` at `8bd6a1cb9` records them green. Both precede `088c62000`. RED-2 likewise repeats the old acquisition population without subtracting the fixes in `a4c4b7262`; its remaining defects are already individually described in the red-triage report. This is not a claim those tests cannot regress again; new red requires a new run and basis.
- EN-7 marks `seon.sci.documentation-test/run-test-var-answers-one-branch-test-as-data` **existing**, but that Var is absent from the test file and its Git history. S7-01 proposes creating it. EN-7 has no dependency on S7-01.
- PB-02 landed after the review target, at `754e20ff6`, while the live plan still says in flight. That commit changes only `src/seon/sci/eval.clj`; it does not add the ledger's proposed `the-installation-check-reads-only-program-rows` proof. Record what its actual proof was rather than silently certifying the proposed one.

**Fix:** import the already-landed red classifications, retain only evidenced residuals, and label observation commits. Correct EN-7 to name a real existing runner proof or depend on the proposed proof's producer. Mark PB-02's code landing separately from its named-proof status and installed status. A commit title is not proof that a planned test exists or that default loaded it.

### 10. Dependencies add avoidable delay and miss actual ownership collisions

**Where:** S7-01 → F4; §5 steps 6, 7, 11–13; groups and Deps columns.

F4 uses `seon.test/run`, which exists; requiring the new manual `clojure.test/run-test-var` convenience adapter first is not a stated semantic need. C7 needs C0/C4, yet the prose route places the broader conflict/research/bail suite before a single T1 smoke. The printer/file groups also serialize whole families without explaining a data dependency, while some cross-group file overlaps remain unrepresented: PB-03 (C) and AT-1 (B/M) both edit `src/seon/cluster.clj`; S5-06 (E) overlaps RP rows (B) in `turn/batch.clj` and `cluster/agent.clj`; LR-PROOF (T) edits test files owned by database and reader rows. Claims prevent damage at launch, but they do not make this a reliable parallel schedule.

**Fix:** distinguish semantic prerequisites from file-release prerequisites. Remove S7-01 from F4 unless the implementer can name the missing runner seam. Schedule the minimum T1 launch/score, current-state call, automatic tests, required merge correctness and measured model-cost fixes before the wider matrix. Run unrelated renames, state consolidation and extra competency cases outside that first smoke path. Add explicit file-release ordering to the actual overlaps, including test files. Do not infer a new concurrency cap from the broad groups.

### 11. Several new seams are mistaken for available targets or lack a complete file closure

**Where:** ledger Files and Proof columns throughout; especially F6/A1, CS rows, S1-08, S5-06 and EN-7.

I checked literal file references across every table: 197 source/test/schema path spellings resolved directly or by suffix. The apparent missing research probe paths resolve under `model-pass/` and `memory/`; the three state/merge schema files are explicitly new. No missing literal existing file was established by that check. It does **not** make entries such as “those owners”, “schema”, “test”, “the eight callers” or “per group” a claimable file list.

Available source owners include `installation-covers-program-change?`, `directory-value`, `stored-source`, `selection-seeds`, `clean-request-handle` and `program-written-since?`. Proposed names include `current-state`, `seon.ai/messages`, `my.agent/inputs`, `seon.db/last-agent-ordinal`, `held-context`, and `agent-run-test-var`; their absence is expected if the row clearly creates them. S1-08 should explicitly say how new `source-value` replaces existing `stored-source`/`stored-source-fn`. F6 reads `my.task/answer`, whereas A1 creates `my.agent/answer`: the adapter and its input contract must be explicit, not inferred from a dependency. Many rows name new tests without claiming their test files.

**Fix:** label new declarations, identify the existing owner being changed, resolve shorthand paths before launch, and include every named proof's file in the claim. Use fully qualified proof names where no section establishes the omitted namespace. Do not treat the source-name inventory as evidence those APIs are installed.

## Coverage and proposed shortest route

The explicit dependency scan found no cycle among 281 recognized row ids and 320 resolved exact edges. Ranges, prose prerequisites, shared files and proposed library decisions are not fully represented by those edges; findings 8–10 are why that result is not a schedulability proof.

The revised plan correctly uses positive merge-request data rather than a new task kind, uses git's merge words, stores norm order in vector cells, keeps Caffeine as the eviction owner, and puts display cuts in the value printer. I found no row expressly authorizing clipping elsewhere. The general cache-weight problem above is independent of display clipping. The atom and vector sections are largely aligned with the rulings, but their large implementation packages still need the specified split. Renames should not pull runtime redesign or arbitrary new enums into what is advertised as a mechanical change.

The shortest defensible route is: finish the measured shared reach-cache and storage correctness work; deliver one actual fixture-provider call with fresh task/proof state; run the full relevant test selection automatically and show its current results; open/update the request and complete the required real merge path on an isolated competency target; satisfy the measured model-cost gate; then run T1 on DeepSeek and record the merge outcome. Keep required conflict/integrity checks in the merge; move the rest of the competency matrix, debug polish and unrelated sweeps after this first smoke. This is an ordering recommendation, not evidence that the missing APIs already work.

## Reproducible read-only evidence

The tool exposes `cluster` but no `root` parameter. `bin/seon status` identified `/Users/sean/src/seon` as the selected root. No uncommitted code was required or loaded.

Availability probe, after checking the namespace exists:

```clojure
(let [started (System/nanoTime)
      connection (seon.cluster.boot/connection "default")]
  {:connection? (some? connection)
   :loaded
   (into {}
         (map (fn [s]
                [s (boolean
                    (when-let [n (find-ns (symbol (namespace s)))]
                      (ns-resolve n (symbol (name s)))))]))
         '[seon.sci.batch/read-next seon.context/current-state
           seon.ai/messages seon.pass-probe/run-mcp seon.pass-probe/window
           datahike.norm.norm/ensure-norms!])
   :ms (/ (- (System/nanoTime) started) 1e6)})
```

Result `result/e481be4ab4a47`: connection true; `read-next` true; other listed Vars false; inner 0.098 ms, entrance 1 ms, no reported error. A preceding probe incorrectly used `find-var` on the unloaded `seon.pass-probe` namespace: `result/e2152f5e8291d`, 2 ms, `IllegalArgumentException`, “No such namespace: seon.pass-probe”. That was my probe error, not an application finding. The corrected probe above required no namespace load. The benchmark functions exist in `test/seon/pass_probe.clj`; they are not loaded on default.

The retained-size counterexample uses the printer's actual whole-value estimate:

```clojure
(mapv
 (fn [size]
   (let [value (byte-array size)
         start (System/nanoTime)
         projection
         (seon.render.value/prepare
          {:seon.render/value value
           :seon.render.value/root 'plan-review-astra3/value
           :seon.render/profile @#'seon.render.value/core-profile
           :seon.render.value/options {:seon.render.value/structural? true}})
         chars (:seon.render.value/whole-characters projection)]
     {:payload-bytes (alength value)
      :whole-characters chars
      :proposed-bytes
      (when chars (* 4 (seon.ai.tokens/estimate-of-characters chars)))
      :diagnostics (count (:seon.render.value/diagnostics projection))
      :ms (/ (- (System/nanoTime) start) 1e6)}))
 [1024 1048576])
```

Result `result/e6517b23797fa`, entrance 2 ms:

| Payload bytes | Whole characters | Proposed cache weight | Diagnostics | Printer ms |
|---:|---:|---:|---:|---:|
| 1,024 | 22 | 24 | 0 | 0.514375 |
| 1,048,576 | 22 | 24 | 0 | 0.174417 |

This proves a counterexample to the proposed weight, not a retained-heap measurement of an implemented SR-H cache. No default GC, schema publication, provider call or model benchmark was performed. Stored health measurements remain historical observations, not fresh performance proofs.

## Landing and verification

Only this review file is owned or changed; source/test line delta is zero. Findings persist here against their existing ledger rows. Other lanes' dirty cache/registry/agent and error/storage files were excluded from the review commit. Those are the foreign boundary; no foreign edit was reverted, loaded or repaired.

`clj-kondo --lint src script test --cache false --parallel` completed in 7,126 ms with **0 errors, 1,470 warnings** (exit 2 for warnings). This is not a zero-findings claim. I used the testing skill to select `seon.cache-test/the-byte-budget-evicts-and-status-reports-it` for the requested commit gate: it checks the existing cache's enforcement of supplied weights, which underlies finding 1. It does not prove the proposed weights or the review's conclusions. No application test reaches a Markdown-only change, and no new test was invented for it. The named cache Var has no pre-existing red recorded in the evidence read here; broad namespaces with known reds were not selected.

Commit through `bin/lane commit plan-review-astra3 -m "Review revised agent-loop plan and verify cost assumptions" --test seon.cache-test/the-byte-budget-evicts-and-status-reports-it docs/research/agent-platform/2026-10-08/review/plan-review-astra3.md`, never amend. Its terminal result supplies the actual gate outcome and commit id; no green outcome is presumed in this note.
