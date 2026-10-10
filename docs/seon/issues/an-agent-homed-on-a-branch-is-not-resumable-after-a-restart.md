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

## Options (owner ruling needed)

1. **Resume is `host!`, on demand at the address.** `send!` (and the outside
   wake) host an unhosted, admitted recipient whose home is listed, then
   write.
   - Needs: the cluster handle and store in the sender's supplied world, or a
     host-on-demand function on the cluster roster that `send!` calls.
   - Cost: about 50 ms once per resumed agent; nothing at boot.
   - Recommended.
2. **Boot rehosts agents with unanswered work.** For each admitted agent
   whose home is listed, read `seon.db/branch-database`, which needs no
   connection. Host only the agents whose wakes are unanswered.
   - This covers work left before the restart, but not a new message to an
     idle agent, so option 1 is still needed.
3. **`branch-connection` opens on demand,** recorded as owned in the cluster
   roster so that retirement releases it.
   - The writes succeed, but the agent is still not woken unless the open
     also starts its armer, which makes it option 1 in a lower layer.

Separately, retiring or deleting an agent's home branch must close its
admission in the same step, or the agent row must stop reading as admitted.

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
