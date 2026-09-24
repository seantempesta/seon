---
type: landing
status: landed (6ea40868d, 64187261c); code only, unverified by test requests (LOAD RULE)
lane: locks-agent-contexts-routing
created: 2026-09-24
audit: docs/research/agent-platform/lock-audit-2026-09-24.md rows 1 and 2 (9437d0e01)
---

# Lane locks-agent-contexts-routing — landing (2026-09-24)

**What existed.** `acquire-context!` held the cluster's contexts monitor across branch
creation, branch open, SCI acquisition and a transaction. `arm!`, `disarm!` and
`stop-owned-graphs!` held the routing monitor across graph start, `flow/stop` and
`join-graph!`. So every turn start was serialized, and the lock order routing → contexts →
branch-open was hidden. **Now** both are values in their atoms. `swap-vals!` reserves the key
or agent with a delay; concurrent callers deref that one delay. All IO, Flow start, stop and
join run outside any monitor.

## Broken or unproven first

- **No test request has run.** The LOAD RULE applies, so the regressions below are committed
  but have not run. Root runs the commands at the end.
- **Behaviour change: concurrent first acquisitions of *different* agents on one
  not-yet-created branch.**
  - Before, the monitor made the second acquirer borrow the first one's connection.
  - Now the second one refuses loudly. `registry/branch!` returns `created? false` ("Isolation
    requires a fresh owned branch."), or `store/open-branch!` refuses `::branch-already-open`.
  - This is the owner's "detect and push back". The armer arms agents sequentially, so it
    cannot race itself.
- **Behaviour change: an arm while that agent's graph is being disarmed.**
  - Before, the arm blocked until the join finished (up to the backstop) and then armed.
  - Now it refuses: "arm! refused: the agent's graph is stopping." with
    `:seon.error/expected :seon.agent/graph-exited`.
  - The armer skips such agents, because the entry is still in `:seon.agent/armed` until the
    stop completes.
- **Not net negative.** Whitespace-insensitive, commit 1 is +45/−5 and commit 2 is +77/−33
  (≈+84 net over both). Raw numstat for commit 2 is +175/−131, because the arm and stop bodies
  moved into private functions and were re-indented.
  - The growth is two contracted private functions (`arm-graph!` and `stop-graph!`, for the
    delays) plus `acquire-once!`.
  - The audit's ±0 estimate assumed the context-state values could become delays. They
    cannot: `my.agent/branch`, `test-support/held-handle` and the MCP `branch` tool read the
    entries as handles. So a reservation is kept as `::acquiring` on the entry, and readers
    still see the previous handle.
- **Stale text outside my paths** (not edited):
  - `src/seon/sci/eval.clj:2008` says acquisition "runs under the arming monitor". The
    sci/eval lane holds that file.
  - `test/seon/custody_stability_test.clj:54` says "under the cluster's context-state monitor".
  - `docs/seon/issues/agent-arm-and-disarm-hold-one-monitor-across-seconds-of-work.md` is now
    resolved by claims rather than by armer messages. Root should close it.
- **RESTART/ADOPT NEEDED.** Hook publication is paused, so default still runs the monitor
  versions. Root adopts `src/seon/cluster/agent.clj` at a checkpoint. No schema change.

## Evidence (small probes, default JVM, scratch namespaces only)

- **Delay seam** (`clojure.lang.Delay` realize/deref, reference-code/clojure):
  - 8 concurrent `swap-vals!` reservers → 1 build, 1 identical result (57 ms).
  - A failed delay rethrows the identical Throwable to every sharer.
- **`acquire-once!`**, from a scratch copy of the file loaded as `tmp.locks-agent-copy`
  (default's Vars untouched; load 120 ms):
  - 6 concurrent same-key callers → 1 build, 1 handle, which is stored as the entry and was
    given the previous handle.
  - A failure restores the previous entry.
  - A release during acquisition (the key is removed mid-build) is not undone.
- **Claims, scratch copy:**
  - An arm on a stopping claim refuses with `:seon.agent/graph-exited`.
  - An arm on a released routing refuses with `:seon.agent/routing`. No IO runs in either
    case.
  - A disarm of an unarmed agent returns nil and leaves the routing unchanged.
- **Timing.** I did not measure the named probe before and after under the LOAD RULE. The
  reservation adds one `swap-vals!` and one deref per acquisition or arm, and the monitor is
  gone. Root's runs give the numbers.

## Change

- **6ea40868d** — `acquire-once!` (new, private, contracted) reserves `[branch agent]` with a
  delay of the acquisition.
  - Other callers of the same key deref it; different keys run in parallel.
  - The key settles to the handle, or back to previous on failure, only while this
    reservation still holds it.
  - `branch-owner` skips entries that have no connection yet.
  - An isolated world still records into its own fresh context state. The parent's
    reservation settles back to previous.
- **64187261c** — the routing monitor is deleted.
  - **`arm!`** reserves `::arming {agent delay-of-arm-graph!}` unless the routing is
    released, a stop is in flight, a claim exists, or the agent is already armed. It then
    either refuses by name, derefs the claim, returns the live entry, or disarms a
    terminated previous graph and rearms.
  - **`arm-graph!`** publishes the entry and clears the claim in one `swap!`. On failure it
    clears the claim.
  - **`disarm!`** reserves `::stopping {agent delay-of-stop-graph!}`; concurrent disarms share
    the one stop. An arm in flight settles first, and then the disarm repeats.
  - **`stop-graph!`** is the old body. Its step order is unchanged, and it clears the claim in
    `finally`.
  - **`stop-owned-graphs!`** marks the routing released in one `swap-vals!`, derefs every
    arming claim present at the mark, then disarms every armed agent. This keeps S1b's
    guarantee: no arm claimed after the mark, and every graph an earlier claim started is
    joined.
- **Regressions**, in `test/seon/cluster/agent_test.clj`:
  - `concurrent-acquisitions-of-one-key-build-once`: a watch holds the first reserver until
    the second has seen the reservation, so the overlap is guaranteed, not timed. Both get the
    identical handle, and the settled entry is that handle.
  - `concurrent-arm-and-release-never-deadlock`: three arms (two of them for one agent) race
    one release of an isolated attempt, and every future settles within the event backstop.
    Each arm either returns a joined graph or refuses with `:seon.agent/routing`. Routing ends
    empty, a late arm refuses by name, and the branch is unlinked.

## Root runs (serially, quiet window, after adopting agent.clj)

- `bin/test-check default --test seon.cluster.agent-test/concurrent-acquisitions-of-one-key-build-once --test seon.cluster.agent-test/concurrent-arm-and-release-never-deadlock --test seon.cluster.agent-test/release-joins-every-owned-graph-before-it-closes-the-branch --include-long`
- `bin/test-check default --test seon.loop-proof-test/concurrent-arms-share-one-graph --test seon.cluster.agent-test/disarm-has-a-declared-loud-turn-completion-backstop --test seon.cluster.agent-test/disarm-interrupts-an-in-flight-turn-and-records-it-interrupted --test seon.cluster.agent-test/disarm-drops-the-route-before-closing-it --test seon.cluster.agent-test/fenced-is-the-derived-quarantine-state --include-long`
- `bin/test-check default --ns seon.cluster.acquisition-test --ns seon.cluster.release-context-test --ns seon.cluster.agent-custody-test --ns seon.cluster.entrance-supplier-test --ns seon.custody-stability-test --include-long --time-limit-ms 590000`
- `bin/test-check default --ns seon.cluster.agent-test --ns seon.cluster.agent-arming-test --ns seon.turn-test --include-long --time-limit-ms 590000`

## Released paths

`src/seon/cluster/agent.clj`, `test/seon/cluster/agent_test.clj`, this landing.
