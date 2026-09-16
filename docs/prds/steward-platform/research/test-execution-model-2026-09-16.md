---
type: research
status: measured; six defects filed as findings, three options for the owner
created: 2026-09-16
tags: [testing, seon.test, seon.test.runner, bin/test, core.async.flow, datahike, sci, performance]
---

# What a gate is, whether it is IO-bound, and whether it should be a flow graph

Answering the owner's question: *"platform tests in separate JVMs, everything
else in the appropriate SCI and Datahike cluster context, parallelised, with
rerun derived from what changed. Tests are usually IO bound right? Can we turn
them into core.async flows on the io and compute channels so the scheduler
handles them better than this random worker parallelism guessing?"*

Read end to end for this note: [AGENTS.md](../../../../AGENTS.md) §0–§5,
[the test-system PRD](../plan/test-system-is-the-database-prd-2026-09-17.md),
[the stage-0 design](test-system-stage0-design-2026-09-17.md) (523 lines) and
[its orchestrator review](review-test-system-stage0-2026-09-17.md);
`bin/test` (807 lines) and `bin/_test-slot` (77); `src/seon/test.clj`,
`src/seon/test/fast.clj`, `src/seon/test/selection.clj`, and the runner regions
named below; `reference-code/core.async/.../impl/dispatch.clj` (123 lines) and
the `flow.clj` / `flow/impl.clj` proc seams; `src/seon/flow.clj` and
`src/seon/cluster/agent.clj`'s graph. Logs:
`tmp/orchestrator/gate-results/batch-107.log` (450 lines) and `batch-106.log`
(352). One read-only evaluation against `default`, mode `jvm`, at basis `:t`
**536871833** — the form and its result are in §2.4 and §4.3.

**Answer in one line.** The tests are *not* IO-bound: in both measured gates
about two thirds of all test-body milliseconds sit in 5–9% of the tests, and
those are Datahike transaction work, `config/apply!`, generative CPU and one
cold fixture construction per worker JVM — the only genuinely *waiting* test
found is one that deliberately blocks a `CountDownLatch`. The worker pool is
also not "random guessing": it is already a dynamically-claimed work queue
running at 83–91% utilisation. Flow would therefore buy scheduling we already
have, and the workload tag cannot be derived today (§4.3). The real money is in
§2.5: JVM/base/fixture preparation, not execution.

---

## 0. Defects found, first

| # | Defect | Evidence |
|---|---|---|
| D1 | **`:seon.test/platform` is not a database attribute.** The tier is read from Var/namespace *metadata* by `marker-reason` (`src/seon/test/runner.clj:653`, used at `:759`). `:seon.test/long` was moved onto the indexed program row with a drift check (`:676`, `:695`); platform never was. [AGENTS.md](../../../../AGENTS.md) §5 and [the PRD](../plan/test-system-is-the-database-prd-2026-09-17.md) §6 both describe it as a declared fact. | Live: `[?e :seon.test/platform _]` is refused — `seon.db/q cannot read uninstalled attribute :seon.test/platform`, the refusal listing the twelve `:seon.test/*` attributes that *are* installed (§2.4). |
| D2 | **Bare selection discovers test namespaces by filename convention.** `bin/test:660-666` runs `find test -name '*_test.clj'` and munges the path to a symbol (`_`→`-`, `/`→`.`). That is one of the three substitutes AGENTS §2.2 bans, in the launcher, ahead of every database-derived selection. | `bin/test:660-666` |
| D3 | **A derived `:io`/`:compute` workload tag does not partition the suite.** Only 10 functions declare `:seon.fn/workload :io`, 0 declare `:compute`, 3 declare `:seon.fn/destroys`. 1,585 of 1,856 tests (**85.4%**) reach one of those 13 identities. | §4.3 |
| D4 | **`gate-sets` costs seconds, not milliseconds, for a multi-seed population.** [The stage-1 design input](../plan/test-system-is-the-database-prd-2026-09-17.md) §4 records 14.181 ms for the worst single seed. Thirteen seeds in one shared-`seen` `gate-sets` call measured **6,213.51 ms** at basis 536871833. Consistent with the open issue [`gate-set-rederives-the-declared-reference-population-on-every-call`](../../../seon/issues/gate-set-rederives-the-declared-reference-population-on-every-call.md), whose measured whole-population number is 17–18 s. Stage 1's "≈ under 50 ms" selection budget is not established. | §4.3 |
| D5 | **Cold fixture construction is charged to one test's own bound.** `seon.test/run`'s `bounded-result` (`src/seon/test.clj:126`) starts the per-test clock before the canonical base exists in that worker JVM. Visible directly: the first `with-database` test in each worker ends at 15.6–16.8 s; a first test that touches no database ends at 89–102 ms (§2.2). The stage-0 note flags the same at its §1. | `src/seon/test.clj:126`; batch-106/107 `END` lines |
| D6 | **Ugly output on failure (standing order, AGENTS §2.4).** One `FAIL` block in `batch-106.log` prints an entire `:seon.fn/ast` entity, its `:seon.fn/source` string and its whole `:seon.fn/calls` ref list. `report-event!` forwards assertion failures to unbounded `clojure.test/report` (`src/seon/test/runner.clj:199`); [test-suite-cost-plan](../../context-generation/research/test-suite-cost-plan-2026-09-15.md) records a failed batch emitting **78,581,555 bytes**. | `batch-106.log`; `src/seon/test/runner.clj:199` |

---

## 1. What a gate is today, mechanically

### 1.1 One invocation, phase by phase

`bin/test` re-execs itself from an in-memory copy of its own bytes first
(`bin/test:33-37`), so an edit mid-run cannot splice into the running
invocation. Then, in order, with the measured `PHASE` lines from the two logs:

