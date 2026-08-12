---
type: issue
status: resolved
severity: friction
tags: [issue, agent, ai, config, provider]
---

# Preserve per-agent credential selection through provider resolution

## Problem

`:seon.config.ai/api-key-variable` is declared per-agent, and
`seon.ai/agent-overlay` reads the supplied value, but model descriptor
resolution silently overwrote it with the provider descriptor's credential
variable. A caller therefore could not disable or redirect one agent's
provider transport through the declared per-agent setting.

## Evidence

On 2026-08-11 the minimum-context premise probe transacted
`:seon.config.ai/api-key-variable "SEON_ABLATION_PROBE_NO_CREDENTIAL_7F49C"`
onto agent `premise` in isolated cluster `ablate`. The subsequent real turn
still called DeepSeek and recorded provider-counted usage.

The cause was `seon.ai/resolved-target`: it associated the descriptor's
credential-variable name after effective per-agent settings had resolved.

## Resolution — 2026-08-12

Provider resolution now fills the descriptor credential only when the
effective target has no `:seon.ai/api-key-variable`. An explicit selection is
therefore structurally preserved, while an absent selection still inherits the
provider descriptor default.

One regression resolves a verified-absent explicit variable through the model
descriptor, calls `seon.ai/complete`, and proves a flat `:seon.ai/no-credential`
value with zero HTTP-leaf calls. A second proves descriptor fallback when the
effective setting is absent.
