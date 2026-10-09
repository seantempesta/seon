# Independent audit, 2026-10-09

## Verdict

**Seon is making real progress toward an agent working through its REPL. It has not yet demonstrated a dependable, self-sustaining repair loop, and it is moving away from the 10,000-line goal.** Ordinary arithmetic and a small contracted definition were sub-second in my warm measurements. Running one previously unrun test was not. The positive results are substantial; the claim that the platform is now routinely self-improving without root repair is ahead of the evidence.

The most useful changes remove work: one shared SCI base, lazy interpretation of affected callers, narrower incremental test selection, removal of old context generation, and carrying values through recording refusals. The least convincing direction is adding more publication, isolation, evidence-transfer and operator machinery before the ordinary branch → definition → actual reaching test → write-back path is reliable. The machinery addresses real failures, but each new boundary creates another agreement that tests must establish.

I would keep investing, with a narrower acceptance criterion and substantially less concurrent platform change. I would not describe this state as healthy or call the backlog an executable schedule.

## What I actually examined

The history census ends at `1a45da7bad1526ef50b2c0a5a3201deb0d9fca61`, HEAD when this lane started. The inclusive window beginning `9e0f1cba8` contains **642 commits**, rather than exactly 641. I read the requested plans, findings, cards and contracts guide, inspected the owners and selected diffs, parsed all task declarations and source/test declaration metadata, made live MCP probes, and ran two sequential throwaway checks against that frozen HEAD. I did not use the orchestrator's reports as proof of correctness.

Reproduction and raw observations are in [audit-history.py](audit-history.py), [audit-census.bb](audit-census.bb) and [audit-evidence.json](audit-evidence.json). The evidence includes the exact check arguments, every returned test result, the task classifications and the live probe envelopes. The scripts read Git or parse data; they do not evaluate the program they inventory.

Limit: this is a review of 15 selected consequential commits, **not a semantic review of all 642 commits**. All 604 open task entries receive an explicit disposition below. Of those, 521 remain provisional because no tagged proof establishes their current status. Calling those 521 bugs verified would be dishonest. “Still valid, provisional” means retain the request pending reproduction, not “I reproduced its historical claim.” I did not manufacture green evidence for missing proofs or delete tasks merely because a function was renamed.

The live JVM initially ran `e040662e42a796d42720de3273051aa94bf8bfe4`, not my frozen HEAD. During this audit another actor replaced it with `35e95e7b35c3f0376f38ae9566e537a8683989f6`. I did not restart, stop or reset default. The historical check results and live timings are deliberately distinguished.

## Measured size, work and health

These are newline counts of **all tracked files** under each directory, not a claim about executable statements. Nonblank counts are also preserved in the evidence.

| Directory | At `9e0f1cba8` | At frozen HEAD | Change |
|---|---:|---:|---:|
| `src/` | 90,230 | 91,637 | +1,407 |
| `test/` | 98,613 | 100,542 | +1,929 |
| `script/` | 5,279 | 5,673 | +394 |
| Total | 194,122 | 197,852 | +3,730 |

Source alone is **9.16 times** the stated 10k goal. This is not explained by tests or documentation. There was a real 5,715-line net deletion in the old-context cut, but additions elsewhere exceeded it. The direction over the requested window is growth.

By changed paths, **357/642 commits (55.6%) touch only tasks/docs/skills**, 283 (44.1%) touch code, tests, operator code, schemas or dependencies, and two fall outside those definitions. Mixed commits count as code. This is a reproducible path classification, not a judgment that documentation is useless. The concern is that a very large descriptive backlog still lacks executable completion criteria.

The static declaration census found **3,709 `defn`/`defn-` declarations under `src/`, 957 without `:malli/schema` metadata**. Presence is weaker than contract completeness; this count excludes other callable declaration forms. Complete contracts are a rule the project is still working toward, not an achieved invariant. The full-tree lint during this audit answered **0 errors, 1,462 warnings**, in 6.291 seconds. “The gate passed” must not be translated into “the laws have zero findings.”

At the first live observation, default reported `:faulted`: 18 failed tests, 115 unknown tests, 24 errored receipts, six open faults and seven error signatures. It used about 2.44 GB of heap, with a 1.73 GB live set and a 4.29 GB maximum; the cache was effectively at its 512 MB budget. There were 1,166 platform threads. These are observations under shared activity, not an attribution of all retained memory or threads to this lane.

After the other actor's restart, MCP briefly answered `:unknown`; the subsequent operator status again answered `:faulted`, with the same 24 errored receipts, six open faults and 115 unknown tests. Its live heap was about 2.14 GB, with 250 platform threads. The operator headline was still `SEON status OK`: that means the status command succeeded, not that the application was healthy. The store had 848,010 files and no completed collection receipt. The scheduled collection had not yet become due; that absence alone does not prove collection is broken.

### Live ordinary operations

Agent `astra-honest-audit`, cluster `default`, SCI mode; original archive `e040662e…`. The first form was `seon.context/current-state`. It cost 1,671.7 ms for the request, including 1,231.2 ms custody acquisition. Subsequent measurements:

| Form | Evaluation ms | Request ms | Armed calls | Reads / transactions | Evaluation allocation MB |
|---|---:|---:|---:|---:|---:|
| `(+ 1 2)`, first measured repeat | 0 | 538.7 | 29,401 | 41 / 1 | 0.0 |
| `(+ 1 2)`, next repeat | 0 | 315.1 | 6,225 | 41 / 1 | 0.0 |
| `in-ns`, then contracted one-line `increment` | 18 | 742.7 | 32,185 | 206 / 1 | 15.3 |
| `my.test/check` naming `seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol` | 1,428 | 1,751.5 | 138,530 | 283 / 8 | 1,402.5 |
| Repeat of that check | 27 | 224.7 | 89,657 | 94 / 1 | 28.4 |

The first named check passed five assertions. The repeated check reused completed work. It is not a second measurement of executing that test body. The allocation column is the evaluation's reported allocation, **not the whole request's allocation**; a zero beside arithmetic does not mean the surrounding machinery allocates nothing. The request cost envelope exposes considerable work outside the value's evaluation.

After the external restart, reading only this lane's branch identity cost 1,671.8 ms, including 1,416.0 ms in the test step, despite a 0 ms value evaluation. Thus “a read is sub-second” is not a stable property yet. These costs belong beside existing tasks `seon.sci.eval/a-fork-snapshots-every-program-var`, `seon.test/one-test-run-costs-seconds-around-a-147-ms-body` and `seon.test.runner/tests-reaching-rebuilds-the-whole-program-reach-graph-per-change`; this audit adds durable reproductions rather than duplicate tasks.

### What actually ran

Check `8e32f8076993` ran **all 41 distinct tagged proofs** for the 44 tasks that had them: 189 passing assertions, 29 failures, two errors, 122,275.6 ms, no exclusions and no reused tests. **22 tests were red**. Ten missing-contract tests and four competency benchmark tests are intentionally red work specifications; the other eight expose defects or obsolete test assumptions and must be investigated. The eight prose/typed competency task pairs are deliberate experiment variants, not accidental duplicates.

Check `e892eec53779` ran **16 additional selected review tests**: 82 passing assertions, two failures, no errors, 13,211 ms, no reuse. Fifteen tests passed. The failing test was `seon.cluster.turn-test/reasoning-starvation-persists-usage-finish-and-the-named-error`: the stored starvation error still lacked its finish reason. Its sibling error-identity proof was green. That is a concrete example of why retiring a task on one green tag would be wrong.

Across both checks: **57 executed tests, 34 green and 23 red**. This is a selected sample, not a whole-suite pass rate. The raw reports identify each failure. In particular:

- The disarm proof still waits for an obsolete prelude latch and errors after 20 seconds; it does not establish that disarm itself is broken in the claimed way.
- Reset's child stdout is not one EDN value: a timestamp reaches the EDN reader.
- Deletion refusals and callback blame still fail their assertions.
- Lost model calls and messages are not correctly established by the model-context tests after the context cut.
- Assignment allocation measured 10,108,112 bytes against an 8,388,608-byte bound.
- The execution-world reuse proof is red.
- The starvation fixture still stamps the retired metadata error identity and loses the expected stored member.

### Agent landings and root rescue

There are **seven commits with `Branch-evidence:` trailers** in the 642-commit window. Six are the named model fan-out landings; the seventh is the outside `impl-rf2-dogfood` lane. This is an independently counted Git fact. It is not a count of autonomous successes: a branch can carry green evidence after root changes its definitions.

I traced the commits covering **15 distinct fan-out fixes**: two in fan-out 1, one in 3, three in 4, two in 6, one in 7, one in 8 and five in 9b. Their landing records report:

| Outcome | Fixes |
|---|---:|
| Accepted unchanged and landed with branch evidence | 2 |
| Root changed the implementation or restored material the agent dropped | 9 |
| Implementation accepted but root repaired/bypassed a proof or write-back path | 4 |

Thus **13/15 needed some form of root rescue**, according to the landing records. Of the nine source interventions, several are substantive: distance recomputed inside a sort comparator, an extra printer, a dropped refusal, and an unused helper. The two unchanged branch-evidence cases are source-doc deduplication (`64b270b02`) and abbreviation handling (`ca65cb792`). The other rescued cases are the missing-connection fix whose proof expectations root updated, the two fan-out-6 fixes, and the missing-section reply in fan-out 9b.

