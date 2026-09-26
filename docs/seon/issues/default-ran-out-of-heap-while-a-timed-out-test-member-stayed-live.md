---
type: issue
status: open
created: 2026-09-26
severity: blocker
tags: [issue, memory, test-runner, heap]
---

# Default ran out of heap while a timed-out test member stayed live

## Sighting (2026-09-26 14:20Z, default at the commit before 5bed888d0)

Root ran test requests one at a time (`tmp/orchestrator/verify-1420.txt`). A
dogfood lane ran tests through MCP at the same time (`word-frequencies` FAIL in
`seon.log`).

1. `seon.call-preparation-test`: passed in 46.8 s.
2. `seon.cluster.host-test`: `a-hosted-agent-proposes-a-merge-and-root-receives-it-on-the-cluster`
   did not exit within its 110.6 s remainder. The result said "thread 1543 remains
   live and keeps its branch until it exits" (`src/seon/test.clj:303-309`).
3. `seon.cluster.mcp-test` got no PREPL output within the bound (14:20:31Z).
   So did `seon.cluster.registry-test` (14:23:01Z).
4. `seon.log:105636-105639`: `OutOfMemoryError: Java heap space`, a
   15,996,101,972-byte dump, and the JVM ended (`-XX:+ExitOnOutOfMemoryError`).

The dump is lost. `-XX:HeapDumpPath=data/heap` was relative, and the JVM's
working directory was the program archive `data/source/<sha>/`. That archive
has no `data/heap` directory, so the JVM wrote a file with that name, and
`start --head` pruned the archive. This part is fixed in c2b757cb3: the
operator passes `<root>/data/heap` as an absolute path at launch.

## What the live JVM shows (pid 7000, 5bed888d0, idle)

- `GC.class_histogram` at 14:3xZ: 1.36–1.45 GB live of a 10.2 GB maximum.
  The largest classes are `Object[]` 326–363 MB, `byte[]` 309–320 MB and
  `datahike.datom.Datom` 200–207 MB (5.0–5.2 M instances). There are 5
  `CachedStorage`, 5 `LocalWriter`, 24 `datahike.db.DB` and 20 `sci.impl.opts.Ctx`.
  The raw samples are in `tmp/platform-boot-heap-opus/histo-{0,1}.txt`.
- No test request has run since the restart, so there is no before/after
  pair yet.

About 8.5 GB was allocated and retained within minutes of step 2. That is a
runaway allocation or retention during the requests. It is not a slow leak.

## Cause, reproduced 2026-09-26 15:51Z (pid 13262, 61d207c3e)

Lane inv-heap-stuck-members-opus ran `bin/test-check default --ns seon.cluster.host-test`
alone under a sampler (`tmp/inv-heap-stuck-members-opus/`). It reproduced the runaway.

| time | heap used | old regions (8 MB) | live `Datom` (post-GC histogram) |
|---|---|---|---|
| 15:51:32 (baseline) | 0.94 GB live | 122 | 3.77 M (151 MB) |
| 15:51:52 | 1.9 GB | 235 | 9.40 M (376 MB) |
| 15:52:13 | 3.8 GB | 455 | 20.3 M (811 MB) |
| 15:52:59 | 7.9 GB | 894 | sampling stopped (7 GB rule) |
| 15:53:34–15:54:28 | 10.2–10.5 GB of 10.24 | 1243–1280 | GC thrash; the JVM survived |
| 15:58 | 1.8 GB live | 226 | 8.69 M (348 MB) |

The retainer is not one leaked world. It is **an unbounded chain of nested test
worlds** made by the install gate.

1. The member's hosted agent runs `(defn proposed-square …)`
   (`test/seon/cluster/host_test.clj:297`).
2. Its install gate (`seon.turn/gate-function-install`, `src/seon/turn.clj:3291`)
   selects `seon.fn/gate-set` of `my.agents.host-probe/proposed-square`. On
   default that answers `[seon.cluster.host-test/a-hosted-agent-proposes-a-merge-and-root-receives-it-on-the-cluster]`,
   the test itself. The edge is `:seon.fn/references`: the test quotes
   `'my.agents.host-probe/proposed-square` as data (`host_test.clj:305`), and
   `gate-set-in` walks references (`src/seon/fn.clj:1786`).
3. The gate runs that host test in the agent's turn
   (`seon.sci.eval/run-candidate-test!`, `src/seon/sci/eval.clj:4623`). The test
   builds a new world (`support/with-database`, `with-host-world`), hosts a new
   agent, and runs the same `defn`. Its `hosted-run!` awaits that run's close under
   the 600 s turn backstop (`seon.cluster.agent/evaluate-source!`, `src/seon/cluster/agent.clj:793`).
   The nested run's gate starts step 2 again, on another Flow thread.

The thread dump at 15:52:23 (`threads-2-155223.json`, `threads-3.json`) shows 24
virtual threads each at `gate-function-install → run-candidate-test! →
with-host-world → hosted-run! → await`. Each holds one fixture world. The SCI arm
cannot interrupt a compiled host test body, so no bound stops the chain. The member
was recorded unfinished at 15:53:31, and its exit was recorded at 15:57:07. I did not determine which nested level failed
first and unwound the chain.

**The missing terminal event** is the hosted run's `:seon.turn/closed-tx`. The run
cannot close before its install gate finishes, and the gate is running the member
that waits for it.

The existing class issue is
[an-agent-definition-gate-runs-the-host-test-that-quotes-its-symbol-nested-in-its-own-fixture](an-agent-definition-gate-runs-the-host-test-that-quotes-its-symbol-nested-in-its-own-fixture.md).
Today's owner ruling settles its model question: gate selection follows call reach,
never symbol mentions. Owners: `seon.fn` (`gate-set-in`, fn.clj:1786) for the edge,
and `seon.sci.eval`/`seon.turn` for running a compiled host test inside an agent's
install gate without a bound that can stop it.

## Second defect: later requests overlap a live body (fixed in `seon.test`)

Before the fix, `admit-run` held only the live body's own test symbol
(`src/seon/test.clj`, the `live` query). Any other request of the cluster was
admitted and built its own world (`clean-request-handle`) beside the live one. At
14:20Z, mcp-test and registry-test did this under the chain above.

Now `admit-run` refuses at the writer with `:seon.test/member-unfinished`, naming the
live test, while any body admitted in this JVM on the cluster has a
`completed-tx` without a `terminated-tx`. `seon.test/run` builds its request
world only after its first batch is admitted, so a refused request builds nothing.
On real data, the new query named the member as-of tx 536871272 (inside the window)
and returned `[]` at head, in 3.5 ms.

Regression:
`seon.test.one-request-test/no-body-of-a-cluster-starts-while-an-unexited-member-holds-its-world`.
A request for the live test and a request for another test are both refused, and
neither writes anything. Both run after the exit is recorded.

## Acceptance

- [x] The retainer is named, with histograms and thread dumps, and the owning path is given as `file:line`.
- [x] A member that does not exit cannot make later requests build more worlds (admission refuses).
- [ ] The gate recursion is removed at its owners (`seon.fn` edge; the host-test executor in the gate). Until
      then, one host-test request can still exhaust the heap by itself.
