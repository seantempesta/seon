---
type: design
lane: async-commit-design-opus
date: 2026-09-26
status: measured; owner decision needed (question at the end); no src edits
---

# One durable commit per form: synchronous, asynchronous, or grouped (2026-09-26)

**What exists.** Each MCP/agent form costs two durable Datahike commits:
- the run request (`submit-source!`, src/seon/cluster/agent.clj:660-753), written only so the agent's Flow graph can derive the work from the database after a payload-free wake;
- the settlement (`settle-batch!`, src/seon/turn.clj:3734).

At about 64 ms each, they are 128 of the 155 ms warm cost
(docs/research/agent-platform/landing/perf-eval-overhead-2026-09-26.md). The owner chose one durable commit per form, then asked whether that commit can be asynchronous ("fire and forget").

**The approach.** Datahike's writer is already a two-stage pipeline:
- a *processing loop* applies each transaction to the previous **uncommitted** value (reference-code/datahike/src/datahike/writer.cljc:123, :200);
- a *commit loop* drains every queued report and persists only the batch's last value, once (writer.cljc:229, :234, :245).

So an asynchronous commit and a group commit are both Datahike mechanisms. Seon does not need a second queue or batcher. The design choice is only **when the caller is answered**:
- after the commit loop persists the transaction (A);
- after the processing loop has applied it (B);
- the same as B, with a wider commit window (C).

## Broken first

1. **Datahike offers no "applied" answer.** A caller gets its report only after the commit (writer.cljc:257-264). The applied value exists in the processing loop but is never delivered. Variant B therefore needs a small fork seam. The alternative is `d/with` on the caller plus a fire-and-forget transact. That applies every transaction twice, and transaction functions, `txInstant` and tempids can diverge, so it is rejected.
2. **A failed commit is terminal for the writer** (writer.cljc:266-284: "A failed commit is terminal even when the cause is an Exception"). The queues close, every staged report is dropped, and every later transaction gets `:writer-shut-down`. The owner's framing was "a commit failure is recorded and delivered beside the next reply". Under B that fault **cannot be recorded**, because its store just failed. By the existing law ("If storage fails, panic in both modes") it becomes a panic. The agent's next form returns the writer error, and results it has already been shown are not stored.
3. **Group commit adds nothing for one sequential agent.** An outside LLM agent's think time is seconds, and an MCP round trip is at least 13 ms of bridge time. Both exceed the commit, so every form gets its own commit anyway (measured below: gap 25 ms gives 40 commits for 40 forms). Forms only coalesce when they arrive faster than commits:
   - several forms in one pass, which `settle-batch!` already settles in one transaction;
   - many concurrent agents, which Datahike already coalesces natively under A (measured: 4 agents gave 2 forms per commit).
4. **The scratch store is lighter than default.** A scratch commit costs about 19 ms and 4.4 disk forces. Default's commit costs about 64 ms and 12-16 forces: it writes 4-8 index nodes of 120-175 KB against this fixture's fewer, smaller nodes. The *ratios* and mechanisms below transfer. The default-scale columns are arithmetic on the landing's measured stages, not a re-run on default: the load cap forbids commit loops on default.
5. **Default was replaced during this lane** (pid 31405, then 32075 at 19:45:04Z, HEAD 36a3714b4 "HEAD c1c2bdbcd could not boot"). My last probe had returned normally at about 19:44:42Z, and the replacement was root's restart, not this probe. Clean-up was done in the new JVM:
   - the scratch store lived under the JVM's working directory, `data/source/b4b90d40…/tmp/async-commit-design-opus/`, not the checkout's `tmp/`; I deleted it by hand (no symlinks);
   - the probe namespaces are removed.

## Method

The probe is `tmp/async-commit-design-opus/acd_probe.clj`; the raw numbers are in `results.edn` beside it. It ran in a throwaway namespace in default's JVM (pid 31405, source snapshot b4b90d405), one bounded form at a time.