This attribution is **historical evidence, not independently replayed agent authorship**. I inspected the resulting diffs and trailers; Git does not preserve every pre-review agent version, and I did not enable a provider to reenact the runs. An exact independently observed autonomous success fraction cannot be reconstructed from the committed data. The reports' success language should retain that limitation. In particular, “first fan-out” in `7c9b96d8e` cannot literally mean the first agent ever to turn a proof green: the earlier fan-out-1 landing records already say that happened.

## Review of 15 consequential commits

“Pass” below means the named behavior ran in my checks at the frozen HEAD. It does **not** imply that I ran the historical commit's own gate, or that every later change preserves it. Numbers after each hash are all-file additions/deletions for that commit, not an invented per-feature budget.

| Commit | What the implementation establishes | Independent evidence and remaining concern |
|---|---|---|
| `6191eace4` (+489/−345), shared base | `seon.sci.eval/cluster-base-key` and the central cache replace the small revision LRU; branch differences remain overlays. This is a useful reduction in repeated construction. | `seon.cluster.evaluate-source-test/an-agents-second-run-and-a-settings-write-construct-no-base`: 3 passes. The key still follows loaded-program identity; whole namespace snapshots and context-owned mutable maps remain. The test proves no new base, not cheap acquisition. |
| `f8f2618e8` (+467/−368), SCI binding integration | Uses SCI's stable Var identity with per-context bindings and enters `sci.ctx-store/with-ctx` at host-side accesses. This addresses a real branch-callee visibility problem. | `seon.sci.eval-test/sci-fork-bindings-are-per-context`: 10 passes; transitive branch execution also passes. The integration adds or moves several `atom` sites, including base bindings, installed functions, program snapshot and recording state. The current design does not satisfy a literal one validated state map per scope. Do not call those all new atoms without a before/after ownership analysis. |
| `55ede54a9` (+161/−5,876), old context deletion | Actually deletes substantial context generation, opening/capture and prompt-selection machinery. This is the strongest size improvement in the sample. | The lost-call and message-context proofs are red, and the disarm fixture still waits on removed machinery. The deletion is real; replacement behavior and conversion of every test/caller were not complete. This is a rollout gap, not an argument to resurrect the deleted framework. |
| `da1210491` (+132/−30), incremental reaching step | The automatic request uses the previous run's basis to narrow changed declarations instead of selecting all branch changes again. | `seon.test-reaching-test/a-reaching-request-selects-what-changed-since-its-previous-run`: 7 passes. Correct narrowing is proven. It does not remove whole-program reach graph construction or all per-request work; my named check still allocated 1.4 GB. |
| `30be17208` (+270/−60), lazy affected callers | `seon.sci.eval/interpret-overlay!` binds affected callers for interpretation on use and carries promotions across the request. | `seon.repl-differential-test/promoting-a-closure-costs-its-walk-and-the-callers-called`: 4 passes. This makes interpretation cost follow called bodies. Reverse-closure traversal/binding is still work, and the closures retain their supplied database. “Lazy” does not mean zero acquisition or retention. |
| `665ac1c10` (+169/−43), namespace write-back | Uses rewrite-clj to make the stored namespace source represent REPL require/alias/refer state, so reindexing can agree with accepted declarations. | `seon.turn.batch-test/a-repl-require-is-written-back-in-the-ns-form-and-reindexes-equal`: 4 passes. The sibling-file write-back regression also passes. This composes the appropriate syntax library rather than regex parsing. The source reconciliation logic is still substantial; neither proof establishes sub-second write-back for large closures. |
| `e984eb0ab` (+132/−33), declaration write-back | `seon.cluster.source/proven-file` re-analyzes changed on-disk bytes, preserves sibling declarations and compares base/ours/theirs digests for the declarations being written. It uses the ordinary analyzer and keeps the final file-base check. | `seon.cluster.write-back-test/a-sibling-change-landed-in-the-file-is-kept-and-the-same-declaration-conflicts`: 5 passes. This removes a concrete root-rescue cause. Work is proportional to the changed file, including another analysis when it diverged. The declared nested `:map` shapes are broad; a passing focused proof does not make the separate merge preparation tests green. |
| `dc79b069f` (+104/−35), cache weights | Changes reach-kind weights to charge their built structures and removes a second reach-declaration holder. | `seon.cache-calibration-test/every-reach-kind-is-charged-what-it-was-measured-to-retain`: 6 passes. This calibrates accounting, not whole-JVM retained memory. It does not fix non-reach kinds or shared-object double accounting everywhere. One central Caffeine owner is the right seam. |
| `86659ca5b` (+36/−15), own bindings | Replaces three armed predicates per namespace Var with a pass over SCI's own bindings. | `seon.eval-alloc-test/a-trivial-form-makes-fewer-armed-calls-than-its-context-holds-vars`: 2 passes. My warm arithmetic reached 6,225 calls, but the preceding request had 29,401; both remain expensive for the form. The commit explicitly leaves namespace snapshots and a costly early request. This is an honest, useful partial fix. |
| `38ff88494` (+33/−30), value survives refusal | `seon.turn/settle-batch-refusal!` keeps evaluated values beside recording/opening failure instead of replacing them with the refusal. | `seon.cluster.evaluate-source-test/a-run-whose-open-is-refused-still-answers-its-values`: 2 passes. This follows the total-path rule and adds little machinery. It does not fix every deletion-refusal association; that separate proof is red. |
| `fa92a2503` (+391/−14), platform check | Composes archive copy, write-back, a temporary Git index, existing operator check and ordinary result recording. It keeps the shared Git index untouched. | The recording test passes 3 assertions, but supplies fabricated child results as its external input; it never proves a changed host-bound body ran. `record!` attaches the branch's reach digests, while the child payload carries counts and reports, not tested digests. With explicitly selected identities, candidate/branch agreement needs a stronger proof; see below. This also adds EDN serialization and a hardcoded 30-line output clip. It is a justified isolation boundary with insufficient end-to-end evidence, not yet proof that the complete REPL platform workflow works. |
| `a62888920` (+583/−159), generated contracts | `seon.contracts/task`, `report` and `gaps` generate typed declarations and select contract findings for one function. The batch exposes ten real missing schemas. | Task-generation proof: 1 pass; partition proof: 3 passes. All ten contract coverage tests are red with missing specs, as expected. Generation does not itself create reaching proofs, and broad return schemas elsewhere still need review. This is a useful inventory tool, not 921 completed or independently assignable fixes. |
| `7fc0ebb2a` (+119/−35), reference context | `seon.ref/references` now offers callers/reaching tests for functions and heading/size/read operations for existing documents. | The two new proofs passed 4 and 18 assertions. This directly improves an agent's context and merits retiring those tasks. It returns data and operations rather than executing a render's offered operation. Reference correctness is still incomplete elsewhere in the inventory. |
| `daaa38c0e` (+326/−59), check templates | Reuses a committed base store per published commit and fixed slot, with file locks, retention and process-aware cleanup. | Cleanup and pruning tests pass 2 and 3 assertions. This reduces repeated boot publication in principle, but adds an operator cache and roughly 267 net lines. External operator caches are allowed by the rules; the implementation still has broad `:any` contracts, a hardcoded 200 ms lock poll and a failed-template fallback. More seriously, the initial template path did not make the candidate's newly added tests eligible. |
| `60f530cef` (+44/−9), candidate adoption | Calls the existing development adoption operation before selecting the candidate's tests. It fixes the correctness hole in the template/check path. | Adoption-failure test: 2 passes. More strongly, my frozen-HEAD checks actually executed tests added after the archive the live JVM initially ran. Publication alone was not installation. The previous green checks could omit new tests, so their messages cannot retroactively be treated as proof those tests ran. |

### Dependency seams and rule compliance

The frozen pins include Malli `418df4417827b66846b799b5e87267a344dffc29`, SCI `41194667cc6b4d52d0d723d66d754d064f9c0d5f`, and Datahike `e65599de5eb393ca842304b6487453616214ea26`. I checked the local reference implementations: `malli.core/validator` and `malli.core/-instrument` own compilation/validation; `sci.core/fork` and SCI's context bindings own interpreter forks. The shared-base and binding changes compose these seams, although Seon's surrounding snapshots and state still cost substantially more than the fork alone.

The selected commits do not establish compliance with “one printer,” “no extra mutable state,” complete contracts or configured policy bounds. Counterexamples are executable source, not philosophical objections: `seon.render.value/bounded-text` still wraps `pr-str`; `seon.test.runner/record-latest-tx` still creates a `memoize`; context copying in `seon.sci.eval` retains separate atoms; the platform output clips at 30 lines; template locks poll at 200 ms. Some predate the reviewed commits. Do not blame every existing violation on this window, but do not declare the laws implemented either.

Another material finding is `c5826eb62`: branch interpretation now includes all affected test callers, but `seon.fn/closure-walk` returns a separate selection limited to the seed and direct callers. `seon.test/reaching` uses that limited selection. Its green test explicitly asserts that the distant test is absent from selection. The task had required finding reaching tests at any depth. I retained and corrected that task rather than treating its green proof as complete: the original completion condition and the recorded one-level selection ruling need reconciliation. Expanding a saturated graph blindly would also be wrong.

