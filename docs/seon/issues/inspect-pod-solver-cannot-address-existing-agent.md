---
type: issue
status: open
severity: blocker
tags: [issue, agent, research, flow]
---

# Let the common Inspect pod solver address an existing agent

## Problem

`seon_inspect.solver.pod_run` owns the typed optional `agent_id` wire field.
The common static solver now forwards it, but the root reachability row has not
yet completed one finalized, source-stable native run proving that the retained
agent is the existing `root`. Ordinary rows must continue omitting the field so
the pod mints a fresh agent.

## Evidence

`src-inspect-ai/src/seon_inspect/solver.py` conditionally adds `agent_id` to
the `/agents/run` payload and preserves HTTP 422 as `AgentRunRefused`.
Commit `8efd3366` threads the optional value through `seon_pod_solver` and
selects `"root"` only for the root orchestration row. Ordinary rows pass
`None`, so `pod_run` preserves payload absence and the pod still mints a fresh
agent. The focused solver/reachability gate passes 79 tests.

The first live row passed construction and `POST /agents/run` recorded
`:agent "root", :reused true`. Concurrent runtime source edits then changed
the admitted ACME target while the model was still running, so Inspect retained
the attempt as interrupted/rejected rather than fabricating the remaining
acceptance proof. The exact root trajectory and shared-mechanism decision are in
[[../../prds/agentic-tool-refinement/research/tool-reachability-falsifiers-2026-07-15]].

The source boundary is complete. This issue remains open only until a coherent
ACME source freeze permits one finalized native log to retain
`pod_agent_id == "root"` with equal start/end target identity.

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
