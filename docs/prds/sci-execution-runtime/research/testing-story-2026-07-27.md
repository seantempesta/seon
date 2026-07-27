---
type: research
status: complete
tags: [research, testing]
---

# The testing story — what killed it before, what the fresh system owes

Owner's concern, verbatim (2026-07-27): *"testing tools with the wrong
fixtures can kill this project, especially when garbage tests pile up
measuring the wrong things. The new system will need new tests so we
shouldn't be porting old problems that likely don't exist."*

This document mines the whole history for what actually killed testing
here, evaluates the fresh system as it stands today, and ends with a
constitution the orchestrator can seal into the plan and conventions.

## 0. Method and the numbers this document was written against

Everything below was measured at `294f56723` on branch
`codex/runtime-reliability-refactor`, not quoted from prose.

| measurement | value |
|---|---|
| `bin/test` (full, one JVM) | 43 tests / 186 assertions / 0 failures / **35.58 s** wall (86.65 s user, 257% CPU) |
| `clojure -M:test -e nil` (JVM + Clojure only) | **0.24 s** |
| `clojure -M:test -e "(require 'datahike.api)"` | **6.86 s** |
| `bin/test seon.cluster.boot-test` | 7 / 27 — **2.27 s** (no Datahike) |
| `bin/test seon.cluster.run-test` | 6 / 28 — **8.71 s** |
| `bin/test seon.cluster.store-test` | 8 / 25 — **23.13 s** (two child JVMs) |
| `bin/test seon.flow-test` | 15 / 72 — **18.96 s** (one child JVM, two monitor servers) |
| `bin/test seon.flow.loop-test` | 4 / 31 — **8.61 s** |
| `bin/test seon.schema.datahike-test` | 3 / 3 — **7.28 s** |
| `bin/test seon.cluster.run-test` output | **15,696 lines**, 355 `:datahike/write-error` events, 2,320 `clojure.lang` stack frames — all from *expected* refusals |

Two derived facts matter for everything that follows:

- **The fixed cost of a Datahike-loading suite is ~7 s, and it is paid per
  JVM, not per suite.** The five suites cost 69.0 s run separately and
  35.6 s run together. Selection that runs three namespaces in three JVMs
  is slower than running everything in one.
- **A child JVM costs a full second cold start of its own.** `store-test`
  is 23 s for 25 assertions because two child JVMs each re-pay the ~7 s
  Datahike load; `flow-test` pays it a third time.

Sources read end to end: `bin/test`, all six fresh suites, `bin/test-writer`,
`bin/test-cljs`, `bin/test-parser`, `tests.edn`, `bin/seon-hook`,
`script/seon/dev/changed_test.clj`, `.claude/seon-hook.edn`,
`docs/prds/sci-execution-runtime/plan/{README,handbook,history,unsettled}.md`,
`research/{pod-test-coverage,test-selection-spec,quality-review,src-split-audit}`,
`docs/conventions.md` §Testing, `.agents/skills/clojure-testing/SKILL.md`,
`src-inspect-ai/{README.md,evaluation-sources.lock.json}`, and ~20 archived
issue notes under `docs/seon/issues/archive/`.

---

## 1. The graveyard — every test harness this project has buried

Git is the archive; each row is a commit you can read.

| harness | born | died | what it solved | what killed it |
|---|---|---|---|---|
| **the gym** (`bin/gym`, `bin/gym-scorecard`, `test/seon/gym/**`, 30+ EDN scenarios) | `24ade2fae` 2026-06-10 | `bdbc32e2b` 2026-07-12 "retire the homegrown gym" | scenario-driven agent behavior scoring with a scratch store and scorecards | a homegrown benchmark harness we also had to maintain. **Owner decision: Inspect AI replaced it** — it runs the standard suites, so the bespoke one had no remaining question of its own. DEAD BY DECISION, not by neglect. |
| **gold-patch replay** (`bin/replay-gold-patches`, `bin/replay_gold_patches.clj`, 434 lines) | `f9d56f44a` 2026-07-06 | `e9b35472d` 2026-07-13 "remove broken JVM replay harness" | a WRONG=0 hard gate over recorded edit patches | **seven days alive.** A recorded corpus rots at the speed of the thing it records; nobody could keep it loadable. |
| **`bin/test-clj`** | `3eceb17d5` 2026-05-27 | `e9b35472d` 2026-07-13 | a JVM entry point beside the CLJS one | folded into the replay removal; it existed only to drive the replay harness. |
| **`seon.test.runner`** (self-hosted CLJS runner + `:seon.test` facts) | `f5d678c22` 2026-05-22 | `9ebd05588` 2026-07-26 (pod cut group 4) | let an *agent* run its own behavioral tests and attach the result to the causing eval | it was a whole second test engine inside the product. Its one durable idea survived (evidence must attach to the eval that caused it — `archive/eval-test-evidence-was-only-latest-state.md`); the engine did not. |
| **`bin/auto-test-hook`** | `a0df50f38` 2025-12-13 | `919089458` 2025-12-28 | shell-level "run tests after an edit" | replaced by the in-process hook; the shell version could not select. |
| **`bin/plan-state` / `state.md`** | `e8c84fcdd` 2026-07-26 | `16d400b7e` 2026-07-27 | a generated snapshot of gate/tree state so the plan could not lie | **one day alive.** Owner ruling: a cached snapshot of the tree is *stored derived state*. Verify against the tree on demand. |
| **kaocha (`tests.edn`)** | pre-Seon | never formally | unit/integration split by metadata, profiling plugin | **still on disk at the repo root with zero live references** (`rg kaocha` finds only `tests.edn` itself and archived prose). Dead config that reads as a supported runner. |
| **`bin/test-parser`** | 2026-07 | never | a sub-second babashka inner loop for `seon.repl.parse` | **broken at HEAD** — `bb.edn:1` paths are `["script" "src" "src-old" "test"]`, the suite now lives at `test-old/seon/repl/parse_test.cljc`, so it fails on require. Its own header admits the shape: *"the authoritative gate is still `bin/test-cljs`; this is the inner loop, not a replacement"* — a fourth runner justified by speed, which then rotted the moment the tree moved. |
| **`bin/test-cljs`** (shadow `:node-test` + Bun, lock file, report EDN) | `3eceb17d5` 2026-05-27 | demoted 2026-07-27 (owner: CLJS build OFF) | the real CLJS gate, 1,300+ tests | the pod died; a runner that needs a shadow build cannot be in a fast loop (see §2 R1). |
| **`bin/test-writer`** (artifact-gated JVM gate) | 2026-06 | demoted 2026-07-27 (quarry only) | the JVM/Datahike gate | **the artifact gate** — see below. This is the single most instructive corpse. |

