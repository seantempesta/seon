---
type: research
status: analysis
created: 2026-10-17
---

# Merge cost: one candidate, one evidence lookup

Lane merge-cost-astra2. Read-only analysis, 2026-10-17. Source examined at
9e902642fc550663ba29b034623a13508a922b34. No implementation was changed.
The independent Opus report was not read; no other agent was contacted.

The minimal merge holds main and the chain, forms their whole program delta
on a branch of main, looks up evidence at that candidate, executes only
missing content identities, and merges the green candidate. Conflicts or red
results stay on that branch for `seon.step.merge/template`. Done is the same
evidence query on the task's branch. Neither operation needs a history of
test requests.

The mechanisms already exist: `seon.program/three-way`,
`seon.cluster.source/merge-members`, `seon.test.runner/reach-digests`,
`seon.test.runner/current-results`, and Datahike branches and writer
serialization. Preserve their incremental folds. Delete the policy and
repeated reconstruction surrounding them.

## Evidence limits and current problems

Default was ready but **faulted**: 18 untested-hot warnings, four agent
errors, 15 errored receipts, four malformed contracts, five open faults and
three unowned namespaces. Its JVM ran archive
d76806d3986cde93e65467befe5ac86a9e869f3e, not examined HEAD 9e902642f.
The loaded database program commit was
6ad20a6e-cf0e-5ea4-8998-c664d39c3b0e; these are different kinds of identity.

Every live probe used MCP eval_clj, JVM mode, explicit root/default and
`(seon.cluster.boot/connection "default")`. No root SCI request was made.
No definition, task, branch or source was written by a probe. Reading warms
normal caches; MCP records its own request bookkeeping. Acquired database
values were released. Default was not stopped or restarted.

Creating a branch, executing a test, publishing main and writing files would
violate this lane's read-only measurement scope. Their costs below are
**estimates**, not measured successes. Current-body measurements come from
the older loaded archive; HEAD numbers are explicitly commit-reported evidence.

The cache reported about 506 MB of its 512 MB budget; live heap was about
1.318 GB of a 4.295 GB cap. There were 3,323 indexed tests.
Allocation below is transient allocated bytes, **not retained memory**.
Warm observations are not cold-store bounds.

## 1. Minimal algorithm

### Hold values, identify content

Hold immutable B (merge base), S (source chain head), H (main head).
C is the complete program delta from B to S, including deletions. Runtime
facts have their schema-declared merge rules and a separate delta; they are
not additional program definitions or test seeds.

For each identity in C compare its base, source and main content:

- S equals B: no source change.
- H equals B: take S.
- H equals S: already agreed.
- Otherwise retain the three versions as an unmerged value.

This is `seon.program/three-way`. It reads main at C identities, without
enumerating all main moves M. A changed helper outside C can still change a
test's reach; candidate test identity handles that, not conflict comparison.

A test identity K includes its definition, complete reach content,
external/dynamic inputs and actual evaluator/platform inputs. It is not an
agent, branch, latest run, request shape or unrelated main commit.
Schemas, namespace resolution, file reads and task data count when observed.
An unchanged test definition can therefore have a new K.
“Unchanged definitions never rerun” must mean **unchanged test identity**;
otherwise a changed helper could not be tested through an unchanged test.

Retain the current reach digest plus input identity. Interpreted evidence
does not prove a differing compiled program. Conversely, salting all tests
with an unrelated loaded-program change creates needless invalidation.
The platform content actually used is the relevant input.

### Reactive definition write

1. Read/evaluate the form once through the REPL; analyze its source once.
   Compile and validate the contract at its producer through the installed
   Malli owners, using `malli.core/validator` / `malli.core/-instrument`.
2. The writer admits the new final owning values. Keep the installed warning
   for experimental missing contracts, recording the finding with that
   definition. Done reads the finding; it does not compile the contract again.
3. Fold the committed declaration delta into reverse reach and affected K.
   Include changed tests, task proofs and tests introduced or newly connected.
4. Look up required K. Reuse green **or red** evidence at that K. Execute
   missing K through the ordinary runner in candidate context forks.
   Pending is not green.
5. Record actual outcome and exit once. Subsequent observation is a lookup;
   reuse needs no new test admission, synthetic completion or report copy.

A write creates the obligation. An evaluation with no relevant change creates
none. Multiple definitions in an evaluation fold once over its committed
result; an overwritten intermediate definition need not cause redundant work.

“Validate once” means once per **new owning value**. A merged component,
reference relation or schema environment may be new although its individual
declarations were separately admitted. Its writer validates that composition.
Revalidating unchanged historical components adds nothing.

### Done and merge are one lookup

Conceptually, without a new API:

    observe(candidate, obligations, evidence)
      = {unmerged,
         admission findings at candidate content,
         required K -> recorded result or unknown}

One fold judges that value: unresolved conflicts, incomplete declarations,
missing proof obligations, unknown results or red required results prevent
completion. Complete admission and all required results green complete it.
Context, done, merge and the merge agent's problem display read that value.

**Done:** candidate S, this task's proofs and tests required by its change.
**Merge:** candidate U = H plus whole source delta, obligations of U.
Only candidate and consequently some K values differ.