| Phase | `bin/test` lines | What it does | batch-107 | batch-106 |
|---|---|---|---:|---:|
| (pre) | `:63-77` | refuse unless `.agents/skills`, `seon-skills`, `.claude/skills` resolve to one tree | — | — |
| (pre) | `:79-254` | parse flags into `selection_mode` ∈ {`explicit`,`changed`,`all`,`full`,`platform`}; refuse `--result-cluster default` | — | — |
| (pre) | `:266-347` | reap retained `tmp/test-runs/run.*` roots (live pid, 3 newest, 24 h) through `seon.fs/delete-recursively!` | — | — |
| `snapshot` | `:519-569` | `git archive HEAD \| tar -x` into a fresh `run.XXXXXX` root, then overlay either `--paths` or every tracked change plus every non-ignored new file; symlink `.git`, `.env`, `reference-code` | **2 s** | **2 s** |
| `test-slot` | `:571-574` | `acquire_test_slot` | **0 s** | **0 s** |
| `dependency-cache-and-classpath` | `:586-675` | `clojure -T:dev-cache ensure-cache`, read back `:seon.dev-cache/digest`, `/path`, `/test-classpath-file`, `/test-digest` | **2 s** | **90 s** |
| `worker-checkouts` | `:677-718` | decide `worker_count`, copy-on-write one checkout per worker into `$run_root/workers/pool-N` | **2 s** | **2 s** |
| `published-base` | `:743-766` | build or reuse the shared published base under `target/test-published-bases/<test-digest>/base` | **113 s** (`PUBLISH`, work-ms 112,098) | **0 s** (`REUSE`, work-ms 1) |
| `coordinator-and-tests` | `:768-795` | launch `seon.test.runner` with eight `-D` properties; it loads namespaces, builds the program graph, runs the tiers | **153 s** | **195 s** |
| (post) | `:798-807` | delete the run root on success, retain it on failure; release the slot; exit the coordinator's status | — | — |

Gate wall: **272 s** (batch-107) and **289 s** (batch-106). Both were red and
both retained their root.

Inside `coordinator-and-tests`, batch-107's own timestamps:

| Segment | Span | s |
|---|---|---:|
| coordinator JVM boot → `START` | (before 21:03:07.560) | ≈45 |
| `LOAD`/`LOADED` 4 test namespaces | 21:03:07.560 → 21:03:08.959 | 1.4 |
| `SELECT building the program graph` → `TIER platform` | 21:03:08.959 → 21:03:27.094 | **18.1** |
| first `BEGIN` → last `END` | 21:03:27.099 → 21:04:54.621 | **87.5** |
| reporting, recording, shutdown | remainder | ≈1 |

Both gates printed `TIER platform 0 tests` — an explicit namespace selection
whose four namespaces declare no platform marker.

### 1.2 Workers, and how tests reach them

`worker_count = logical_processors / 2`, capped at **3** (`bin/test:677-692`),
floored at 1, further capped to the namespace count for an explicit selection
(`:696-698`), overridable by `SEON_TEST_WORKERS` (`:699-701`). The comment at
`:682-686` records why 3: each worker loads the whole program (~1 GB, 100%+
CPU), the confirmation phase reruns failures in fresh JVMs up to the same
count, and one gate at cores/2 reached load average 75 on 18 cores
(2026-09-15). Test JVMs are launched `nice -n 15` (`bin/test:400-403`).

**"pool-1/pool-2/pool-3" are those worker checkouts**, one directory each under
`$run_root/workers/` (`bin/test:704-708`), and the worker id in every
`BEGIN`/`END` line is that directory's name. Each is a separate JVM launched by
the coordinator (`src/seon/test/runner.clj:2874`) as
`clojure -Scp <coordinator classpath> -J-Dseon.operator.root=… -J-Dseon.test.published-base=… -M:test -m seon.test.runner --worker <id>`,
with its own isolated operator root, and driven over its stdin/stdout by an EDN
**exchange** (`worker-exchange!`, `:2765`).

**Partitioning is not static and not by namespace.** The coordinator derives
*tasks* (`test-tasks`, `:801`): a namespace declaring `::test/once-fixtures` or
a `test-ns-hook` becomes ONE atomic task holding all its selected Vars
(`atomic-namespace-task?`, `:796`); every other namespace contributes one task
per Var. Tasks are sorted long-first and pushed into a single
`LinkedBlockingQueue`; each worker loops `.take`-ing from it
(`run-task-pool!`, `:3159-3182`). It is already a dynamically-claimed shared
queue. Tasks whose symbols are not in the published manifest are split off
(`split-resolved-tasks`, `:845`) and run on a **serial** worker (`:3185-3193`);
leftovers from a retired worker get one bounded wave on that serial worker, or
a typed `worker-pool-exhausted` result — never a silent drop (`:3199-3232`).

The evidence that claiming is dynamic is in the logs: in batch-106 the three
workers took **33 / 71 / 16** tasks for **121.1 / 117.7 / 134.0 s** — wildly
uneven counts, closely matched times.

### 1.3 The bounds

| Bound | Value | Owner |
|---|---|---|
| suite silence horizon | 300 s (`SEON_TEST_SILENCE_SECONDS`) | `silence-seconds`, `runner.clj:506` |
| per-exchange bound | `max(60, silence − 30)` = **270 s** | `exchange-bound-seconds`, `:524`; printed on every `BEGIN` |
| worker retirement after `destroyForcibly` | 10 s, then reported | `retire-worker!`, `:2747` |
| runner reap backstop | 10 s, then `kill -KILL` | `bin/test:375-390` |
| fixture event backstop | 20 s | `seon.test-support/event-backstop-seconds`, `test/seon/test_support.clj:29` |
| test slot wait | 1800 s (`SEON_TEST_SLOT_WAIT_SECONDS`) | `bin/_test-slot:15` |