### The artifact gate, specifically

`bin/test-writer:20-48` runs babashka to `prepare-dependencies!`, then reads
`seon.dev.artifact/current-manifest`, and if it is absent **refuses to run unit
tests at all**:

> `"Standalone writer tests require the current compiled program artifact. Run
> \`bin/seon up\`, wait for watcher readiness, then run \`bin/seon down\` and
> retry \`bin/test-writer\`."`

To run a pure in-memory unit test you had to boot the whole operator (shadow
watcher → database server → pod), wait for readiness, shut it down, and only
then test. The remedy string *is* the indictment. The costs are recorded:

- `plan/README.md:505` — *"`bin/test-writer` discovers 0 tests"* as a plain
  statement of State A;
- `unsettled.md:241-249` — the JVM gate spent a day *"RESTORED and RED"* with
  the note that the failures were *"probably not real failures at all — the
  suite had not been runnable"*;
- `docs/prds/sci-execution-runtime/plan/README.md:342` (the ten-second ruling)
  names the same chain as structurally unfixable: *"serial shadow-cljs build →
  ~45 s AOT/CDS republish on any JVM edit → pod start-gates"*.

A gate that requires a build is a gate that is not run, and a gate that is not
run goes red for reasons nobody can attribute.

### The one artifact-gate idea worth keeping

`bin/test-writer:60-70` installs a targeted trove log filter that suppresses
**exactly** `datahike.writer` / `:datahike/write-error` payloads, with the
comment: *"Expected transaction conflicts are asserted as values by the
retained tests."* The fresh `bin/test` has no such filter, and it shows: see
§4's noise finding.

---

## 2. The pathologies — ranked root causes, with receipts

Ranked by how much of the historical damage each caused.

### R1 — The gate needed something built (velocity death)

Receipts above. Second-order damage: every "is it green?" question became
"is the artifact fresh?", and the answer was recorded in prose that went stale
in a day (`history.md:114-120`: *"verify against the tree, not against a
document — including a document written yesterday by me"*).

**Fresh status: STRUCTURALLY IMPOSSIBLE.** `bin/test` is 44 lines of bash that
shells `clojure -M:test` on the source classpath (`bin/test:44`). There is no
manifest read, no operator, no artifact, no lock. Measured: 0.24 s to a live
JVM. The only way to reintroduce this class is to add a build step to
`bin/test`, which is a one-line diff to reject in review.

### R2 — Ambient and shared state in fixtures (the silent-contamination class)

The recurring shape: a test replaces or opens something global, and its
`finally` restores less than it took.

- `archive/generated-terminal-test-leaks-global-db-query.md` — `my.plan-test`
  redefined `seon.db/query` to return `"terminal-message"` and restored only
  three of four things. **Focused runs were green; the full gate produced 11
  failures in an unrelated namespace**, all reading the literal string
  `"terminal-message"`. Deterministic contamination that only the full suite
  could see.
- `archive/canvas-test-completed-before-restoring-database-stub.md`,
  `archive/cljs-tests-finished-before-restoring-global-stubs.md` — the same
  class via async completion ordering.
- `archive/test-runner-fixture-opened-a-local-datahike-database.md` — a fixture
  opened its own connection against a removed ambient-connection API.
- `archive/datahike-query-stats-fixture-leaked-connections.md` — leaked
  connections across tests.
- `archive/dev-eval-program-row-rejection-was-fixture-noise.md` — the most
  expensive one. A *fixture's* stubbed error string
  (`"program row rejected"`, source `"(+ 1 2)"`) was written 27 times into the
  shared `logs/pod-events.log`, was then found by a live-system audit, ranked
  as a live defect, and carried into a PRD as a real blocker. **A fixture wrote
  into production's log and cost a whole investigation.**
- `branch-trial-tests-write-into-live-operator-state.md` (still open) — a trial
  harness wrote `tmp/seon-operator/branches/trial.edn` and made `bin/seon
  status` unusable on the live cluster until a human deleted the file.

**Fresh status: MOSTLY STRUCTURAL, ONE HOLE.** There is no ambient connection
to `set!` — every database test constructs `{:store {:backend :memory :id
(random-uuid)}}` and releases in a `finally`
(`test/seon/cluster/run_test.clj:54-65`). `with-redefs` appears nowhere in
`test/`. Filesystem fixtures are project-local under `tmp/<suite>/<uuid>/`
(`boot_test.clj:25-28`, `store_test.clj:28-31`). The hole: `flow_test.clj`
starts real `flow-monitor` HTTP servers on ephemeral ports and spawns child
JVMs — process-level shared state that is *isolated by construction today* but
is exactly where R2 returns if a stop is ever skipped on a failure path.

### R3 — Checks that read absence of signal as health

The program's own named worst failure (`handbook.md:108-112`;
`history.md:108-113`): **two false clearances shipped within one hour.** A
duplicate-registration check reported "none" because `rg` prefixes filenames so
`uniq -d` never matched; a suite check reported "green" by inferring passing
from the absence of `FAIL` lines in a log that was still being written.

**Fresh status: PARTLY STRUCTURAL — one live instance remains.** `bin/test`
exits on `(+ fail error)` from `clojure.test/run-tests` (`bin/test:41-43`),
which is a real verdict, and a failing `require` aborts with a non-zero exit.
But **`run-tests` over a namespace containing zero `deftest`s reports 0/0 and
exits 0.** Delete every test in a file, or rename `deftest` away, and the gate
says green. The discovery rule is filename-only (`bin/test:24-31`), so a suite
that stops testing is indistinguishable from a suite that passes. This is the
one place the fresh gate still answers "fine" when the subject is absent.

