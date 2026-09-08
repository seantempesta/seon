---
type: research
status: active
date: 2026-09-08
tags: [research, test, runner, performance]
---

# Runner base cache

## Grounding

Read `AGENTS.md`, the agent-record-and-turn-loop PRD (including §10),
`bin/test`, `src/seon/test/runner.clj`, the runner-paths landing note,
and the concurrent Maven classpath issue end to end. Applied the
data-oriented Clojure, REPL, and testing skills.

Dependency ledger and initial observations:

- `dev_cache.clj:186–221`: the project digest includes canonical filenames.
  A different isolated checkout directory therefore changes the digest even
  with identical first-party bytes. `ensure-cache` checks only the selected
  cache before rebuilding (`dev_cache.clj:440`).
- `bin/test:365–408` prepares one HEAD-plus-overlay checkout;
  `bin/test:488–499` clones its worker views with macOS `cp -cR`.
- `bin/test:604` starts publication anew for every invocation.
  `src/seon/test/runner.clj:2721` delegates `--prepare-base` to the ordinary
  `seon.cluster/refresh-source!` owner.
- Workers already receive `-Scp` from the coordinator's Java classpath
  (`src/seon/test/runner.clj:1879`). Preparation and coordinator launches
  still resolve classpaths independently.
- The coordinator builds another manifest (`runner.clj:2595`); the
  canonical fixture builds its own manifest and populated memory database
  (`test/seon/test_support.clj:155–162`, `:184–231`). Publication caching
  alone does not remove that repeated work.
- File-store fixtures clone the base and reidentify the private store
  (`test/seon/test_support.clj:51–119`). Sharing that base path does not
  itself make workers branch from it. Datahike `branch!` changes the store's
  branch roster (`reference-code/datahike/src/datahike/versioning.cljc:212`);
  its cross-store `fork-database` copies Konserve keys (`:550`). The
  existing Seon file-store owner holds an exclusive lifetime lock
  (`src/seon/cluster/store.clj:1–13`).

## Initial run

Command:

```sh
bin/test --paths bin/test src/seon/test/runner.clj dev_cache.clj test/seon/test_runner_test.clj -- seon.repl-test
```

Snapshot HEAD: `f3bd1d58c1fb48dffbf4311db395c7b44594bc28`; no snapshot
differences. Root: `tmp/test-runs/run.vKbHyB`; started
`2026-09-08T19:36:14Z`. Output: `tmp/runner-base-cache-before.log`.
The dependency cache rebuilt 364 namespaces, selecting
`bc038f64ef6d4187b47a86851778bdad8ae14fe7eeb9193c8ac939bf1ad8bf7d`.

Other gates were active. This is a correctness preflight, **not** the
required uncontended before measurement. No before/after speedup is claimed.

Default JVM MCP returned `2` for `(+ 1 1)` in 1 ms. `bin/seon status`
completed after waiting for another lane's adoption lock and reported
default pid 14049 alive. Runtime-status answered; the render proc's ping
was unknown. This lane did not mutate the cluster or another lane's process.

## Implementation checkpoints

The initial stop at an issue-sweep rename was incorrect. The owner restarted
this lane; issue renames are preserved and path-isolated gates continue.

- `f7aeaffbf`: launcher compatibility with snapshots whose cache producer
  predates the classpath fields; phase timing and cached-input launch path.
- `45e5c6c56`: location-independent project bytes, retained dependency-cache
  lookup, test classpath admission under the existing lock, immutable
  snapshot publication, saved publication manifest, and canonical fixture
  branches through a Konserve memory frontend.
- `1893da15d`: corrected the process dependency's vector/options argument order.
- `79326a2d2`, `5f0170172`, `e0a7965cd`: real base reuse regression,
  standalone fixture support, nested gate isolation, retention regression,
  and separate cache lock-wait/work timings.
- `9a8189cbc`: dependency namespaces hash source bytes and names, excluding
  source URL locations too.

The producer computes the test digest from the snapshot's first-party gate
inputs and dependency digest. Relative input names retain their meaning;
absolute checkout locations are excluded. The test classpath keeps
first-party roots relative and external dependency roots canonical. All test
JVM launches receive that admitted classpath through `-Scp`.

`src/seon/test/cache.clj` holds `target/dev-dependency-cache.lock` while it
admits `target/test-published-bases/DIGEST/{checkout,base,ready.edn}`.
Publication runs with the cached checkout as its working directory; the
runner verifies that `seon/fn.clj` resolves from that directory before
calling the ordinary publication owner. The saved manifest is the very
manifest publication produced, not a second analysis. Coordinator selection
and canonical fixtures read that manifest.

