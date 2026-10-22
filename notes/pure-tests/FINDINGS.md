# Pure tests: findings (worktree session pure-tests, 2026-10-23)

Goal: every test's and check step's result is a pure function of what its reach digest
covers, so a green is reused forever and shared between agents and checkouts; and the full run
(`bin/test --all` + `bin/check 100`) is much faster. Sources: `seeds.md` (a), `hidden-inputs.md`
(b), `config-reads.md` (c), `value.md` and `baseline/` (d, measured), `ticks.md` (the time
dimension). "Status" is this branch's state.

## Hidden inputs

| # | hidden input | tests / steps affected | fix at the producer | size | status |
|---|---|---|---|---|---|
| 1 | **Measured time in a verdict** (cost proofs at 3x of CPU ns; profile-test's estimate within 2x of the wall clock; the unit table's ns within 3x) | every cost proof (~13 sites), profile-test, cost-test/the-unit-table-is-current | owner 2026-10-23: cost is work done. `seon.cost/exact` = ticks, bytes, calls; CPU ns shown, never red. Ticks measured identical at load 21 and 47 (ticks.md); measured by Jolt's one timer owner, `jolt.host/ticks-of` (dry-run, jolt/thread-ticks), once its pin lands | +40 src | **done** (b8696a1, b684729); ticks wait for the pin |
| 2 | **Generative seeds from the wall clock**: our malli fork ignored `:seed`; failures dropped it | mg-check step (~82 contracts), merge-test/a-conflict-value…, reader/print/r3 (literal seeds: pure, but one fixed case set forever) | malli `jolt/mg-seed` (`::mg/=>seed`, `::mg/seed` on failure); `seon.proofs/seed` from the digest + salt; reproduction form printed | malli +4, seon +35 | **done** (cc61a4d; malli 0762fb6b, pin bump is root's) |
| 3 | Unseeded `shuffle`, `random-uuid` in measured data | cost-test unit benches, db-test commit cost | a fixed scramble; `(UUID. 0 k)` | 8 | **done** (cc61a4d) |
| 4 | **Uncommitted edits in `reference-code/` submodules** (tree-id staged HEAD; build and runtime read the files) | every test (`:env`), the build | `seon.fx/pin` + bin/build's `pin`: HEAD+tree when dirty | +25 | **done** (93e5bdf) |
| 5 | The tree checked before a shard's run only | every :tree test | check before and after | 3 | **done** (93e5bdf) |
| 6 | **Config: the whole map in :env**; place keys per root; profile keys (`ai/provider`, `ai/script`) differ between an agent's root and bin/test | every test; agents never share greens with checkouts | shrink2: drop place keys (jolt/brief3). Then option B (root's go): digest only the config keys a test's reached forms name, whole-config readers (root boot, fork server, `cfg/config` handed on, binary-sh) fall back to the whole config, recorded as a fact | +45 | **waits for jolt/brief3** (same functions in program.clj) |
| 7 | HEAD instead of the working tree | program-test/the-same-tree-gives… (`git archive HEAD`: red whenever dirty) | copy the working tree | 3 | **done** (93e5bdf). Left: source pins from the index (fx.clj:77), e2e-loop clones HEAD |
| 8a | **Two shards in one millisecond got one temp path**: Jolt's File/createTempFile was millis+counter, checked then created | any bin/test run (a shard died: "index.lock: File exists") | Jolt jolt/temp-file-unique a2fec377: pid+counter, exclusive create (root's go); 81 of 160 paths distinct before | Jolt +15 | **done** (pin bump is root's) |
| 8b | **The fork server SIGKILLed before it reaped its agents** (lifecycle's `:left`) | bin/check lifecycle | server-step's stop kills and reaps its children; release! half-closes and awaits the exit | +20 | **done** (bcc6337) |
| 8c | **A joined thread still counted by the kernel** (lifecycle's `:os`) | bin/check lifecycle, root-test/root-threads… | Jolt: Thread.join awaits the Mach dead-name notification (lifecycle.md) | Jolt | **asked root** |
| 8d | An inherited `PROTO_GUARANTEES` deciding reconcile-test/contracts-are-checked | that test | read `seon.contract/on?` (config) | 1 | **done** (bb64d2f) |
| 8 | **Shard co-residency**: which namespaces share a process changes with every change (threads left alive, `seon.agent/state`, the test store delay) | lib-test/a-forked-child… (fork refused beside a live thread), any test reading process state | a test leaves no thread or state behind (a shard-end check that names the test that left one); not one process per namespace (startup cost) | S | open |
| 9 | Kept stores in `target/` (`target/runs/test-store`, `target/check-store` seeded once by whichever binary) | root-test, agent-test, every check step | a branch of a seed the current binary makes, keyed by its build | M | open |
| 10 | Inherited environment (guard: `seon.proofs-test/a-test-reads-no-input-its-digest-misses` names every test read of a clock, random, getenv, sleep or timeout: bb64d2f) (`SEON_CHECKOUT`, `JOLT_*_LIB`, `PATH`, `TMPDIR`, `GIT_DIR`) | every test | the runner starts shards with an exact environment | S | open |
| 11 | Vars reached only through data: render fns named by symbol in schemas; code in eval strings (check.clj, e2e.clj) | render tests; check steps | `seon.program/names` takes qualified symbols too; eval strings: steps take :tree or name their vars | S/M | open (program.clj: after brief3) |
| 12 | A child program named as text (`test/build/build/reader.clj`) | root-test/a-build-without-whole-program-inference… | **delete the test** (row D1) | - | proposed |
| 13 | Deadlines as verdicts (red-only): `nc -w`, `deref … 10000`, `ping-ms` | lib-test, agent-test, root-test, model-test; drain | the event they wait for (check-events' pattern) | S | open, red-only |
| 14 | Ports picked then closed (TOCTOU), model-test's `rand-int` port | root/agent/model tests, idle step | bind port 0 in the server and read it back | S | open, red-only |

Build identity (report; check-events owns the stale green): Chez and Jolt are separate
top-level submodules, both pins in every test's `:env`; with #4 a dirty build is named too.
Ticks belong to the compiled code, so they too are covered by those pins.

## Time-only tests and checks (the rule: no assertion load can turn red)

| test / step | verdict | reason |
|---|---|---|
| profile-test/every-touched-function-has-an-estimated-time, 2x block | **deleted** | estimate vs wall clock; red under load (11.7 vs 2.5 ms) |
| cost-test/the-unit-table-is-current | **converted** | ticks and bytes within 1.25x; ns no longer asserted |
| cost-test overrun / slack tests | **converted** | on bytes and ticks |
| profile-test/a-measures-time-is-what-its-thread-does | keep | asserts the CPU clock excludes a wait (20 ms sleep vs < 2 ms of work: 1000x margin), a property, not a bound load moves |
| profile-test/the-clock-is-the-one-every-process-reads | keep | the clock's identity and resolution, not a duration |
| lib-test/the-memory-probe-costs-what-the-process-holds | **open, ask root** | the only catch for a 1000x regression (1.25 s probe), asserted as a wall ratio of medians; no counted stand-in for kernel time |
| check steps (scale, idle, lifecycle, backpressure, threads) | comply | report ms; assert on counts, memory, threads (check-events converted the waits) |

## Where the full run's time goes (measured, `baseline/`, load 12-41 on 18 cores)

Full run **1530 s**: `bin/test --all` 486 s, then `bin/check 100` 1044 s, serially.

- **bin/test** 486 s wall = plan+build ~49 s + the slowest shard 437 s. Shards (wall / CPU / test time): 437/87/433, 110/95/105, 68/62/51, 397/372/335 s, dealt by test count. One test is 351 s of shard 0 (source-test/a-restart…), mostly waiting on a generation build in a fresh worktree. Idle cores: shards 1-2 finished 330 s before the run did.
- **bin/check** 1044 s wall, 718 s CPU, every step serial (sum of step walls 1092 s). Top: swarm 251, mg-check 136 (one shard; one contract, `seon.template/with-merge`, 78 s), scale 74, lifecycle 73 (red), e2e-calc 70, e2e-loop 49, resume 49, robust 43 (1.7 s CPU: waits), backpressure 31, e2e-conflict 30, e2e-hairy 26, e2e-semantic 25, drain 21, fx 20, laws 19.
- Shared setup: 19 tests boot a root each (agent, repl, server tests; 10-20 s each); several program/source/render tests import the whole tree again (the runner already did); every agent turn spends 1.5-4.4 s computing digests for its base (`seon.agent/test-plan`), which every e2e step and swarm pays per turn.

## The slowest tests and steps: what each uniquely catches (detail: value.md)

| test or step | s | uniquely catches | verdict | how |
|---|---|---|---|---|
| source-test/a-restart-catches-up… | 351 | build! → bin/root next on the same store; catch-up after SIGKILL | make cheaper | copy only what the build reads (not 2.1 GB of target/); share e2e-loop's turn |
| swarm | 251 | 20 tasks / 5 vars: conflicts resolve, nothing stuck | make cheaper | `swarm 10 8` (still conflicts) |
| mg-check | 136 | every pure contract generates and holds | make cheaper | bound `with-merge`'s generator (78 s); deal shards by measured ms |
| lifecycle | 73 | stop! leaves nothing across 10 cycles | keep (red: a leak to fix first) | then n=3 |
| e2e-calc | 70 | cancel → stuck naming what it blocks | keep | per-turn digests once per attempt |
| r3.inc-test/incremental-equals-full | 60 | incremental reconciler = full, over histories | make cheaper | drop the always-true `acyclic?` (a third of each step), 150 → 75 steps; seeds now vary with the digest |
| robust | 43 | 11 failure classes | make cheaper | 1e9-tick spins and 1 GB heap → 1e8 and 64 MB |
| agent-test/an-agents-test-run-is-its-proofs… | 42 | (superset in a-wide-reach…) | **delete** after a-wide-reach is green (red at baseline) | D3 |
| backpressure | 31 | paused proc holds a 20k flood | make cheaper | 200 agents → 8 |
| drain | 21 | every accepted request answered at stop! | make cheaper | 32 → 8 |
| fx | 20 | unknown kind typed; stubbed test fork | make cheaper | a benchmark asserting nothing: fewer loops |
| laws | 19 | every catch/clock/resolution site named | make cheaper | one pass per file (later: compiler facts) |
| root-test/a-build-without-whole-program-inference… | 18.5 | Jolt's own build-smoke covers it; Seon never builds that way | **delete** | D1 |
| repl-test/source-answers-a-platform-var… | 11.2 | rows of an-agent-repl-answers… | **delete** (move its one distinct row) | D2 |
| root-test/root-threads-are-what-root-declares | 2.9 | lifecycle's threads check at n=10 | **delete** | D4 |
| db-test/minting-past-the-lease… | 5.6 | | make cheaper | lease 50, not 99,998 ids |
| program-cost-test/load-order-is-one-pass | 15.2 | | make cheaper | build 10k form maps without a db |
| agent/repl/server tests booting a root each (19) | 10-20 each | | make cheaper | one root per namespace and config |

## Deletes (owner: delete what isn't needed, first)

D1 root-test/a-build-without-whole-program-inference-reads-with-tools-reader (covered by Jolt's
build-smoke; tests a mode Seon never uses). D2 repl-test/source-answers-a-platform-var-from-the-program
(duplicate rows). D3 agent-test/an-agents-test-run-is-its-proofs-and-what-its-change-reaches
(a-wide-reach… asserts a superset; delete once that one is green). D4
root-test/root-threads-are-what-root-declares (lifecycle's check, at n=10). Also deleted: the
time-only block of profile-test (above).

## Plan for the speed work, in the owner's order

1. delete D1-D4;
2. shared setup: one root per test namespace and config; the base's digests once per agent attempt; check-store seeded per build;
3. the critical path: bin/check steps run at once (`:seon.config.proofs/jobs` at a time; the steps that measure memory, threads or idleness alone), shards and mg-check dealt by measured work, swarm/robust/backpressure/drain/fx sized to what they prove, the restart test's copy.

Target: well under half of 1530 s. Bound: the longest single step (swarm, ~100 s once cut) plus the serial measuring steps (~150 s), beside the test shards (~150 s once dealt by work).
