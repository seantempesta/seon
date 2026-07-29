---
type: issue
status: open
severity: blocker
tags: [issue, agent-runtime, provider, database]
---

# Fresh model requests cannot express an output budget

## Problem

Fresh Seon's provider descriptor cannot carry a maximum completion-token
budget, and `seon.ai/request-body` cannot put one on the compatible wire.
Consequently a thinking model may consume its whole serving default in
reasoning, emit no assistant content, and hold an agent turn until the remote
HTTP deadline. Raising or lowering the budget through database facts is
unrepresentable.

The same closed request also cannot select a supported reasoning control. This
is secondary for Qwen, whose Ollama contract has only thinking on/off rather
than a thinking-token level, but it prevents a descriptor from using a level
with models that do support one.

## Evidence

The 2026-07-29 dedicated Ollama load calibration used the real project model
and the ordinary Seon prompt/task path. A transparent recording proxy added
only the missing compatible request fields.

- Default thinking with `max_tokens=4096` generated 4,096 completion tokens,
  finished `length`, and emitted zero visible content.
- `reasoning_effort=low` with the same cap behaved the same way: 4,096
  completion tokens, `length`, zero visible content. Ollama documents levels
  for GPT-OSS, not Qwen, so this was not a valid Qwen bound.
- `max_tokens=8192` preserved default thinking and let three consecutive real
  task shapes finish `stop` with correct visible forms.

Raw rows are under `tmp/load-testing/evidence/smoke4/`,
`tmp/load-testing/evidence/smoke-low/`, and
`tmp/load-testing/evidence/smoke-8192/`.

The missing construction is visible in `src/seon/ai.cljc`: `targets` assembles
endpoint, model, timeout, and one authentication declaration, while
`request-body` emits model, stream, messages, and stream options. The schemas
under `src/seon/schema/ai.edn` have no maximum-output or reasoning-control
attribute.

## Owner

`seon.ai`'s descriptor/request boundary and its config/schema rows. This is one
provider-family capability expressed as data, not an Ollama-specific adapter
arm.

## Acceptance

- A database-owned provider descriptor can declare a positive maximum output
  and the compatible request transmits it as `max_tokens`.
- Finish reason `length` with no visible assistant content returns a specific
  flat error value rather than the generic empty-stream error.
- A supported thinking control is optional and data-driven; absence preserves
  provider/model defaults.
- A real loopback compatible-server test captures the exact request document
  and proves the maximum survives descriptor resolution.
- The Ollama project row can carry `max_tokens=8192` without a load-only proxy.
