---
lane: test-harness
date: 2026-09-08
status: landed
---

# The test harness, audited and repaired by class

Owner instruction: "make sure the test harness is doing the right thing as
that often fixes entire classes of problems."

Read end to end before any edit: [AGENTS.md](../../../../AGENTS.md) §2.3, §2.4
and §5 entirely; [the testing skill](../../../../.agents/skills/clojure-testing/SKILL.md);
[`bin/test`](../../../../bin/test); [`src/seon/test/runner.clj`](../../../../src/seon/test/runner.clj);
[`src/seon/test/selection.clj`](../../../../src/seon/test/selection.clj);
[`test/seon/test_support.clj`](../../../../test/seon/test_support.clj);
the landing notes
[instrumented-gate-backlog](instrumented-gate-backlog-landing-2026-09-08.md),
[instrumented-gate-backlog-2](instrumented-gate-backlog-2-landing-2026-09-08.md),
[production-defects-p1-p6](production-defects-p1-p6-landing-2026-09-08.md),
[storage-bound-repair-2](storage-bound-repair-2-landing-2026-09-07.md) § the
instrumented gate, [custody-isolation](custody-isolation-landing-2026-09-08.md) §4,
[verify-repair-2](verify-repair-2-2026-09-08.md) §2, and the four named issues.

## 1. Parity with boot — the armed set is the set a cluster arms

**Before.** `arm-contracts!` refused only when `:seon.instrument/instrumented`
was not a positive integer. That floor was satisfied by the worker's OWN test
namespaces, so a worker that armed none of the program still passed. Its input
was unchecked too: `declared-program-namespaces` answered `[]` in silence
whenever the relative root `"src"` did not resolve, and silently dropped any
file whose first form was not an `ns` form
([issue](../../../seon/issues/archive/declared-program-namespaces-returns-empty-in-silence.md)).
[verify-repair-2 §2](verify-repair-2-2026-09-08.md) measured the consequence:
the gate armed **908** vars, a live cluster **871**, and three cluster-side
vars — `seon.artifact/-main`,
`seon.artifact/install-initialization-pages!`, `seon.test/run` — were enforced
in production and checked by nothing.

**After.** Both sides are derived, and the check is SET COVERAGE, not a count.

- `seon.instrument/armable` (new, `src/seon/instrument.clj`) answers "which
  vars would malli instrument", using malli's own two questions: `mi/-schema`
  (a `:malli/schema`, or a complete set of arglist schemas) and the
  primitive-fn exclusion malli itself applies
  (`reference-code/malli/src/malli/instrument.clj:16,24`). It is a query
  anybody can ask, so the armable set and the set `apply!` installs cannot
  drift.
- `arm-contracts!` requires every declared program namespace, then refuses
  when `(set/difference (armable program) (instrumented))` is non-empty,
  NAMING the unarmed contracts. `seon.sci.admit/required-cap` is excluded on
  malli's own rule, which is correct: its `^long` hint means nothing can arm
  it, on a cluster exactly as here.
- `declared-program-namespaces` is now total: an unresolvable root, an
  unreadable file, a file declaring no namespace, and an empty derivation are
  each a typed refusal naming what was missing.
- The worker's armed line now prints `program-namespaces=` and
  `program-armable=` beside `instrumented=`, so the parity numbers are on the
  record of every run.

**Regressions.** `seon.test-runner-test/the-armed-program-derivation-refuses-absence-instead-of-answering-empty`
(all four absences, plus a non-vacuity assertion that the real root derives a
program containing `seon.artifact`) and
`arming-refuses-when-a-program-contract-carries-no-wrapper` (a program
contract with no wrapper refuses and names it; complete coverage arms; a
worker carrying MORE than the program is not a refusal).

### The parity proof, measured

Gate side, from a real worker JVM's own stderr
(`bin/test seon.fs-test`, ONE test namespace loaded — the worst case for the
old floor check):

