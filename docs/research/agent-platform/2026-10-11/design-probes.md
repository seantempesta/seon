---
title: "Design probes P1–P3 for the runtime-follows-db design, and one parity rerun"
type: research
status: evidence
created: 2026-10-11
basis: default on HEAD 48523bb20 (fresh restart); design runtime-follows-db.md at 1b426bc9b; cards filed as tasks at 7f956fb6b
---

# Design probes P1–P3, and one parity rerun

Lane `design-probes`. These probes test [runtime-follows-db](runtime-follows-db.md) §2 (the frontier query), §3 (the
reconciler hosts graphs), §4 (resume from a commit) and the `:stopped` intent. Everything ran from root's JVM REPL on
`default` through MCP `eval_clj`. P1 used in-memory Datahike stores and `datahike.api/with` on the live value.
P2 used scratch branches, which were retired afterwards. P3 used a toy graph. No test sets were run.

## Summary

| Probe | Answer | Changes in the design |
|---|---|---|
| P1 frontier query | **§2's query as written does not run on Datahike.** A rewrite without recursion through negation is correct on 12 scenarios × 3 tree sizes. In two queries it takes 1–4 ms at 50 leaves. | §2's rules are replaced (below); S1 needs new attribute names; S2 is two reads |
| P2 `host!` from a proc; fork from a commit | **Both work today.** | R1 needs no new hosting mechanism; §4's resume to a commit is `fork!` as it is. P2's two proofs would be green on arrival |
| P3 finish the pass, then stop | **Works with Flow's verbs as they are.** `flow/stop` + `join-graph!` (no interrupt) exits after the current transform. `flow/pause` + `ping-proc` acknowledges the end of the pass. | D3 is only "skip `interrupt-turn!` for `:stopped`". P3's proof would be green on arrival |
| Parity: slug-removes-edge-hyphens | **Green in 2 calls.** Merge request recorded, not landed. | none |

## P1: the frontier query on Datahike

### The query as written fails

- §2's `done` rule recurses through negation: `(done ?t)` calls `(not (done ?c))`. Datahike (fork at cb3bce51) throws
  `StackOverflowError` in `datahike.query.lower/rename-branch-vars`. It never terminates. Each failing call allocated
  150–460 MB first.
- `seon.db/q` does not answer that `StackOverflowError` as a refusal. It propagates as a thrown `Error`.
- Datahike's binding check refuses a `not-join` whose inner variable is bound only by a rule call:
  "Insufficient bindings: none of #{?l} is bound in (not [?l …])". So
  `(not-join [?t] (needs* ?t ?n) (not (done ?n)))` cannot be written inline either.
- **Semantic defect.** Assume the rule were fixed. The last clause, "no ended attempt whose end is not `:sent-back`",
  still excludes the target of a send-back, because the target's own earlier `:done` end matches. A sent-back task
  never re-enters the frontier, and everything that needs it deadlocks. In the probe, the literal clause gave `#{}`
  where `#{prove}` was expected. Fix: a `:done` end is already judged by `done`, so only the terminal non-done ends
  exclude a task (`#{:given-back :capped :stalled :cancelled}`).

### The stratified form

Leaf completion is non-recursive. Recursion is positive (`under`, `needs*`). Negation applies only to finished
relations. A parent is done exactly when no leaf under it is open.

```clojure
[[(leaf-done ?t) [?a :seon.task/attempt-of ?t] [?a :seon.task/end :done ?tx]
                 (not-join [?t ?tx] [?b :seon.task/end :sent-back ?tx2] [?b :seon.task/target ?t] [(> ?tx2 ?tx)])]
 [(under ?n ?l) [?n :seon.task/children ?l]]
 [(under ?n ?l) [?n :seon.task/children ?c] (under ?c ?l)]
 [(self-or-under ?n ?l) [(identity ?n) ?l]]
 [(self-or-under ?n ?l) (under ?n ?l)]
 [(needs* ?t ?n) [?t :seon.task/needs ?n]]
 [(needs* ?t ?n) [?p :seon.task/children ?t] (needs* ?p ?n)]
 [(blocked ?t) (needs* ?t ?n) (self-or-under ?n ?l) [?l :seon.task/id] (not [?l :seon.task/children _])
               (not (leaf-done ?l))]]
;; eligible leaves (one query) minus the blocked set (one query, the rule evaluated unbound)
[:find [?t ...] :in $ % :where [?t :seon.task/id] (not [?t :seon.task/children _]) (not (leaf-done ?t))
 (not-join [?t] [?t :seon.task/execution ?i] [(contains? #{:held :stopped :cancelled} ?i)])
 (not-join [?t] [?a :seon.task/attempt-of ?t] [?a :seon.task/end ?e] [(contains? #{:given-back :capped :stalled :cancelled} ?e)])]
[:find [?t ...] :in $ % :where (blocked ?t)]
```

