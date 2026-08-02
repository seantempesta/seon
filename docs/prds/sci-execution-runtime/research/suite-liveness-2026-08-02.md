---
type: research
status: complete
tags: [testing, reliability, process, flow]
---

# Suite liveness — 2026-08-02

## Result

The silent full-suite failure was not store-flock contention and was not a
child JVM. Two supposedly killed test JVMs remained alive for 91 and 83
minutes. Independent `jcmd Thread.print -l` dumps found both main threads
parked forever in the same unbounded agent teardown take:

`clojure.core.async/<!!` → `seon.cluster.agent/disarm!` → agent or cluster
fixture cleanup.

Neither process had a descendant. `flow/stop` is asynchronous, and
`disarm!` then waits for `:seon.cluster.loop/completion`
(`src/seon/cluster/agent.clj:419-421`). The turn stop transition intends to
publish that value (`src/seon/cluster/agent.clj:190-195`), but these dumps
prove it does not publish on every reachable stop path. The production repair
is filed in
[[agent-graph-stop-can-wait-forever-for-turn-completion]]. The suite platform
now contains this class loudly while preserving the captured evidence.

Commit `6829c2db3` adds semantic progress and the suite-level liveness
backstop. The initial checkout-wide refusal in that commit was superseded by
the owner ruling: concurrent runs now isolate instead of serialize. Commit
`f2b2e26b0` replaces the first symlink-only checkout projection with
copy-on-write first-party roots so canonical source paths remain honest.
There is no suite lock.

## Dependency ledger

| Boundary | Selected source | First-party use |
|---|---|---|
| `clojure.test` reporting | Clojure `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`; `reference-code/clojure/src/clj/clojure/test.clj:405-412` | `src/seon/test/runner.clj:71-95,239-270` |
| Flow stop and proc transitions | core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`; `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:108-155`, `flow/impl.clj:174-217,271-305` | `src/seon/cluster/agent.clj:190-195,400-429` |
| Process and JVM evidence | OpenJDK 26.0.1 `ProcessHandle`, `Process.onExit`, and `ThreadMXBean` | `src/seon/test/runner.clj:97-187` |
| Root isolation | existing `bin/seon --root PATH`; `script/seon/fresh_operator.clj`, especially process-root propagation and root-scoped paths | `bin/test:112-196` |
| Database child readiness | Datahike `256b714d97a0e8f952b01a47c693eff2976ccee7` | `test/seon/cluster/store_child.clj` and the event-driven proof recorded in [[flaky-tests-2026-08-02]] |

## Reproduction

The two orphaned JVMs were PIDs 7519 and 8569. At capture they had consumed
zero CPU progress for roughly 91 and 83 minutes and retained about 929 MB and
1.37 GB RSS respectively.

- PID 7519 parked in `CountDownLatch.await` → promise deref →
  `clojure.core.async/<!!` → `seon.cluster.agent/disarm!`. Its caller was the
  `disarm-all!` cleanup for
  `lint-refusals-continue-the-episode-until-the-cap` in
  `test/seon/cluster/agent_test.clj`.
- PID 8569 had the same terminal wait through
  `seon.cluster/disarm-agents!` → `seon.cluster/stop!`, called from
  `test/seon/cluster/program_restart_test.clj:272`.
- `ProcessHandle.descendants` was empty for both. This refutes the proposed
  child-JVM cause for the observed incident. The child-process audit still
  found unbounded read/wait paths already owned by
  [[operator-subprocesses-have-unbounded-read-and-wait-paths]]; they are not
  duplicated here.

Both exact lifecycle regions later completed in the instrumented full-suite
runs. That does not repair the race; it proves the suite now says where it is
when the race does not occur.

## Isolation decision

Concurrent `bin/test` is supported. Refusal would serialize every development
lane, so every invocation now creates one root under `tmp/test-runs/run.*` and
uses it simultaneously as:

- the JVM working directory;
- `seon.operator.root` and `seon.test.root`;
- the owner of private `data`, `logs`, `target`, `tmp`, `.cpcache`, process
  records, advertisements, and result database; and
- the root retained after any nonzero exit for diagnosis.

The fresh first-party roots `bin`, `config`, `resources`, `script`, `src`, and
`test` are APFS copy-on-write clones on macOS, reflink copies on Linux, and
ordinary copies only on other platforms. This is necessary, not cosmetic: the
first symlink projection made canonical file paths escape the run root and
failed analyzer, edit-feedback, MCP-frame, and operator-root tests. The
copy-on-write shape made those same boundaries green concurrently:

```text
focused-a-exit=0 focused-b-exit=0
Ran 37 tests containing 242 assertions.
0 failures, 0 errors.
Ran 25 tests containing 141 assertions.
0 failures, 0 errors.
```

Large vendored dependencies remain shared through symlinks. The
`target/dev-dependency-classes` cache is also shared and consumed only; the
separate `:dev-cache` alias is its only writer (`dev_cache.clj:11-13,182-247`).
Fresh source analysis never writes it.

Fixed test paths such as `tmp/armed-test`, `tmp/oversight-test`,
`tmp/config-test`, and `tmp/docstring-scan-test` now resolve inside each run.
Two simultaneous invocations of the same fixed-path config namespace proved
that separation:

```text
suite-a=0 suite-b=0
Ran 11 tests containing 46 assertions.
0 failures, 0 errors.
Ran 11 tests containing 46 assertions.
0 failures, 0 errors.
```

Prepl and explicit test web ports are ephemeral. During the concurrent full
runs, both suites requested derived web port 7912. One bound it; the other
announced the collision and continued on ephemeral port 59561, exercising the
existing fallback rather than requiring another mechanism.

## Progress and the loud backstop

The CLI runner starts before requiring test namespaces and flushes these
semantic transitions:

- suite `START` with PID, Git commit, namespace count, and silence limit;
- `LOAD` and `LOADED` for every namespace; and
- `BEGIN` and `END` for every namespace and test Var.

The caller can therefore distinguish loading, the exact active test, and
completed work. The old 87 minutes of zero bytes is no longer possible.

The last-resort silence limit defaults to 300 seconds and may be shortened
only through `SEON_TEST_SILENCE_SECONDS`. It does not substitute a clock for
an observable child exit. It detects the suite-level absence of all reporter
events, which is the otherwise unobservable symptom seen by the caller. Its
firing prints and persists:

- suite identity, root, working directory, and last semantic progress;
- every descendant PID, start instant, liveness, and command;
- JVM deadlock detection and a full thread dump; and
- the exact diagnostic path before forcing descendants down and halting with
  exit 124.

A one-second falsifier fired while the runner was loading its selected
namespace:

```text
bin/test: SUITE LIVENESS BUG
bin/test: last-progress ... "LOAD 1/1 seon.test-runner-test" ...
bin/test: isolated-operator-root .../tmp/test-runs/run.E9MsPl
bin/test: diagnostic-log .../tmp/test-liveness/55332-1785700133429.log
bin/test: forcibly stopping suite descendants and exiting 124
bin/test: retained failed isolated operator root .../run.E9MsPl
```

Legitimate generative tests in the observed full gates produced their next
event in roughly 120 to 180 seconds. The 300-second value is therefore a loud
last-resort bound above measured work, not a primary completion mechanism.

## Concurrent full-suite proof and remaining gate boundary

Two full suites ran concurrently from distinct roots and both terminated with
their own counts rather than refusing or hanging:

```text
suite-a-pid=55482 suite-b-pid=55483