The exchange bound is deliberately *inside* the silence horizon so the typed
task bound always fires before the watchdog, which otherwise killed runs at
exit 124 with the coordinator parked mid-exchange (`:524-533`).

### 1.4 Slots, `SEON_TEST_ORCHESTRATOR`, and the eight-batch queue

`bin/_test-slot` is a machine-wide admission primitive sourced by `bin/test`
and `bin/test-fast`. **Two slots per source checkout** (`SEON_TEST_SLOTS`,
`:14`) as project-local directories under `tmp/test-slots/` whose `mkdir` is
atomic; a slot whose holder pid is dead is reclaimed (`:46-51`); exhaustion
after the bound is a loud failure naming every holder (`:53-61`), never a hang.
The declared reason (`:4-13`): each invocation launches a program-loading JVM at
100%+ CPU, and on 2026-09-15 six lanes launched eight at once, starving the
owner's cluster and the live agent run.

`SEON_TEST_ORCHESTRATOR=1` is the exemption to **orchestrator-only mode**
(`:19-27`): while `tmp/test-slots/orchestrator-only` exists, any invocation
without it is refused with the rule and told to commit and list its namespaces
in `tmp/orchestrator/gate-requests/<lane>.txt`.

**"The gate queue of eight batches" is exactly that mechanism.** A batch is one
`bin/test` invocation over a named set of namespaces — batch-107's `selection=`
line is `seon.db-test seon.schema-test seon.maintenance-schema-test
seon.turn-test`. The queue exists because of three rules acting together:
AGENTS §5 forbids a lane `--all`/`--full` and reserves full suites for the
orchestrator's integration checkpoints; AGENTS §7 caps the machine (≤3 lanes at
the page, workers capped at 3, ≤4 prepl connections) and says lanes never run
tests; and `bin/_test-slot` admits two invocations per checkout. With eight
lanes' worth of gating to do and two slots, the orchestrator serialises them
into a numbered queue and hands each lane its reds from the log.

### 1.5 The platform tier and what runs isolated

The tier partition is `test-selection` (`runner.clj:763`): the platform tier
runs FIRST on every tiered invocation and the run stops when it is red. Its
membership is `platform-reason` (`:759`) → `marker-reason` (`:653`) → the Var's
or its namespace's `:seon.test/platform` metadata, which must be a non-blank
*reason* string. **This is D1: it is metadata, not a fact.**

Destructiveness is separately and correctly derived. `:seon.fn/destroys` is
declared once at the definition (`src/seon/fn.clj:687-690`) and every consumer
reaches it through `:seon.fn/calls` (`seon.test/host`, `src/seon/test.clj:293-327`):
a test reaching a declaring function answers `:seon.test.host/isolated-snapshot`,
anything else answers `:seon.test.host/in-process`, and an unanswerable call
graph is the typed unknown, never "in-process". `destructive-refusal`
(`src/seon/test.clj:346`) refuses such a test *without executing* in any JVM whose
declared operator root is the development checkout — the check behind
[`a-platform-tier-test-wiped-the-checkouts-store`](../../../seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md).

So today: **everything a `bin/test` gate runs runs in an isolated worker JVM**
under its own operator root. The in-process path (§3) is a separate entry, used
by `seon.test/check` and by an agent's `my.test/run`, and it is where the
destructive refusal bites.

---

## 2. Are the tests IO-bound? No. Measured.

### 2.1 Distribution of per-test `elapsed-ms`

Every `END worker=… elapsed-ms=… task=…` line from each log.

| | batch-107 | batch-106 |
|---|---:|---:|
| tests (`END` lines) | 104 | 120 |
| total body ms | 218,967 | 372,737 |
| mean | 2,105 | 3,106 |
| p50 | **352** | **379** |
| p75 | 891 | 1,411 |
| p90 | 4,245 | 9,173 |
| p95 | 15,634 | 16,262 |
| max | 42,208 | 73,654 |
| < 100 ms | 22 | 37 |
| 100 ms – 1 s | 58 | 44 |
| 1 – 5 s | 17 | 19 |
| 5 – 15 s | 1 | 12 |
| ≥ 15 s | 6 | 8 |
| ms held by tests ≥ 10 s | 149,394 (**68.2%**) in 6 tests | 244,835 (**65.7%**) in 11 tests |
| ms held by all tests < 1 s | 23,479 (10.7%) in 80 tests | 18,839 (5.1%) in 81 tests |

The shape is the same in both: a median test costs a third of a second, and two
thirds of the time is in under a tenth of the tests.

### 2.2 The ten slowest, opened and classified