The attribute names above are placeholders. The probe used `:probe.task/*` and `:probe.attempt/*`; see S1 below.

Use two queries. Written as one query with `(not (blocked ?t))` the result is the same, but it costs 75–93 ms and
118–151 MB at 50 leaves whenever many leaves are candidates. Datahike evaluates the negated recursive rule once per
bound `?t`. `blocked` evaluated unbound costs 0.9 ms. Set difference in Clojure is free next to that.

### Correctness and cost

Tree: subject → design; prove needs design; N fan-out leaves need prove; b needs prove and has a nested fan-out
(schema, then M bodies that need it); integrate needs every fan-out leaf and b; merge needs integrate and is
`:held`. One tree has 50 leaves (N=23, M=22), one has 500 (N=250, M=245), and one has 50 open leaves beside
10 closed 50-leaf trees. The 12 scenarios, with the frontier expected in each:

| Scenario | Expected frontier |
|---|---|
| fresh | `#{design}` |
| design done (chain) | `#{prove}` |
| prove done (fan-out) | N leaves + b's schema (an inherited need is now done) |
| schema and one fan-out leaf done (nested) | N−1 + M |
| all done but integrate (join; b counts via its children) | `#{integrate}` |
| integrate done, merge held | `#{}` |
| merge released | `#{merge}` |
| a fan-out leaf ends `:sent-back` → prove | `#{prove}` |
| prove has an open attempt | `#{prove}` (the roster splits running from launch) |
| prove ended `:given-back` | `#{}` (a problem for root, not a relaunch) |
| one fan-out leaf `:stopped` | N−1 + schema |
| all done | `#{}` |

All 12 scenarios are exact at all three sizes. The stratified form with the literal last clause fails only the
send-back scenario. Times are the two-query frontier, per call. MB is allocation on the calling thread.

| Tree | ms (range over scenarios) | MB |
|---|---|---|
| 50 leaves, in-memory store | 0.8–4.0 | 1.1–3.9 |
| 50 leaves, `d/with` on the live value (607,106 datoms, 715 tasks) | 0.8–3.8 | 1.2–4.1 |
| 500 leaves | 4.5–76 (76 when about 500 leaves are blocked) | 7–33 |
| 50 open + 500 closed leaves | 4.5–7.8 | 7.7–10.5 |

- **The 50-leaf budget holds:** 4 ms worst case against 50 ms.
- **The cost grows with every task row, not only with the open trees.** The query starts at `[?t :seon.task/id]`, so
  closed trees are scanned. About 0.008 ms per closed leaf; the 715 tasks today add about 6 ms.
- At 500 leaves the cost follows the size of `blocked` (`needs*` × `self-or-under`). If big trees become ordinary,
  derive `blocked` only for leaves whose needs are open, as a semi-join.

### What S1 and S2 must change

- **`:seon.task/steps` is taken.** It is today's component set of `:seon.step/step` (`seon.task/steps-view`,
  `:plan`/`:append`/`:done` ops). S1 must either retire that step model in the same cut or take a new name for child
  tasks. The probe used `:children`.
- **The end is not on an attempt today.** `:seon.task/end` is an attribute of the end message to root
  (`seon.turn/end-task-call`; `seon.message` schema). §2 reads it from the attempt (`[?a :seon.task/end …]`).
- **`:seon.task/assigned` is a handle value, not a ref.** The query joins through `[?t :seon.task/id ?h]`, or S3
  writes the attempt with a ref to the task.
- `seon.db/q` refuses a database value without a carried schema projection, so these rules run through `seon.db/q`
  only after S1 publishes the attributes. The probe ran them through `datahike.api/q`. Engine and indexes are the
  same; only the guard is missing.

