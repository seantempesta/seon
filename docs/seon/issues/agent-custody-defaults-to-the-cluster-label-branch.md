---
type: issue
status: open
severity: high
created: 2026-09-24
tags: [issue, testing, custody, branch, agent]
---

# Agent custody defaults to the cluster-label branch

## Problem

An agent created without `:seon.agent/branch` gets
`(registry/cluster-branch cluster-name)` (`src/seon/cluster/agent.clj:205`,
`creation-tx`), a store-global name derived from the cluster label, not the
branch the creating connection holds. `acquire-context!`
(`agent.clj:811-819`) then follows that row: when it differs from the handle's
live branch it is treated as isolated and, with no retained context, is
created with `registry/branch!` (`:834-849`). The name is shared by every
run with the same label and, for rows inherited from the live cluster
(`root`), is `:cluster-default` itself.

Inside a test member world (`seon.test/run` → `with-database`, both acquired
through `acquire-context!` with `:seon.agent/isolate? true`) this gives two
outcomes:

1. **Observed, today:** a fixture seeding an agent in cluster `"turn-test"`
   routes it to `:cluster-turn-test`. The first run creates that branch in the
   shared store; any later run whose branch survived (a timed-out run keeps its
   branch, `lane-fix-resume-turn-ns-2026-09-24.md:32-35`) refuses at arm or
   system-turn with `"Isolation requires a fresh owned branch."` (`agent.clj:848`).
2. **Latent:** a member handle carries the live cluster's
   `:seon.agent/context-state` atom (`agent.clj:900`, `:919`). Acquiring or
   arming `root` through that handle (not through `support/cluster-handle`,
   which supplies a fresh atom, `test/seon/test_support.clj:357-376`) finds
   `[:cluster-default "root"]` as `previous` and returns the live root's held
   connection (`agent.clj:852-856`): the test would read and write live data.
   No current test was found taking this path; nothing prevents one.

## Evidence (2026-09-24, lane probe-test-custody-escape)

Default (pid 70372) died of `OutOfMemoryError` at 18:08Z before a live probe
could run, so the evidence is its log, `data/clusters/default/logs/seon.log`
(2026-09-21 → 2026-09-24):

- 43 refusals `"Isolation requires a fresh owned branch."`, every one naming
  a cluster-label branch: `:cluster-turn-test` 31, `:cluster-grammar` 6,
  `:cluster-virtual-turns` 2, `:cluster-rereads` 2, `:cluster-trial` 1,
  `:cluster-issue-settlement` 1; none name `:cluster-default` or a member branch.
- Raised from `arm!` (`agent.clj:988`) and `seon.turn/system-turn`
  (`turn.clj:2110`); affected tests include `seon.rereads-test`,
  `stored-help-keeps-its-renderer-and-bare-response`,
  `virtual-turns-use-the-proc-and-compaction-is-agent-scoped`,
  `issue-settlement-runs-tests-and-derives-completion` and about thirty
  `seon.cluster.turn-test` cases (tally in `tmp/probe-test-custody-escape/`).

## Wanted

Agent creation carries the receiving connection's branch
(`(get-in @connection [:config :branch])`) through the canonical writer
instead of inferring it from the cluster label: fix `creation-tx`
(`agent.clj:205`) and its callers (`seon.cluster/seed-root-agent!`,
`cluster.clj:3289`; `ensure-entity!`). A member world's inherited rows that name
the live branch must not route an acquisition through the live
context state; the member's `:seon.agent/context-state` should not be the live
cluster's atom. Regression: arm an agent seeded in a `with-database` world twice
across runs, observe its connection's branch is the world's branch, and observe
live default's head is unchanged. Related, different cause:
`test-fixture-worlds-inherit-the-live-clusters-errors-and-messages.md`
(read contamination from the inherited commit).