### R4 — Non-reproducible gates (time seeds, wall-clock deadlines)

- `archive/codec-totality-gate-used-time-seeds.md` — **identical source
  produced 83 failures at 22:03 and 85 at 22:55.** The suite could not
  distinguish a regression from a different sample until `1fbbc7b8e` pinned
  seed `424242`.
- `archive/embedding-nonblocking-test-used-five-millisecond-deadline.md` — a
  5 ms `Future` timeout as a *correctness* proof; a cold scheduler failed it
  while the behavior was correct.
- `archive/owner-close-reconnect-test-raced-retry-backoff.md`,
  `archive/uds-send-owns-slot-test-raced-real-write.md` — the same class.

**Fresh status: MOSTLY STRUCTURAL, ONE REGRESSION ALREADY PRESENT.** Every
fresh property pins a seed (`run_test.clj:492`, `boot_test.clj:90`,
`store_test.clj:69`, `loop_test.clj:505`) and time is an explicit input
(`run_test.clj:67-72` deterministic `t0`/`at`). But
`run_test.clj:503-589`'s recovery property builds its inputs with
`(random-uuid)` **inside** the property body (`:526-527`), so its `:seed
20260727` does not make the trial reproducible and a shrunk counterexample
cannot be replayed. A seeded property with a nondeterministic input is a
non-reproducible gate wearing a seed.

### R5 — Multi-runtime duplication (two engines, two runners, one truth)

`bin/test-cljs` needed a shadow compile, a Bun process, a `tmp/test-cljs.lock`,
and its own report EDN. The archive has an entire genre of issues about the
*runner* rather than the code: `test-cljs-lock-no-wait-dial`,
`test-cljs-compile-failure-retains-live-lock-owner`,
`test-cljs-owner-crash-retained-bun-runner`,
`test-cljs-multiple-exact-selectors-run-zero-tests`,
`changed-test-interruption-orphans-test-runner`,
`changed-test-hooks-queue-stale-runs-behind-active-owner`. Add
`changed-test-full-widening-parity`: a widened selection ran **1,292 tests
where the canonical runner ran 1,305**, and 14 assertions failed only because
the direct invocation lacked `config/test.edn` — the two paths to "green"
disagreed.

**Fresh status: STRUCTURALLY IMPOSSIBLE while the CLJS build stays off.** One
runner, one classpath, no lock, no report file. Note the residue: `tests.edn`
and `bin/test-parser` are still on disk and both claim to be runners.

### R6 — Test count as the health metric, and coverage audits that rot

`archive/test-coverage-audit-stale.md` — a completed coverage audit whose P0/P1
findings all named files that no longer existed. `archive/test-suite-audit-2026-06-25.md`
is a careful audit that opens by noting *"a concurrent agent is mid-fix RIGHT
NOW"* — it was stale before it was committed.

**Fresh status: RULED, and the ruling is the right one.** `README.md:218-223`:
*"A smaller suite is a desired outcome, not a regression… the health metric is
class coverage, never test count. No lane re-inflates the suite to match an old
number."* The fresh 43 tests replacing ~1,300 is a success, not a gap.

### R7 — Harnesses that outlived their question

The gym (32 days), the replay harness (7 days), `bin/plan-state` (1 day). Each
solved a real question; none was deleted when the question closed, so each
spent its remaining life as maintenance load and as a *second* answer to "is it
working?" The counter-rule already exists (`AGENTS.md:837`: *"Do not restore
the gym, add bespoke drive scripts, or create another runner"*), and it is
enforced by the fact that a new runner is a visible new file.

### R8 — Each half covered, the interaction untested

The cleanest specimen is `store-flock-fcntl-close-hazard-has-no-recurring-falsifier.md`.
Java's `FileLock` is `fcntl`: `close(2)` drops **every** lock a process holds on
a file the moment **any** descriptor to it closes. The obvious in-process
refusal (open a second channel, catch, close it) silently unlocks the store
while `FileLock.isValid` still returns `true`. Two sealed tests each covered a
half — `one-holder-per-store-in-one-process` reopened only in-process,
`the-flock-fences-across-processes` never performed an in-process refusal while
the child held. Live proof of the gap:

```text
child BEFORE in-process refusal => REFUSED
in-process second open => :seon.cluster.store/held-elsewhere
child AFTER in-process refusal => ACQUIRED     <-- the fence is gone
```

It was found by hand, which **by this repo's own rule counts as NOT COVERED**;
the interaction is now a standing test (`store_test.clj:223-257`, admitted in
`f399551fd`). This class is not structural and never will be — it is the
permanent reason live falsifiers exist.

---

## 3. The standing rulings that already govern testing

Recorded here so the constitution in §10 restates rather than invents.

- **Properties are the acceptance surface; examples are teaching docs.**
  `README.md:610-620`, `handbook.md:89-92`.
- **The edge-case tripwire is a DESIGN VERDICT.** Catching yourself writing
  point tests to fence edge cases means the design admits states it should not;
  stop and find the construction that makes the class unrepresentable
  (`README.md:615-620`).
- **One regression per failure class, at one choke point** (`AGENTS.md`
  §Testing; `clojure-testing` SKILL.md:188-196).
- **Port ideas (invariants), never tests. A smaller suite is a desired
  outcome.** `README.md:218-223`.
- **Gate cadence:** the edit hook's affected selection per edit; full suites at
  frozen-tree checkpoints only (`README.md:290-293`).
- **Every proof must be claimed by a recurring surface.** A test no runner
  discovers, or a live proof that ran once in a lane, is NOT COVERED
  (`AGENTS.md` §Testing; enforced in practice by
  `fresh-flow-source-is-not-covered-by-bin-test.md`).
- **Sealed-suite construction.** The orchestrator authors schemas + contracts +
  tests; one lane implements and may touch neither; friction is reported, never
  resolved by weakening (`handbook.md:32-54`).
