---
title: "Audit 2: finish the automatic merge, then the unattended chain"
type: research
status: complete; independent audit and proposed root handoff, not an approved implementation plan
basis:
  previous-audit: c2284b109
  initial-head: aea2a10527f75f374150936bd0fd6ab4d4ddc200
  analysis-head: 45bfab18e59526f229f84ed02ffd63eb9904b752
  reviewed-head: e1d2854cb2ca7fb7cb93eb58f1bd0640623927fe
  default-before-reset: b14dc2b3fd796eee50d85e6e8f2400b2d3ec4eca
  default-at-delivery: 7ef4967dbd305a793dee484c3a327915c39d577a
created: 2026-10-12
---

# Audit 2

This is an independent audit for root after compaction. I did not open the other audit; its commit summary surfaced incidentally in the final git refresh, after this audit was drafted, and is not used as evidence here. Evidence labels below distinguish **live** observations on default, **committed** code or recorded experiments, and **unverified** reports. A committed implementation is not evidence that default loaded it. Default was neither restarted nor tested by this auditor. Root reset it during drafting; the two live bases are explicitly separated below. Queries used MCP `eval_clj`, `mode: jvm`, `agent: root`, `cluster: default`, with an explicit connection. No model agents were launched.

The main authorities are [AGENTS.md](../../../../AGENTS.md), [the previous audit](fable-audit.md), [done-and-merge](hybrid-done-and-merge.md) §1/§4/§6, [test-results](hybrid-test-results.md) §7, and [immutable-env](hybrid-immutable-env.md). The owner's working handoff at `/Users/sean/.claude/projects/-Users-sean-src-seon/memory/project_handoff_2026_10_12_night.md` is corroborating context, not proof. Its “RUNNING” and “PENDING” sections are substantially superseded by its later updates and the commits below.

## 0. Five findings

**1. The system improved, but the missing link is still missing.** Experiments 2–4 produced two manual git merges, one merge request, and zero automatic code merges, with 8, 23 and 17 reported hand acts respectively. The work now has one `seon.merge/merge!`, but `seon.cluster.agent/next-actions` deliberately keeps requested code merges for root, and publication still needs a real checkout instead of the archive's `.`. The shortest credible path is shared test judgment → merge/publication → one ordinary automatic merge; successor and relaunch work follows. “Finish M3–M18” is too broad a milestone. Evidence: [chains 2](chain-experiment-2.md), [3](chain-experiment-3.md), [4](chain-experiment-4.md), `4825cac93`, `fec3d7737`, [M6](m6-notes.md), source at `45bfab18e`.

**2. The most dangerous unfinished work is trustworthy evidence.** `seon.test.runner/result-at-reach`, `seon.test/select` and `seon.context/step-status` still need the R4–R7 work that makes configuration, retained base results and red/unknown judgments agree. R8 must preserve root's merge observation after branch deletion. The last broad baseline available here is old; `red-triage.md` ranked already-fixed failures, and the central-round handoff has no committed result yet. Running a narrow proof is not the same as proving the interpretation and merge paths: chain 4's compiled proof passed before root supplied three additional corrections. Evidence: [test-results](hybrid-test-results.md), [red-owner prebrief](prebrief-red-owners.md), [chain 4](chain-experiment-4.md), `fec3d7737`.

**3. Repeated human orchestration, not inability to write a small function, dominated the paid experiments.** G6/G7/G8—relaunch, successor basis and graph start—recurred while task selection became harder. Chain 3 used a 185-line scanner; chain 4's fixture was already known to fail interpreted before its paid launch. Haiku's inventories helped, but root treated some static judgments as launch evidence. Keep Haiku gathering, Sonnet implementing specified slices, Opus owning lifecycle and evaluator semantics, Astra owning measured performance, and Fable synthesizing. Root must demonstrate the actual proof path before purchasing another experiment. Evidence: [candidates](chain-3-candidates.md), [queue](chain-queue.md), [chains 3](chain-experiment-3.md)/[4](chain-experiment-4.md), `seon.cluster.agent/fork-attempt!`, `seon.task/ready`, `seon.cluster.agent/plan`.

**4. Performance work became materially better, but operating cost has not followed it down.** R11 reduced `seon.test.runner/reach-digests` for one changed test from 407.64 ms/627.57 MB to 68.75 ms/106.80 MB, with a red-to-green end-to-end proof. Failed cost-gb candidates were correctly withheld. Yet operator logs after the previous audit's basis contain 210 timed checks totaling 239.8 aggregate minutes, and today's start-event count grew from 13 to 22. First whole-program reach still costs 1.79 s/4.98 GB on R11's measured path. Claims of a cheap request must distinguish unchanged reads, a changed definition with a reaching test, and first acquisition. Evidence: `6433c7593`, [cost-gb](cost-gb.md), [sentinel](sentinel-baseline.md), operator-log census in §5.

**5. Root's next context must stop manufacturing obsolete work.** `plan.md` still says R11 and E7 are unfinished, describes predecessor/octopus E6 despite the owner's successor-from-main decision, names completed lanes as running, and says main has no test runs. The final committed census at `e700c5ea4` is 802 task identities, 173 with tags, 629 without, and no dangling task tags—not 783/155/628/25. Filing every future phase before proving the next few has produced tasks whose advertised red tests do not exist. Correct the plan and the next executable tasks together; do not convert the entire unproven backlog into a prerequisite. Evidence: census §5; `seon.task/ready`, `seon.context/step-status`, [M4 prebrief](prebrief-m4.md), `6433c7593`, `bc347d583`, [done-and-merge](hybrid-done-and-merge.md).

## 1. Against the goal: what moved, and what root now holds

### The experiments, counted consistently enough to expose the trend

“Hand acts” below are each author's reported count, including configuration; they are not a normalized count of shell calls. Pre-launch work and grouped final actions vary. A manual git merge after an experiment is shown separately from whether its agent requested a merge. None of these proves an automatic merge.

| Experiment / actual running archive | Attempts / model calls | Prompt / cached prompt / completion tokens | Reported hand acts | Agent merge requests | Automatic code merges | Subsequent manual git merges |
|---|---:|---:|---:|---:|---:|---:|
| [1](chain-experiment-1.md), `2319152dc` | 4 / 48 | 383,447 / 252,160 / 8,513, summed from its attempt table | 5; pre-launch instructions copy excluded | 0 | 0 | 0 |
| [2](chain-experiment-2.md), `c792b6aaf` | 7 / 73 | 837,630 / 610,816 / 22,832 | 8 | 1 | 0 | 1, `4825cac93` |
| [3](chain-experiment-3.md), `98720b4eb` | 8 / 105 | 1,307,921 / 950,784 / 65,455 | 23 | 0 | 0 | 0 |
| [4](chain-experiment-4.md), `46a1c4f06` | 5 / 69 | 884,188 / 661,120 / 18,013 | 17, or 16 excluding configuration | 0 | 0 | 1, `fec3d7737` |

The last three total 247 calls, 3,029,739 prompt tokens, 2,222,720 cached prompt tokens and 106,300 completion tokens, for 48 reported hand acts and two manual changes. Cached prompt tokens are a subset, not extra tokens. Different tasks and fixtures prevent a causal model-efficiency comparison. The handoff calls chain 4 `b14dc2b3f`; that is its report/default-restart commit, not the experiment's executing archive. Chain 3 used 1.56 times chain 2's prompt tokens and 2.87 times its completion tokens; an unqualified “three times the tokens” is misleading.

The afternoon audit's three historical manual merges become **five recorded manual merges overall**, adding these two. This counts git artifacts, not `:seon.task/outcome` in the current store. The live store has one `:closed` outcome and no returned `:merged` outcome in this audit's pre-reset census; that does not erase historical manual git merges.

