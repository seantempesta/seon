---
type: landing
status: landed
created: 2026-09-23
lane: flow-quick-wins
spec: docs/research/agent-platform/flow-usage-audit-2026-09-23.md (120353209) §5 steps 1, 5, 7, 14
---

# Flow quick wins (audit §5 steps 1 and 7 landed; 5 and 14 reported)

## Commits

| commit | step | paths |
|---|---|---|
| `88b52ae8a` | issue notes D1-D6, D8-D10 | nine new files under `docs/seon/issues/` (below) |
| `c2140df4e` | 1 (D1) | `src/seon/cluster/boot.clj`, `test/seon/flow_launcher_error_join_test.clj` |
| `25de4dc81` | 7 (row 3, D8b) | `src/seon/schedule.clj`, `test/seon/schedule_test.clj` |

## Step 1: the launcher graph's error channel joins the fault channel

`seon.cluster.boot/join-launcher-errors!` is called in `stand-cluster-runtime!` after
`arm-agents!`. It calls the existing `seon.flow/join-error-fanout!` with the launcher's
`::flow/started`, the cluster fan-out's `::flow/fault-channel`, and the tag `{}`. The tag
is `{}` because a launcher fault is no run's fault, and `::flow/pid` names the launcher.

- **Before (read-only probe, `default` pid 70720).** The launcher `error-chan` had buf
  count 0 and `.takes` 0: no reader. The probe took 0.38 ms in-form.
- **Contract.** The input and output schemas were compiled against default's carried
  projection registry. The valid instance validates; `{:seon.flow/work-launcher {}}`
  is refused; the output validates as `:seon.flow/channel`.
- **Regression.** `seon.flow-launcher-error-join-test/a-throwing-background-submission-reaches-the-fault-channel`
  uses a real launcher and a throwing `submit!`. The fault reaches the fault channel
  with the identical throwable, pid `::work-launcher` and cid `::io-submission`. The
  submitter's terminal still settles. The join ends after `stop-work-launcher!`.
- **Falsified.** With `join-launcher-errors!` redefined to join nothing, the test errors:
  "The test channel did not publish its required event." That took 20,010 ms, which is
  test-support's default event backstop.
- **Evidence cost of a launcher fault (read-only probe).** `seon.error/prepare` was run
  on a realistic launcher fault whose `::flow/msg` carries the cluster environment. It
  took 201 ms and reported `:seon.error/data-size` 8,388,675 against a blob threshold of
  4096. `data-content` was 431 chars. So each launcher fault costs about 0.2 s of
  evidence measurement over the environment. Launcher faults are rare, but this is a
  cost finding for the committer's owner.

## Step 7: the schedule timer is `async/timeout`

`arm-timer` is now `(take! (timeout d) #(offer! kick ::kick))`. The virtual thread,
`Thread/sleep` and interrupt cancellation are deleted. `cancel-timer` forgets
`::timer-at`. Both functions gained contracts.

- **Probe (default JVM, pure core.async, no cluster state).** The kick arrived after
  60.2 ms for a 50 ms timeout. An offer onto a closed kick channel from the timeout
  callback returned `false` and threw nothing.
- **Contract.** `[:=> [:cat :map [:maybe :inst]] :map]` validates a Date and nil, and
  refuses a string (default's registry).
- **Regression.** `seon.schedule-test/the-schedule-timer-kicks-at-its-instant-through-core-async`
  checks that the kick arrives, and that a closed channel takes no stale kick and
  nothing throws. It does not assert "no thread": virtual threads are invisible to
  `Thread/getAllStackTraces`, so the deletion is proven by the source, not by the test.

## Proof boundary

- Both regressions ran in a plain `clojure -M:test` JVM over `clojure.test/test-vars`.
  That is diagnostic tier: no `seon.test/run` admission, no armed contracts, no
  recording. The working tree gave 13 assertions, 0 fail. HEAD snapshots gave
  `c2140df4e` 7 pass and `25de4dc81` 13 pass. The snapshots were `git archive` copies
  with `.cpcache` and `reference-code` symlinked.
- **Not exercised:**
  - a booted cluster with the join installed (a durable launcher fault through the real
    committer);
  - the schedule proc's transform re-arming through a real agent graph.
  Both need a JVM that loaded these files. `default` must not be adopted, and a scratch
  boot is over the ten-second law (`ready-ms` 106,787 on default).
- `bin/test --ns seon.schedule-test` on `default` (old loaded code, not this edit) gave a
  pre-existing baseline: run `7365f44c382f`, 4 red / 10 fail, 38,909 ms, recording
  refused. Default's code does not include this edit, so that run is no evidence either
  way.
- Stop-path limitation for step 1: `boot/stop!` runs `disarm-agents!`, which stops the
  fault graph, before `stop-work-launcher!`. A launcher fault raised in that gap goes onto
  a fault channel nobody reads. The join's completion is not awaited, which is the same
  shape as the agent graphs' joins (audit row 12). Both close with audit step 3 (one
  drain per graph).

## Step 5 (rows 25-27): not edited, files held

- **Row 25**, `cluster/agent.clj` `await-turn-completion!`: already bounded in the
  working tree by the mcp-and-stop lane's uncommitted hunk (`backstop` is always in the
  `alts!!` ports). Nothing is left for this row.
