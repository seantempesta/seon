---
title: "Merge hybrid: one merge, reactive evidence, one judgment"
type: plan
status: "owning plan for the merge and testing redesign, written 2026-10-17 by Fable from the owner's rulings (ac12586a5), analysis A (merge-audit-opus.md, 525e7aecd) and analysis B (merge-cost-astra.md, 44219e306 and ced9b12c0); every disputed claim verified against the code or a read-only JVM probe on default"
---

# Merge hybrid (2026-10-17)

**Phase status** (each implementing commit updates this line):
P0 done (53fc1da51) · P1 done (5f5eb1855) · P2 done (14448514a) · P3 done (510b8023e) · P4a done (this commit; census landed beside it) · P4b done (a3ed65f73: the merge writes `:seon.task/merged-declarations` in its one transaction; its count and what main's merge moved are one derivation, `seon.cluster.source/moved`, over the value the transaction leaves (`seon.cluster.source/moving`) and over the merge commit (`seon.cluster.source/merge-accepted`); `:seon.task/merge-commit` is gone, `seon.merge/merge-commit` derives the commit from the transaction that wrote the count, else the `:merged` outcome's) · P5 pending · P6 done 5313b890e (one `seon.test/run` per step; the judgment read once from `seon.context/step-verdict`; `done-refusals` holds a task with no proofs of its own to an executed result for each next proof) · P7 pending ·
P8 pending · P9a pending · P9b pending · P10 done 8f39ed642 · P11a pending · P11b pending · P11c pending · P11d pending ·
P12 pending (after `seon.cluster.agent/agent-creation-writes-no-program-namespace`) · P13 (per phase) census `seon.guard-census-test/a-changed-function-holds-no-guard` landed with P4a

