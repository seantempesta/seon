---
title: test-fast implementation and interrupted verification
type: research
date: 2026-09-08
status: in-progress
---

# test-fast implementation and interrupted verification — 2026-09-08

Status: resumed by the owner. The initial interruption below is historical;
verification continues under the owner's instruction to resolve the shared
arming namespace and complete both timings and gates.

## Resumed implementation

`src/seon/test/arm.clj` now owns program loading and contract application.
`bin/test-fast` initializes through that namespace directly. The worker's
call will switch only after `git status --short src/seon/test/runner.clj`
is empty, as explicitly required by the orchestrator. No runner edits were
made during this resumed turn while the file was dirty.

The first successful resumed measurement of `seon.repl-test` was **47.18 s**:
15 tests, 50 assertions, zero failures and errors. Its arming report showed
911 installed contracts and complete coverage of 909 armable program
contracts. These counts are dated observations, not admission thresholds.
Subsequent `seon.cluster.work-test` attempts reached their tallies but exposed
changing source spans and then static-analysis errors during canonical
fixture population. The missing `ProcessHandle` import in the owned runner
test was fixed; the other in-flight syntax error subsequently cleared.

## Changes

`bin/test-fast` starts one test JVM in the working tree. It calls the extracted
`seon.test.runner/initialize-contracts!`, which the worker initialization now
also calls. This preserves the original sequence: load selected namespaces
under the packaged projection, reacquire the projection, derive shipped
decisions and caps, load the complete program, apply instrumentation, and
verify program contract coverage. The command reuses the runner's reporter,
progress watchdog, and namespace-boundary re-arming. Empty test namespaces
refuse. Failures exit nonzero.

The added `fast-and-worker-arm-the-complete-program-contract-set` regression
observes actual wrappers against `seon.instrument/armable` and exercises an
invalid call. It still needs execution under both launchers. AGENTS §5 and
the turn PRD §10 rule 1 document iteration versus commit gates.

## Dependency and fixture evidence

- `src/seon/test/runner.clj`: `arm-contracts!`, `arming-decision`,
  `packaged-test-projection`, `run-request!`, `start-liveness-backstop!`.
- `src/seon/instrument.clj:685`: the existing instrumentation owner collects
  loaded function contracts and applies the supplied projection and dial.
- `test/seon/test_support.clj`: `source-manifest` and `database-base` are
  delayed once per JVM; `with-database` branches the canonical memory base.
- `test/seon/cluster/turn_test.clj:164`: its `with-cluster` uses that memory
  fixture. Other helpers, including `test/seon/cluster/armed_test.clj:64`,
  explicitly exercise file-backed boot through `populate-published-root!`.
  Those helpers retain their own publication and roots; the fast launcher
  itself does no publication. Their compatibility remains unverified.

The fast command provides no worker isolation, retained invocation root,
automatic platform tier, or recorded result facts. It reads changing working
files directly, so a simultaneous rewrite can interrupt namespace loading.

## Measurement and exact stopping boundary

Command: `/usr/bin/time -p bin/test-fast seon.repl-test`.

Observed elapsed: **26.73 s** (user 41.92 s, system 1.73 s), exit 1,
**no tests executed**. This is a failed startup measurement, not test runtime.
The JVM reported macroexpansion failure at `seon/test/runner.clj:423:1`:
`defn-` received `(alt (Runtime/getRuntime) 124)`. The current file does not
contain that malformed form. Concurrent changes appeared in the runner's
namespace declaration and base/manifest paths during this load. The evidence
is consistent with reading a file across an in-place rewrite; it does not
establish a persistent syntax defect in the other lane's final bytes.

The publication hook independently refused with
`:malli.core/invalid-schema` for `:seon.turn/system-request`. An earlier
hook refused development adoption with “Development updates require their
own running JVM.” No live adoption success is claimed.

The assignment explicitly requires stopping at another lane's in-flight
verification boundary without editing or resuming that lane. Verification
stopped; the owned timed shell exited and was collected. `seon.cluster.work-test`,
the comparative `bin/test` timings, the regression gate, and `--platform`
were **not run**. No comparison is available.

Shared files `src/seon/test/runner.clj` and `test/seon/test_runner_test.clj`
contain concurrent foreign changes. This slice is left uncommitted rather
than committing their bytes under this assignment. Resume only after owner
coordination, then run both timed subjects, the regression with explicit
owned paths, and the platform gate.