- **Row 26**, `cluster.clj` (held by publication-work). The exact change:
  1. `loop-handle` gains `:seon.config.agent/turn-completion-backstop-ms` from `dials`.
  2. `disarm-agents!` builds
     `bound {:seon.await/config-attribute :seon.config.agent/turn-completion-backstop-ms :seon.await/config-value <handle value>}`.
  3. It replaces the armer `>!!` + `<!! quiesced` with one
     `(await/await! {:seon.await/bound bound :seon.await/diagnostic {… :seon.error/member ::armer-quiesced} :seon.await/port-operations [[armer-channel {:seon.agent/quiesce quiesced}] quiesced]})`.
     That is one deadline across the put and the take (`await.clj` `await-port-operations`).
  4. It replaces both completion `<!!` (cluster loop, render) with `await!` over
     `[completion]`.
  5. A returned `:seon.await/timeout-error` or `closed-error` is thrown as
     `(ex-info message diagnostic)`.
- **Row 27**, `flow.clj` `stop-error-fanout!`. The exact change: take the same `bound`
  (a new required `::stop-bound :seon.await/bound` member of `::error-fanout-request`,
  held on the fanout, passed by the cluster.clj caller above). Await `completion` and
  `committer-error-completion` through `await!`, one diagnostic member each. A flow.clj-only
  change would have to invent a constant or a config-read seam, which is the same
  reason the existing issue `orderly-stop-completion-joins-have-no-bound.md` recorded
  (Disposition 2026-08-14).

## Step 14 (rows 19-20): not edited, `render/web.clj` held by page-key

The exact change:

1. In `serve!`, return `:seon.render.web/workers workers`. The `:seon.render.web/server`
   schema in `resources/seon/schemas/seon.render.web.edn` gains that member, declared as
   an ExecutorService predicate.
2. The service holds `:seon.render.web/feeds (atom #{})`.
3. In the SSE `on-open`, the feed thread is `.unstarted`, then `swap! conj` into feeds,
   then started. Its `finally` does `swap! disj`.
4. In `stop!`:
   - `http/server-stop!` with the http-kit `:timeout` option, then deref its promise
     under the feed `backstop-ms`;
   - `.shutdown workers`, then `.awaitTermination workers` under the same remaining
     bound;
   - `.join` each registered feed thread with the remaining `Duration`;
   - any thread still alive, or a false `awaitTermination`, throws one `ex-info` naming
     the missing exit event, the bound and the live count.
5. Regression: a served instance with one open tab stops, and afterwards the feed thread
   is not alive and `workers` `.isTerminated`.

## Out-of-scope findings

- `src/seon/cluster.clj` `commit-fault!`'s outer catch builds its last-resort message
  with `(str fault)`. A launcher or agent fault carries the whole environment in
  `::flow/msg`/`::flow/state`, so this string can be megabytes. It is a swallowing
  fallback (it returns a value, and the cause is only positional). The file is held; the
  catch belongs in the swallowed-errors census.
- `seon.error/prepare` measures 8.4 MB for an environment-carrying Flow message (201 ms,
  above). The committer's owner should decide whether a Flow fault's `::flow/msg` and
  `::flow/state` enter evidence at all. No note is filed yet (see the report).
- The MCP `eval_clj` NullPointerException summary had no Seon frame
  (`seon.operator.prepl$io_prepl` only). It did not name the dereferenced form. That is
  ugly output.

## Issue notes (`88b52ae8a`)

D1 `flow-work-launcher-error-channel-has-no-reader.md`, D2
`flow-stop-transition-errors-are-dropped-on-a-closed-error-channel.md`, D3
`one-cluster-holds-two-plus-n-datahike-listeners.md`, D4
`the-wake-router-queries-on-the-writer-thread-and-drops-its-own-fault.md`, D5
`durable-faults-cross-dropping-channels.md`, D6
`agent-arm-and-disarm-hold-one-monitor-across-seconds-of-work.md`, D8
`cluster-threads-outside-flow-have-unobserved-exits.md`, D9
`flow-report-channels-have-no-reader.md`, D10
`the-turn-proc-cannot-answer-ping-or-stop-during-a-model-call.md`. D7 is the existing
open class `orderly-stop-completion-joins-have-no-bound.md`. It was not edited, because
the grant covered new files only.

## Timings

| operation | wall ms | notes |
|---|---:|---|
| `bin/seon status` | 139 | |
| `bin/test --ns seon.schedule-test` on default (×2, baseline) | 49,015 / 38,909 | **over 10 s: defect.** The run was on old loaded code; `returned-and-thrown-handler-errors…` took 6,080 / 6,856 ms, over its 5 s bound |
| standalone JVM, namespace load (working tree) | 14,376-19,064 | **over 10 s: defect**, the class of `a-focused-test-jvm-spends-thirty-seconds-before-its-first-test.md` |
| standalone test run (2 tests) | 139-142 | |
| falsification run (join removed) | 20,010 | the test-support default event backstop firing, as intended |
| HEAD snapshot `c2140df4e`: archive / load / test | 568 / 15,635 / 15 | load over 10 s (same class) |
| HEAD snapshot `25de4dc81`: load / tests | 15,257 / 139 | load over 10 s (same class) |
| `seon.error/prepare` on a launcher fault (probe) | 201 | 8.4 MB measured evidence |
| other probes | 0.4-78 | |

No cache applies to a plain `clojure -M:test` load: it compiles from source. The
`.cpcache` classpath cache hit in both snapshots, since they were linked.