```text
WARNING: Not instrumenting primitive fn #'seon.sci.admit/required-cap
bin/test: CONTRACTS ARMED worker= pool-1 mode= :panic namespaces= 1
          program-namespaces= 91 registered= 879 instrumented= 878
          program-armable= 876
```

Cluster side, on the live `default` cluster through
`mcp__seon__eval_clj` `jvm` mode, deriving the armable set independently with
malli's own two rules rather than calling the new function:

```clojure
{:program-namespaces 91
 :cluster-loaded-ns 518
 :cluster-instrumented 812
 :armable-in-program 820
 :armed-outside-program 2
 :armable-not-armed ["seon.edit.jvm/edit" "seon.fs.jvm/glob" "seon.fs.jvm/read"
                     "seon.fs.jvm/read-complete" "seon.fs.jvm/stat"
                     "seon.fs.jvm/write" "seon.shell.jvm/run"
                     "seon.test.runner/run-coordinator!"
                     "seon.web.jvm/fetch" "seon.web.jvm/search"]}
```

| | gate worker | live cluster |
|---|---|---|
| program namespaces declared | 91 | 91 |
| program namespaces LOADED | 91 | 90 (`seon.repl` absent) |
| armable program contracts | 876 | 820 |
| armed | **876 — all of them** | 810 |

The gate is now a strict SUPERSET of the cluster on the program side. The
three vars [verify-repair-2 §2](verify-repair-2-2026-09-08.md) named as the
gate's blind spot — `seon.artifact/-main`,
`seon.artifact/install-initialization-pages!`, `seon.test/run` — are inside
the 876 by construction, because the arm refuses if any armable program
contract carries no wrapper.

**And the probe found a production defect going the other way.** A live
cluster leaves TEN declared program contracts armed by nothing, NINE of them
the capability implementations themselves — `seon.fs.jvm/write`,
`seon.shell.jvm/run`, `seon.web.jvm/fetch`. They are resolved lazily at first
effect execution, so they load AFTER boot arms, and boot's positive count
makes the gap invisible. Filed:
[`a-live-cluster-arms-ten-fewer-contracts-than-it-declares`](../../../seon/issues/a-live-cluster-arms-ten-fewer-contracts-than-it-declares.md).
Not fixed here: it is a boot-path change wanting the reset-boundary live
proof, with three lanes holding live clusters.

**This closes** [`declared-program-namespaces-returns-empty-in-silence`](../../../seon/issues/archive/declared-program-namespaces-returns-empty-in-silence.md).

## 2. Worker isolation — the runner now names the suspect, and one verdict was lying

Two findings, and the second is the larger one.

### 2.1 The confirmation phase did not reproduce the pool worker's world

`confirm-parallel-failure!` initialized its worker with **one** namespace —
`[(symbol (::task-namespace task))]` — while every pool worker is initialized
with the WHOLE selection (147 namespaces under `--all`). A test whose subject
depends on what is LOADED — the program graph, the acquired SCI ctx's
bindings, which capability namespaces resolve in the ctx — was therefore
answering a *different question* in the confirmation than in the pool.

