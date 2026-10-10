---
type: issue
status: open
severity: blocker
tags: [issue, agent]
---

# An agent homed on a branch is not resumable after a restart

## Problem

After `bin/seon start --head`, an agent homed on its own branch can't be
messaged or woken until something hosts that branch again. Nothing rehosts it
on its own. Root's `my.message/send` to the agent is refused, so root can't
ask a finished agent why it acted as it did (essential #2).

Root cause: these addressing paths borrow a connection where they need to
resume the agent.

- `seon.db/branch-connection` only borrows from Datahike's connection
  registry. That registry is per process, and the only thing that fills it
  for an agent branch is `seon.cluster.host/host!`, through
  `seon.cluster.agent/acquire-context!` and `seon.cluster.store/open-branch!`.
- Hosting happens lazily, on the first MCP eval as that agent
  (`seon.mcp/execution`). It never happens at boot.
- `seon.cluster.message/send!`, `seon.task/home`, `seon.fault` and the
  competency launcher all treat the nil borrow as the end of the road.

Opening a connection on demand inside `branch-connection` (the obvious patch)
does not fix it, for two reasons:

1. **A connection without a host has no wake route.** The route listener is
   registered only by the armer that `host!` starts
   (`seon.cluster.agent/armer-step`, init arity → `seon.cluster.wake/route!`).
   A message written through a bare connection is stored, and the agent never
   runs.
2. **An unowned connection blocks retirement.** A connection opened outside a
   roster execution is borrowed by a later `host!` with
   `:seon.agent/owns-connection? false`. `seon.cluster.agent/release-context!`
   then never releases it, so `seon.cluster.registry/retire-branch!` refuses
   `::cluster-connected` until the next restart.

## Evidence (default, HEAD e25afdf6e, 2026-10-10, JVM mode)

- **chain2-forgive-1** (created before the restart): its branch
  `:agent-e84b11b8bd78` is in `d/branches`, but `branch-connection` answers
  nil. **chain2-forgive-2** (created after the restart): it answers a
  connection.
- **Restart simulation on a disposable agent `resume-probe-1`:**
  1. A first MCP eval hosted it.
  2. `host/stop!` dropped what a restart loses: the host and its connection.
     The branch stayed listed, and `branch-connection` answered nil.
  3. `seon.cluster.message/send!` from root answered
     `:seon.message/unknown-recipient` ("… holds no connection to; the message
     was not sent").
  4. `host/host!` on the existing branch answered a host in 49 ms, and
     `branch-connection` then answered a connection. The armer primes itself
     on `::flow/resume`, so work written before hosting is derived.
  5. The probe was retired afterwards: admission closed and the branch
     removed.
- **Live roster of open-admission agents homed off the cluster branch:**

  | Branch state | Agents |
  |---|---|
  | Listed and hosted | 3 |
  | Listed, not hosted (chain2-forgive-1, source-whole) | 2 |
  | Branch no longer in `d/branches` | 21 |

  The 21 are astra-node-cache*, loop-*, demo-*, par-a/b, repl-only-3*,
  verify-loop, chain-frictions-probe, cost-reliable-probe1 and loop-fix-1.
  Those agents still read as admitted, but they can never be hosted again:
  `open-branch!` refuses `::branch-absent`. Some path deletes an agent's home
  branch without closing its admission (suspects: reset, or a lane branch
  retired outside `seon.cluster.host/retire!`).

## Ruling (root, 2026-10-10): option 1

Resuming is hosting on demand. `my.message/send` takes the cluster handle as
`:seon.agent/context-source`. It hosts an admitted recipient whose listed home
no host runs (`seon.cluster.host/host!`), then writes the message. A home that
is gone from the roster answers `:seon.cluster.store/branch-absent`. Boot does
nothing new.

`my.agent/retire-branch!` closes the admission of every agent homed on the
branch (`seon.cluster.host/close-admissions!`) before the roster deletion.

Tasks:

- `seon.cluster.host/a-message-resumes-a-stopped-agent`
- `seon.cluster.host/retiring-a-branch-closes-its-agents-admission`
- `seon.cluster.host/a-message-to-an-agent-whose-branch-is-gone-refuses-by-name`

Still open:

- **The world does not supply the handle yet.** `seon.batch/world-members`
  and the turn's world (`seon.turn`) don't carry `:seon.agent/context-source`.
  So a `my.message/send` evaluated by root still answers
  `:seon.message/unknown-recipient` with `:seon.message/home`, until the world
  carries the handle.
- **A hosted agent's armer doesn't take a message wake.**
  `seon.cluster.host-test/an-ordinary-message-wakes-the-idle-hosted-agent` is
  red at HEAD d4ff12e13 (check run e84e6e4fb5d0, the 20 s await). The resume
  proof's final assertion is red for the same cause.

## Workaround today (root, JVM mode)

```clojure
(let [i (get @seon.operator.runtime/running-instances "default") h (:seon.turn.loop/cluster i) host (seon.cluster.host/host! {:seon.turn.loop/cluster (assoc h :seon.store/store (:seon.store/store i)) :seon.agent/branch :agent-e84b11b8bd78 :seon.agent/id "chain2-forgive-1" :seon.ns/name 'user}) c (:seon.db/connection (:seon.turn.loop/cluster host))] (seon.db/call-with-custody {:seon.db/connection c} #(seon.eval.drive/inbound! c "default" seon.cluster/boot-process-identity "chain2-forgive-1" "Why did you act as you did?")))
```

## Acceptance

Two proofs must pass, each with a fresh roster:

- A test hosts an agent on a branch, stops the host, and then
  `my.message/send` from root to that agent writes the message, and the agent
  takes a run.
- Retiring that agent's branch afterwards succeeds.
