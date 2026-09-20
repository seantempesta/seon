---
type: plan
status: ready for coordinated implementation
created: 2026-09-21
tags: [plan, testing, performance, datahike]
---

# Cold gate cost — measured diagnosis and launch text

The largest proven defect is **868 tasks incorrectly routed to serial while
three fully primed pool JVMs execute nothing**. The canonical database fixture
already shares one base per worker and forks it. Neither supplied log measures
33 seconds of database reconstruction per test. Fix task identity and worker
lifetime first; remove verified duplicate preparation second. Do not build
another fixture cache to solve a mechanism that already exists.

This is the read-only design lane's sole written artifact. No JVM, gate,
worktree, live-cluster operation, or other lane session was launched or operated.
Source references were inspected in the shared tree, initially at
`2039f18764ef74645d5453c9065f50115afe7753`, subsequently at
`2dd9debe7598370bd4c3514fc2cd2fbe34dc7333`; dirty source belongs to other lanes.
Function names below locate the evidence when concurrent edits move line numbers.

## Evidence read and measurement boundary

Read end to end: [publication launch style](publication-dissolution-spec-2026-09-20.md),
[results reuse](../research/results-reuse-everywhere-2026-09-20.md),
[publication research](../research/publication-dissolution-2026-09-20.md)
through its population/lineage landing, `src/seon/test/runner.clj`,
`src/seon/test.clj`, `test/seon/test_support.clj`, `bin/test`, and
`bin/_test-slot`. Read AGENTS.md §5 and both requested
[working-edge](unsettled.md) load-audit blocks, at approximately lines 4748
and 5029, plus the current lane-status blocks. Applied the
data-oriented-clojure, clojure-testing and datahike skills. Both logs were
processed end to end, including incomplete exchanges and their terminal status;
the reproducible full-file timing census is below.

| Raw evidence | Lines / bytes | SHA-256 |
|---|---:|---|
| `tmp/orchestrator/gate-step1-2026-09-21.log` | 10,891 / 1,094,591 | `211355bdae39f9c1e3a1fd5c7ed4643cf762d1cd331764ac6d30219dc24b9aa2` |
| `tmp/orchestrator/gate-1a-three-suites-2026-09-20.log` | 1,091 / 151,127 | `7cdd2abfdc6609a8a7186d81742743708390e92b8cf903d74e36ca01934a6f39` |

The complete log records `git=cab89b9e…`, 43 namespaces, and **exit 1**.
It has 868 BEGIN and 868 numeric END events, not 868 proven green tests.
There are 162 attributed-output blocks, 151 FAIL and 160 ERROR headings;
these are log headings, not a durable result tally. Final reporting encounters
`ClassCastException` comparing String and Symbol. Do not claim successful
recording from this run. The stopped log records `git=679782648…`, three
namespaces, 124 selected tasks, 121 BEGIN events and 120 END events; only
68 END events have elapsed values, 52 lack them, and the last BEGIN is
unmatched. Missing durations are unknown, never zero. Its final gate duration
and durable tally are unavailable. The embedded timestamps are September 20;
the filename's date does not change them.

## Timing distribution

Task durations are worker-reported `elapsed-ms`, not BEGIN-to-END wall spans.
Median is the middle value (mean of two middles); p90 is nearest rank.

| Log / worker | Completed timed tasks | Sum ms | Median ms | p90 ms | Readiness fixture preparation ms |
|---|---:|---:|---:|---:|---:|
| Complete / pool-1 | 0 | 0 | unavailable | unavailable | 16,023 |
| Complete / pool-2 | 0 | 0 | unavailable | unavailable | 16,232 |
| Complete / pool-3 | 0 | 0 | unavailable | unavailable | 16,011 |
| Complete / serial | 868 | 3,286,611 | 914 | 4,461 | 13,557 |
| Stopped / pool-1 | 0 | 0 | unavailable | unavailable | 96,320 |
| Stopped / pool-2 | 0 | 0 | unavailable | unavailable | 91,611 |
| Stopped / pool-3 | 0 | 0 | unavailable | unavailable | 93,544 |
| Stopped / serial | 68 timed of 120 ENDs | 1,060,202 | 4,969.5 | 37,346 | 89,888 |