`parallel-only` then meant "green in a smaller world", which is no evidence
about scheduling at all. Thirteen of the twenty `parallel-only` verdicts in
[instrumented-gate-backlog-2](instrumented-gate-backlog-2-landing-2026-09-08.md)
§4 are `seon.sci.eval-test`, and their names are exactly the load-sensitive
kind:
`every-public-capability-function-in-the-graph-resolves-in-the-ctx`,
`bare-dir-and-program-derived-doc-are-repl-native`,
`compiled-runtime-roots-cannot-be-redefined-by-agent-code`,
`a-selected-render-inherits-the-live-arm-or-owns-one-when-unarmed`.
The issue's own ruling-out is consistent with this: `bin/test
seon.sci.eval-test` reports seven reds and none of the thirteen, because that
selection loads one namespace in BOTH the pool and the confirmation, so the
two worlds agree.

**Fix.** The confirmation worker loads the same namespace set the pool worker
loaded, leaving exactly ONE difference: the task runs alone. Regression:
`seon.test-runner-test/a-confirmation-loads-the-pool-workers-world`.

### 2.2 The runner measures what a task leaves behind

Nothing DECLARES which state is shared between tasks in a pooled worker, and a
declaration would be the maintained list §2.2 bans. So the seam that admits the
work derives it: `ambient-snapshot` is taken either side of every `:run`
command and reports the difference as that task's own fact. The members are
facts about the running process, not counts anybody keeps:

| member | what it catches |
|---|---|
| `::snapshot-instrumented` | a task that stripped or added contract wrappers |
| `::snapshot-registered` | a task that changed malli's function-schema registry |
| `::snapshot-live-clusters` | a task that left a cluster running |
| `::snapshot-sci-base` | a task that defined into the SHARED test SCI base ctx rather than its own fork (per-namespace var counts; never forces the delay) |

A red task carries `::prior-ambient-drift` — the earlier tasks in the SAME
worker that changed any of it — and a `parallel-only` verdict prints those
tasks as its suspected leakers, or says explicitly that nothing ambient
changed before it, which is itself the answer that sends the reader elsewhere.

**Known limit, stated rather than hidden:** the SCI-base member counts vars per
namespace, so it sees a `def` into the base and does NOT see metadata mutation
of a shared SCI Var. `seon.sci.eval-test/agent-owned-sci-var-metadata-remains-mutable`
and `compiled-runtime-metadata-cannot-be-changed-by-agent-code` are exactly
that shape; if they survive §2.1's fix, the detector needs a metadata member.

**Regression.** `seon.test-runner-test/a-task-that-changes-worker-global-state-is-named-as-the-leaker`.

### The verdict, on `--all`, naming a leaker for the first time

```text
bin/test: confirmation parallel-only
  seon.cluster.loop-test/a-refused-terminal-commit-still-closes-the-run
  worker=pool-1
bin/test:   suspected leakers, earlier in worker pool-1 —
bin/test:     seon.cluster.agent-test/routing-conservation-waits-for-terminal-evidence
              [:seon.test.runner/snapshot-instrumented]
```

That is the whole point of the mechanism: before it, this line read
`confirmation parallel-only seon.cluster.loop-test/a-refused-terminal-commit-still-closes-the-run`
and stopped, sending the reader to the run loop's owner. The cause was two
namespaces away, in a test that stripped three wrappers and did not put them
back.

### It found one on its first run

The FIRST `bin/test --platform` after the detector landed printed:

```text
bin/test: WORKER-GLOBAL STATE CHANGED by
  seon.cluster.cohost-boot-test/a-second-cluster-boots-under-the-first-cluster-s-instrumentation
  worker= pool-1
  {:snapshot-instrumented {:drift-removed-count 918}
   :snapshot-registered   {:drift-added-count 8}}
