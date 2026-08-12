---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, testing]
---

# Derive a completion reply from the triggering message

## Problem

The message-delivery composition does not preserve the intended two-message
conversation. Agent A's request reaches agent B, but the resulting facts also
contain a duplicate request from B to itself and B replies to A with A's own
completion text instead of B's answer. The extra connection makes the trigger
chain one hop deeper than the asserted conversation.

## Evidence

`seon.cluster.turn-test/a-turn-delivers-what-a-form-asks-to-send-and-still-finishes`
failed before W1 in `tmp/full-gate-2026-08-10c.log` with these facts:

```clojure
#{["please count the widgets" "agent-b" "agent-a"]
  ["please count the widgets" "agent-b" "agent-b"]
  ["asked agent-b" "agent-a" "agent-b"]}
```

The W1 integration gate reproduced the same set at
`tmp/orchestrator/w1-integration-stdout.log:413193-413208`; it also measured
`message/chain-depth` as 2 instead of 1. Because the August 10 failure predates
W1's August 11 history and settlement commits, this is pre-existing residue,
not a W1 regression.

## Owner

The `seon.cluster.loop/asked-value` and `seon.cluster.message/reply` boundary
that derives a completion delivery from the run's triggering message.

## Acceptance

- The composition commits exactly A's request to B and B's answer to A.
- No agent receives a duplicate of the triggering request from itself.
- The committed trigger/about refs derive conversation depth 1.
- The existing turn regression passes without changing its expected facts.