Complete run: **100% of timed tasks are serial**. The serial span from first
BEGIN (`06:31:38.818022Z`) to last END (`07:29:13.548787Z`) is
**3,454.731 s, 97.78%** of the 3,533 s coordinator phase. Summed worker task
durations occupy **93.03%**; the 168.120 s difference within the span includes
exchange and between-task work and is not a measured fixture subtotal.
Counting serial startup from the bulk announcement (`06:31:07.153478Z`)
through last END gives **3,486.395 s, 98.68%**. These denominators and
boundaries differ deliberately. Both runs report platform **0**; neither
proves platform behavior.

| Complete-run task elapsed bucket | Tasks | Sum ms |
|---|---:|---:|
| Below 1 s | 476 | 330,417 |
| 1–5 s | 316 | 632,472 |
| 5–10 s | 19 | 125,030 |
| At least 10 s | 57 | 2,198,692 |

Thus 57/868 tasks (6.57%) consume **66.90%** of timed execution. The largest
are `seon.turn-work-test/situation-totality-property` (242,733 ms),
`seon.cluster.boot-test/development-adoption-targets-one-of-two-cohosted-clusters`
(192,185), `incremental-source-refresh-preserves-agreement-across-real-edits`
(190,093), and `explicit-refork-destroys-the-old-branch-and-forks-current-source`
(114,088). Their total elapsed includes intentional boot/publication/property
work; the log cannot assign that time wholesale to ordinary fixtures.

`seon.error-test/recurrence-counting-does-not-require-a-notification-threshold`
took **32,945 ms in the stopped log** (line 910), but **5,353 ms in the complete
log** (line 2540). The latter is 83.75% lower under different code and load;
this is not a controlled optimization result. The ~23:10 load audit's
“cold fixture per test” is an attribution the evidence does not establish.

## Why the pool does nothing

`test-tasks` (`src/seon/test/runner.clj:986`) stringifies `var-symbol` into
`::task-symbols`. `indexed-test-symbols` (`:1020`) keeps the manifest's
`:seon.test/sym` values unchanged; `split-resolved-tasks` (`:1027`) tests
membership directly. Those values are symbols. This code shape also exists
at the complete log's `cab89b9e` revision.

The retained publication is stronger evidence than a current schema claim:
`target/test-published-bases/e8cb1a8c76cfe6b393cf4b1a167ff815b1dbd56ef90d15c2373fa7fa53635411/base/manifest.edn`
contains **2,076 test identities, all symbols**. A native Babashka EDN read
matched the complete log's 868 task names: **0/868 as strings; 868/868 as
symbols; zero missing identities**. The log's “868 task(s) lack complete
:seon.test rows” is therefore false for the actual membership condition.
This is a representation defect, not a reason to loosen test admission.

Long metadata sorts tasks and supplies execution allowances. It does not
classify tasks as serial. A namespace's `:once` fixtures or `test-ns-hook`
make an atomic task; that also does not make it serial. Genuine unindexed
tasks and the existing bounded leftover wave use the serial worker.

`run-coordinator!` (`runner.clj:4580`) starts the configured pool before
selection completes and joins all readiness futures. `worker-command-loop!`
(`:2021`) acquires the packaged projection, canonical base and arm before
READY. `run-task-pool!` (`:4088`) starts pool drainers and serial work
concurrently. An empty queue ends a drainer but does **not** stop its JVM.
The coordinator's outer cleanup retains all workers until the whole request
finishes. Thus “serial first” is a proposed ordering change, not the existing
topology. Even after identity repair, a pool that finishes before genuine
serial work still retains its heap.

Complete-run unused pool priming is **48,266 aggregate worker-ms**; stopped-run
unused priming is **281,475 aggregate worker-ms**. Parallel durations cannot
be subtracted directly from gate wall time. The load audit measured three
idle workers at approximately **3.6 GB RSS each: 10.8 GB avoidable resident
memory**, alongside the 11 GB serial worker and 164 MB coordinator. RSS is
not committed heap, and this is the stopped-gate audit, not a measurement of
the complete log. Other applications also contributed load; they do not
explain why the gate retained unused processes.