Real progress since `c2284b109`:

- `seon.sci.eval` private-Var acquisition was repaired (`7fea1f2e7`), bounded refusal evidence improved (`9e23a93b7`), and `a247a976d`/`02ebec38a` demonstrated actual branch-evidence commits. This establishes that the route can work; it does not establish universal branch acquisition. R11 subsequently recorded acquisition signature `500ddee…` on its own branch.
- `seon.context/step-observation` judges all-known-green proofs at the fork point (M1, `346645d56`); `seon.merge/squash-merge` merges disjoint set additions (M2, `3eca1a962`); `seon.merge/merge-tx` advances the base inside the writer (M3, `767551c7b`); lifecycle derives actions and returns failures at the Flow boundary (M5, `1b09d8043`, `c74ef3437`); M6 removes the separate merge engines (`3da1f6449`). These are necessary mechanisms, not the end-to-end milestone.
- `seon.test/select` reuses red results at the same key (R1, `50ca41030`); `seon.test.runner` records executed declarations outside static reach (R2, `474174b52`); R11 removes repeated reach work (`6433c7593`). G1/G3/G4/G5/G9 repairs improved task admission, calling examples, send-back reasons, instructions and reaching-test discovery; exact commits appear in the handoff table below.
- Chain 2 corrected `seon.render.value/classify`. Chain 4 contributed `seon.render.web/stop!`, but root supplied `finally` shutdown, actual termination waiting and the executor schema member. Its narrow green did not prove the final change unaided.

What only looked like completion: a filed phase with a nonexistent proof; a passing compiled test presented as an interpreted task's evidence; a cache-charge reduction presented as reclaimed heap; an old red list called current; or a commit count presented as autonomous output. Each occurred in the evidence below.

### Root's handoff: finished, pending, and actually claimed

This table supersedes the old handoff's initial running list. “Done” means the named slice has a commit and stated evidence, not that its entire parent design is complete.

**Start here after compaction.** Root has already reset default onto `7ef4967db`; current PID 16255, start `2026-10-12T10:56:24.561Z`. The newest reviewed HEAD is `e1d2854cb`. Read the existing central-round result when available, publish/reload the completed script-contract fix, and resolve the M9-before-M8 activation dependency. Do not repeat the reset, rerun a paid chain on unchanged G6/G7/G8, apply cost-gb patches, revive E6, or trust the old “RUNNING” list. The source semantics discussed at `45bfab18e` are unchanged by the intervening documentation and filing commits.

| Line of work | State and evidence at the review basis | Root's next action / owner symbols |
|---|---|---|
| branch-repl | Finished slice: `7fea1f2e7`, `9e23a93b7`, `a247a976d`, `02ebec38a`; later branch-acquisition defects remain | Use `seon.cluster.branch-evidence/branch-evidence` for eligible ordinary changes; reproduce each remaining `seon.sci.eval/acquisition-refusal` by its exact branch/basis |
| claim / restart-batch operator | `69e485980` permits an untracked unclaimed file; `23bcc612e` requires an explicit restart batch for dependency inputs | Keep `bin/lane` ownership; this did not itself reduce restart frequency |
| chains 2 and 3 | Finished experiments; chain 2 merged manually, chain 3 no request | Do not rerun the scanner to rediscover `seon.task/ready` and `seon.cluster.agent/fork-attempt!` defects |
| chain 4 / chain4-merge | Finished experiment and manual merge `fec3d7737`; now in reset default | `seon.render.web/stop!` still needs no repeated manual merge |
| web-check | Finished evidence `48bb5cfee`: 63 test vars, parent four red → change three red; 314 pass/6 fail assertions → 316 pass/4 fail | Remaining vars: `seon.render.web-test/a-reconnect-refuses-a-pass-derived-before-it-connected`, `seon.render.web-test/debug-algorithm-carries-the-render-evaluation-inputs`, `seon.render.web-test/declared-units-are-components-in-schema-order`. Claim still present; claim is not a running worker |
| M1/M2/M3 | Done at `346645d56`, `3eca1a962`, `767551c7b` | Do not revive the alleged Datahike join defect in `m3-notes.md`; see `e9bcf36d2`, `83acdfbf3`, `88e7651da` |
| M5 | Done at `1b09d8043`, `c74ef3437` | `seon.cluster.agent/lifecycle-facts`, `seon.cluster.agent/next-actions`, Flow's boundary; M10/M12/M13 remain |
| M6 | Done at `7c3077b9e`, `3da1f6449`, notes `59a1ffe1f`; final consolidation included by the later reset | Read `seon.merge/merge!` and [M6 notes](m6-notes.md) before M4/M7/M8/M9 |
| M4 | Earlier lane stopped before editing; latest prebrief `aea2a1052`; no named red deftest | Opus must finish the merge-branch composition and prove conflict resolution, not only conflict creation |
| R1/R2 | Done, `50ca41030`, `474174b52` | Continue with `seon.test/select`, `seon.test/admit-run`, `seon.test.runner/result-at-reach` |
| R3 | Replacement rule agreed, implementation parked (`51a5e159e`); sink misses the callable, proof remains a patch | Do not resurrect a general multimethod declaration design; priority depends on an observed ordinary task needing the case |
| cost-gb / recording candidates | Research finished; rejected candidates preserved (`73bd74de9`, [cost-gb](cost-gb.md)) | Do not apply its saved patches as accepted fixes; `seon.test.runner/reach-digests` subsequently changed in R11 |
| reach-incr / R11 | Done `6433c7593`, 20 tests/106 assertions green; included by the later reset | Measure the same changed-input probe through the installed path; sentinel alone does not exercise it |
| root-drift / step-skip | Done `b7be69770`, `360e1f2ea`; in default | `seon.test/request-changes`, `seon.turn/test-step`, `seon.turn/step-due?`; prove a definition with an actual reaching test, not only a read |
| unmap / unalias | Done `60fd947e7`, `ad44a3ba7`; in default | `my.program/retract-operation!`, `my.program/ns-unalias!`; remaining `my.program/breaks` graph cost is separate |
| cache weights / loaded digests | Done `5218b097e`, `e70ed838d`, `a1ad947be`, `74b86a38c`; in default | `seon.cache/status`, `seon.cache/present`, `seon.sci.eval/loaded-digests`; don't rank these old defects again |
| E7 / contracts-pcw | E7 done `bc347d583`, included by reset; contract repair done `e1d2854cb`, claim released | Live program-context charge 29,775,704 bytes. Publish/reload the newer script contracts and verify health; charge is not retained heap |
| fresh-boot | Finished isolated evidence `45bfab18e`, tested `aea2a1052`; reported failure did not reproduce | No blanket boot blocker established; preserve and inspect the exact failing acquisition basis if it recurs |
| central round | Handoff says Sonnet running; **unverified execution state**; `central-round-head.md` absent from disk and git at inspection | Root obtains its result before calling any broad red count current; do not duplicate its JVM |
| filing | Finished `4a395ee74`, claim released: three web reds, eight turn reds, reused-red reply and first-facts-merge proofs/tasks | New red proofs now exist for `my.test/check` reuse and `my.root/merge!` first-call cost. They are newer than reset default; preserve named evidence |
| post-reset sentinel | Finished `e700c5ea4`, measured default `7ef4967db` | `seon.sentinel/run`: warm 51.9 vs 38.6 ms, fresh 247.4 vs 213.9 ms, definition 460.5 vs 378.6 ms; investigate under the same quiet conditions before attributing the slowdown |
| surveys | Six committed inventories, `efff77b94`, `5104f9cb1`, `c78274fe1`, `12e076842`, `7ef4967db`, `e76d44003` | Read as hypotheses with stated bases. The doc-drift survey demonstrably misidentifies existing definitions as absent; details in §5. No new automatic assignment from a static count |
| audits and older claims | Final claims observed: audit2-astra, root-report, astra-one-system, astra-review-merge-base, web-check | Peer audit is committed; file not read. Older documentation claims prove ownership only; worker liveness is unverified. Root alone reconciles stale claims |
| M7–M18 / R4–R10,R12 / E3,E5 | Planned or filed, not completed in reviewed commits | §6 gives dependency order. Filing is not evidence of an executable red proof |

