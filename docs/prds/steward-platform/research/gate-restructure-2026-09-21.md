---
type: research
status: in progress
created: 2026-09-21
tags: [testing, performance, datahike]
---

# Gate restructuring

The assignment spec was read end to end, together with AGENTS.md §5 and
the data-oriented-clojure, clojure-testing and datahike skills. The current
publication and results-reuse closeouts retain their admission and cold-proof
limitations. Native lane status exposed only this session; the current working
edge records publication landed and SCI/program queued after `e10797ad5`.
Owned runner paths were clean before editing. Foreign dirty paths are excluded.

## Identity correction

The retained manifest contains 2,076 symbol test identities. All 868 complete-log
task names match as symbols, zero as strings, and zero are genuinely absent.
The full-file census reproduced both raw-log SHA-256 values in the spec:
complete 868 BEGIN / 868 END / 868 timed; interrupted 121 / 120 / 68.
Complete serial samples: median 914 ms, p90 4,461 ms, sum 3,286,611 ms.
Interrupted serial samples: median 4,969.5 ms, p90 37,346 ms, sum 1,060,202 ms.
All three pools have zero timed tasks in both logs. These are historical
execution durations, not fixture measurements or a successful durable tally.

Task construction, timeout/exchange/exhaustion results, and confirmation
selection now retain symbols. The positive regression reads the canonical
manifest and constructs tasks from an actual published test Var; one absent
identity remains unresolved. Existing task fixtures use symbols too.
Schema inventory: `resources/seon/schemas/seon.test.runner.edn` already declares
captured results through `:seon.test/sym`; no separate internal task-symbols
schema exists. No schema resource changed in this slice.

## Verification boundary

`tmp/gate-restructure/identity-fast.log`, snapshot `run.iqeY8n`, HEAD
`4510d4ef9`, armed 1,484 contracts (1,479 program-armable), then refused
snapshot admission before executing any tests. The live recording authority
rejects optional `:seon.error/offending` in
`:seon.test.runner/invalid-marker-reason-error`: “A stored error member must
have a storable registered attribute.” The publication closeout identifies
the held `src/seon/schema/internal.cljc` admission seam. No bridge, admission,
or recording change is included. Executed count: zero; unchanged count and
durable tally: unavailable. This is not a green iteration.

The serial precommit JVM load passed (exit 0): `clojure -M -e
"(require 'seon.test.runner) (println :gate-restructure-identity-load)"`.
This verifies namespace loading only. The edit hook reported existing runner
warnings and unrelated Markdown pin errors in the two wave-3 specs; neither
foreign document was edited.

Default remained untouched. Its startup health read failed on a missing
`:seon.error/at` in `seon.problems/problems`; the existing health issue records
that exact observation. Cold process-lifetime integration and platform proof
remain the orchestrator's responsibility.

## Dependency and lifetime grounding

Datahike `reference-code/datahike/src/datahike/versioning.cljc:212` owns
same-store `branch!`: reuse primary roots and copy-on-write secondary indexes,
then publish the branch head and roster. This differs from `fork-database`
at :550, which copies to another store. The ordinary fixture's
`with-branched-database` carries the base projection but acquires branch-local
connections and projection state, releasing connections before deleting the
branch and releasing the base hold.

`src/seon/test/cache.clj:318` already owns checkout creation, called by
`start-worker!`. Its child preparation has the existing declared bound.
`run-task-pool!` owns the existing queue; `worker-exchange!` owns command
deadlines and retirement; `stop-worker!` owns process-tree exit observation.
The lifecycle change will use those owners after selection, reserve one first
task per child, and close each drainer before joining serial work.

## Lifetime slice

Identity checkpoint: `9d40d32b4`. The next slice removes speculative worker
startup from `run-coordinator!` and eager checkout copies from `bin/test`.
Each stage derives `min(configured cap, resolved task count)` delays after
classification. The queue receives only tasks not reserved as a child's first
task. A fast child cannot consume another child's reservation. Each drainer
stops and reaps its child in `finally`; pool results are eagerly collected
before the serial join (the previous `mapcat` was lazy). Serial remains lazy
and serves unresolved work and the existing one leftover wave, then closes.
Startup failures and retired-worker leftovers produce terminal errors carrying
symbol identities. Initialization failures close their partial child.

Decision regressions cover zero demand, serial-only demand, demand below the
cap, and one terminal error per task after startup refusal. The existing
orchestrator integration namespace gains an event-based real-process exit
regression: pool exit must be observed while serial work is still waiting for
its release latch. It uses protocol children and injects bounded task bodies;
it is not evidence of full JVM fixture isolation. Full cold gate/platform
proof and the new child-process regression remain unexecuted in this lane.
No before/after child RSS or creation/exit measurement is claimed yet.

## Resumed preparation slice

Lifetime checkpoint: `cdf517203`. The restored preparation changes carry one
immutable report-options value through worker/task/Var reporting, preserving
explicit profiles, and remove the duplicate config application before the
canonical cluster seed. Checkout preparation retains its shell-declared bound
across lock acquisition and copy commands at the existing checkout owner.
Regressions cover the reserved first task, retirement leftovers, the reporter
profile, branch/schema isolation, exceptional cleanup, and the single config
writer. The process-exit regression now prints PID/start/exit evidence when
the orchestrator runs it; it has not run here.

