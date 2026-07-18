---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, cljs]
---

# Preserve the agent namespace after asynchronous eval

## Problem

After a successful Promise-returning form, the eval batch replaced its current
namespace with the invalid symbol `:`. Every later form in the turn then failed
to resolve ordinary home-namespace aliases and referred lifecycle functions.

## Evidence

Live agent `cuddly-showers-join` successfully evaluated `(* 17 19)` and
`(seon.agent.message/user "323")`. The following plan and completion evals all
recorded `:seon.eval/ns :` and failed as undefined. Every later turn repeated
the same transition immediately after a successful asynchronous message call.
`seon.eval/eval-form-entry!` unconditionally trusted the self-host evaluator's
reported ending namespace whenever the eval succeeded.

## Owner

The existing `seon.eval` per-form namespace fold and its self-host evaluator
result.

## Acceptance

- A Promise-returning form preserves its valid starting namespace.
- A real agent can send a message, update its plan, and complete in one turn.
- Failed or malformed evaluator namespace output cannot become durable eval
  data or affect later forms.

## Resolution

Resolved by `e7886806` and `d1dba787`. Evaluator namespace output is validated,
and only an explicit `ns` declaration can move the batch fold. The final fresh
agent completed two asynchronous home-namespace functions in one turn with no
failed eval or empty namespace row.
