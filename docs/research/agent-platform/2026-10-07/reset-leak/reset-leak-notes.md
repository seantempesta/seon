---
type: notes
status: active
created: 2026-10-07
tags: [notes, reset, leak, host]
---

# reset-leak notes (lane reset-leak, 2026-10-07)

Task: `seon.cluster/reset-leaves-old-agents-branches-and-connections` (declared in
`resources/seon/tasks/seon.cluster.edn`; runtime id e153baaa1746 on default).

## Cause

`bin/seon reset NAME --force` → operator `request!` `:reset` → the running JVM's
`seon.cluster.boot/request!` `:reset`: `stop!` the instance, `registry/retire-branch!` the
cluster branch, `start!` again (whose `registry/retire-unclaimed!` retires every roster branch
no fact claims **and no connection in this process holds**).

`stop!` disarmed only the instance's own layers (`seon.cluster/disarm-agents!`: view, armer,
agents armed in the instance's routing, cluster graph) and released the cluster connection.
Every MCP agent is a *host* (`seon.cluster.host/host!`): an execution acquired on its own
`:agent-*` branch, recorded as a child roster under the instance routing's
`:seon.agent/children`, with its own armer graph and an **owning** branch connection
(`:seon.agent/owns-connection? true`, `:owns-branch? false`). Nothing on the stop path ever
touched the children, so after a reset each host kept its graph, SCI context and connection
(with its loaded indexes) alive; `retire-unclaimed!` then skipped every such branch because it
was connected. Live default (2026-10-07 09:10Z) showed exactly this shape: 4 hosted children
each with `[[branch nil] owns-connection true]`.

## Fix

Reuse the existing owners. `seon.cluster.host/stop!` is the tail of `retire!` (disarm the
host through `seon.cluster/disarm-agents!`, drop `::host`, `agent/release-context!` its
execution) without closing admission; `retire!` now calls it. `release-children!` stops every
host recorded in a routing's children, then releases every execution they still own (non-host
isolated branches included). `seon.cluster.boot/stop!` calls it before
`disarm-agents!` (hosts are the newest layer); a missed join answers the same timeout and keeps
the instance, as before. With the connections released, `start!`'s `retire-unclaimed!` retires
the orphaned `:agent-*` branches (their agent rows are gone with the old cluster branch). No new
mechanism, registry or retry.

Which step of the one loop it serves: the agent's world (its branch execution) is released when
its cluster is; deletes the duplicated stop tail in `retire!`.

## Proof

- Regression `seon.cluster.host-test/stopping-the-cluster-releases-every-host-connection`
  (names the task): two hosts hold their branch connections; `release-children!` answers nil,
  the routing has no children, and no connection remains for either branch.
- `bin/seon check` of the slice with that test plus `a-host-retires-after-it-has-run`:
  executed 2, pass 8, fail 0, 7.8 s tests, 68.9 s total (boot 46.7 s).

### End-to-end reset on a disposable operator root (8 hosted agents, each one SCI run)

Probe after `(System/gc)` ×2 through the root's prepl (`scratchpad/reset-leak/probe.clj`).

| | before fix (HEAD 99bdb12a) | after fix |
|---|---|---|
| fresh boot heap used | 338 MB | see below |
| after hosting 8 agents | 1153 MB, 8 `:agent-*` connected | see below |
| after `reset --force` | **1334 MB**, roster still 8 `:agent-*`, all 8 connected, children 0 | see below |
| reset phases | stop 14 ms, unlink 27 ms, total 12,656 ms | see below |

## Findings (recorded)

- Hosting one MCP agent plus one trivial SCI run costs ~1.4 s and ~100 MB of live heap per
  host (8 hosts: 11.0 s, 338 → 1153 MB). Over the 1 s / 10 MB budget.
- `reset` total 12.7 s on a fresh disposable root (stop 14 ms, unlink 27 ms; the rest is
  `start!`). Over the 10 s bound.
- Host's own nested children (a host acquiring another branch) are released by
  `release-context!` (owning executions) but a nested *host's* graph is not stopped by
  `host/stop!`. No caller creates nested hosts today; not fixed.

## Design check

The design held: the leak was exactly one missing step in the instance stop path, and every
piece of the fix already existed (`disarm-agents!` for a host, `release-context!`,
`retire-unclaimed!` at start). Awkward parts: `seon.cluster` cannot require
`seon.cluster.host` (host requires cluster), so the child-host stop lives in host.clj and is
called from boot.clj rather than inside `disarm-agents!`; and hosts are recorded only as a
private `::host` delay in a child roster, so finding them means walking the children.

Root restart: the fix is in compiled code on the stop path, so default must be restarted onto
the commit (`start --head`) before its next reset benefits. **RESET NEEDED** is not required;
a restart clears the current leak anyway.
