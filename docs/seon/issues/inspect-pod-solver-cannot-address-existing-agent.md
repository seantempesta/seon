---
type: issue
status: open
severity: blocker
tags: [issue, agent, research, flow]
---

# Let the common Inspect pod solver address an existing agent

## Problem

`seon_inspect.solver.pod_run` already owns the typed optional `agent_id` wire
field, but `seon_pod_solver` cannot accept or forward it. Ordinary rows can
correctly omit the field and mint a fresh agent; the root reachability row
cannot address the existing `root` agent without duplicating the common static
pod solver.

## Evidence

`src-inspect-ai/src/seon_inspect/solver.py` conditionally adds `agent_id` to
the `/agents/run` payload and preserves HTTP 422 as `AgentRunRefused`.
`seon_pod_solver` calls that function with only prompt, timeout, and URL.

The separate experimental task in
`src-inspect-ai/src/seon_inspect/tasks/namespace_reachability.py` therefore
fails root-row construction rather than inventing another solver. The exact
root trajectory and shared-mechanism decision are in
[[../../prds/agentic-tool-refinement/research/tool-reachability-falsifiers-2026-07-15]].

## Owner

`seon_inspect.solver.seon_pod_solver` owns the one static capability bridge.
Its focused solver and native-task tests own omission, explicit routing,
retained metadata, and refusal behavior.

## Acceptance

- Omitting `agent_id` preserves attribute absence on the request and continues
  to mint a fresh ordinary agent.
- An explicit `agent_id="root"` reaches `pod_run` byte-for-byte through the
  common solver; no fallback, coercion, task-specific solver, or second
  evidence path exists.
- `_record_result` retains the pod's actual returned agent id and all existing
  turn, eval, database, transport, source-admission, and model-server metadata
  unchanged.
- An unknown explicit id still raises `AgentRunRefused` from HTTP 422 and
  produces no capability score.
- The root reachability task removes its construction refusal, uses the common
  solver, and one finalized native log proves the addressed agent is `root`;
  the ordinary reachability rows still prove fresh-agent home edges by
  omission.