| ms | test | What it actually does | Class |
|---:|---|---|---|
| 73,654 | `seon.fn-test/indexing-uses-a-prebuilt-manifest-without-analysis` | `seon.fn/build-manifest` over `seon.fn/source-roots` plus a written fixture root, then `index!` into a branch (`test/seon/fn_test.clj:1148-1167`) | **CPU** (whole-tree analysis) + database |
| 42,208 | `seon.turn-test/transitions-agree-with-the-model` | `tc/quick-check` **60 trials**, each a fresh `with-database` branch plus a command sequence executed against the writer (`turn_test.clj:1787-1830`) | **CPU + Datahike transactions** |
| 39,157 | `seon.test-reaching-test/an-expired-check-reports-the-verdicts-it-already-recorded` | deliberately runs a probe test whose body is `(.await (CountDownLatch. 1) 60 SECONDS)` and asserts the measured allowance fires (`test_reaching_test.clj:786`, helper at `with-expiring-selection`) | **Waiting, by design** |
| 38,503 | `seon.turn-test/virtual-turns-use-the-proc-and-compaction-is-agent-scoped` | builds the fixture **twice**; each builds a cluster (`config/apply!` + `seed-cluster!`), two agents, an SCI ctx fork, a flow work launcher, then submits virtual turns (`turn_test.clj:358-400`, `:660`) | **Fixture / `config/apply!`** |
| 21,166 | `seon.turn-test/recovery-preserves-terminal-receipts-exactly` | `tc/quick-check` 30 trials, fresh branch each (`turn_test.clj:1841-1875`) | **CPU + transactions** |
| 16,769 | `seon.fn-test/an-attribute-declared-after-this-jvm-started-is-indexed-without-a-restart` | first `with-database` on pool-3 | **Cold fixture base (D5)** |
| 16,746 | `seon.test-reaching-test/an-in-process-check-excludes-a-destructive-test-and-reports-it` | seeds a cluster, runs `check` in process | Fixture + database |
| 16,329 | `seon.fn-test/agent-source-reaches-the-evaluator-through-one-visible-path` | three `production-callers` queries inside `with-database` — first `with-database` on pool-2 (`fn_test.clj:1778-1798`) | **Cold fixture base (D5)** |
| 16,262 | `seon.fn-test/a-declaration-answers-its-source-root-through-its-file` | **two** `db/q` assertions inside `with-database` — first `with-database` on pool-1 (`fn_test.clj:2267-2279`) | **Cold fixture base (D5)** |
| 16,154 / 15,729 / 15,634 | the three first `seon.db-test` tests, one per pool, batch-107 | first `with-database` in each worker | **Cold fixture base (D5)** |

The control that proves D5: pool-2's *first* batch-106 task,
`a-file-changed-after-capture-analyzes-to-the-captured-spans`, ended in **89
ms** — it writes a file and calls `analyzer/analyze` with no
`with-database` (`fn_test.clj:2404-2425`). Its *second* task was the 16,329 ms
one above. The 15–17 s is not JVM start and not the test; it is the one
canonical base construction that the first database test in a worker pays.

**Exactly one of the ten is waiting on an event, and it is waiting on purpose.**
Nothing sleeps. No test in either gate makes provider HTTP calls.

### 2.3 What the fixture base costs, and what a branch costs

`seon.test-support/with-database` (`test/seon/test_support.clj:1019-1025`)
routes to `with-branched-database` (`:974`) unless a test asks for
`:seon.test-support/database-id` or `fresh-store?`, which take the slow
`with-fresh-database` path (`:954`) that repopulates a private in-memory store.

The branched path: `acquire-base!` takes a hold on one process-wide canonical
base (`database-base`, `:609`, a `defonce`-backed `retrying-base`), built once
per publication per JVM by `create-base` (`:379`) — which clones the runner's
published base store into `tmp/fixture-bases/base-*` — then `d/branch!`s a
leased branch name from it (`:990-994`), reconnects carrying the base's
projection, runs the body, deletes the branch and releases the hold
(`:1010-1017`).

Dated costs, from the notes that measured them (not re-measured here):

| Measurement | ms | Source |
|---|---:|---|
| `with-database` alone — a branch of the warm canonical base | **1** | [turn-bookkeeping-cost](../../context-generation/research/turn-bookkeeping-cost-2026-09-16.md):94 |
| `with-database` + `seed-cluster!` | 2,354 / 2,341 | same |
| `config/apply!`, first call / identical again | 3,009 / 2,101 | same |
| empty `with-cluster` (two applies + launcher) | 5,017 / 4,851 / 5,050 | same |
| fresh worker JVM startup | 11,189 – 16,179 | [test-suite-cost-plan](../../context-generation/research/test-suite-cost-plan-2026-09-15.md):23 |
| cold base publication | 41,508 / 118,399 / 44,011 | same |
| reused base publication | 3 | same |
| cold base publication, batch-107 | **112,098** | `batch-107.log` |
| reused base publication, batch-106 | **1** | `batch-106.log` |

**The branch is not the cost; `config/apply!` is.** A `with-database` branch is
1 ms. A fixture that seeds a cluster pays ~2.3 s, and one that stands up a
cluster pays ~5 s, because `config/apply!` compiles and reconciles the manifest
and is paid twice.

### 2.4 The live population

One evaluation, `mode: jvm`, cluster `default`, explicit custody via
`(seon.operator/connection "default")`, basis `:t` **536871833**:

```clojure
(let [database (seon.db/db (seon.operator/connection "default"))]
  {:tests      (seon.db/q '[:find (count ?e) . :where [?e :seon.test/sym]] database)
   :functions  (seon.db/q '[:find (count ?e) . :where [?e :seon.fn/sym]] database)
   :workload-io (seon.db/q '[:find [?s ...] :where [?f :seon.fn/workload :io]
                                                   [?f :seon.fn/sym ?s]] database)
   :destroys    (seon.db/q '[:find [?s ...] :where [?f :seon.fn/destroys _]
                                                   [?f :seon.fn/sym ?s]] database)})
```

| Observation | Result |
|---|---|
| tests / functions | **1,856** / **5,106** |
| `:seon.fn/workload :io` | **10** functions |
| `:seon.fn/workload :compute` | **0** functions |
| `:seon.fn/destroys` | **3** functions |
| `:seon.effect/capability` | **10** functions |
| `(gate-sets database <those 13 seeds>)` | 1,585 distinct tests in **6,213.51 ms** |
| `[?e :seon.test/platform _]` | **refused**: `seon.db/q cannot read uninstalled attribute :seon.test/platform` (D1) |

### 2.5 Honest split of gate wall time

batch-107 (272 s wall, three workers):