Root reset default onto `7ef4967db` during drafting: R11, the corrected web executor change, final M6 and E7 are now in its committed archive. **Do not repeat the pending restart from the old handoff.** The later filing commit `4a395ee74` and script-contract fix `e1d2854cb` are not in that archive; verify their publication/reload separately. The zero open errors after reset does not prove the old errors' causes were fixed; their evidence is preserved in [faults-before-reset.edn](db-clean/faults-before-reset.edn).

### Shortest credible milestones

For **one automatic ordinary task**, choose an existing pure owner function with a reproduced red proof that actually runs on an agent's SCI branch. Complete the evidence dependencies R4/R5/R6/R7, the M7 judgment, and R8's durable root observation; finish M4's conflicts without losing either side; make M9 publication recoverable and give it the actual checkout; then M8 removes the explicit request/manual hold. M9 must precede enabling M8 or ship in the same proven activation slice. The current task text says M9 after M8 while [M6 notes](m6-notes.md) says publication is why automation is held: resolve that dependency before assigning either.

The proof is the entire path: assignment → branch edit → current green → root merge → publication/git commit → durable evidence after branch deletion, with no hand action between assignment and committed result. A fixture model can prove orchestration first; one deliberately paid ordinary task then proves the product loop. A fixture green alone cannot count as the requested first ordinary task.

For **an unattended chain**, add M10 (successors fork main only after needs merge), M12 (terminal outcomes/relaunch), M13 (one starter for all entrants), and the current-state/proposal behavior M14/M15 needed for agents to repair their own work. M11 is required before simultaneous sibling changes are advertised as unattended. M16 and M17 become prerequisites when the demonstrated chain uses messaging or retained bases must be collected; they are not excuses to delay the first serial ordinary merge. The chain proof must cover a send-back/relaunch as well as a happy successor. Count operator interventions explicitly. E6's predecessor/octopus design is superseded, not an alternate prerequisite.

## 2. How we work: retain the roles, change the evidence and scheduling

### Did the afternoon recommendations work?

| Previous recommendation | What happened | Judgment |
|---|---|---|
| Fix the lane REPL first | `seon.sci.eval` repair and two branch-evidence commits; later acquisition remained blocked for R11 | Real improvement, incomplete coverage. No evidence supports treating every source change as eligible now |
| Own the red baseline | `red-triage.md` and later correction; central round not yet available here | Not achieved at this basis; `seon.test.runner` evidence still has to be reconciled |
| Require end-to-end performance | cost-gb withheld failing candidates; R11 demonstrated target reductions | Worked. Keep rejected measurements as evidence, not implementation debt |
| Batch restarts | Operator rule added; start-event count 13 → 22 | Rule installed; desired operational reduction not demonstrated |
| Ask design questions early, narrow-check once, prove before filing far ahead | Test-results required two checks and owner revisions; many future proofs still nonexistent | Partially followed. Caller inventories helped; eager task filing continued |
| Keep a goal probe running | Chains 2–4 ran | Followed too literally when unchanged lifecycle defects and unsuitable task paths were already known |
| Fix new-file claiming | `69e485980` | Done |
| Prune tasks / shrink oversized owners | Small retirements; final census still 629 untagged; source larger | Some hygiene, not a solved backlog. Do not start a mechanical namespace split solely to create parallelism |

From `c2284b109` at 13:14:44 to `45bfab18e` at 17:47:18 (+0700), git records **107 commits**, **43 touching no program-input directory** (`src`, `test`, `resources`, `reference-code`, `bin`, `script`). Co-author trailers: Sonnet 49, Opus 33, Haiku 17, Fable 2; six have none of those trailers. These are artifact counts, not throughput or token efficiency. No complete per-lane active-time or token ledger was available in the authorized evidence, so an honest “best per hour/token” ranking cannot be computed. Two manual changes in this 4.54-hour interval is about 0.44 manual changes/hour; automatic output remains zero. Neither figure fairly compares models performing different work.

The concrete results support the roles more strongly than those counts. Sonnet delivered narrow changes such as `my.program/ns-unalias!` and task hygiene. Opus repaired the hard `seon.turn/test-step` selection and M5/M6 semantics. Astra's R11 achieved the measured large gain after a smaller cost-gb attempt failed its target. Haiku found the fixture handle problem and deletion hazards, but its task queue and stale red triage also misdirected work. Fable's synthesis helped settle the one merge model and reuse semantics, but another model's review cannot replace the implementer's reaching proof. Sources: commits and notes in §1; [prebrief-m5-m7](prebrief-m5-m7.md), [prebrief-red-owners](prebrief-red-owners.md).

### Was the design round worth it?

For the shared semantics, **yes**, but not in its repeated-document form. Done-and-merge converged on one assignment identity, one merge base, and root reacting to branch facts. Test-results converged on reuse at the actual key, evaluator separation, unknown as unknown, and root retaining its own observation. Those decisions remove mechanisms. However, the earlier audit counted four done-and-merge checks; M4 later stopped on a missing merge branch, M13 still has an entrant design gap, and test-results needed a second check to repair seven mechanical failures (`def69a2cc`, `87671e342`). These are evidence that prose consistency was mistaken for a working composition.

Use the full dig → independent plans → hybrid → narrow check for uncertain cross-owner semantics. Give both planners the exact known counterexamples and check questions at the beginning. Require a small REPL proof of the disputed composition before expanding phase tasks. For an agreed local fix, use one short card and a reproduced regression. Do not run a fresh multi-model design round for each already-decided M or R phase. Haiku supplies evidence and inventories; Opus/Astra judge it. A prebrief suggesting duplicate conflict representations until a later commit is not authority to violate the one-owner design: [prebrief-m4](prebrief-m4.md) §5 explicitly proposes that staging and even calls the merge-branch step optional, although M4's promised behavior requires it.

### Concrete scheduling now

Run two implementation responsibilities while the critical files permit it: Opus on merge/lifecycle, Sonnet on specified test-evidence changes, with Opus handling any runner semantic gap. Astra takes one measured performance problem only when its files are disjoint from that evidence work. Haiku remains available for the next prebrief and reproducibility check; Fable performs this audit's hybrid. This is an initial assignment shape, **not a permanent two-lane cap**: AGENTS.md permits all ready disjoint work that root can prove centrally. `seon.turn`, `seon.cluster.agent`, `seon.test.runner` and their tests make many apparent parallel phases sequential.

The readiness evidence for an assignment is its current red proof, exact target archive, complete caller conversion and an available owner. “Test absent” is not “test red.” Before another paid chain, establish that the exact proof runs interpreted on its intended branch and that the known lifecycle blocker was actually changed. This is how root chooses work; it does not call for a new runtime guard or launch service. Keep one goal experiment owned and ready to run when its blocker changes, instead of continuously paying models to rediscover an unchanged blocker.

## 3. Is plan.md §0 current, correct and focused?

No. The following is the correction inventory for §0 at `45bfab18e`. Historical measurements can remain in dated evidence; they should not be presented as the active queue.