`bin/_test-slot:19` limits invocations to two, not children inside one gate.
`cache/worker-count` (`src/seon/test/cache.clj:19`) caps the ordinary pool at
three, with explicit overrides. `bin/test:984` also prepares pool checkouts
eagerly (3 s here). Lower process priority and heap percentages do not remove
the unnecessary lifetime.

### Three topology options, priced

Estimates are engineering effort, not measured delivery promises. Each uses
the existing queue, worker protocol, checkout owner and exit handling.

| Option | Guarantee | Cost and what we give up |
|---|---|---|
| **1. Lazy, demand-sized existing workers; retire each when its queue drains — recommended** | Start after admitted executable tasks are known; zero pool JVMs for zero pool tasks; at most `min(existing cap, runnable task count)`; stop on exhaustion before waiting for serial or recording. Serial starts only on actual unresolved/leftover demand. | About 1 day including real process regressions. Preserves useful pool/serial overlap. Gives up speculative startup overlap with selection and indefinite warm retention; a later nonempty tier may pay another boot. Mixed active workloads can still reach the existing peak, but idle workers do not remain resident. |
| 2. Serial first, then a demand-sized pool, releasing each stage | Serial and pool workers never coexist; same zero-demand and drained-worker guarantees. Platform still completes before bulk. | About 0.5–1 day. Gives up overlap, adding up to `min(serial duration, pool critical path)` per mixed tier. With this log's zero resolved tasks there is no overlap to lose, but that is precisely the classification bug being fixed. |
| 3. Keep workers across tiers only while immediately assignable demand exists; shrink at every transition | Avoids some repeated boots; preserves platform barrier and reaps surplus workers on completion events. | About 1.5–2 days. More partial-startup, transfer and cleanup cases. Gives up the simple stage lifetime; no benefit demonstrated by these two platform-empty logs. No idle grace timer is allowed. |

Choose option 1 in the launch below. “Never holds an idle worker” means no
worker without executable demand is started or retained after exhaustion;
bounded startup/exchange/exit are legitimate active phases. It does not mean
zero transient time between a completion event and process exit.

## What the fixture already shares; what is still repeated

Dependency ledger: checked-out Datahike gitlink
`e11845bac78e1241bca0766ddc07d978bd63d74a`,
`reference-code/datahike/src/datahike/versioning.cljc:212` (`branch!`) and
`:550` (`fork-database`). `branch!` acquires the roster permit, reads the
selected stored database, shares primary roots, forks secondary indices using
their copy-on-write support, and writes the new head and roster. It does not
replay the population. This is bounded metadata/index-branch work, not a claim
that every secondary implementation or roster size has constant cost.
`fork-database` copies store keys to a different store; do not substitute that
more expensive operation for the fixture's branch.

First-party ownership:

- `test/seon/test_support.clj:388`, `create-base`: once per immutable
  publication/worker, clone and reidentify the published store, construct its
  memory frontend, derive the program projection and SCI base. The private
  backend clone is required: connect-time migration can mutate a backend even
  with frontend-only writes. Do not remove it or connect directly to the
  shared publication.
- `:618`, `base-state` / `database-base`: existing retained base with hold and
  retirement semantics. `:990`, `with-branched-database`: hold that base,
  branch its sealed head, carry its projection, create branch-specific state
  and connections, run the body, then release/delete the branch.
- `:956`, `reconnect-with-projection`: the ordinary branch path supplies the
  shared projection; only the no-projection arity derives one. Mutable
  projection state, writer, connection and agent SCI fork remain isolated.
- `:1035`, `with-database`: the ordinary path already forks. Explicit
  `database-id`/`fresh-store?` takes the separate physical-store fixture and
  repopulates by design. Preserve these observed fixture requirements.

The current logs measure base priming at READY **once per worker**, before
the first task: 13,557 ms on the complete serial worker. Amortized over 868
tasks this is **15.62 ms/task**, not a measured per-task charge. All four
workers' priming totals 61,823 ms, or 71.22 ms/task amortized, including the
three wasted bases. The stopped serial base costs 89,888 ms once; dividing
it across 68 timed tasks would still not measure each fixture.