# suite A
bin/test: isolated operator root .../tmp/test-runs/run.enGJhp
Ran 840 tests containing 4149 assertions.
52 failures, 3 errors.

# suite B
bin/test: isolated operator root .../tmp/test-runs/run.SvyWyG
Ran 840 tests containing 4149 assertions.
52 failures, 3 errors.
```

Those runs exposed and retained the symlink canonical-path defect fixed by
`f2b2e26b0`. The post-fix concurrent owner-boundary proof above is green.

A final green pair of full gates is currently blocked by an unrelated dirty,
protected `test/seon/ai_stream_fold_test.clj`. Its focused gate independently
reports 19 tests, 72 assertions, 29 failures across
`presentation-noise-is-silent-but-malformed-data-refuses-the-stream`,
`one-shot-replies-refuse-present-malformed-assistant-fields`, and
`a-malformed-stream-settles-as-an-evidenced-flat-error`. The source owner has
not landed the behavior those new assertions require; the existing issue is
[[malformed-sse-data-can-change-agent-code]]. Per the shared-tree instruction,
this lane stopped rather than editing or working around that boundary.

## What is now unrepresentable

- One suite cannot delete, reopen, or observe another suite's relative test
  root or operator state.
- A second suite cannot wait behind a suite-global lock; no such lock remains.
- Namespace loading and test execution cannot remain silent to the caller.
- A suite with no reporter progress for 300 seconds cannot remain alive
  indefinitely: it emits durable process and thread evidence, stops its own
  descendants, exits 124, and retains its root.

The underlying agent graph stop race remains representable until its owning
issue is repaired. What is deleted here is its silent, evidence-free impact on
the development gate.
