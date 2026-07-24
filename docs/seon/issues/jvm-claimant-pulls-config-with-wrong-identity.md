---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, database, config]
---

# Pull JVM claimant limits from the cluster config identity

## Problem

The JVM claimant completes a successful hosted-provider attempt, then fails
before eval because `invocation-configuration!` looks up the config singleton
with the keyword value `:seon.config/singleton`. The registered and committed
`:seon.config/id` identity is the string `"cluster"`.

The resulting empty pull is reported as if
`:seon.config.claim-driver/invocation-deadline-ms` were absent, even though the
fact is present in the database. This prevents every JVM-owned reply from
reaching eval.

## Evidence

The source mismatch is direct:

- `src/seon/agent/driver/host.clj` uses
  `[:seon.config/id :seon.config/singleton]` in
  `invocation-configuration!`.
- The same namespace already uses `[:seon.config/id "cluster"]` for its startup
  config pull.
- `src/seon/config/resolve.cljc` registers `:seon.config/id` as a string
  identity and exposes `cluster-config-id`.
- Other maintained call sites use
  `[:seon.config/id config/cluster-config-id]`.

The default-cluster `drive6` live drive created agent `fresh-rice-crash`, run
`i5u53tgo4zlo`, turn `gnb8qu1o87gg`, and attempt `n1p49np57wqf`. Pod claimant
PID `31023` held epoch 1 for render; JVM claimant PID `30970` acquired epoch 2.
DeepSeek returned HTTP 200 and the attempt changed from `:open` at transaction
`536873733` to `:success` at transaction `536873735`.

The live config singleton is entity `6124`, with
`:seon.config/id "cluster"`,
`:seon.config.claim-driver/invocation-deadline-ms 120000`, and
`:seon.config.claim-driver/invocation-result-maximum-bytes 1048576`. The two
limits were committed at transaction `536871010`.

At transaction `536873736`, phase-error settlement persisted the false-missing
limit message on the turn, advanced it from `:reply-ready` to `:published`,
changed its status from `:running` to `:error`, closed the run with reason
`:error`, retracted both the JVM claim and the agent's current-run ref, and
released custody. Core-fault transaction `536873737` then recorded the same
message with kind `:core-bug` and data naming the deadline key.

Exact database evidence is retained in
`tmp/orchestrator/drive6-gate.log`.

## Owner

`seon.agent.driver.host/invocation-configuration!` owns the JVM claimant's
config acquisition. It must use the one maintained cluster config identity
from `seon.config.resolve`, as the sibling claimant and execution call sites
already do.

## Acceptance

- A focused JVM test asserts the exact config lookup ref used by
  `invocation-configuration!` and returns both committed claimant limits.
- A default-cluster live drive advances a successful hosted-provider attempt
  through `:reply-ready` to `:evaling`, with durable eval receipts.
- The same drive completes several turns, writes schema-backed facts in one
  turn, reads them in a later turn, publishes the final synthesis, and leaves
  no claimant or current-run ref after close.