Historical direct measurements distinguish the missing terms:
[turn bookkeeping](../../context-generation/research/turn-bookkeeping-cost-2026-09-16.md)
at line 94 records warm `with-database` **1 ms**, branch plus `seed-cluster!`
**2,354 / 2,341 ms**, `config/apply!` **3,009 ms initially / 2,101 ms again**,
and a helper doing two applies plus a launcher **4,851–5,050 ms**.
These are dated measurements, not present-HEAD bounds. The earlier
[preparation study](../research/test-preparation-costs-2026-09-16.md) moved
first-task base costs of 16–17 s to readiness; it explicitly did not prove a
total wall-time reduction.

There is a concrete duplicate in the named recurrence test today:
`test/seon/error_test.clj:872`, `with-db`, calls `config/apply!` then
`test-support/seed-cluster!`; `test_support.clj:1103` calls `apply-config!`
itself. That rebuilds/reconciles the same default cluster configuration twice
per helper invocation. The recurrence body (`error_test.clj:923`) then makes
two real occurrence transactions and two reads. Remove the duplicate at the
helper, retaining the canonical seed and its refusal checks. Do not preseed
the entire population with an error-test cluster or share mutable connections.

Another explicit immutable input is `runner/report-options` (`:123`): it
derives config defaults and the render profile in `run-task!` (`:1617`) and
again in `run-vars!` (`:622`). Carry one acquired reporter value through a
worker request, honoring explicitly supplied profiles. The outer call occurs
**before** the task timer, so even task elapsed does not cover all per-task
preparation. Do not introduce a global memoization registry.

`seon.test/resolve-test` (`src/seon/test.clj:1475`) also recomputes wanted and
acquired program digests for each identity. That is a measurement candidate,
not authorization to weaken provenance or redesign admission. It belongs to
the SCI/test owner if profiles show it dominates after the scoped changes.

## Honest cost model after results reuse

Let E be the executable members returned by the existing selector, excluding
`:seon.test/unchanged`. For a normal changed request with established green
evidence, E is uncovered changed reach. A first request, prior failures,
missing evidence, or changed external inputs can widen E. Named/platform
eligibility is not permission to execute already-covered members again.

The reused confidence is the recorded **tested basis, program digest and
input digest**. Native reuse can preserve an older tested basis/program when
reachable contents and external inputs still match. Absence of a result,
incomplete execution or failure is not green. The results-reuse research's
verified fast request goes **4 executed → 0 executed**; every request still
gets its own run event. Preserve that selector and recorder verbatim in intent.

Do not label this old cold log “changed-reach only.” It was an explicit
43-namespace run. In the inspected cold `run-coordinator!`, explicit selects
all Vars, bare `changed` refuses until custody is supplied, and
`worker-request-admission` is defined separately from this execution path.
The selector migration/provenance work is a named upstream boundary, not
something this restructuring lane should silently complete. Recheck after
the owning lanes land. No claim here that cold and fast reuse integration
are already identical.

The useful model is:

```text
gate wall = snapshot/slot/dependencies + publication + selection/recording
          + worker boot and ONCE-per-worker canonical priming on critical path
          + scheduled sum over E of (warm fixture + resolution + body + teardown)
          + exchange/cleanup overhead
```

“Fixture priming × executed tests + publication + worker boot” is only valid
if “fixture” means the measured repeated warm setup, and test bodies and
resolution are retained. Canonical priming multiplies **workers**, not tests.
Parallel sums are work; wall time follows the schedule's critical path.

The complete log's phase seconds are 11 snapshot, 0 slot, 2 dependencies,
3 worker checkouts, **88 publication and 3,533 coordinator/tests: 3,637 total**
reported seconds (rounded phases, not an independently timed end-to-end span).
Publication is **2.42%**; coordinator/tests **97.14%**. Eliminating all base
publication would save at most those 88 reported seconds on this run.
The remaining bottleneck is serial task execution; **the fixture share within
it is unmeasured**. Current evidence does not establish fixture dominance.