- **Store.** The scratch file store used Seon's own `seon.cluster.store/datahike-configuration`: branching factor 4096, fused roots, `keep-history?`. It held about 200k base datoms. Setup was a one-time fixture load of 3.75 s over 20 commits; the store was deleted afterwards.
- **Settlement shape.** Each settlement was a run row plus an evaluation row: about 13 datoms and about 420 B of shown text.
- **Writer path.** Transactions were raw `d/transact!`. Seon's writer-side validation (about 5 ms per transaction on default) sits in the processing loop in every variant, so it counts in the reply path for A and B alike.
- **Forces.** `jdk.FileForce` events were counted by a programmatic JFR `Recording` with zero threshold, filtered to the scratch path.
- **B's "reply" time.** A `[:db.fn/call marker]` appended to the settlement runs in the processing loop, and its timestamp is the moment B would answer. The same marker checks read-your-writes: does the pending value it receives hold the *previous* form's evaluation, and does `@conn` (the committed value)?
- **Durable time.** Taken from a `d/listen` callback, which fires after the commit.

## Measurements

Scratch store, 40 forms per row, milliseconds.

| variant | reply p50 / p95 | commits / forms | forces / form | durable lag p50 / p95 | pending read sees previous form |
|---|---|---|---|---|---|
| A0 today: two awaited commits | 38.1 / 55.9 | 80 / 40 | 8.5 | 0 (the reply *is* durable) | n/a |
| **A** one awaited commit | 19.1 / 36.0 | 40 / 40 | 4.4 | 0 | n/a |
| **B** async, forms 25 ms apart | 0.48 / 0.83 | 40 / 40 | 4.5 | 19.2 / 38.3 | yes (committed value lagged 4 / 40) |
| **B** async, 5 ms apart | 0.28 / 0.39 | 13 / 40 | 1.7 | 32.0 / 60.7 | yes (committed lagged 39 / 40) |
| **B** async, burst (0 ms) | 0.20 / 0.31 | 2 / 40 | 0.7 | 80.4 / 84.8 | yes (39 / 40) |
| A, 4 concurrent agents (native coalescing) | 71.1 / 94.0 | 20 / 40 | 4.5 | 0 | n/a |
| **C** `:commit-wait-time 25`, one sync agent | 49.1 / 65.9 | 20 / 20 | 4.3 | 0 | n/a |
| **C** wait 25, async, 5 ms apart | 0.28 / 0.54 | 5 / 40 | 1.1 | 76.2 / 113.1 | yes |
| **C** wait 25, 4 concurrent sync agents | 55.6 / 91.2 | 11 / 40 | 1.5 | 0 | n/a |