The immutable base is a Konserve file backend. Ordinary database fixtures
use `:tiered` with a memory frontend, `:frontend-only` writes and
`:frontend-first` reads. Datahike branch heads and transactions stay in that
frontend. Dependencies: `reference-code/konserve/src/konserve/tiered.cljc`,
`reference-code/datahike/src/datahike/versioning.cljc`; first-party fixture
owner: `test/seon/test_support.clj`. Explicit file-store boot fixtures retain
their existing filesystem clones because they exercise physical store and
process semantics. Worker checkout clones remain: measured together they
cost only 1–2 seconds on this host.

Retention excludes live launcher references identified by PID plus start
instant. Under the same lock it retains the three newest inactive bases
and removes inactive bases older than 24 hours. A regression plants a
symlinked sentinel in an evicted directory and verifies the target survives.
Missing store or manifest prevents cache admission/reuse.

The cache commit used a temporary index and a path-limited working view to
exclude neighbouring arming/instrumentation edits in the two shared owner
files. Their shared-tree bytes were preserved. No protected cluster, turn,
render, my, or blob path was changed by this lane.

## Measurements in progress

Selection throughout: `seon.repl-test`, complete namespace. Captured source
HEAD `79326a2d27a7eb2e9d0d24a1d0f3aae73d210775`; source digest
`b18fbef09b9fbdc653a457d07e94d87e37e7ab76552dcdaff7e56e974dd02b11`.
The second invocation ran from detached worktree
`tmp/runner-base-cache-wt`, sharing `reference-code` and `target`, and chose
exactly the same digest and dependency classes.

| Phase | Cold | Warm, contended |
|---|---:|---:|
| Snapshot | 3 s | 3 s |
| Dependency cache and classpath | 46 s, rebuilt | 8 s, reused |
| All worker checkout clones | 1 s | 1 s |
| Published base | 85 s, published | 103 s, reused after lock wait |
| Coordinator and tests | 20 s | 27 s |
| Tally | 15 tests, 50 assertions, 0 failures, 0 errors | same |

Evidence: `tmp/runner-base-cache-cold-4.log` and
`tmp/runner-base-cache-warm.log`. Both successful run roots were removed by
the launcher. Other lanes and nested launcher regressions were gating;
`bin/codex-agent status` reported eight active lanes around 20:13 UTC.
The warm result proves reuse across checkout locations, but does **not**
establish the under-60-second end-to-end target under that lock contention.
The new timing separates lock wait from work for subsequent measurements.

Earlier implementation preflights exposed two cache launcher defects:
Babashka cannot close the concrete Java FileLock object (closing its owning
channel releases it), and the process library takes the command vector
before its options map. Both were corrected. Two other preflights ended
before a dependency result or test tally; no performance result is inferred.

The initial fast runner run was 39 tests / 232 assertions / 7 failures /
0 errors. Two assertions concerned a launcher fixture which had not created
the newly required store and manifest; one required an isolated-run property
without using the standalone canonical fixture; four concerned a nested
real gate exceeding its bound while competing for the shared cache lock.
Fixture creation and standalone support are corrected; nested real gates
now use `--paths bin/test`. The isolated runner regression gate is pending.

## Live evidence

Default MCP JVM answered `(+ 1 1)` in 1 ms. A read-through Konserve tiered
connection to a real published current-src returned 4,410 program functions
in 8,258 ms, then released and deleted only its memory frontend. This proves
the dependency mechanism, not completed development adoption.

Manual `bin/seon init --dev default --changed ...` reached complete analysis
but refused because source changed during that analysis. Publication will be
retried; no convergence is claimed yet. The before baseline, uncontended
warm timing, regression/platform tallies and final cleanup remain pending.


## Dependency-only identity correction (14:38 owner priority)

`f2e3bcb34` removes first-party inputs from dependency class identity.
`dependency-configuration-digest` hashes `deps.edn` bytes, Git's staged
`reference-code` gitlinks, and JDK/runtime architecture properties. Dependency
namespace source bytes remain validated separately; checkout and source URL
locations are excluded. The snapshot/base digest still includes first-party
bytes, so source edits invalidate publication without recompiling dependencies.
The platform regression `dependency-configuration-excludes-first-party-source`
changes a source file and then `deps.edn`, asserting the respective unchanged
and changed dependency identities.