Publication is improving separately: the publication research records
97.603 s complete and 93.153 s changed publication in an earlier probe, still
380 analyzed inputs; subsequent `3ac00fb8e`/`179f6c4bc` land lineage
reconciliation and reuse survival. The later 149.134 s publication observation
includes startup; it is not a one-file incremental benchmark. Duplicate
publisher deletion and final measurements remained open at this inspection.
Use 88 s as this log's cost, not as the finished incremental publisher's price,
and do not promise the publication spec's single-digit target is already met.

## Verbatim launchable implementation assignment

```text
Lane: gate-restructure; model gpt-6-astra, effort low. Work in
/Users/sean/src/seon, branch steward-platform. Execute directly; no delegation.

Read docs/prds/steward-platform/plan/gate-restructure-spec-2026-09-21.md
end to end, AGENTS.md §5, the data-oriented-clojure, clojure-testing and
datahike skills, the two raw logs named in the spec, and the current status
of the publication/results-reuse research notes before editing.

Objective: remove unused worker lifetimes and redundant per-test preparation
while preserving the exact admitted members, arming, platform barrier,
failure behavior, execution bounds and durable result recording.

Coordination BEFORE launch: the orchestrator checks native lane status and
git status, specifically publication-dissolution and kind-sweep-sci-program.
The dated working edge says sci-program landed 603d2587c; verify its current
assignment and dirty paths rather than assuming those files are free.
At design time runner.clj, cache.clj, bin/test, test_runner_test.clj,
test_runner_integration_test.clj and test_failure_facts_test.clj were dirty.
Publication still owns duplicate-publisher/launcher work. Wait to launch on
overlapping owned paths until their owners land; a namespace prefix is not
a permanent lock. Do not resume, message, or operate another lane yourself.

Owned after release: src/seon/test/runner.clj (task representation and worker
lifetime/report preparation), src/seon/test/cache.clj and bin/test only for
existing checkout demand/lifetime; test/seon/test_support.clj only for measured
fixture preparation/observation; test/seon/error_test.clj for duplicate config
application; their existing runner/preparation/support regressions; and
docs/prds/steward-platform/research/gate-restructure-2026-09-21.md with the
reproducible measurement script committed alongside it. The existing runner
schema declarations may change with their consumers in one publication if
internal task symbols or carried report options require it. Inventory exact
schema paths first. src/seon/test.clj selection/admission/recording and SCI
program acquisition are read-only for this assignment. You are not alone in
the tree; preserve others' edits and do not revert shared files.

Commit 1: truthful classification and demand-owned workers (option 1).
1. Reproduce the retained manifest's 0 string / 868 symbol matches. Keep test
   identities as symbols through task construction, membership and results;
   stringify only presentation/protocol fields that genuinely require text.
   Convert every affected consumer/contract coherently. Do not turn genuinely
   missing rows into resolved rows or invent a serial test roster.
2. Derive worker demand AFTER existing selection, from executable tasks only.
   No pool starts for an empty or serial-only stage. Bound pool size by the
   existing configured cap and current runnable task count. Assign a concrete
   first task when launching each child, so a faster-starting child cannot
   drain all demand while another is still starting. Preserve atomic
   namespace tasks, :once/:each behavior, long allowances and platform-first
   fail-fast. Do not launch bulk workers before a red platform decision.
3. Use the existing worker queue, start-worker!, worker-exchange!, stop-worker!
   and checkout owner. A worker that drains its queue exits and is reaped
   before the coordinator waits for remaining serial work or recording.
   Serial starts only for genuine unresolved tasks or the existing bounded
   leftover wave. Close on startup failure and exceptions as well as success.
   Preserve typed exhaustion and process identity cleanup; no silent task loss.
   Keep shutdown cleanup safe for already-stopped children. Checkouts follow
   demand at their existing owner; retain the existing preparation bound.

Commit 2: eliminate proven duplicate setup; measure the remaining fixture.
1. On ONE foreground test JVM, acquire the canonical base once, then measure
   repeated ordinary with-database calls and the named recurrence test. Emit
   monotonic durations separately for base readiness, branch/connect/state,
   config/cluster seeding, test resolution, body and cleanup. Observe the real
   functions; do not mock their returned database/projection. Distinguish
   inclusive from exclusive timings so nested work is not counted twice.
2. Remove the duplicate config/apply! before seed-cluster! in error-test's
   helper; the canonical helper remains the one writer. Carry one immutable
   report-options/profile value per worker request into task and Var reporting.
   Keep explicit caller profiles authoritative. No global registry or second
   database base. Confirm the ordinary fixture carries the existing immutable
   projection and forks the sealed base; do not replace branch-local state,
   connection, writer or SCI private state with shared mutable objects.
3. Report first and subsequent fixture measurements (count, median, p90, max),
   population/analysis/base-context construction counts, and recurrence-test
   setup/body split before and after on the same admitted snapshot. Include
   actual executed and unchanged counts. Reused results are not timing samples;
   use the bounded real-fixture probe for repeated measurements, without
   deleting evidence or bypassing admission to force a test rerun.
4. Bound every measured operation with the existing declared event/exchange
   limits; timeout names the phase and becomes failure, never a missing sample.
   The structural work budget for a warmed ordinary fixture is zero population
   replays, zero complete source analyses, zero new canonical SCI bases, and
   one branch lifecycle. Assert those counts. Do not invent a millisecond
   ceiling from the old 1 ms observation or tune a larger timeout around a red.
   If resolution or real test body dominates, report that fact and its owning
   seam; do not relabel it fixture rebuilding or widen into admission changes.

Regressions, using canonical fixtures and real SCI/Datahike with contracts armed:
- Positive published symbol identities classify as resolved; one truly absent
  identity stays unresolved. Exercise the canonical manifest, not only a
  hand-written map that repeats the old string representation.
- Zero demand launches zero workers. Serial-only demand starts no pool.
  Fewer tasks than the cap creates no surplus. In a mixed run, a drained pool
  exits while a bounded serial body is still awaiting its release event.
  Observe actual process exit, not an empty future list or an RSS threshold.
  Cover retirement/startup failure and prove each admitted task gets exactly
  one terminal outcome through the existing rules. No sleep-based observation.
- After readiness, successive ordinary fixture branches perform no population
  or canonical SCI rebuild; writes and schema additions on one branch are
  absent on the next. Throw during the body and verify branch/base holds clean
  up. The named error fixture applies cluster config once and retains its
  recurrence/message assertions. Explicit fresh-store tests retain isolation.
- A nonempty platform stage runs before bulk; platform red starts no bulk
  child, and declared long bounds still apply. No change to platform membership,
  reuse confidence, tally or recorder. Pure decision tests are useful but do
  not substitute for the real process-lifetime integration proof.

Validation: one JVM at a time, no worktrees, no default restart or reset.
Use bin/test-fast --paths <exact owned files> -- seon.test-preparation-test
seon.test-runner-test seon.error-test plus the affected existing fixture suite.
No bin/test cold gate, --all, --full or nested cold child gate in this lane.
The real child-gate integration regressions belong in the existing orchestrator
integration namespace; the orchestrator runs them once after review, together
with bin/test --paths <landed files> -- <affected namespaces> and
bin/test --platform. Do not claim a fast iteration proves process isolation.
Prove owned namespaces load before each coherent path-limited commit; keep
that JVM serial with tests. If foreign changes prevent a load or overlay,
record the exact path/envelope and continue independent source/spec work;
never repair another lane or create a worktree as a workaround.

No tuned idle timers, heap/slot/worker-cap increases, new pool/scheduler,
cross-JVM fixture service, second fixture cache, hand-rostered schema,
unarmed test path, disabled fixtures, fewer property trials, forced repeated
test execution, or admission/recording changes. Keep platform policy intact.
Estimate: 1.5–2 engineering days total after held paths release, including
fixture attribution and real lifecycle regressions; orchestrator cold proof
is additional and run once. Numbers are estimates, not measured speedups.
Landing note: exact commits/files, before/after counts and timings, child
process creation/exit evidence, observed peak RSS separately from heap, fast
tally and unchanged members, held/foreign boundaries and cold proof owed.
If a larger cross-owner design is needed, stop before production edits and
bring exactly three priced options. Otherwise commit coherent slices and stop.
```