A concrete **unproven platform agreement**: `my.test/check-platform` allows `:seon.program/identities` to select a subset of the branch changes. It writes that subset into a published-base archive, then `seon.test.platform/record!` stamps results using reach digests from the full held branch database. If a test also reaches another changed declaration omitted from the candidate, those two programs can differ. The present recording test cannot detect that case. This is a static correctness risk, not a demonstrated failing runtime trace. The needed regression is two changed host-bound declarations, a test reaching both, and a candidate containing only one; recording must refuse the mismatch or select an exactly corresponding tested program. No extra proof registry is needed.

The plans also contain superseded causal claims. The publication-bisect finding contradicts treating publication as the demonstrated source of the full slowdown; the store-GC investigation distinguishes workload writes from an idle leak. Those findings should replace earlier causal language in the owning plan. Keeping both descriptions as if current makes future agents repeat refuted investigations. The REPL/testing skills likewise still describe some retired system-turn/task-handle behavior and hand-written line references; the installed owners, not those passages, were my authority.

## Task audit and edits

At frozen HEAD there were **604 task declarations in 85 files**. Only **44 tasks (7.3%) had tagged top-level `deftest` proofs**, representing 41 distinct tests. **26 tasks had at least one red proof; 18 had all their tagged proofs green.** The remaining **560 tasks (92.7%) had no tagged proof** in the parsed source/test/script declarations. Fifty-five did not even declare a must-read set. The census had no parse errors. These counts describe task files, not every historical or database-generated task.

The dispositions are 555 “still valid” (mostly explicitly provisional), 15 “already fixed,” 12 “stale,” two “duplicate,” and 20 “not agent-sized.” They sum to 604. A stale reference does not prove its underlying behavioral problem is fixed. The appendix distinguishes such retained stale entries from actual retirements.

This slice **retires 19 entries and removes every matching source/test `:seon/tasks` tag**. It leaves **585 declarations** from the frozen inventory. Two duplicates disappear; 14 entries are already fixed; three are moot or contradicted by current law. It also corrects four intents:

- Reply-size measurement now names the remaining producer, without asserting the deleted bootstrap site still exists.
- Error-identity cleanup now says which fixture is still broken and that its existing green proof is insufficient.
- MCP timeout work now distinguishes the passing tool-table schema test from installation and a real bridge round trip.
- Transitive branch interpretation now distinguishes a passing execution proof from automatic test selection, which still intentionally omits distant tests.

The retired duplicate drift entries describe a producer that now calls `seon.test.runner/report-value`; it no longer directly prints the drift with `pr-str`. The retired custody task demanded a security boundary the current AGENTS.md explicitly rejects. The two retired turn tasks name deleted functions with no remaining calls in `src`, `test` or `script`.

**Ownership:** the first claim for `src/seon/sci/eval.clj` was refused because `repl-only-2` held it, so I initially left JVM-recording and profile-wrapper retirements as proposals. That lane later committed and released the file. I then claimed it and completed those two retirements with their tags. No held file was edited. Other stale-reference rows remain proposals because a declaration lookup alone cannot establish the intended replacement symbol or close the behavior.

Five additional tasks landed elsewhere during the audit. They are listed separately after the frozen appendix and retained without pretending to reproduce the other lane's reports. The final observed inventory is **590 tasks: 604 − 19 + 5**.

I did not merge the prose/typed competency pairs: they intentionally compare two task formulations. Nor did I retire the value-storage task merely because its laziness proof passes: its stricter timing/allocation intent is broader. The same caution applies to platform proof, MCP installation, and namespace-wide `my.program/breaks` timing.

## What root should do next

These are engineering estimates, not measured completion promises. Cost includes an independent proof and a landing, with no new paid-provider run assumed.

1. **Make one complete repair loop the acceptance test — 1–2 engineer-days.** Start with a small existing red task, define its fix on its branch, prove that the actual changed caller chain ran, write it back, and accept only current matching evidence. Repeat with a host-bound change and the two-change/subset platform case above. Preserve branch and tested identities in the existing evidence. Stop declaring success at the recording-only seam.
2. **Repair the nine unexpected red behavior classes in this sample — roughly 2–4 engineer-days, sequenced by owner.** First distinguish obsolete fixtures from production failures; the disarm latch and error metadata already have concrete diagnoses. Fix reset stdout, error association/delivery, context replacement and test-world reuse at their producers. The ten contract and four competency reds remain explicit work, not regressions to hide.
3. **Make the next ten assigned tasks genuinely executable — 0.5–1 day for curation, then bounded implementation slices.** Choose tasks with a current red proof and a small owner. Turn broad sweep/triage entries into a few precise changes. Automatically report green-only and proofless tasks using the existing task/test relation; do not add another tracker. This audit removes 19 entries, but 560 proofless requests cannot be made launchable by better prose alone.
4. **Profile the full request around one new test and one definition — 1–2 engineer-days for a focused measurement/fix cycle.** Use the same immutable program and input on parent and candidate. Measure all request stages and retained/allocation cost; distinguish reuse from execution. The 1.4 GB named-test evaluation and 89,657 calls on reuse deserve priority over another micro-optimization of arithmetic. Remove work before adding another cache.
5. **Set a deletion and integration budget for platform work — 1 day to inventory owners, then 3–5 days for the first useful cuts.** Require each major addition to name the machinery it makes obsolete and prove its removal. Begin with context snapshots, duplicated error/printing paths and operator/evidence plumbing. Update the single current plan with the refuted causes and live proof status. The immediate goal is a consistently smaller, reliable path, not a promise to delete 80k lines in one sweep.

## Landing and verification limits

Only task entries, task metadata tags, this audit and its evidence/reproduction scripts are changed by this lane. No function body, test assertion, provider configuration or live default code was changed. The live scratch definition remained on this lane's branch. `my.agent/retire-branch!` accepted its retirement (`:my.agent/retiring? true`); this is not claimed as observed physical deletion of the branch. The reply is preserved in the evidence.

Foreign boundaries during the audit included `repl-only-2`'s SCI source/kernel, schema and `test/my/program_test.clj`, `root-report`'s report, the dirty http-kit submodule, the pre-existing temporary proof file and untracked directories. The review checks ran committed frozen HEAD. The landing check uses committed HEAD plus only this lane's claimed paths; unrelated working-tree edits are excluded. No other lane was resumed or messaged.

Full-tree lint: 0 errors, 1,462 warnings. The selected review checks above intentionally report their reds. The landing check names task ingestion and one or more green tests for every edited test file; this checks the metadata/task reconciliation without pretending the known-red namespaces are wholly green. No broad suite, browser paint, real model-provider run, or exhaustive performance benchmark is claimed.

The first landing gate booted the candidate and passed all **12 tests / 128 assertions**, run `cb87d77d80a4`, **13,375 ms** test time. It nevertheless refused the commit because live publication/adoption still held the previous Malli dependency while HEAD carried the separately landed fork bump `00b1f4eed`. Total check time was **117,684 ms**, including **56,369 ms** template seeding and **36,745 ms** candidate boot. This is additional measured operator cost, not a test failure and not sub-second ordinary work. The complete refusal and phase values are in audit-evidence.json. I did not bypass the gate. Another actor subsequently replaced default (observed pid 20363), permitting a retry through the same normal command. Neither restart was performed by this lane.

The final pre-commit lint repeated after metadata cleanup: **0 errors, 1,462 warnings, 6.431 seconds**. The production source delta is **−3 lines**, all task tags; test delta is **−8 lines**, all task tags. The report is about **35,300 estimated tokens**, most of it the required 604-task appendix; the raw evidence is about **90,400 estimated tokens** and intended for querying rather than reading linearly.

The landing gate runs these 12 named tests (the commit is refused unless they pass):

- `seon.task-ingestion-test/ingested-tasks-answer-both-ways-and-unchanged-input-writes-nothing`
- `my.test-check-test/a-host-bound-redefinition-names-its-platform-proof-instead-of-running-its-tests-in-sci`
- `my.test-check-test/a-docstring-only-defn-reruns-no-test-and-a-body-change-reruns-its-reaching-tests`
- `seon.cluster.evaluate-source-test/code-evaluates-as-one-ordinary-run-of-its-agent`
- `seon.cluster.write-back-test/a-sibling-change-landed-in-the-file-is-kept-and-the-same-declaration-conflicts`
- `seon.flow-test/a-proc-thread-lives-in-its-projection-world-without-one-armed-call`
- `seon.operator-test/a-checks-adoption-answers-its-failure-as-data`
- `seon.ref-test/a-referenced-doc-shows-its-heading-size-and-one-read`
- `seon.ref-test/a-referenced-function-shows-its-callers-and-reaching-tests`
- `seon.sci.library-aliases-test/an-agent-in-a-test-namespace-writes-a-proof-with-its-helpers`
- `seon.test-cost-test/a-member-records-its-calls-allocation-and-time`
- `seon.mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`

## Every-task disposition

The inventory is frozen at the audited HEAD. “None” means no tagged top-level proof, not no tests anywhere. Green/red refer to the executed proof batch. A **provisional** retained item is not a reproduced bug. “Stale / static reference” identifies a missing must-read declaration, not a license to delete its underlying request. “Retired” means this slice removes the entry and its tags.