| Existing claim or direction | Correct statement / evidence / concerned owner |
|---|---|
| Heading says verified at `015673f47`/`786915ca3` | It mixes later M6 with much older measurements. State both source and loaded archive explicitly; `seon.cluster.boot/connection`, `seon.sci.eval/loaded-commit-id` |
| Three manual merges; first chain ran; later “run once” | Five recorded manual merges overall; four experiments; §1 gives each archive and counts |
| 783 tasks / 94 files / 155 tagged / 180 tags / 25 missing / 628 untagged | At `45bfab18e`: 796 / 95 / 166 / 166 / 0 / 630; at final `e700c5ea4`: 802 / 95 / 173 / 173 / 0 / 629. Static tags are not executed red proofs. Live `seon.task/id` was 825 before reset, 796 afterward |
| `my.task/refine!` and `my.task/create!` absent | Still absent in inspected public namespace; retain that fact. `seon.task/create!` existing is not a public-tool implementation. M14's proposed proof must not silently depend on the missing tool |
| M1/M2/M3/M5/M6 done | Correct for those slices. Do not infer M4 or automatic publication from M6; `seon.merge/merge!` still has the transitional request distinction |
| M4 and M7–M18 “filed, not started” | No completed implementations shown, but “filed” overstates readiness: M4's named test is absent, M13 has an unresolved entrant composition, M14's tool assumption is false; prebriefs name the gaps |
| R4–R12 not started | R11 done `6433c7593`; R3 replacement still lacks implementation. R4/R5/R6/R7/R8 must precede claiming trustworthy automatic merge evidence |
| E3 not started | Immutable-env marks partial; `seon.sci.eval/copy-base-ctx` still forks because mutable-base consumers remain. Describe the remaining guarantee, not “nothing implemented” |
| E4 done with open proof | Mechanism done, proof of a private atom surviving another agent's program change and final proc installation are still open in the owning design. Qualify completeness |
| E5 not started | Remaining ownership/transfer work is real: `seon.sci.eval/acquire!`, `seon.sci.eval/transfer-evaluated-roots!` and proc context installation; do E5 before E3's final guarantee |
| E6 partial, octopus fork missing | Superseded by owner's successor-from-main decision, M10. Do not implement the predecessor/octopus target in `seon.cluster.agent/fork-attempt!` |
| E7 partial; measurement script nonexistent | Done `bc347d583`; `seon.program-context-weight/report`, `seon.sci.eval/context-weight` and `seon.sci.eval/overlay-weight` changed. Four script contracts were corrected in `e1d2854cb` after fresh-boot; installed verification remains |
| Cost ranking says two full context regenerations and unchanged-request test work remain | E4 and `360e1f2ea` changed these. The `015673f47` table is a dated baseline, not “ms now”; `seon.turn/test-step` must be remeasured with reaching tests |
| Program-context constant unmeasured; key charges/loaded-digests/presence misses are pending | E7, `5218b097e`, `e70ed838d`, `a1ad947be`, `74b86a38c` addressed these. Distinguish default's old E7 charges from HEAD's calibration |
| Missing branch connection causes a producer NPE | Retired `913e6a4f4`: `seon.db/branch-connection` correctly returns nil under its maybe contract; the probe dereferenced it |
| Integer-as-reference rendering is unfiled | Fixed `4825cac93`, `seon.render.value/classify` |
| First-agent acquisition is untouched | Admission duplication removed `483b3e9b6`; 209.37 → 156.325 ms quiet medians. A second branch remains until M13/M17; don't remove its base without converting readers |
| At most two build lanes; Sol 6.1 default implementer | AGENTS.md `4826578fa`: Sonnet specified work; disjoint lanes limited by central verification capacity, not that old fixed cap |
| branch-repl, chain-2, cost-gb, dig-test-results, lane-batch “running now” | Finished slices/research; §1 replaces this list. Root must not resume historical sessions from this sentence |
| First automatic merge as soon as M6 and M8 allow | Omits R5→R6→R7→M7, R4 key correctness, R8 durable observation and M9 publication/checkout; M4's conflict semantics also unfinished |
| Finish all M3–M18 before the goal / split large namespaces first | Focus first ordinary merge; defer unrelated mechanisms. Split only by a proved ownership boundary when it removes work, not to manufacture concurrent file claims |
| Main has no test runs | Before reset, live `seon.test.run/id` count was 25. After reset the aggregate returns nil. Neither is a complete baseline, and owner expressly rejected central bulk runs on main |
| Cost-gb remains the active performance lane | Its candidates were rejected and preserved; R11 later solved a large part of reach work. `seon.test.runner/reach-digests` first whole-program work remains expensive |
| Immutable E3,E5–E7 all queued | E7 done; E6 obsolete; E5→E3 and E4's remaining proof are the real outstanding work |
| Separate root merge task; dial off until owner writes automatic criteria (repeated in older direction/standing rules/final subsection) | Superseded by done-and-merge §4: assignment is request, everything at step end, automatic when the agreed judgment permits; root resolves unmerged coordinates. Do not ask the owner to decide this again |
| Conflicts keep main and become prose problem tasks | Describes the existing `seon.merge/conflict-task!`, not M4's agreed target: keep base/ours/theirs on the merge branch |
| Two named clean-HEAD reds are the chores baseline | Historical only; neither the old two nor the broad round's 229+117 is a fresh HEAD census. Use the pending central result and exact-key evidence |
| Guessed names, concise cost line, interpreted census and H/AW table delegation describe current guarantees | These remain directions or partially implemented lines, not verified completed guarantees. `seon.error`, `seon.context/current-state`, `seon.test/select` need named current proofs; don't relaunch whole old H/AW sequences |

Correcting numbers alone is insufficient. The owning hybrids also disagree internally: done-and-merge's opening “what exists” still describes two engines and its M6 status text lags its front matter; immutable-env retains predecessor semantics; test-results' broad “every proof red” heading does not establish that a newly named deftest exists. Update the **owning sections**, not a parallel ruling appendix. [M4](prebrief-m4.md), [M12–M14](prebrief-m12-m14.md) and [R4–R12](prebrief-r4-r12.md) are useful inventories, not overrides of the hybrids or the owner.

## 4. Problems ranked by their effect on the goal