## Reproduce the census without a JVM

Run this Python from the repository root. It reads every line, checks task
coverage, and does not convert missing durations to zero. These are tool-side
log patterns, not proposed production regexes. The retained artifact query
below uses native Babashka, not Clojure's JVM launcher.

```python
from pathlib import Path
from collections import Counter
import hashlib, math, re, statistics

for name in ("gate-step1-2026-09-21.log", "gate-1a-three-suites-2026-09-20.log"):
    p = Path("tmp/orchestrator") / name
    raw = p.read_bytes()
    lines = raw.decode().splitlines()
    begins, ends, samples = [], [], []
    for line in lines:
        if " BEGIN worker=" in line:
            begins.append(line)
        if " END worker=" in line:
            ends.append(line)
            m = re.search(r"END worker=(\S+) elapsed-ms=(\d+) task=(\S+)", line)
            if m:
                samples.append((m[1], m[3], int(m[2])))
    print(name, len(lines), len(raw), hashlib.sha256(raw).hexdigest())
    print("BEGIN/END/timed", len(begins), len(ends), len(samples))
    for worker in ("pool-1", "pool-2", "pool-3", "serial"):
        xs = sorted(ms for w, task, ms in samples if w == worker)
        print(worker, len(xs), sum(xs),
              statistics.median(xs) if xs else None,
              xs[math.ceil(.9 * len(xs)) - 1] if xs else None)
    print("task coverage", Counter(re.search(r"task=(\S+)", x)[1] for x in begins)
          == Counter(re.search(r"task=(\S+)", x)[1] for x in ends))
    for line in lines:
        if " PHASE " in line or "WORKER READY" in line:
            print(line)
    print("slowest", sorted(samples, key=lambda row: row[2], reverse=True)[:8])
```

