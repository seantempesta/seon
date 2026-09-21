---
type: research
status: draft
created: 2026-09-21
tags: [deletion, test-system, seon.test.runner, bin/test, fixtures, one-jvm, algorithmic-analysis]
---

# Deletion audit: the test system and the test corpus

Read-only audit. No JVM, no `bin/test`, no `bin/seon`, no MCP evaluation was
run. The only write is this note. Every claim below cites `file:line` read in
this session; anything I could not read is named as unverified in §7.

**The question (owner, 2026-09-21):** *"identify all the code we can delete —
be it test code or code that's duplicating behavior"*, against the standing
observation that the system *"basically [has] a global accessible system in
the form of databases and caches AND can maintain the appearance of separate
systems all running in the same JVM due to immutability and forking."*

**The answer in one paragraph.** The test system is 12,388 lines of source and
shell, of which roughly **4,600 exist only to coordinate separate operating-system
processes** — worker JVMs, worker checkouts, git snapshots, slots, run roots,
an EDN wire protocol, a distributed member-claiming protocol, a liveness
watchdog that dumps every child JVM, and a re-run-in-isolation confirmation
stage whose only job is to decide whether a red was a parallelism artifact.
Every one of those mechanisms protects against a hazard that a one-JVM design
does not have: the owner's own forking premise is already implemented and
measured in the fixture (`test/seon/test_support.clj:946`, Datahike `branch!`
plus `sci/fork`, **37 ms p50**), so the isolation the worker JVMs buy at
seconds each is already available at milliseconds. The PRD that governs this
area already ruled the same direction — *"The primary host for tests is the
cluster's own JVM, in process"*, worker JVMs *"are the exception"*
(`docs/prds/steward-platform/plan/test-system-is-the-database-prd-2026-09-17.md:44`).
The deletion is the ruling being executed, not a new design.

---

## 0. Verification boundary

- Branch `steward-platform`, HEAD `b2f34ebaf`, read 2026-09-21.
- The working tree carries another lane's uncommitted edits in
  `src/seon/cluster.clj`, `src/seon/fn.clj` and two publication tests. Line
  numbers below are **working-tree** lines. None of the files I cite in the
  test system are among the dirty ones.
- I read source for every span I recommend deleting. Spans I sampled rather
  than read line-by-line are marked *(sampled)* in §1 and counted
  conservatively.
- `docs/prds/context-generation/plan/unsettled.md` was not read, per the
  assignment.

---

## 1. The runner: 4,967 lines to run `clojure.test`

`src/seon/test/runner.clj` has 184 definitions. Grouped by mechanism, with the
one question that decides each: **does this hazard exist when the tests run in
the cluster's own JVM, on a forked Datahike branch and a forked SCI context?**