## P2: hosting from the reconciler's pass; forking from a commit

### `seon.cluster.host/host!` from an `:io` proc's transform

**Works.** Setup:

- A scratch parent branch P stood in for the cluster: forked from `:cluster-default`, acquired, its execution merged
  into root's handle.
- A child branch C was forked from P.
- A toy graph (`seon.flow/start-graph!`) had one `:io` proc. Its transform called `host!` for agent `probe/hosted`
  on C, on the proc thread `seon-flow`, in 107 ms.

Results:

- The hosted agent answered an ordinary eval request (`(+ 1 2)`, turn 6d42cb00111b).
- The toy graph was then stopped and `join-graph!` returned nil. The host stayed in P's roster and answered a second
  eval request (`(* 6 7)`, turn 4b773058d45e).
- `retire!` returned nil. Both branches were retired, and no `probe-*` branch remained.

Why it holds:

- Every graph gets its own executor (`seon.flow/graph-executor`, one platform thread per proc). A graph started inside
  another proc's transform does not share that proc's executor, so it outlives that graph.
- The cluster armer already does the same thing today: `armer-step` → `arm!` → `arm-graph!` acquires a context and
  starts an agent graph on the armer's own proc thread.
- `host!` adds two `home-call` transactions and a branch armer graph. The armer's init registers its route.
  Nothing there locks against the calling proc. The cluster route sees the new `:seon.agent/id` and offers into the
  armer's sliding-1 channel, which never blocks.

What this changes: §3's rule "only the reconciler starts graphs" is a single-writer rule, not a thread-safety one.
It stays, for the dual-writer reason the design gives. The cost to budget is about 100 ms of armer time per launch
plus the context acquisition. During that time the armer arms nothing else, so `max-running` also bounds the
armer's pass.

### `seon.cluster.registry/fork!` from a historical commit

**Works.** The source was main's commit 6acb4e5c… at t 536871199; main had moved 4 transactions past it. `fork!`
took `{::from <commit-uuid> :seon.db/db (d/commit-as-db …)}` and ran in 64 ms.

- The branch's parent commit is the historical commit. Its t is 536871200: the source t plus its own fork-entity
  transaction.
- None of main's later transactions are on the branch.
- The fork entity has `:base-t 536871199` toward `:cluster-default`.
- The t 536871200 repeats main's own t 536871200, as §4 says ("t repeats across forks"). Resume code must key by
  commit id, never by t.
- Retired cleanly.

`fork!` from a commit requires the source value (`:seon.db/db`) to name the parent branch. §4's "resume to a commit"
must hand it `commit-as-db`, which the caller releases.

## P3: finishing the current pass, then stopping

Read `clojure.core.async.flow.impl/proc` (reference-code/core.async):

- The control tap is buffered (10) and read with `:priority true` between transforms. It is also read while outputs
  are put (`send-outputs`).
- A transform is never preempted. `pause` and `stop` are commands that take effect at the next read.
- `ping` is answered only from the loop, so after a pause it comes back when the current pass ends.

Toy `:io` graph, every transform `Thread/sleep 400`. Times are in ms from the start:

| Step | Observed |
|---|---|
| inject `:a :b`; `flow/pause` at 110 while `:a` runs | `pause` returned at 111 (a command, not a join) |
| `flow/ping-proc :turn 5000` | answered at 414, status `:paused`, count 1: the end of `:a` (411) |
| 600 ms paused | `:b` did not run (1 pass) |
| `flow/resume` | `:b` ran (1022–1429) |
| inject `:c`; `flow/stop` at 1642, mid-pass | `seon.flow/join-graph!` returned nil at 1942, when `:c` ended (1942) |

What this changes:

- **`:stopped` = `flow/stop` + `join-graph!` under `:seon.config.agent/turn-completion-backstop-ms`, without
  `interrupt-turn!`.** That is `seon.cluster.agent/stop-graph!` minus one line. The bound must cover a whole pass:
  the dial's description already adds the provider attempt timeout or the form's evaluation limit. A pass longer
  than the bound gets today's typed join timeout, and the entry is kept.