| Priority | Problem and evidence | Owner to change; required proof |
|---|---|---|
| 1 | Completion, merge and durable publication still differ. `seon.cluster.agent/next-actions` explicitly excludes requested merges; `seon.merge/merge!` writes back after accepting and returns `:seon.source/accepted` on later failure. M6 notes name `.` as the archive checkout problem | Opus: `seon.context/step-status`, `seon.cluster.source/accept-merge!`, `seon.merge/merge!`, proposed `seon.merge/publish!`. M7/M8/M9 end-to-end proof including interruption after acceptance and exactly one git publication |
| 2 | Evidence can be missing or keyed incorrectly. R4 config missing, R5 base result not retained, R6/R7 competing judgments, R8 evidence disappears with branch. Chain 3 H2 reused a red after the tags it read changed | Test owner: `seon.test/select`, `seon.test/admit-run`, `seon.test.runner/result-at-reach`, `seon.context/step-status`; prove each existing counterexample. H2 needs a deterministic fixture correction, not an assumption that R12 changes the key |
| 3 | Successor/relaunch/start requires root again. Pre-reset `seon.cluster.agent/fork-attempt!` had six recorded occurrences of missing predecessor branch; G6/G8 recur in chains 3 and 4 | Opus: M10/M12/M13 at `seon.task/ready`, `seon.cluster.agent/fork-attempt!`, `seon.cluster.agent/plan`, `seon.cluster.host/start!`; prove successor after predecessor branch deletion, relaunch, and every existing entrant |
| 4 | Ordinary edits do not have a reliable schema/admission path. Chain 4 J1 admitted a bare-symbol schema then reads failed; J3 rejects a qualified contract property digest; J4 cannot resolve a branch predicate; J5 schema registration rejects a function contract admitted moments before | Opus/schema owner: `seon.schema/register!`, `seon.schema/admit-declarations!`, `seon.db` declaration digest producer, `seon.sci.eval/database-effective-config`. Use the exact chain regressions; no new exemption. J1's four-minute silent stop needs delivery evidence too |
| 5 | A red baseline and a wrong fixture can send a lane to the wrong library. M3's supposed Datahike join bug was a digit-leading random task handle; stale compiled test-support hid the fix (`e9bcf36d2`, `83acdfbf3`, `88e7651da`). G1 passed alone 9/9 at suspect commits (`9e622aa99`), so M3 causation was not established | Sonnet reproduces, Opus judges: `seon.test-support/task-row`, operator program-class key, `seon.test-support/with-database`. Store exact archive, evaluator, run/key and failure; do not “fix Datahike” from the stale M3 note |
| 6 | Remaining performance is first derivation and actual change/test work. R11 first whole request 1788.34 ms/4,976,979,136 allocated bytes; M6 first facts merge 417 ms/~1 GB vs warm 6.4 ms/~1 MB; first code merge 1275 ms/~2.3 GB vs warm 548 ms/~470 MB | Astra on `seon.test.runner/reach-digests`, `seon.merge/merge!`, their declaration/projection reads. Attribute common work before another cache; same end-to-end parent/change probe on a quiet JVM |
| 7 | The product's context is still misleading or silent. Chain 3 instructions were cut from 2,473 to 1,024 characters in its source report; chain 4 unresolved *new* schema keywords induced unwanted registration. `my.test/check` can answer bare false; pre-reset error `7ea039…` passes nil as diagnostic message | `seon.context/current-state`, `seon.ref` reference resolution, value printer, `my.test/check`, `seon.test/run`, `seon.error.refusal/diagnostic`. Prove data delivered to the agent; do not solve by adding instruction copies or longer retry loops |
| 8 | Planning backlog is not executable work. Final census: 629 without tags. `task-triage-1.md` classified only 36 of 150; 114 unresolved. Its “no task resource commits today” is false (46 such commits just in the audited interval), and its “no relaunch attribute” is false (`:seon.task.attempt/relaunch` exists; the missing reader remains) | Root task owner with Haiku evidence/Sonnet cleanup: `seon.task` ingestion, `seon.task/ready`, `seon.context/proofs`. Reproduce and prove the next few goal-critical tasks, retire obsolete tasks in their fixing/deleting commit |

The fences already identified deserve deletion at their producer: `--accept-red-before` is replaced by same-key comparison in R9; the per-request test narrowing without a production caller belongs to `seon.test` cleanup; the test fixture collision refusal is not isolation. M6's requested-merge hold is a visible transitional manual dependency, not the promised finished orchestration. Do not remove it before publication owns the right checkout. Do not add retries for missing predecessor branches: M10 removes the dependency on those branches.

Cache evidence does **not** support deleting every low-hit kind. Live `seon.cache/status` on `b14dc2b3f` reports 401,652,984/512,000,000 weighted bytes. Its program-context kind charges 165,545,216 bytes with 4 hits/5 misses and 4,948 ms cumulative load time. E7's 31 MB is a recalculation of held values, not a live post-restart cache reading or a measured 134 MB heap reclamation. Conversely `:seon.task/file-declarations` has 92 hits/677 misses but only 152 ms cumulative loading: low hit rate alone does not make it the next performance priority. `:seon.health/derived` has 19 hits/47 misses and 8,772 ms cumulative loading; that warrants a scoped profile, not allocation of shared counters to a single request. `seon.cache` is the owner of all these observations.

## 5. Risks, evidence limits, and numbers root may safely reuse

### What could waste the next day

1. **Another paid experiment before its known blocker changes.** Chains 3/4 demonstrate this at `seon.task/ready`, `seon.cluster.agent/plan` and `seon.cluster.agent/fork-attempt!`. Run a small interpreted eligibility/proof check first; do not pick “small source” as a substitute for a working test path.
2. **Treating a prebrief as a design decision.** M4's proposed duplicate representations and M13's missing MCP/schedule entrants need an Opus composition, not a mechanical deletion. Missing `my.task/refine!` cannot be waved away in M14's proof.
3. **Testing a different program from the one named.** The class-cache defect, old M3 diagnosis and E7's unidentified root all show why archive, PID/start instant and branch basis belong to evidence. A disappearing shell-owned JVM is not evidence that boot refuses every form ([fresh-boot](fresh-boot.md)).
4. **Equating red with regression, or known red with permission.** The historical central baseline, `red-triage.md`, G1's isolation result and evaluator differences forbid either shortcut. The responsible owners are `seon.test.runner` and `seon.context/step-status`; R9 must remove manual accepted-red lists through the common judgment.
5. **Restarting for each small commit and redoing broad checks in every lane.** The operator event counts below show the cost. Root batches ready code, retains a usable REPL and runs broad tiers centrally. Ordinary branch-evidence work and documentation should not launch check JVMs.
6. **Optimizing a proxy or charging shared work twice.** R11 distinguishes calling-thread allocation from retained heap and JVM-wide windows. E7 distinguishes incremental context ownership. Keep those distinctions when choosing the next target; do not revive the rejected storage encoding changes.
7. **Filing all future phases instead of proving today's next phase.** An absent proof and an unresolved design are not runnable assignments. Tagging an already-green test does not turn task backlog into progress. The 10,000-line aspiration also remains far away: committed `.clj`/`.cljc` line census at `45bfab18e` is 95,874 in 111 source files and 106,626 in 415 test files; previous audit reported 95,285/105,806. These are physical lines, not complexity measures; deletion should follow ownership and library reuse, not a quota.

### Live observations made by this audit

The following initial values are **pre-reset default `b14dc2b3f`**, PID 62950, start instant `2026-10-12T10:10:08.117Z`, source directory `/Users/sean/src/seon/data/source/b14dc2b3fd796eee50d85e6e8f2400b2d3ec4eca`. `bin/seon status` and MCP `runtime_status` reported `:faulted`, nine open Seon error signatures, 18 agent-error signatures, 71 errored evaluations in its legacy health category, 21 unknown tests, 22 unowned namespaces, four hot-untested warnings and one cache warning. Ready time was 17,460 ms. These counts are not a fresh suite run.

The explicit-database census returned 825 tasks, 112 agents, 2,911 tests, 25 test runs and 20 assignments; outcomes `[:closed 1]`. Query request: 47.5 ms, 8.3 MB calling allocation, eight reads, one recording transaction, result `ec542623302e7`. Forms were `seon.db/q` counts of `:seon.task/id`, `:seon.agent/id`, `:seon.test/sym`, `:seon.test.run/id`, `:seon.task/assignment`, and a grouped count by `:seon.task/outcome`, all against `@(seon.cluster.boot/connection "default")`. Operator writes and task ingestion explain why this is not the file census.

`seon.problems/open-faults` returned these operations and occurrence counts (compact query result `e50824a44ee47`, 102.8 ms request, 103.8 MB calling allocation, 44 reads, one recording transaction):

| Signature prefix | Operation | Occurrences | Observed content; interpretation limit |
|---|---|---:|---|
| `500ddee` | `seon.sci.eval/acquisition-refusal` | 20 | `seon.sci.eval/database-effective-config` was given a database without its cluster configuration. Earlier full observation dates these immediately after the last restart; this is retained evidence, not proof every new acquisition still fails |
| `db142341` | `seon.cluster.agent/fork-attempt!` | 6 | Predecessor branch gone; direct evidence for M10 |
| `759d40be` | `seon.cluster.registry/retire-branch!` | 3 | Connection released; no root cause isolated by this audit |
| `75d68dbb` | `seon.db/transact!` | 2 | Unowned-component validation refusal; not independently reproduced here |
| `0c04f819` | `seon.test/run` | 1 | Overlay refuses `seon.render.web/stop!`'s nilable return; matches chain 4's admission-path problem |
| `117f391d` | `my.agent/retire-branch!` | 1 | Caller passed keyword instead of map; a caller mistake still appears among Seon's failures |
| `7c40461f` | `seon.db/pull` | 1 | Malli invalid schema; cause not independently isolated here |
| `7ea039b9` | `seon.error.refusal/diagnostic` | 1 | `seon.test/run` supplied nil `:seon.error/message` |
| `bd510b99` | `seon.sci.eval/install-row!` | 1 | Unresolved `profile/measure`; existing namespace-alias task, not a new reason to disable acquisition contracts |

