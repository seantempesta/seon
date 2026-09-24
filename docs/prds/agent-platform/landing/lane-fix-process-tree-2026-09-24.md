---
type: landing
status: partial
lane: fix-process-tree
created: 2026-09-24
report: docs/research/agent-platform/one-happy-path/cross-cutting-astra-2026-09-24.md
---

# Lane fix-process-tree: shell uses the one termination owner (2026-09-24)

`seon.cluster.process/terminate-subprocess!` is now the single subprocess-tree
termination owner for the operator and for shell. It sends TERM to every verified
identity, waits up to the caller's grace, force-kills verified survivors, then
awaits actual exit within the caller's cleanup bound. Shell's own identity and
force algorithm is deleted. Shell also samples its child's tree while waiting,
so a descendant orphaned by a normal root exit is reaped.

## Broken or unproven first

- **The live JVM does not run this code.** `bin/test-check default --ns seon.cluster.process-test`
  refused with "no eligible tests": the new test namespace and the edited source are not in
  default's program. A live probe showed `terminate-subprocess!` still private with arity 2,
  and `terminate-tree!` still loaded. Adoption belongs to root through publication.
- **The existing shell time-limit test could not run.**
  `time-limit-reaps-the-process-tree-and-marks-the-effect-interrupted` errored in fixture setup,
  before any process started: `seon.test-support/agent-tx` reported "A fixture agent needs
  exactly one cluster". This is the known fixture class in
  `docs/seon/issues/db-test-edn-codec-fixture-writes-are-refused.md`. The timeout path through
  the real handler is therefore proven only by the drills below. Each shell file-database test
  republishes the whole program. Two tests took 203 s in the snapshot, and an earlier three-test
  run was still indexing after 10 min. That is a platform cost outside this lane.
- **The census is red before this change.** `seon.bounded-boundary-census-test` fails identically
  with HEAD's and with the edited `process.clj`/`shell/jvm.clj`: 34 subjects, all in `script/`.
  Filed as `docs/seon/issues/bounded-boundary-census-red-on-script-subprocess-waits.md`.
  The new `.get (.onExit …)` forms are lexically inside `terminate-subprocess!` and `await-exit`,
  the owners the census already allows. No new census subject appears.
- **A double-forked daemon is not caught.** For example, `(cmd &)` orphans its child before any
  sample. Descendants are visible only while their parent lives (`ProcessHandle.descendants`),
  and the JVM exposes no process-group or subreaper seam. Detection is sampled every 50 ms.
- **The operator still captures identities only at launch and at termination.** `run-process!`
  has the same sampling gap for descendants orphaned during the run. The ruling did not cover it.
- **On interrupt, shell passes the identities sampled so far.** The owner also adds the root's
  current tree, so it misses nothing more than the timeout path does.

## Behaviour changes

- **Operator (`run-process!` on timeout).** Before: babashka `destroy-tree`, an immediate
  `destroyForcibly` on every identity, then await up to 10 000 ms. Now: TERM each verified
  identity, a grace of up to `subprocess-grace-ms` 1000 ms, then force survivors and await
  up to 10 000 ms. A timed-out operator child that ignores TERM now takes up to 1 s longer
  to die. A well-behaved child gets a clean TERM exit instead of SIGKILL. The 1000 ms value
  is this lane's choice; root may rule on it.
- **The owner no longer calls `babashka.process/destroy-tree`.** That function signals only the
  root's current live tree, without identity checks. The owner signals each captured identity
  after checking `(pid, start-instant)`, which also covers descendants already orphaned.
- **Shell, timeout or interrupt.** Before, a root that exited within grace skipped the force,
  so a TERM-ignoring grandchild survived. Now every captured identity is awaited, and survivors
  are forced. Shell passes `:seon.config.shell/termination-grace-ms` as both grace and cleanup bound.
- **Shell, normal exit.** It reaps surviving captured descendants before draining output. Before,
  nothing was reaped. A command that starts a background server and exits now has that server
  terminated, as ruled.

## Evidence

Drills on processes this lane started, checking `(pid, start-instant)` before every kill.
The before runs used default's loaded Vars. The after runs used the edited forms, read from
disk into a throwaway namespace `scratch.fix-process-tree` in the default JVM, which has since
been removed. No default Var was redefined.

| drill | before | after |
|---|---|---|
| (a) root exits within grace, TERM-ignoring grandchild (`terminate-tree!`, grace 250/2000) | 5.48 ms, **1 survivor** (pid 12707) | 308.5 ms, 0 survivors, reaped true |
| (b) root exits normally after 0.3 s, detached `sleep 30` | `:exited`, **1 survivor** (pid 14753) | `:exited`, 3 captured, reap 255.1 ms, 0 survivors |
| (c) well-behaved `sleep 30 & wait`, shell limit 300 ms | n/a | reap 2.95 ms, 0 survivors |
| owner on drill (a), its old immediate force | 3.55 ms, 0 survivors | covered by (a) |
| `/usr/bin/true` per run: old `await-exit` vs new `await-exit` + reap | 2.99 ms | 3.23 ms (+0.24 ms, +8%) |

- Reap times of about 250 ms in (a) and (b) are the grace plus the JDK's exit polling for a
  non-child handle. The orphan is not the JVM's child, so `onExit` polls.
- One descendant sample (`subprocess-tree-identities`) costs 0.387 ms, averaged over 500 runs.
  At 50 ms sampling that is under 1% of a core.
- Snapshot (`git archive` of HEAD at 09:18 plus this lane's four files, `reference-code`
  symlinked), `clojure -M:test`, `seon.cluster.process-test`: 2 tests, 10 assertions,
  0 failures, 1006 ms. Test (c) asserts root exit 143 (TERM, not 137 KILL), that the child's
  TERM trap ran, and elapsed < 2000 ms.
- Snapshot, `seon.shell.jvm-test/a-normal-exit-reaps-the-descendant-it-left-behind` through the
  real `run` handler and file-backed fixture: 2 of 2 assertions passed (exit 0, and the detached
  grandchild was gone). It ran in 203 213 ms, together with the fixture-erroring sibling test.

## Changes

- `src/seon/cluster/process.clj`: `subprocess-tree-identities` and `terminate-subprocess!` are
  public; `terminate-subprocess!` takes explicit `grace-ms cleanup-ms`; `run-process!` passes
  `subprocess-grace-ms` 1000 and `subprocess-cleanup-ms` 10000.
- `src/seon/shell/jvm.clj`: deleted `handle-identity`, `process-descendants`, `same-process?`,
  `force-exact!` and `terminate-tree!`. `await-exit` samples the tree into a volatile of
  captured identities. All three termination paths call the owner.
- `test/seon/cluster/process_test.clj` (new): regressions (a) and (c).
- `test/seon/shell/jvm_test.clj`: regression (b), `a-normal-exit-reaps-the-descendant-it-left-behind`.
- Net lines: source +67 / −78, which is **−11**; tests +82.

No reset is needed.