What the numbers establish:
- **B is correct at the writer and hides the commit entirely.** The reply is the apply (0.2-0.8 ms here, plus Seon's roughly 5 ms of validation on default).
- **Every transaction sees the previous form's writes.** The processing loop chains the uncommitted value. The committed `@conn` does not, so any *reader* must be handed the pending value.
- **B saves no disk work at a realistic cadence.** Forces per form equal A's whenever forms are spaced wider than a commit.
- **C trades latency for forces.** One synchronous agent pays the window (19 → 49 ms). Four concurrent agents gain (p50 71 → 56 ms, forces per form 4.5 → 1.5).

### Default-scale per-form estimate

This is arithmetic on the landing's measured stages, not a re-run.

| variant | per-form reply | disk forces / form | how derived |
|---|---|---|---|
| A0 today | 155 (measured) | 24-32 | landing |
| **A** | ~87; with the landing's option-1 trims ~60-70 | 12-16 | 155 − 68.5 (submission transaction) |
| **B** | ~30; with trims ~12-18 | 12-16 at agent cadence | 155 − 68.5 − 62.9 + ~6 apply (validation + `with`) |
| **C** | A plus window if synchronous; B's if asynchronous | 12-16 / forms per window | only concurrency changes it |

The landing quoted "60-70 ms" for A without trims. Its own table gives about 87, and 60-70 needs its option-1 trims as well.

## The three variants

### A. One synchronous durable commit per form

**Mechanism.**
- The run request travels to the agent's Flow graph as a value. The wake stops being payload-free for source submissions: the request map and a reply port ride the mailbox.
- The request is replaceable, because the caller holds it and awaits. The mailbox buffer states that loss policy: bounded, and a full mailbox is refused as busy. The sliding-1 `CountedSlidingBuffer` (agent.clj:108-135) would silently drop a payload, so it cannot be used here.
- `next-agent-work` (turn.clj:2950) admits the submitted request as the `:open` situation.
- The run row (`turn/system-run-tx`, turn.clj:775), the evaluations and the close are written in the one `settle-batch!` transaction (turn.clj:3734).
- The caller is answered from the settlement's `:db-after`.

**Guarantee: shown ⇒ stored.** Every shown evaluation, its exact shown text and any program row are durable before the caller sees them.

**Crash semantics.**
- A crash before settlement loses a request nobody was answered for: the MCP call fails at its bound.
- There is no open turn at boot, so `recover-call` (turn.clj:1719) has nothing to close for source runs.
- **Sacrifice relative to today:** a form that crashes mid-evaluation leaves no evaluation record, even when it had already made its own durable writes or external effects. Today the durable request lets boot mark it interrupted.
- A long-running form's "running" state is visible only in the proc's ping state, not in the database.
- Model-provider turns keep their durable opening. They run for seconds and need interrupted recovery, so A applies to source submissions only.

**Read-your-writes.** Trivial. The next form begins after the commit, so `@conn` holds it.

**Failure delivery.** Unchanged: the synchronous commit returns or throws to the settling pass. A refused settlement closes through the existing refusal arm, and a storage failure panics.

**Code.**
- *Deletes:*
  - the submission commit and wake in `submit-source-in-projection` (agent.clj:725-753);
  - `evaluate-source!`'s listener, its `closed?` pull and the `run-id` atom (agent.clj:775-800, an atom removed). The caller instead awaits the reply port through `seon.await/await!`.
- *Adds:* the request situation in `next-agent-work` and `step` (turn.clj:2950, :5727), run rows in `settle-batch!`, and a bounded payload mailbox.
- Estimate: about −70 / +55 lines, and no Datahike change.

**Laws touched.**
- "Listen before deriving work; channels carry only rederivable or replaceable data and buffers express that loss policy." A fits this sentence (the request is replaceable, and the buffer declares refusal). It needs the ruling that a source request is *replaceable* rather than a durable recovery payload.
- "Recover facts, not execution. Close interrupted turns and mark unfinished evaluations." It needs an amendment, because source runs that never settled leave no fact to close. Proposed: *"A source run is durable only at settlement; one that never settled leaves no fact, and its caller's failed call is its record."*

### B. One commit per form, asynchronous

**Mechanism.** A, plus the caller is answered when the writer has **applied** the settlement.
- Datahike seam (fork, about 15 lines in writer.cljc's processing loop, :139-200): an optional `:applied` port on the invocation receives the applied report right after `op-fn` returns. Validation refusals therefore also arrive at apply time, synchronously. Only I/O failure is left for later.
- A second, smaller seam publishes the writer's pending head for readers, as Datahike's own value on the connection. It must not be a Seon atom beside the connection, which the laws forbid.
- Seon: `transact-call` (src/seon/db.clj:4703) gets an asynchronous arm returning `{:applied report :durable port}`.
- `settle-batch!` answers on the applied report.
- `blob/with-publication!` (src/seon/blob.clj:296-316) must hold its reachability permit until the *durable* port resolves, not until return.
- The pass hands the next form the applied value.

**Guarantee: shown ⇒ applied and ordered; stored eventually.**
- **Prefix durability:** a commit persists the batch's last value, which contains every earlier report, so a crash loses a *suffix* of forms and never leaves a hole (writer.cljc:229-245).
- Any synchronous write by anyone forces everything queued before it durable, because the writer is serial: a form's own `d/transact!`, a merge, a write-back.

**Crash semantics.**
- The loss window is the applied-but-uncommitted suffix: about one commit at agent cadence (default about 64 ms; measured p95 38 ms here at 25 ms spacing), up to 113 ms p95 under C's window or bursts.
- Lost: the evaluation records and shown text the agent *already saw*, program rows it was told were defined (`#'ns/f` exists in its SCI world, not in the store after restart), and turn closes.
- Boot has nothing to recover: no fact of those forms exists. That satisfies "recover facts, not execution" literally, but the agent's own history now disagrees with what it was shown.

**Read-your-writes.**
- The writer chain gives it for transactions (measured: 40 of 40).
- Readers need the pending value: the next pass, the MCP bridge's `history-entries` and `run-receipts` (they read the saved response), and page renders. Without the seam, the committed `@conn` lagged on 39 of 40 burst forms.

**Failure delivery.**
- A commit failure resolves every pending durable port with the error and shuts the writer (writer.cljc:266-284).
- Delivery is loud: the next reply carries the error, the graph stops, and status shows it.
- It cannot be *stored* on the failed store, so it panics under both `:panic` and `:record`. The "recorded and delivered beside the next reply" part of the request is impossible as stated.
- Once the writer is dead, results already shown are unrecoverable.

**Code.** Everything in A, plus about 15 fork lines and about 50-60 Seon lines (asynchronous arm, permit lifetime, pending-value hand-off, error attached to the next reply). Nothing further is deleted. Net growth about +70 over A.

**Laws touched.**
- "Persist exact shown text" and the ruled guarantee "every settled evaluation and its shown text are durable before the caller sees them" become: *"shown text is persisted in order; an agent may see it up to one commit before it is durable, and a crash loses at most that suffix."*
- "Recording without delivery is a defect" becomes delivery without recording for commit failures, under the existing panic rule.
- "No atom beside a connection" still holds only if the pending head lives in Datahike's writer.

### C. Asynchronous group commit

**What Datahike already offers.** It coalesces natively: the commit loop drains the whole queue per commit (writer.cljc:229). `:commit-wait-time` (writer config, writer.cljc:83, :285, :309; Seon passes `{:backend :self}` at src/seon/cluster/store.clj:182) adds a pause after each commit that widens the next batch. Group commit therefore needs no Seon batcher, only that dial and, for single-caller benefit, B's asynchronous reply.

**Batch window and size.**
- The window is commit time plus the wait.
- The batch size is the forms that arrive in that window: 1 at LLM cadence; about 3 at 5 ms spacing (8 with a 25 ms wait); 20 in a burst.

**Latency against throughput.**
- *Synchronous callers:* each pays up to the wait (one agent 19 → 49 ms). Concurrent agents gain throughput and forces (4.5 → 1.5 per form), and p50 latency improved here (71 → 56 ms) because fewer, larger commits queue less.
- *Asynchronous callers:* the reply is unchanged, and the durable lag and crash window grow (p95 61 → 113 ms).

**Guarantee, crash semantics, read-your-writes and failure.** Those of A (synchronous) or B (asynchronous), with the window widened by the wait.

**Code.** One configuration member. By the laws it would be a bootstrap parameter (the writer is created at connect), not a fact, which is a mild conflict with "running configuration is database facts".

## Recommendation

**Build A now; decide B by owner question; do not build C.**

- **A** removes the submission commit (−64 ms, −50% of forces per form) with no Datahike change. It shrinks agent.clj (an atom and a listener go) and keeps "shown ⇒ stored". Its one sacrifice (no fact for a form that crashes mid-evaluation) is narrow, and the owner already accepted it in choosing one commit.
- **B** buys a further ~57 ms per form on default (~87 → ~30). That is about 1-3% of an LLM agent's seconds-long cycle, and it costs a fork seam, the pending-value hand-off, and the loss of "shown ⇒ stored". It saves no disk work at agent cadence.
- **C** is already native under concurrency. The wait dial helps only many-agent bursts and hurts the single agent. Revisit it only when a measured multi-agent load shows commit-bound throughput.

**Owner question (precise).**
> Do you accept that an agent may be shown a form's result, including "#'ns/f defined", up to one commit (≈65 ms, ≈115 ms p95 under load) before it is durable? A JVM crash would then lose that suffix of shown evaluations and program rows, never out of order and never a hole, and a failed commit would panic with those results unstored. In exchange, a form's reply would drop from about 87 ms to about 30 ms on default (option-1 trims: about 65 ms to about 15 ms). If yes, build B on top of A (about 15 Datahike-fork lines plus about 60 Seon lines). If no, stop at A.

## Evidence

- `tmp/async-commit-design-opus/acd_probe.clj`: the probe.
- `tmp/async-commit-design-opus/results.edn`: the raw numbers.
- Dependency seams, all under `reference-code/datahike/src/datahike/`:
  - writer.cljc:123/:200 (pending chain), :229-264 (batch commit and callbacks), :266-284 (terminal commit failure), :83/:285/:309 (`commit-wait-time`);
  - writing.cljc:436-588 (`commit!`: nodes, commit record, branch head, in that order);
  - writing.cljc:877-894 (`transact!` on the writer's current value).
- Seon owners:
  - src/seon/cluster/agent.clj:660-800 (`submit-source!`, `evaluate-source!`);
  - src/seon/turn.clj:775 (`system-run-tx`), :1719 (`recover-call`), :2950 (`next-agent-work`), :3734 (`settle-batch!`), :5727 (`step`);
  - src/seon/db.clj:4703 (`transact-call`);
  - src/seon/blob.clj:296 (`with-publication!`);
  - src/seon/cluster/store.clj:173-192 (store and writer configuration).
