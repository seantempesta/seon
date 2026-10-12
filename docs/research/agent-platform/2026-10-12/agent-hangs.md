---
title: "Lane agent-hangs: the ten round hangs, per test"
type: research
status: evidence (one-test check JVMs, `bin/seon check PATHS --test NS/VAR`, HEAD f70e48517 to d50a30d21 plus this lane's paths; default not queried, not stopped)
basis: "Survey docs/research/agent-platform/2026-10-12/survey/agent-test-hangs.md (9862b2844). Times: before = the survey's alone runs (body ms); after = the check's `run` line (request ms, namespace load included) of a one-test check."
---

## For root

Cause verified for the agent-test fixtures: the fixture's hand-built agent (`agent-entity`) names no cluster, so with
an assignment `seon.turn/next-agent-work` answers `seon.config/agent-cluster`'s refusal ("Agent parked names no
cluster ... its branch holds 2 cluster config rows") and the loop never opens a turn. That is why a bare
`assign-task!` gave 61.6 s (round-waste.md). Conversion: `create-working-agents!` (canonical
`seon.cluster.agent/creation-tx` naming the cluster, then `seon.test-support/assign-task!` per agent).

| Test | Cause | Disposition | Before | After |
|---|---|---|---|---|
| `agent-test/park-wake-test` | no assignment; agent without cluster | (a) converted; task `wake-fixtures-observe-real-terminal-events` retired (proof green), its wall-clock `profile/measure` wrapper removed (the runner holds the bound) | 26,001 ms, red | 5,124 ms, green |
| `agent-test/pause-during-in-flight-call-test` | same; and `seon.run/complete` ends the held task, so the second wake was never answered | (a) converted; reply is `seon.run/wait` (the pause, not the task's end, is the subject) | 64,963 ms, red | 4,366 ms, green |
| `agent-test/restamp-recovery-test` | same | (a) converted | 25,700 ms, red | 4,924 ms, green |
| `agent-test/release-joins-every-owned-graph-before-it-closes-the-branch` | no assignment | (a) `assign-task!` on the child | 25,040 ms, red | 3,837 ms, green |
| `agent-test/stop-flow-interrupts-an-in-flight-turn-and-records-it-interrupted` | no assignment | (a) assigned; now a fast red: two error facts after the stop, which is exactly the open task `seon.turn/a-stop-during-model-context-assembly-records-errors` (this test is its proof) | 24,710 ms, hang | 3,690 ms, red (task) |
| `agent-test/n-agent-parallel-turns-property` | no assignment; agents without cluster | (a) converted; (c) twelve trials are proportional work: `:seon.test/long` 12,000 ms, measured 8,001 ms | 120,561 ms, red | 9,691 ms, green |
| `agent-test/wake-routing-conservation-property`, `routing-conservation-waits-for-terminal-evidence` (and their vacuity guard `routing-proof-rejects-an-empty-production-subject`) | see item R below | **deleted** with `routing-trial`, `create-generated-agent!` | 25,000 ms each | gone |
| `blob-publication-test/publication-and-collection-are-exclusive-in-both-orderings` | (c) `populate-published-root!` published the whole checkout (44,846 ms) and five `registry/collect!` marked it (about 3 s each) | fixture is a fresh file store holding only what collection reads: one synthetic digest attribute plus the program's own `:seon.blob/digest`, `:seon.config.db/validation-node-limit`, `:seon.config.db/write-time-limit-ms` declarations | 70,906 ms, red | 2,692 ms, green |
| `armed-test/a-message-committed-during-boot-start-is-conserved` | item A below | **deleted (owner)** | 73,228 ms | gone |

Same class, same file, fixed alongside: `stop-flow-has-a-declared-loud-turn-completion-backstop` (20,230 ms red →
4,371 ms green, assigned), `wait-closes-in-terminal-tx-test` (20,168 ms red → 5,007 ms green, converted),
`concurrent-start-and-release-never-deadlock` (error: its latch and its start fn were both named `start`, so
`.countDown` hit the fn; latch renamed `begin`; 3,355 ms green). The whole namespace plus the long members ran in one
check: 25 members, 38,080 ms, no member unfinished.

## Remaining items (one short lane each)

**R. Routing tests: deletion confirmed by the owner.** `routing-trial`'s subject is db<->flow starting a created agent
because it has a message (`seon.cluster.agent/agents-to-start`, creation as a `:seon.wake/starts` wake). Verified
by run: without an assignment the agent has no work (CX-2, 7e39aeed3); with a hand `:held` assignment
`next-agent-work` answers `:open` but `seon.cluster.agent/plan` (c15d1eace, R1 slice 2) plans `:start-flow` with
`:seon.task/execution :paused` for that attempt, and db<->flow took only its prime (one plan message, no started
graph). So no production path answers a newborn agent's message; M13 deletes the second starter. Message
conservation to started agents stays proven by `n-agent-parallel-turns-property`. **Owner:** deletion accepted. The question was: accept the
deletion (named commits 7e39aeed3, c15d1eace), or keep a version through `:running` task attempts, which would be a
db_flow_test case, not a routing one. Side finding: db<->flow in that fixture saw no wake after its prime although
`wake/route!` should offer `plan-channel` on `:seon.agent/id` assertion; not traced. File:
`test/seon/cluster/agent_test.clj` (already deleted in this commit).

**A. Armed boot-window message: deleted (owner).** `armed-test/a-message-committed-during-boot-start-is-conserved` and its
task `seon.cluster.armed-test/a-boot-window-message-is-observed-as-a-delivered-wake` are gone (owner 2026-10-12:
boot-window delivery is covered by deriving work from facts after boot).

**P. Published-root cost.** Task `seon.test-support/a-published-root-costs-a-template-clone-not-a-publication`:
`populate-published-root!` costs about 45 s per call, 19 callers; every armed-test member is over its bound before
its own work. File: `test/seon/test_support.clj` (held by fixture-hour).

**E. Event bound vs member bound.** Patch in `agent-hangs-patches.md`: `await-event!` defaults to the awaiting test's
declared bound via one owner `seon.test.runner/declared-limit-ms` (extracted from `duration-failures`). Not run (file
held). The remaining-bound version needs the runner: task text there for `resources/seon/tasks/seon.test.edn`
(held by restart-safe). Files: `test/seon/test_support.clj`, `src/seon/test/runner.clj`.

**H. hot-reload-var-test.** Task `seon.cluster.agent-test/hot-reload-observes-var-built-procs-without-redefining-the-turn-step`.
Agent creation fixed (canonical creation-tx); its hand-built control graph never reports an idle pass (20,079 ms),
and it `alter-var-root`s `seon.turn/step` in the shared JVM. File: `test/seon/cluster/agent_test.clj`.

**W. Writer NPE.** Task `seon.db/an-undeclared-write-time-limit-refuses-by-name`: on a store declaring a program but
not `:seon.config.db/write-time-limit-ms`, `seon.db/transact-call` throws a NullPointerException (`branch-dial`
nil) instead of a named refusal. File: `src/seon/db.clj`.

**X. Pre-existing reds untouched:** `agent-test/a-function-without-a-contract-is-admitted-and-stored` (fail line
416), and the blob test's two `with-redefs` of `datahike.gc-guard` permit functions (no public observation seam;
needs a fork hook, a restart-batch change).

## Notes

- Test-only lines: agent_test.clj net about −250 (routing trial and three deftests gone).
- Check logs were in the session scratchpad; the numbers above are copied from their `run` lines.
- `bin/seon --help` answers an "Unknown option" error map rather than help (`bin/seon help` works).
