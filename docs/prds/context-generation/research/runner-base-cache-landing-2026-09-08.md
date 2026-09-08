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