- **`:paused` = `flow/pause`, then `flow/ping-proc` with the same bound,** which acknowledges the end of the pass.
  The paused graph stays pingable.
- **Keep `:compute` transforms out of this:** Flow's `.get` under `compute-timeout-ms` (5 s) throws but leaves the
  future running.

## Parity rerun: `seon.competency/slug-removes-edge-hyphens`

This repeats [parity-probe](parity-probe.md) run 2: `seon.competency/start-run!`, `await-runs` (1 s poll, 240 s
deadline), `grade`. The setup:

- Agent `design-probes-slug-1`; settings `{:seon.config.ai/no-provider false :seon.config.run/max-episode-runs 15}`.
- DeepSeek `deepseek-flash`. This was a deliberate paid run.
- `:seon.config.root/land? false` was already effective on HEAD.

| | This run | Run 2 |
|---|---|---|
| Start (setup, assignment, wake) | 4.25 s | 1.4 s |
| Launch to settled `:done` | 5.1 s (model wall time 2.6 s) | 7.4 s (1.8 s) |
| Calls / to green / forms | 2 / 2 / 2 | 2 / 2 / 3 |
| Tokens prompt (cached) / completion | 3,021 (1,280) / 162 | 3,006 / 142 |
| Errors agent / platform; repeated forms | 0 / 0; 0 | 0 / 0; 0 |
| Proofs | `{:green 1}` | `{:green 1}` |

What root sees:

- The end message 843fc35df70a reads `:done`, with `:seon.task/merge "b8fed1a10e38"`.
- Merge task b8fed1a10e38 is `:merge`, with the intent "Merge branch agent-79e5eb13993d's change for 889ea52da177."
- Proposal 0f20e11c07a4 records commit 6acb4f22…, proved `[[seon.competency-test/t5-slug-removes-edge-hyphens <digest>]]`
  and run b2316856232c.
- Admission is closed. There is no `:seon.task/landed`, and no landing commit appeared in git (HEAD moved only by
  other lanes' commits).
- Branch `:agent-79e5eb13993d` is kept for review.

The start was 3× run 2's. The whole request allocated 4.2 GB (`:seon.cost/alloc-mb 4217`) for a 2-call run.

## Heap and cost (old gen after `System/gc`)

| Point | Old gen | `seon.cache` |
|---|---|---|
| Session start (fresh restart) | 368 MB | 115 MB |
| After P1–P3 | 731 MB | 33 MB |
| After the parity run | 786 MB | 61 MB |

- Other lanes were active on default at the same time (`bin/lane status`: live-root-memory, astra-one-system and
  others), so not all of the +418 MB is this lane's.
- Every probe branch was retired. Nothing here holds a host, a context or a graph.
- The class histogram at the end showed 1.15 M `datahike.datom.Datom` instances against 607 k datoms on main.
  Retained index nodes, not `seon.cache`, are the growth to look at (owner: the live-root-memory lane's task
  `seon.test/a-full-tier-on-one-jvm-retains-what-its-members-built`).

## Findings for the owners

1. **The §2 rules are not runnable on Datahike.** Use the stratified form above; S2 owns it. The literal clause's
   send-back deadlock is fixed in the same form.
2. **The `seon.db/q` read boundary lets `StackOverflowError` escape** instead of answering it as a refusal.
3. **Datahike's planner evaluates a negated recursive rule once per outer binding,** 75–93 ms and 118–151 MB at
   50 leaves. This is an upstream issue for the datahike fork; S2 avoids it with two reads.
4. **The P2 and P3 cards' proofs would be green on arrival**
   (`seon.cluster.host-test/the-reconciler-hosts-a-branch-agent-from-its-own-pass`,
   `seon.cluster.registry-test/a-fork-from-a-historical-commit-reads-that-commit`,
   `seon.cluster.agent-test/a-stop-intent-finishes-the-current-pass-before-the-graph-exits`). By the task rule, a
   green proof proves nothing. Retire those three cards, or move their proofs to R1, R4 and D3, whose behaviour is
   new. P1's cost proof is meaningful only once S1's attributes exist.
5. **S1 collides with the live `:seon.task/steps`.** `:seon.task/end` is on the end message, not an attempt, and
   `:seon.task/assigned` is a value, not a ref. S1 and S3 must settle these three first.