Narrow check done 2026-10-17 (Sonnet, lane merge-check): §8 holds the claims table; the fixes are applied in place in
§§1–5 and 7.

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
4. **Every automatic test runs in the one JVM; automatic testing never boots a JVM** (owner, 2026-10-17, "No
   automatic JVM boots", `merge-rulings.md`). Members run in the agent's SCI fork on its branch. A member that reads
   the program as files (`:seon.test/reads`; today one test, `seon.babashka-load-test/declared-namespaces-load-in-babashka`)
   runs in that same JVM over the candidate's files, written cheaply from the branch to a disposable directory
   (`seon.test.platform/snapshot!` clones the published roots, `seon.cluster.source/proven-files` supplies the
   candidate bytes), and may launch the external tool it tests (`bb`), never a JVM. A member the one JVM must not run
   (declared `:seon.test/platform`, reaching a `:seon.fn/destroys` owner, or declaring a
   `:seon.test/fixture-observation`; `seon.test/host-exclusions`) is never run automatically, and neither is the
   test of a changed host-bound declaration (`:seon.fn/host-bound?`, which SCI cannot interpret): the done rule
   refuses them as data (`:seon.source/test-evidence-error` naming them) and the chain bails to root, which proves
   platform work in JVM mode with the deliberate `bin/seon check`. Today `seon.cluster/isolated-run` boots a JVM at
   the merge only when a deferred member exists (verified; A's "a JVM boot per merge" is not what the code does);
   the ruling deletes that boot (P8) and the agent's `my.test/check-platform` offer (P8, P9b).
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
| 7 | Run | New: R_new only, in the one JVM (the SCI fork; a file-reading member over a disposable candidate directory, plus `bb`) | R_new × T(body); F files written for the one reads member | 0 when main did not move; otherwise R_new × 50–500 ms; the reads member ≈ 0.2 s of `bb` launches (10 namespaces, measured) plus the directory write; no boot | "a JVM boot per merge", 38.9 s | "a separate physical cost" | **Code: the boot is conditional on a deferred member today (`seon.cluster/isolated-run`).** A's e2e number came from a proof that reaches a file-reading test. B is right. The ruling deletes the boot altogether (P8): there is no Q and no boot cost on any path. |
| 8 | Judge | Lookup: R results + contract gaps of Δ + the cached file proof | R + C (+ F once per content) | < 15 ms (measured warm: `contract-gaps` 0.7–0.8 ms, `current-results` 0.1–0.3 ms); file proof 2 ms cached, ~280 ms / 0.7 GB per new file content (TASK-CHAIN §2.4; not re-measured, it needs a write) | `contract-gaps` 5–12 ms; judgment built four or more times per request | 0.8–1.2 ms partial done; "publication keys main's commit, so unrelated bookkeeping can force candidate file work" | Agree on cost. One judge, called once per observation (P1, P2). The file proof is computed once per (changed file content) and consumed by done, merge and write-back (P7), keyed by the files' content rather than main's commit, which answers B's point. |
| 9 | Write main | New: one transaction with the basis fence | C + facts datoms | 20–60 ms | two transactions today, plus two `db.fn/call` rechecks | 2–40 ms; "native held basis" | Same mechanism under two names: `:datahike/expected-basis-t` on `seon.db/transact!` with `:parents`. One transaction (P4). |
| 10 | Write back | New: splice, analyze once, lint, write F files, one git commit | F · bytes | F × 50–300 ms; process startup dominates | the file proof runs three times today (`publication`, `prepare-merge!`, `write-back-commits`: three `proven-files` call sites) and lint twice (`publication`, `file-refusal`) | reuse candidate bytes + analysis; `indexed!` should consume them | B is right: one proof per content, consumed thrice (P7). |
| 11 | The step on B (per eval request) | Δ, T lookups; stale check; **one** run of stale(T) | R stale lookups + R_new bodies | 0.2–4.5 ms + runs (measured `seon.test/stale` warm: 4.5 ms / 4.5 MB at R = 80, 0.24 ms at R = 1) | three requests per step, 147–999 ms; `step-verdict` over 12 chains 12 s / 29 GB | six-change request 156–177 ms / 201–216 MB; first 1,066 ms / 1,316 MB | Agree. One request (P6); one judgment per step (P1). |
| 12 | Context "what will merge" | Lookup over the step's cached Δ and head entries | C | 1–2 ms | — | — | New (P10); costs nothing the step did not already derive. |

**Expected merge when main did not move:** about 50–150 ms of reads, one transaction, then F × (50–300 ms) of
write-back. No test runs, no boot. **When main moved:** add R_new runs in the one JVM (the reads member also writes its
disposable directory when its reach is new). There is no boot on any path.

### Other disagreements, settled

- **Totals.** A: the merge/done path ~4,330 → ~1,830 lines. B: 447 functions / 11,035 lines → 3,600–5,100. They
  count different scopes: A the merge, gate, step and done functions; B adds all of `seon.test` (3,300 function
  lines) and `seon.test.runner` (2,713). A's per-function figures verify; B's final total is a per-responsibility
  guess that names no function for most of its 5,900–7,400 deletion. §3 gives the verified count: about 2,900 lines
  go in the phases below (≈1,250 on the merge/done path, `seon.test.platform` included, ≈1,650 in the runner), before the ≈175 guard sites the
  S-reads conversion removes.
- **`publication` inside done.** A deletes it (a dry-run merge per judgment); B keeps and reuses it. The owner's
  2026-10-15 ruling (a merge refused a lint error its done rule never saw) decides for B: the file proof stays in the
  one judgment, computed once per changed file content and consumed by the merge and the write-back.
- **Agent test verbs.** A deletes `my.test` whole. The ruling keeps one simple manual verb. P9 keeps `my.test/run`
  as a function of a set of test symbols and deletes the rest.
- **Isolated members.** A's recommended option (the merge counts them unknown-allowed; root's batch runs them later)
  contradicts the first ruling; the later "No automatic JVM boots" ruling settles it: the one file-reading member runs
  in the one JVM, and every other member the JVM must not run is refused as data (P8).
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
| `seon.merge/squash-merge`, `conflict-task!`, `merged`, `merge-tx`, `collision-remap`, `cluster-entity?`, `add-count`, `attribute-tx`, `member-merge`, `shown-state` | 176 (measured; the plan's 215 did not reproduce) | the facts squash |
| `seon.merge/merge!`, `merge-onto`, `assigned-task`, `merged?`, `acceptance`, `assignment-row` | 198 → ~95 | one path, one transaction (P4) |
| `seon.merge/merge-main!`, `absent-references`, `carried-entities`, `checkout`, `indexed!`, `publish!` | 213 | main forward; publication (P7 makes `indexed!` consume the held analysis) |
| `seon.cluster/candidate-gate!`, `reaching-run` | 91 | S's write and in-JVM run (P8 removes `isolated-run`, the platform call and `:seon.source/platform-run`) |
| `seon.test/host-exclusions`, `isolated-members` | 74 | root's `bin/test --platform` (`bin/test-check` calls `isolated-members`) and the done rule's list of members never run automatically; P8 drops the `:seon.test/reads` branch from `host-exclusions` (a reads member runs in the one JVM). `isolated-members` throws on default today (§8, out-of-scope finding) |
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
| `seon.cluster.source/merge-seeds`, `merge-list`, `carried`, `unselected-reach`, the `:selection` option, `:seon.merge/stays`, `:seon.merge/will-merge` | 84 | P4a (with `seon.context/branch-changes`, their live caller, converted in the same phase: it shows every entity as merging, no `:stays`; P10 adds status) | the whole delta |
| `seon.cluster.source/merge-verdict`, `tested-task-basis`, `task-basis-refusal`, `task-basis-held-tx`, `declarations-held-tx`, the `:expected-digests` key of `prepared`, `:seon.source/tested-task-basis`, `:seon.source/prepared`'s run-basis members | 139 | P4a | `done-refusals` over S; `:datahike/expected-basis-t` |
| `seon.cluster.source/moved-digests` (5), with write-back's `:row-changed` check (its only other caller, `write-back-commits`) | 5 + the clause | P7 (not P4: `write-back-commits` calls it; HEAD would not boot) | a held immutable value is never compared with a later place |
| the second `merged` call (`seon.merge/merge-onto` squashes once, outside the writer, on the H its basis fence names; `facts-tx`) | ~5 | P4a | one squash per merge. **Not deleted (P4a, 2026-10-17):** `seon.merge/chain-row` and `assignment-row`'s first-parent walk: the facts' base is the fork point, not the program's merge base, which a forward merge moves to main's head while it never moves the facts (`seon.merge-test/root-merges-main-forward-into-a-chain-branch`); one merge base for both needs the forward merge to carry the facts first |
| `seon.merge/merge-commit`'s recorded-attribute branch, the second transaction in `merge-onto` (`answer`), `:seon.task/merge-commit` and its eleven files (`seon.task` attempt facts, `seon.context/step-before`, `seon.cluster.agent/publication-due?` and `lifecycle-facts`, the schemas `seon.task`, `seon.chain`, `seon.merge`, five test files: `my.root-test`, both merge tests, `seon.cluster.db-flow-test`, `seon.cluster.registry-test`) | ~20 + readers | P4b | `seon.merge/merge-commit` already derives the commit from the `:merged` outcome (`seon.db/commit-at-t`, verified); `:seon.task/merged-declarations` **stays**: `publication-due?` reads it per assignment per pass and the one transaction writes it from Δ's count (a `merge-accepted` read per pass would cost more) |
| not deleted: `seon.cluster.source/held-digests` (7 call sites: `unmerged`, `publication`, write-back), `unmerged-count` (the reconciler's `work-left?` and `:seon.flow.actions/unmerged`, `seon.merge/merge-onto`), the schema `:seon.source/expected-digests` (the output of `held-digests`, also declared by `seon.cluster.branch-evidence`) | — | — | each has a live caller outside the merge list |
| `seon.cluster.source/red-at-merge-base`, `seon.test/held-red`, `fork-point-red`, `seon.turn/run-merge-base`, `:seon.source/red-on-target`, `:seon.source/stray-red-error` | 72 + 2 schema entries | P5 | red is red: `:seon.source/red-test-error` |
| `seon.turn/run-branch`, `run-proofs`, `branch-due`, `step-reds`, `step-proved?`, `prove-turn`'s ordering | 77 | P6 | one request over stale(T ∪ branch tests ∪ proofs) |
| the re-proofs in `prepare-merge!` (`proven-files` + `root-refusal` + lint) and in `write-back!`; `seon.merge/indexed!`'s re-analysis | ~60 | P7 | the one file proof, by content |
| `seon.test.platform/check-files!` (35), `seon.cluster/isolated-run` (13), `seon.turn/platform-proof` (17) and the `:seon.turn.loop/platform` summary and schema, `:seon.source/platform-run` and its `:seon.test.platform/ms` phase, the `:seon.source/deferred` exemption in `done-refusals`, `:seon.test/reads` in `host-exclusions` | ~75 | P8 | the in-JVM run over the candidate's files; unrun platform-bound members and host-bound changes are `:seon.source/test-evidence-error` data and the chain bails to root |
| `my.test/check`, `check-now`, `check-platform`, `run-owned`, the `run` macro; the `my.test/check` offer in `seon.render.var/render-ai`; `seon.sci.lookup/agent-test-vars`' route to `check`; the `seon.mcp` and `seon.batch` prose; whole `seon.test.platform` (the remaining 172 lines: `snapshot!`, `candidate-commit!`, `run-check!`, `record!`, `published-source`; P8 keeps `snapshot!` and `published-source` until then) and `test/seon/test/platform_test.clj` | 152 + 172 + offers | P9b, after P9a converts the callers | `my.test/run`; root's `bin/seon check` |
| `seon.cluster.source/merge-changes`, `agent-owned` (also read by `unmerged`/`compare-commit` and three `seon.context` functions) | 29 | P12, after `seon.cluster.agent/agent-creation-writes-no-program-namespace` is implemented (filed in `resources/seon/tasks/seon.cluster.agent.edn`, no phase here) | nothing: an agent's creation writes no program namespace |

Merge/done path total: about **1,250 lines deleted** (the check's count, `seon.test.platform` included) of the ~2,000 in these functions, and five derivations of
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
with a later place). **Phase: P7** (all three: `:no-replacement` and `:one-file` are checked twice, in `proven-files`'s
`refuse` and in `write-back-commits`; `:row-changed` is the `moved-digests` clause; the enum is in
`resources/seon/schemas/seon.source.edn`, the red-to-green row is
`seon.cluster.write-back-test/an-agent-replacement-merges-and-writes-back-its-exact-span`'s "a later change to a
written entity refuses by name", which becomes "writes back the accepted value"). The check found no phase
assigned to them before.

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
| `seon.test.runner/admitted-members`, `program-written-since?` | 74 | read-time rechecks of what the writer admitted and the digest encodes (`program-written-since?`'s caller in `merge-verdict` goes with P4a; two tests, `seon.test.admission-digest-test` and `seon.test.selection-test`, still call it) |
| `seon.test.runner/record-latest-tx`, `failure-replacement-tx`, `designed-red?` | 233 | the latest-on-test representation beside the digest-keyed evidence; readers convert to `result-at-reach` / `current-results` first |
| `seon.test/carried-member`, `carried-reports`, most of `carried-results` | ~45 | a reused result is a reference to the producing result, never a copy under fresh admission |
| `seon.test/root-agent?`, `verified?`'s program-digest arity, `held-red`, `fork-point-red` | ~60 | actor-based evaluator identity and red-at-base (P5 takes the last two) |
| `seon.test.runner/ambient-snapshot`, `ambient-drift`, `set-drift`, `live-cluster-schema-states`, `projection-form-keys`, `restore-live-cluster-schema!`, `schema-restore-drift` | 200 | protection of JVM-global state after the fact; deleted only after the tests that write ambient state run in branch contexts (P11d, counted first) |

Runner total: about **1,650 lines**. What B lists as candidates without a named caller conversion
(`seon.test/program-overlay-tx` 65, `checkpoint-head` 14, `prepare-checkpoint!` 26, `change-base` 11, `binding-lost?` 6: private to `seon.test`, not `seon.test.runner`) is
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

> The owner's later ruling of 2026-10-17 ("No automatic JVM boots", `merge-rulings.md`) is applied: P8 below is the
> rewritten row, `check-files!` and `isolated-run` are deleted there, and the §7 question is decided.


HEAD boots after every phase. Live chains keep working between phases because `done-refusals` stays the judge and
`seon.merge/merge!` stays the entrance throughout; each phase changes what feeds them. These stay green through all
phases (the check found two of them red on HEAD, §8: root classifies and repairs them before P4a; the rest were green): `seon.cluster.merge-test/a-requested-green-end-merges-and-publishes-with-no-hand-act`,
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
| **P1** One judge (45 min) | `src/seon/cluster/source.clj`, `src/seon/context.clj`, `test/seon/cluster/source_test.clj`, `test/seon/cluster/agent_test.clj` (a quoted `'seon.cluster.source/results-verdict` operation symbol at the coverage-refusal test), `src/seon/turn.clj` (a docstring reference), `resources/seon/schemas/seon.context.edn`, `resources/seon/schemas/seon.source.edn`, `resources/seon/schemas/seon.turn.loop.edn` (descriptions naming it; a publication change, so the commit goes through `bin/lane commit --test`), `resources/seon/tasks/seon.cluster.source.edn` (task text), `docs/seon/schedule/cards/TASK-CHAIN.md` §2.4 | `step-status` always calls `done-refusals`; `merge-verdict` calls `done-refusals` over its judged input; a Prove step's product is its next task's proofs (`:seon.source/next-proofs`). TASK-CHAIN §2.4: "`results-verdict` stays the judge of the final merge only" → deleted; done and merge are one function. | `results-verdict` (55), the `chained?` dispatch in `step-status`, the "Every proof must be a member" and dangling-task clauses | a non-chained row in `seon.cluster.source-test/done-refusals-judges-every-task-alike` (an own-branch assignment's step is judged by `done-refusals`); `results-verdict-judges-a-never-started-member-as-evidence` converted to `done-refusals`; the tests that stay green through every phase and call `results-verdict` (`done-and-merge-judge-one-member-list`, `done-and-merge-judge-a-red-reaching-test-the-same-way`, `member-fold-equals-the-full-merge-list`, `an-uncovered-refusal-names-the-red-tests-reaching-it`, all in `seon.cluster.source-test`) compare `done-refusals` with `merge-verdict` here and with `done-refusals` over S after P4a | — |
| **P2** One judged input (30 min) | `src/seon/context.clj`, `src/seon/cluster/agent.clj` | Extract `step-observation`'s builder as `seon.context/judged-input` `[main candidate task-sym cluster-name]` (the observation of a branch value against main's head: the agent's branch, or a chain branch at its attempt's end commit; `step-observation` calls it with `seon.task/main-head` and the agent's cluster); root's done pass (`advance-chains!`, `merge-chains!`) judges `:seon.source/judged` of it at the end commit. Root ruled option (a): a plain deletion, no cost claim. | `done-judged`'s derivation (46; a 10-line unwrapper of `:seon.source/judged` stays in `agent.clj`: it throws the error map or the no-run refusal) | root's done pass calls no `seon.program/fold-changes` (green on HEAD, so no new test), proven by the tests that reach the change: the done tests of `seon.cluster.source-test`, `seon.context-test`, `seon.cluster.db-flow-test` by var and `seon.cluster.merge-test` | none claimed: the delta is reused through the content-keyed `seon.program/own-changed-identities` cache |
| **P3** Coverage from the one selection (30 min) | `src/seon/cluster/source.clj`, `test/seon/cluster/source_test.clj` | `uncovered-functions` reads seed → tests from `member-contributions` instead of walking `seon.fn/gate-sets` per function (`gate-sets` stays: five other callers; the fold excludes `:seon.test/long` tests, which the walk includes, so the refusal's "tests that would cover it" loses declared-long ones; the check found the two equal on all six changed functions of three kept chain branches). | the `gate-sets` walk | a new assertion in `seon.cluster.source-test/an-uncovered-refusal-names-the-red-tests-reaching-it`: the judgment's `:seon.profile/called` holds no `seon.fn/gate-sets` (today one per function) | ms and alloc of the judgment at C = 20 |
| **P4a** The one merge (60 min; needs P1–P3) | `src/seon/cluster/source.clj`, `src/seon/merge.clj`, `src/seon/context.clj` (`branch-changes`, the live caller of `merge-seeds` and `merge-list`: converted here to show every Δ entity as merging, no `:stays`), `resources/seon/schemas/seon.source.edn`, `resources/seon/schemas/seon.merge.edn`, `resources/seon/schemas/seon.context.edn` (`:stays`), `test/seon/merge_test.clj`, `test/seon/cluster/merge_test.clj`, `test/seon/context_test.clj`, `docs/seon/specs/merge.md`, TASK-CHAIN §2.6 | `prepare-merge!` = compare, conflicts, fork S, one write (Δ's replacement + unmerged entries + `merge-tx`), `reaching-run` (lookup, run stale), `done-refusals` over `judged-input` on S. `accept-merge!` = one `seon.db/transact!` with `:parents #{B S}` and `:datahike/expected-basis-t` of the H the judgment read (the merge commit is already one transaction; the second write is `answer`'s, P4b); `merge-onto` has one path (a `:sent-back` source merges facts only; a done one everything). One merge base for program and facts. TASK-CHAIN §2.6 amended: whole delta; no merge list. Kept (live callers): `held-digests`, `unmerged-count`, the schema `:seon.source/expected-digests`, `merge-changes` and `agent-owned` (P12). | `merge-seeds`, `merge-list`, `carried`, `unselected-reach`, `:selection`, `merge-verdict`, `tested-task-basis`, `task-basis-refusal`, `task-basis-held-tx`, `declarations-held-tx`, `chain-row`, the first-parent walk in `assignment-row`, the second `merged` call, `:seon.merge/stays`, `:seon.merge/will-merge`, `:seon.context/stays`, the `:expected-digests` key of `prepared`, `:seon.source/tested-task-basis` | `seon.merge-test/a-merge-carries-only-what-its-tests-reach` rewritten as `a-merge-carries-the-whole-delta` (an unreached scratch function merges; today it stays); `a-prepared-merge-carries-its-merge-list-and-a-staying-conflict-refuses` → `a-conflict-keeps-the-merge-branch-with-mains-version-and-an-unmerged-entry`; new `seon.cluster.merge-test/main-moved-since-the-judgment-refuses-by-basis-and-the-next-pass-reruns-only-the-reached`: a write to main between judgment and transaction answers `:seon.db/transaction-refused-error` (the fence is proven on `seon.db/transact!` with `:parents` by `seon.db-test/a-merge-shares-the-write-fence-and-records-immutable-lineage`, green on HEAD), the next pass merges and its run executes exactly R_new members (a count of executed results, 0 when the move is outside the reach) | `seon.profile/measure` of `seon.merge/merge!` on `a-requested-green-end-merges-and-publishes-with-no-hand-act`'s chain, parent vs change: executed members 0 when main did not move; ms and alloc |
| **P4b** One transaction (30 min; after P4a) | `src/seon/merge.clj`, `src/seon/task.clj`, `src/seon/context.clj`, `src/seon/cluster/agent.clj`, `resources/seon/schemas/seon.task.edn`, `resources/seon/schemas/seon.chain.edn`, `resources/seon/schemas/seon.merge.edn`, `test/my/root_test.clj`, `test/seon/merge_test.clj`, `test/seon/cluster/merge_test.clj`, `test/seon/cluster/db_flow_test.clj`, `test/seon/cluster/registry_test.clj`, TASK-CHAIN §2.6 | The merge transaction writes `:seon.task/merged-declarations` from Δ's count itself (`publication-due?` keeps reading it); `:seon.task/merge-commit` is no longer recorded: every reader calls `seon.merge/merge-commit`, which already derives the commit from the `:merged` outcome through `seon.db/commit-at-t`. `answer`'s second `db/transact!` goes. | `:seon.task/merge-commit` (schema and its eleven reader files), the recorded-attribute branch of `merge-commit`, `answer`'s second transaction | new `seon.cluster.merge-test/a-merge-writes-one-transaction-on-main`: `seon.db/commit-at-t` of the `:merged` outcome equals the merge commit and `:seon.task/merge-commit` is absent from the row while `:seon.task/merged-declarations` equals Δ's count (today the attribute is a second commit and the test is red) | transactions on main per merge, parent vs change: 1 (today 2); ms and alloc |
| **P5** Red is red (45 min; after P4a) | `src/seon/cluster/source.clj`, `src/seon/context.clj`, `src/seon/turn.clj`, `src/seon/test.clj`, `resources/seon/schemas/seon.source.edn`, `test/seon/cluster/source_test.clj`, `test/seon/context_test.clj`, `test/seon/cluster/merge_test.clj`, `test/seon/test_test.clj`, `resources/seon/schemas/seon.test.edn`, `resources/seon/schemas/seon.test.member.edn` (descriptions naming `fork-point-red`), `resources/seon/tasks/seon.test.edn`, `resources/seon/tasks/seon.turn.edn`, `resources/seon/tasks/seon.cluster.source.edn` (task text), TASK-CHAIN §2.4 | `done-refusals`: a red member not among the next proofs refuses, one clause, one type. TASK-CHAIN §2.4 loses `:seon.source/red-on-target` and `:seon.source/stray-red-error`. | `red-at-merge-base`, `held-red`, `fork-point-red`, `run-merge-base`, `:seon.source/red-on-target`, `:seon.source/stray-red-error`, `seon.test-test`'s `fork-point-red` test | the flipped rows: `done-refusals-judges-every-task-alike` row "a test red at the merge base too is not the change's" → refuses `:seon.source/red-test-error`; `seon.context-test` row 771 likewise; `done-and-merge-judge-a-red-reaching-test-the-same-way` and `seon.cluster.merge-test/a-test-never-run-on-the-target-is-not-blamed-on-the-merge` rewritten: a red reaching test refuses whether or not main holds it red | — |
| **P6** One test request per step (45 min; file-disjoint from P1–P5) | `src/seon/turn.clj`, `test/seon/turn/test_step_verdict_test.clj`, `test/seon/reply_consistency_test.clj` (calls `turn/branch-due`), `test/seon/turn_test.clj` (calls `turn/step-proved?` twice), `test/seon/test_support.clj` (a docstring naming `run-proofs`) | `test-step` computes stale over T ∪ branch tests ∪ proofs once and runs ONE `seon.test/run`; `prove-turn` is `test-step` with no evaluated form; the judgment is read once from `step-verdict`. | `run-branch`, `run-proofs`, `branch-due`, `step-reds`, `step-proved?`, `prove-turn`'s ordering | a new assertion in `seon.turn.test-step-verdict-test/the-step-verdict-covers-every-test-of-the-member-list`: one `:seon.test.run/id` written per step (today up to four) | `seon.profile/measure` of one step with six changes, parent vs change (B's baseline 156–177 ms / 201–216 MB) |
| **P7** One file proof, consumed thrice (45 min; after P4a) | `src/seon/cluster/source.clj`, `src/seon/merge.clj`, `resources/seon/schemas/seon.source.edn` (the write-back reason enum), `test/seon/cluster/write_back_test.clj` | `publication` keys its proof by the changed files' content digests (not main's commit) and holds candidate bytes + analysis; `prepare-merge!` and `write-back!` take the held proof; `seon.merge/indexed!` consumes the held analysis. Lint runs once per file content. Also here (no earlier phase can: `write-back-commits` calls them): the write-back's `:row-changed`, `:no-replacement` and `:one-file` reasons and `moved-digests`, with the enum entries. | the re-proof in `prepare-merge!` (`root-refusal`, second `proven-files`), the re-proof in `write-back!`, `indexed!`'s re-analysis, `moved-digests`, `:row-changed`, `:no-replacement`, `:one-file` | a new assertion in a write-back test: `:seon.profile/called` over merge + publish holds exactly one `seon.cluster.source/proven-files` and one `lint-errors` (today three and two); `an-agent-replacement-merges-and-writes-back-its-exact-span`'s "a later change to a written entity refuses by name" becomes "writes back the accepted value" (today it asserts `:row-changed`) | ms and alloc of merge + publish, parent vs change (TASK-CHAIN §2.4's 280 ms / 0.7 GB per uncached proof is the term that drops to once) |
| **P8** Automatic testing never boots a JVM (45 min; after P7; owner ruling 2026-10-17, "No automatic JVM boots") | `src/seon/turn.clj`, `src/seon/cluster.clj`, `src/seon/cluster/source.clj`, `src/seon/context.clj`, `src/seon/cluster/agent.clj`, `src/seon/test.clj`, `src/seon/test/platform.clj`, `test/seon/babashka_load_test.clj`, `resources/seon/schemas/seon.source.edn`, `resources/seon/schemas/seon.turn.loop.edn`, `resources/seon/schemas/seon.test.edn`, `docs/seon/architecture/vocabulary.md`, `test/seon/merge_test.clj`, `test/seon/cluster/source_test.clj`, `test/my/test_check_test.clj`, TASK-CHAIN §2.4 | (1) A member declaring `:seon.test/reads` (today one test, `seon.babashka-load-test/declared-namespaces-load-in-babashka`) runs in the one JVM over the candidate's files: `seon.test/host-exclusions` stops deferring it; the step (`seon.turn/run-reaching`) and the merge (`seon.cluster/reaching-run`) pass `seon.test/run` the candidate's bytes (`seon.cluster.source/proven-files`, as `:seon.test/files`) when a stale member reads files; the runner clones the published roots with `seon.test.platform/snapshot!`, writes the bytes over the clone in a disposable directory, binds `seon.test/*files-root*` for that member's body and deletes the directory after the body exits; the test's `hook-roots` resolve against it (default: the working directory), and it may launch `bb` (about 0.2 s for its ten namespaces, measured). The two new names are recorded in vocabulary.md first. (2) What the one JVM must not run is data, not a boot: `seon.turn/judged-input` carries the changed `:seon.fn/host-bound?` functions (`:seon.source/host-bound`, the query `platform-proof` makes today) and `done-refusals` refuses them, and every member of `:seon.source/deferred` (`host-exclusions` with `isolate?`, probed on default: 49 destroyer-reaching and 24 fixture-observation or reads of 3,323 tests, the reads one leaving the set in (1); the 112 tests declaring `:seon.test/platform` are not deferred by `host-exclusions` and change nothing here; none of the five kept chain branches reaches a deferred member) as ONE `:seon.source/test-evidence-error` naming them under `:seon.error/offending`; the chain bails to root, which proves platform work with `bin/seon check`. TASK-CHAIN §2.4 amended (the new judged key, no new type). | `seon.test.platform/check-files!` (35), `seon.cluster/isolated-run` (13) and its call in `reaching-run` and `candidate-gate!`, `:seon.source/platform-run` and the `:seon.test.platform/ms` phase, `seon.turn/platform-proof` (17) and `:seon.turn.loop/platform`, `check-platform`'s offer, the `:seon.source/deferred` exemption in `done-refusals`, `:seon.test/reads` in `host-exclusions` | `seon.merge-test/the-gate-runs-a-file-reading-test-against-the-candidate-and-refuses` rewritten as `a-file-reading-test-runs-in-the-one-jvm-over-the-candidates-files` (same setup and `:seon.test/long` bound; today it asserts `:seon.source/platform-run`, a `bin/seon check` boot): the prepared merge names the reader under `:seon.source/red-tests`, holds no `:seon.source/platform-run`, no `:seon.test.platform/ms` and no `:seon.test.run/git-sha`, and its `:seon.profile/called` holds no `seon.test.platform/run-check!`; `seon.cluster.source-test/done-refusals-judges-every-task-alike` row "a deferred member is the merge's platform run's to answer, so done waits for none" flips to `[:seon.source/test-evidence-error]` naming the member (probed on default: `done-refusals` answers `[]` today), plus a row: a changed host-bound function in `:seon.source/host-bound` refuses `[:seon.source/test-evidence-error]` | ms of the merge on that chain, parent vs change (A's 38.9 s → the `bb` cost, about 0.2 s plus the directory write); boots per merge: 0 (assert no `seon.test.platform/run-check!` call) |
| **P9a** Convert the callers of the retired verbs (45 min; after P8) | `bin/seon-hook`, `script/seon/dev/context_corpus.clj`, `script/seon/interpreted_census.clj`, `src/seon/turn.clj` (`check-now` and the check offers), the 25 test files that call `my.test/check` (`test/seon/context_test.clj`, `test/seon/cluster/host_test.clj`, `test/seon/test/redefined_callee_test.clj`, `test/seon/sci/library_aliases_test.clj`, ... the list `rg 'my.test/check'` gives; `test/my/test_check_test.clj` and `test/seon/test/platform_test.clj` are deleted with their subjects) | `bin/seon-hook`'s post-edit check (`(my.test/check {:seon.test/changed ...})`, a live executing caller) becomes `my.test/run` over the changed test symbols, or the `seon.test/run` request it already wraps; the other callers convert to the same. Nothing is deleted here: both verbs exist until P9b. | — | `seon.dev.hook-test` (the hook's reply over a changed symbol is unchanged, asserted on its data) green on `my.test/run`; `rg 'my.test/check' src script bin test` empty except P9b's own deletions | — |
| **P9b** One verb; help; skill; offers (45 min; after P9a) | `src/my/test.clj`, `src/seon/render/var.clj`, `src/seon/sci/lookup.clj`, `src/seon/mcp.clj`, `src/seon/batch.clj`, `src/seon/test/platform.clj`, `resources/seon/help.md`, `.agents/skills/clojure-testing/SKILL.md`, `resources/seon/tasks/my.test.edn`, `resources/seon/tasks/seon.test.edn`, `resources/seon/tasks/seon.test.platform.edn`, `test/seon/bootstrap_test.clj`, `test/seon/render/var_test.clj`, `test/seon/mcp_entrance_test.clj` (`run-owned`), `test/seon/test/platform_test.clj` | `my.test/run` as §3.4; help's four statements (§3.5); skill §5 rewritten; `agent-test-vars` routes to `run`; retire 11 `my.test.edn` tasks and the `seon.test.platform.edn` tasks, move `a-two-test-check-allocates-a-gigabyte-and-a-half` and `a-defn-request-runs-four-tests-for-seven-gigabytes` to `seon.test.edn` as runner tasks; delete the rest of `seon.test.platform` (`snapshot!` stays only if P8's files root still calls it: then it moves to `seon.test`). | `check`, `check-now`, `check-platform`, `run-owned`, the `run` macro, the Var render's offer, the `check-platform` prose in `seon.mcp`, `seon.batch`'s docstring reference, `seon.test.platform` (172 left after P8) | new `seon.bootstrap-test/help-references-one-test-verb` (help's `:seon.help/references` resolve `my.test/run` and no `my.test/check`); new `seon.render.var-test/a-var-render-offers-no-test-run` (a pending or newly-red Var's render carries no `:seon.render/operations` naming a test verb) | — |
| **P10** What will merge (45 min; after P4a) | `src/seon/context.clj`, `resources/seon/help.md`, `test/seon/context_test.clj` | `branch-changes` lists every entity of Δ by family (functions, tests, schemas, namespaces, and the chain's task entities from the facts squash), each with its status from the head entries and the `my.program/ns-unmap!` form; removed entities under `:seon.context/removed`. (P4a already converts `branch-changes` off `merge-list`, so every entry shows as merging; P10 adds status and the removal form.) | `:seon.context/stays` | new `seon.context-test/the-block-shows-every-change-that-will-merge-with-its-test-status`: a branch with one changed function (reached, green), one new test (red) and one new schema shows all three with status and the removal form (today an unreached change shows `:stays` and no status) | ms of `current-state` at C = 20, parent vs change |
| **P11a** Runner: one request (60 min; after P6 and P9b) | `src/seon/test.clj`, `src/seon/test/runner.clj`, `test/seon/test_test.clj`, `test/seon/test/selection_test.clj`, plus the callers the check found outside those files: `test/seon/pass_cost_test.clj`, `test/seon/pass_probe.clj`, `test/seon/test/one_request_test.clj` (`select`), `test/seon/program_test.clj` (`changed-since-green`), `script/seon/cost_record2.clj` (`digest-differences`), `script/seon/agent_cost.clj` (`carried-results`), `resources/seon/schemas/seon.test.member.edn` | `seon.test/run` takes `{:seon.test/execution :seon.test/identities :seon.test/rerun?}`; stale by reach digest; resolve in the fork; `run-vars!`; `commit-results!`. Pre-brief (Haiku) lists every caller of `select` and the policies, and settles `program-overlay-tx`, `checkpoint-head`, `prepare-checkpoint!`, `change-base`, `binding-lost?`. | `select` and the selection family (666), the `:incremental` / `:platform` / `:all` policies, `run-request` → ~120, `admit-run`'s selection and scope work, `root-agent?`, `verified?`'s digest arity, `seon.test.selection-test` (its two historical reds included) | new `seon.test-test/a-no-op-evaluation-writes-no-run` (an eval request that changed nothing writes no `:seon.test.run/id`; today the step's requests admit runs) | `seon.profile/measure` of the step's request: no `seon.test/select` call; ms and alloc parent vs change (B's first-request 1,066 ms / 1,316 MB is the target term) |
| **P11b** Runner: evidence is referenced, never copied (60 min) | `src/seon/test.clj`, `src/seon/test/runner.clj`, their tests, and the readers of `latest-results`/`newest-results` outside them (`src/my/program.clj`, `src/seon/problems.clj`, `src/seon/render/var.clj`, `src/seon/mcp.clj`, `src/seon/turn.clj`, `src/seon/cluster/source.clj`) | Convert `latest-results`, `newest-results` and every reader of the latest-on-test representation to `result-at-reach` / `current-results`; a reused result is a reference to its producing result. | `record-latest-tx`, `failure-replacement-tx`, `designed-red?`, `carried-member`, `carried-reports`, most of `carried-results` | new `seon.test-test/a-reused-result-is-referenced-not-copied`: after a reuse the count of result datoms is unchanged (today a copy is admitted) | reads per reuse, parent vs change |
| **P11c** Runner: no read-time rechecks (30 min) | `src/seon/test/runner.clj`, `src/seon/test.clj` | The writer's admission and exit facts are the only admission; readers stop re-validating counts and bases. | `admitted-members`, `program-written-since?`, the `:seon.test/invalid-basis` and `:seon.test/program-mismatch` re-admission comparisons | new assertion in `seon.test-test`: a lookup of R = 80 results makes no `admitted-members` call (`:seon.profile/called`) | reads per lookup |
| **P11d** Runner: compartments, then no ambient repair (60 min; count first) | `src/seon/test/runner.clj`, `test/seon/test_support.clj` (calls `restore-live-cluster-schema!`), the converted tests | Haiku counts the tests whose stored runs reported ambient drift and the JVM-global state they write; each converts to a branch context (`support/with-database`, the member's fork) first; then the snapshot/restore goes. If the count is zero, the deletion is immediate. | `ambient-snapshot`, `ambient-drift`, `set-drift`, `live-cluster-schema-states`, `projection-form-keys`, `restore-live-cluster-schema!`, `schema-restore-drift` (200) | new `seon.test-test/a-members-own-schema-change-leaves-its-sibling-unchanged` (B's phase 4 proof; today the sibling sees it only through the restore) | ms and alloc of `run-vars!` on a 10-member run, parent vs change |
| **P13** No new guards: the error law per phase (owner 2026-10-17: convert as touched, never everything at once, never code scheduled for deletion) | every phase's own files | Not a sweep. Each phase P4a–P12 applies AGENTS.md's error law (1821d961f) to the functions it keeps or writes: defect refusals throw to the entrant boundary, expected interop failures are one specific declared variant, fallible reads at the operation's start, re-checks of contract guarantees deleted. Code a phase deletes is deleted, never converted; untouched code waits for its toucher. This row lands one census early (beside P4a): `seon.guard-census-test/a-changed-function-holds-no-guard` checks only functions whose definition changed since the law's commit (by definition digest against that commit, a ratchet, no hand list), failing on a catch other than an edge's own translation or the entrant boundary, or an error-shape check outside an edge. | guards in each phase's kept code | the census: green at landing (no function changed since the law yet), red for any later change that adds a guard | — |

**Ordering.** P1 → P2 → P3 → P4a → P4b are serial on `source.clj` and `merge.clj`; P6 runs beside them (turn.clj and
its tests only); P5 after P4a; P7 after P4a; P8 after P7; P9a after P8; P9b after P9a; P10 after P4a and beside
P5–P9b; P11 after P6 and P9b; P12 whenever its producer task is implemented. P1, P4a, P4b and P8 change schema
resources or publication, so they commit through `bin/lane commit --test` (platform class), not `--branch-evidence`.
Root batches one restart after P4a (schema resources change) and one after P9b. TASK-CHAIN §2.4 and §2.6 are amended
in P1, P4a, P4b, P5 and P8, each with its red deftest (the task-chain design is locked). The S-reads conversion (≈175 `:seon.error/at` guard sites on this
path) stays the errors spec's work, done per owner after P11.

**Live chains during the cut.** Between P4a and P5 `red-on-target` stays wired (P4a keeps `red-at-merge-base`); P5
removes the excuse in one place. After P8 a chain whose change reaches a deferred member or a host-bound function
is refused at done as data and bails to root; before P8 it merged with those members unrun. Between P8 and P9b the
verb `my.test/check-platform` exists with no offer and no automatic caller; harmless.

---

## 5. Totals

| | Lines today (function spans) | Deleted in P1–P11 | Kept (some rewritten) |
|---|---|---|---|
| Merge and done path (`seon.cluster.source` merge section, `seon.merge`, `seon.cluster` gate, `seon.context` step, `seon.turn` step, `seon.cluster.agent` done, `my.test`, `seon.test.platform`, red-at-base in `seon.test`) | ≈2,000 (A's 4,330 includes write-back's 968 and the 400-line turn step whole) | ≈1,250 | ≈950 + write-back 968 |
| Runner (`seon.test`, `seon.test.runner`) | 6,013 | ≈1,650 | ≈4,350 (execution, fixtures, reach index, capture, recording, exit) |
| Guard sites (S-reads, later) | ≈175 sites | — | — |

About 2,900 lines go (the check's corrections: `unmerged-count` stays, −14; P8 deletes `check-files!`, `isolated-run` and `platform-proof` (≈65 against the 25 first counted), +40; P9b also deletes the 172 lines of `seon.test.platform` left after P8, +172); nothing new is added beyond `judged-input`'s name, `my.test/run`'s body, the files root's two names (`:seon.test/files`, `seon.test/*files-root*`) and
its directory write, the host-bound refusal key, the rewritten `branch-changes` and the new deftests. Both analyses' claims of larger cuts rest on work
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

None. The platform-boot question this section held ("where the step's platform boot waits") is decided by the owner's
ruling "No automatic JVM boots" (`merge-rulings.md`): automatic testing never boots a JVM, P8 is rewritten to it, and
there is no boot to wait for.

---

## 8. Narrow check (lane merge-check, 2026-10-17)

Method. Live claims: read-only JVM-mode forms on default through MCP `eval_clj` (never SCI as root), against the
running JVM, which is **d76806d39, 17 commits behind HEAD 2fd101c25** (default pid 18697, `:faulted` with five open
faults that predate this lane); line counts and callers are from HEAD's files (`rewrite-clj` spans in `bb`, exact-token
`rg` over `src test script resources bin .agents docs/seon`). Proofs: `bin/seon check --at HEAD --include-long` over
the named tests (two runs, 61 s and about 25 s). Scripts are in `tmp/merge-check/`.

### 8.1 Claims

| # | Claim | Form, value, time | Verdict |
|---|---|---|---|
| 1 | `merge-members` 0.06–0.08 ms warm | `(seon.cluster.source/merge-members bdb delta #{})` on `:chain-42e0e4c46b71` (C = 7, R = 80): 0.040–0.082 ms, 24.6 KB | reproduced |
| 2 | `reach-digests` 0.58 / 0.17 ms warm (R = 80 / 1), 1.0 MB / 34 KB | 0.55–0.94 ms, 1008 KB (R = 80); 0.056–0.15 ms, 33.6 KB (R = 1) | reproduced |
| 3 | `current-results` 0.13–0.24 ms | 0.22–0.33 ms (R = 80), 0.10–0.15 ms (R = 1) | reproduced (upper end 0.33) |
| 4 | Δ: `own-changed-identities` about 1 ms warm, cached by content | 0.03–0.16 ms warm on five kept chain branches | reproduced (cheaper) |
| 5 | Merge base cached by the id pair, < 5 ms warm | 0.11–0.13 ms on the second call, 3.8–10.5 ms on the first per branch; the cache is `seon.cache/lookup ::merge-base` (read) | reproduced; the cold first call is 4–10 ms |
| 6 | Row 11 "stale check about 1 ms + runs" | `seon.test/stale` warm: 4.5 ms / 4.5 MB (R = 80), 0.24 ms (R = 1) | partial, fixed in §2 row 11 |
| 7 | Row 8 `contract-gaps` 5–12 ms | 0.7–0.8 ms warm, 0.5 MB | reproduced (the cold figure not measured) |
| 8 | `seon.db/transact!` passes `:datahike/expected-basis-t` and `:parents` to `merge-db!` | read `seon.db/transact-call` (`((if (:parents request) d/merge-db! d/transact!) connection request)`; `prepared-write` keeps the map's keys); proven by `seon.db-test/a-merge-shares-the-write-fence-and-records-immutable-lineage`, green on HEAD (run 2eadd522a22d) | reproduced |
| 9 | The fence is enforced in Datahike's `writing.cljc` | `datahike.writing/transact!` throws `:transaction/stale-basis` unless `:max-tx` equals the value; `merge-writer!` calls it; `writer.cljc` maps op `merge!` to it (submodule ee3f8ec8) | reproduced |
| 10 | `seon.cluster/isolated-run` boots only when a deferred member exists | read: `(when (and (seq deferred) (seq files)) ... check-files!)` | reproduced (moot: P8 deletes it) |
| 11 | A's 38.9 s "boot per merge" end to end | the ruling forbids the boot; code reading only | not reproduced |
| 12 | Fork S 15 / 23 / 36 ms; transaction 20–60 ms; write-back F × 50–300 ms; file proof 280 ms / 0.7 GB | these write (branch, store, files): not run read-only | not reproduced |
| 13 | `registry/fork!` is the entrance the gate uses | `seon.cluster/candidate-gate!` calls `registry/fork!`; both `fork!` and `branch!` exist | reproduced |
| 14 | `seon.db/commit-at-t` and `seon.merge/merge-commit` derive the commit from the `:merged` outcome | read both: `merge-commit` already falls back to `commit-at-t` | reproduced; it changes P4 (8.2) |
| 15 | P3: the member fold's seed-to-tests equals the `gate-sets` walk | per changed function of three kept chain branches: `seon.fn/gate-sets` against `merge-members`, six functions, sets equal, no declared-long test among them | reproduced (n = 6; declared-long tests are the one theoretical difference, noted in P3) |
| 16 | `done-judged` calls `merge-base` and `own-changed-identities` itself | read `seon.cluster.agent/done-judged`; called by `advance-chains!` and `merge-chains!` | partial: two callers, fixed in P2 |
| 17 | "five derivations" of Δ | exact callers of `own-changed-identities`: `seon.context/change`, `seon.cluster.source/compare-commit`, `seon.test/changes`, `seon.cluster.agent/done-judged`, `seon.step` via `seon.test/branch-delta` | reproduced |
| 18 | The file proof runs three times, lint three times | `proven-files`: `publication`, `prepare-merge!`, `write-back-commits` = 3; `lint-errors`: `publication`, `file-refusal` = 2 | partial, fixed in row 10 and P7 |
| 19 | `my.test.edn` holds 13 tasks | `rg -c` = 13 | reproduced |
| 20 | Function-span line counts used for deletions | HEAD spans: `results-verdict` 55, `done-judged` 46, merge-seeds + merge-list + carried + unselected-reach 84, P4's second row 158 (61+13+18+14+18+5+15+14), the select family 666, `run-request` 415, `admit-run` 231, the ambient family 200, `seon.test` + runner 6,013, the `my.test` check family 155 (plan 152), `check-files!` 35 | reproduced except: the facts-squash row is **176**, not 215 (fixed); the carried-results family is 54, not about 45; `unmerged-count` is not deletable (8.2) |
| 21 | Names exist as stated | all exist except: `program-overlay-tx`, `checkpoint-head`, `prepare-checkpoint!`, `change-base`, `binding-lost?` are private to **`seon.test`**, not the runner (fixed in 3.3); `seon.cluster.agent/agent-creation-writes-no-program-namespace` is a filed task, not a function (`resources/seon/tasks/seon.cluster.agent.edn`); `latest-results` and `newest-results` live in `seon.test.runner` | partial, fixed |
| 22 | `isolated-members` and `host-exclusions` are kept and sound | `isolated-members` has one caller, `bin/test-check` (root's `bin/test --platform`), and no test; **it throws on default**: `(#'seon.test/isolated-members db)` answers `Insufficient bindings: none of #{?test} is bound in (not-join [?test] [?test :seon.test/fixture])` in 6.5 ms, while the same query run directly returns 10 rows | refuted as "sound"; finding in 8.4 |
| 23 | Counts behind P8 | default: 3,323 tests; `:seon.test/reads` 1 (`seon.babashka-load-test/declared-namespaces-load-in-babashka`), `:seon.test/fixture-observation` 51, `:seon.test/platform` 112; `host-exclusions` with `isolate?`: 49 destroyer-reaching and 24 deferred; none of five kept chain branches has a deferred member; `bb` loads the ten declared namespaces in 0.22 s | reproduced (new data) |
| 24 | The listed "stay green through all phases" tests are green | `bin/seon check --at HEAD`: 19 of 21 executed green; **red on HEAD, twice (2 of 2)**: `seon.cluster.merge-test/a-requested-green-end-merges-and-publishes-with-no-hand-act` (main never records `:merged`, `merge_test.clj:603`; the `rev-parse HEAD^` error at line 254 follows) and `seon.cluster.db-flow-test/a-done-merge-step-merges-its-branch-onto-main` (reason "The final merge refused it." lacks the `:seon.source/write-back-error` suffix, `db_flow_test.clj:2595`). Cause not isolated (time-box); both reach the write-back | refuted |

### 8.2 Deletion order: callers outside the converting phase, and the fix

| Symbol | Live caller outside its phase's files | Fix applied |
|---|---|---|
| `results-verdict` (P1) | `agent_test.clj` (quoted `'seon.cluster.source/results-verdict` operation symbol), a `turn.clj` docstring, schemas `seon.context`, `seon.source`, `seon.turn.loop`, task text | P1 files extended; schema descriptions publish, so the commit is platform class |
| `merge-seeds`, `merge-list`, `:seon.merge/stays`, `:seon.context/stays` (P4) | `seon.context/branch-changes` (live) | P4a converts `branch-changes` in the same phase; P10 adds status |
| `unmerged-count` (P4) | `seon.cluster.agent` twice (`:seon.flow.actions/unmerged`, `work-left?`), `seon.merge/merge-onto`, schema `seon.flow.actions` | not deleted |
| `held-digests`, schema `:seon.source/expected-digests` (P4) | seven call sites in `source.clj`; `seon.cluster.branch-evidence` declares the schema | not deleted (only `prepared`'s key goes) |
| `moved-digests` (P4) | `write-back-commits` (the `:row-changed` clause) | moved to P7 with `:row-changed`, `:no-replacement`, `:one-file` (no phase held them before) |
| `:seon.task/merge-commit`, `:seon.task/merged-declarations` (P4) | `seon.task` facts, `seon.context/step-before`, `seon.cluster.agent` (`publication-due?`, lifecycle facts), three schemas, five tests | P4b; `merged-declarations` stays (written in the one transaction), `merge-commit` is derived by `commit-at-t` |
| `merge-changes`, `agent-owned` (P4) | `seon.context` three times, `compare-commit` and `unmerged` | P12, after the filed producer task |
| `red-at-merge-base`, `fork-point-red` (P5) | descriptions only (`seon.test`, `seon.test.member` schemas, three task files) | P5 files extended |
| `branch-due`, `step-proved?` (P6) | `reply_consistency_test.clj`, `turn_test.clj` twice | P6 files extended |
| `check-files!`, `isolated-run`, `platform-proof`, `:seon.source/deferred` (P8) | `context.clj`, `agent.clj`, `source_test.clj`, `test_check_test.clj`, three schemas | P8 rewritten with them |
| `my.test/check` (P9) | `bin/seon-hook` (executes it after every edit), 25 test files, two scripts, `seon.turn` | P9a converts the callers; P9b deletes |
| `seon.test/select` family (P11a) | `pass_cost_test`, `pass_probe`, `one_request_test`, `program_test`, `cost_record2.clj`, `agent_cost.clj` | P11a files extended; its Haiku pre-brief confirms |
| `latest-results` and `newest-results` readers (P11b) | `my.program`, `seon.problems`, `seon.render.var`, `seon.mcp`, `seon.turn`, `seon.cluster.source` | P11b files extended |

### 8.3 Proof status per phase

| Phase | Named proof | Status on HEAD |
|---|---|---|
| P0 | the two salvaged deftests | `same-evaluator-red.patch` exists; the two task files exist and hold neither task; red by design |
| P1 | flipped rows of `seon.cluster.source-test/done-refusals-judges-every-task-alike` and `results-verdict-judges-a-never-started-member-as-evidence` | tests exist (`source_test.clj:444, 650`) and are green as written (check run), so the flipped rows are red once written |
| P2 | root's done pass calls no `seon.program/fold-changes` (green on HEAD) | root ruled option (a): P2 is a deletion with no cost claim and no new test, proven by the reaching done and merge tests (lane merge-p2's probe: root's done pass called `seon.program/own-changed-identities` once and `seon.cluster.source/merge-base` once, 15.4 ms, 2.25 MB, and `seon.program/fold-changes` zero times, because the step's delta is reused by content revision inside `own-changed-identities`) |
| P3 | assertion in `an-uncovered-refusal-names-the-red-tests-reaching-it` | exists (`source_test.clj:625`, green); `uncovered-functions` calls `gate-sets` per function (read; 3–20 ms each); the equality premise reproduced |
| P4a | two rewrites and one new test | `a-merge-carries-only-what-its-tests-reach` (`merge_test.clj:664`) and `a-prepared-merge-carries-its-merge-list-...` (`:779`) exist and are green (check run), so the rewrites are red; the new test is specified; the fence's own test is green |
| P4b | `a-merge-writes-one-transaction-on-main` | new; red: the attribute is recorded by a second transaction (`merge-onto`'s `answer`) |
| P5 | flipped rows | probed on default: `done-refusals` with `:seon.source/red-on-target` answers `[]` where the new spec says `[:seon.source/red-test-error]`; `a-test-never-run-on-the-target-is-not-blamed-on-the-merge` exists (`cluster/merge_test.clj:805`) |
| P6 | assertion in `the-step-verdict-covers-every-test-of-the-member-list` | exists (`test_step_verdict_test.clj:30`, green); up to four requests per step by reading `test-step` (`run-reaching`, `run-merge-base`, `run-branch`, `run-proofs`) |
| P7 | assertion in a write-back test; `an-agent-replacement-merges-and-writes-back-its-exact-span` | exists (`write_back_test.clj:132`); counts corrected to three and two; the `:row-changed` assertion (`:200`) is the red-to-green row |
| P8 | rewritten `a-file-reading-test-runs-in-the-one-jvm-over-the-candidates-files`; flipped deferred row | the base test `the-gate-runs-a-file-reading-test-against-the-candidate-and-refuses` exists (`merge_test.clj:730`, long, not run: it boots a JVM); the deferred row probed on default: `[]` where the new spec says `[:seon.source/test-evidence-error]`; the reads member is green in the check JVM (cwd = the checkout) |
| P9a | `seon.dev.hook-test` over `my.test/run` | exists; the hook's call is a live `my.test/check` today |
| P9b | `help-references-one-test-verb`, `a-var-render-offers-no-test-run` | new; red: help names no `my.test/run`, the Var render offers `(my.test/check ...)` (`render/var.clj:159`) |
| P10 | `the-block-shows-every-change-that-will-merge-with-its-test-status` | new; red by reading `branch-changes` (shows `:stays`, no status) |
| P11a–d | `a-no-op-evaluation-writes-no-run`, `a-reused-result-is-referenced-not-copied`, a lookup with no `admitted-members` call, `a-members-own-schema-change-leaves-its-sibling-unchanged` | new; specified; not run (they need the P6 and P9b bodies) |
| P12 | none here | the producer task `agent-creation-writes-no-program-namespace` is filed with its own proofs |

### 8.4 Findings outside this lane's assignment

- **`seon.test/isolated-members` throws on default** (claim 22). Its only caller is `bin/test-check`'s `--platform` path, so `bin/test --platform` is broken there, and no test covers it. Evidence: the form in claim 22, 6.5 ms. No issue file was written: this lane commits only this file and assigned lanes never edit the issue index; root files it. It blocks no phase: P8 keeps the function and a deliberate `bin/seon check` does not call it.
- **Two "stay green" merge tests are red on HEAD** (claim 24): root classifies them (the write-back path in the check JVM) before P4a, or the guard list has no baseline.
- **Default runs d76806d39, not HEAD**: every live figure above is from that commit; P4a's restart batch is the first point at which a probe of `merge-members` and `branch-changes` sees HEAD's `reaching-selection`.
