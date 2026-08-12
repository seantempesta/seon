---
type: issue
status: open
severity: friction
tags: [issue, agent, ai, config, provider]
---

# Preserve per-agent credential selection through provider resolution

## Problem

`:seon.config.ai/api-key-variable` is declared per-agent, and
`seon.ai/agent-overlay` reads the supplied value, but model descriptor
resolution silently overwrites it with the provider descriptor's credential
variable. A caller therefore cannot disable or redirect one agent's provider
transport through the declared per-agent setting.

## Evidence

On 2026-08-11 the minimum-context premise probe transacted
`:seon.config.ai/api-key-variable "SEON_ABLATION_PROBE_NO_CREDENTIAL_7F49C"`
onto agent `premise` in isolated cluster `ablate`. The subsequent real turn
still recorded attempt `57891a0f-f7c8-4934-9196-bf054036848b-attempt-0` against
`https://api.deepseek.com/chat/completions`, model `deepseek-v4-flash`, with
7,149 provider-counted prompt tokens.

The two sides of the mismatch are visible in current source:

- `resources/seon/schemas/seon.config.ai.edn:1-2` declares the credential
  variable with `:seon.config/per-agent true`, and `src/seon/ai.clj:321-331`
  includes it in the agent overlay.
- `src/seon/ai.clj:345-365` then associates the provider descriptor's
  `:seon.config.ai/api-key-variable` after the overlay has been selected,
  replacing the agent value without a refusal or diagnostic.

The scratch root was stopped immediately after the recorded attempt. The
ablation harness now intercepts `seon.cluster.prompt/prompt` directly and does
not rely on this broken setting.

## Owner

The one `seon.ai/targets` / `resolved-target` precedence chain between an
agent overlay and its selected provider descriptor.

## Acceptance

- A per-agent credential-variable selection either reaches the final request
  unchanged or is refused loudly as an unsupported setting; it is never
  silently replaced.
- One regression proves a verified-absent per-agent credential variable makes
  no network attempt and leaves the pre-provider prompt capture queryable.
- Provider descriptor facts continue to supply defaults when the agent has no
  override.
