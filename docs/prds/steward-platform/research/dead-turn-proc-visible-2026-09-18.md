---
type: research
status: open
created: 2026-09-18
tags: [oversight, agent, flow, faults]
---

# Dead turn proc visibility

This bounded lane read `AGENTS.md` sections 0–5,
`.agents/skills/seon-flow-architecture/SKILL.md`, the owning issue,
`live-trial-1-2026-09-17.md`, and program-facts PRD sections 1k and 1m end to
end. It also read the error-entities PRD's base-member section and the
core.async Flow implementation before changing a seam.

The lane landed three independent slices and stopped at one exact ownership
dependency. It did not operate the shared `default` cluster.

## Landed seams

### Every agent proc is observed

Commit `f4b9e007c` makes `seon.oversight/flow-status` enumerate every proc in
each agent's actual Flow graph rather than projecting only mailbox and turn
counters (`src/seon/oversight.clj:121-141`). Each proc receives the same
bounded `flow/ping-proc` observation used for cluster plumbing; an absent pong
is `:unknown`, never evidence of health. `mcp-runtime-observation` now returns
that complete fleet value (`src/seon/cluster.clj:582-604`). The observation is
keyed by `:seon.agent/id` and carries `:seon.oversight/procs` with pid, ping,
status, passes, and buffer occupancy.

The canonical booted-cluster regression checks that the root agent reports
all three procs declared by its graph—mailbox, turn, and schedule—and that
each is either a reply or the explicit unknown (`test/seon/oversight_test.clj:115`).
The MCP regression checks that the returned observation includes both agents
and plumbing (`test/seon/cluster/mcp_test.clj:318`).

### Sliding-one wake loss is counted

Commit `647694741` replaces both agent wake sliding buffers with one
`CountedSlidingBuffer` implementation (`src/seon/cluster/agent.clj:95-122`,
`:503`, `:742`). A put into a full buffer increments a monotonic overwrite
count. The standard Datafiable buffer observation now exposes that count as
`:seon.oversight/dropped`, and the HTML buffer face names it when nonzero
(`src/seon/oversight.clj:27-48`). No durable fact was added: this is bounded
process-local observation of an in-flight channel.

The regression puts three wakes into a capacity-one channel and observes two
drops through the same occupancy function used by oversight
(`test/seon/oversight_test.clj:27`).

### Classifying fault evidence survives the cap

Commit `b78936144` adds `seon.sci.admit/admit-partitioned`: ordinary admission
still wins when the whole value fits; otherwise the priority partition is
admitted first and the omitted remainder is represented by the ordinary
bounded-admission marker (`src/seon/sci/admit.clj:801`). Error preparation and
fact payload fitting use that seam (`src/seon/error.clj:415-462`, `:585`,
`:620`).

The dated priority set keeps the current `:seon.error/kind` and the
error-entities PRD base members—kind, severity, layer, operation, member,
expected, actual, path, message, and cause—without implementing that PRD's
schemas. If even the classifying partition cannot fit, admission returns the
existing over-bound refusal; it never silently presents missing classification
as a valid capped fault.

The 32 KiB evidence regression under a 1 KiB result cap retains kind, layer,
operation, member, and message, plus an over-bound remainder marker
(`test/seon/error_test.clj:492`).

## Named dependency: durable FAILED state and panic stop

Item 2 and its combined real-agent regression are not landed. The only
existing point that has both required inputs—confirmation that the durable
fault commit succeeded and the fault's attributed `:seon.agent/id`—is the
cluster fault committer's `:seon.flow/panic!` callback at
`src/seon/cluster.clj:3241-3247`. It also closes over the cluster routing atom,
whose entries own each agent graph (`src/seon/cluster/agent.clj:778-790`). The
assignment allowed edits to `src/seon/cluster.clj`'s observation function
only.

The agent-owned join at `src/seon/cluster/agent.clj:772-777` sees the raw Flow
error before the central committer records it. Stopping there would violate
the requirement that FAILED be durable before the graph stops. Adding a
second consumer or copying the fanout into the agent namespace would duplicate
the one fault-delivery mechanism. Therefore the correct next change is to
extend the existing cluster `panic!` callback: after a committed attributed
fault, find the routing entry, durably derive FAILED from the unresolved
occurrence, and stop only that entry's graph. The JVM and unrelated agent and
cluster graphs remain running. The agent page and runtime observation must
derive FAILED from that same durable occurrence, never from the armer routing
atom.

That production edit requires explicit ownership of the `arm-agents!` fault
committer callback and the status/page read seam. Until it lands, the requested
regression—first turn pass throws, durable fault names agent and proc,
runtime-status reports FAILED/unknown, later wake increments dropped—cannot
truthfully be made green.

## Fast verification

All runs used the canonical armed `bin/test-fast` harness in a clean
HEAD-plus-lane-commits worktree because the shared-tree overlay admission
correctly refused a foreign dirty caller (`test/seon/schema_test.clj`) against
a published graph 32 commits behind HEAD.

- `seon.error-test`: **38 tests, 190 assertions, 0 failures, 0 errors**.
- `seon.oversight-test`: **5 tests, 46 assertions, 0 failures, 0 errors**.
- `seon.oversight-test seon.cluster.mcp-test`: the owned oversight and runtime
  observation regressions passed. The namespace run ended at **16 tests, 99
  assertions, 4 failures, 1 error** in MCP rendering, symbol-expectation, and
  config-reconciliation cases outside the observation assertions. The lane
  did not establish their cause and makes no attribution for them.
- `seon.cluster.armed-test`: the existing first-proc-fault regression reached
  the injected durable fault, then found `:seon.error/proc` and
  `:seon.error/process` absent from the root error pull. Those values now live
  on the occurrence and require `seon.error/latest-fact`. Repairing that stale
  read alone would not prove the unfinished FAILED-state and graph-stop
  behavior, so the lane did not turn it into a misleading green regression.

The orchestrator still owes the cold path-limited gate and platform proof.

## Live-proof plan after adoption

The Seon MCP tools were absent from this Codex lane even though `bin/seon
status` reported `default` alive. The recurrence is recorded in
`docs/seon/issues/seon-mcp-tools-absent-in-codex-lane-again.md`; no manual
prepl substitute was used.

After the named dependency lands and the orchestrator adopts it on `default`:

1. Create a disposable worker on the canonical fixture and inject one turn
   proc failure on its first real pass.
2. Observe `runtime_status`: the agent entry must list mailbox, turn, and
   schedule; turn must be `unknown` or failed, the agent must be FAILED, and
   plumbing and unrelated agents must still answer.
3. Query the durable fault: its latest occurrence must name the same agent id,
   `:seon.agent/turn`, and the uncapped classifying keys. Confirm the agent's
   graph is stopped while the JVM and cluster plumbing remain live.
4. Send another wake and observe the mailbox-to-turn buffer's dropped count
   increase from the prior value.
5. Open `/agent/<id>` and verify the page visibly says FAILED and renders the
   durable fault classification; it must not render ARMED from routing alone.