The open-error read itself allocates about 104 MB for nine summaries; this is a measured observation, not a root-cause attribution. The earlier larger read cost 101.4 ms/89.9 MB. It is below a second but deserves scoped investigation at `seon.problems/open-faults` if this is an ordinary context path. No task was written by this read-only audit; root should search for an existing task before filing.

**Final live refresh, after root's reset.** MCP `runtime_status`, `bin/seon status` and JVM query `e301b0595710c` identify `/Users/sean/src/seon/data/source/7ef4967dbd305a793dee484c3a327915c39d577a`, PID 16255/start `2026-10-12T10:56:24.561Z`. Health is degraded, not faulted: four malformed script contracts, one errored evaluation, and three hot-untested warnings at the last operator read. Ready was 1,350 ms. The query returns 796 tasks, 2,912 tests, nil from the aggregate run-count query, zero `seon.problems/open-faults`, total cache weight 70,174,384 bytes and program-context weight 29,775,704 bytes (16 hits/6 misses, zero evictions, cumulative load 3,104 ms). Query cost: 60.6 ms/12.1 MB calling allocation, seven reads, one recording transaction. A later status read weighed 72,470,836 bytes: a changing cache is not a contradictory measurement.

Root preserved the old evidence in [db-clean/faults-before-reset.edn](db-clean/faults-before-reset.edn), `16ddd77d7`, before the reset. Its disappearance from the new live store proves neither M10 nor schema acquisition was fixed. The old 825-task/25-run population is no longer the live population. A task/test record census and an executed, keyed baseline remain different things.

[Sentinel-after-reset](sentinel-after-reset.md), `e700c5ea4`, reports `seon.sentinel/run` on `7ef4967db`, although the driver printed a newer checkout HEAD. Warm median increased 38.6→51.9 ms, fresh 213.9→247.4 ms and definition 378.6→460.5 ms; calls and reads fell, and definition allocation fell 117.9→97.1 MB. The report's “no regressions at 1.5x” is a threshold statement, not “no slowdown”: all five warm samples exceed the earlier maximum. Cause is unverified, so compare quiet equivalent conditions before assigning a fix. The outer driver was 2,106.8 ms, whereas inner operations were below half a second; those scopes must not be combined. The sentinel still does not prove R11's changed-definition-with-reaching-test path on default.

**New inventories are not automatically trustworthy.** [Survey doc-drift](survey/doc-drift.md), `e76d44003`, says `seon.schema/admit-declarations!` and four plan proof names do not exist. They do exist in the reviewed source: `seon.schema/admit-declarations!`, `seon.context-test/broken-tests-show-with-their-failures`, `seon.sci.eval-test/no-agent-work-writes-a-program-context`, `seon.sci.eval-test/equal-programs-on-two-branches-share-one-program-context`, and `seon.db-test/equal-declaration-content-shares-one-projection-across-values`. The test names follow multiline `deftest` metadata, which a single-line definition grep misses. This audit checked their definitions; it does not claim it ran them. Do not file those five “absent definition” defects. The survey's aggregate unresolved-reference count is therefore not a verified defect count. Its other claims require individual checking. Similarly the [test-risk survey](survey/test-risk.md) explicitly uses only 550 partial results from `5a715c9b9`; its 76 stored reds are not a current complete HEAD baseline. These surveys corroborate the need for Haiku evidence followed by judgment, not a new priority queue from counts alone.

Filing's new `my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts` (`4a395ee74`) records 692 ms/~1.06 GB for its first merge and asserts cost, but its body does not assert the measured merge succeeded. Before accepting a performance implementation against it, add or retain a reaching assertion over the successful merge's data; a fast refusal must not satisfy the cost proof. This is a static proof-gap finding, not a reproduced production refusal. The task's 100 ms/64 MB target is a proposed proof bound, not a measured achievable cost yet.

**Fresh boot is not established as broken at HEAD.** E7's handoff reported ten old `seon.cluster.squash` declarations refusing and an unknown loaded commit. New committed evidence [fresh-boot](fresh-boot.md), `45bfab18e`, ran `aea2a1052` through two isolated rebuilds (ready 47.3/51.3 s) and three restarts (11.9/3.6/3.7 s). Arithmetic, new-agent evaluation, a definition and loaded-commit lookup succeeded; open faults were empty. It found four malformed private script contracts (`seon.program-context-weight/jol-vm`, `seon.program-context-weight/jvm-retained`, `seon.program-context-weight/reference-offsets`, `seon.program-context-weight/walk!`) and a cache warning. The report did not preserve or identify E7's failing database. Its hypothesis of an older archive or premature probe is **unverified**; default's old namespace evidence could also involve retained declarations. Do not erase the recorded failure, and do not keep calling it a reproduced HEAD boot blocker.

### Reproducible census and operational accounting

The file census reads **git objects at `45bfab18e`**, not another lane's dirty files. Read every `resources/seon/tasks/*.edn` with `clojure.edn/read-string`; derive each task symbol from filename namespace and `:seon.task/name`. Parse all tracked `test/**/*.clj` and `.cljc` with `edamame.core/parse-string-all`, `:all true`, auto-resolved aliases and preserved data readers; collect metadata `:seon/tasks`, unwrapping `(quote symbol)`. Unwrap quotes around either the metadata collection or an individual symbol; failing to unwrap a quoted vector falsely creates two dangling “tags” (`quote` and the vector). There were zero parse errors. At `45bfab18e`: 796 task identities, 166 tagged identities, all matching, 630 untagged, none dangling. Repeating at `e700c5ea4` after filing gives 802 identities, 173 tagged and matching, 629 untagged, none dangling. This is a static inventory, not a claim that all 173 proofs are red, meaningful, or executable. Raw EDN counts and default's generated tasks are different populations.

The pre-reset operator census reads `data/clusters/default/logs/operator-check-20261012T*.edn`. At that collection: 409 events, 399 with `:seon.operator/total-ms`, sum 28,283,858 ms (471.4 aggregate minutes). Restricting filenames after local `2026-10-12 13:14:44`, the timestamp of `c2284b109`, gives 212 events, 210 timed, sum 14,387,362 ms (**239.8 minutes**). The number is the sum of each event's own `:seon.operator/phases` total, or the same nested under `:seon.error/offending`; it is neither CPU time nor elapsed wall time, and concurrent checks overlap. Nine more `operator-start-20261012T*.edn` files appear than the afternoon audit's 13, for 22 total. A start event is not proof of a forced dependency restart; the cause of all nine was not independently classified. The latest start file at that collection was `operator-start-20261012T171036.651.edn`, PID 62950; the later reset is reported separately above. Do not present the earlier 193 checks/234 minutes/13 starts as today's final totals.

Git accounting uses `git log c2284b109..45bfab18e` and each commit's changed paths and co-author trailers. The required full-day log and commit bodies were read. The 46 task-resource commits in this interval alone falsify `task-triage-1.md`'s “no task resource commits today.” No complete lane token or active-hour accounting was read; per-model economic claims beyond the recorded chain usage are unverified.

## 6. Proposed plan §0

The text between the following separators is a complete replacement for plan.md §0. It intentionally replaces the layered “older direction” lists. Links are written for this audit; root should adjust them relative to `docs/seon/plan.md` when applying the hybrid.