### resources/seon/tasks/my.agent.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `my.agent/a-context-only-branch-read-is-not-supplied` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.agent/a-refused-retire-leaves-the-agent-admission-closed` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.agent/retiring-a-branch-allocates-a-hundred-megabytes` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.agent/settings-root-writes-on-main-never-reach-an-agent-on-its-branch` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/my.edit.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `my.edit/file-edit-tools-bypass-write-back` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.edit/edit-hook-budgets-need-owner-numbers` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/my.fs.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `my.fs/fs-read-ignores-limit-and-has-no-line-addressing` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.fs/archive-file-reads-have-no-explicit-source-world` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/my.program.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `my.program/an-agent-cannot-see-or-choose-its-change` | stale; static reference | Proof none. Must-read not found in declaration census: my.program/overrides. Retain underlying problem pending reference/behavior verification. |
| `my.program/breaks-plans-a-contract-change-as-a-retraction` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/breaks-costs-seconds-on-a-hub` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/a-one-line-change-resends-the-whole-definition` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/propose-refusal-names-no-next-step` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/a-merged-change-reaching-the-files-is-unproven` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/history-shows-the-source-twice-and-no-commit-id` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/ns-unmap-and-remove-ns-cost-quarter-thousand-tokens-and-most-of-a-second` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/the-merge-prepare-costs-three-seconds-and-400k-calls` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/alias-and-require-as-retarget-silently` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/merge-tools-throw-instead-of-returning` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/root-cannot-diff-a-proposal` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/accept-cannot-retire-a-hosted-proposers-branch` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/breaks-on-a-namespace-subject-takes-five-seconds` | still valid; tested | Proof green. Tagged proof green; remaining intent is broader than this proof, so retained. |
| `my.program/write-branch-writes-files-the-lane-has-not-claimed` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/diff-answers-empty-after-a-branch-write-back` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/write-branch-in-the-defn-request-answers-a-merge-changed-nothing` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/a-proof-test-fails-from-concurrent-clusters-drift` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/remove-ns-allocates-two-gigabytes` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/a-root-repl-proof-cannot-reach-its-file` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/breaks-of-a-widely-used-function-allocates-a-gigabyte` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/a-clojure-style-ns-unmap-is-refused-as-a-missing-context` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/a-repl-alias-stays-on-the-namespace-row-after-ns-unalias` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/write-branch-in-the-running-agent-harness-compares-with-a-home-branch-the-agent-did-not-fork-from` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/write-branch-of-one-function-allocates-a-gigabyte` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/the-map-argument-proof-errors-reading-its-reply` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.program/write-branch-refuses-a-file-a-sibling-change-already-landed-in` | already fixed; retired | Proof green. Entry and all declaration tags removed. |

### resources/seon/tasks/my.task.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `my.task/the-queue-on-an-agent-branch-offers-tasks-claimed-on-the-cluster` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.task/no-per-function-health-view-and-open-misses-over-bound-tasks` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.task/task-creation-is-not-an-agent-call` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.task/task-writes-are-untimed-and-refusals-are-hand-built` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.task/open-offers-no-authored-tasks` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.task/open-takes-over-a-second-validating-query-variables` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/my.test.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `my.test/run-answers-a-whole-namespace-without-a-verdict` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.test/run-owned-reply-repeats-its-output` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.test/test-output-and-an-empty-reaching-run-reach-the-reply` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.test/check-ignores-the-tests-key` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.test/an-agent-cannot-run-one-test-by-hand` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.test/a-two-test-check-allocates-a-gigabyte-and-a-half` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `my.test/a-check-failure-shows-no-message` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.ai.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.ai/no-auth-refuses-at-complete` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.ai/the-ai-test-captured-run4-reply-is-unread` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.ai/deepseek-model-rows-and-dial-classification-are-stale` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.ai/a-dsml-tool-call-reply-reads-as-prose` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.ai/contract-output-observed?` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |

### resources/seon/tasks/seon.background.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.background/dogfood-followups-background-and-shell-weight` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |

### resources/seon/tasks/seon.batch.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.batch/a-jvm-run-answers-evaluated-but-not-recorded` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.batch/an-earlier-redefinition-of-a-host-function-fails-as-a-core-fault` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.batch/a-deftest-fixture-observation-is-dropped-by-the-runtime-batch` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.batch/a-request-cost-counts-only-the-evaluation-arm` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.bootstrap.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.bootstrap/help-evidence-test-red-on-head` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.bootstrap/session-end-is-taught-three-ways` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.cache.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.cache/one-weighted-cache-under-a-memory-cap` | stale; static | Proof none. Central Caffeine cache and configured 512 MB cap already exist. Re-scope any remaining noncentral derived state; do not build another cache. |
| `seon.cache/non-reach-kinds-weigh-three-to-seven-times-their-retained-bytes` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.call-preparation.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.call-preparation/a-positional-database-is-not-supplied` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.call-preparation/the-call-state-is-an-atom` | stale; static reference | Proof none. Must-read not found in declaration census: seon.call-preparation/hook. Retain underlying problem pending reference/behavior verification. |
| `seon.call-preparation/a-map-argument-missing-its-database-is-refused-not-supplied` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.cluster.agent.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.cluster.agent/a-trivial-form-waits-on-two-durable-commits` | already fixed; retired | Proof green. Entry and all declaration tags removed. |
| `seon.cluster.agent/an-escaped-pass-closes-the-reply-without-its-fault` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.agent/a-wrapped-stop-interrupt-is-recorded-as-a-core-fault` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.agent/interrupted-rewalks-the-cause-chain` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.agent/reply-size-is-measured-by-printing-the-sources` | duplicate; retired | Proof none. Entry and all declaration tags removed. Merged into the sibling description; drift producer itself is now fixed. |
| `seon.cluster.agent/reply-size-prints-sources-to-measure-them` | still valid; corrected | Proof none. Merged duplicate; removed obsolete bootstrap claim; current submit-source-in-projection still prints forms to count them. |
| `seon.cluster.agent/finished-agents-and-their-branches-accumulate` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.cluster.boot.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.cluster.boot/a-development-cluster-boots-in-tens-of-seconds` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.boot/reset-cli-stdout-is-not-one-edn-value` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |
| `seon.cluster.boot/request-failure-message-is-a-printed-cause-chain` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.cluster.branch-evidence.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.cluster.branch-evidence/the-branch-evidence-gate-rarely-sees-red-before-on-main` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.branch-evidence/an-agent-check-costs-ten-times-its-one-test` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.branch-evidence/a-printer-failure-answers-unprintable-without-its-cause` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.branch-evidence/agent-identity-prints-its-id-into-the-message` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.branch-evidence/the-branch-evidence-commit-is-dominated-by-whole-tree-lint` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.branch-evidence/a-lane-branch-does-not-survive-a-default-restart` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.cluster.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.cluster/adoption-readopts-what-the-jvm-loaded` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster/record-jvm-evaluations-on-shared-path` | already fixed; retired | Proof none. Proof green and owner implementation corrected. Claimed and removed tags after repl-only-2 released its file. |
| `seon.cluster/a-missed-quiescence-keeps-the-route-listener` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster/adoption-write-costs-sixteen-seconds` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster/boot-publication-phases-are-invisible` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster/a-test-adoption-moves-the-jvms-loaded-identity` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster/incremental-publication-test-redefines-process-wide-vars` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster/schema-adoption-refuses-instead-of-dropping-an-edn-encoded-value` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster/schema-convergence-sees-order-the-store-does-not-keep` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster/publication-tests-cost-35-seconds` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster/schema-form-keys-re-derive-files-whose-entities-do-not-change` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster/retiring-an-attribute-with-stored-component-data-refuses-adoption` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster/a-missed-agent-join-throws-instead-of-answering` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster/contract-instruction-entity-changes` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |

### resources/seon/tasks/seon.cluster.host.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.cluster.host/a-refused-deletion-reaches-the-deleting-forms-receipt` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |

### resources/seon/tasks/seon.cluster.instruction.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.cluster.instruction/toolkit-includes-fixtures-and-undocumented-generators` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.cluster.message.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.cluster.message/unresolved-sender-logs-unbounded` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.cluster.registry.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.cluster.registry/branch-creation-is-serialized-store-wide` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.registry/a-status-read-pays-a-store-read-for-the-current-source-commit` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.registry/a-retire-branch-error-repeats-in-every-reply` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.registry/contract-cluster-branch` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |

### resources/seon/tasks/seon.cluster.source.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.cluster.source/changed-identities-walks-history-for-an-adopted-commit` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.source/index-issues-drops-every-opened-date-in-a-git-less-archive` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.source/a-new-function-cannot-arm-in-the-merge-gate` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.source/a-test-only-namespace-throws-at-prepare` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.source/a-branch-schema-blocks-diff-and-merge` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.source/a-red-prepare-leaks-its-candidate-branch` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.source/a-launch-option-change-in-deps-edn-refuses-every-check` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.source/a-dependency-addition-cannot-be-proven-with-its-first-consumer` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.source/a-candidate-script-namespace-is-analyzed-as-the-running-jvms-copy` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.source/three-write-back-merge-tests-red-in-prepare-merge` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.cluster.status.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.cluster.status/heap-live-counts-garbage` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.cluster.store.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.cluster.store/each-commit-forces-the-directory-once-per-node` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.store/a-small-commit-rewrites-thirty-index-nodes` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.store/index-roots-lose-their-subtree-counts` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.store/each-commit-serializes-its-root-record-twice` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.store/an-ordinary-commit-writes-26-files-and-136-kb` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.store/collection-lists-the-store-keys-twice` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.store/each-commit-serializes-hundreds-of-times-the-bytes-it-writes` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.store/a-test-world-rereads-index-nodes-from-disk` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.cluster.store/the-node-cache-counts-nodes-not-bytes` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.cluster.wake.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.cluster.wake/wake-delivery-never-arrives-in-the-test-world` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.competency.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.competency/area-is-contracted` | still valid; tested | Proof red. Intentional benchmark work, not an established platform regression; prose/typed pairs are deliberate input variants. Proof red. |
| `seon.competency/area-is-contracted-typed` | still valid; tested | Proof red. Intentional benchmark work, not an established platform regression; prose/typed pairs are deliberate input variants. Proof red. |
| `seon.competency/quantity-schema-then-function-prose` | still valid; tested | Proof red. Intentional benchmark work, not an established platform regression; prose/typed pairs are deliberate input variants. Proof red. |
| `seon.competency/quantity-schema-then-function-typed` | still valid; tested | Proof red. Intentional benchmark work, not an established platform regression; prose/typed pairs are deliberate input variants. Proof red. |
| `seon.competency/slug-has-a-passing-test-prose` | still valid; tested | Proof red. Intentional benchmark work, not an established platform regression; prose/typed pairs are deliberate input variants. Proof red. |
| `seon.competency/slug-has-a-passing-test-typed` | still valid; tested | Proof red. Intentional benchmark work, not an established platform regression; prose/typed pairs are deliberate input variants. Proof red. |
| `seon.competency/app-calls-util-through-an-alias-prose` | still valid; tested | Proof red. Intentional benchmark work, not an established platform regression; prose/typed pairs are deliberate input variants. Proof red. |
| `seon.competency/app-calls-util-through-an-alias-typed` | still valid; tested | Proof red. Intentional benchmark work, not an established platform regression; prose/typed pairs are deliberate input variants. Proof red. |

### resources/seon/tasks/seon.config.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.config/a-config-read-walks-the-whole-registry` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.config/compute-concurrency-dial-has-no-executor` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.config/no-provider-cannot-be-lifted-per-agent` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.context.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.context/the-current-state-block-misleads-and-is-large` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.context/a-lost-model-call-is-not-in-the-model-context` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |
| `seon.context/a-message-to-the-agent-is-not-in-the-model-context` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |
| `seon.context/an-agents-context-omits-the-errors-mcp-shows` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.context/proofs-of-one-task-allocates-close-to-a-gigabyte` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.contracts-compile-test.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.contracts-compile-test/edit-hook-refuses-a-lawful-throw-of-a-registered-error` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.contracts-compile-test/empty-input-calls-analyzer-with-a-refused-empty-source-map` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.db.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.db/call-reach-is-recomputed-per-render` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/arity-caches-copy-the-indexes` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/custody-scopes-widen` | stale; retired | Proof none. Entry and all declaration tags removed. Current law contradicts intent. |
| `seon.db/deletion-error-has-no-caller` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/a-query-refusal-holds-a-parser-object` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/the-checked-writer-is-slow-on-a-fresh-branch` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/admission-without-projection-embeds-its-error` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/a-report-db-before-names-a-stale-commit` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/q-lets-a-dependency-throw-escape` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/read-evidence-carries-result-digests` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/encoding-only-storage-changes-migrate-reused-entities` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/projection-fallback-prints-instead-of-returning` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/an-entity-only-pattern-scans-past-the-query-work-bound` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/a-unique-rejection-message-is-the-raw-exception` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/capturing-read-evidence-costs-0.8-ms-per-index-seek` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/a-transaction-of-many-profile-measurements-hits-the-validation-node-limit` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/render-rejection-ai-fails-its-own-input-contract` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/the-datahike-parity-test-names-an-unadmitted-callable` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/the-world-schema-refuses-the-database-value` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/settlement-transactions-are-stamped-as-absent-call` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/a-probe-below-the-seek-break-even-scans-the-attribute` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/a-variable-attribute-rule-query-reads-942-nodes-for-no-rows` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/an-agent-transact-reports-the-runs-open-datoms` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/an-identity-join-scans-the-whole-ref-attribute` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/an-or-clause-joined-to-a-pattern-costs-a-scan-per-binding` | stale; static reference | Proof none. Must-read not found in declaration census: seon.turn/code-currency. Retain underlying problem pending reference/behavior verification. |
| `seon.db/fork-optimistic-tx-report-test-races-its-listener` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/fork-planner-regression-bench-never-finishes` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/history-joins-ignore-shared-added-flags` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/planner-estimates-load-every-node-of-the-counted-range` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/q-hands-a-non-query-map-to-refused-read` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/rule-arguments-resolve-lookup-shaped-data-as-entities` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/a-cross-pass-alias-retarget-leaves-dependant-digests-stale` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/the-digest-refusal-names-no-cause` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/transact-call-takes-five-seconds-at-its-maximum` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/a-refused-write-names-the-undeclared-key-among-the-registered-candidates` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/a-datahike-query-walks-every-retained-result-bucket` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/every-index-node-access-updates-an-lru-map` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/a-deftest-after-a-same-request-refer-is-refused-by-digest` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.db/contract-edn-encoded?` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |

### resources/seon/tasks/seon.dev.hook.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.dev.hook/the-hook-sends-only-forms-with-a-var-definition` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.dev.hook/the-hook-cannot-load-a-host-only-test-namespace-on-a-branch` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.dev.mcp.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.dev.mcp/a-live-io-prepl-session-has-no-idle-bound` | stale; static reference | Proof none. Must-read not found in declaration census: seon.dev.mcp/execute-clj-eval. Retain underlying problem pending reference/behavior verification. |
| `seon.dev.mcp/large-source-request-fails-in-the-bridge` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.dev.mcp/the-timeout-remedy-names-a-parameter-eval-clj-does-not-declare` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.documentation.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.documentation/documentation-test-reads-a-retired-doc-and-dir-shape` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.effect.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.effect/background-poll-answers-edn-strings` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.effect/a-file-stat-costs-a-thousand-armed-calls` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.error.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.error/a-deleted-functions-fault-never-resolves` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error/fault-observation-returns-the-recorded-error` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error/error-transactions-carry-references-never-worlds` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error/refusal-html-reads-as-fragments` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error/health-stays-faulted-on-dead-process-faults` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error/a-fault-notification-is-its-errors-edn-as-text` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error/a-distance-zero-fault-render-omits-whether-it-is-open` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error/the-error-observation-pull-names-541-attributes` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error/a-fixed-fault-with-an-answering-task-has-no-proof-path` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error/s1-left-metadata-readers-and-stamps-in-three-test-files` | still valid; corrected | Proof green. Corrected: identity test green, but turn fixtures still use metadata; starvation test red in review batch. |
| `seon.error/error-messages-print-growing-values` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.error/error-admission-is-a-second-clipper` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.error/a-fault-without-an-operation-is-recorded-under-normalize` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error/an-error-render-drops-the-members-that-say-what-is-missing` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error/fault-task-closing-has-no-writer-left` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error/fault-window-derives-the-error-selector-when-no-error-occurred` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error/error-notifications-to-root-are-printed-maps` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.error.refusal.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.error.refusal/refusal-offending-arguments-held-by-reference` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error.refusal/a-refusal-omits-the-data-that-explains-it` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error.refusal/hand-built-error-maps-remain-across-the-namespaces` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.error.refusal/errors-capture-whole-stack-traces` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.error.refusal/identity-folding-assumes-stable-projectors` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error.refusal/the-error-data-walk-allocates-a-kilobyte-per-collection-node` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.error.refusal/an-interpreted-callback-is-blamed-on-the-compiled-function-calling-it` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |

### resources/seon/tasks/seon.eval.drive.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.eval.drive/unreachable-definitions-remain-in-src` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.fault.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.fault/carried-receipt-hangs-on-cyclic-cause-chain` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fault/fault-write-bound-is-wall-clock-under-contention` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fault/log-lines-print-values-outside-the-printer` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.fault/a-delivered-fault-names-the-normalizer-not-its-boundary` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.flow.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.flow/a-cancelled-future-is-not-exit` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.flow/blocking-konserve-io-bursts-the-mixed-pool-to-five-hundred-threads` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.flow/graph-report-channels-hold-whole-pass-reports` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.fn.analyzer.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.fn.analyzer/local-root-dependency-analysis-goes-stale` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn.analyzer/fork-source-lint-misreads-local-macro-bindings` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn.analyzer/analysis-runs-over-the-one-second-bound-without-a-task` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn.analyzer/each-analysis-resolves-the-lint-configuration-from-disk` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.fn.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.fn/tests-reaching-saturates` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/call-reach-refuses-every-gate` | already fixed; retired | Proof none. Entry and all declaration tags removed. Owner code and review-batch proof support retirement. |
| `seon.fn/a-used-class-makes-a-row-host-bound` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/agent-mistakes-read-as-missing-dependency-analysis` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/dependency-analysis-error-is-not-an-error` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/mention-closure-survives-beside-call-reach` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/gate-sets-map-arity-gets-a-supplied-database` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/caller-relint-follows-contract-consumers` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/report-identities-costs-a-second-per-call` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/fn-test-redefines-vars` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.fn/build-artifact-is-a-second-indexing-path` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/functions-without-tests-is-the-last-datalog-reach` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/cross-file-derivation-inputs-rely-on-dependent-relint` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/an-armed-first-population-takes-a-hundred-seconds` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/refused-publication-reads-have-no-redefinition-free-regression` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/contract-findings-has-no-scope` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/an-edit-rewrites-every-definition-below-it` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/backticked-docstring-references-have-no-index` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/batched-analysis-uses-one-namespace-row-per-run` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/a-renamed-refer-drops-its-call-edge` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/private-flag-disagrees-with-the-var-for-computed-metadata` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/a-dependent-keeps-no-edge-to-a-callee-defined-after-it` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/contract-facts-row-names-no-schema-in-its-refusal` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/an-empty-tasks-annotation-is-refused-without-its-shape` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/an-uncontracted-defn-is-admitted-without-its-warning` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/submitted-declarations-test-passes-a-projection-as-schema-targets` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/the-signature-reader-refuses-text-sci-evaluated` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/a-file-proof-two-compiled-callers-away-never-sees-the-branch-redefinition` | still valid; corrected | Proof green. Branch interpretation fixed; automatic selection still omits distant tests by design. Corrected intent exposes conflict with original any-depth completion claim; root must reconcile policy and proof. |
| `seon.fn/a-schema-resource-reference-is-no-edge` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.fn/gate-set-walks-the-whole-transitive-closure-to-select-two-levels` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.fs.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.fs/contract-attributes` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |

### resources/seon/tasks/seon.health.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.health/health-counts-a-red-proof-of-an-open-task-as-a-failure` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.html-views.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.html-views/html-views-identity-and-fault-pairs-are-red` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.instrument.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.instrument/hot-internals-pay-armed-validation` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.instrument/instrumentation-is-jvm-global` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.instrument/contract-refusal-carries-wrapper-bookkeeping` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.instrument/a-test-redefines-arming-jvm-wide` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.instrument/violation-construction-is-quadratic-in-explain-errors` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.instrument/a-map-for-a-sequential-crashes-the-refusal-builder` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.instrument/a-re-evaluation-test-leaves-a-replaced-root` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.instrument/a-contract-with-an-fn-ifn-cannot-arm-on-a-sci-branch` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.instrument/a-call-missing-an-argument-is-refused-as-the-wrong-argument` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.maintenance.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.maintenance/collection-marks-slowly-and-records-nothing` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.maintenance/root-verification-compares-a-moving-head` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.maintenance/a-test-request-collects-the-whole-store-under-the-exclusive-gate` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.maintenance/the-daily-collection-keeps-every-commit-of-a-live-branch` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.maintenance/the-dry-run-projects-seventy-minutes-for-a-three-minute-sweep` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.maintenance/a-commit-writes-forty-index-files-and-an-eval-request-commits-at-least-once` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.mcp.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.mcp/entrance-stages-select-no-tests` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.mcp/entrance-profile-counts-the-requested-evaluation` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.mcp/jvm-mode-replies-omit-time-calls-and-location` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.mcp/jvm-mode-defs-live-forever-with-no-owner` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.mcp/eval-clj-advertises-no-timeout` | still valid; corrected | Proof green. Corrected: schema proof green; installed bridge/client and platform round trip remain unproven. |

### resources/seon/tasks/seon.operator.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.operator/class-prune-deletes-a-directory-a-check-is-sharing-before-its-jvm-launches` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.operator/a-deep-check-queue-times-out-waiting-for-a-slot` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.operator/check-cannot-run-tests-of-host-bound-namespaces-against-a-candidate` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.operator/check-test-cannot-run-platform-tests` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.operator/check-test-reports-failed-for-an-excluded-long-test` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.operator/committed-source-same-sha-race` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.operator/status-prints-ok-while-the-cluster-is-still-starting` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.operator/operator-prints-whole-results` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.operator/check-rehearsal-cost-follows-drift-not-the-change` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.operator/check-decides-documentation-by-input-roots` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.operator/check-rehearses-an-indexer-change-with-the-old-indexer` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.operator/build-cache-keys-hash-printed-text` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.operator/a-candidate-that-babashka-cannot-load-passes-the-check` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.operator/a-command-with-help-answers-an-error-map-instead-of-its-usage` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.operator/a-restart-prunes-the-program-classes-a-starting-check-uses` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.operator/one-test-check-takes-74-seconds` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.operator/a-test-the-candidate-adds-is-not-run-by-the-check` | already fixed; retired | Proof green. Entry and all declaration tags removed. |

### resources/seon/tasks/seon.print.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.print/a-dig-form-through-a-list-index-reads-nil` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.print/multiline-literals-hide-line-breaks-from-layout` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.print/elision-forms-call-a-seon-helper` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.print/the-printer-law-lint-refuses-nothing` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.print/a-member-cut-costs-more-than-it-hides` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.print/the-display-budget-override-is-not-installed` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.print/a-child-cap-starves-small-elements` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.print/stored-or-compared-text-printed-under-ambient-bindings` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.print.value.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.print.value/a-contract-refusal-prints-a-generated-example-of-the-whole-schema` | stale; static reference | Proof none. Must-read not found in declaration census: seon.print.value/bounded-text. Retain underlying problem pending reference/behavior verification. |
| `seon.print.value/printed-copies-stored-or-sent-instead-of-values` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.print.value/ad-hoc-text-clips-outside-the-printer` | stale; static reference | Proof none. Must-read not found in declaration census: seon.print.value/bounded-text. Retain underlying problem pending reference/behavior verification. |

### resources/seon/tasks/seon.problems.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.problems/a-status-observation-costs-half-a-gigabyte` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.problems/ai-prose-returns-printed-text-not-data` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.profile.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.profile/slow-explanation-blames-the-agent-for-platform-time` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.profile/scope-wrappers-report-callback-time-as-work` | already fixed; retired | Proof green. Proof green and owner implementation corrected. Claimed and removed tags after repl-only-2 released its file. |
| `seon.profile/call-counters-are-jvm-wide-so-counting-tests-race` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.profile/the-bracket-leaves-a-thread-local-entry-per-cell-on-every-thread` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.profile/reading-the-profile-cells-costs-3-ms` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.profile/no-per-symbol-profile-and-zero-calls-reads-as-unmeasured` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.profile/transaction-stamps-store-hot-and-repeated-sets-nothing-reads` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.profile.measurement.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.profile.measurement/a-test-run-upserts-three-hundred-measurement-rows` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.program.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.program/a-docstring-edit-changes-the-definition-digest` | already fixed; retired | Proof none. Entry and all declaration tags removed. Owner code and review-batch proof support retirement. |
| `seon.program/program-attributes-trust-any-partition` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.program/a-defn-scans-every-projection-form-per-call` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.program/row-vocabulary-sweep` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.program/values-printed-to-order-or-compare` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.program/sci-scratch-definitions-refuse-their-produced-digest` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.ref.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.ref/references-resolves-each-name-with-its-own-pulls` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.ref/a-doc-section-test-takes-eighteen-seconds` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.ref/a-task-target-reads-as-an-unresolved-reference` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.ref/a-referenced-function-shows-no-callers-or-tests` | already fixed; retired | Proof green. Entry and all declaration tags removed. |
| `seon.ref/a-referenced-doc-path-reads-as-unresolved` | already fixed; retired | Proof green. Entry and all declaration tags removed. |

### resources/seon/tasks/seon.render.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.render/captured-invocations-blank-source-faces` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render/cold-renders-recompute-read-evidence` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render/face-vocabulary-remains-in-code` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.render/unit-sweep-retyped-non-map-render-inputs` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render/a-transaction-report-prints-its-runs-bookkeeping` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render/a-render-against-another-database-reads-the-context-custody` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render/an-unchanged-retained-call-selects-and-invokes-again` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render/a-nested-declared-value-prints-as-an-object` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render/declared-metadata-selects-a-value-that-no-longer-fits` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render/landing-reds-2-render-bow-outs` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |

### resources/seon/tasks/seon.render.ns.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.render.ns/function-source-lines-print-several-per-line` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.ns/ns-render-tests-are-red-on-head` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.render.test.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.render.test/a-test-entity-shows-its-failures-twice` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.test/a-failed-test-renders-in-28-ms` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.test/render-coverage-reads-the-retired-render-unknown-refusal-key` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.test/a-test-render-omits-what-the-test-asserts` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.render.transcript.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.render.transcript/delivered-errors-carry-no-result-handle` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.transcript/contract-runtime-duration` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |

### resources/seon/tasks/seon.render.value.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.render.value/continuations-cannot-navigate-derived-nodes` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.value/nested-renders-bypass-the-floor` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.value/test-messages-print-whole-worlds` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.value/a-lazy-seq-that-throws-shows-as-empty` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.value/an-uncut-string-node-carries-no-total` | stale; static reference | Proof none. Must-read not found in declaration census: seon.cluster/mcp-get-value. Retain underlying problem pending reference/behavior verification. |
| `seon.render.value/a-count-prints-as-an-entity-reference` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.value/a-short-identifier-string-is-cut-at-its-first-chunk` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.value/a-cut-operation-in-render-data-is-not-runnable` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.value/short-strings-in-pairs-are-cut` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.value/bounded-text-survives-in-the-render-namespace` | stale; static reference | Proof none. Must-read not found in declaration census: seon.print.value/bounded-text. Retain underlying problem pending reference/behavior verification. |
| `seon.render.value/cuts-cost-more-than-they-hide-across-siblings` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.value/failed-selection-escapes-the-profile-budget` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.value/printing-thirty-two-numbers-allocates-megabytes` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.value/transacted-passes-a-basis-descriptor-to-stored-collection` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.value/a-cut-set-or-map-names-the-whole-collection` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.render.var.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.render.var/a-deftest-reply-shows-only-its-var` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.var/reaching-tests-are-never-run-and-a-first-red-is-silent` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.var/the-offered-check-names-an-unquoted-symbol` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.var/an-unchanged-redefinition-lists-pending-tests` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.render.web.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.render.web/the-render-cache-keeps-every-debug-view` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.web/a-page-renders-with-a-refused-database` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.web/reconnect-wire-test-walks-the-whole-window` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.web/an-unreportable-page-failure-is-only-a-log-line` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.web/the-web-worker-executor-is-never-shut-down` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.web/stale-debt-tags-remain-in-web-and-schema` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.web/pages-print-values-instead-of-the-printer-html-face` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.render.web/the-built-stylesheet-is-stale-against-its-source` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.web/a-reconnect-refuses-a-pass-derived-before-it-connected-is-red` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.web/a-route-throw-no-longer-answers-a-bounded-500` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.web/unrelated-transaction-no-longer-reuses-the-debug-observation` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.render.web/contract-query-params` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |

### resources/seon/tasks/seon.repl.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.repl/a-slow-reply-names-nothing` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.repl/every-reply-repeats-open-faults` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.repl/a-slow-reply-names-counts-not-proportionality` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.repl/offered-operations-cannot-name-the-prior-result` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.repl/directory-render-returns-text-the-printer-clips-mid-form` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.repl/a-jvm-def-is-told-it-lives-in-sci` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.repl/a-def-bound-to-an-error-value-shows-only-the-var` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.repl/a-ref-from-another-branch-prints-with-the-wrong-identity` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.repl/every-def-repeats-the-same-persistence-note` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.repl/a-fn-valued-def-is-told-nothing-is-persisted` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.repl/printed-output-reaches-the-reply-unbounded` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.repl/the-out-elision-form-re-pulls-the-database` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.repl/reply-lacks-gc-commits-and-a-dialled-allocation-bound` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.schedule.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.schedule/a-scheduled-collection-runs-inside-the-schedule-proc-step-and-reports-as-in-world` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schedule/the-schedule-test-root-page-members-differ-after-a-firing` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |

### resources/seon/tasks/seon.schema.datahike.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.schema.datahike/decoding-pays-a-projection-scope-and-cache-claim-per-value` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schema.datahike/an-attribute-query-calls-logical-attribute-once-per-datom` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schema.datahike/the-datahike-fork-collapses-distinct-incomparable-values` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schema.datahike/a-slot-refusal-copies-the-whole-stored-value` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.schema.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.schema/agents-can-validate` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schema/register-works-only-top-level` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schema/the-projection-travels-ambiently` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schema/map-validation-throws-on-sorted-maps-with-foreign-keys` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schema/a-function-contract-changes-the-schema-projection` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schema/pull-selector-predicate-compiles-per-check` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schema/schema-registration-rechecks-the-whole-program-per-key` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schema/contract-check-on-three-files-exceeds-one-second` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schema/a-registration-refusal-carries-an-error-type` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schema/schema-only-derivations-are-rederived-per-projection` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schema/the-durable-projection-cache-evicts-a-commits-entry-across-tests-sharing-a-jvm` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schema/an-agent-contract-naming-an-unregistered-schema-is-refused-as-a-platform-fault` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schema/contract-function-arities-in` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |

### resources/seon/tasks/seon.schema.edn.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.schema.edn/declaration-stamp-has-no-caller` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.schema.edn/contract-resource-population` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |

### resources/seon/tasks/seon.sci.admit.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.sci.admit/large-printed-output-refuses-the-evaluation` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.sci.arm.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.sci.arm/changed-namespaces-flags-a-namespace-sci-merely-touched` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.arm/arming-prints-the-authored-contract-to-read-it-back` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.arm/arming-projection-is-rebuilt-per-caller-within-one-form` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.arm/an-unarmable-contract-breaks-the-definition` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.sci.branch-execution-test.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.sci.branch-execution-test/branch-proof-expects-a-retired-evaluation-row` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.branch-execution-test/interpreted-callers-proof-expects-host-bound-callers-to-refuse` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.branch-execution-test/branch-proof-live-context-lacks-the-private-test-helpers` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.sci.eval.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.sci.eval/doc-and-dir-erase-request-keys` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/a-contract-predicate-is-refused-both-ways` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/incremental-install-leaves-callers-on-the-jvm-definition` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/repl-namespace-changes-drift-from-the-ns-text` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/acquisition-rebuilds-the-whole-program` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/copied-vars-pin-old-wrapper-generations` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/shown-result-ignores-the-floor-refusal` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/clojure-test-parity-residue` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/branch-change-measured-against-loaded-program` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/analysis-order-differs-from-evaluation-order` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/forms-just-after-boot-cost-hundreds-of-ms` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/agent-form-writes-name-an-unwritten-evaluation` | already fixed; retired | Proof green. Entry and all declaration tags removed. |
| `seon.sci.eval/program-movement-is-checked-three-ways-per-form` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/a-result-handle-is-bound-at-three-sites` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/a-refused-run-leaves-its-program-in-the-context` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/interpreted-rows-lack-own-namespace-class-imports` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/copied-jvm-import-hides-sci-import` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/non-defn-definitions-store-no-entity` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/defmacro-in-let-loses-its-location` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/an-agent-cannot-read-the-private-code-it-must-change` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/replaced-and-acquisition-ms-have-no-producer` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/docstring-parts-reads-everything-after-example-as-the-example` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/the-defn-reply-prints-before-its-own-change-applies` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/value-storage-walks-serializes-and-stores-every-value-up-to-eight-megabytes` | still valid; tested | Proof green. Tagged proof green; remaining intent is broader than this proof, so retained. |
| `seon.sci.eval/doc-prints-contracts-expanded-with-keys-garbled` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/bare-source-and-apropos-do-not-resolve-in-an-agents-repl` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/find-doc-floods-out-with-thousands-of-tokens` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/a-fresh-contexts-result-handles-have-no-regression` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/live-results-are-held-for-the-contexts-lifetime` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/a-private-function-def-disappears-between-mcp-evaluations` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/a-stack-overflow-in-the-error-path-reaches-shown-failure-as-nil` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/dependency-namespaces-have-no-doc-or-dir-in-an-agents-repl` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/diagnostics-dig-forms-point-into-the-value` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/dir-shows-each-uncontracted-def-as-two-timestamped-errors` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/loading-source-arms-tens-of-thousands-of-calls` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/run-test-var-throws-on-an-sci-test-var` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/a-test-error-report-prints-the-whole-host-stack` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/a-test-report-erases-the-agents-stack-frames` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/an-arity-error-names-the-sci-closure` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/a-redefinition-walks-the-reverse-closure-with-its-test-reach` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/tests-reaching-turns-and-acquisition-are-red-at-head` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/a-program-change-walks-every-var-to-carry-the-private-layer` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/a-file-backed-namespace-has-no-refers-in-the-agent-context` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/test-support-has-no-vars-in-the-agent-context` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/an-unloaded-namespace-row-resolves-to-nothing` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/binding-a-host-dynamic-var-does-not-reach-host-code` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/source-of-a-library-var-answers-source-not-found` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/java-io-is-unresolved-in-an-agent-context` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/a-def-re-evaluated-in-a-fork-names-the-inherited-var-in-its-body` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/showing-an-infinite-lazy-seq-realizes-far-past-what-is-shown` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/install-row-throws-are-not-attributed-to-a-caller-or-an-error-record` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/a-trivial-form-makes-fifty-thousand-armed-calls` | still valid; tested | Proof green. Tagged proof green; remaining intent is broader than this proof, so retained. |
| `seon.sci.eval/a-fork-snapshots-every-program-var` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.eval/contract-context-projection` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |
| `seon.sci.eval/a-test-namespace-is-empty-in-an-agents-context` | already fixed; retired | Proof green. Entry and all declaration tags removed. |

### resources/seon/tasks/seon.sci.forgive.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.sci.forgive/a-prompt-marker-is-stripped-and-its-namespace-ignored` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.forgive/prose-after-the-last-form-is-dropped` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.sci.kernel.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.sci.kernel/the-memory-gate-names-in-flight-work-it-cannot-see` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.kernel/memory-pressure-crossed-during-test-rounds` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.kernel/sci-pmap-escapes-the-arm` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.kernel/a-failure-keeps-and-classifies-every-frame-of-every-link` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.kernel/an-agent-exception-has-no-error-type` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.kernel/interruptible-range-allocates-six-times-core-range` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.kernel/softly-held-index-nodes-count-as-memory-pressure` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.kernel/a-memory-refusal-is-answered-four-times` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.kernel/a-file-function-that-calls-slurp-cannot-be-redefined` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.sci.reader.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.sci.reader/reader-head-check-is-a-second-declaration-derivation` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.reader/schema-entities-lose-their-declaring-namespace` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.reader/a-value-deeper-than-the-readers-stack-cannot-be-read-back` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.sci.reader/prose-in-a-reply-is-evaluated-as-symbols` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.task.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.task/no-repl-entrance-creates-a-task` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.task/open-tasks-with-all-proofs-green-are-not-flagged` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.task/tasks-have-no-done-when-and-name-subjects-only-through-must-read` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.task/task-text-references-are-never-resolved` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.task/a-runtime-created-task-is-never-written-back` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.task/assign-cannot-see-a-red-proof-the-gate-will-not-land` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.task/an-assignment-reads-only-its-proofs` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |
| `seon.task/open-tasks-whose-proofs-are-green-are-never-retired` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.test-support.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.test-support/refusal-data-recognizes-errors-by-at-and-layer` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test-support/evaluate-answers-nil-for-a-refused-form` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test-support/a-test-member-connection-is-not-the-cluster-head` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test-support/file-backed-branch-without-republication` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test-support/assign-task-drops-its-refusal` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test-support/a-running-agent-world-has-no-task-home` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.test.accretion.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.test.accretion/schema-row-compiles-every-reached-form` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.test.cache.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.test.cache/dependency-revisions-are-not-test-inputs` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.test.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.test/a-changed-check-builds-a-world-per-request` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/a-check-with-nothing-to-run-answers-green` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/a-test-world-has-no-agent` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/property-members-pay-a-commit-per-step` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/tests-redefine-default-vars` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.test/reuse-only-selection-costs-a-second` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/one-test-run-costs-seconds-around-a-147-ms-body` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |
| `seon.test/an-agent-branch-test-run-cannot-branch-its-fresh-checkpoint` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/a-test-request-refuses-whole-when-one-namespace-has-no-eligible-tests` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/a-test-run-refusal-escapes-as-an-exception` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/release-and-rethrow-sites-repeat-instead-of-one-with-release` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/the-transitions-test-runs-six-to-seven-seconds-against-a-five-second-bound` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/red-tests-on-head-are-unclassified` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.test/test-release-closes-branch-connections-out-of-order` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/reds-on-head-9d6b392b6-in-task-reaching-tests` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.test/reds-on-head-178aa24e0-in-db-program-schema-tests` | not agent-sized; scope | Proof none. Split the listed sites/owners into separately proven slices. |
| `seon.test/a-check-answer-shows-digests-the-agent-cannot-use` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/a-stale-stored-definition-digest-refuses-every-test-world` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/an-agent-branch-sees-none-of-its-clusters-test-evidence` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/a-one-test-proof-check-allocates-a-gigabyte` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/a-core-test-in-a-namespace-the-loaded-program-lacks-is-refused-as-host-bound` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/a-check-compares-every-definition-digest-of-the-program-twice` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/a-schema-change-has-no-reaching-tests` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/a-repl-test-after-a-root-ns-form-has-no-executable-var` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/a-proof-that-evaluates-agent-code-reuses-its-red` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/destructive-reach-keeps-a-second-cache` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/a-malformed-task-tag-stops-the-whole-check` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/changed-definition-symbols-scans-whole-attributes-since-the-last-run` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/reach-attribute-advances-from-the-recording-report` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/an-sci-deftest-with-tasks-reports-no-executable-test-var` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/a-first-deftest-in-a-new-namespace-costs-seconds-of-test-step` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/a-named-check-drops-declared-long-tests-without-saying-so` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test/a-test-result-keeps-no-cost` | already fixed; retired | Proof green. Entry and all declaration tags removed. |