| Bucket | s | share |
|---|---:|---:|
| JVM starts, snapshot, dependency cache, worker checkouts | ≈51 | 19% |
| shared base publication (cold) | 113 | 42% |
| program-graph build in the coordinator (`SELECT`) | 18 | 7% |
| **test bodies** (218,967 ms over 3 workers ⇒ 73.0 s of a 87.5 s window) | **73** | **27%** |
| worker idle inside the execution window | 14.5 | 5% |

Worker utilisation in the execution window: batch-107 **83.4%**
(219.0 of 262.6 worker-s), batch-106 **90.9%** (372.7 of 410.0). The 340-test
batch-12b gate recorded in
[suite-efficiency-plan](../../context-generation/research/suite-efficiency-plan-2026-09-15.md)
sums to 720,985 worker-ms in a 264,398 ms window across three workers — **90.9%**.

With a warm base (batch-106) the shape changes but the conclusion does not:
289 s wall, 90 s of it a dependency-cache lock wait (`lock wait-ms= 87431`),
18 s program graph, 136.7 s execution window holding 372.7 worker-seconds.

**So: roughly a quarter to a half of a gate is test bodies; the rest is
preparation, and one cold publication costs more than every test in the gate
put together.** Parallelism is not the bottleneck. Three workers at 83–91%
utilisation is not "random guessing".

---

## 3. The in-process path that already exists

| Function | File:line | What it is |
|---|---|---|
| `seon.test/run` | `src/seon/test.clj:393` | run one test Var, commit its result facts, return them pulled from `:db-after`. Refuses a destructive test in a development-root JVM before executing; snapshots and restores the live cluster's schema registry around the body; converts drift into an extra error count |
| `seon.test/run-owned` | `:467` | an agent's entry: supplies the agent's own `:seon.db/connection` as the **body's custody**, so the body's elided `seon.db` arities reach that cluster. `run` itself hands none — a host REPL is nobody's cluster work |
| `seon.test/check` | `:736` region | the in-process gate: selects, primes the fixture base, loads and arms, then runs the selected tests **serially** in a loop with a total deadline |
| `seon.test/resolve-test` | `:513` region | `requiring-resolve` on the test symbol — a Var lookup, not an identity resolution |
| `seon.test.runner/run-var!` | `src/seon/test/runner.clj:579` | the one capture path shared by the worker and the in-process run |
| `seon.test.runner/run!` | `:1737` | the worker-side request handler |
| `seon.test.fast` | `src/seon/test/fast.clj` (61 lines) | `bin/test-fast`: one armed JVM, no operator root, no checkout copy, no base publication |

**Custody is a value, never a re-read.** `run-var!` (`:612-633`) binds
`clojure.test`'s four dynamic Vars itself and then calls
`(db/call-with-custody custody #(test/test-vars [test-var]))`
(`src/seon/db.clj:259`). The docstring at `:596-603` records the incident that
forced this: before the connection was taken as a value, a `bound-fn` or
virtual thread carried whatever custody was in scope, and on 2026-09-17 an
agent's own test wrote synthetic schema rows into `default`'s datoms and every
later write on the cluster was refused.

**Arming.** A worker and `test-fast` both call
`seon.test.arm/initialize-contracts!` and re-assert on every `:begin-test-ns`
(`fast.clj:29,:42`). The in-process `check` calls `prepare-tests!`
(`test.clj:516`) instead, and primes the canonical base explicitly before
starting any per-Var backstop when the selection reaches
`seon.test-support/with-database` (`test.clj:741-747`) — the one place the D5
charge is already avoided.

**The drift detector** is two independent checks:
`ambient-snapshot`/`ambient-drift` (`runner.clj`, around `run-var!`) derive the
worker JVM's process-global state — the wrappers malli actually installed, the
contracts it holds, the clusters running, the shared SCI base — and report what
a task changed, converting it into an error count on the result. Separately
`run` calls `runner/live-cluster-schema-states` → `restore-live-cluster-schema!`
→ `schema-restore-drift` (`test.clj:431,440,441`; `runner.clj:1184,1269`) so an
in-process run cannot leave the live cluster's schema projection poisoned.

**The known gap** is
[`the-in-process-test-loader-cannot-load-a-namespace-needing-a-test-alias-dependency`](../../../seon/issues/the-in-process-test-loader-cannot-load-a-namespace-needing-a-test-alias-dependency.md).
Its cause is visible in four lines: `test-loader` (`test.clj:106-111`) builds a
`DynamicClassLoader` and adds **only `[:aliases :test :extra-paths]` read out of
`deps.edn`** — no jar, no `tools.build`, no `core.async.flow-monitor`. The
stage-0 note's §3 fix is right and cheap: `dev_cache.clj:466-491` already calls
`tools.build`'s `create-basis {:project "deps.edn" :aliases [:test]}` and holds
its ordered `:classpath-roots`; hand that one value to both hosts.

**An agent-authored deftest as a program fact (PRD stage 2).** The evaluator
already mints the row: `seon.sci.eval` lifts `:seon.workload` and the same
markers onto a definition at `src/seon/sci/eval.clj:417-418`, exactly as
`seon.fn/var-row` does at `src/seon/fn.clj:679-680`. At basis 536871833 the
population is **1,822 `:core` / 1 `:agent`** (stage-0 note §1). Such a row has
`:seon.test/sym`, `:seon.test/ns`, `:seon.test/source`,
`:seon.schema.admission/source :agent` and no `:seon.fn/file` — precisely the
shape `with-expiring-selection` writes by hand today
(`test/seon/test_reaching_test.clj`, the `transacted!` of
`{:seon.test/sym … :seon.schema.admission/source :core :seon.test/ns …
:seon.test/source (pr-str source)}`). Resolution is then: pull by
`[:seon.test/sym s]`, read `:seon.schema.admission/source`, and either
`requiring-resolve` the Var (core) or evaluate the stored source in the
cluster's base SCI ctx and take the resulting SCI Var (agent). Nothing new is
needed on the fact side; only `resolve-test` has to stop being
`requiring-resolve`.