A frozen `f2e3bcb34` checkout primed dependency digest
`be25ec7be762a5aa35f684128ca7d48bc907fc7c3d9fe6c54f16192477057646`:
364 namespaces, lock wait 1 ms, held 34,578 ms. Appending a comment to
`src/seon/test/cache.clj`, then running
`bin/test --paths src/seon/test/cache.clj -- seon.repl-test`, selected the
same dependency digest with `:status :current`, no rebuild message, lock
wait 1,643 ms and held 1,284 ms. Its new snapshot digest was
`faa4a63068c82e000c64aeba59c4b63fb1368781e04fa90509700981f4ced2d8`.
Snapshot/dependency/worker phases were 4/7/1 seconds. The publication lock
waited 67,663 ms; the orchestrator restart terminated publication before a
tally. This is a dependency hit proof, not a completed gate. Seven lanes
were active. Evidence: `tmp/runner-dependency-prime.log` and
`tmp/runner-dependency-gate-1.log`.

## Warm-cache measurement without a competing cache lock

At the same frozen `79326a2d2` source and the same `seon.repl-test` selection,
a private worktree target was seeded with dependency classes only; its base
was built by the ordinary publisher. No published store was copied.

| Phase | Cold publication | Warm publication |
|---|---:|---:|
| Snapshot | 4 s | 3 s |
| Dependency cache and classpath | 5 s | 4 s |
| Worker checkout clones | 1 s | 1 s |
| Published base | 50 s | 0 s (reuse, 3 ms) |
| Coordinator and tests | 33 s | 27 s |
| Total measured phases | 93 s | 35 s |
| Tally | 15 tests / 50 assertions / 0 failures / 0 errors | same |

Evidence: `tmp/runner-base-cache-isolated-cold.log` and
`tmp/runner-base-cache-isolated-warm.log`. The whole warm run, including
tests, is below the 60-second fixed-cost target. This isolates cache-lock
contention, not CPU contention: eight lanes were active, with three other
coordinators and six workers observed. It predates the dependency-only key
correction. The old-harness baseline has not yet produced a completed tally.


## Three-regression comparison requested at 14:52

Separate detached worktrees with linked `reference-code`, run serially.
The two older revisions predate `bin/test-fast`; the probe calls each
revision's own worker projection and contract-arming functions, then runs
only the three requested existing tests through `clojure.test/run-tests`.
No fixture or assertion was changed. A 240-second probe backstop kills and
reaps its own remaining descendants. Current load exceeded the owner's
quiet-machine observation; these are assertion comparisons, not timings.

| Revision | Named tests | Assertions | Failures | Errors |
|---|---:|---:|---:|---:|
| `90170c3c8^` = `74b5b4b05` | 3 | 33 | 0 | 0 |
| `90170c3c8` | 3 | 33 | 0 | 0 |
| `b7e8a9143` (HEAD captured for comparison) | 3 | 33 | 2 | 1 |

At HEAD, fresh-root and interruption pass. The stale-cache fixture alone
fails its old physical-symlink assertion, exits before writing its
transcript, and consequently reports the missing transcript and retained
failed root. `547f59257` changed that layout to shared cache parents plus
`-Scp`; the fixture still expected the former selected-directory link.
The pool-sizing change is not the cause of this failure. The worker-layout
comparison is still running to reproduce the additional isolated failures.
Evidence logs: `tmp/runner-bisect-{before,sizing,head}.log`.

Reproducible probe, invoked with `clojure -M:test PROBE.clj` in each worktree:

