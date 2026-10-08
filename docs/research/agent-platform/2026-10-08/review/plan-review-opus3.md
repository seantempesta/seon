---
type: review
status: done (lane plan-review-opus3, 2026-10-08)
tags: [review, agent-loop]
---
# Plan review (Opus, third pass): the ledger after `088c62000`

Reviewed: `docs/seon/schedule/agent-loop-ledger.md` at `088c62000`, against `review/owner-rulings.md` (including the
rulings added in `6548ab9d3` and `4707b9546` while this review ran), CLAUDE.md/AGENTS.md ("Words and where they come
from"), `docs/seon/specs/agent-loop.md`, `docs/seon/specs/merge.md` and `reinvention/reinvention-opus.md`.

Method: every cited commit checked with `git log -1` (all 55 exist and match their rows); `git log` since 2026-10-06
for landings the ledger misses; every backticked path checked against the tree; the functions the rows name grepped
at their stated files; dependencies parsed by script (254 rows, 45 landed ids) for cycles, unknown ids and edges to
landed rows; file contention counted per file; the live system read through MCP as agent `plan-review-opus3`
(`runtime_status`, one JVM read of heap and loaded commit, 1 ms). No dependency cycle was found.

## Fixes applied in the ledger (and the spec)

Factual corrections:

1. **PB-02 status was contradicted by git.** It said "in flight (led-pb02b)"; `754e20ff6` landed it after the ledger
   commit. That commit changes only `src/seon/sci/eval.clj`: the row's named proof
   (`seon.sci.branch-execution-test/the-installation-check-reads-only-program-rows`) does not exist, and led-pb02b still
   holds its claim. Status now says so, and §5 asks for the proof before PB-02b claims `eval.clj`.
2. **LR-CACHEb named the wrong files.** It listed `src/seon/test/runner.clj` (held by led-storage). The lane's claim and
   uncommitted diff are `db.clj` (`remember-revisions!`, `content-revisions`), `cluster/registry.clj`, its schema,
   `cluster/agent.clj`, `my/agent.clj` and three test files. Corrected.
3. **EN-7 called a proof "existing" that does not exist.** `seon.sci.documentation-test/run-test-var-answers-one-branch-test-as-data`
   is S7-01's new test. Corrected.
4. **EN-2's file was wrong.** `record-evaluated-call` is at `src/seon/turn.clj:1317`, not in `turn/batch.clj`. Added.
5. **S4-05a's `my.agent/inputs` does not exist at HEAD.** Marked new.
6. **F0's proof is a table row, not a deftest.** `later-pass-redefinition-reaches-earlier-callers` is a row in
   `seon.repl-differential-test` (`test/seon/repl_differential_test.clj:21`). Corrected.
7. **RP-0** is now lane defn-why, an expectation table for a one-line `defn` (coordinator). Status updated.

Owner answers folded in (coordinator, `6548ab9d3`):

8. **RP-9:** a nested `defn` is saved from its own source span; one closing over a local stays live-only with a note.
   Its proofs renamed to match; it now waits only on the pass redesign approval.
9. **F5:** a code task with no change against main is done after checking main still holds the tested program; new
   proof `…/a-code-task-with-no-change-is-done-without-a-merge-request`; status `ready`.
10. **Subtree counts:** new §3.23 with DH-1 (verify first on a fresh store; old format means reset, not fork code),
    DH-2 (keep counts on every write, only if DH-1 shows writes drop them) and DH-3 (range counting:
    `seon.db/planner-estimates-load-every-node-of-the-counted-range`, child counts in branch nodes, store size
    measured). **Finding while writing it:** there is no Seon fork of the sorted set. Datahike pulls
    `org.replikativ/persistent-sorted-set` 0.4.137 from Maven (`reference-code/datahike/deps.edn:23`); DH-2 starts one.
    The task's earlier evidence (`tmp/store-writes/subtree_counts.clj`) is in gitignored `tmp/`; DH-1 commits it.
11. §6 keeps only the two redesign approvals, noting that root asks them after the transaction census.
    `agent-loop.md` §6 gains decisions 31-35 and its "Open" line says the same.

Owner rulings added as rows (coordinator):

12. **"Know what things are"**: UL-1a (one scripted search and red proofs: a `defcheck` macro expanding to `deftest`
    is a test and one expanding to `defn` is a function, at the REPL and from a file), UL-1b (REPL side from SCI's
    observation), UL-1c (file side from clj-kondo), UL-1d (schemas from the registry; it takes RP-17's declaration
    deletion, which therefore no longer waits on the redesign approval). Checked at the pinned clj-kondo 57252e07:
    `:defined-by` exists (`analysis/README.md:106`, `impl/analysis.clj:94`), but it is **always the original macro**.
    A project macro that expands to `deftest` has `:defined-by` = that macro, so the file side needs a `:lint-as` or
    hook entry (`:defined-by->lint-as`) or the loaded Var's metadata after `require`. The coordinator's proof "from a
    file" fails without one of those; UL-1c says so. Head-guessing sites found by the search: `reader.cljc:291, 302,
    306-315, 359, 366, 400, 409`, `signature.cljc:33-80`, `turn.clj:4714`, `repl.clj:151`, `sci/eval.clj:669`,
    `fn/analyzer.clj:597`.
13. **UL-2, Malli's composite registry**: `seon.schema/reference-registry` (`schema.clj:768-790`) is
    `(mr/composite-registry defaults references fallback)` (`malli/registry.cljc:54`, pinned 43c6dca8). One
    difference, stated in the row: Malli's `-schemas` merges in lookup order, ours merged references over the fallback.
    The structural registry (`schema.clj:1309-1321`) has an opaque fallback that answers any keyword or Var, which no
    Malli registry does, so a one-method registry stays inside the composite.
14. **Atoms note:** a registration's change event is the recording transaction's tx-report (`d/listen`); no registry
    atom or watch.

No arbitrary targets (owner, `4707b9546`):

15. Every budget code and row-level MB/ms target is converted to "expected from the work; measured parent and change;
    10× over is a defect; any regression reported". The owner's own numbers stay: 20-40 MB per model pass and never GB,
    the 16 GB machine, the 2,000-token raw-return bound, CLAUDE.md's 1 s and 10 s. Removed as invented: PB-02's 25 ms,
    PB-02b's 1 ms/2 ms, PB-03's 50 MB/100 ms, the 60/80/30 MB `defn` numbers (§3.2 intro, PB-05, §5), every MS row's
    token cap, P10's 20 tokens, PR-04/PR-07 token caps, S1-06/S1-07a/H-01 caps, EN-6's 150 ms/20 MB, EN-7's 15 ms,
    MEM-02's 100 threads, the 20 MB per idle agent (MEM-03, SR-H, AT-3), RO-2/RO-4's sub-caps, LR-STALE3's 5 ms, AT-1's
    1 ms. MEM-01's 4 GB heap and 6 GB RSS are marked as root's proposal, not the owner's. `agent-loop.md` §2 converted
    the same way.
    **For root:** CLAUDE.md's hot-path rule ("fix regressions over 20% or 50 ms before commit") is itself a fixed
    number. The ledger now cites it as CLAUDE.md's rather than as a budget, but the new ruling may call for revising it.

Out of scope, recorded: `seon.fn.analyzer/analyze` (11 calls, mean 1.17 s, max 10.0 s) and `seon.fn/analyzed-files`
(mean 2.17 s, max 14.7 s) are over the 1 s bound on default with no task on their health rows. Task
`seon.fn.analyzer/analysis-runs-over-the-one-second-bound-without-a-task` added to
`resources/seon/tasks/seon.fn.analyzer.edn`.

## Findings for root, ranked

### 1. The 900 MB model pass is parked behind four steps (contradicts the "never parked" ruling)

Measured at HEAD (`model-pass/pb-02-notes.md`): ordinary model pass 531-640 ms and 667-875 MB; after a `defn`
635 ms and 904 MB; a `defn` pass 734 ms and 925 MB. The owner wrote that a 945 MB model pass is never acceptable and
that a known budget breaker on the main path is never parked behind a future slice. The largest warm owner is context
projection, 350-405 MB per wake (`model-pass/profile.md` rank 2: `declared-sources`, `render.walk/neighborhood`,
`prompt/prompt`). The rows that delete it are S4-01 … S4-07b3, and they need no owner approval. §5 puts them at step 7,
after merge groundwork, tasks finishing and the rename sweeps.

Proposed: make the S4 chain step 3's second track, right after F4 releases `turn.clj` (or before F4 if root prefers
memory first), and ahead of steps 4-6. The writer floor (rank 3, 97-125 MB over seven commits) then needs RP-14 (one
commit per model pass), which is blocked on owner question 1. So that question is on the path to the smoke run, and §5
should say so plainly.

### 2. One file serializes three "parallel" steps

`src/seon/sci/eval.clj` (4,436 lines) is in the Files of 25 unlanded rows. Step 1 (PR-07, PR-11, S1-06, S1-07a,
S1-08), step 2 (S7-01) and step 3 (PB-02b, PB-03, SR-H, SR-I, LR-STALE3, RED-2) all wait on it in series, although
§5 says steps 1-3 run in parallel. `src/seon/turn.clj` (6,125 lines) is in 34 rows, including F4 and the whole S4
chain.

Proposed:
- (a) An explicit group C order on the critical path: PB-02's proof, PB-02b, PR-07's `eval.clj` part, PR-11, SR-H,
  S1-06, S1-07a, S1-08, PB-03, SR-I, LR-STALE3, RED-2.
- (b) A shrink row (CLAUDE.md asks root for these on oversized files): move the lookups (`documentation-value`
  `:2107`, `directory-value` `:2024`, `agent-test-namespace` `:328`) into their own namespace, so the S1 rows and S7-01
  stop contending with the pass-cost rows.

### 3. F4 waits on S7-01 for no reason

S7-01 adds an SCI `clojure.test/run-test-var` adapter that records nothing. `split/reconciled-plan.md` Q15: "A proof is
recorded only by `my.test/check`". F4 runs through `seon.test/run` in the agent's proc and never calls the adapter.
The edge puts F4, the core of "tests run by themselves", behind the busiest file. Proposed: drop the F4 → S7-01
dependency.

### 4. The smoke run is gated by an owner question that root has deferred

C8 → C0 → RP-10, RP-14, RP-15a, all `blocked-on-owner` (question 1), which root will ask after the transaction census.
That is right by the spec (§2: no competency run until a model pass is within the budget). The path should show it.
Two ways to shorten it:
- **Merge chain.** MA-11 (`merge!`) depends on MA-10 and MA-9, and MA-9 waits on the three norms rows. A T1 smoke run
  needs neither norms nor moved-target handling. Proposed: MA-11 depends on MA-3, MA-6 and MA-7; MA-8a-c and MA-9 land
  before X1-X4, not before the smoke run. That removes four serial rows from the path.
- **Step 6.** The renames, atoms and vectors (CN, AT, VEC) are numbered before steps 7-12 but none is needed for the
  smoke run. Proposed: list step 6 under "off the path" so lanes never hold path files for it.

### 5. AT-4 rebuilds what the census says to delete

AT-4 turns the `CountedSlidingBuffer` atom into proc state. The census (item 5) shows nothing reads its `:dropped`
count (`rg ':dropped'` finds nothing outside Flow's `ping` datafy), and `(async/sliding-buffer 1)` replaces the
deftype (`cluster/agent.clj:107-134`). Under "stop reinventing", delete it rather than move it. Proposed: AT-4 drops
the buffer from its list, and a UL row deletes it.

### 6. The census landed; six ready deletions have no rows, and nine owner decisions are not asked

`reinvention-opus.md` (`9246a9ac0`) lists six REPLACE items, about 460 lines deleted. Item 1 is now UL-1. Items 2-6 have
no rows:
- instrument restore state, `instrument.clj:1177-1294`, no production caller;
- the EDN symbol predicate, `program.cljc:72-115`;
- problem prose against `malli.error/error-message`, which MS-08 should take so it does not grow more prose;
- the wake buffer (finding 5);
- `form-pairs` against `ring.util.codec/form-decode-map`.

Items 9-17 are OWNER DECISION cases (topological sorts, call preparation, arming, layout prediction, structural hash,
spelling distance, cache counters, hiccup, Flow reach-ins). The ruling says these go to the owner case by case, but
§6 does not list them. CN-16, CN-17, VEC-4 and CN-1 already wait on four of them. Also:
- The reply built by string splicing (`repl.clj:275-296`, read back by `read-shown` `:81-98`) is a second printer
  ruled out already. No PR row names those lines; PR-17 names only `repl.clj:101-107`.
- AT-8 deletes Seon's per-kind cache counters, but the census (item 15) shows Caffeine's `StatsCounter` gets no key, so
  one shared weighted cache cannot give per-kind counts. AT-8 loses per-kind hits and misses. That is an owner choice
  (census options a-c), not a mechanical swap.

Proposed: root writes UL rows for items 2-6, adds items 9-17 and the AT-8 gap to §6 in plain words, and gives the
reply-splicing lines to PR-17.

### 7. Norms copy Datahike's record instead of reusing it

MA-8c records "each norm's symbol and definition digest" in a Seon shape. Datahike's norms record `:tx/norm` on the
transaction and skip a recorded norm (`norm/norm.clj:31, 142-158` at e5453d96). The owner approved one transaction
for program plus converted data, which `ensure-norms!` cannot do. That justifies running the norm ourselves. It does
not justify a second record. Proposed: MA-8c records `:tx/norm` as Datahike does, so Datahike's own never-twice check
reads the same fact.

### 8. Slices too big for about 15 minutes, or not specified

Too big:
- **F4** (critical path). It covers: the serial step, a new bound dial, continuation across passes, superseding older
  queued tests, derivation at proc start, the reply summary, and model and MCP alike. Four proofs. Proposed split:
  - F4a: proofs run after a changing MCP pass;
  - F4b: the reaching request;
  - F4c: the bound and continuation;
  - F4d: derivation at proc start.

  It should also state where the "queued tests" live: the Flow proc state, never an atom.
- **CS-01c.** It covers Seon names, library names with pinned revisions, `reference-code/` paths, problems and unread
  items, with five proofs. Proposed split: Seon names; library names; paths; unread.
- **The rest:**
  - MA-11 and MA-12;
  - RED-1 (13 reds);
  - NK-7 (543 task entries plus ten files) and NK-8b (83 source references in 14 files);
  - PR-17, PR-18, PR-24, PR-26 and PR-27 (6-9 files each);
  - PR-20a-d (5-6 namespaces each);
  - AT-1 (boot-critical).

Not specified:
- PR-A/PR-B ("per checkpoint", no proof or budget).
- E-P3 and E-SREADS (families not listed).
- PB-04 and MEM-04 (files "named by the probe"). This is acceptable for diagnosis rows, but they should say they are
  diagnosis.
- C7/C8 ("see §5").

By the ledger's own definition, `ready` means files, contract, proof and budget. The MA, E and C tables have no Files
column, and the MA, E, C, NK, GV, CN, VEC and RED tables have no Budget column, yet their rows say `ready`. Proposed:
add the columns, or mark those rows `ready after split`.

### 9. Minor

- §2's "LR-CACHEb, fork part" landed row makes the script see LR-CACHEb as landed. Harmless, but keep the Seon part's id
  distinct (for example LR-CACHEb2) if tooling reads the table.
- LR-CACHEb's uncommitted `db.clj` uses `[:maybe :map]` in two new contracts. The project rule forbids `[:maybe]`.
  This is for the lane's own review, not changed here.
- RED-1's "13 red at HEAD" was not re-run (no test run in a review lane). Unverified.

## Checked and consistent

- **Automatic tests:** F4, CS-01d, F9, H-01 and MS-11b match "tests run automatically, all relevant ones".
- **No kinds:** derived statuses (`:seon.task/status`, proof `:green/:red/:pending`) are derived, never stored.
  `:seon.batch/mode` is reader grammar per the census.
- **Git's words:** the rows and specs use main, merge request, source and target branch, ours and theirs, unmerged and
  norm. Code names wait on their renames, as the ledger says.
- **Vectors:** `:seon.merge/norms` is cells. S4-01's agent ordinal and EN-1's form ordinal are occurrence numbers,
  which `vectors.md` §1 keeps.
- **Atoms:** SR-H and AT-1 to AT-3 follow the ruling.
- **No clipping outside the printer:** CS-02 uses the printer's budget key, S4-04 refuses rather than clips, and MS-12
  leaves cuts to the printer.

## Live observations (MCP, agent `plan-review-opus3`, default)

- Health is `:faulted`: 6 open faults, 8 over-bound warnings and 56 errored receipts.
- Max heap is 10,488 MB (MEM-01 not landed). Heap live is 942 MB, with 180 platform threads (MEM-02).
- The cache holds 346 of 512 MB.
- Loaded commit `6ac7343e`.
- The over-bound rows without a task are recorded above.