- **Today's decisive evidence on that discipline: 5 of 5 friction stops were
  AUTHOR defects** (`unsettled.md:86-90`). The lanes were right every time.
  `056134cc0` (the author's contradictory defaults assertion), `c2d3a96af` +
  `1d6947069` (the shared-database property), `f399551fd` (the admitted fcntl
  falsifier) are the receipts. **The sealed suite's real product is a
  high-quality bug report about the contract**, and the correct response is to
  fix the contract, never to relax the bar.
- **Fixture load paths are not the live boot path** — schema, acquisition and
  process changes always need the reset-boundary live proof (`AGENTS.md`
  §Testing).

---

## 4. The fresh story as built today — evaluated, not described

### What is right

**`bin/test` is honest and fast.** Filename-derived discovery
(`bin/test:24-31`) means the file's location *is* its registration — no
manifest, no `tests.edn`, no runner list to drift. Exit code is
`(+ fail error)`. Full run 35.6 s from cold with zero prerequisites.

**Fixtures are constructed, not ambient.** Fresh `:memory` store per test with
a random `:id`; the attribute list is written out explicitly
(`run_test.clj:29-52`) so a missing attribute fails loudly instead of silently
widening. Deterministic clock as an explicit input.

**The model-based state machine is the strongest thing in the tree.**
`run_test.clj:451-494` generates command sequences, asks a pure oracle whether
each must commit or refuse, runs the same command against real Datahike, and
treats a disagreement *in either direction* as a counterexample — then
re-asserts durable-fact invariants after **every** command
(`:423-449`), reading the database independently of what the call returned.
This is the correct answer to `clojure-testing`'s own listed trap ("a property
passes but the code is wrong — the property observes only the returned value").

**Live falsifiers are sealed tests, not lane anecdotes.** Real prepl sockets
in this JVM (`boot_test.clj:36-52`, `:148-173` — including the ten-second
ruling asserted in-suite), real child JVMs holding a real flock
(`store_test.clj:179-221`, `:223-257`), a real SIGKILL'd child whose committed
facts must survive (`flow_test.clj:1089+`). The `store_test` child-JVM
readiness loop is exemplary: **the ready file is authoritative and the clock is
only the foreign-process backstop** (`store_test.clj:196-207`) — precisely the
"timeouts are last resorts" ruling, implemented.

**Refusal is asserted as a value with the database checked unchanged**
(`run_test.clj:74-81` + the invariant re-assert), not with a bare `thrown?`.

### What is weak

**W1 — The gate drowns its own signal.** `bin/test seon.cluster.run-test`
prints **15,696 lines** for six tests: 355 `:datahike/write-error` events with
2,320 `clojure.lang` frames, every one an *expected* refusal that the test
asserts as a value. A human or a reviewing model scrolling that log is being
trained to ignore stack traces in the test gate. The old runner had already
solved this exact problem with a five-line targeted trove filter
(`bin/test-writer:60-70`); the idea was not ported with the mechanism.

**W2 — One property is seeded but not reproducible.**
`run_test.clj:526-527` mints `(random-uuid)` inside the property body. Fix: derive
the identity from the generated inputs and the trial, never from a random source.

**W3 — One property shares a database across trials.**
`recovery-preserves-terminal-receipts-exactly` (`run_test.clj:503-589`) wraps
`tc/quick-check` **inside** `with-model-database`, so all 30 trials share one
connection. It is currently safe only because each trial mints a unique
`run-id`/`agent-id` — i.e. safe by the same `random-uuid` that breaks W2. This
is the *identical* structural mistake that made the state-machine property
unsatisfiable (`c2d3a96af`), surviving one file away from its own fix.

**W4 — Child-JVM cost is already 60% of the suite and grows linearly.**
`store-test` 23.1 s and `flow-test` 19.0 s are dominated by three cold child
JVMs at ~7 s each. Today's 35.6 s full run is fine; ten more cross-process
falsifiers is a five-minute gate, and a five-minute gate stops being run
(see R1).

**W5 — Selection is namespace-level and, worse, per-JVM.** The hook widens to
whole namespaces (`script/seon/dev/changed_test.clj:268-316`); the function-level
replacement is specified but explicitly blocked on N5
(`research/test-selection-spec-2026-07-27.md:24-30`). Meanwhile, because the
fixed cost is per JVM (~7 s), selecting three of six namespaces saves almost
nothing over running all six in one JVM.

**W6 — The zero-test blind spot** (R3 above).

**W7 — No cross-rung integration suite exists.** Every fresh suite tests one
namespace. Nothing yet proves B0+B1 compose — that a started cluster opens its
own store under its own flock and that stopping it releases both. The handbook
names composition as a rung metric (`handbook.md:138-143`), but no *test*
claims it, so composition is currently proven only by the orchestrator's live
check, which is not a recurring surface.

**W8 — Dead runners still on disk.** `tests.edn` (kaocha, zero references) and
`bin/test-parser` (broken at HEAD) both advertise themselves as ways to run
tests.

### W9 — the sharpest finding: an oracle inherits its author's blind spot

`research/quality-review-2-2026-07-27.md` (`4bc02d33e`) landed after the
measurements above and is the most important single piece of evidence in this
document: **against the same green 43/186 gate, an adversarial review
reproduced five blockers.** The suite was not merely silent about them — in two
cases it *actively agreed with the defect*.

| blocker | why the green suite missed it | class |
|---|---|---|
| a failed `d/release` propagates but its `finally` invalidates the flock, so a foreign JVM can open a second writer (`src/seon/cluster/store.clj:299-315`) | no test injects a fault into a *teardown* path; every fixture's `finally` is assumed to succeed | **fault injection on release/cleanup paths** |
| held transitions (`heartbeat`/`plan`/`release`/`close`) ignore lease expiry — an expired holder resurrects itself (`run.cljc:230-248`) | **the oracle duplicates the omission** at `run_test.clj:269-281`: it treats holder+epoch as sufficient, so *the generated property agrees with the defect* | **oracle blind spot** |
| a delayed `stop!` kills a replacement instance that started in between (`cluster.clj:336-355`) | no test drives two lifecycle operations concurrently; `boot_test` is strictly sequential | **concurrent lifecycle race** |
| `close-tx` commits `closed-at` even when the agent pointer is absent or foreign — `cond->` omits the retraction instead of refusing (`run.cljc:419-428`) | the revised model "cannot generate an absent or foreign pointer independently" | **relational fence / generator domain too narrow** |
| a `:done` receipt can be upserted back to `:running`; both writes commit | "the model emits receipt commands but never compares receipt facts with its own receipt map" | **receipt-status monotonicity / invariant checker gap** |

Three lessons, and they are the ones that generalize:

1. **A model-based property is exactly as good as its oracle, and an oracle
   written by the contract's own author inherits that author's assumptions.**
   This is the same signal as the 5-of-5 author-defect friction stops
   (`unsettled.md:86-90`), arriving through a different door. The mitigation is
   not more tests — it is an *adversarial reader* of the contract, which is
   precisely what the quality-review lane is.
2. **The property's real coverage is its invariant checker, not its command
   generator.** `invariants-hold?` (`run_test.clj:423-449`) never reads receipt
   status, so no sequence of generated receipt commands could ever have caught
   the monotonicity break. Adding commands to a property whose checker does not
   observe the resulting facts adds runtime, not coverage.
3. **Teardown is untested code.** Every `finally` in every fixture and every
   `release!`/`stop!` in production is a path no test has ever made fail.

Two further review-2 findings belong here: **the Gemini review queue has no
recurring test at all** (its CLI tests are quarry-only under `test-old/`, while
the live unlocked read-modify-write over one pending file can lose concurrent
lanes' edits — `bin/seon-hook:339-381`), and **`bin/issues-index --check`
reports the derived issue index stale**, with fresh-rung notes using
unsupported `closed`/`active` statuses. Both are the same rule as everything
else in this document: *a proof invisible to the owning runner is not
coverage.*

---

## 5. The three surfaces — and only three

### (1) `bin/test` — code correctness

The subject of this document. One runner, source classpath, in-memory
Datahike, exit code is the verdict.

### (2) `src-inspect-ai/` — agent and model evaluation

A real Python package (`pyproject.toml`, `src/seon_inspect/`, `tests/`,
`evals/`), not a scratch harness — 69 tracked files, ~25 modules, ~25 pytest
files. Its shape (`src-inspect-ai/README.md`):

- **Option B, Seon owns the loop**: inspect supplies the dataset and host-side
  scorer; a Seon cluster's agent is a custom `@solver` behind
  `POST /agents/run` (`solver.py`). Inspect never manages the agent's turns.
  **This is the `/solve` bridge**, and it is the only connection point.
- **A cluster is the isolation unit** (`cluster.py`); per-sample cluster,
  restart, fork and release modes are *paused* and fail loudly rather than
  invoking removed commands.
- **Scoring goes through the real Seon oracles** (`oracle_scorers.py`: the bb
  parse/structural server via `bin/oracle-server`, the node eval bundle) behind
  a **fail-loud oracle-liveness gate** — a golden known-good must score
  faithful and a golden known-bad must FAIL before any task constructs. That
  gate exists because *a dead eval bundle silently voided a whole GPU run
  once*. It is the R3 lesson (absence of signal ≠ health), independently
  rediscovered and correctly fixed, in Python.
- **`evaluation-sources.lock.json` + `source_admission.py`** pin exact Gitlink
  revisions of `reference-code/inspect-ai` and `inspect-evals`, nested view
  overlay, provider versions, `uv.lock`, `evals/datasets.lock`, **and the
  committed Seon harness source**; the identity map is stored in the native
  `.eval` log. A mismatch fails before any model or cluster work.
- **`scorecard.py` / `evals/scorecard.jsonl`** is an append-only ledger with
  pass^k / pass@k / flake-excluded reducers and a standing regression alarm
  wired into pytest (`tests/test_scorecard_alarm.py`).

**What the fresh system owes it — one concrete debt.** R0 split the tree but
left the admission list stale: `evaluation-sources.lock.json:45-65` admits
`src` but **not** `src-old` or `test-old`; `tests/test_source_admission.py:126-134`
pins that set; `tests/test_canary_guard.py:19-21` scans `src` and `test` but
not their old peers (recorded as a deliberate downstream follow-up in
`research/src-split-audit-2026-07-26.md:436-453`). So today an eval run's
"immutable identity map" does not cover the classpath-loaded quarry: the
harness can claim a pinned Seon while `src-old` — still on `:writer`'s
classpath and on `bb.edn`'s default paths — changed underneath it. Two honest
resolutions: add `src-old`/`test-old` to the admitted set, or record an
explicit waiver in the lock. Silence is the one wrong answer. A second, smaller
debt: `bin/oracle-server` and the node eval bundle are pod-era artifacts, so
the eval surface still depends on a build the dev loop no longer runs.

**Boundary rule:** a new eval is a new task/scorer **inside** `src-inspect-ai`.
Never a fourth harness, never a drive script, never a `bin/` entry point.

### (3) The Gemini hook review — advisory, per edit

`bin/seon-hook` batches edited files and calls `agy` at most once per
`:interval-seconds 120` (`.claude/seon-hook.edn`). The rubric is **data**:
`:skills ["data-oriented-clojure" "data-modeling" "datahike" "clojure-testing"]`,
each `SKILL.md` re-read fresh at review time (`bin/seon-hook:415-431`) so a
skill edit reaches the very next review with no rebuild, followed by
`docs/conventions.md`. Truncation is marked in-band and the prompt tells the
reviewer never to report a syntax error explained by a truncation marker
(`:487-493`).

Two things follow. First, it has **already caught a real contract defect**: the
first live review found that a nil `observed-epoch` in takeover mode emits
`[:db.fn/cas … ::claim-epoch nil 1]` against a non-nil epoch
(`unsettled.md:34-40`, `tmp/reviews/20260727T112009`). Second — and this is why
the skills matter operationally — **the `clojure-testing` skill is literally
part of every review prompt.** Anything wrong or stale in it is injected into
every review of every edited file. It must stay short, current, and
review-relevant. It is advisory: it never gates, and it never replaces a
falsifier.

---

## 6. Organization as the system grows

### Suite per namespace, plus a per-rung composition suite

`test/<path>/<name>_test.clj` mirroring `src/<path>/<name>.clj` is right and
already enforced by the discovery rule. Keep it.

What is missing is the **cross-rung composition suite**. Prescription: one
suite per *rung boundary*, named for the composition, not the namespace —
`test/seon/cluster/tower_test.clj` for B0+B1 (start a cluster, prove it opened
its own store under its own flock, prove a second start refuses, prove stop
releases both). This is what the handbook's "blocks compose" metric currently
asserts in prose only. Rule of placement: **a test whose subject is one
namespace's contract goes in that namespace's suite; a test whose subject is
that two blocks agree goes in the composition suite for that boundary.** A
composition suite is the natural home for the R8 interaction class.

### The tiered gate

Three tiers, distinguished by *cost*, not by importance. All three live under
`test/` and all three are discovered by the one runner — tiers are a selection
concern, never a second harness.

| tier | what is in it | cost today | who runs it, when |
|---|---|---|---|
| **fast** | pure derivations, schema/bridge, in-memory Datahike properties | 8-9 s per JVM (≈7 s of it Datahike load) | the **edit hook**, per edit, selected |
| **falsifier** | real sockets, real files, child JVMs, real SIGKILL | ~7 s per child JVM on top | the **lane's acceptance** for the rung it proves, and every orchestrator checkpoint |
| **checkpoint** | everything, one JVM | 35.6 s today | the **orchestrator** at a frozen-tree boundary, before any commit that closes a rung |

Mechanism, and the important constraint: **because the ~7 s Datahike load is
paid per JVM and not per suite, selection must batch every selected namespace
into ONE `bin/test` invocation** (which it already accepts —
`bin/test:17-19`). Never one JVM per namespace. And do not build a tier
registry: the tier of a suite is derived from what it does, and the only
mechanical marker worth adding — if and when the falsifier tier exceeds the
fast tier in wall time — is a metadata key on the `deftest` that `bin/test`
can select on. Not before; a dial nobody needs is a mechanism to maintain.

**The N6 final gate is not a suite.** It is `README.md`'s owner-written system
gate — live agents running for an hour, a load test to a *named* wall, a turn
measured with its conditions. No amount of green replaces it.

### Who runs what

- **Hook (every edit):** fast tier, selected, batched into one JVM; plus lint,
  markdown/docstring lint, and the rate-limited Gemini review.
- **Implementation lane (its rung):** the whole sealed suite for its namespace,
  including its falsifiers. A lane reports friction; it never edits a sealed
  test.
- **Quality-review lane (rung boundary):** audits the standing result against
  the contracts — this is what found the two N2 correctness holes
  (`research/quality-review-2026-07-27.md`).
- **Orchestrator (checkpoint / rung close):** full `bin/test` on a frozen tree,
  plus the live falsifier the rung's contract names, plus the reset-boundary
  proof for anything touching schema, acquisition, or process lifecycle.
- **`src-inspect-ai` (deliberate, paid):** never in the edit loop.

---

## 7. Fixture discipline — what makes a fixture honest here

Derived from what actually broke, not from general testing lore.

1. **Construct, never inherit.** A fixture creates its world and destroys it in
   a `finally`. No ambient connection, no global stub, no shared temp path.
   Receipt: the entire R2 genre.
2. **One database per test AND per generative trial.** The connection must be
   created *inside* `prop/for-all`, not around `quick-check`. A pure model
   resets every trial; the world it reasons about must too. Receipt:
   `c2d3a96af` — the property was unsatisfiable by *any* implementation, and
   the implementation lane correctly refused to hack around it. Still violated
   at `run_test.clj:503-589` (W3).
3. **The attribute list is written out.** Explicit is a feature: the list is
   the test's declared surface and a missing entry fails loudly under
   `:schema-flexibility :write` instead of silently widening
   (`run_test.clj:29-52`).
4. **Time is an input, never read.** `t0` + `(at offset)`
   (`run_test.clj:67-72`). A wall-clock read or a tuned sleep as a correctness
   proof is the R4 class.
5. **Every generated input is a function of the seed.** No `random-uuid`, no
   `System/currentTimeMillis`, no unordered set iteration inside a property
   body. A seed that does not make the trial replayable is decoration (W2).
6. **Filesystem fixtures live under project-local `tmp/`, never a system temp
   dir**, and never under a live process directory. Receipts:
   `boot_test.clj:9-10`; `branch-trial-tests-write-into-live-operator-state.md`.
7. **Observe durable facts independently of the return value.** A call can
   return something agreeable while the write did not happen
   (`run_test.clj:423-449`).
8. **A fixture must not be able to write anywhere production reads.** Receipt:
   `dev-eval-program-row-rejection-was-fixture-noise.md` — 27 fixture log lines
   became a phantom production defect in a PRD.
9. **Readiness is an observed event; the clock is only the backstop for a
   foreign process, and its firing is a bug report** (`store_test.clj:196-207`).
10. **A fixture that needs a build is not a fixture.** Receipt: the artifact
    gate.

---

## 8. The porting question

### Old IDEAS that still owe us coverage

From the 34 CLAIM + 30 MIXED namespaces in
`research/pod-test-coverage-2026-07-26.md`, translated into invariant classes
that survive the design. These are ideas to re-derive at the surviving choke
point — **not files to port**:

| owed class | why it survives | where it lands |
|---|---|---|
| **Run custody / fold / receipts** | the crash model still depends on it | **already claimed** by `run_test.clj` (the model property) — the single biggest win of the rewrite |
| **One total value-admission boundary** (hostile/lazy/opaque values realized and bounded at one choke point before the interrupt disarms) | the eval boundary still exists; `render.value-test`'s 71 assertions were the only proof | N3's value-admission package — **highest-risk unclaimed class** |
| **Schema/corpus round trip** (registration fails loudly; complete `:seon.fn`/`:seon.ns`/`:seon.schema` committed and acquired at one basis) | N5's whole subject | N5, plus B2's schema-EDN admission gate |
| **Generative codec/wire totality** with a pinned seed | the database boundary still crosses values | wherever the wire boundary lands; port the *seed discipline* with it |
| **Capability policy** (closed requests, allowlist/SSRF, byte/time caps, flat errors) | `seon.effect` will re-own it | when the first capability family lands — as *one* policy test per class, not per leaf |
| **Bounded pure context derivation** (paging truthful, errors visible, no hidden database I/O in a render) | the derived-view construct is unchanged | N4 |
| **Reactive delivery** (interest wake → equality suppression → latest-wins per consumer) | unchanged by the in-process move | N4 (partially claimed already by `flow_test`'s sliding-mailbox test) |
| **Instrumentation resilience** (a contract whose referenced schemas cannot resolve is rejected as one complete candidate) | one assertion, high value | B2's admission gate |

Also owed, and cheap: **the trove log filter idea** from `bin/test-writer:60-70`
(W1), and **the pinned-seed-in-the-failure-report idea** from
`codec-totality-gate-used-time-seeds`.

### Don't port — these problems do not exist any more

Dead because the mechanism is dead:

- **Promise/async test rails** — `seon.test.async`, `async-fixture`,
  `fixture-support`, settle/timeout helpers, `done` callbacks. No CLJS, no
  Promises. (`archive` verdict B, 5 namespaces.)
- **The self-hosted test runner** (`seon.test.runner`, its probes, its timeout
  probes, its summary persistence). Keep only the idea that evidence attaches
  to the eval that caused it.
- **Pod/Node/Bun platform tests** — `seon.subprocess`, `seon.platform`,
  `seon.log`'s Node file sink, `seon.client-advertisement`,
  `seon.instrument-async`'s `AsyncFunction` wrapper,
  `seon.internal-require-boundary`'s Node source scan.
- **Wire/session/transport tests** — `db-session`, `db-remote-contract`,
  `transport-uds`, `writer-read-decline`. O1 co-location deletes the wire from
  the agent path; there is no session to negotiate.
- **Artifact-gate machinery** — manifest freshness, `SEON_WRITER_ARTIFACT_*`,
  shadow manifest waits, checksum paths, `out/test` bundle ownership, the
  `tmp/test-cljs.lock` protocol and every issue about it.
- **Diffusion/typeahead subsystem suites** (`diffusion.*`, `ai.typeahead`,
  `ctx.typeahead-steps`, `repl.autocomplete`) — opt-in subsystems, not core.
- **Retired agent-facing surfaces** — `my.data`, `my.ns`, `seon.items`,
  `seon.result`.

Dead by decision:

- **The gym, in every form.** `bin/gym`, `bin/gym-scorecard`,
  `test/seon/gym/**`, the EDN scenario corpus, the scorecard trend file.
  Inspect AI replaced it. Live remnants to clear: **`acme/gym/diffusion_gym.bb`
  and `acme/gym/scenarios/*.edn` still exist on disk**, and **`bin/acme
  gym-diffusion` still routes to them** — ACME is TABLED
  (`README.md:725-731`), so both are dead weight; `AGENTS.md:837` already bans
  restoring it; and an **agent-memory pointer still lists three testing
  surfaces including "gym = free smoke"**, which is stale — there are two code
  surfaces plus the advisory review layer, and the gym is not one of them.
- **`tests.edn`** (kaocha) — delete; zero references.
- **`bin/test-parser`** — delete; broken at HEAD, and its own header describes
  the banned pattern ("the inner loop, not a replacement").
- **`bin/plan-state` / generated `state.md`** — already deleted; do not
  reinvent a generated snapshot of gate state.
- **Any "drive script."** If it will run again it is a test under `test/`; if
  it will not, it is a `tmp/` probe.

---

## 9. Gaps, ranked

The ranking is now anchored by live evidence rather than by inspection:
quality-review-2 reproduced five blockers against the same green gate whose
numbers open this document (§4 W9). The first three gaps are the test classes
those blockers name.

1. **Three failure classes have no representative in the fresh suite at all
   (W9).** Ranked first because a lane with a laptop and an hour found five
   blockers the gate could not see.
   - **Fault injection on teardown paths.** No test makes a `release!`,
     `stop!`, or fixture `finally` fail. → the store's fence must be proven to
     survive a failed `d/release`; that is a sealed test, not a review note.
   - **Concurrent lifecycle.** No test drives two lifecycle operations at once;
     `boot_test` is strictly sequential while the contract is generation-fenced.
     → one concurrency property per lifecycle owner, using latches rather than
     sleeps.
   - **Receipt-status monotonicity.** The invariant checker
     (`run_test.clj:423-449`) does not read receipt facts, so the property is
     structurally blind to it. → **extend the checker before extending the
     generator**; a command whose effects the checker ignores buys nothing.
2. **The oracle is authored by the contract's author (W9).** `run_test.clj:269-281`
   agrees with the lease-expiry defect. → make "does the oracle restate the
   contract, or re-derive it?" an explicit item in every rung's quality review,
   and prefer oracles written from the *invariant* ("custody is process AND
   epoch AND live lease") rather than from the implementation's branch shape.
3. **Cross-rung composition has no recurring surface (W7).** Every rung is
   green and nothing proves the tower. The plan's own metric ("blocks compose")
   is currently prose. → one composition suite per rung boundary, starting with
   B0+B1.
2. **The gate drowns its own signal (W1).** 15,696 lines / 355 stack-traced
   expected refusals for six tests. → port the targeted trove filter from
   `bin/test-writer:60-70`. Cheap, immediate.
3. **Two fixture defects in a *sealed* suite (W2, W3).** `random-uuid` inside a
   seeded property, and a shared database across 30 trials — the second being
   the same mistake a blocker issue was filed for one file away. → fix both in
   the next `run_test.clj` contract revision. Note what this says about the
   model: sealed-suite authoring is where defects concentrate (5/5 friction
   stops were author defects), so the author's own suite needs the same review
   rigor as a lane's implementation.
4. **The zero-test blind spot (W6).** A namespace with no `deftest` reports
   green. → have `bin/test` fail when a *selected* namespace contributes zero
   tests, and print the per-namespace test count in the summary.
5. **Child-JVM cost growth (W4).** 60% of the suite already. → treat a child
   JVM as a scarce resource: one per *interaction class*, never one per
   scenario; a class that can be proven with two connections in one JVM must
   be. If the falsifier tier passes the fast tier in wall time, split it out of
   the hook's selection before it slows the loop.
6. **Generative-honesty lint is unbuilt.** `handbook.md:116-125` requires every
   `[:fn]` schema to carry an honest generator and names the lint as part of
   the one admission gate. Malli never validates a `:gen/*` override, so a
   dishonest generator green-washes everything downstream, silently. → build it
   with B2's schema-EDN admission gate.
7. **Function-level test selection is specified, not built** (W5), and blocked
   on N5. Acceptable — but note the measurement above: **at six suites,
   namespace-level selection into one JVM is already near-optimal**, because
   the cost is the JVM, not the suites. Selection precision is a future problem;
   do not spend on it now.
8. **`src-inspect-ai` source admission is stale after R0.** It admits `src` but
   not `src-old`/`test-old`, so an eval's identity map does not cover the
   classpath-loaded quarry. → add the paths or record an explicit waiver.
9. **Dead runners on disk (W8).** `tests.edn`, `bin/test-parser`, `acme/gym/`.
   → delete; git is the archive.
10. **Nothing yet claims the reset-boundary class in the fresh tree.** The
    standing rule says fixture load paths are not the live boot path; B2's
    config→facts and bootstrap-ancestor work is the first fresh change that
    needs it. → name the live proof in the B2 contract, and make it a sealed
    test if it can run in-process.

---

## 10. The testing constitution

*One page. Every line is enforceable and traceable to a receipt above.*

**I. One runner.** `bin/test` is the code-correctness gate. Source classpath,
in-memory Datahike, no artifact, no operator, no lock, no build. A test file is
registered by being named `*_test.clj[c]` under `test/` — there is no manifest.
Adding a second runner, a drive script, or a "fast inner loop beside the gate"
is a defect, whatever its speed.

**II. Three surfaces, and only three.** `bin/test` for code correctness;
`src-inspect-ai/` for agent and model evaluation (a new eval is a new
task/scorer *inside* it); the Gemini hook review as an advisory per-edit layer
that never gates. The gym is dead by decision. There is no fourth.

**III. A gate must not need anything built.** Any prerequisite — an artifact, a
watcher, a compiled bundle, a running operator — is disqualifying. A gate that
is slow to start is a gate that is not run.

**IV. Absence of signal is never health.** When you write a check, ask what it
reports when its subject is absent. If the answer is "fine", the check is worse
than nothing. Zero tests is a failure; an empty log is not a pass; a missing
file is not a clean tree.

**V. Properties are the acceptance surface; examples are teaching docs.** The
property observes durable facts *independently* of what the call returned, and
re-asserts its invariants after every command. A state-machine property with a
pure oracle that must agree in both directions is the default shape for
anything with transitions. **A property's coverage is its invariant checker,
not its command generator** — extend the checker first; commands whose effects
nothing observes buy runtime, not coverage.

**V-bis. The oracle must re-derive the invariant, not restate the
implementation.** An oracle written by the contract's author inherits that
author's blind spot: `run_test.clj:269-281` agreed with the lease-expiry defect
it existed to catch. Write the oracle from the stated invariant, and make an
adversarial reader — the quality-review lane — responsible for asking whether
it did.

**VI. Every generated input is a function of the seed.** Pin it, print it with
the schema key, size, generated value, explanation, and complete shrunk check.
No `random-uuid`, no wall clock, no unordered iteration inside a property body.
A seeded property with a nondeterministic input is not reproducible.

**VII. One world per test, and per trial.** Construct it, destroy it in a
`finally`. The database connection is created *inside* `prop/for-all`. No
ambient connection, no global redef, no shared temp path, and nothing a fixture
writes may be readable by production. Filesystem fixtures live under
project-local `tmp/`, never a system temp dir and never a live process
directory.

**VIII. Time is an input.** A deadline is legitimate only for genuinely
unobservable foreign state; readiness is an observed event, and a firing clock
is a bug report, not a wait.

**IX. Refusal is a result.** Assert the specific refusal rule from the deepest
non-empty `ex-data` in the cause chain, and independently assert the database
is unchanged. Never a bare `thrown?`, never message matching.

**X. One regression per failure class, at one choke point.** Before writing a
test, name the class and the construction that makes it unrepresentable.
**Accumulating point tests around edge cases is a design verdict, not
coverage** — stop and fix the design.

**XI. Cover the interaction, not just the halves.** Two green halves is the
signature failure of this codebase (the fcntl fence). When two mechanisms
constrain each other, the test is the one that exercises both at once.

**XI-bis. Teardown is untested code until a test makes it fail.** Every
`finally`, `release!`, and `stop!` is a path no fixture exercises in anger. A
cleanup path that guards an invariant — a fence, a lock, a lease — must have a
fault injected into it and the invariant re-asserted. Failing closed is the
required direction: retaining a fence is safe, dropping it is data loss. And
any lifecycle fenced by generation or identity gets a *concurrent* test, driven
by latches, never by sleeps.

**XII. Live falsifiers are sealed tests.** Real sockets, real files, real child
JVMs, real SIGKILL — in the suite, discovered by the runner. A live proof that
ran once in a lane counts as NOT COVERED. Falsifiers are expensive: one per
interaction class, never one per scenario, and their cost is reported.

**XIII. Tiers are selection, never a second harness.** Fast tier per edit
(batched into ONE JVM — the cost is the JVM, not the suite); falsifier tier at
lane acceptance and every checkpoint; the full suite at frozen-tree boundaries.
Schema, acquisition and process changes additionally need the reset-boundary
live proof, because no fixture can see that class.

**XIV. Port ideas, never tests. A smaller suite is a desired outcome.** The
health metric is class coverage, never test count. When a mechanism dies its
tests die in the same commit. No lane re-inflates a suite to match an old
number.

**XV. A sealed suite's friction reports are its product.** 5 of 5 friction
stops were author defects. When a lane stops, fix the contract — never relax
the bar, never let the lane edit the test. The author's own suite gets the same
review rigor as the lane's implementation.

**XVI. Every proof is claimed by a recurring surface, and green never outranks
the live system.** Falsify with an observed datom, log line, page, or REPL
result. The final gate is not a suite: live agents really running, load-tested
to a named wall, every number carrying its conditions.