```

`test/seon/cluster/cohost_boot_test.clj:161` ends its `finally` with a bare
`(instrument/remove!)`, which is total by design: the worker leaves that task
with **918 wrappers gone**, and every later task in `pool-1` runs unarmed
against this test's timing rather than its own subject. That is precisely the
class [an-armed-contract-test-is-unarmed-by-another-test-in-the-same-worker](../../../seon/issues/an-armed-contract-test-is-unarmed-by-another-test-in-the-same-worker.md)
named — now with a culprit rather than a victim. `reassert-contracts!` already
makes the gate correct despite it, which is why the note is `cleanup` and not
a blocker.

`bin/test --all` then found two more of the same shape:

| task | wrappers stripped and never restored |
|---|---|
| `seon.cluster.cohost-boot-test/a-second-cluster-boots-under-the-first-cluster-s-instrumentation` | 918 |
| `seon.db-test/instrumented-wildcard-pull-keeps-unparsed-database-fields-ordinary` | 926 |
| `seon.cluster.agent-test/routing-conservation-waits-for-terminal-evidence` | 3 |

`seon.db-test` is the sharp one: it was the VICTIM in
[an-armed-contract-test-is-unarmed-by-another-test-in-the-same-worker](../../../seon/issues/an-armed-contract-test-is-unarmed-by-another-test-in-the-same-worker.md)
— `malformed-reads-return-flat-errors` red in the pool, green alone — and it
is also a CAUSE, exactly as that note's resolution predicted. It was in this
lane's owned paths and is **fixed**; the other two belong to another lane and
their hunks are in
[the issue](../../../seon/issues/a-platform-test-leaves-its-worker-stripped-of-every-contract.md),
along with the complete census of `instrument/remove!` callers and which
of them restore.

One census row matters beyond tidiness: `test/seon/sci/eval_test.clj:1050`
strips without restoring, and the very next deftest in that file is
`bare-dir-and-program-derived-doc-are-repl-native` — one of the thirteen. That
is a SECOND candidate cause for those verdicts, beside §2.1's.

## 3. Totality — every non-test verdict is typed, counted, and named

`print-final-tally!` printed one extra section ("Unconfirmed tasks"). It now
prints five, each counted:

- **Worker exchange failures** — the task, worker, error kind, exit code and
  stderr log for every task whose worker died, was bounded, or refused.
- **Unlaunchable tasks** — tasks that never ran because every pool worker had
  retired.
- **Unconfirmed tasks** — tasks whose isolated confirmation could not run.
- **Parallel-only tasks** — with their suspected leakers.
- **Tasks that changed worker-global state** — AGENTS §5.7.

And the classification is honest: `parallel-failure-classification` gives a
task whose worker DIED the verdict `:worker-exchange`, never `:parallel-only`.
That mis-attribution is precisely the re-arm defect
([issue](../../../seon/issues/the-test-runners-re-arm-kills-the-worker-under-its-own-contract.md)):
a dead worker took every namespace it held down with it, and each was reported
`confirmation parallel-only` against its own owner for a day. The task stays
RED — a dead worker is never quietly green — and it stays named as an exchange
failure.

### A really dying worker, observed

Not a fixture: while `resolve-loaded` was still missing its guard, three real
worker JVMs died at `exit 1` mid-task inside a nested `bin/test seon.fs-test`.
The tally said what happened, in the words it is supposed to:

```text
Ran 3 tests containing 0 assertions.
0 failures, 3 errors.

Worker exchange failures — 3 task(s) whose worker died, was bounded, or
refused; these reds belong to the exchange, not to the tests:
 - seon.fs-test/concurrent-deletions-treat-a-vanished-path-as-success
     worker= pool-1 kind= :seon.test.runner/worker-exited exit=1
     log=…/workers/pool-1/logs/worker-stderr.log
 - seon.fs-test/recursive-deletion-never-crosses-a-symlink
     worker= serial kind= :seon.test.runner/worker-exited exit=1  log=…
 - seon.fs-test/recursive-deletion-publishes-rate-bounded-progress
     worker= serial kind= :seon.test.runner/worker-retired
