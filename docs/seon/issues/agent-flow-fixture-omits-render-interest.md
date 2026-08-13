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
  reach and pass its stream assertions under direct invocation and the focused
  repository runner.
- The same tally exposed a fourth stale construction in
  `seon.cluster.message-test/inbound-wakes-the-named-agent-test`: its direct
  `wake/route!` request omitted `:seon.render.web/interest`, so the listener
  caught a nil dereference before routing Bob's message. The fixture now
  supplies `(atom :all)`, matching the production request contract.
- All three `seon.gen.loop-test` reds were a fifth instance: the shared
  `with-render-context-proc` constructed the now-total render terminal without
  its required interest reference, so every test stopped at proc construction
  before reaching its durable routing assertions. The shared fixture now
  supplies the declared reference; the total terminal remains unchanged.
- A direct combined rerun crossed that construction boundary. It then spent
  more than five minutes in the first test while `prompt/acquire-within-budget`
  waited for `render/acquire-context!`; the virtual-thread-aware dump at
  `tmp/overnight-gen-loop-threads.json` showed the render proc actively in
  `render.web/context-pass`, computing a Datahike schema projection rather than
  a missing delivery. That later render-cost boundary is not evidence against
  the fixture correction.
- The later complete-tier worker dump at
  `tmp/test-runs/run.czWGK6/tmp/test-liveness/86091-1786612994637-threads.json`
  showed the same stack in the wake-routing property: the turn waited for
  context while the render proc repeatedly derived config through
  `schema/projection-from-database` at individual render calls. An identical
  routing trial took 217,100 ms without a supplied render profile and 6,185 ms
  with the shipped agent profile; both returned every routing verdict true.
- `test-support/render-context-channel` now supplies that already-derived
  profile to these fixed-config fixtures. The production render proc and walk
  still execute; only the unrelated config-to-profile derivation is removed
  from each visited node. The focused agent namespace passed 18 tests / 176
  assertions; the combined agent + generative-loop run passed 21 / 196, with
  the goal test reduced from 758 seconds to 12.910 seconds. The nine-worker
  reproduction passed 92 / 510; the same goal test completed in 18.626 seconds
  while the routing property completed in 76.179 seconds.

## Owner

The hand-built render graph fixtures in `test/seon/cluster/agent_test.clj`,
`test/seon/cluster/loop_test.clj`, `test/seon/cluster/turn_test.clj`, and
`test/seon/cluster/message_test.clj`, plus `test/seon/gen/loop_test.clj`.

## Acceptance

- Both agent conservation properties reach their graph behavior without a
  missing-port refusal.
- The loop settings fixture reaches its config-acquisition assertions without
  a missing-port refusal.
- The turn streaming fixture reaches its channel and settled-fact assertions
  without a missing-port refusal.
- The focused namespace passes after the protected projection source is
  coherent.
- The generative-loop tests cross proc construction and complete their durable
  routing assertions within the focused runner's ordinary duration.