---

## 0. Where we are, and the order

**Where we are — five lines.**

1. Agents can implement an ordinary change and root can merge it; five manual changes are recorded overall, including two from today's experiments, but automatic code merges remain zero ([audit 2](audit-2-astra.md) §1).
2. `seon.merge/merge!` is one engine; the common judgment, conflicts, automatic invocation and recoverable publication are not yet one completed loop (M4/M7/M8/M9).
3. Test reuse at the same key and incremental reach work exist (R1/R2/R11); configuration, retained base observations and one red/unknown judgment remain R4/R5/R6/R7/R8 work.
4. Final review basis is `e1d2854cb`; root reset default onto `7ef4967db` during the audit, including final M6/R11/web/E7; later filing and the completed script-contract fix need publication/reload verification.
5. Committed tasks at `e700c5ea4`: 802 identities, 173 with tagged tests and 629 without; this backlog is not an executable queue. The next task must have a reproduced red proof on its actual evaluation path.

**The agreed model.** The assignment is the merge request. Agents write their own branch; root reacts. Everything belonging to the step merges at its end under the one judgment; successors fork main after their needs merge. The branch is the proposal, and the current-state block shows its diff. Root handles unmerged coordinates. These are decided in [done-and-merge](hybrid-done-and-merge.md) §4; no new automatic-merge permission question or separate merge-task mechanism is needed. Test runs are data: root records its own merge observation on main; it does not merge the branch's runs. A test result is reused at its full key, red included; evaluator remains part of the key; unknown is not green ([test-results](hybrid-test-results.md) §7).

### Ordered work

1. **Establish the current installed and test basis. Owner: root, Sonnet executing; Haiku compares evidence.** Obtain the existing central round before starting another; classify current reds by archive, evaluator and result key. The reset to `7ef4967db` and its sentinel have already happened: do not repeat them from the old handoff. E7's malformed script contracts are now corrected by `e1d2854cb`; prove their publication/reload rather than assigning that fix again. Compare the actual changed-definition/reaching-test probe against its committed baseline; the latest sentinel timings were slower and do not exercise that proof. Fresh boot at `aea2a1052` already passed the isolated checks in [fresh-boot](fresh-boot.md); investigate `seon.sci.eval/database-effective-config` only against a preserved failing basis. **Proof/target:** current stored central results, successful installed branch acquisition and publication, warm and first-operation costs separately. **Unblocks:** reliable phase proofs and a usable agent REPL.

2. **Make one test judgment and retain its evidence. Owner: Sonnet for specified R4/R5, Opus for runner/judgment semantics; Astra only on disjoint measured cost.** R4 adds configuration to the key; R5 retains base results before R6/R7 replace competing comparisons. Resolve the chain-3 metadata-reading fixture using explicit deterministic inputs under the existing owner rule. R9/R10 remove manual accepted-red lists through the same comparison; R12 detects observed disagreements at one key, not silent cache staleness. **Red proofs:** dial change invalidates reuse; base result remains queryable; no base result is unknown; old/new red distinction is the same in context, branch evidence and checks; a same-key result is not rerun. **Unblocks:** M7 and trustworthy interpretation of the baseline. Do not call a nonexistent named test red.

3. **Complete conflicts and the shared merge judgment. Owner: Opus; Haiku prebrief against the final M6 commit.** M4 stores base/ours/theirs on the merge branch and removes `seon.merge/conflict-task!` with its last caller; resolution removes the unmerged coordinate. M7 uses `seon.context/step-status` on the actual merged program and the writer's comparison of tested digests, deleting `seon.test/change-reds`. R8 records root's merge observation on main before deleting its branch. **Red proofs:** conflict values survive and clear on resolution; a moved reached definition writes nothing; a designed typed red is judged correctly; root retains results after branch deletion. **Unblocks:** a merge whose answer and stored evidence agree. No parallel conflict representation or main-only conflict placeholder.

4. **Finish publication and prove the first automatic ordinary merge. Owner: Opus; Sonnet applies settled file conversions.** M9 derives publication from accepted program facts, owns the real checkout, and survives interruption; M8 removes the agent request, stored done machinery and manual hold only when this path exists. Reverse their current activation dependency or prove them together. **Red proof:** one ordinary assigned task edits on its SCI branch, reaches green, merges and produces its git commit with zero post-assignment hand acts; interrupt after acceptance and verify publication once on the next pass. Existing M9 target is under one second for its small-file case, measured separately from model time. **Unblocks:** the first goal milestone. Do not wait for all M18 work.

5. **Prove a serial unattended chain, including recovery. Owner: Opus on M10/M12/M13; Sonnet on specified M14/M15 work.** Successors fork main after needs merge; terminal assignments can be relaunched through facts; one starter covers model, MCP, schedule and resumed entrants without deleting their last caller. Show the branch diff and make restore usable by the agent. Add M11 before concurrent sibling repairs. **Red proofs:** predecessor branch deleted before successor starts; capped/send-back assignment becomes runnable through the declared path; each entrant gets exactly its graph; agent sees and repairs its proposal. Then one paid Design/Prove→Implement chain, with zero manual state edits or graph starts. **Unblocks:** unattended serial work; parallel claims wait for the sibling proof.

6. **Remove the next measured ordinary cost, not the previous report's ranking. Owner: Astra, file-disjoint; Opus for SCI ownership changes.** R11 is done. Measure the complete request with one changed function and one reaching test; separately profile first whole reach and M6's first merge. Keep `seon.cache` content reuse and dependency mechanisms; no encoding change without a measured end-to-end win. E5 precedes E3's final immutable-context guarantee, with E4's private-state preservation proof; do this earlier only if it is the reproduced acquisition cause. **Target:** ordinary non-model operations under a second; R11's existing one-change proof below 100 ms/128 MB remains green. Explain first full-program proportional work rather than labeling every warm sample cold. **Unblocks:** economical repeated agent work.

7. **Keep task/context hygiene beside the critical path. Owner: Haiku gathers, Sonnet fixes, root chooses.** Correct stale plan/task references and consume filing's completed `4a395ee74` evidence. Start with the next goal-critical tasks, not all 629 untagged entries. `seon.context/current-state` must deliver instructions, actionable errors and current proof status; new intended schema references must not induce accidental registration. Fix ordinary schema-admission defects from chain 4 at `seon.schema`/`seon.db`, with the exact counterexamples. **Red proofs:** each corrected context/admission behavior through the agent entrance; retire tasks when their proof is green. **Unblocks:** useful self-contained agent contexts and valid next assignments.

8. **Defer mechanisms that the milestone does not yet exercise. Owner: root sequences; Opus for M16/M17, Sonnet for mechanical M18.** M16 messages and M17 retained-base/GC work become prerequisites when that workload is demonstrated; a dependency bump occurs only in a restart batch. M18 and the broad source vocabulary renames remain after M8. E6 predecessor/octopus work is superseded by M10 and should be retired. R3's no-key replacement remains explicit pending work, not a general multimethod project. **Proof:** each retained phase's own red behavior and last-caller deletion, then the full M-final chain. **Unblocks:** broader concurrency and durable long-running operation without delaying the first ordinary merge.

### Phase status

“Done” below describes a committed slice. Default installation and whole-goal acceptance are reported separately. A filed task without a reproduced red proof is not ready to assign.

