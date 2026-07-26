---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime, operator]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Reconcile the cluster JVM for named clusters

## Problem

`bin/seon cluster open <name>` reconciles only the target pod. A named
source-checkout cluster therefore has no target-specific long-lived JVM
run-holding process or web-render process even though its derived launch descriptor and
process graph include both members.

This blocks isolated live proof of cluster JVM behavior. Sending a provider
request would not exercise a run-holding process owned by the named cluster.

## Evidence

On 2026-07-24, the source-frozen default watcher and writer were ready on
artifact
`e7d465aa3f532a93c2c9d290e724be469e8a731cc138def1682188888c242e6f`.
After a fresh `claimantllm` database open, the supported command reported:

```text
● cluster claimantllm ready
  database: /Users/sean/src/seon/data/clusters/claimantllm/db
  web: http://127.0.0.1:58850
```

But `bin/seon cluster status claimantllm --edn` immediately reported
`:seon.dev.target.status/degraded`. Its pod workload PID `75077` was ready,
while the reported host was the default cluster workload PID `73205` with
`:seon.dev.process/current-spec? false` and
`:seon.dev.process/ready? false`. No claimantllm host process record existed
under `tmp/seon-clusters/claimantllm/processes/`.

The source explains the mismatch:

- `seon.dev.cluster/ensure-under-lock!` selects and ensures only the pod spec;
- `seon.dev.process/state-file` uses the target process directory only for a
  pod whose writer is externally owned, while host and web-render reads fall
  back to the source configuration's process directory; and
- `seon.dev.cluster/close!` likewise targets only the pod.

The complete transcript is
`tmp/orchestrator/claimantllm-gate.log`. The target pod was stopped cleanly
before any DeepSeek request.

## Reconfirmation — 2026-07-25

The load-and-saturation measurement attempted the supported disposable-cluster
path before making any paid provider call. The blocker is unchanged:

- `seon.dev.cluster/ensure-under-lock!` still selects only the pod spec
  (`script/seon/dev/cluster.clj:233-258`);
- `seon.dev.cluster/close!` still stops only that pod
  (`script/seon/dev/cluster.clj:318-333`); and
- the HTTP route comment still advertises the nonexistent
  `bin/seon cluster create` command (`src/seon/web/serve.cljs:722`).

Consequently the safe real-agent concurrency ceiling measured in a disposable
cluster was zero: the first required resource to fail was cluster lifecycle
composition, before a target JVM driver existed. No provider request was sent.
This is a measurement blocker, not evidence about agent throughput.

## Owner

`seon.dev.cluster` owns named-cluster lifecycle composition.
`seon.dev.process` owns process-record paths and complete graph reconciliation.
The existing shared-writer launch descriptor remains the one topology
authority.

## Acceptance

- `cluster open` reconciles every target-owned source-checkout member required
  by the derived graph: host, pod, and web-render.
- Target host and web-render records and logs use the named cluster's private
  process and log directories; they never alias the source cluster records.
- `cluster status` reaches ready only when every target-owned member is current
  and ready and the external watcher/writer owners are ready.
- `cluster close` and `cluster restart` stop or replace every target-owned
  member in dependency order without changing the source cluster generations.
- A fresh named-cluster run persists a `:seon.agent.run/process` whose PID matches its
  target host workload PID.

## Resolution

Resolved across `051825d92`, `2c885f754`, and `037e285e2`.

`seon.dev.cluster/ensure-under-lock!` now reconciles the entire derived target
graph in dependency order, while close/restart target every target-owned
member. `seon.dev.process` stores all target-owned records and the web-render
port under the named process directory and supplies the named database
backend/path to the host.

Fresh cluster `agentload0726` reached ready with private host workload PID
`15335`, pod workload PID `15367`, web-render workload PID `15402`, database
`data/clusters/agentload0726/db`, and web endpoint
`http://127.0.0.1:55729`. Historical query of real run `m5bng2aq847g`
showed `:seon.agent.run/process "host-15335"` asserted at transaction
`536871033` and retracted at terminal transaction `536871036`. Its eval
receipt and transcript reply are recorded in
[[../../../prds/sci-execution-runtime/research/measurements-2026-07-25#172-one-real-turn-proves-the-named-driver-and-database]].

Focused operator lifecycle proof and the live open/turn/reset falsifier are
green. The separate remaining architecture gap—named clusters still depend on
one shared writer process—is tracked by
[[../named-clusters-share-one-writer-process]].