---

## 4. Flow

### 4.1 What core.async actually gives us

`clojure.core.async.impl.dispatch` (123 lines) memoises one executor per
workload tag (`executor-for`, `:98-111`): `:compute` is
`Executors/newCachedThreadPool` named `async-compute-%d` (`:94`), `:io` is a
virtual-thread-per-task `Executor` when available (`make-io-executor`, `:82-89`),
`:mixed` is another cached platform pool (`:96`).

`flow/process` (`flow.clj:263-286`) takes `:workload` and, for `:compute`,
`:compute-timeout-ms` **default 5000**. Its semantics, verbatim in intent: for
`:mixed`/`:io` the workload dictates the thread the **process loop including its
calls to transform** runs in; for `:compute` each transform call runs in a
separate thread, the loop runs in `:io`, and the loop **blocks awaiting the
future for `compute-timeout-ms`, reporting on `::flow/error` if it times out**
(`flow/impl.clj:258-262`).

Seon's construction seam is `seon.flow/var-process` (`src/seon/flow.clj:125-177`):
it refuses a non-Var step (hot reload would stop applying), refuses a missing or
`:mixed` workload (`:mixed` pins one platform thread per proc and is the measured
scaling cliff), and refuses `args` naming no `:seon.env/environment`. The agent
graph (`src/seon/cluster/agent.clj:440-470`) is one `create-flow` definition of
two `:io` procs — `#'mailbox-step` and `#'turn/step` — joined by a
`(sliding-buffer 1)` conn, with the environment scoped per agent and carried in
`:args`.

### 4.2 `clojure.test`'s dynamic-binding model, and whether flow respects it

`clojure.test` needs four dynamic Vars on the executing thread:
`*report-counters*` (a `ref`), `*testing-vars*`, `*testing-contexts*` and the
`report` multimethod itself; `use-fixtures` `:once`/`:each` are applied by
`test-vars`, which groups by namespace.

The decisive mechanical fact:

- `clojure.core.async/thread-call` applies **`bound-fn*`** before dispatching
  (`reference-code/core.async/.../async.clj`, `thread-call`), so bindings *are*
  conveyed.
- `clojure.core.async.flow.impl/futurize` (`flow/impl.clj:29-36`) wraps a raw
  `FutureTask` and calls `.execute` — **no `bound-fn*`, no conveyance**. Seon's
  own `var-process` docstring states it as the design: *"flow conveys no
  bindings anywhere by design"* (`src/seon/flow.clj:134-136`).

This is survivable, because `run-var!` already establishes every binding it
needs *inside* the executing thread (`runner.clj:625-633`) rather than
inheriting it. A proc transform that calls `run-var!` is correct. But it means
nothing outside `run-var!` may rely on conveyance, and the `:compute` path adds
a second hazard: **a test as a `:compute` transform would hit the 5,000 ms
`compute-timeout-ms` default**, and at p90 = 4,245/9,173 ms (§2.1) a large
fraction of this suite exceeds it. It would have to be set per graph to the
exchange bound.

### 4.3 Can the workload tag be derived? No — measured.

`:seon.fn/workload` already exists as a stored fact
(`resources/seon/schemas/seon.fn.edn:142`, enum `[:io :compute]`, indexed from
`:seon.workload` metadata at `src/seon/fn.clj:679-680` and
`src/seon/sci/eval.clj:417-418`). It is a **declaration on capability entry
points**, not a derivation: `seon.fn` refuses an `:seon.effect/capability`
marker without a workload and refuses one that is not `:io`
(`src/seon/fn.clj:1785-1794`).

Measured at basis 536871833: 10 functions declare `:io`, **0** declare
`:compute`, 3 declare `:seon.fn/destroys`. Tests reaching any of those 13
identities: **1,585 of 1,856 = 85.4%**.

So the proposed derivation — *a test reaching `Thread/sleep`, provider HTTP,
filesystem or an event backstop is `:io`, otherwise `:compute`* — labels 85% of
the suite `:io` and would put essentially the whole suite on one executor. That
is not a partition; it is a relabelling. And §2.2 says the label would be
**wrong**: the expensive tests are Datahike transactions, `config/apply!` and
generative CPU, which are compute-and-blocking-storage, not I/O waiting.

`gate-sets` on those 13 seeds also cost **6,213.51 ms** (D4), so the tag is not
free to compute either.

### 4.4 Where per-test isolation breaks inside one JVM

This is the real objection to in-process parallelism, and it is already
measured, not hypothetical.

