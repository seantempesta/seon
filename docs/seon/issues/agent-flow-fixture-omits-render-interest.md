---
type: issue
status: open
severity: friction
tags: [issue, testing, flow, render]
---

# Supply every declared render dependency in the agent-flow fixture

## Problem

The agent-flow property fixture constructs `seon.render.web/render-step`
without the declared `:seon.render.web/interest` reference. Flow therefore
refuses during proc initialization before either agent conservation property
can exercise its graph lifecycle.

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

## Owner

The hand-built render graph fixture in
`test/seon/cluster/agent_test.clj`.

## Acceptance

- Both agent conservation properties reach their graph behavior without a
  missing-port refusal.
- The focused namespace passes after the protected projection source is
  coherent.