```clojure
;; Evaluate with bb -e or its stdin reader; no Seon namespace is loaded.
(require '[clojure.edn :as edn])
(let [base "target/test-published-bases/e8cb1a8c76cfe6b393cf4b1a167ff815b1dbd56ef90d15c2373fa7fa53635411/base/manifest.edn"
      manifest (edn/read-string (slurp base))
      indexed (set (keep :seon.test/sym
                         (mapcat :seon.fn.file/rows
                                 (:seon.fn.manifest/artifacts manifest))))
      tasks (map second
                 (re-seq #"BEGIN worker=serial task=(\S+)"
                         (slurp "tmp/orchestrator/gate-step1-2026-09-21.log")))]
  (prn {:indexed (count indexed)
        :types (frequencies (map type indexed))
        :tasks (count tasks)
        :string-matches (count (filter indexed tasks))
        :symbol-matches (count (filter indexed (map symbol tasks)))
        :missing (remove indexed (map symbol tasks))}))
;; {:indexed 2076, :types {clojure.lang.Symbol 2076}, :tasks 868,
;;  :string-matches 0, :symbol-matches 868, :missing ()}
```

## Landing boundary

This document corrects the two unverified fixture attributions in the working
edge without editing that foreign authority. The sole-document instruction
also keeps the newly measured identity/lifetime findings here for the owning
implementation lane, rather than adding separate issue files. Existing reuse,
publication and failure-fact work remains with its owners. The two scoped wins
are executable demand/lifetime correctness and removal of demonstrated
duplicate preparation; only the first can presently be ranked as a dominant
whole-gate defect. Current per-test fixture cost and final incremental
publication speed remain explicitly unmeasured. No runtime or test success is
claimed by this source-and-log diagnosis.

Verification: the document's Python census reproduces both tables and complete
versus incomplete task coverage; its Babashka query reproduces the retained
manifest matches. No JVM tests were run. The edit hook reports foreign
Markdown errors in `wave-3a-task-family-spec-2026-09-21.md` and
`wave-3bc-render-pairs-and-template-proofs-spec-2026-09-21.md`: both cite the
old Datahike pin `fcbd8862800e638dc0f8f5521111f999279cbcd2` rather than current
`e11845bac78e1241bca0766ddc07d978bd63d74a`. Those documents are outside this
lane's sole-file authority; repository-wide Markdown success is not claimed.
