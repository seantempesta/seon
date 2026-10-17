---
title: "Merge hybrid: one merge, reactive evidence, one judgment"
type: plan
status: "owning plan for the merge and testing redesign, written 2026-10-17 by Fable from the owner's rulings (ac12586a5), analysis A (merge-audit-opus.md, 525e7aecd) and analysis B (merge-cost-astra.md, 44219e306 and ced9b12c0); every disputed claim verified against the code or a read-only JVM probe on default"
---

# Merge hybrid (2026-10-17)

**Phase status** (each implementing commit updates this line):
P0 pending · P1 pending · P2 pending · P3 pending · P4 pending · P5 pending · P6 pending · P7 pending · P8 pending ·
P9 pending · P10 pending · P11a pending · P11b pending · P11c pending · P11d pending

The rulings bind (`merge-rulings.md`). Where A or B disagrees with them, the ruling wins; where A and B disagree with
each other, §2 says which is right and how it was checked. Line counts below are function spans measured on HEAD
ced9b12c0 (a top-level form to the next, trailing blank lines and comments excluded); the spans are within one or
two lines of A's per-function figures and B's `select` 444 / `run-request` 415.

---

## 1. The design in a few lines

1. **The one merge.** When a chain's last task is done, root's `db<->flow` forks main's head H into a merge branch S,
   transacts the chain's **whole delta** Δ (every program entity changed since the merge base O, deletions included,
   by `seon.program/three-way`) plus the facts squash (`seon.merge/merge-tx`), and judges S with the same function the
   step used on the chain branch. No conflict and nothing refused: ONE transaction on main, parents #{B, S}, with
   Datahike's own basis fence (`:datahike/expected-basis-t` = H's basis, `reference-code/datahike/src/datahike/writing.cljc`,
   enforced inside the writer; `seon.db/transact!` already passes it with `:parents` to `merge-db!`). A conflict, or a
   red, keeps S and appends the merge step (`seon.step.merge/template`) for the merge agent. Main moved in between:
   the writer refuses, and the next pass merges again from the new head, with the unchanged reach answered by lookup.
   No merge list, no selection, no second judge, no second transaction, no writer-side digest rechecks.
2. **Reactive testing keyed by reach digest.** Every eval request that wrote a program entity runs **one** test request
   on the branch: the stale members of `seon.cluster.source/merge-members` (the tests reaching Δ, the tests Δ defines,
   the task's proofs), where stale means "no result at its current reach digest" (`seon.test/stale`,
   `seon.test.runner/reach-digests`). Results are recorded keyed by reach digest. An unchanged reach never reruns, on
   any branch; a result is a lookup wherever the digest matches.
3. **Done and merge are one judgment.** `seon.cluster.source/done-refusals` over `[branch judged]` is the only judge;
   one builder (`seon.context/step-observation`'s judged input, taking a database and a task) feeds it at every eval
   request (the block), at the attempt's end (root's done) and over S (the merge). It refuses exactly: an unmerged
   entry; a member without an executed result (unknown, not green); a red member not among the next task's proofs,
   whether or not main holds it red; a changed function with a contract gap; a changed function no green test
   reaches (last task only); the one file proof's clj-kondo error. Nothing re-validates what contracts or the writer
   guarantee.