```clojure
(require '[clojure.test :as test] '[seon.schema :as schema] '[seon.test.runner :as runner])
(let [owner (or (find-ns 'seon.test.arm) (find-ns 'seon.test.runner))
      packaged (ns-resolve owner 'packaged-test-projection)
      arm (ns-resolve owner 'arm-contracts!)
      decision (ns-resolve owner 'arming-decision)
      namespaces ['seon.test-runner-test]
      selected '#{a-fresh-run-root-is-claimed-before-population-and-sweep
                  interrupted-launcher-awaits-its-runner-before-retaining-the-root
                  stale-dependency-cache-is-refused-or-selected-and-recorded}]
  (schema/call-with-projection (packaged "bisect") #(require 'seon.test-runner-test))
  (let [projection (packaged "bisect")]
    (arm (decision) projection "bisect" namespaces)
    (doseq [[name v] (ns-publics 'seon.test-runner-test)
            :when (and (:test (meta v)) (not (selected name)))]
      (alter-meta! v dissoc :test))
    (let [result (future (schema/call-with-projection projection #(test/run-tests 'seon.test-runner-test)))
          summary (deref result 240000 ::expired)]
      (prn summary)
      (when (= ::expired summary) (println "BISECT BACKSTOP: named launcher regressions did not finish in 240 seconds"))
      (with-open [children (.descendants (java.lang.ProcessHandle/current))]
        (doseq [child (reverse (vec (iterator-seq (.iterator children))))]
          (.destroyForcibly ^java.lang.ProcessHandle child)))
      (shutdown-agents)
      (System/exit (if (and (map? summary) (zero? (+ (:fail summary) (:error summary)))) 0 1)))))
```


The unmodified HEAD launcher then prepared an actual worker checkout, and
that checkout ran the identical three-regression probe: **3 tests / 26
assertions / 5 failures / 2 errors**, matching the owner's reported failure
counts. Evidence: `tmp/runner-bisect-worker.log`.

The worker's `.gitignore` is a valid symlink to the prepared checkout;
resolving it succeeds. Nevertheless this bounded direct command in that
worker produces Git's warning:

```sh
git --work-tree="$PWD" ls-files --others --exclude-standard -- tmp/runner-ignore-probe
# warning: unable to access '.gitignore': Too many levels of symbolic links
```

Git refuses the symlink when reading ignore rules for an explicit work tree.
This is not a cyclic link. The `--work-tree` snapshot comparison arrived in
`cd42689b2`, before the sizing commit; the top-level worker symlinks date to
`be6db44da`. Copying top-level regular-file bytes into each worker retains
valid ignore rules and avoids walking nested fixture/cache output as new
source. The stale-cache assertion is independently updated to verify the
admitted `-Scp` argument and a real worker `.gitignore`, instead of the
obsolete cache-parent layout. All first-party entries now use the platform copy-on-write operation; only
`reference-code` remains an external dependency link.


Copying top-level files alone removed the Git ignore warning but left
synthetic directory links. Git treated those directories' unchanged files
as overlay work, and the fresh-root bound still fired. That partial probe
was stopped and its own descendants reaped. The final change clones **all
first-party entries**, preserving authored symlinks with `cp -cRP` on macOS
(`cp -a --reflink=auto` on Linux). No synthetic first-party directory link
remains. A newly prepared worker then passed the same probe: **3 tests / 33
assertions / 0 failures / 0 errors**. Evidence:
`tmp/runner-bisect-worker-clone.log`. The isolated full runner namespace
gate is now running over exactly `bin/test` and
`test/seon/test_runner_test.clj` on frozen `b7e8a9143`.


The first full namespace gate with complete COW views reported **41 tests /
259 assertions / 10 failures / 0 errors**. Only fresh-root and concurrent
nested gates failed; both passed in confirmation. The retained evidence
identified an additional runtime-directory recursion: an earlier test leaves
`workers/confirmation/operator-roots/confirm-world/confirmation-launch.edn`
in the worker checkout. Bare snapshot enumeration admitted that untracked
output, and population tried to copy `workers` into its own worker child.
The launcher now excludes generated `workers/` wherever it already excludes
`data`, `logs`, `target`, and `tmp`.

The concurrent regression's two nested snapshots omitted the parent's
changed runner-test file and therefore selected a different source digest.
Their one cold publication took 161 seconds, followed by JVM initialization;
the test's 240-second completion bound fired before a tally. The nested
`--paths` selection now includes `test/seon/test_runner_test.clj`, keeping
these root-lifecycle checks on the parent's already-published source digest.
No execution bound or assertion was weakened. Evidence:
`tmp/runner-cache-fixed-gate.log`, retained root `run.J9B7XW`.


Final owned-file gate on frozen `b7e8a9143` plus `bin/test` and
`test/seon/test_runner_test.clj`: **41 tests / 259 assertions / 0 failures /
0 errors**, exit 0. Snapshot 7 s, dependency/classpath 7 s, worker copies
8 s, cold publication 131 s, coordinator/tests 439 s; total launcher wall
596.35 s including cleanup. This is the runner's nested lifecycle suite,
not the performance selection. Successful root `run.CVyzaZ` was removed.
Evidence: `tmp/runner-cache-final-owned-gate.log`.