An intermediate Prove task may prove that a regression is red: the red is
input to a passing proof about the regression, not an exemption that makes a
failing required test green. Installed next-proof and red-at-base rules differ
from the requested target. Implementation must amend TASK-CHAIN `2.4 and
supply red regressions before changing those semantics.

### Merge operation

1. Hold H, S, B; read C and do the small three-way comparison.
2. Branch from H through `seon.db/branch!`. Transact source delta plus unmerged
   entries; acquire/fork the context at that immutable candidate.
3. Let the same reactive write mechanism observe/test U. Reuse source/main
   evidence for identical K. New work is missing K, normally where main's
   moves affect reach.
4. Conflicts/non-green evidence keep U for the existing merge agent. Its
   edits use the same write/test path.
5. Otherwise advance main with accepted delta and parents, using the native
   writer's expected basis for **the whole tested H**. If main moved, derive
   the next candidate from that new main through the ordinary operation.
   No lock, retry loop or second verdict tier wraps the obsolete candidate.
6. Write the accepted files through exact-source write-back.

Removing `seon.cluster.source/declarations-held-tx` requires the native held
basis operation; deleting concurrency semantics is not a simplification.

“Only M∩R reruns” assumes complete, accessible source evidence and unchanged
remaining inputs. Main can introduce a test that newly reaches the source
change; task inputs can change; evidence can be missing. Each yields new or
unknown K even if the old source test list was unchanged. Reuse a current
red as red; do not rerun it until it happens to pass.

### Durable evidence, not just a warm cache

`seon.test/carried-results` has a shared content cache, but on a miss searches
source branches obtained from main's agent facts. Cold cost depends on branch
count/history. Caffeine is not the durable authority.

For this merge, the smallest solution carries held source and main evidence
views into candidate lookup. They are already the merge's inputs. Reuse can
reference the producing commit/result instead of copying executed members.
Broader arbitrary-branch reuse needs a real durable content index at its
owner. Do not claim constant-time cold lookup merely because warm cache lookup
is constant-time. No new mutable registry is proposed.

## 2. Cost table

C = changed identities; R = required/affected tests; M = main moves since B.
M∩R means main's changed **inputs inside transitive test reach**, not an
intersection of entity and test names. Q ≤ R is tests missing K on U.

Other unavoidable terms: D changed datoms/source bytes; E affected dependency
edges/SCCs; F changed file bytes; A runtime-fact datoms; G ancestry visited for
merge base; Bn evidence branches searched; T(Q) actual body/fixture cost.
C and R alone bound neither fanout, entity size, history nor test time.

Estimates assume warm local indexes/classes, C≈5–50, R≈5–60, ordinary small
declarations, hundreds to low thousands of affected edges, files totaling
10–200 KB, and no new host-bound platform boot. R80 was also measured.
MB means **allocation**, not retained branch storage. Estimates are hypotheses
for measurement, not established performance budgets.

| Component | Lookup/new work; reads and writes; proportionality | Estimated ms / allocated MB | Today's path and read-only evidence |
|---|---|---:|---|
| Write and test a definition | New source analysis/contract proportional to bytes+D; fold affected E, R evidence lookups. One declaration transaction, actual result transactions; no writes for reuse. | Infrastructure 5–80 / 1–30 **plus T(Q)**. Simple bodies might be 1–20 ms; integration bodies can be seconds. | Request folding exists; `seon.turn/prove-turn` still enters `seon.test/run-request` selection/admission. HEAD commit-reported six-change requests: 156–177 ms / 201–216 MB; first ~1,066 ms / 1,316 MB. |
| Find the change | Cached branch delta; update from new lineage datoms, return C. No write. Cold cost depends on G/D. | Warm .05–2 / .01–1; cold ancestry not bounded by C. | `seon.program/own-changed-identities` C8: .07–.11 ms/.011 MB, zero counted read entrances. Cached merge-base probe C7: 7.31 ms/4.71 MB, one read entrance. |
| Detect conflicts with main moves | Three content reads per identity, compare C. No need to enumerate M. No writes until candidate transaction. | .5–8 / .3–5 | Three digest maps plus `seon.program/three-way` C8 warm .78–.82 ms/.991 MB, six read entrances; C7 first 6.96/4.04, warm .66–.78/.973. |
| Build merge branch | Branch pointer/roster write; share H indexes; transact D+A and conflicts. Index paths proportional to changed datoms, not program size. | 2–40 / .2–10 for small branch+transaction/context fork; device dependent. | `seon.cluster/candidate-gate!` also acquires context, writes merge facts and launches gate. Prepare derives file proofs beforehand. No live write measurement. |
| Select tests with new digests | Reuse contribution/reach folds; R K lookups; recompute reached changed inputs M∩R, including new main tests. Hits write nothing. | .2–8 / .1–5 | C8/R8 members .05–.08 ms/.024 MB; reach .13–.17/.124–.128. R80 reach .41–.45/~1.034. `seon.test/select` R8 first 32.49/27.43; warm 4.68–6.05/8.10, 27 read entrances. Request history reconstruction dominates the simpler lookup. |
| Run them | Q=0: zero bodies and zero test branch/admission/result writes. Otherwise execute Q in candidate forks; record real outcomes/exit. | 0 for Q=0; otherwise T(Q) and actual allocation; no honest fixed bound from C,R. | Resolution/body/record owners remain. Current request machinery can admit and copy carried members around reuse. Isolated platform execution is a separate physical cost. |
| Judge | Same observation for done/merge; fold R results and produced admission findings. No extra DB reads beyond evidence, no writes. | .1–2 / .05–1 | R8 current-results first 3.53/4.52/46 reads, then .12–.15/.117/0 reads. R80 warm .12–.18/.159/0 reads. Partial done .80–1.20/.764–.933/6 reads. Full done also derives publication/contract coverage separately from merge. |
| Write main | Native writer over accepted D+A and parent identities at held H. O((D+A) log index size); no test execution/re-judgment. | 2–40 / .2–10 for small ordinary delta; durability must be measured. | `seon.cluster.source/accept-merge!` reopens branches, re-judges, reconstructs replacement and checks declaration/task bases. Keep necessary runtime-fact merge semantics and writer serialization. |
| Write back files | Read F, splice declarations, analyze new candidate byte content once, install and commit git objects/ref. Write F, not whole core. | 10–250 / 2–50 warm for 10–200 KB; lint/process startup can dominate. | `seon.cluster.source/publication`, `seon.cluster.source/prepare-merge!` and `seon.cluster.source/write-back!` derive proofs; `seon.merge/indexed!` analyzes installed files again. Hold bytes+analysis keyed by content. Not live measured. |
| Resolve conflicts | Read conflicting B/S/H identities; ordinary edits plus affected E/R/Q. Write edited values and actual results once. | Bookkeeping 1–10 / .1–3 per small edit plus analysis/tests and unbounded model/human time. | Unmerged entries and `seon.step.merge/template` already give the right model; no extra merge protocol or test path needed. |

Warm comparison + reach + result lookup C8/R8 is about 1–2 ms.
That is **not** a 1–2 ms merge: it excludes candidate transaction, context
acquisition, files, main write and new tests. The first experiment should
target tens of milliseconds of database work for an ordinary no-new-K merge,
measuring files separately, rather than budgeting another whole-program gate.

### Measured evidence

Read counts are instrumented read entrances, not physical I/O or datoms.
`seon.profile/measure` allocation covers its calling thread; whole MCP requests
include additional printer/instrumentation/cache work. No GC was reported
inside the measured table operations.

| Held branch | C | R | Current-results ms, three observations | Allocated MB |
|---|---:|---:|---|---|
| chain-fd3ba8395135 | 8 | 8 | 3.53, .12, .15 | 4.519, .117, .117 |
| chain-42e0e4c46b71 | 7 | 80 | .18, .14, .12 | .159, .159, .159 |
| chain-ce9d, discovery probe | 7 | 1 | 3.87, .17, .14 | 2.719, .113, .113 |

C includes branch namespace declarations; it is not today's curated merge-list
count. R80 was already warm, so it does not establish cold scaling.

MCP result/e0307042ce2fa holds the C8/R8 and C7/R80 probe: evaluation 91 ms;
whole request 133.7 ms, 32,144 calls, 175.9 MB.
Result/e5d1b6262a6ef holds select/partial-done: evaluation 73 ms;
whole request 125.6 ms, 139.9 MB. Its judged value had one proof, no task,
merge-identities, lint or next proofs. It returned
`:seon.source/red-test-error`, not green full-chain completion.
First selection's dominant measured self-time was
`seon.db/vector-query-call`: 15 calls, about 23 ms.

A broader discovery request allocated 809.3 MB in 309.8 ms (269 ms evaluation).
This is an allocation concern, not retained-memory growth or a localized cause.
It is not the cost of a merge component. This read-only report preserves that
finding for root; it creates no task data.

Two authored probe mistakes returned normal errors: zero-arity
`seon.cluster/loaded-commit`, and a digest-map request missing required
max-datoms. Corrected requests produced the measurements above. The mistaken
requests cost 57.8 ms/8 MB and 77.8 ms/42.9 MB. They are not merge defects.

### Recent change-cost commits: preserve the folds

Git history since 2026-10-16 was read for all eight target files.
These numbers are **commit-message reports**, not new reproductions:

| Commit | Mechanism | Reported before → after |
|---|---|---|
| 5b71e4670 | Fold changes; batch stale lookup | Repeated 24-test request 420.4→285.8 ms; unchanged 120.8→35.6; verdict 19.67→5.37 |
| d76806d39 | Reuse task-reader program across bookkeeping | First 1,364.5→1,232.7 ms; repeated 598.8→496.5; unchanged ~36 |
| 6f1d9b0cc | Per-identity membership contribution/edge fold | Six-change request ~255–292→223–247 ms |
| b95b83af0 / 538e731c3 | Seek missing reach evidence; native undecoded search | Avoid unrelated result-history decoding/scanning |
| 51c745cde | Fold held results, fetch only missed reach | Lookup 2.51→1.19 ms; six-change lookup ~1.14–1.38→.77–1.05 |
| 169422141 | Cache declared names by content | Verdict ~3.71–4.37→3.25–3.52 ms; ~6.02→5.24 MB |
| 9e902642f | Incremental structural program-map digest | Six-change request ~212.6–266.5→156.3–177.3 ms; ~302.5–400.2→201.1–215.5 MB |

Last commit first request still ~1,066 ms/~1,316 MB. Reported old-generation
occupancy rose 595.6→683.6 MiB: no demonstrated retained-memory improvement.
Remove repeated requests while retaining these incremental owners.

## 3. Classification of today's path

### Counting rules and totals

The eight full files contain 855 top-level defn/defn- forms and 23,694 physical
lines. The census here includes all source/merge/test/runner functions and the
cluster gates, context step/done, turn proving/end and agent db-flow functions
enumerated in Appendix A. It includes shared publication/execution support;
not all 2,864 lines of source.clj are merge overhead.

**447 functions / 11,035 function-span lines: 380 needed, 15 guards/fences,
45 second derivations, seven evidence bypasses, zero established dead.**
Function spans include contracts/docstrings and exclude namespace/constants
and comments outside functions. Forms were read with Edamame, not counted by
a regex.

One primary category per function. Needed means its responsibility survives,
not its whole current implementation. Second derivation means consolidate,
not remove the responsibility. Bypass includes a second program/actor proof
path that defeats legitimate reactive reuse. Dead requires established lack
of callers; none was established. Private does not imply dead.

| Namespace / selected part | Needed | Guard | Second derivation | Bypass | Dead | Function lines |
|---|---:|---:|---:|---:|---:|---:|
| seon.cluster.source | 110 | 6 | 11 | 1 | 0 | 2,636 |
| seon.merge | 23 | 0 | 1 | 0 | 0 | 609 |
| seon.cluster gates | 2 | 0 | 1 | 0 | 0 | 104 |
| seon.test | 69 | 0 | 19 | 4 | 0 | 3,273 |
| seon.test.runner | 105 | 9 | 3 | 0 | 0 | 2,611 |
| seon.context step/done | 27 | 0 | 4 | 0 | 0 | 503 |
| seon.turn proving/end | 30 | 0 | 5 | 2 | 0 | 824 |
| seon.cluster.agent chain/db-flow | 14 | 0 | 1 | 0 | 0 | 475 |

### Largest deletions and their replacement

1. **Partial merge curation.** `seon.cluster.source/merge-seeds`,
   `seon.cluster.source/merge-list` and `seon.cluster.source/merge-changes`
   select a delta subset; `seon.cluster.source/unselected-reach` checks what
   it omitted. Full chain delta and schema partition replace both.
   `seon.cluster.source/agent-owned` adds stewardship filtering beside data
   ownership. Preserve namespaces, deletions and reference integrity at the
   delta/writer owner.

2. **Repeated candidate judgment.** `seon.cluster.source/publication`,
   `seon.cluster.source/results-verdict`, `seon.cluster.source/merge-verdict`,
   `seon.context/step-observation` and `seon.cluster.agent/done-judged`
   construct overlapping answers. Publication keys main's commit, so unrelated
   bookkeeping can force candidate file work; accept repeats judgment.
   Make installed `seon.cluster.source/done-refusals` fold one held observation.
   Definition admission owns contract work; reach owns coverage.
   Malformed contracts remain incomplete via their recorded findings.

3. **Request-history selection.** `seon.test/select` is about 445 lines:
   run/membership facts, reconstructed baselines, policy/evaluator/scope
   eligibility, then reach lookup. `seon.test/selection-facts`,
   `seon.test/behavior-changed` and admission member/scope projections duplicate
   information a held obligation value supplies. Retain real input/evaluator
   identity, replace prior-request-shape derivation.

4. **Copied reuse and legacy result representation.**
   `seon.test/carried-member` / `seon.test/carried-reports` create reused
   outcomes/reports under fresh admission; retain producing commit/result
   references instead. `seon.test.runner/record-latest-tx` and
   `seon.test.runner/failure-replacement-tx` maintain latest-on-test beside
   admitted evidence. They have callers: convert them and delete the old
   representation in the same slice, not as presumed dead code.

5. **Checks of already admitted data.**
   `seon.test.runner/admitted-members` reads membership history and validates
   completed counts again. Admission/completion writers own those facts once.
   `seon.test.runner/program-written-since?`,
   `seon.cluster.source/task-basis-refusal`,
   `seon.cluster.source/task-basis-held-tx` and
   `seon.cluster.source/declarations-held-tx` compare later state with prepared
   state. Hold tested values and use native writer basis instead of stamps.

6. **Ambient test repair.** `seon.test.runner/ambient-snapshot`, drift checks
   and schema restoration protect global JVM state after the fact. Ordinary
   test writes belong in branch contexts; truly host-bound/destructive work
   uses the existing isolated runner. Remove snapshots/restores with the
   ambient writes, never ahead of them. `seon.test/host`, destructive reach
   explanations and actual exit recording remain needed. Timeout is not exit.

7. **Old-red and alternate proof paths.** `seon.test/held-red`,
   `seon.test/fork-point-red`, `seon.cluster.source/red-at-merge-base` and
   `seon.turn/run-merge-base` excuse current reds by judging old programs.
   Reuse red as red and resolve it. `seon.test/verified?`'s ordinary arity asks
   the right question; its program-digest arity supplies another proof identity.
   `seon.turn/platform-proof` should use the same evidence mechanism while
   retaining physically required isolated execution.

8. **Repeated file proof.** Keep `seon.cluster.source/proven-file`, schema/task
   editing and `seon.cluster.source/write-back!`. Candidate bytes plus analysis
   are reusable immutable values. `seon.merge/indexed!` should consume them.
   Replacing `seon.cluster.source/call-with-commit-lock` requires native git
   object/ref and index ownership, not removal of a lock around the same
   shared mutable index.

Retained `seon.cluster.source/prepare-merge!` (~157 lines) mixes comparison,
curation, files, gate and judgment; `seon.test/run-request` is hundreds of
lines. Their N labels do not exempt that work.
`seon.test/resolve-test` stays: resolving the actual interpreter's test is
necessary. Actor-based evaluator selection in `seon.test/root-agent?` is the
bypass; the real host/SCI boundary is not.

`seon.merge/squash-merge` retains schema-specific runtime-fact merging.
Its A datoms cannot be hidden inside C. Assignment/chain scans and first-parent
walks should read the already-held assignment and chain references. Real task
and end facts still reach main; evidence need not become copied program.

### Error/refusal census

An error is not automatically a fence. Conflicts, unknown evidence, invalid
new input, storage failure and a body without exit are real data. A repeated
read-time check can share a type with a necessary write-time failure.

Appendix B classifies **31 distinct explicitly referenced concrete
error/unknown schema names: 28 needed, three second derivations; no established
dead, guard-only or bypass-only broad types**. Guard/bypass counts belong to
the specific producers/reasons. Source.clj's lexical inventory has 16 entries
but one is clojure.test's unqualified :error event: 15 are schema names under
this rule, including `:seon.test/result-unknown`. This explains the difference
from the assignment's 16 refusal/error types. Error unions and attributes
carrying errors are not counted as concrete variants.

## 4. Smallest design and phased deletion

Use existing owners, no version-two namespace or cache:

- `seon.program`: partition, delta and three-way values.
- `seon.test.runner`: reach identities, durable evidence and existing folds;
  `seon.test` executes missing identities.
- `seon.cluster.source/done-refusals`: the common held-observation judgment.
- `seon.merge`: candidate branch, native main transaction, runtime facts,
  merge-step integration. `seon.cluster.source` keeps source publication and
  exact-source write-back.
- `seon.cluster.agent/db<->flow` reacts to facts and advances/resolves, with
  no independent proof derivation.

| Responsibility within audited scope | Estimated final function lines |
|---|---:|
| Merge/done coordination and context/db-flow adapters | 250–450 |
| Delta/reach/obligation/evidence folds and reads | 650–950 |
| Shared execution, fixtures, reports and exit | 1,500–2,100 |
| Source publication/bootstrap support | 700–900 |
| File translation, installation and git | 450–650 |
| **Total** | **3,550–5,050** |

Rounded estimate **3,600–5,100 function lines**, about 5,900–7,400 fewer than
11,035. Merge/done glue itself: **250–450 lines**. These are estimates, not a
measured diff or a claim the entire core becomes 10,000 lines. Owners outside
the census, tests, schemas and non-function overhead remain. Moving code
between files is not deletion.

### Proof vocabulary

Names checked in source. “Stay green” below is an implementation requirement,
not a claim this lane ran the entire set.

- D1: `seon.cluster.source-test/done-and-merge-judge-one-member-list`
- D2: `seon.cluster.source-test/done-and-merge-judge-a-red-reaching-test-the-same-way`
- D3: `seon.cluster.source-test/done-and-merge-judge-a-reaching-test-with-no-current-result-the-same-way`
- D4: `seon.cluster.source-test/member-fold-equals-the-full-merge-list`
- M1: `seon.merge-test/a-merge-reuses-the-chain-branchs-green-at-an-unchanged-reach`
- M2: `seon.merge-test/root-merges-main-forward-into-a-chain-branch`
- M3: `seon.merge-test/the-gate-runs-a-file-reading-test-against-the-candidate-and-refuses`
- M4: `seon.merge-test/a-copy-of-an-unchanged-branch-is-empty`
- F1: `seon.cluster.db-flow-test/a-publication-wakes-the-pass-that-deletes-the-merged-branch`
- F2: `seon.cluster.db-flow-test/a-refused-final-merge-keeps-its-merge-branch-and-holds-a-merge-step`
- F3: `seon.cluster.db-flow-test/a-done-merge-step-merges-its-branch-onto-main`
- F4: `seon.cluster.db-flow-test/a-merge-step-green-at-its-start-ends-done-with-no-model-call`

Each phase amends its owning spec, adds/tightens its red proof first, converts
every caller/contract/schema in the same commit, and publishes/reloads on a
disposable live-store branch through the normal check before root's batched
restart. No phase leaves a renamed Var's callers for later.

| Phase | One bootable slice and what disappears | Required new red proof | Existing merge / done / db-flow proofs |
|---|---|---|---|
| 1. One observation | Consolidate done/merge/context at current done owner, consume held membership/evidence/admission findings. Delete duplicate judgment assembly after all callers convert. Preserve installed semantics initially. | Same candidate gives identical named missing/red results everywhere; second observation adds no body calls/evidence writes. | M1,M4 / D1,D2,D3 / F1,F4 |
| 2. Reactive work at write | Query K before creating run; preserve folds; convert legacy result readers/writers together; reference carried evidence instead of copying. | No-op evaluation writes no run; same K on candidate executes zero; main helper move reruns only affected tests; new main test included. End-to-end `seon.profile/measure` proof. | M1,M3 / D1,D3,D4 / F1,F4 |
| 3. Whole delta, native basis | Amend TASK-CHAIN/MERGE-STEP; delete curation/subset checks, task/declaration stamps, old-red exemptions and repeated accept verdict with their callers. Keep real unmerged values/native expected basis. | Deletion, equal edits, direct conflict, main move outside C inside reach, new main test, main moved at commit; base red cannot excuse current red; stored missing-contract finding remains incomplete. | M1,M2,M4 / D1,D2,D3 / F1,F2,F3,F4 |
| 4. Actual compartments | Ordinary tests use branch context; physical platform execution records same evidence. Delete ambient snapshots/restores, actor proof identity, read-time membership re-admission with producer conversion. | Own schema/context change leaves sibling unchanged; timeout does not claim exit; interpreted green cannot prove differing compiled body. | M1,M3 / D1,D3 / F1,F4 |
| 5. One file proof | Reuse held candidate bytes/analysis; convert indexed! consumer; delete re-analysis. Native git index/ref ownership precedes removal of custom lock. Preserve durable accepted-writeback recovery. | One analysis per new bytes; moved file base is real conflict; failed install continues from accepted value without rerunning tests. | M3,M4 / D1,D3 / F1,F3 |

Phase 3 converts/retires old partial-merge assertions in
`seon.merge-test/a-merge-carries-only-what-its-tests-reach` and
`seon.merge-test/a-prepared-merge-carries-its-merge-list-and-a-staying-conflict-refuses`
in the same commit. Convert D4's rows to whole-delta membership while retaining
its fold-equals-full law. D1 also contains red-at-base and deferred-member
exception rows: replace those rows in phases 3 and 4 while preserving its
same-observation law. Keeping obsolete semantics to keep tests green would
defeat the cut.

Central proof:
`seon.cluster.db-flow-test/a-three-task-chain-ends-merged-with-no-hand-act`,
counting model calls and hand acts, plus parent/change no-new-K and
one-main-reach-move probes. This long named test belongs in the central round,
not repeated in every lane. Other long integration proofs above run by
explicit var under their declared bounds. None is claimed green here.

### Dependency seams

Datahike pin ee3f8ec817e5310b3a0ea5adb8904214183568c0:

- `datahike.versioning/branch!` shares immutable indexes, writes metadata;
  `seon.db/branch!` is Seon's boundary.
- `datahike.versioning/merge-base` walks ancestry. Recompute for held commit
  pairs on a cache miss; cost G, not C.
- `datahike.versioning/merge!` accepts transaction data, parents and
  `:datahike/expected-basis-t`. It does **not** calculate Seon's semantic
  three-way merge. Keep `seon.program/three-way`.
- `datahike.versioning/force-branch!` supports expected-current-commit, but
  pointer replacement is not the required fact transaction. Prefer the already
  exposed `seon.db/merge!` entrance with native basis.

Malli pin dd13ff81139159778edb0a67b491eccc0baa991d:
`malli.core/validator` and `malli.core/-instrument` own compiled contracts.
Recompute on changed schema/function content, not each completion observation.

SCI pin eb3290d5c5ed72133ae1947492ecd324413868ed:
`sci.core/fork` / `sci.core/init` own contexts. A branch fork is distinct from
rebuilding the program. Host-bound code is the physical exception, not a
second ordinary merge evaluator.

## 5. Verification and foreign boundary

No implementation changed. Full working-tree lint:

    clj-kondo --lint src script test --cache false --parallel

completed in 6,123 ms: **0 errors, 1,833 warnings**, exit 2 for warnings.
This is not a zero-finding run.

The report commit uses bin/lane commit merge-cost-astra2 with D1, D3, M4 and
F1 as exact named tests. Their result is the commit gate output/trailer;
the phase list is not a substitute for running its proofs during implementation.

Foreign boundary: reference-code/konserve had tracked work,
reference-code/http-kit nested untracked work, and
tmp/test-data-assertions/proofs.edn was modified. Existing research artifacts
were untracked. None was edited/staged/included. The gate checks HEAD plus
only this report, with pinned dependencies.

Historical reds reported by 51c745cde against 538e731c3, run 7d3733b5c867:
`seon.test.selection-test/named-selection-reuses-green-members-by-reachable-content`
and
`seon.test.selection-test/selection-derives-bases-obligations-and-exact-symbol-reach`.
Their fixtures pass an uncommitted database to provenance. They were not rerun
or accepted as green here. No code fix or default reset is authorized by this
report.

## Appendix A. Exhaustive scoped function census

N needed, G guard/fence, D second derivation, B evidence bypass, X established
dead. Every scoped function occurs once below. Category reasons and mixed
responsibilities are explained above.

### seon.cluster.source

**N (110):** `seon.cluster.source/refuse!`, `seon.cluster.source/require-committed!`, `seon.cluster.source/snapshot`, `seon.cluster.source/digest`, `seon.cluster.source/capture-paths`, `seon.cluster.source/path-digests`, `seon.cluster.source/discover-paths`, `seon.cluster.source/dependency-digests`, `seon.cluster.source/loaded-dependencies`, `seon.cluster.source/classify-paths`, `seon.cluster.source/snapshot-test-input-digest`, `seon.cluster.source/stored-path-digests`, `seon.cluster.source/current`, `seon.cluster.source/commit-database`, `seon.cluster.source/database`, `seon.cluster.source/changed-datoms`, `seon.cluster.source/changed-identities`, `seon.cluster.source/unresolved-report!`, `seon.cluster.source/deleted-identities`, `seon.cluster.source/scratch-branch`, `seon.cluster.source/resolve-population`, `seon.cluster.source/retire-scratch!`, `seon.cluster.source/publication-error`, `seon.cluster.source/stale-publication-error`, `seon.cluster.source/record-results-at-head!`, `seon.cluster.source/record-results!`, `seon.cluster.source/index-tasks!`, `seon.cluster.source/publication-input-digest!`, `seon.cluster.source/publish!`, `seon.cluster.source/refused`, `seon.cluster.source/merge-base`, `seon.cluster.source/merge-base-database`, `seon.cluster.source/branch-merge-base`, `seon.cluster.source/replacement`, `seon.cluster.source/held-digests`, `seon.cluster.source/moved-digests`, `seon.cluster.source/release!`, `seon.cluster.source/contribution`, `seon.cluster.source/remove-contribution`, `seon.cluster.source/add-contribution`, `seon.cluster.source/fold-members`, `seon.cluster.source/member-contributions`, `seon.cluster.source/merge-members`, `seon.cluster.source/unmerged-tx`, `seon.cluster.source/entry-identity`, `seon.cluster.source/unmerged`, `seon.cluster.source/with-unmerged`, `seon.cluster.source/compare-commit`, `seon.cluster.source/compare-branch`, `seon.cluster.source/unmerged-count`, `seon.cluster.source/lint-refusal`, `seon.cluster.source/carried`, `seon.cluster.source/prepare-merge!`, `seon.cluster.source/red-test-verdicts`, `seon.cluster.source/red-test-refusal`, `seon.cluster.source/gap-symbols`, `seon.cluster.source/uncontracted`, `seon.cluster.source/uncontracted-refusal`, `seon.cluster.source/uncovered-refusal`, `seon.cluster.source/versions`, `seon.cluster.source/unmerged-refusal`, `seon.cluster.source/done-refusals`, `seon.cluster.source/accept-merge!`, `seon.cluster.source/write-back-refusal`, `seon.cluster.source/root-refusal`, `seon.cluster.source/sha-256`, `seon.cluster.source/spliced`, `seon.cluster.source/declaration-digests`, `seon.cluster.source/file-digests`, `seon.cluster.source/stage!`, `seon.cluster.source/install-staged!`, `seon.cluster.source/namespace-path`, `seon.cluster.source/file-rows`, `seon.cluster.source/conflicts`, `seon.cluster.source/committed-bytes`, `seon.cluster.source/definition-order`, `seon.cluster.source/proven-file`, `seon.cluster.source/schema-path`, `seon.cluster.source/same-value?`, `seon.cluster.source/relaid`, `seon.cluster.source/schema-entry-edit`, `seon.cluster.source/schema-file-text`, `seon.cluster.source/schema-entry-digest`, `seon.cluster.source/proven-schema-file`, `seon.cluster.source/filed-declaration`, `seon.cluster.source/task-path`, `seon.cluster.source/task-entry-node`, `seon.cluster.source/task-file-text`, `seon.cluster.source/proven-task-file`, `seon.cluster.source/identity-namespace`, `seon.cluster.source/new-path`, `seon.cluster.source/change-path`, `seon.cluster.source/proven-files`, `seon.cluster.source/lint-errors`, `seon.cluster.source/write-back-commits`, `seon.cluster.source/main-parent`, `seon.cluster.source/merge-accepted`, `seon.cluster.source/merge-paths`, `seon.cluster.source/write-back!`, `seon.cluster.source/git`, `seon.cluster.source/commit-refusal`, `seon.cluster.source/commit-message`, `seon.cluster.source/committed-merge`, `seon.cluster.source/commit-locked`, `seon.cluster.source/commit!`, `seon.cluster.source/ledger-file`, `seon.cluster.source/covers?`, `seon.cluster.source/waiting-paths`, `seon.cluster.source/claim-paths!`, `seon.cluster.source/release-claim!`.

**G (6):** `seon.cluster.source/unselected-reach`, `seon.cluster.source/declarations-held-tx`, `seon.cluster.source/agent-owned`, `seon.cluster.source/task-basis-refusal`, `seon.cluster.source/task-basis-held-tx`, `seon.cluster.source/call-with-commit-lock`.

**D (11):** `seon.cluster.source/merge-list`, `seon.cluster.source/merge-seeds`, `seon.cluster.source/merge-changes`, `seon.cluster.source/tested-task-basis`, `seon.cluster.source/file-refusal`, `seon.cluster.source/publication`, `seon.cluster.source/green-result?`, `seon.cluster.source/contract-gaps`, `seon.cluster.source/uncovered-functions`, `seon.cluster.source/results-verdict`, `seon.cluster.source/merge-verdict`.

**B (1):** `seon.cluster.source/red-at-merge-base`.

### seon.merge

**N (23):** `seon.merge/collision-remap`, `seon.merge/cluster-entity?`, `seon.merge/add-count`, `seon.merge/attribute-tx`, `seon.merge/member-merge`, `seon.merge/shown-state`, `seon.merge/squash-merge`, `seon.merge/conflict-task!`, `seon.merge/merged`, `seon.merge/merge-tx`, `seon.merge/assigned-task`, `seon.merge/chain-row`, `seon.merge/merged?`, `seon.merge/assignment-row`, `seon.merge/acceptance`, `seon.merge/merge-onto`, `seon.merge/merge!`, `seon.merge/absent-references`, `seon.merge/carried-entities`, `seon.merge/merge-main!`, `seon.merge/checkout`, `seon.merge/merge-commit`, `seon.merge/publish!`.

**D (1):** `seon.merge/indexed!`.

### seon.cluster

**N (2):** `seon.cluster/isolated-run`, `seon.cluster/candidate-gate!`.

**D (1):** `seon.cluster/reaching-run`.

### seon.test

**N (69):** `seon.test/unknown`, `seon.test/failure-text`, `seon.test/failure-message`, `seon.test/with-test-loader`, `seon.test/task`, `seon.test/test-var-meta`, `seon.test/apply-seeds!`, `seon.test/attempt-request-context`, `seon.test/measured-body`, `seon.test/bounded-result`, `seon.test/development-root`, `seon.test/destroyers`, `seon.test/selection-revisions`, `seon.test/destructive-reach`, `seon.test/destructive-paths`, `seon.test/destructive-exclusion`, `seon.test/host`, `seon.test/host-text`, `seon.test/selection-refusal`, `seon.test/selection-read!`, `seon.test/schema-consumers`, `seon.test/namespace-readers`, `seon.test/selection-seeds`, `seon.test/reaching-selection`, `seon.test/reaching`, `seon.test/selection-admission`, `seon.test/admission-refusal!`, `seon.test/admit-run`, `seon.test/reach-digest`, `seon.test/recorded-result`, `seon.test/current-results`, `seon.test/stale-in`, `seon.test/stale`, `seon.test/resolve-loaded`, `seon.test/resolve-test`, `seon.test/owned-symbols`, `seon.test/world-refusal`, `seon.test/carry-world-projection!`, `seon.test/open-world!`, `seon.test/world-base`, `seon.test/bindings`, `seon.test/branch-delta`, `seon.test/branch-changes`, `seon.test/branch-tests`, `seon.test/request-changes`, `seon.test/changes`, `seon.test/clean-request-handle`, `seon.test/request-refusal`, `seon.test/host-exclusions`, `seon.test/member-result`, `seon.test/isolated-members`, `seon.test/declared-bound-ms`, `seon.test/requested-bound-ms`, `seon.test/green?`, `seon.test/recorded-outcomes`, `seon.test/source-branches`, `seon.test/carried-results`, `seon.test/report-tempid`, `seon.test/file-two-outcomes!`, `seon.test/loaded-program`, `seon.test/namespace-declarations`, `seon.test/interpreted-namespaces`, `seon.test/arm-first-loads!`, `seon.test/run-request`, `seon.test/request-view!`, `seon.test/record-thrown!`, `seon.test/run`, `seon.test/unfulfilled`, `seon.test/tally`.

**D (19):** `seon.test/changed-since-green`, `seon.test/selection-facts`, `seon.test/definition-digests`, `seon.test/behavior-of`, `seon.test/behavior-changed`, `seon.test/changed-definition-symbols`, `seon.test/green-members`, `seon.test/select`, `seon.test/admission-members`, `seon.test/admission-scope`, `seon.test/admission-member-values`, `seon.test/digest-differences`, `seon.test/program-overlay-tx`, `seon.test/checkpoint-head`, `seon.test/prepare-checkpoint!`, `seon.test/change-base`, `seon.test/binding-lost?`, `seon.test/carried-member`, `seon.test/carried-reports`.

**B (4):** `seon.test/root-agent?`, `seon.test/verified?`, `seon.test/held-red`, `seon.test/fork-point-red`.

### seon.test.runner

**N (105):** `seon.test.runner/var-reference?`, `seon.test.runner/class-loader?`, `seon.test.runner/var-symbol`, `seon.test.runner/event-symbol`, `seon.test.runner/report-options`, `seon.test.runner/throwable-signature`, `seon.test.runner/event-signature`, `seon.test.runner/report-value`, `seon.test.runner/message-text`, `seon.test.runner/failure-message`, `seon.test.runner/ensure-result`, `seon.test.runner/reported-actual`, `seon.test.runner/report-pair`, `seon.test.runner/failure-report`, `seon.test.runner/report-identity`, `seon.test.runner/failure-identity`, `seon.test.runner/capture-event!`, `seon.test.runner/report-error!`, `seon.test.runner/report-event!`, `seon.test.runner/assertionless-failure`, `seon.test.runner/declared-limit-ms`, `seon.test.runner/duration-failures`, `seon.test.runner/capture-and-report-event!`, `seon.test.runner/captured-results`, `seon.test.runner/run-vars!`, `seon.test.runner/run-var!`, `seon.test.runner/run-selected-tests`, `seon.test.runner/resolve-loaded`, `seon.test.runner/reach-read!`, `seon.test.runner/reach-keywords`, `seon.test.runner/reach-out`, `seon.test.runner/reach-node`, `seon.test.runner/reach-schema-node`, `seon.test.runner/reach-weight`, `seon.test.runner/reach-revision-key`, `seon.test.runner/reach-store`, `seon.test.runner/reach-attribute`, `seon.test.runner/reach-declarations`, `seon.test.runner/reach-fixed-point`, `seon.test.runner/reached-graph`, `seon.test.runner/reach-graph`, `seon.test.runner/reach-successors`, `seon.test.runner/reach-components`, `seon.test.runner/reach-component-digests`, `seon.test.runner/test-reach-digest`, `seon.test.runner/reach-index`, `seon.test.runner/reach-names`, `seon.test.runner/reach-unavailable`, `seon.test.runner/reach-reverse`, `seon.test.runner/differing-entities`, `seon.test.runner/changed-reach`, `seon.test.runner/reach-entries`, `seon.test.runner/static-reach-digests`, `seon.test.runner/closure-digests`, `seon.test.runner/program-content-digest`, `seon.test.runner/task-input`, `seon.test.runner/task-identity`, `seon.test.runner/task-inputs`, `seon.test.runner/task-reach-digests`, `seon.test.runner/reach-digests`, `seon.test.runner/reach-memberships`, `seon.test.runner/reach-closure`, `seon.test.runner/tests-reaching`, `seon.test.runner/program-digest-read-attributes`, `seon.test.runner/program-revisions`, `seon.test.runner/program-revisions-digest`, `seon.test.runner/program-digest`, `seon.test.runner/provenance`, `seon.test.runner/report-entity`, `seon.test.runner/execution-refusal!`, `seon.test.runner/execution-read`, `seon.test.runner/execution-members`, `seon.test.runner/worker-identity`, `seon.test.runner/thrown-artifact`, `seon.test.runner/prepare-failures!`, `seon.test.runner/complete-members`, `seon.test.runner/record-tx`, `seon.test.runner/member-reports`, `seon.test.runner/recorded-member-results`, `seon.test.runner/harness-closure`, `seon.test.runner/function-digests`, `seon.test.runner/executed-roots`, `seon.test.runner/retain-results!`, `seon.test.runner/commit-results!`, `seon.test.runner/exit-tx`, `seon.test.runner/record-exit!`, `seon.test.runner/thrown-error`, `seon.test.runner/record-interrupted!`, `seon.test.runner/run-result-facts`, `seon.test.runner/results-from-facts`, `seon.test.runner/result-read-error`, `seon.test.runner/run-results`, `seon.test.runner/newest-results`, `seon.test.runner/latest-results`, `seon.test.runner/members-at-reaches`, `seon.test.runner/result-at-reach`, `seon.test.runner/dynamic-call-inputs`, `seon.test.runner/current-reaches`, `seon.test.runner/recording-attributes`, `seon.test.runner/result-revisions`, `seon.test.runner/changed-result-data`, `seon.test.runner/fold-current-results`, `seon.test.runner/current-results`, `seon.test.runner/red-result?`, `seon.test.runner/red-at-current-reach?`.

**G (9):** `seon.test.runner/live-cluster-schema-states`, `seon.test.runner/projection-form-keys`, `seon.test.runner/restore-live-cluster-schema!`, `seon.test.runner/schema-restore-drift`, `seon.test.runner/ambient-snapshot`, `seon.test.runner/set-drift`, `seon.test.runner/ambient-drift`, `seon.test.runner/program-written-since?`, `seon.test.runner/admitted-members`.

**D (3):** `seon.test.runner/failure-replacement-tx`, `seon.test.runner/record-latest-tx`, `seon.test.runner/designed-red?`.

### seon.context

**N (27):** `seon.context/proof-failures`, `seon.context/declared-tests`, `seon.context/head-entries`, `seon.context/proofs`, `seon.context/held-task`, `seon.context/task-references`, `seon.context/by-family`, `seon.context/merged`, `seon.context/step-before`, `seon.context/chain`, `seon.context/send-backs`, `seon.context/change`, `seon.context/step-members`, `seon.context/current-results`, `seon.context/step-verdict`, `seon.context/refusal-data`, `seon.context/shown-refusal`, `seon.context/step-section`, `seon.context/task-done?`, `seon.context/branch-end-summary`, `seon.context/end-summary-facts`, `seon.context/recorded-end-summary`, `seon.context/stopped-error`, `seon.context/end-summary-tx`, `seon.context/ended`, `seon.context/step-start`, `seon.context/attempt-start`.

**D (4):** `seon.context/proof-status`, `seon.context/step-status`, `seon.context/branch-changes`, `seon.context/step-observation`.

### seon.turn

**N (30):** `seon.turn/task-end`, `seon.turn/end-task-call`, `seon.turn/record-end!`, `seon.turn/end-task!`, `seon.turn/attempt-running?`, `seon.turn/proved-unanswered?`, `seon.turn/step-due?`, `seon.turn/held-task`, `seon.turn/task-obligations`, `seon.turn/task-proofs`, `seon.turn/attempt-t`, `seon.turn/step-proved?`, `seon.turn/changed-functions`, `seon.turn/run-reaching`, `seon.turn/pending-syms`, `seon.turn/executed`, `seon.turn/request-summary`, `seon.turn/summary`, `seon.turn/shown-tests`, `seon.turn/executed-syms`, `seon.turn/test-costs`, `seon.turn/ran-something`, `seon.turn/step-limit-ms`, `seon.turn/step-reds`, `seon.turn/test-step`, `seon.turn/queue-of`, `seon.turn/merge-queue`, `seon.turn/summary-queue`, `seon.turn/continue-queue`, `seon.turn/step`.

**D (5):** `seon.turn/step-tests`, `seon.turn/run-proofs`, `seon.turn/run-branch`, `seon.turn/prove-turn`, `seon.turn/branch-due`.

**B (2):** `seon.turn/platform-proof`, `seon.turn/run-merge-base`.

### seon.cluster.agent

**N (14):** `seon.cluster.agent/chain-ends`, `seon.cluster.agent/bail-chain!`, `seon.cluster.agent/attempt-verdict`, `seon.cluster.agent/chain-bails`, `seon.cluster.agent/bail-chains!`, `seon.cluster.agent/refusals-verdict`, `seon.cluster.agent/advance-chains!`, `seon.cluster.agent/chain-finishes`, `seon.cluster.agent/refusal-section`, `seon.cluster.agent/merge-step`, `seon.cluster.agent/merge-chains!`, `seon.cluster.agent/finish-chains!`, `seon.cluster.agent/run-pass!`, `seon.cluster.agent/db<->flow`.

**D (1):** `seon.cluster.agent/done-judged`.

## Appendix B. Error and refusal inventory

Categories are primary current responsibilities, not permission to delete
all uses of a broad schema. N is needed data; D duplicates a decision made
elsewhere. The concrete schema inventory is:

| Schema | Category | Responsibility |
|---|---|---|
| `:seon.cluster.wake/unroutable-declarations-error` | N | Cannot route actual declarations; needed Flow boundary. |
| `:seon.config/error` | N | Invalid configuration at its write/load boundary. |
| `:seon.db/invalid-read-error` | N | A database read did not supply a value. |
| `:seon.db/pull-budget-error` | N | A bounded read could not supply the requested value. |
| `:seon.db/transaction-refused-error` | N | Writer did not admit the proposed transaction. |
| `:seon.git/commit-error` | N | Git write did not complete. |
| `:seon.instrument/registration-error` | N | New contract instrumentation failed. |
| `:seon.program/digest-map-refusal` | N | Content comparison could not obtain its inputs. |
| `:seon.schema/missing-projection-error` | N | Required schema environment is absent. |
| `:seon.sci.eval/interpretation-error` | N | Actual interpretation failed. |
| `:seon.sci.eval/row-acquisition-error` | N | Actual branch definition acquisition failed. |
| `:seon.sci.kernel/memory-pressure-error` | N | Execution resource boundary. |
| `:seon.source/conflict-error` | N | Real three-way or unresolved candidate conflict. |
| `:seon.source/lint-error` | D | Read produced analysis findings; remove repeat lint at done/merge, retain original analysis failure. |
| `:seon.source/publication-error` | N | Source publication did not complete. |
| `:seon.source/red-test-error` | N | Current required evidence is red. |
| `:seon.source/stray-red-error` | D | Changed tests belong in the same obligations, not a second red category. |
| `:seon.source/test-evidence-error` | N | Required evidence absent/incomplete; remove obsolete subset/task-stamp reasons. |
| `:seon.source/uncontracted-error` | D | Read definition admission finding; remove contract recompilation at judgment. |
| `:seon.source/write-back-error` | N | File representation/installation failed; specific obsolete reasons below. |
| `:seon.test.run/immutable-error` | N | Writer owns one immutable admission/outcome; remove read-time revalidation. |
| `:seon.test.run/retracted-definition-error` | N | Execution result refers to a definition absent at the recording boundary. |
| `:seon.test.run/unavailable-error` | N | Required execution/evidence cannot be obtained. |
| `:seon.test.seed/preparation-error` | N | Explicit fixture preparation failed. |
| `:seon.test/admission-error` | N | Actual execution admission failed; old repeated eligibility reasons retire. |
| `:seon.test/execution-error` | N | Actual execution or evidence production failed. |
| `:seon.test/not-runnable-error` | N | Resolved definition cannot execute as a test. |
| `:seon.test/resolution-error` | N | Test resolution failed in its actual context. |
| `:seon.test/result-unknown` | N | Required K has no completed usable evidence. |
| `:seon.test/selection-error` | N | Requested obligation cannot be resolved; history-selection reasons retire. |
| `:seon.test/unknown-error` | N | Unexpected test boundary error remains explicit. |

The referenced `:seon.test/host-error` and `:seon.db.write/error` are unions,
not two more concrete variants. `:seon.test.member/error`,
`:seon.test.run/stopped-error`, `:seon.agent/end-test-error`,
`:seon.turn.loop/test-error` and `:seon.task/test-error` carry errors; they are
not variants. clojure.test's :error is an event category.

### Legacy source publication refusal rules

Eleven literal rule tags are separate from the schema count above:

| Tags (fully qualified) | Classification |
|---|---|
| `:seon.cluster.source/root-absent`, `:seon.cluster.source/restart-needed`, `:seon.cluster.source/source-absent` | N: source/host prerequisites. Host-bound dependency changes cannot be interpreted away. |
| `:seon.cluster.source/populate-unresolvable`, `:seon.cluster.source/scratch-schema-refused`, `:seon.cluster.source/source-population-refused`, `:seon.cluster.source/source-seal-refused` | N: actual publication steps failed. |
| `:seon.task/findings-refused`, `:seon.task/index-refused` | N: task source indexing/read failed. |
| `:seon.cluster.source/publish-readback-failed` | N: publication could not obtain its result. A repeated equivalence derivation inside it can still be removed. |
| `:seon.cluster.source/stale-publication` | G: replace a later state comparison with a held publication input and native writer basis. |

Totals: ten N, one G. Also, the generic source refusal constructor uses
`:seon.program/history` (N, no common ancestor/readable history) and
`:seon.program/scope` (G, curated subset admissibility). These are failure
locations in the legacy shape, not new concrete error schemas.

### Write-back refusal reasons

Twelve values of `:seon.source/write-back-refused`:

| Reasons | Classification / owner |
|---|---|
| :root-refused | N: explicit filesystem destination is an input. |
| :commit-absent | N: accepted source value is unavailable. |
| :file-absent, :file-changed, :conflict | N: actual destination/base conflicts, resolved at the file writer. |
| :not-analyzable, :not-equivalent | N: new representation failed its one producer proof. |
| :install-failed | N: installation failed. |
| :span-mismatch | N: proposed source edit does not identify its source span; preserve exact editing semantics. |
| :no-replacement | G: zero delta should be a no-op value, not a refusal tier. |
| :one-file | G: namespace/file identity belongs at declaration/write-back ownership, not another late filter. |
| :row-changed | G: hold the accepted immutable declaration value; do not compare a later place with it. |

Totals nine N, three G. This does not authorize ignoring a genuinely changed
destination file or applying an edit at the wrong span.

### Test reason tags

Selection/admission/resolution/execution-refusal fields are open qualified
keywords, not closed enums. The following are the literal reasons in their
constructor calls on the audited path (not expected/offending payload values):

| Literal reasons | Classification |
|---|---|
| `:seon.test/identity-unresolved`, `:seon.test/namespace-unresolved` | N: the requested executable obligation is absent. |
| `:seon.test/cluster-required`, `:seon.test/cluster-unavailable`, `:seon.test/cluster-mismatch` | N at execution entrance: explicit custody, no ambient connection. |
| `:seon.test/coverage-unknown`, `:seon.test/input-evidence-unavailable`, `:seon.test/analysis-unknown`, `:seon.test/provenance-unknown` | N as producer findings/unknowns; read the findings instead of re-admitting immutable facts. |
| `:seon.test/classpath-incompatible`, `:seon.test/classpath-unavailable` | N: physical host resolution failure. |
| `:seon.test/policy-unscoped` | N at user entrance: requested work has no scope. Internal merge already holds obligations. |
| `:seon.test/invalid-basis`, `:seon.test/program-mismatch` | G in repeated selection/admission comparisons; hold the actual database/context pair. Never allow a different executed program to count as evidence. |
| `:seon.test/edge-schema-mismatch` | G: validate the edge representation once at publication, not every selection. |
| `:seon.test/fixture-excluded` | G: replace a selection filter with properly owned test/input data; fixtures are not extra program obligations. |
| `:seon.test/population-unknown` | N at the producer if membership/outcome is missing; G in admitted-members history/count revalidation. Classified N for this reason census because both uses exist. |
| `:seon.test.run/immutable` | N at admission/completion writer; remove read-time duplicate. |
| `:seon.test/claim-replaced`, `:seon.test/report-conflict` | N at completion writer: result must belong to the actual body/claim. Preserve writer serialization. |
| `:seon.test/sym` used by stale lookup as execution-refusal | D: malformed legacy reason stamp; the original read error is already the answer. |

Totals for these 21 distinct reason tags: 16 N, four G, one D. A field's
openness means this is a literal call-site census, not a proof that no dynamic
callee can return another reason.

Fixture application additionally has :invalid-value and :write-refused
(two N input/write failures). Missing-context refusals returned through the
test-world boundary use the already-listed unavailable/error schemas; they
do not add an alternate merge verdict.

## Appendix C. Exact read-only profiling forms

Run with MCP eval_clj mode jvm, agent root, cluster default. Branches are
historical inputs from this observation; their availability is not promised
forever. Acquired database values are released. These forms perform no
explicit database writes.

### Component measurements

```clojure
(let [connection (seon.cluster.boot/connection "default") database (seon.db/db connection)
 summarize (fn [m] (assoc (select-keys m [:seon.profile/ms :seon.profile/alloc-bytes :seon.profile/reads :seon.profile/calls :seon.profile/gc-ms]) :answer-count (count (:seon.profile/value m)) :error (:seon.error/type (:seon.profile/value m))))]
 (mapv (fn [name] (let [b (seon.db/branch-database database name) base (seon.db/commit-database b (seon.cluster.source/branch-merge-base b))]
  (try (let [c (seon.program/own-changed-identities base b seon.cluster.source/merge-bound) r (vec (sort (:seon.source/members (seon.cluster.source/merge-members b c #{}))))]
   {:branch name :C (count c) :R (count r) :measures
    (mapv (fn [_] {:change (summarize (seon.profile/measure #(seon.program/own-changed-identities base b seon.cluster.source/merge-bound)))
     :members (summarize (seon.profile/measure #(seon.cluster.source/merge-members b c #{})))
     :three-way (summarize (seon.profile/measure #(apply seon.program/three-way (mapv (fn [d] (seon.program/digest-map d (assoc seon.cluster.source/merge-bound :seon.program/identities c))) [base b database]))))
     :reach (summarize (seon.profile/measure #(seon.test.runner/reach-digests b r)))
     :results (summarize (seon.profile/measure #(seon.test.runner/current-results b r nil)))}) (range 3))})
   (finally (seon.cluster.source/release! [base b]))))) [:chain-fd3ba8395135 :chain-42e0e4c46b71]))
```

### Selection and partial done measurement

```clojure
(let [connection (seon.cluster.boot/connection "default") database (seon.db/db connection)
 b (seon.db/branch-database database :chain-fd3ba8395135)
 base (seon.db/commit-database b (seon.cluster.source/branch-merge-base b))
 c (seon.program/own-changed-identities base b seon.cluster.source/merge-bound)
 r (:seon.source/members (seon.cluster.source/merge-members b c #{}))
 results (seon.test.runner/current-results b (vec (sort r)) nil)
 run (second (:seon.test/run (first (vals results))))
 judged {:seon.source/members r :seon.source/results (vec (vals results)) :seon.task/proofs #{(first (sort r))}
         :seon.program/identities c :seon.test.run/id run :seon.test.run/branch :chain-fd3ba8395135}
 brief (fn [m] (assoc (select-keys m [:seon.profile/ms :seon.profile/alloc-bytes :seon.profile/reads :seon.profile/calls])
                 :error (:seon.error/type (:seon.profile/value m))
                 :value-count (count (:seon.profile/value m))
                 :hot (take 3 (:seon.profile/ranked-by-self m))))]
 (try {:measurements (mapv (fn [_] {:select (brief (seon.profile/measure #(seon.test/select
 {:seon.db/db b :seon.test.run/cluster [:seon.cluster/name "default"] :seon.test.run/policy :named
 :seon.test/identities r :seon.test.run/evaluator :interpreted})))
 :judge (brief (seon.profile/measure #(seon.cluster.source/done-refusals b judged)))}) (range 3))
 :judgment (mapv :seon.error/type (seon.cluster.source/done-refusals b judged))}
 (finally (seon.cluster.source/release! [base b]))))
```