4. **In-environment runs; a separate JVM only when reached.** Members run in the agent's SCI fork on its branch. A
   member the environment cannot run (declared `:seon.test/platform`, reaching a `:seon.fn/destroys` owner, or
   reading the program as files, `seon.test/isolated-members`) runs in one throwaway JVM cloned from HEAD plus the
   branch's proven files (`seon.test.platform/check-files!`), from the **step** that reached it, keyed by the
   candidate's content; the merge then finds that result by digest and boots only when main moved its reach. Today
   `seon.cluster/isolated-run` already boots only when a deferred member exists (verified; A's "a JVM boot per
   merge" is not what the code does), but it does so at the merge and offers the agent `my.test/check-platform` by
   hand; the hybrid moves the boot to the step and deletes the offer.
5. **The running "what will merge" summary.** The block's `:seon.context/changes` lists every function, test, schema,
   namespace and task entity Δ holds (added, changed, removed), each with its test status from the judged head
   entries (green, red, pending, unknown, uncovered) and the form that removes it (`my.program/ns-unmap!`). No
   `:seon.context/stays`: everything shown merges, so the agent prunes scratch before it is done.

---

## 2. The cost table

Sizes: C changed entities (5–50), R members reaching them (10–80 measured), M main's moves since O, R_new members
whose reach includes a move of M (new digest on S), F files to write back, Q isolated members reached.

Probe (read-only, JVM mode on default, 2026-10-17, result `result/e4373ed796f0c`, 81 ms): two kept chain branches,
C = 7 with R = 80 and C = 7 with R = 1: `merge-members` 0.06–0.08 ms warm; `reach-digests` 0.58 / 0.17 ms warm
(R = 80 / 1), 1.0 MB / 34 KB; `current-results` 0.13–0.24 ms. Default was `:faulted` (5 open faults predating this
lane), ready in 10.4 s, heap 1.3 of 4.3 GB.

| # | Component | Lookup / new work | Proportional to | Expected | A said | B said | Verdict |
|---|---|---|---|---|---|---|---|
| 1 | Merge base O | Lookup: `seon.cluster.source/merge-base` (Datahike's commit-graph walk), cached by the id pair | commits since the fork (G) | < 5 ms warm | < 5 ms; a second algorithm (`seon.merge/assignment-row` → `registry/first-parent-where`) exists | 7.3 ms / 4.7 MB first, cached after; cost is G, not C | Both. One algorithm for program and facts (ONE MERGE BASE ruling); the first-parent walk goes (P4). |
| 2 | Find Δ | Lookup: `seon.program/own-changed-identities`, cached by content | C · log N | ~1 ms warm | 8–16 ms cold | 0.07–0.11 ms warm | Both (cold vs warm). Computed once per content; today recomputed by five callers (A §5). |
| 3 | Conflicts | New: digests of Δ at O, B, H + `seon.program/three-way` | 2·C avet lookups | 1–5 ms | 2.5–4.5 ms | 0.66–0.82 ms warm | Agree. Main is never enumerated. |
| 4 | Merge branch S | New: `seon.cluster.registry/fork!` of H, one transaction of Δ's replacement + unmerged entries + the facts squash | C entities + facts | 20–60 ms | 15 / 23 / 36 ms at C = 5 / 20 / 50 | 2–40 ms estimate | Agree. The fork is a pointer, never a copy. (B names `registry/branch!`; the forking entrance the gate uses is `registry/fork!`.) |
| 5 | Select T on S | Lookup: the `merge-members` contribution fold, keyed by content revision | C seeds; main's moved call edges | ~0.1 ms warm, 40–65 ms cold | 43–65 ms cold / 0.05 ms warm | 0.05–0.08 ms warm | Verified 0.06–0.08 ms warm. Keep the fold; delete the four other reach derivations (A §5). |
| 6 | Reach digests on S | Lookup, except members reaching M's moves | R lookups + R_new digests | R × ~0.01 ms warm | 67–312 ms (R = 10–73), "10–60× expected" | 0.13–0.45 ms warm | **Verified 0.17–0.58 ms warm.** A measured the first computation after a nuke; that is paid once per reach content, not per request. A's out-of-scope finding is a cold-start latency, not a per-request defect. |
| 7 | Run | New: R_new only, in the SCI fork; Q isolated members in one JVM when reached and new | R_new × T(body); Q: one boot | 0 when main did not move; otherwise R_new × 50–500 ms; a boot 32–39 s | "a JVM boot per merge", 38.9 s | "a separate physical cost" | **Code: the boot is conditional on a deferred member today (`seon.cluster/isolated-run`).** A's e2e number came from a proof that reaches a file-reading test. B is right. The ruling moves the boot to the step; the merge reuses it. |
| 8 | Judge | Lookup: R results + contract gaps of Δ + the cached file proof | R + C (+ F once per content) | < 15 ms; file proof 2 ms cached, ~280 ms / 0.7 GB per new file content (TASK-CHAIN §2.4) | `contract-gaps` 5–12 ms; judgment built four or more times per request | 0.8–1.2 ms partial done; "publication keys main's commit, so unrelated bookkeeping can force candidate file work" | Agree on cost. One judge, called once per observation (P1, P2). The file proof is computed once per (changed file content) and consumed by done, merge and write-back (P7), keyed by the files' content rather than main's commit, which answers B's point. |
| 9 | Write main | New: one transaction with the basis fence | C + facts datoms | 20–60 ms | two transactions today, plus two `db.fn/call` rechecks | 2–40 ms; "native held basis" | Same mechanism under two names: `:datahike/expected-basis-t` on `seon.db/transact!` with `:parents`. One transaction (P4). |
| 10 | Write back | New: splice, analyze once, lint, write F files, one git commit | F · bytes | F × 50–300 ms; process startup dominates | proofs and lint run three times today | reuse candidate bytes + analysis; `indexed!` should consume them | B is right: one proof per content, consumed thrice (P7). |
| 11 | The step on B (per eval request) | Δ, T lookups; stale check; **one** run of stale(T) | R stale lookups + R_new bodies | ~1 ms + runs | three requests per step, 147–999 ms; `step-verdict` over 12 chains 12 s / 29 GB | six-change request 156–177 ms / 201–216 MB; first 1,066 ms / 1,316 MB | Agree. One request (P6); one judgment per step (P1). |
| 12 | Context "what will merge" | Lookup over the step's cached Δ and head entries | C | 1–2 ms | — | — | New (P10); costs nothing the step did not already derive. |

**Expected merge when main did not move:** about 50–150 ms of reads, one transaction, then F × (50–300 ms) of
write-back. No test runs, no boot. **When main moved:** add R_new runs, and one boot only if a reached isolated member's
digest is new on S.

### Other disagreements, settled

- **Totals.** A: the merge/done path ~4,330 → ~1,830 lines. B: 447 functions / 11,035 lines → 3,600–5,100. They
  count different scopes: A the merge, gate, step and done functions; B adds all of `seon.test` (3,300 function
  lines) and `seon.test.runner` (2,713). A's per-function figures verify; B's final total is a per-responsibility
  guess that names no function for most of its 5,900–7,400 deletion. §3 gives the verified count: about 2,700 lines
  go in the phases below (≈1,050 on the merge/done path, ≈1,650 in the runner), before the ≈175 guard sites the
  S-reads conversion removes.
- **`publication` inside done.** A deletes it (a dry-run merge per judgment); B keeps and reuses it. The owner's
  2026-10-15 ruling (a merge refused a lint error its done rule never saw) decides for B: the file proof stays in the
  one judgment, computed once per changed file content and consumed by the merge and the write-back.
- **Agent test verbs.** A deletes `my.test` whole. The ruling keeps one simple manual verb. P9 keeps `my.test/run`
  as a function of a set of test symbols and deletes the rest.
- **Isolated members.** A's recommended option (the merge counts them unknown-allowed; root's batch runs them later)
  contradicts the ruling. P8 follows the ruling.
- **Red at the merge base.** A and B both delete it; the ruling removes the excuse. P5.
- **Refusal counts.** A: 16 kinds → 6. B: 31 concrete error schemas, 28 needed. Different censuses (B counts every
  error schema on the test path, runner included). On the merge/done path six kinds survive plus the basis fence's
  `:seon.db/transaction-refused-error`; the write-back's nine input/installation reasons stay with their producer.
- **`my.test.edn`.** A says about 7 tasks; it holds 13. P9 retires 11 and moves the two cost tasks to `seon.test.edn`.
- **Lint.** The ruling's merge checks are conflicts, new digests and contract completeness; lint is "what the branch
  alone decides" inside the one judgment, a lookup at merge time. The merge's new work stays those three.

---

## 3. What stays and what is deleted

### 3.1 The merge and done path

**Stays** (bodies trimmed where noted):

| Symbol | Lines | Role |
|---|---|---|
| `seon.cluster.source/merge-base`, `merge-base-refusals`, `merge-base-database`, `branch-merge-base`, `refused` | 52 | the one merge base; the criss-cross refusal is real |
| `seon.cluster.source/replacement`, `release!`, `merge-bound` | 16 | |
| `seon.cluster.source/contribution`, `remove-contribution`, `add-contribution`, `fold-members`, `member-contributions`, `merge-members` | 118 | the one test selection, incremental |
| `seon.cluster.source/unmerged-tx`, `entry-identity`, `unmerged`, `with-unmerged`, `compare-commit`, `compare-branch`, `unmerged-sets`, `versions`, `unmerged-refusal` | 119 | three-way comparison and git's unmerged entries |
| `seon.cluster.source/red-test-verdicts`, `red-test-refusal`, `green-result?` | 36 | |
| `seon.cluster.source/incomplete-findings`, `contract-gaps`, `gap-symbols`, `uncontracted`, `uncontracted-refusal` | 51 | contract completeness, judged once |
| `seon.cluster.source/uncovered-message`, `uncovered-refusal`, `uncovered-functions` | 32 → 25 | coverage, derived from the member fold (P3) |
| `seon.cluster.source/lint-refusal`, `file-refusal`, `publication` | 45 | the one file proof (P7) |
| `seon.cluster.source/done-refusals` | 62 → ~50 | THE judge (P1, P5) |
| `seon.cluster.source/prepare-merge!` | 157 → ~40 | compare, conflict, S (P4) |
| `seon.cluster.source/accept-merge!` | 61 → ~35 | one transaction with the basis fence (P4) |
| `seon.cluster.source` write-back: `proven-files`, `proven-file`, `write-back!`, `commit!`, `lint-errors`, the ledger | 968 | unchanged, except the re-proof it drops (P7) |
| `seon.merge/squash-merge`, `conflict-task!`, `merged`, `merge-tx`, `collision-remap`, `cluster-entity?`, `add-count`, `attribute-tx`, `member-merge`, `shown-state` | 215 | the facts squash |
| `seon.merge/merge!`, `merge-onto`, `assigned-task`, `merged?`, `acceptance`, `assignment-row` | 198 → ~95 | one path, one transaction (P4) |
| `seon.merge/merge-main!`, `absent-references`, `carried-entities`, `checkout`, `indexed!`, `publish!` | 213 | main forward; publication (P7 makes `indexed!` consume the held analysis) |
| `seon.cluster/candidate-gate!`, `reaching-run`, `isolated-run` | 104 | S's write and run, isolated members through `check-files!` (P8 calls `isolated-run` from the step too) |
| `seon.test.platform/check-files!` and helpers | 207 | the one platform runner |
| `seon.test/host-exclusions`, `isolated-members` | 74 | |
| `seon.context/change`, `step-members`, `current-results`, `step-observation` (→ the one judged-input builder), `step-status`, `step-verdict`, `step-section`, `task-done?`, `branch-changes` (rewritten, P10) | ~195 → ~150 | |
| `seon.turn/test-step`, `run-reaching`, `step-tests`, `task-proofs`, `step-due?`, `prove-turn` | 138 → ~75 | one request per step (P6) |
| `seon.cluster.agent/merge-chains!`, `refusals-verdict`, `merge-step` | 75 | the conflict path stays |
| `seon.cluster.branch-evidence/branch-evidence` | 184 (file) | the lane commit's question; unchanged |
| `my.test/run` | ~25 | the ONE manual verb (P9) |

**Deleted**, each in the phase that converts its last caller:

| Symbol | Lines | Phase | Replaced by |
|---|---|---|---|
| `seon.cluster.source/results-verdict` | 55 | P1 | `done-refusals` |
| `seon.cluster.agent/done-judged` | 46 | P2 | the one judged-input builder |
| the `gate-sets` walk in `uncovered-functions` | 7 | P3 | the member fold's seed → tests |
| `seon.cluster.source/merge-seeds`, `merge-list`, `carried`, `unselected-reach`, the `:selection` option, `:seon.merge/stays`, `:seon.merge/will-merge` | 84 | P4 | the whole delta |
| `seon.cluster.source/merge-verdict`, `tested-task-basis`, `task-basis-refusal`, `task-basis-held-tx`, `declarations-held-tx`, `moved-digests`, `held-digests` (but the head digests `unmerged` reads), `unmerged-count`, `:seon.source/expected-digests`, `:seon.source/tested-task-basis`, `:seon.source/prepared`'s run-basis members | 158 | P4 | `done-refusals` over S; `:datahike/expected-basis-t` |
| `seon.merge/chain-row`, `assignment-row`'s first-parent walk, `merge-commit`, the second transaction in `merge-onto` (`:seon.task/merge-commit`, `:seon.task/merged-declarations`), the second `merged` call | 102 | P4 | one merge base; one transaction; `seon.db/commit-at-t` of the `:merged` outcome |
| `seon.cluster.source/red-at-merge-base`, `seon.test/held-red`, `fork-point-red`, `seon.turn/run-merge-base`, `:seon.source/red-on-target`, `:seon.source/stray-red-error` | 72 + 2 schema entries | P5 | red is red: `:seon.source/red-test-error` |
| `seon.turn/run-branch`, `run-proofs`, `branch-due`, `step-reds`, `step-proved?`, `prove-turn`'s ordering | 77 | P6 | one request over stale(T ∪ branch tests ∪ proofs) |
| the re-proofs in `prepare-merge!` (`proven-files` + `root-refusal` + lint) and in `write-back!`; `seon.merge/indexed!`'s re-analysis | ~60 | P7 | the one file proof, by content |
| `seon.turn/platform-proof` and its `my.test/check-platform` offer, the `:seon.source/deferred` exemption in `done-refusals` | 25 | P8 | the step runs isolated members through `isolated-run` |
| `my.test/check`, `check-now`, `check-platform`, `run-owned`, the `run` macro; the `my.test/check` offer in `seon.render.var/render-ai`; `seon.sci.lookup/agent-test-vars`' route to `check`; the `seon.mcp` and `seon.batch` prose | 152 + offers | P9 | `my.test/run` |
| `seon.cluster.source/merge-changes`, `agent-owned` | 29 | P4 (after `seon.cluster.agent/agent-creation-writes-no-program-namespace` fixes the producer, filed) | nothing: an agent's creation writes no program namespace |

Merge/done path total: about **1,050 lines deleted** of the ~2,000 in these functions, and five derivations of
"which tests reach this change", three of "is this red at the base", two merge bases, two judges and two transactions
each become one.

### 3.2 Refusal types that remain

On the merge and done path:

| Type | Meaning | Where |
|---|---|---|
| `:seon.source/conflict-error` | main and the chain changed one declaration since O (three-way), or an unmerged entry is unresolved | `prepare-merge!`, `unmerged-refusal` |
| `:seon.source/red-test-error` | a member (proof or reaching test) is red on the judged branch and is not one of the next task's proofs | `done-refusals` |
| `:seon.source/test-evidence-error` | a member has no executed result (unknown, never green; an empty proof set never finishes); on the last task, a changed function no green test reaches | `done-refusals` |
| `:seon.source/uncontracted-error` | a changed function with a contract gap | `done-refusals` |
| `:seon.source/lint-error` | a clj-kondo error in a file the merge writes (the one file proof) | `done-refusals` |
| `:seon.db/transaction-refused-error` | main moved between the judgment and the write: Datahike's basis fence; the next pass merges again | the writer |
| `:seon.source/write-back-error` | the nine input and installation reasons (`:root-refused`, `:commit-absent`, `:file-absent`, `:file-changed`, `:conflict`, `:not-analyzable`, `:not-equivalent`, `:install-failed`, `:span-mismatch`) | `write-back!` |

Deleted: `:seon.source/stray-red-error` (it is a red), `results-verdict`'s "Every proof must be a member" and
"names tasks the destination has not admitted" (the writer's ref integrity), `merge-verdict`'s three run-basis
messages, `declarations-held-tx`'s and `task-basis-held-tx`'s refusals, `prepare-merge!`'s three scope refusals,
`:seon.program/scope`, and the write-back's `:no-replacement`, `:one-file` and `:row-changed` (B's three G reasons:
an empty delta is a no-op value, file identity belongs to the declaration, a held immutable value is never compared
with a later place).

### 3.3 The runner: stock clojure.test plus evidence

What is already stock: `seon.test.runner/run-vars!` binds `clojure.test/report` to `capture-event!`, runs
`run-selected-tests` (namespace fixtures once, `clojure.test/test-vars`) and answers captured results; `commit-results!`
and `record-tx` store them keyed by reach digest; `reach-digests` and `current-results` are the lookup. That is the
runner the ruling describes: run through clojure.test's own run and report maps, key by reach digest, store.

What is not, and goes (P11):

| Symbol | Lines | Why |
|---|---|---|
| `seon.test/select`, `selection-facts`, `behavior-changed`, `changed-since-green`, `green-members`, `definition-digests`, `behavior-of`, `changed-definition-symbols`, `digest-differences`, `admission-members`, `admission-scope`, `admission-member-values`; the `:incremental`, `:platform` and `:all` policies | 666 | request-history selection: "which tests should run" is stale(T) by reach digest, a lookup the step already holds |
| `seon.test/run-request` | 415 → ~120 | one request: resolve, stale, run, record |
| `seon.test/admit-run` | 231 → ~80 | the admission writer stays (bound admission, actual exit: `record-exit!`); its selection and scope work goes |
| `seon.test.runner/admitted-members`, `program-written-since?` | 74 | read-time rechecks of what the writer admitted and the digest encodes |
| `seon.test.runner/record-latest-tx`, `failure-replacement-tx`, `designed-red?` | 233 | the latest-on-test representation beside the digest-keyed evidence; readers convert to `result-at-reach` / `current-results` first |
| `seon.test/carried-member`, `carried-reports`, most of `carried-results` | ~45 | a reused result is a reference to the producing result, never a copy under fresh admission |
| `seon.test/root-agent?`, `verified?`'s program-digest arity, `held-red`, `fork-point-red` | ~60 | actor-based evaluator identity and red-at-base (P5 takes the last two) |
| `seon.test.runner/ambient-snapshot`, `ambient-drift`, `set-drift`, `live-cluster-schema-states`, `projection-form-keys`, `restore-live-cluster-schema!`, `schema-restore-drift` | 200 | protection of JVM-global state after the fact; deleted only after the tests that write ambient state run in branch contexts (P11d, counted first) |

Runner total: about **1,650 lines**. What B lists as candidates without a named caller conversion
(`program-overlay-tx` 65, `checkpoint-head` 14, `prepare-checkpoint!` 26, `change-base` 11, `binding-lost?` 6) is
left to P11a's pre-brief: delete only with the caller that converts.

### 3.4 Agent test verbs: one

`my.test/run` becomes a function, `[:=> [:cat [:set :seon.test/sym]] [:or :seon.test/results :seon.error/value]]`:
run the named tests on my branch now, each answered from its record when its reach digest is unchanged, else run
and recorded; nothing else. `(my.test/run #{'ns/test-name})`. Everything else in `my.test` goes (P9), the Var
render stops offering a check (the step already ran it; the render shows status), `platform-proof`'s offer goes (P8),
and `clojure.test/run-tests` at an agent's REPL routes through `my.test/run` (`seon.sci.lookup/agent-test-vars`).

### 3.5 Help text (`resources/seon/help.md`), the rulings' three statements

Added to the orienting sections, in these words or close:

- "Every function is instrumented: its inputs are validated before entry and its outputs on return, so a value that
  reaches your function already satisfies its schema. Validate nothing by hand."
- "Tests run by themselves as you change things: each eval request runs the tests reaching what it changed, keyed by
  reach digest, so an unchanged test never runs again. Your next block shows which are red, with their failures and
  the result handle to dig into."
- "You may run a test by hand with `(my.test/run #{'ns/test-name})`; it is almost never necessary."
- "`:seon.context/changes` is what will merge: every function, test, schema and task entity your chain added, changed
  or deleted since its merge base, each with its test status. Remove scratch with `(my.program/ns-unmap! 'ns/name)`
  before you are done; everything shown merges."

Line 17's "Running them by hand with `clojure.test/run-tests` records nothing" becomes "records on your branch like
any other run" (it routes through `my.test/run`). `.agents/skills/clojure-testing/SKILL.md` §5 (today: "Agents call
`(my.test/run)` and `(my.test/check …)`") is rewritten in the same commit (P9).

---

## 4. Phases

> **Owner ruling, later on 2026-10-17 (supersedes P8 as written, and the §7 question on the platform boot):**
> automatic testing never boots a JVM; see `merge-rulings.md`, section "No automatic JVM boots".
> - P8 becomes: file-reading members run in the one JVM over the candidate's files, written to a disposable directory.
> - A chain whose change reaches a host-bound declaration bails to root as data.
> - Delete `check-files!`, the per-merge `isolated-run` boot and `check-platform`'s automatic use. Root's deliberate `bin/seon check` stays.
> - The narrow check rewrites P8's row in place.


HEAD boots after every phase. Live chains keep working between phases because `done-refusals` stays the judge and
`seon.merge/merge!` stays the entrance throughout; each phase changes what feeds them. These stay green through all
phases: `seon.cluster.merge-test/a-requested-green-end-merges-and-publishes-with-no-hand-act`,
`seon.cluster.merge-test/a-red-reaching-test-refuses-acceptance`,
`seon.cluster.source-test/done-and-merge-judge-one-member-list`,
`seon.cluster.source-test/member-fold-equals-the-full-merge-list`,
`seon.context-test/a-chains-step-is-done-only-when-the-done-rule-refuses-nothing`,
`seon.context-test/the-test-step-runs-the-member-list-the-done-rule-judges`,
`seon.merge-test/a-merge-reuses-the-chain-branchs-green-at-an-unchanged-reach`,
`seon.merge-test/root-merges-main-forward-into-a-chain-branch`,
`seon.cluster.db-flow-test/a-refused-final-merge-keeps-its-merge-branch-and-holds-a-merge-step`,
`seon.cluster.db-flow-test/a-done-merge-step-merges-its-branch-onto-main`,
`seon.cluster.db-flow-test/a-merge-step-green-at-its-start-ends-done-with-no-model-call`. The central round runs
`seon.cluster.db-flow-test/a-three-task-chain-ends-merged-with-no-hand-act` after P4, P7 and P8.

A cost proof is `seon.profile/measure` on the named probe, parent and change in the same run; its call counts
(`:seon.profile/called`) are the assertion, its ms and allocation the numbers in the commit message.

| Phase | Files | Change | Deletes (last caller converted here) | RED proof (red on HEAD, green after) | Cost proof |
|---|---|---|---|---|---|
| **P0** Decline the patch; salvage its findings (15 min, docs + tasks) | `resources/seon/tasks/seon.sci.eval.edn`, `resources/seon/tasks/seon.sci.reader.edn`, `test/seon/test_test.clj`, this file | `same-evaluator-red.patch` is **declined**; see §6. Its two SCI findings are real and are filed as the patch wrote them: `seon.sci.eval/ns-interns-lists-the-core-gap` and `seon.sci.reader/built-in-tags-go-to-the-default-data-reader-fn`, with the `interpreted-outcome` helper and the two long deftests as their proofs. Nothing of its red-at-base changes. | — | the two salvaged deftests are red by design (the tasks' proofs), not this phase's | — |
| **P1** One judge (45 min) | `src/seon/cluster/source.clj`, `src/seon/context.clj`, `test/seon/cluster/source_test.clj`, `docs/seon/schedule/cards/TASK-CHAIN.md` §2.4 | `step-status` always calls `done-refusals`; `merge-verdict` calls `done-refusals` over its judged input; a Prove step's product is its next task's proofs (`:seon.source/next-proofs`). TASK-CHAIN §2.4: "`results-verdict` stays the judge of the final merge only" → deleted; done and merge are one function. | `results-verdict` (55), the `chained?` dispatch in `step-status`, the "Every proof must be a member" and dangling-task clauses | a non-chained row in `seon.cluster.source-test/done-refusals-judges-every-task-alike` (an own-branch assignment's step is judged by `done-refusals`); `results-verdict-judges-a-never-started-member-as-evidence` converted to `done-refusals` | — |
| **P2** One judged input (30 min) | `src/seon/context.clj`, `src/seon/cluster/agent.clj`, `test/seon/cluster/db_flow_test.clj` | Extract `step-observation`'s builder as `seon.context/judged-input` `[database task-sym]` (reads the task on the branch it is given; Δ from the cached derivation; O from `merge-base`); `merge-chains!` calls it at the end commit. | `done-judged` (46) | new `seon.cluster.db-flow-test/roots-done-judgment-derives-nothing-the-step-did-not`: `seon.profile/measure` over root's done pass answers `:seon.profile/called` with no `seon.program/own-changed-identities` and at most one `seon.cluster.source/merge-base` (today `done-judged` calls both itself) | the same measure, ms and alloc of the done pass, parent vs change |
| **P3** Coverage from the one selection (30 min) | `src/seon/cluster/source.clj`, `test/seon/cluster/source_test.clj` | `uncovered-functions` reads seed → tests from `member-contributions` instead of walking `seon.fn/gate-sets` per function. | the `gate-sets` walk | a new assertion in `seon.cluster.source-test/an-uncovered-refusal-names-the-red-tests-reaching-it`: the judgment's `:seon.profile/called` holds no `seon.fn/gate-sets` (today one per function) | ms and alloc of the judgment at C = 20 |
| **P4** The one merge (60 min; needs P1–P3) | `src/seon/cluster/source.clj`, `src/seon/merge.clj`, `resources/seon/schemas/seon.source.edn`, `resources/seon/schemas/seon.merge.edn`, `test/seon/merge_test.clj`, `test/seon/cluster/merge_test.clj`, TASK-CHAIN §2.6 | `prepare-merge!` = compare, conflicts, fork S, one write (Δ's replacement + unmerged entries + `merge-tx`), `reaching-run` (lookup, run stale), `done-refusals` over `judged-input` on S. `accept-merge!` = one `seon.db/transact!` with `:parents #{B S}` and `:datahike/expected-basis-t` of the H the judgment read; `merge-onto` has one path (a `:sent-back` source merges facts only; a done one everything). One merge base for program and facts. TASK-CHAIN §2.6 amended: whole delta; one transaction; no merge list. | `merge-seeds`, `merge-list`, `carried`, `unselected-reach`, `:selection`, `merge-verdict`, `tested-task-basis`, `task-basis-refusal`, `task-basis-held-tx`, `declarations-held-tx`, `moved-digests`, `held-digests` (bar the head digests `unmerged` reads), `unmerged-count`, `chain-row`, the first-parent walk in `assignment-row`, `merge-commit`'s attribute and the second transaction, the second `merged` call, `merge-changes` + `agent-owned` once the producer fix lands, `:seon.merge/stays`, `:seon.merge/will-merge`, `:seon.source/expected-digests`, `:seon.source/tested-task-basis` | `seon.merge-test/a-merge-carries-only-what-its-tests-reach` rewritten as `a-merge-carries-the-whole-delta` (an unreached scratch function merges; today it stays); `a-prepared-merge-carries-its-merge-list-and-a-staying-conflict-refuses` → `a-conflict-keeps-the-merge-branch-with-mains-version-and-an-unmerged-entry`; new `seon.cluster.merge-test/main-moved-since-the-judgment-refuses-by-basis-and-the-next-pass-reruns-only-the-reached`: a write to main between judgment and transaction answers `:seon.db/transaction-refused-error`, the next pass merges and its run executes exactly R_new members (a count of executed results, 0 when the move is outside the reach) | `seon.profile/measure` of `seon.merge/merge!` on `a-requested-green-end-merges-and-publishes-with-no-hand-act`'s chain, parent vs change: executed members 0 when main did not move; transactions on main 1 (today 2); ms and alloc |
| **P5** Red is red (45 min; after P4) | `src/seon/cluster/source.clj`, `src/seon/context.clj`, `src/seon/turn.clj`, `src/seon/test.clj`, `resources/seon/schemas/seon.source.edn`, `test/seon/cluster/source_test.clj`, `test/seon/context_test.clj`, `test/seon/cluster/merge_test.clj`, `test/seon/test_test.clj`, TASK-CHAIN §2.4 | `done-refusals`: a red member not among the next proofs refuses, one clause, one type. TASK-CHAIN §2.4 loses `:seon.source/red-on-target` and `:seon.source/stray-red-error`. | `red-at-merge-base`, `held-red`, `fork-point-red`, `run-merge-base`, `:seon.source/red-on-target`, `:seon.source/stray-red-error`, `seon.test-test`'s `fork-point-red` test | the flipped rows: `done-refusals-judges-every-task-alike` row "a test red at the merge base too is not the change's" → refuses `:seon.source/red-test-error`; `seon.context-test` row 771 likewise; `done-and-merge-judge-a-red-reaching-test-the-same-way` and `seon.cluster.merge-test/a-test-never-run-on-the-target-is-not-blamed-on-the-merge` rewritten: a red reaching test refuses whether or not main holds it red | — |
| **P6** One test request per step (45 min; file-disjoint from P1–P5) | `src/seon/turn.clj`, `test/seon/turn/test_step_verdict_test.clj` | `test-step` computes stale over T ∪ branch tests ∪ proofs once and runs ONE `seon.test/run`; `prove-turn` is `test-step` with no evaluated form; the judgment is read once from `step-verdict`. | `run-branch`, `run-proofs`, `branch-due`, `step-reds`, `step-proved?`, `prove-turn`'s ordering | a new assertion in `seon.turn.test-step-verdict-test/the-step-verdict-covers-every-test-of-the-member-list`: one `:seon.test.run/id` written per step (today up to four) | `seon.profile/measure` of one step with six changes, parent vs change (B's baseline 156–177 ms / 201–216 MB) |
| **P7** One file proof, consumed thrice (45 min; after P4) | `src/seon/cluster/source.clj`, `src/seon/merge.clj`, `test/seon/cluster/write_back_test.clj` | `publication` keys its proof by the changed files' content digests (not main's commit) and holds candidate bytes + analysis; `prepare-merge!` and `write-back!` take the held proof; `seon.merge/indexed!` consumes the held analysis. Lint runs once per file content. | the re-proof in `prepare-merge!` (`root-refusal`, second `proven-files`), the re-proof in `write-back!`, `indexed!`'s re-analysis | a new assertion in a write-back test: `:seon.profile/called` over merge + publish holds exactly one `seon.cluster.source/proven-files` and one `lint-errors` (today three and three) | ms and alloc of merge + publish, parent vs change (TASK-CHAIN §2.4's 280 ms / 0.7 GB per uncached proof is the term that drops to once) |
| **P8** Platform members from the step (45 min; after P7) | `src/seon/turn.clj`, `src/seon/cluster.clj`, `src/seon/cluster/source.clj`, `test/seon/merge_test.clj` | The step hands reached isolated members to `seon.cluster/isolated-run` (made public) over the branch's held proven files; `check-files!` keys the boot by the candidate commit (content); results record by reach digest; `done-refusals` judges them like any member; the merge's `reaching-run` finds them by lookup and boots only when a reached isolated member has no result at its S digest. Bounded by the cluster's check dial, as `fork-point-red` was. | `platform-proof` and its offer, the `:seon.source/deferred` exemption in `done-refusals` | `seon.merge-test/the-gate-runs-a-file-reading-test-against-the-candidate-and-refuses` rewritten as `a-file-reading-test-runs-from-the-step-and-the-merge-reuses-it`: the step's run holds the platform result; the merge's answer carries no `:seon.test.platform/ms` (no boot) | ms of the merge on that chain, parent vs change (A's 38.9 s → the read cost); boots per content 1 |
| **P9** One verb; help; skill; offers (45 min; after P8) | `src/my/test.clj`, `src/seon/render/var.clj`, `src/seon/sci/lookup.clj`, `src/seon/mcp.clj`, `src/seon/batch.clj`, `resources/seon/help.md`, `.agents/skills/clojure-testing/SKILL.md`, `resources/seon/tasks/my.test.edn`, `resources/seon/tasks/seon.test.edn`, `test/seon/bootstrap_test.clj`, `test/seon/render/var_test.clj` | `my.test/run` as §3.4; help's four statements (§3.5); skill §5 rewritten; `agent-test-vars` routes to `run`; retire 11 `my.test.edn` tasks, move `a-two-test-check-allocates-a-gigabyte-and-a-half` and `a-defn-request-runs-four-tests-for-seven-gigabytes` to `seon.test.edn` as runner tasks. | `check`, `check-now`, `check-platform`, `run-owned`, the `run` macro, the Var render's offer, the `check-platform` prose in `seon.mcp`, `seon.batch`'s docstring reference | new `seon.bootstrap-test/help-references-one-test-verb` (help's `:seon.help/references` resolve `my.test/run` and no `my.test/check`); new `seon.render.var-test/a-var-render-offers-no-test-run` (a pending or newly-red Var's render carries no `:seon.render/operations` naming a test verb) | — |
| **P10** What will merge (45 min; after P4) | `src/seon/context.clj`, `resources/seon/help.md`, `test/seon/context_test.clj` | `branch-changes` lists every entity of Δ by family (functions, tests, schemas, namespaces, and the chain's task entities from the facts squash), each with its status from the head entries and the `my.program/ns-unmap!` form; removed entities under `:seon.context/removed`. | `:seon.context/stays` | new `seon.context-test/the-block-shows-every-change-that-will-merge-with-its-test-status`: a branch with one changed function (reached, green), one new test (red) and one new schema shows all three with status and the removal form (today an unreached change shows `:stays` and no status) | ms of `current-state` at C = 20, parent vs change |
| **P11a** Runner: one request (60 min; after P6 and P9) | `src/seon/test.clj`, `src/seon/test/runner.clj`, `test/seon/test_test.clj`, `test/seon/test/selection_test.clj` | `seon.test/run` takes `{:seon.test/execution :seon.test/identities :seon.test/rerun?}`; stale by reach digest; resolve in the fork; `run-vars!`; `commit-results!`. Pre-brief (Haiku) lists every caller of `select` and the policies, and settles `program-overlay-tx`, `checkpoint-head`, `prepare-checkpoint!`, `change-base`, `binding-lost?`. | `select` and the selection family (666), the `:incremental` / `:platform` / `:all` policies, `run-request` → ~120, `admit-run`'s selection and scope work, `root-agent?`, `verified?`'s digest arity, `seon.test.selection-test` (its two historical reds included) | new `seon.test-test/a-no-op-evaluation-writes-no-run` (an eval request that changed nothing writes no `:seon.test.run/id`; today the step's requests admit runs) | `seon.profile/measure` of the step's request: no `seon.test/select` call; ms and alloc parent vs change (B's first-request 1,066 ms / 1,316 MB is the target term) |
| **P11b** Runner: evidence is referenced, never copied (60 min) | `src/seon/test.clj`, `src/seon/test/runner.clj`, their tests | Convert `latest-results`, `newest-results` and every reader of the latest-on-test representation to `result-at-reach` / `current-results`; a reused result is a reference to its producing result. | `record-latest-tx`, `failure-replacement-tx`, `designed-red?`, `carried-member`, `carried-reports`, most of `carried-results` | new `seon.test-test/a-reused-result-is-referenced-not-copied`: after a reuse the count of result datoms is unchanged (today a copy is admitted) | reads per reuse, parent vs change |
| **P11c** Runner: no read-time rechecks (30 min) | `src/seon/test/runner.clj`, `src/seon/test.clj` | The writer's admission and exit facts are the only admission; readers stop re-validating counts and bases. | `admitted-members`, `program-written-since?`, the `:seon.test/invalid-basis` and `:seon.test/program-mismatch` re-admission comparisons | new assertion in `seon.test-test`: a lookup of R = 80 results makes no `admitted-members` call (`:seon.profile/called`) | reads per lookup |
| **P11d** Runner: compartments, then no ambient repair (60 min; count first) | `src/seon/test/runner.clj`, the converted tests | Haiku counts the tests whose stored runs reported ambient drift and the JVM-global state they write; each converts to a branch context (`support/with-database`, the member's fork) first; then the snapshot/restore goes. If the count is zero, the deletion is immediate. | `ambient-snapshot`, `ambient-drift`, `set-drift`, `live-cluster-schema-states`, `projection-form-keys`, `restore-live-cluster-schema!`, `schema-restore-drift` (200) | new `seon.test-test/a-members-own-schema-change-leaves-its-sibling-unchanged` (B's phase 4 proof; today the sibling sees it only through the restore) | ms and alloc of `run-vars!` on a 10-member run, parent vs change |

**Ordering.** P1 → P2 → P3 → P4 are serial on `source.clj`; P6 runs beside them (turn.clj only); P5 after P4; P7
after P4; P8 after P7; P9 after P8; P10 after P4 and beside P5–P9; P11 after P6 and P9. Root batches one restart
after P4 (schema resources change) and one after P9. TASK-CHAIN §2.4 and §2.6 are amended in P1, P4 and P5, each with
its red deftest (the task-chain design is locked). The S-reads conversion (≈175 `:seon.error/at` guard sites on this
path) stays the errors spec's work, done per owner after P11.

**Live chains during the cut.** Between P4 and P5 a chain reaching a test red on main is refused at done by the old
`red-on-target` excuse's absence only if P5 lands first; P4 keeps the excuse wired until P5 removes it in one place.
Between P8 and P9 the offer `my.test/check-platform` is gone while the verb still exists; harmless.

---

## 5. Totals

| | Lines today (function spans) | Deleted in P1–P11 | Kept (some rewritten) |
|---|---|---|---|
| Merge and done path (`seon.cluster.source` merge section, `seon.merge`, `seon.cluster` gate, `seon.context` step, `seon.turn` step, `seon.cluster.agent` done, `my.test`, `seon.test.platform`, red-at-base in `seon.test`) | ≈2,000 (A's 4,330 includes write-back's 968 and the 400-line turn step whole) | ≈1,050 | ≈950 + write-back 968 |
| Runner (`seon.test`, `seon.test.runner`) | 6,013 | ≈1,650 | ≈4,350 (execution, fixtures, reach index, capture, recording, exit) |
| Guard sites (S-reads, later) | ≈175 sites | — | — |

About 2,700 lines go; nothing new is added beyond `judged-input`'s name, `my.test/run`'s body, the step's call of
`isolated-run`, the rewritten `branch-changes` and the new deftests. Both analyses' claims of larger cuts rest on work
not named here (A: the S-reads conversion and file reorganizations; B: a per-responsibility estimate).

---

## 6. The unapplied patch

`docs/research/agent-platform/2026-10-16/same-evaluator-red.patch` is **declined and never applied.** It adds
`seon.test/red-evaluators` and threads an evaluator match through `held-red`, `fork-point-red`, `red-at-merge-base`
and `run-merge-base`, all of which P5 deletes; the ruling "a red test on the merged result refuses the merge, even if
main had it red before" removes the question it answers. Opus's P0 and B's "reuse red as red" agree.

Its two findings survive on their own: live run 7's two tests green compiled and red interpreted
(`seon.error-test/every-error-owner-function-declares-its-input-and-output`: SCI's `ns-interns` lists the clojure.core
names `seon.sci.eval/install-caller-host-bindings!` interns per namespace; `seon.turn-test/evaluation-ai-is-only-the-repl-session`:
SCI hands `#inst` to a bound `*default-data-reader-fn*` before `clojure.core/default-data-readers`). Under "red is
red" they are no longer excused: a chain whose change is in either test's reach will have that member run
interpreted on its branch and be refused until the SCI owner is fixed. P0 files the patch's two tasks and their two
deftests exactly as the patch wrote them (the `interpreted-outcome` helper and the two long deftests in
`test/seon/test_test.clj`), nothing else from it, and root ranks them before the first live run after P5.

---

## 7. Open questions

One, not resolvable from the rulings or the code:

- **Where the step's platform boot waits.** P8 runs a reached isolated member's JVM from the step, bounded by the
  cluster's check dial, which exceeds `:seon.config.test/step-time-limit-ms` (shipped 10,000 ms) by three times a
  boot. The ruling says results arrive in the agent's next context; it does not say whether the reply that caused
  the boot waits for it. The plan's default: the step reports the member pending in that reply and the boot runs to
  its exit on the step's own thread, recorded when it exits, so the following block shows it (the "timeout is not
  termination" rule already keeps ownership until exit). Root may instead make the reply wait. Either way there is
  one boot per changed host-bound content, and never one at an ordinary merge.
