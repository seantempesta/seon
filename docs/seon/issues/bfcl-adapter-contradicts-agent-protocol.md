---
type: issue
status: open
severity: friction
tags: [issue, agent, capability, research]
---

# BFCL adapter contradicts the agent execution protocol

## Problem

The shipped BFCL adapter asks a Seon agent to emit bare JSON and not execute
anything, while the stable system context says every action and reply must be
an executable Clojure form. Bare JSON therefore runs nothing and repeated
formless replies close the run without an answer. The benchmark measures an
adapter contradiction instead of function-selection capability.

## Evidence

The exact native Inspect evidence in
`evals/runs/2026-07-15-inspect-turn-evidence-qwen-smoke/` preserves the
model-facing prompt and four replies for sample `multiple_0`. Qwen 3.5 2B
selected the correct argument values in a JSON object but used the current
home namespace as the function name; three subsequent replies were empty and
the run closed `:no-forms`. The prompt simultaneously says bare replies run
nothing and demands a bare JSON-only answer.

The earlier form adapter under
`evals/runs/2026-07-05-bfcl-ast-dev-form/` removed the JSON mismatch but told
the agent to invoke candidate function symbols that were not present in the
Seon program graph. Those calls could only fail as undefined functions.

## Owner

`src-inspect-ai/src/seon_inspect/bfcl_adapter.py` owns the benchmark-to-agent
answer contract. The existing `seon.agent.lifecycle/complete` function owns
successful run closure and result delivery; no second result channel is
needed.

## Acceptance

- The BFCL prompt requests one executable `complete` form and never asks the
  agent to invoke an unregistered candidate function.
- The value passed to `complete` remains BFCL's JSON call-array shape, so the
  maintained scorer and native Python argument types are unchanged.
- Focused adapter tests feed the returned JSON through BFCL's real AST scorer.
- The identical live Qwen sample produces a form/eval instead of closing from
  repeated formless replies; the exact prompt and replies remain in the native
  Inspect log whether the model's selected call scores correctly or not.