| Phase | State at final review `e1d2854cb` | Evidence / next dependency |
|---|---|---|
| P1, P2, P4, P5, P6 | Historical slices done | `b09c02b29`, `9d25711e7`, `031125438`, `be32e7501`, `015673f47`; no separate rerun of their obsolete phase sequence |
| P3, P7–P10, P-final | Remaining guarantees absorbed by M/R; P7 catch removal done in M5 | M5/M7/M12/M13 and final chain, not parallel implementation tasks |
| U0–U7 | Superseded | M1–M18 replaces push design |
| M1 | Done | `346645d56`, base known-green rule; no extra base test run |
| M2 | Done | `3eca1a962`, cardinality-many member merge |
| M3 | Done | `767551c7b`, writer-owned merge base |
| M4 | Pending; prebrief, no named red deftest yet | After M6; prove merge-branch conflicts and resolution |
| M5 | Done | `1b09d8043`, `c74ef3437`, derived lifecycle and one boundary catch |
| M6 | Done; in default after reset | `7c3077b9e`, `3da1f6449`; one engine, manual requested-merge hold remains |
| M7 | Pending | R5→R6→R7, current full key; shared judgment and writer comparison |
| M8, M9 | Pending; activation order needs correction | Publication/checkout M9 before enabling M8, or one proven combined activation |
| M10 | Pending | Successors fork main after merged needs; replaces E6 target |
| M11 | Pending | After M7; needed for concurrent sibling repair |
| M12 | Pending | Close/relaunch facts; G6 |
| M13 | Pending, entrant composition unresolved | G8; preserve MCP/schedule/resumed entrants while removing duplicate starters |
| M14, M15 | Pending | Current diff/restore; M14 proof must not assume absent `my.task/refine!` |
| M16 | Pending | Messages as branch facts and root delivery |
| M17 | Pending | GC retains required merge bases; library change only in restart batch |
| M18 | Deferred | Final vocabulary conversion after M8; current symbols remain until conversion |
| M-final | Not proven | First ordinary automatic merge, then unattended chain with counted hand acts |
| G9 | Done | `98720b4eb`, reaching tests beyond two caller levels |
| R1, R2 | Done | `50ca41030`, `474174b52` |
| R3 replacement | Agreed, not implemented | `51a5e159e`; undigested callable gives no reusable key; existing sink gap |
| R4 | Pending | Configuration digest; one-time key change, not restart invalidation |
| R5 | Pending | Retain base observation before R6 |
| R6, R7 | Pending | One exact-key status and context/entrance judgment; prerequisite for M7 |
| R8 | Pending | With M7, root keeps its own merge observation on main |
| R9, R10 | Pending | Delete accepted-red override and convert operator judgment after shared comparison |
| R11 | Done; in default after reset | `6433c7593`, incremental reach and end-to-end cost proof |
| R12 | Pending | Observed conflicting results at one key become a flake task; no automatic rerun policy |
| E1, E2 | Done | `f424382cd`, frozen program context |
| E3 | Partial | Final guarantee after E5 removes mutable-base consumers |
| E4 | Mechanism committed, final proof/ownership incomplete | `172b23e7c`; private-state preservation proof and proc installation remain |
| E5 | Pending | Derived next context and one owner of installation |
| E6 | Superseded | M10 successor-from-main; retire predecessor/octopus task wording |
| E7 | Done; in default after reset; newer script fix needs installed verification | `bc347d583`, measured context charges; `e1d2854cb` corrects four contracts |
| H1–H10 | Historical design, not a verified all-done claim | Preserve established primitives; remaining starter/ownership/branch-cost work follows M10/M13/M17/E5 |
| AW0–AW7 | Historical design with committed slices; not an independent new queue | Partition/store ids/branch writes/messages have commits; one merge replaces AW copy mechanics. Remaining guarantees follow M/R work; no all-green census claimed |

**How root executes this order.** Check claims before assigning files; filing and `contracts-pcw` have released their paths at the final snapshot. Ready disjoint work runs in parallel, constrained by root's ability to prove it centrally. Every implementation owns its red proof and reaching tests. Ordinary eligible changes use branch evidence; platform/schema/boot changes use the candidate archive and named tests. Documentation is not a program input. One restart installs a ready batch, then sentinel, affected integration and the broad tier centrally; no default reset for an audit or a lane. Keep a goal probe owned, but run a paid experiment only after the named blocker changes. Read every error and record exact evidence; no manual accepted-red exception substitutes for the common judgment.

---

## 7. Questions for the owner

These are prioritization/scope choices. The merge model, reuse of unchanged results, successor-from-main rule, branch-as-proposal, root observation on main, evaluator separation and restart discipline are already decided. Do not reopen them from older prose.

1. **Which remaining cost should get the next scarce performance assignment while merge work proceeds?** The first ordinary task before a chain is already decided; this asks about the competing cost work, not that milestone.
   - **Recommended: the complete changed-definition/reaching-test request**, after verifying R11 on the installed path. Cost: defer deeper first-whole-program and storage work; benefit: optimize what each successful agent edit pays. The last pre-R11 fixture request was 736 ms with a 510 ms test step (`b7be69770`); its current installed value is unmeasured.
   - First facts merge: `seon.merge/merge!`, now with the filed 692 ms/~1.06 GB counterexample. Cost: prioritize a less frequent first operation; benefit: remove large allocation from the actual acceptance path. Correct the proof's missing success assertion first.
   - First whole-program reach/storage: `seon.test.runner/reach-digests`, still 1.79 s/~4.98 GB in R11's evidence. Cost: possible dependency/restart work and collision with runner changes; benefit: lower acquisition and broad-check burden. No reliable person-hour estimate exists for these alternatives.

2. **How much unproven backlog should receive active attention before that milestone?**
   - **Recommended: only goal-critical tasks plus verified cheap retirements in touched owners.** Cost: most of the 629 statically untagged tasks remain; benefit: proof work directly changes the next experiment.
   - Reserve a separate Sonnet/Haiku capacity for a bounded top slice. Cost: competing verification attention and occasional file collisions; benefit: more accurate task context and fewer stale instructions.
   - Require all untagged tasks to be classified first. Cost: 629 is an inventory, not 629 confirmed defects; today's triage left 114/150 unresolved. This delays the goal without a demonstrated return.

3. **When should paid model experiments resume?**
   - **Recommended: after an interpreted ordinary proof and deterministic full-loop proof demonstrate the previously blocking mechanism is fixed; then one paid task.** Cost: no continuous paid probe during implementation; benefit: model calls test a new capability rather than a known lifecycle failure.
   - Keep one paid chain continuously in flight. Cost: the last three used 3.03 million prompt tokens and 48 reported hand acts, with no automatic merge; benefit: faster discovery of unrelated friction.
   - Stop paid runs until every broad test is green. Cost: unrelated historical reds can postpone product evidence indefinitely; benefit: a cleaner global baseline. I recommend current, attributable evidence rather than this blanket condition.

**No question is needed about automatic authorization itself.** Done-and-merge §4 already decides it. Likewise, the handoff's interpretation that another test's task tags are cluster data is consistent with test-results §7's owner ruling, but its application to chain 3 H2 must be made explicit in the owning fixture/spec. R12 only detects disagreements that actually produce two observations at one key; it cannot notice a result that is forever reused. Root should settle that concrete test input with the test owner, not purchase another design round about all database reads.

### Delivery and verification boundary

Only this audit is owned and committed by `audit2-astra`. No source, tests, schema, task resource, plan, other audit or agent session was edited. The final foreign boundary is dirty `reference-code/http-kit`, `tmp/test-data-assertions/proofs.edn`, and pre-existing untracked artifacts; filing and `contracts-pcw` committed their work independently; none is part of this commit. This is documentation, so no test var reaches the change: `seon.test.cache/input-roots` excludes documentation and `bin/lane commit` has the explicit documentation-only path. Requested `clj-kondo --lint src script test --cache false` completed in 9,266 ms with **0 errors and 1,615 warnings** (exit 2 for warnings). The audit has no runtime tests to run; its links, claims and diff were checked separately. The exact commit is reported with delivery. No missing runtime evidence is counted as a pass.