### resources/seon/tasks/seon.test.platform.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.test.platform/a-host-bound-change-has-no-proof-from-the-repl` | still valid; tested | Proof green. Recording proof green; it injects child results. Partial-identity candidate/reach agreement and actual changed host execution need end-to-end proof. |
| `seon.test.platform/a-test-the-branch-adds-is-not-eligible-in-the-check` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test.platform/a-refused-platform-check-names-no-diagnostic` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.test.runner.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.test.runner/failure-evidence-is-stored-as-text` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test.runner/a-rerun-failure-report-conflicts-and-faults-the-request` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test.runner/reach-over-every-test-takes-thirty-seconds` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test.runner/drift-prints-unbounded` | duplicate; retired | Proof none. Entry and all declaration tags removed. Merged into the sibling description; drift producer itself is now fixed. |
| `seon.test.runner/drift-is-blamed-on-concurrent-requests` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test.runner/a-green-re-record-sweeps-the-failure-cell` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test.runner/worker-drift-prints-its-sets-unbounded` | already fixed; retired | Proof none. Entry and all declaration tags removed. Owner code and review-batch proof support retirement. |
| `seon.test.runner/reach-entries-fill-half-the-cache-at-half-hit-rate` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test.runner/a-test-error-shows-runner-frames-not-the-throw-site` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test.runner/verified-recomputes-reach-digests-for-the-whole-program` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test.runner/tests-reaching-rebuilds-the-whole-program-reach-graph-per-change` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.test.runner/tests-reaching-misses-seon-id-id-from-its-calling-test` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.turn.batch.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.turn.batch/tests-read-forms-through-a-stage-no-entrant-uses` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn.batch/a-declared-var-call-stores-no-edge` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn.batch/analysis-uses-the-namespace-row-after-the-form` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn.batch/a-value-def-over-a-function-keeps-the-stale-function-row` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn.batch/an-unjoined-definition-write-is-silent` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn.batch/a-jvm-compile-error-shows-the-wrapper-message` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn.batch/a-core-unmap-with-a-surviving-caller-is-silently-undone` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn.batch/a-repl-namespace-row-keeps-neither-doc-nor-source` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn.batch/the-display-budget-has-no-feedback` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn.batch/a-failed-defn-after-in-ns-leaves-an-unbound-stub-var` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn.batch/redefining-a-file-function-in-seon-error-is-refused-by-its-digest` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn.batch/a-refused-remove-ns-still-removes-the-namespace-from-the-context` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.turn.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.turn/close-turn-is-a-third-commit` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-reload-between-forms-is-undetected` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-turn-pass-derives-its-work-twice` | still valid; tested | Proof green. Tagged proof green; remaining intent is broader than this proof, so retained. |
| `seon.turn/an-agent-form-is-read-again-by-each-consumer` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/receipt-attributes-are-a-hand-list` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-gate-exception-refuses-the-whole-run` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/analyze-settlement-is-unreached` | stale; retired | Proof none. Entry and all declaration tags removed. Named function and its calls are absent from src/test/script. |
| `seon.turn/a-nested-in-ns-loses-the-whole-run` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-refused-multi-form-settlement-returns-nothing` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/schema-redeclaration-revalidates-unchanged-attributes` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/the-unbound-check-walks-the-live-value-without-a-bound` | stale; retired | Proof none. Entry and all declaration tags removed. Named function and its calls are absent from src/test/script. |
| `seon.turn/a-refused-refusal-settlement-throws-past-the-reply` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-transient-write-refusal-parks-the-agent-until-restart` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-definition-is-settled-twice` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/row-tx-is-a-second-declaration-writer` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/eval-origin-has-no-producer-or-consumer` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/turn-continue-read-results-arrive-before-done-takes-six-seconds` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-deep-value-loses-the-run` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/each-trivial-evaluation-retains-two-hundred-kilobytes` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-deferred-wake-is-answered-by-silence` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-message-from-root-never-refills-the-turn-bound` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-prose-only-reply-evaluates-its-first-word` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-not-admitted-diagnostic-prints-its-write-attempt` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/an-identical-re-record-conflicts-with-itself` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-pass-reads-its-run-through-whole-branch-queries` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/turns-left-allocates-836-kilobytes-per-call` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/the-test-step-results-never-reach-the-eval-reply` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/an-executed-test-request-allocates-seven-hundred-megabytes-in-the-step` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/two-branches-share-reach-declarations-is-red-beside-the-turn-tests` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/the-reply-shows-unreached-only-for-names-this-request-defined` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-bounded-step-that-leaves-queued-tests-wakes-nothing-when-no-member-ran` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-red-proof-carries-no-reason` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/ai-attempt-stores-printed-copies-of-settings-and-usage` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-model-pass-never-sees-its-test-step` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-stop-during-model-context-assembly-records-errors` | still valid; tested | Proof red. Current tagged proof is red; exact symbols and reports are in audit-evidence.json. |
| `seon.turn/a-throwing-test-step-loses-its-cause` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |
| `seon.turn/a-host-bound-redefinition-runs-its-reaching-tests-in-sci` | already fixed; retired | Proof green. Entry and all declaration tags removed. |
| `seon.turn/a-root-request-runs-the-clusters-whole-drift-as-reaching-tests` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.turn.world.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.turn.world/fault-recording-refuses-cross-branch-custody` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

### resources/seon/tasks/seon.web.jvm.edn

| Task | Classification | Evidence / action |
|---|---|---|
| `seon.web.jvm/fetch-catch-all-hides-host-defects` | still valid; provisional | Proof none. No tagged proof; retained pending current reproduction. |

## Tasks added after the frozen census

These five declarations are preserved with their original evidence. They do not alter the frozen-window statistics. Their sources are included in audit-evidence.json. No tagged proof was present when inventoried.

| Task | Classification | Review limit / next proof |
|---|---|---|
| `my.agent/a-read-as-another-agent-blocks-its-retirement` | Not agent-sized | Separate named-branch observation from connection retirement; each needs its own regression. |
| `my.edit/exact-has-no-digest-for-a-file-over-eight-kilobytes` | Still valid, provisional | Reproduce the reported failure through the named owner before assigning. |
| `seon.cluster.source/a-second-write-back-conflicts-with-the-branchs-own-first` | Still valid, provisional | Reproduce the reported failure through the named owner before assigning. |
| `seon.repl/a-request-spends-seconds-outside-its-named-stages` | Still valid, provisional | Reproduce the reported failure through the named owner before assigning. |
| `seon.test.platform/a-schema-resource-change-cannot-be-proven-by-check-platform` | Still valid; static boundary | The platform path copies archive roots and writes declarations; prove a changed schema resource is included in the tested candidate. |