| Class | Evidence |
|---|---|
| **Tests that mutate worker-global instrumentation** | The two "batch-12b" tests the plan names: `seon.sci.eval-test/instrumented-generated-form-does-not-advise-an-absent-program-row` **added** 46 instrumented Vars; `seon.sci.eval-test/the-evaluator-remains-live-after-its-namespace-reloads` **removed** 24 (`tmp/orchestrator/gate-results/batch-12b/p1-ambient-state.md`). Caught only by `ambient-drift` |
| **A reload that captured roots cannot restore** | `restoring-captured-roots-reinstalls-a-definition-a-reload-replaced`: a body that reloads a program namespace replaces the protocols/classes the closures were compiled against; reinstating the roots left every later `seon.print/text-sink` in that worker building a superseded class its own `sink?` refused ([AGENTS.md](../../../../AGENTS.md) §5) |
| **Parallel-only failures under load** | Eleven tests failed in a nine-worker pool and passed on immediate isolated rerun, plus two agent generative properties ([`parallel-test-stress-exposes-eleven-isolation-sensitive-tests`](../../../seon/issues/parallel-test-stress-exposes-eleven-isolation-sensitive-tests.md)). One concrete shared resource named: `Clj-kondo cache is locked by other thread or process` |
| **Shared fixture base** | [`in-process-test-runs-poison-the-shared-fixture-base`](../../../seon/issues/in-process-test-runs-poison-the-shared-fixture-base.md) |
| **Shared kondo cache** | [`a-fixture-namespace-poisons-the-workers-shared-kondo-cache`](../../../seon/issues/a-fixture-namespace-poisons-the-workers-shared-kondo-cache.md) |
| **Concurrent classpath resolution** | [`a-concurrent-classpath-resolution-corrupts-tools-deps-and-aborts-the-gate`](../../../seon/issues/a-concurrent-classpath-resolution-corrupts-tools-deps-and-aborts-the-gate.md) — the batch-106 `lock wait-ms= 87431` is this seam under contention |
| **`with-redefs` / `alter-var-root`** | `with-redefs` is `alter-var-root` on the **root** binding, process-wide and not thread-local. `seon.fn-test/indexing-uses-a-prebuilt-manifest-without-analysis` (`fn_test.clj:1161`) redefs `analyzer/analyze` to throw. Any concurrent test in the same JVM calling `analyze` during that window fails. This is not detectable by `ambient-drift`, which snapshots before and after, not during |

`with-redefs` alone rules out arbitrary intra-JVM test parallelism without a
declared fact naming which Vars a test redefines.

### 4.5 The right unit of parallelism

Given the numbers: **the namespace, as it already is** — but for the fixture
reason, not the scheduling reason.

- Per test is wrong wherever a namespace has `:once` fixtures or a
  `test-ns-hook`: `run-var!` calls `test-vars [v]`, so n singleton runs pay the
  `:once` fixture n times. `test-tasks` (`runner.clj:801`) already makes exactly
  that exception.
- The `with-database` branch is **1 ms** (§2.3), so the fixture-branch is *not*
  a unit worth grouping by. What is worth grouping by is `config/apply!` at
  2–3 s a call, paid twice per seeded cluster — and that is a per-body cost
  today, not a `:once` fixture, so no claim granularity saves it. **The
  measurable win is making `config/apply!` idempotent-cheap or hoisting it into
  a `:once` fixture, not re-slicing the queue.**

---

## 5. Selection by change: what already derives it

| Concern | Function | File:line | Basis |
|---|---|---|---|
| tests reaching an identity | `seon.fn/gate-sets` / `gate-set` / `tests-reaching` | `src/seon/fn.clj:1375`, `:1406`, `:1416` | shared-`seen` frontier walk over `:seon.fn/calls`, declared reference edges and unresolved file references, acquired **once per operation** for many seeds |
| changed dependencies of one test | `seon.test/changed-since-green` | `src/seon/test.clj:54` | the test's recorded `:seon.test/reach`, its own result history for the last green `:t`, then `db/since` over `:seon.fn/source` and `:seon.fn/spec`. Missing history or closure evidence is the typed unknown |
| changed identity → tests | `seon.test/identity-tests` / `changed-reach` / `reaching` | `:490`, `:519`, `:526` | `:seon.fn/sym` → `tests-reaching`; `:seon.test/sym` → itself; `:seon.ns/name` → its tests plus everything reaching its members; **anything else is the typed "the reaching set cannot bound a schema change"** |
| staleness by content | `seon.test/stale-in` | `:546` | compares the recorded `:seon.test/reach-digest` against a freshly derived one; a test with `:seon.test/fixture-observation` is never eligible |
| in-process gate | `seon.test/check` | `:736` region | assembles the above, then runs serially |
| recorded reach | `record-tx` | `src/seon/test/runner.clj:2097` | derives `reach-digests` and `reach-memberships` from the **tested** database value and writes them as a delta — an unchanged re-record writes zero datoms beyond transaction metadata (157,981 → 0, measured 2026-09-17, `:2090-2096`) |

**Is anything selected by mtime?** No. `seon.test/selection`'s
`input-digests` (`src/seon/test/selection.clj:69`) is SHA-256 over file bytes,
and `changed-inputs` (`:85`) compares digests. There is no `lastModified` call
in the selection path.

**Is anything selected by a hand list or a naming convention?** Yes, two:

1. **D2** — `bin/test:660-666` derives the bare selection's namespace list from
   `find test -name '*_test.clj'` and a filename-to-symbol munge.
2. **D1** — the platform tier is Var/namespace metadata, not a fact.

And one file artifact: the green basis lives at
`tmp/test-basis/green-basis.edn` (`selection.clj:187-213`), a checkout-local
file, not a fact on the cluster. The suite-efficiency probe found it **absent**,
which silently widens the bare gate to every eligible test.

**Distance to stage 1's one `select`.** The derivations exist and are correct;
what is missing is that they are called from three places with three different
bases — `check-in-process` uses stale test rows, the coordinator uses the file
basis, `seon.test.fast` uses named loaded namespaces (stage-0 note §1). So
stage 1 is a consolidation, not an invention. Two caveats the stage-1 text does
not carry: the **≈50 ms** selection budget is not established (D4: 6.2 s for 13
seeds), and `:seon.test/platform` must become an installed attribute before
`select` can answer the platform member at all (D1).

---

## 6. Three options

Stated against the measured split: **test bodies are 27% of a cold gate and
~50% of a warm one; the workers already run at 83–91%; the median test is
352 ms and two thirds of the time is in six to eleven tests.**

### What "parallelism inside one JVM" actually buys