`morning-fast.log` armed 1,499 contracts but refused admission at
`:seon.call-preparation/ambiguous-call-error`, member
`:seon.call-preparation/candidates`: “A stored error member must have a
storable registered attribute.” `adoption-fast.log` reached the same refusal.
Both executed zero tests; neither supplied a durable unchanged tally. This is
not the store-lock failure and was not retried as one. The held call-preparation
declaration and bridge were not edited. The first overlay inadvertently
included the already-staged `test/seon/test_support.clj` caller conversion;
that foreign two-line diff is excluded from this lane's commits.

The independent real-fixture probe ran on the shared checkout, including its
foreign edits, with 1,490 contracts armed. It is not a clean HEAD-plus-owned-paths
proof. `morning-measure.edn` records run `e2dd79a9c4f7`, program digest
`412b35d58cdb691b7a8072b4f678108f23d660cd35d99f61a556a591c5c94520` and
input digest `726f7c1efd52a466eb4b3cab43a347f2a6c2a331a1c0ab8918e969e68cd04c45`.
Base readiness was 96,064.399 ms, with one population, one complete source
analysis and one canonical SCI base construction. All ten warmed ordinary
fixtures asserted zero of each and exactly one branch/delete lifecycle.

| Warm ordinary fixture phase | First (n=1), ms | Subsequent n | Median, ms | p90, ms | Max, ms |
|---|---:|---:|---:|---:|---:|
| Total | 27.087708 | 9 | 2.639125 | 3.322791 | 3.322791 |
| Setup | 14.593041 | 9 | 2.205459 | 3.040041 | 3.040041 |
| Body | 0.606584 | 9 | 0.037917 | 0.105750 | 0.105750 |
| Cleanup | 11.888083 | 9 | 0.273791 | 0.849875 | 0.849875 |

For the one first sample, median, p90 and max all equal the shown duration.
The recurrence member was normally admitted and durably recorded: **one
executed, zero unchanged, zero failures, one error**. Resolution took
425.912 ms; config application occurred once (682.978 ms). Its failing body
took 776.130 ms, its inclusive fixture 1,651.184 ms, and total task 2,396.651 ms.
These failing spans remain marked incomplete in the raw events and are not
included in successful-duration distributions. The script now retains task
output/results for diagnosis as well as the recorder's tally.

Durations are monotonic wall time. Exclusive observations subtract nested
observations on the same thread; asynchronous base construction is separately
observed and MUST NOT be added to base-readiness elapsed time. Recursive
seed-cluster arities yield two inclusive seed observations but only one config
application. This does not mean two cluster preparations.

`/usr/bin/time -l` measured maximum resident set size **8,786,558,976 bytes**
and peak memory footprint **8,424,725,728 bytes** for this probe. Neither is
heap usage; no heap measurement or worker RSS improvement is claimed.
The historical complete log still has 868 serial timings (median 914 ms,
p90 4,461 ms, max 242,733 ms) and zero pool timings. It contains no isolated
fixture timing. A comparable successful before/after recurrence body and
post-change cold worker census remain unavailable; these numbers do not
establish a whole-gate speedup.

Reproduction scripts alongside this note: `gate-restructure-census-2026-09-21.py`
and `gate-restructure-measure-2026-09-21.clj`. Probe command, after acquiring
the existing test slot, is `clojure -J-Dseon.test.source-root="$PWD"
-J-Dseon.test.git-sha="$(git rev-parse HEAD)" -M:test
docs/prds/steward-platform/research/gate-restructure-measure-2026-09-21.clj
tmp/gate-restructure/measure.edn`. The probe never forces reuse into execution.
The serial owned namespace load passed in `morning-load-and-failure.log`,
including the publication-adoption namespace, and printed the fully expanded
docstring contract with no schema Vars. Cold gate/platform and process proof
remain owed to the orchestrator.

The normally admitted red retry `3d58e4a63df7` retained the complete task output
in `tmp/gate-restructure/recurrence-diagnosis.edn`. Its one error is
`:malli.core/invalid-schema` at `seon.error/stored-observation`,
`src/seon/error.clj:287`, calling `malli.core/properties`. That path has foreign
staged edits and was not changed. This retry again recorded one executed and
zero unchanged. Its ten ordinary probes again met the structural budget;
first total was 25.666709 ms, subsequent n=9 median 2.861042 ms, p90/max
3.357791 ms. This is a second diagnosis run on a changed shared program digest,
not a matched before/after comparison. The final owned namespace load passed
after all source and regression edits (`final-owned-load.log`).

The final lifetime namespace load passed (exit 0), including both runner test
namespaces and the integration namespace. `bash -n bin/test` and the owned
diff whitespace check passed. Fast execution is still behind the unchanged
recording-admission refusal above; no unarmed test invocation substituted for
it. Existing checkout child preparation bounds remain in the cache owner.