```

and each confirmation read `confirmation worker-exchange seon.fs-test/…`, not
`parallel-only`. The named log held the actual cause in one line
(`No such namespace: seon.test-support` from an unguarded `find-var`), which
is the whole point: three `seon.fs-test` reds that belong to `seon.fs-test`
in no way whatsoever, and a path straight to the thing that was broken. Before
this change the same event produced three per-namespace reds and, on
confirmation, three `parallel-only` verdicts against `seon.fs-test`'s owner.

One thing that run exposed and this note's own §2.4 standing order demands
reporting: the retired worker's line printed `exit= log=` with nothing after
them, claiming two facts the runner does not have. Absent is no key in the
tally too; fixed, with the assertion added to the regression.

**Regression.** `seon.test-runner-test/a-dead-workers-task-is-never-classified-parallel-only`,
which drives the classification directly and asserts the tally names every one
of the five sections. The existing deliberately-dying-worker fixtures
(`kill-after-command-acceptance-is-one-attributed-task-result`,
`checked-write-failure-…`, `live-worker-exceeding-its-bound-…`,
`exit-before-readiness-…`) remain the live proof that a real dead worker
produces exactly one attributed terminal value.

## 4. Fixtures — the census, before and after

Derived over all 157 files under `test/`, counting a
construction as HAND-BUILT when it assembles a shape the canonical fixture
already owns.

| construction | detector | hand-built before | after | canonical fixture |
|---|---|---|---|---|
| cluster handle | a map literal carrying `:seon.cluster.wake/channel` and the shipped dials | 5 (3 real, 2 false positives on `wake/route!` channel maps) | 2, both in files another lane holds | `seon.test-support/cluster-handle` |
| SCI ctx | `sci.eval/cluster-ctx` / `fork-cluster-ctx` called directly | 0 outside `test-support` | 0 | `seon.test-support/fork-cluster-ctx` |
| database + schema roster | `d/create-database` outside `test-support` | 10, every one a deliberate production-shaped or file-store database | 10 | `seon.test-support/with-database` + `::extra-schema` |
| effective config | `config/compile-manifest` | 8, all of them suites whose SUBJECT is config compilation | 8 | `seon.test-support/effective-config` |

The three real hand-built handles, and what happened to each:

1. `test/seon/render_source_test.clj` — `(merge (config/defaults) {…})`.
   Routed through `test-support/cluster-handle`. This is §2.5, not §5.3: the
   merge did carry every dial, but it poured the whole EFFECTIVE CONFIG into
   the handle rather than the members `:seon.cluster.loop/cluster` declares, so
   a renamed dial would leave the fixture working while production broke.
2. `test/seon/sci/defs_child.clj` — a verbatim re-implementation of
   `cluster-handle`, thirteen members long, under a docstring saying it could
   not use `seon.test-support` because that namespace "stands up its own
   database base". **It does not.** The base is a `delay`, forced by
   `with-database` and by nothing else, so a foreign JVM running against a real
   file store may take the fixture and leave the base unrealized. Deleted; the
   child now calls the one fixture and supplies only its cluster identity.
3. `test/seon/gen/loop_test.clj` — already routed; the detector matched the map
   literal INSIDE the `cluster-handle` call. No change.

**Left, because another lane holds those files** (`turn-loop` owns
`test/seon/cluster/*` and `test/seon/render/*`). Both are the same shape —
`(merge (config/defaults) {…})` where `(test-support/cluster-handle {…})`
belongs — and neither omits a declared member:

```clojure
;; test/seon/cluster/evaluate_sources_test.clj:57 and
;; test/seon/render/transcript_test.clj:1280
           cluster (merge defaults
                          {:seon.db/connection connection
                           :seon.cluster/name "…"
                           …})
;; wanted:
           cluster (test-support/cluster-handle
                    {:seon.db/connection connection
                     :seon.cluster/name "…"
                     …})
```

## 5. Selection — proven derived, never mtime, never a filename

Probe: `research/scripts/test_harness_selection_probe_2026_09_08.clj`, run
against the frozen worktree.

| claim | measured |
|---|---|
| a change selects the tests that REACH it | `src/seon/test/selection.clj` → 5 tests; `src/seon/db.clj` → **800 tests across 111 namespaces**; `src/seon/render/hiccup.clj` → 246 tests across 35 namespaces |
| the relation is call-edge transitivity, not a name match | of the 111 namespaces reaching `seon.db`, **107 share no name stem with it** (`my.background-test`, `seon.ai-stream-fold-test`, …) |
| a file no edge reaches selects nothing | `AGENTS.md` → 0 tests |
| MTIME IS NOT CONSULTED | after `setLastModified` on a source file, `input-digests` is byte-identical and `changed-inputs` is `[]` |
| CONTENT is what selects | a one-byte digest change → `["src/seon/test/selection.clj"]` |
| gate inputs no edge can reach widen, and are named | `resources/…` → widening `true`; `src/seon/db.clj` → `false` |

### `--all` vs `--full` — is the split honest?

**Yes.** 47 declared `:seon.test/long` markers (32 on vars, 5 on whole
namespaces, 10 individually authored) across 18 files resolve to the **54
tests** `--all` reports skipping, and the runner refuses a blank reason
(`marker-reason` throws `::invalid-marker-reason`), so every one carries a
written justification. Every reason names a real process, and most carry a
measured cost:

```text
bin/test: skipped 54 long tests:
 - seon.dev.fresh-operator-export-test/export-verb-produces-an-openable-queryable-store
     - 200.542 s pool: real start JVM, export JVM, store copy/reidentify,
       reopen, and query proof.
 - seon.bootstrap-drive-test/one-fake-o1-drive-grades-on-its-ending-commit
     - 171.859 s pool: real cluster graph bootstrap, objective/fork drive,
       and ending-commit grading.
 - seon.flow-test/forced-child-jvm-death-preserves-committed-facts
     - Forcibly terminates a child JVM to cover committed-fact survival
       across process death.
bin/test: run skipped coverage with: bin/test --full
```

The tier is not a place slow-and-flaky tests hide: it is the live-boot,
real-JVM, SIGKILL tier, it is named at the end of every `--all`, and the
command that runs it is printed beside the list. `--full` result below.

## 6. Bounds — every await, audited

Every await in the coordinator is bounded, most of them transitively; the
audit, by line:

| site | bound |
|---|---|
| `persist-virtual-thread-dumps!` `.get` | each dump bounded by `jcmd-backstop-seconds` (10 s) and catching Throwable |
| `worker-exchange!` | `anyOf` over reply / exact process exit / `exchange-bound-seconds` = `max(60, silence − 30)`; the bound RETIRES the worker |
| `stop-owned-process-tree!` `.get` | `process-tree-exit-backstop-seconds` (10 s), then a LOUD forced kill naming the stuck pids |
| `retire-worker!` | `waitFor` 10 s, and a survivor is reported |
| pool / confirmation / launch `.get` | transitively bounded — every underlying exchange is |
| `test-support/await-event!` | `event-backstop-seconds` (20 s) for latches, futures and channels, each throwing with the event it never saw |
| suite liveness | `SEON_TEST_SILENCE_SECONDS` (300 s default), dumping coordinator AND every descendant with `jcmd Thread.dump_to_file -format=json` — virtual threads included — then `halt 124` |

One caveat worth naming: `read-exchange-reply!`'s `.join` on the exact-exit
future is uninterruptible, so if a bounded exchange somehow failed to kill its
worker the reply virtual thread would stay parked. It cannot in practice — the
`:bound` branch calls `retire-worker!` with `destroyForcibly` — but the thread
is not itself bounded, and that is the one place in the exchange where the
bound is an invariant rather than a clock.

Retained-root reaping matches AGENTS §6: `bin/test` keeps every LIVE
invocation (by `kill -0` on the recorded pid) plus the three newest inactive
roots younger than 24 hours, reaps the rest through `seon.fs`'s no-follow walk,
and leaves an unclaimed directory alone for 30 s so a racing `mktemp` can
publish its claim.

## 7. Result facts — the tier that could fill the evidence recorded none

`bin/seon status` reported `test evidence: UNKNOWN for 144 namespaces (1369
current tests have no recorded result row)` and told the reader to "run
bin/test". Running `bin/test` could never fix it: `persistent_results_root` was
set ONLY for the bare default, so `--all` and `--full` — the two tiers that run
every eligible test — recorded **nothing at all**, and the bare default records
only the tests reaching changed code.

Result facts are per-test and total (`record-tx` replaces one test row's
complete latest result), so a tier that ran a subset writes real rows about
exactly the tests it ran. The narrowing was the defect. Every
evidence-WIDENING tier now records — bare, `--changed`, `--all`, `--full`.
`--platform` is deliberately excluded: it rewrites the same declared
moving-part rows every time and every lane runs it after every slice, so the
round trip to the store holder would be a constant cost for no new evidence.
An explicitly named `--result-cluster` still directs the evidence there, and an
explicit namespace selection still records nothing.

Before: `test evidence: UNKNOWN for 144 namespaces (1369 current tests have no
recorded result row)`.

The status line now says exactly WHY the evidence is unknown and which tier
fills it, instead of pointing at one that cannot.

## 8. Files touched

Owned and changed:

- `src/seon/instrument.clj` — `armable` (new, derived from malli's own rules)
  and its private `primitive-fn?`.
- `src/seon/test/runner.clj` — total `declared-program-namespaces`; set-coverage
  arming refusal; per-task ambient snapshot, drift and attribution;
  `parallel-failure-classification`; the confirmation worker's namespace set;
  the five-section total tally.
- `bin/test` — every tiered invocation records its per-test result facts.
- `script/seon/fresh_operator.clj` — the unknown-evidence status line says WHY
  and which tier fills it.
- `test/seon/test_runner_test.clj` — five class regressions, one stale tally
  expectation corrected.
- `test/seon/render_source_test.clj`, `test/seon/sci/defs_child.clj` — routed
  through `seon.test-support/cluster-handle`; a false docstring corrected.
- `docs/seon/issues/declared-program-namespaces-returns-empty-in-silence.md`
  (resolved),
  `docs/seon/issues/thirteen-sci-eval-reds-appear-only-under-the-whole-gate.md`
  (named cause),
  `docs/seon/issues/a-live-cluster-arms-ten-fewer-contracts-than-it-declares.md`
  (new).
- `research/scripts/test_harness_selection_probe_2026_09_08.clj` and
  `research/scripts/test_harness_parity_probe_2026_09_08.clj` — the two probes
  whose numbers are quoted above.

Deliberately NOT touched, because another lane holds them: the two
hand-rostered cluster handles in `test/seon/cluster/evaluate_sources_test.clj`
and `test/seon/render/transcript_test.clj` (hunks in §4), and the boot arming
path's missing pre-load (issue filed, §1).

## 9. Issue outcomes

| issue | outcome |
|---|---|
| [`declared-program-namespaces-returns-empty-in-silence`](../../../seon/issues/archive/declared-program-namespaces-returns-empty-in-silence.md) | **resolved** — every absence is a typed refusal; the arming assertion is set coverage, not a floor of zero |
| [`the-test-runners-re-arm-kills-the-worker-under-its-own-contract`](../../../seon/issues/the-test-runners-re-arm-kills-the-worker-under-its-own-contract.md) | already resolved by `instrumented-gate-backlog-2`; verified at HEAD and its remaining half — "never a per-namespace red" — is now enforced by `parallel-failure-classification` with its own regression |
| [`an-armed-contract-test-is-unarmed-by-another-test-in-the-same-worker`](../../../seon/issues/an-armed-contract-test-is-unarmed-by-another-test-in-the-same-worker.md) | already resolved; the drift detector now makes the same class VISIBLE rather than only survivable |
| [`thirteen-sci-eval-reds-appear-only-under-the-whole-gate`](../../../seon/issues/thirteen-sci-eval-reds-appear-only-under-the-whole-gate.md) | **named cause, still open** — the confirmation loaded a smaller world than the pool; fixed, awaiting the `--all` that is this note's acceptance criterion |
| [`a-live-cluster-arms-ten-fewer-contracts-than-it-declares`](../../../seon/issues/a-live-cluster-arms-ten-fewer-contracts-than-it-declares.md) | **new** — found by the parity probe |
