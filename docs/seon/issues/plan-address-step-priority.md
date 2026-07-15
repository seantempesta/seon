---
type: issue
status: open
severity: friction
tags: [issue, agent, database]
---

# Address-message steps can displace authored plan work

## Problem

An address step linked to the inbound human message can become the active plan
anchor while agent-authored work remains open. The agent then follows and
closes the message bookkeeping step instead of advancing the plan that carries
the task's expected outcomes.

## Evidence

The plan-preload pilot observed address-step capture in all three scenarios.
The first `active!` selected the auto-minted message step; in the first scenario
the resulting escalation was attributed to that bookkeeping step rather than
the authored work, and in the third scenario the address step was prematurely
closed while the requested work still lacked authored plan steps. The measured
defect is the coexistence of an active address step with open authored work.
The pilot does not support a requirement that address steps always sort first.

## Owner

The one derived plan position/queue mechanism in `my.plan.internal` and its
message-linked step facts from `seon.agent.message`.

## Acceptance

- No derived plan state has a message-linked address step active while an
  agent-authored step remains open.
- The inbound message remains traceable and addressable without displacing the
  authored plan's outcome-bearing position.
- Behavioral tests cover a message arriving before and after authored plan
  leaves, including the pilot's address-capture shape. The fix derives from
  existing facts and adds neither a stored priority mirror nor a second queue.
- Address rows are excluded from authored document deletion and use a
  maintained schema'd verifier rather than bypassing completion evidence.

The grounded address/reconcile laws are in
[[docs/prds/agent-runtime-correctness/research/plan-transition-authority-audit-2026-07-15]].