| # | Mechanism | Span (lines) | What it protects against | Exists in one JVM? | Verdict |
|---|---|---|---|---|---|
| 1 | clojure.test report capture, failure identity, signatures | `:111-435` (325) | nothing — this is the actual reporting | yes | **keep** |
| 2 | Duration bound enforcement (`duration-failures`) | `:360-378` (19) | a test exceeding its declared bound | yes — this IS the owner law | **keep** |
| 3 | Liveness watchdog, virtual-thread dumps, `stop-descendants!`, `halt 124` | `:435-640` (205) | a **child JVM** wedging with no output; kills the process tree | no — a wedge in one JVM is a thread, and `bounded-result` (`src/seon/test.clj:143`) already bounds the Var | **delete** |
| 4 | `run-vars!` / `run-var!` / `test-vars-in` | `:655-750` (95) | nothing | yes | **keep** |
| 5 | Marker reading (`marker-reason`, `long-declarations`) | `:752-805` (53) | undeclared/blank long reasons | yes | **keep** |
| 6 | `verify-platform-declarations-indexed!`, `verify-long-declarations-indexed!` | `:874-960` (86) | Var metadata disagreeing with the **indexed manifest a worker was handed** | no — one JVM reads one program value; the drift has no second copy to drift from | **delete** |
| 7 | `test-tasks`, `atomic-namespace-task?`, `split-resolved-tasks` | `:1019-1077` (58) | packing namespaces into balanced worker groups | no — no pool to pack | **delete** |
| 8 | Destructive-drill tier guard (`destructive-owner-rows`, `verify-platform-tier-carries-no-destructive-drill!`) | `:1086-1180` (94) | a test reaching `:seon.fn/destroys` deleting the development root | **yes, and more so** — in-process is exactly when this matters | **keep** |
| 9 | `expensive-fixture-tests`, `verify-fixture-observations!` | `:1179-1244` (65) | a fixture not declaring its cost to the **scheduler** | no — nothing schedules | **delete** |
| 10 | Tier construction, `run-request!`, `run-tiers!` | `:1244-1354` (110) | nothing | yes | **keep** |
| 11 | Ambient drift detector (`ambient-snapshot`, `bounded-drift`, `ambient-drift`, journal limit) | `:1393-1447`, `:1545-1660` (169) | a **pooled worker JVM** being left unarmed by a previous test | no — but see note | **delete** |
| 12 | `restore-live-cluster-schema!`, `schema-restore-drift` | `:1447-1545` (98) | a test leaving the live cluster's projection changed | **yes** — this is the one-JVM hazard, already used by `src/seon/test.clj:409` | **keep** |
| 13 | `run-task!` (a worker's single task) | `:1657-1721` (64) | — | no | **delete** |
| 14 | `load-declared-predicate-owners!`, `packaged-test-projection`, `program-source-files`, `declared-program-namespaces` | `:1721-1849` (128) | — | **verbatim duplicate of `src/seon/test/arm.clj:5-137`** (see §1b) | **delete** |
| 15 | Worker EDN wire protocol, `serve-worker-commands!`, `worker-command-loop!`, `worker-main!`, `run!` | `:1907-2135` (228) | a worker process needing to be told what to run over stdin | no | **delete** |
| 16 | Reach facts / digests / memberships | `:2135-2440` (305) | nothing — this is "which tests reach my change" | yes | **keep** |
| 17 | `claim-member`, `execution-members`, `worker-identity`, execution refusals | `:2454-2714` (260) | **two processes racing to claim the same test**; dead-generation reclamation; mid-transaction population checks | no — one JVM's work list is a sequence | **delete** |
| 18 | `complete-members`, `record-tx`, `record-latest-tx`, `commit-results!` | `:2714-3183` (469) | nothing — this is the recording authority | yes | **keep** |
| 19 | Staged/persistent results via EDN files (`stage-completion!`, `commit-staged-completion!`, `persistent-results-form`, `record-persistent-results!`) | `:3263-3443` (180) | a worker handing results to a coordinator **through the filesystem** | no — a return value | **delete** |
| 20 | Result queries (`run-results`, `latest-results`, `recorded-run!`, `reusable-result`) | `:3032-3145`, `:3443-3560` (230) | nothing — this is reuse of unchanged evidence | yes | **keep** |
| 21 | Worker count, checkout, exchange, start/stop, process-tree ownership, task pool | `:3628-4310` (682) | everything about owning child processes | no | **delete** |
| 22 | Parallel-failure confirmation (re-run each red alone in a fresh worker to classify it `:parallel-only` / `:reproducible` / `:worker-exchange`) | `:4308-4500` (192) | reds that are artifacts of **concurrent worker scheduling** | no — nothing is concurrent | **delete** |
| 23 | Tally printing | `:4494-4630` (136) | nothing | yes | **keep** |
| 24 | `confirmation-vars`, `run-parallel-stage!` | `:4666-4710` (44) | — | no | **delete** |
| 25 | `run-coordinator!`, `coordinator-main!`, `-main` | `:4718-4967` (249) | coordinating the pool; ~50 lines is a real entry point | mostly no | **shrink (−200)** |

**Runner deletion: 2,561 of 4,967 lines (52%).** Survivors: report capture and
the duration bound, tier construction, reach facts, the recording authority
and its result queries, the destructive guard, and the live-schema restore.

### 1a. The confirmation stage is the clearest single example

`confirm-parallel-failure!` (`:4377`) launches **another worker JVM per red**,
loads the whole pool worker's namespace set into it, and classifies the result.
Its own comments name the two incidents that grew it: a re-arm that killed the
worker under its own contract (`:4361-4376`), and thirteen `seon.sci.eval-test`
verdicts green alone and red under the gate (`:4400-4407`). Both are *properties
of a pool of reused subprocess JVMs*. Neither exists when each test holds its
own forked branch and ctx in one JVM. 192 lines, plus the
`worker-checkout "confirmation"` tree (`:4308-4327`), delete whole.

### 1b. A verbatim duplicated mechanism (§2.5 violation)

`diff` of `src/seon/test/runner.clj:1721-1849` against `src/seon/test/arm.clj:5-137`
returns only keyword-aliasing and one import difference — same four functions,
same bodies. Both are live: the runner's copies feed `worker-command-loop!`
(`runner.clj:2065`) and `coordinator-main!` (`:4939`); `arm.clj`'s feed
`initialize-contracts!` (`arm.clj:239`), which is what `bin/test-fast` uses
(`src/seon/test/fast.clj:71`). One program-namespace derivation, two copies,
because the worker and the fast path were built at different times. `arm.clj`
survives; the runner's copy goes with the worker.

### 1c. Duplicate run paths: six, one survives

| Path | Entry | Selection | Admission | Execution | Recording |
|---|---|---|---|---|---|
| `bin/test` (cold gate) | shell, 1,107 lines | `seon.test/select` | `admit-run` | worker pool | `record-snapshot!` → files → `record-tx` |
| `bin/test --fast --paths` | same shell, snapshot branch | same | same | same pool | same |
| `bin/test-fast` | `bin/test-fast:41` → `seon.test.fast/-main` | `snapshot-request` (`fast.clj:20`) — **its own** | `record-snapshot!` | `run-request!` in one JVM | `record-snapshot!` |
| `seon.test/run` | `src/seon/test.clj:541` | `host-admission!` | shared | `execute-admitted!` | `commit-results!` |
| `seon.test/run-owned` | `:576` | `host-admission!` | shared | `execute-admitted!` | `commit-results!` |
| `seon.test/check` / `check-in-process` | `:1627`, `:1901` | `check-admission` (`:1595`) — **its own** | `admit-run` | in-process | `commit-results!` |

`run` and `run-owned` already differ only in who supplies custody and whether
the test Var is resolved from facts first (`:576-601`) — they are one function
with an argument. `check-in-process` is a third selector. `seon.test.fast/-main`
is a fourth, and it is a 123-line transcription of what `run-request!` already
does, wrapped in its own provenance construction (`fast.clj:20-51`).

**The one that survives: `seon.test/check` over `seon.test/run-owned`.** These
two are not rivals — they are the same path at two granularities, and both
already exist and already work in one JVM. `run-owned` (`:576`) admits and runs
**one** test under the custody the request names. `check` (`:1901`) selects the
tests observing a change and runs them **in the calling JVM** — its docstring
says so: *"Run tests observing this change in the calling JVM and record their
facts."* It already excludes destructive tests by program-graph reach and
reports them with their cold command, and it already shares
`check-request-admission` → `admit-run` → `commit-results!` with `run-owned`.

**This is the strongest single piece of evidence in this audit.** The
capability the owner describes — *the system records which functions changed
and reruns only tests reaching them* — is implemented, in-process, today, in
`seon.test/check`. `bin/test`'s 1,107 lines of shell, the worker pool, the
checkouts, the slots and the confirmation stage are a **second implementation
of the same selection**, differing only in that it copies the tree into
subprocesses first. `run` (`:541`) collapses into `run-owned`'s no-agent
arity; `check-in-process`'s selector (`:1595`) merges into the shared one;
`seon.test.fast` is deleted whole; `bin/test` becomes a thin invocation of
`check` plus the one platform-tier subprocess.

### 1d. Evidence that the two run kinds have already forked semantically

`seon.test/changed-since-green` (`src/seon/test.clj:61`) — the function that
answers *"which definitions changed since this test was last green"*, the
capability the owner actually wants — excludes every fast-path run by clause:
`(not [?run :seon.test.run/published-base-digest])` (`:81`). Runs recorded by
`bin/test-fast` cannot answer the change question at all. That is the cost of
the second path, stated in a query.

### 1e. Shell and slots

| File | Lines | Verdict |
|---|---|---|
| `bin/test` | 1,107 | **shrink to ~100.** Its phases are: git-index HEAD snapshot into a run root (`:768-813`), dependency cache and classpath (`:894-933`), worker checkouts (`:987`), published base, phase watchdogs (`:546-676`), run-root retention and runner reaping (`:417-545`). Every phase exists to hand a **different process** a **different copy of the tree**. One JVM needs: acquire cluster, call the run path, exit with the tally. |
| `bin/test-fast` | 48 | **delete** — `:14` already `exec`s `bin/test --fast` for the `--paths` case; the rest is a second launcher. |
| `bin/_test-slot` | 165 | **delete** — it bounds *concurrent test JVMs per checkout* (`bin/_test-slot:14`). One JVM, no slots. The rule it enforces (AGENTS §11, "I'm tired of my machine dragging") is satisfied by construction, not by a lock directory. |

---

## 2. Time escapes: the `:seon.test/long` declarations

**The bound is genuinely enforced.** `duration-failures`
(`src/seon/test/runner.clj:360-378`) fails the test when elapsed exceeds
`(max ordinary declared-allowance)` and records it as ordinary assertion
evidence. So every declaration below is a *deliberate, granted* escape from the
owner's ten-second law, and each one is a claim that the work is genuinely
necessary.

### 2a. The aggregate, before the per-test table

`grep -rho 'seon.test/long-ms[ ]*[0-9]*' test` sums to **68,105,085 ms =
18.9 hours** of explicitly granted time budget across the corpus.

| File | Granted ms | Declarations | Share |
|---|---:|---:|---:|
| `test/seon/test_runner_integration_test.clj` | **45,000,000** | 25 @ 1,800,000 | **66.1 %** |
| `test/seon/shell/jvm_test.clj` | 4,800,000 | 8 @ 600,000 | 7.0 % |
| `test/seon/cluster/boot_test.clj` | 2,400,000 | 4 @ 600,000 | 3.5 % |
| `test/seon/test/runner_test.clj` | 2,700,001 | 4 | 4.0 % |
| `test/seon/dev/fresh_operator_reset_test.clj` | 1,800,000 | 3 @ 600,000 | 2.6 % |
| everything else (≈100 declarations) | 11,405,084 | ~100 | 16.7 % |

**Two thirds of the entire granted time budget sits in one 1,513-line file
whose own docstring says what it is:** *"Explicit orchestrator subprocess
coverage; excluded from ordinary runner and platform selection"*
(`test/seon/test_runner_integration_test.clj:2`). Every one of its 28 deftests
carries the identical reason string *"Orchestrator integration: owns child
processes"* and the identical allowance of **1,800,000 ms — thirty minutes
each**. Their names are the inventory of §1's deletion list:
`worker-exit-backstop-names-and-fails-a-stuck-child` (`:659`),
`worker-root-cleanup-awaits-recorded-child-completion` (`:720`),
`liveness-dump-includes-coordinator-and-worker-virtual-threads` (`:314`),
`a-fresh-run-root-is-claimed-before-population-and-sweep` (`:999`),
`codex-lanes-refuse-cold-gates-before-acquiring-resources` (`:1380`).

This file is class **B** in its entirety: it is the regression suite for the
process machinery, and it is deleted by the same cut that deletes the
machinery. **12.5 hours of the 18.9-hour budget disappears with it**, and the
deletion costs no coverage of anything the system does for an agent.


### 2b. Classification of all 128 declarations, by reading each body

128 declarations exist (122 on deftests, 6 namespace-level: `cluster/armed_test.clj:1`,
`cluster/program_restart_test.clj:1`, `concurrency_independence_test.clj:1`,
`concurrency_streams_test.clj:1`, `sci/eval_instrumentation_test.clj:1`,
`dev/fresh_operator_reset_test.clj:1`). 90 carry `:seon.test/long-ms`; 38 declare
a reason only. Each body was read.

| Class | Declarations | Declared ms | Share of ms |
|---|---:|---:|---:|
| **A — algorithm defect** (dominated by work proportional to the whole program: canonical fixture acquisition, complete publication / `refresh-source!`, full clj-kondo analysis, whole-store copy, full schema population) | **72** | 14,440,000 | 22 % |
| **B — process machinery** (child JVMs, `bin/seon`, `bin/test` launcher, injected workers, checkouts, run roots, edit hook, cold-gate shell) | **18** | 5,220,000 | 8 % |
| **C — genuinely long** (real cross-process OS proofs, deliberate property runs, measured deadlines) | **7** | 85,000 | 0.1 % |
| **D — verbatim duplicate of a sibling's declaration** | **31** | 45,660,000 | **70 %** |
| total | 128 | 65,405,000 ≈ 18.2 h | |

**Folding D back into what each duplicates: B-process (orchestrator / worker /
operator) becomes 50,880,000 ms = 78 % of the entire granted budget; A-algo
18,040,000 ms; C 145,000 ms.** Of the 31 D rows, 23 are the identical
*"Orchestrator integration: owns child processes"* string at 1,800,000 ms each
in `test_runner_integration_test.clj` (`:109` through `:1462`), and 6 are the
identical *"Publish the canonical program into a physical store"* string at
600,000 ms each in `shell/jvm_test.clj` (`:214`, `:237`, `:271`, `:304`,
`:321`, `:387`). A verbatim-duplicated allowance is the per-call-site
replication §3d describes, in the metadata rather than the body.

**Every one of the ten largest is class B or D** — all at the 1,800,000 ms tier
in `test_runner_integration_test.clj`. The next tiers are class A:
`cluster/publication_host_test.clj:18` (1,200,000),
`test/selection_test.clj:296` and `test/publication_test.clj:12` (900,000).

**The class-A 72 are the owner's algorithmic-analysis point stated by the tests
themselves.** Representative, O(program) step named: 15 cold boots and
publications in `cluster/boot_test.clj` (`:233`, `:466`, `:873`, `:958`,
`:1142`, `:1799`, …); *"two real indexed publications"*
(`fn/publication_test.clj:39`, `:112`); *"two complete SCI acquisitions"*
(`sci/lazy_acquisition_test.clj:9`); *"cold clj-kondo analysis"*
(`fn/publication_cache_test.clj:9`); *"copy + hash whole published store
twice"* (`test_support_test.clj:195`). The seam that makes each O(edit)
already exists and is named in §4: the base is built **once per JVM**
(`test_support.clj:582`) and each test takes a **37 ms** branch+ctx fork
(`:946`). The residual O(program) step inside that base is
`schema/projection-from-database` at **3,996 ms**
(`test-system-fork-2026-09-23.md:104`).

### 2c. A latent absence-as-health defect in the marker itself

`duration-failures` raises the bound **only when the reason is a non-blank
string AND `long-ms` is a positive integer** (`src/seon/test/runner.clj:369-371`).
The **38 reason-only declarations therefore get no raised bound at all** — their
body limit stays the 5,000 ms default (`resources/seon/schemas/seon.test.edn:27-29`)
and their exchange allowance is 0 (`runner.clj:421-425`, `:1043-1046`). Yet each
one's own reason string states a measured time of 47–200 s: every test in
`dev/fresh_operator_test.clj`, fifteen in `cluster/boot_test.clj` (`:497`,
`:531`, `:565`, `:590`, `:620`, `:702`, `:779`, `:823`, `:1089`, `:1226`,
`:1351`, `:1386`, `:1463`, `:1541`, `:1663`), both `print_test.clj` property
runs, and the five ns-level declarations without ms. They pass today only
because a declared-long test is **excluded from ordinary selection**
(`src/seon/test.clj:1047-1049`). The moment anything runs them with
`:seon.test/include-long? true`, all 38 fail on a bound 10–40× below their own
stated cost. **A marker half-declared reads as healthy because nothing selects
it** — the project's named failure class, inside the bound mechanism itself.
This is a defect to file whether or not the deletion happens.

---

## 3. Corpus duplication — the hypothesis I was given is mostly wrong

I looked for three things in the 98,985-line corpus: tests of mechanisms ruled
deleted, hand-rostered fixtures, and classes covered several times. **Two of
the three came back nearly empty, and saying so is the finding.**

### 3a. Legacy mechanisms: ~4 deletable lines, not thousands

Every "legacy spelling" in AGENTS.md §3's vocabulary table that I grepped is
**still live in `src/`**. The table's third column governs *prose*; it does not
describe deleted code.

Still live in `src/`, therefore **keep**: `:seon.ns/steward`
(`resources/seon/schemas/seon.ns.edn:28`, `src/seon/cluster/agent.clj:144`);
`receipt` (`src/seon/db.clj:157-158,3342` — `*receipt*`, `stamp-receipt`);
`:seon.cluster.eval/*` (835 hits in `src/`); `episode` (`src/seon/run.clj:38`,
`src/seon/turn.clj:2714-2735`); the `seon.issue` entity family (14 `src/` files
— and **`seon.task` has zero hits in `src/` or `resources/`**, so the ruled
rename never happened). Zero hits anywhere: `seon.fn.ast`, CLJS/pod/self-host/relay.
All 51 `:kind`/`:type` hits are namespaced (`:seon.error/kind`,
`:seon.schema.shape/type`) — no bare discriminators exist.

The **only** dead mechanism found: the `seon.cluster.run.form` family has zero
hits in `src/` or `resources/`, and `test/seon/turn_test.clj:1069-1072` asserts
its *absence* — a check about a family that cannot come back, which is exactly
the absence-as-health class the project bans. **Delete those 4 lines.**

**Deletable here: 4 lines.** The corpus is not carrying dead mechanisms.

### 3b. Hand-rostered fixtures: ~270 lines, and they are mirrors, not schema

Hand-written schema is already near-eliminated: 22 files mention `:db/valueType`
at all, and the six largest (`render/root_pull_test.clj:13-34`,
`flow_test.clj:142-166`, `cluster/wake_test.clj:133`, `cluster/boot_test.clj:213`)
declare auto-namespaced **synthetic** attributes through
`::test-support/extra-schema` — exactly what AGENTS §5 rule 1 permits.
Hand-written `:seon.fn/*` maps are down to 2 files against 36 using
`program-fn-row`. What remains are hand-maintained **mirrors of derivable
state** (the derive-or-die law):

| file:line | Mirror | Replacement | Lines |
|---|---|---|---:|
| `test/seon/await_owner_census_test.clj:10-15` | a literal `#{}` of 5 caller symbols pinned by `=` against a `:seon.fn/calls` query that is *already in the same file* (`:17-27`) | derive the expectation, or delete the file | 46 |
| `test/seon/fn_core_calls_test.clj:6-10` | `printer-symbols` roster pinning one core call edge | same class | 29 |
| `test/seon/cluster/turn_test.clj:2315-2334` **and** `test/seon/ai_test.clj:1333-1368` | a **byte-identical 12-row provider error-class table in two files**, mirroring the declared `[:enum …]` at `resources/seon/schemas/seon.ai.edn:71-82` | generate both from the enum | ~56 |
| `test/seon/flow_test.clj:142-166`, `:204-244` | `fault-schema` mirrors `:seon.config/on-core-error`, and `commit-fault!` (`:224-231`) is a **fake committer** writing `::fault-id`/`::fault-proc` instead of `seon.error` facts | `apply-config!` and the real fault committer — a mirror its authority re-decides | ~90 |
| three helpers reimplemented locally beside the canonical one | `instrument_test.clj:53-71` (`preserving-instrumentation-state` by hand via `alter-var-root`); `turn_work_test.clj:50-62` (`model-attempt`, whose docstring admits "exactly as `record-attempt!` does"); 3× `delete-recursively!` in `registry_test.clj:68`, `dev/fresh_operator_test.clj:41`, `dev/fresh_operator_reset_test.clj:18` | `test_support.clj:1073`, `record-attempt!`, `test_support.clj:819` | ~49 |

**Deletable here: ~270 lines.** Note the shape: these are §2.2 "derive or die"
defects and §2.1 mirrors, worth fixing on their own terms, but they are not
bulk.

### 3c. Class duplication: one clear class, ~309 lines

| Class | Instances read | Keep | Delete |
|---|---|---|---:|
| *a read refusal propagates verbatim and is never read as absence* | 10 deftests: `turn_test.clj:939`, `:959`, `:984`; `cluster_test.clj:16`, `:40`, `:397`; `config_test.clj:632`, `:685`; `db_test.clj:2152`; `plan_test.clj:429` | `cluster_test.clj:397` — the only one driving a **real** refusal through the production writer with a basis-`t` check | **~215** (9 × ~24). Seven copy an identical 7-line `:seon.audit/poison` setup verbatim; three fake the read with `with-redefs [db/q …]` — a mocked harness, already banned |
| *`ns-unmap` is committed program data whose SCI mutation follows the terminal commit* | `cluster/turn_test.clj:541`, `:598`, `:625`, `:646`, `:692` | `:541` (accepted) + `:692` (refusal — structurally distinct) | **~94** (`:598-691`) |
| the publication family | 24 files, 1,776 lines, ~38 deftests, each with a distinct `:seon.test/fixture-observation` | — | **0** — the full deftest inventory was read; no two assert the same class. Merging the 11 `cluster/publication_*_test.clj` files would cut headers only (~150 lines) |

### 3d. What fraction of 99 K is actually deletable — the honest number

**Not 30–40 %. The bloat is not duplication and it is not legacy.** The corpus
is 2,136 top-level deftests over 98,985 lines = **46 lines per deftest**. The
mass is *body and setup size*, replicated per call site.

| Bucket | Lines | Basis |
|---|---:|---|
| Legacy mechanisms (§3a) | 4 | counted |
| Hand-rostered mirrors (§3b) | ~270 | counted |
| Class duplication (§3c) | ~309 | counted |
| Process-machinery tests that die with §1 (§6b) | ~3,200 | counted + deftest-list read |
| Refusal-class replicas beyond the one class read | ~950 | **extrapolated** — 150 deftests name `refuses`/`refusal`; 9 of 10 were redundant in the one class read exhaustively; applied at a conservative 15 % to the other 140 |
| **Total** | **≈4,700 ≈ 4.8 %** | |

A 14 % figure is reachable only by assuming the 15 %-per-class redundancy rate
generalizes corpus-wide, and nothing I read supports that: the publication
family and eight per-incident files came back clean. **I would not act on 14 %.**

**The real lever on the corpus is not deletion.** `cluster/turn_test.clj` is
3,729 lines for 59 deftests (63 each); `turn_test.clj` 2,284 for 34 (67 each).
The `:seon.audit/poison` probe, the 12-row error-class table and the
`fault-schema` mirror are each 20–90 lines of *setup* repeated per test.
Hoisting those into `seon.test-support` removes more lines than deleting whole
tests and costs no class coverage. That is an accretion into the existing
fixture owner, not a new mechanism.

---

## 4. Fixtures: the fork is already built, and already measured

`test/seon/test_support.clj` has two fixture paths behind one entry
(`with-database`, `:982`):

| Path | What it builds per test | Cost |
|---|---|---|
| `with-branched-database` (`:946`) — the default | Datahike `branch!` off a held base (`:967`), `d/connect` under the base's carried projection (`:970`), a projection **state** (`:973`), and `fork-cluster-ctx` (`:615`) forking the base's acquired SCI ctx | **37.0 ms p50** measured |
| `with-fresh-database` (`:921`) — `:fresh-store?` / `:database-id` | calls **`create-base nil`** (`:927`), i.e. rebuilds the entire base: clone the published store directory, `reidentify!`, connect, derive the projection from the database | **4,648 ms first**, and it is paid **per test that asks for it** |

**The forking premise the owner describes is implemented.** `with-branched-database`
is exactly "immutability and forking maintaining the appearance of separate
systems in one JVM": a Datahike branch is a head pointer
(`isolation-merge-writeback-2026-09-19.md:24`, *"branch forking is O(1) and the
registry already owns it"*), and `sci.eval/fork-cluster-ctx` is SCI's
copy-on-write fork. The base is built once per JVM per publication
(`database-base`, `:582`), with a hold protocol so an adoption mid-run retires
rather than closes it (`:433`).

**What the measurements say about where the remaining cost is.** From
`docs/prds/steward-platform/research/test-system-fork-2026-09-23.md:60-110`,
the first fixture acquisition came down in four steps, each removing work
proportional to the whole program:

| Change | First acquisition | Subsequent p50 | The O(program) step removed |
|---|---|---|---|
| baseline | **188,908 ms** | 5.0 ms | `seon.fn/index!` — re-indexing every source file into the fixture database |
| remove indexing | 45,190 ms | 183 ms | — |
| defer SCI, drop redundant reconnect | 11,869 ms | 96 ms | 33,223 ms of SCI acquisition per base |
| remove the tiered store | **4,648 ms** | **37.0 ms** | Konserve `sync-on-connect` copying every backend key (`store.cljc:91-104`) |

The residue is named and is still O(program): `schema/projection-from-database`
takes **3,996 ms** of the 4,648 (`test-system-fork-2026-09-23.md:104`) — it
queries every schema form, every function contract and every function source
and builds the Malli projection. That is the single largest remaining
algorithmic defect in the fixture path, and the note is explicit that it was
left alone as out of scope.

**The deletion this implies.** Nothing in `with-branched-database` should be
deleted — it is the target shape. What should be deleted is **everything that
multiplies the base**: `create-base` is paid once per JVM, so N worker JVMs pay
it N times, and `with-fresh-database` pays it again per requesting test. Two
actions, both net deletion:

1. Delete the worker pool (§1) and the base is built **once, in the cluster's
   JVM, at the publication it already adopted** — arguably zero times, because
   `seon.cluster` already holds a connection and a ctx on `:current-src`.
   `src/seon/test/cache.clj:276-476` (`child!`, `copy-checkout!`,
   `worker-checkout!`, `reap!`, `prepare-base!`, `ensure-base!` — 200 lines)
   goes with it.
2. `with-fresh-database` (`:921-945`) exists because blob keys live outside
   branch facts (`:990`). That is a store-scoped concern for a handful of
   tests; it should fork a second *store*, once, not rebuild a base per call.
   Only **6 test files** request it, across 8 call sites (`grep -rn
   'fresh-store?|database-id' test`), and each call pays the full 4,648 ms.

**`test_support.clj` itself is not bloat.** Of its 1,123 lines, the hold
protocol (`:406-577`), the retiring base (`:433`) and `fork-cluster-ctx`
(`:615`) are the forking design working. The deletable ~120 lines serve worker
processes: `clone-directory!`, `replace-directory!`, `populate-published-root!`,
`populate-published-operator-root!`, `with-published-file-database` (`:60-180`)
hand a **file-backed published root** to something that boots from files.

---

## 5. What is genuinely needed, and how each survives

| Capability | Where it lives after the deletion | Lines |
|---|---|---|
| An agent asks "which tests reach my change" | `seon.test/changed-since-green` (`src/seon/test.clj:61`) over `runner/reach-memberships` (`:2362`) and `seon.test.selection/reaching-tests` (`selection.clj:79`) — a Datalog read of stored `:seon.fn/calls` edges, already O(edit) | ~300, kept |
| Runs exactly those, in-process, on its own fork | `seon.test/run-owned` (`:576`) → `execute-admitted!` (`:409`) → `bounded-result` (`:143`), with `test-support/with-database`'s branch+ctx fork | ~250, kept |
| Gets recorded results | `runner/commit-results!` (`:3145`), `complete-members` (`:2714`), `record-tx` (`:3014`) | ~470, kept |
| Unchanged evidence is reused, not re-executed | `runner/reusable-result` (`:3046`), `seon.test/select`'s `green-members` (`test.clj:759`) | ~200, kept |
| Every test has a bound and fails over it | `duration-failures` (`:360`) plus the declared-`long` markers (`:752`) | ~75, kept |
| A platform tier proves boot from zero | the destructive-drill guard (`:1086-1180`) plus **one** isolated invocation for the `:seon.test/platform` set — the single surviving use of a subprocess | ~95 + a ~100-line `bin/test` |

Every one is a query or a function call on a value. None needs a second
process, a checkout, a slot, a wire protocol or a watchdog.

---

## 6. Totals

### 6a. Source and shell

| File | Lines | Deletable | Survives as |
|---|---:|---:|---|
| `src/seon/test/runner.clj` | 4,967 | **2,561** | report capture + bound, tiers, reach facts, recording, result queries, destructive guard, schema restore |
| `src/seon/test.clj` | 2,182 | **~500** *(sampled)* | `run-owned` as the one path; `check-in-process` (`:1627-1851`) and `check` (`:1901-1975`) collapse into it; `host`/`destroyers` and `changed-since-green` kept whole |
| `src/seon/test/cache.clj` | 546 | **200** | input digests and `input-roots` kept; `child!`, `copy-checkout!`, `worker-checkout!`, `reap!`, `prepare-base!`, `ensure-base!` (`:276-476`) go with the pool |
| `src/seon/test/fast.clj` | 123 | **123** | nothing — it is the fourth run path |
| `src/seon/test/bounds.clj` | 44 | **~30** | one default bound survives; exchange/reporter-grace/fixture-priming are pool constants |
| `src/seon/test/arm.clj` | 254 | 0 | the surviving copy of §1b's duplicate |
| `src/seon/test/selection.clj` | 215 | 0 | `reaching-tests` is the capability |
| `src/seon/test/accretion.clj` | 411 | 0 | generative contract checking, a separate feature |
| **Clojure subtotal** | **8,742** | **≈3,414** | |
| `bin/test` | 1,107 | **~1,000** | ~100 lines: acquire cluster, run, tally |
| `bin/test-fast` | 48 | **48** | — |
| `bin/_test-slot` | 165 | **165** | — |
| **Shell subtotal** | **1,320** | **≈1,213** | |
| **SOURCE + SHELL TOTAL** | **10,062** | **≈4,627 (46 %)** | |

Schemas under `resources/seon/schemas/seon.test*.edn` total 1,153 lines; the
worker/claim families (`seon.test.member`'s worker, claim-tx and host
attributes; parts of `seon.test.runner.edn`, 139 lines) shrink with §1, but I
did not read every schema line and do not count them. *(unverified — §7)*

### 6b. Test corpus

| Bucket | Lines | Basis |
|---|---:|---|
| The test system's own tests | 10,002 | counted: `test/seon/test/*.clj` + `test/seon/test_*.clj` |
| — of which pure process machinery | 1,513 | `test_runner_integration_test.clj`, counted, class B entire |
| — of which worker-machinery deftests in the two runner suites | ~1,600 *(estimated)* | `test/seon/test/runner_test.clj` (1,324) and `test/seon/test_runner_test.clj` (1,632) are **two separate runner suites**, not copies. I read both deftest lists: at least 20 of the second's 37 name the pool directly — `worker-demand-is-derived-after-task-classification`, `each-launched-worker-owns-its-first-task-before-startup`, `retired-workers-leave-one-bounded-leftover-wave`, `root-owning-tasks-never-co-run-inside-one-worker-group`, `isolated-confirmations-overlap-with-bounded-parallelism`, `unlaunchable-confirmation-worker-does-not-suppress-the-tally`, `a-dead-workers-task-is-never-classified-parallel-only`, `a-confirmation-loads-the-pool-workers-world`, `gate-completions-travel-as-a-file-not-as-code`, `a-missing-or-unreadable-staged-completion-is-named`; and in the first, `unused-workers-own-no-checkout`, `default-red-does-not-launch-confirmation`, `a-cold-worker-does-not-arm-its-base-around-host-test-bodies`, `the-exchange-bound-derives-from-the-long-declaration`, `a-declared-long-exchange-widens-the-silence-horizon`. What survives is one suite over report capture, the duration bound, the marker lifting and the recording authority |
| Tests that launch subprocesses | 16,287 | counted: files matching `ProcessBuilder|sh/sh|clojure.java.shell`. **Most of this is NOT deletable**: `dev/fresh_operator_test.clj` (2,206), `cluster/boot_test.clj` (1,837) and `operator_test.clj` (1,441) are the **platform tier** — boot from zero, the one thing that must run in its own process. They survive. |
| **Corpus class-B total that dies with §1** | **≈3,200** | 1,513 counted + ~1,600 read from the two runner suites' deftest lists + ~120 of `test_support.clj`'s file-backed published-root helpers (`:60-180`) |

### 6c. Two schema-law defects found in passing (file, not fix)

`await_owner_census_test.clj:8` and `fn_core_calls_test.clj:6-10` pass
**strings** where `:seon.fn/sym` is declared `:qualified-symbol`
(`resources/seon/schemas/seon.fn.edn:179-181`), against the 2026-09-17 symbol
law. Nine more files do the same: `bootstrap_test.clj:310`,
`operator_test.clj:474`, `test_reaching_test.clj:68`, `help_test.clj:90,106,109`,
`rereads_test.clj:165`, `adoption_contract_freshness_test.clj:91`. Whether the
schema bridge coerces could not be checked without a JVM; if it does not, those
queries match nothing and the assertions are vacuous — the project's own
absence-as-health class. **This is an issue note, not part of the deletion.**

---

## 7. What I could not verify

1. **Nothing was executed.** No JVM, no gate, no cluster. Every number here is
   read from source or quoted from a dated note that measured it. The
   4,627-line source figure is a sum of spans I read; the ~500 for
   `src/seon/test.clj` is *sampled*, not line-by-line.
2. **The schema shrink is uncounted.** `resources/seon/schemas/seon.test*.edn`
   is 1,153 lines; the worker/claim attribute families shrink with §1, but I
   did not read every schema line and claim no number.
3. **Whether the symbols-as-strings sites in §6c are red or vacuous** cannot be
   settled without running them — it depends on whether the schema bridge
   coerces `:qualified-symbol`.
4. **The ~950-line refusal-class extrapolation in §3d is extrapolation**, from
   one class read exhaustively (9 of 10 redundant) applied at a conservative
   15 % to 140 other `refuses`/`refusal` deftests. It is the only estimated row
   in the corpus total and should be confirmed before it is acted on.
5. **`src/seon/run.clj` (149 lines) is a legacy-named live namespace** required
   by `seon/turn.clj`, `background.clj`, `bootstrap.clj`, `my/agent.clj`,
   `my/turn.clj`. "Run" is the legacy spelling for **turn**. That rename is
   outside this audit's area; noted so it is not lost.
6. **The order of operations is not established here.** §13 of AGENTS.md
   requires a retirement and every caller's conversion in one slice. Deleting
   the worker pool converts `bin/test`, `bin/test-fast`, `bin/_test-slot`,
   `seon.test.fast`, two runner suites and `test_runner_integration_test.clj`
   at once. That is a coherent slice, but it is a large one, and it needs the
   owner's three-option gate before any production edit.

## 8. The recommendation in one line each

1. Delete the worker pool and everything that serves it: **−2,561** in
   `runner.clj`, **−200** in `cache.clj`, **−123** `fast.clj`, **−1,213** shell.
2. Collapse six run paths to `seon.test/check` over `run-owned`: **−500** in
   `test.clj`.
3. Delete the regression suite for the deleted machinery: **−3,200** in `test/`,
   and **12.7 of the 18.2 granted hours** go with it.
4. Fix the one named O(program) step left in the fixture —
   `schema/projection-from-database`, 3,996 ms — and the 72 class-A allowances
   stop being needed rather than being re-tuned.
5. File, do not bundle: the 38 half-declared markers (§2c), the symbols-as-strings
   sites (§6c), and the hand-maintained mirrors (§3b).
6. Do **not** plan a 30–40 % corpus cut. It is ~5 %. The corpus's problem is
   46 lines per deftest, and the fix is hoisting repeated setup into
   `seon.test-support` — accretion into the existing owner, not deletion.
