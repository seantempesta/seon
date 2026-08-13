---
type: issue
status: open
severity: friction
tags: [issue, testing, flow, render]
---

# Supply every declared render dependency in the agent-flow fixture

## Problem

The agent-flow property fixture, the loop settings fixture, and the turn
streaming fixture constructed `seon.render.web/render-step` without the declared
`:seon.render.web/interest` reference. Flow therefore refused during proc
initialization before either fixture could exercise its subject.

## Evidence

- `src/seon/render/web.clj` derives `::render-interest` from
  `:seon.render.web/interest` and loudly refuses every absent port.
- At coherent HEAD `5472b6c1e`, a direct run of
  `n-agent-parallel-turns-property` failed with
  `:seon.render.web/missing-port` and missing
  `:seon.render.web/render-interest`.
- Supplying `(atom :all)` in `test/seon/cluster/agent_test.clj` passes that
  construction boundary. Final property verification is blocked by the
  projection lane's in-flight source population, which currently fails static
  contract indexing with `EOF while reading`.
- The same missing dependency reproduced in
  `prompt-and-call-resolve-once-record-settings-and-see-next-turn-config` at
  `test/seon/cluster/loop_test.clj`; supplying the same `(atom :all)` fixture
  value closes that member of the class.
- The 2026-08-13 complete-run attribution found the third member in
  `test/seon/cluster/turn_test.clj`'s `render-proc-for`. Supplying the declared
  reference lets `streaming-rides-channels-and-only-the-settled-value-is-a-fact`
  reach and pass its stream assertions under direct invocation.

## Owner

The hand-built render graph fixtures in `test/seon/cluster/agent_test.clj`,
`test/seon/cluster/loop_test.clj`, and `test/seon/cluster/turn_test.clj`.

## Acceptance

- Both agent conservation properties reach their graph behavior without a
  missing-port refusal.
- The loop settings fixture reaches its config-acquisition assertions without
  a missing-port refusal.
- The turn streaming fixture reaches its channel and settled-fact assertions
  without a missing-port refusal.
- The focused namespace passes after the protected projection source is
  coherent.
