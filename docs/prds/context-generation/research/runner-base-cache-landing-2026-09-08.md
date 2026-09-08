---
type: research
status: blocked
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

## Stop boundary and unfinished work

The landing-note edit's Markdown validator failed:

```text
Dependency pin validation could not derive repository evidence:
/Users/sean/src/seon/docs/seon/issues/changed-test-selector-classifies-hosts-by-path-prefix.md
(No such file or directory)
```

`git ls-files` confirms the path is tracked; `git status --short --` that
path reports ` D`. This lane did not delete it. This is a foreign
documentation-validation boundary, not a failing test assertion. Stopped
under the assignment's concurrency rule without editing or messaging the
other lane.

The preflight launcher pid 28617 received TERM and reaped its publication
JVM pid 49161. Shell exit: 143. Its recorded phase times are:

| Phase | Wall time | Qualification |
|---|---:|---|
| Snapshot, dependency preparation, worker checkout preparation | 100 s | Combined; 19:36:14–19:37:54 UTC; other gates active |
| Base publication | 86 s | Interrupted; 19:37:54–19:39:20 UTC |
| Tests | Not reached | No tally |
| Warm-cache run | Not run | No after measurement |

No production code changed. Base caching, stable digest implementation,
classpath caching, fixture consumption, retention, regressions, and the
platform gate remain unfinished. The Maven issue remains open. No green
gate or under-60-second result is claimed. Both owned shell sessions ended;
the interrupted root remains as evidence.