It removes, per gate: three worker JVM starts (11.2–16.2 s each, but overlapped,
so ~16 s of wall), three worker checkout copies (2 s), and **three cold
canonical base constructions** (15.6–16.8 s each, D5) — because one JVM builds
the base once. Against batch-107's 272 s that is roughly **30–35 s of wall**,
about 12%. It does **not** touch the 113 s publication or the 18 s program-graph
build. It buys back a fraction of a fraction.

What it risks is concrete: with `with-redefs`, `alter-var-root`, instrumentation
re-arm and the eleven known parallel-only tests (§4.4), concurrent bodies in one
JVM produce failures that do not reproduce alone — and if that JVM is the
owner's `default` cluster, a wedged test wedges the window he is working in. The
existing bounds only partly cover it: SCI's `time-limit`/`:interrupt-fn`
(`reference-code/sci/doc/interrupt.md`) bounds an **SCI** evaluation, so it
applies to an agent-authored test's body and to nothing else; a first-party
`deftest` running as loaded Clojure is bounded only by `bounded-result`'s
`FutureTask` on a virtual thread (`test.clj:126-140`), and a `FutureTask` that
times out is *abandoned*, not killed — the thread keeps running, and with it any
`with-redefs` it installed. The isolated worker JVM is what makes today's
abandonment survivable, because `retire-worker!` (`runner.clj:2747`)
`destroyForcibly`s the whole process.

### Option A — keep worker JVMs, claim tests from a run entity (PRD stage 3). **Recommended.**

**Guarantee.** One selection function on both hosts; the run entity's members
are written before execution; workers claim through a `[:db.fn/call …]`
transaction function so the writer decides and no pre-read can race; a second
launcher against the same cluster, digest and basis selects only the complement;
a dead worker's claim is reclaimed by the existing process-record liveness
census. Process-level isolation is unchanged, so every defect in §4.4 stays
contained, and a wedged test is still killable with `destroyForcibly`.

**Cost.** The stages as priced: 1 day selection, 1 day resolution, 1–1.5 days
claims, ½ day tally. Plus the two corrections this note adds: install
`:seon.test/platform` as an attribute with the `verify-long-declarations-indexed!`
drift check already written for `:seon.test/long` (D1), and delete
`bin/test:660-666` (D2).

**What we give up.** The three redundant cold base constructions per gate
(~16 s each) and the three JVM starts stay. Nothing else.

### Option B — in-process flow graph in the cluster's JVM for the non-platform tier

A proc claiming test identities, a proc per workload executing them, a proc
recording through the one writer; worker JVMs kept for the platform/destructive
tier only.

**Guarantee.** One base construction per gate, and an agent's test runs on
exactly the seam the batch gate uses.

**Cost.** Beyond the graph itself: the workload tag has to be *invented*, since
the derivation does not partition (D3, 85.4%); `:compute` procs need
`compute-timeout-ms` raised from 5,000 ms or the p90 test fails as a flow error
(§4.2); a declared fact naming every Var a test redefines is needed before any
two bodies may run concurrently (§4.4); and the abandonment problem above has to
be solved, because there is no `destroyForcibly` for a thread.

**What we give up.** Process isolation, which is what currently converts eleven
known load-sensitive tests from flaky into merely slow. And the owner's own JVM
becomes the thing a bad test wedges.

**Assessment.** Flow's scheduler is not better than a `LinkedBlockingQueue` at
83–91% utilisation; it is the *same* work-stealing shape with a different
vocabulary. Flow earns its place where there is a *topology* — ports,
backpressure, signals, hot-reloadable step Vars, an error-chan. A test run has
one fan-out and one fan-in. The one thing flow would genuinely add is the
`:io`/`:compute` executor split, and §4.3 says we cannot populate it honestly.

### Option C — attack the 73%: the base, `config/apply!`, and the program graph. **What I would do first, alongside A.**

Not a scheduler change at all. Four measured targets, in cost order:

| Target | Measured | Shape of the fix |
|---|---:|---|
| cold base publication | **112,098 ms** (batch-107) vs **1 ms** reused | already cached by `test-digest`; the miss is the cost. A publication that reuses the *development cluster's already-published* `:current-src` base instead of rebuilding under the snapshot digest |
| `config/apply!` | 3,009 ms first, 2,101 ms identical-again, paid **twice** per seeded cluster | an identical apply that reconciles nothing should cost what `record-tx`'s delta writer costs — the same defect shape, already solved once (`runner.clj:2090-2096`) |
| per-worker cold base | **15.6–16.8 s × 3** per gate (D5) | `check` already primes it before the clock starts (`test.clj:741-747`); the worker does not. Move the priming into worker readiness and the first test stops lying about its own duration |
| coordinator program-graph build | **18 s** every gate | `SELECT building the program graph` runs in the coordinator after loading; it is the same manifest the published base already carries |

**Guarantee.** These are pure subtractions with no isolation change and no new
mechanism — AGENTS §2.5's "is this simpler than it was?" answers yes for each.

**Cost.** Unknown per item; each is a separate measured slice.

**What we give up.** Nothing structural. It does not advance the PRD's
facts-are-the-test-system direction, which is why it runs alongside A rather
than instead of it.

---

## 7. Verification boundary

No test was run; no test JVM was launched; no `bin/test` invocation was made.
Exactly one read-only evaluation was issued (`mode: jvm`, cluster `default`,
explicit `(seon.operator/connection "default")` custody), at basis
`:t` 536871833; its form and result are in §2.4 and §4.3. Every other number
comes from the two named gate logs, from source read in this working tree, or
from the dated research notes cited inline — where a number is dated and not
re-measured here, the note that measured it is linked. The `gate-sets` timing in
D4 is a single observation on a live cluster under concurrent load, not a
benchmark. This note is the only file this lane touched.
