---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, ai, config, observability]
---

# Start token estimation from recorded evidence

## Problem

Per-model token calibration becomes accurate after one provider attempt, but a
fresh cluster has no attempt facts. Its first prompt therefore used the old
four-characters-per-token arithmetic convention. The independent
model-authoring observer measured 34,798 prompt characters as 8,699 estimated
tokens against 10,766 provider tokens: 19.2% low.

The calibration design correctly refused to invent an error band without local
observations. That did not require retaining an unmeasured point estimate:
recorded evidence from the 2026-08-08 whole-system arc and the 2026-08-10
model-authoring drive consistently fit 3.21–3.23 characters per token.

Evidence:
[model authoring observer](../../../prds/sci-execution-runtime/research/model-authoring-observer-2026-08-10.md),
"Verdict 4 — token economics."

## Acceptance

- The shipped first-turn estimate is an explicit database-backed config
  decision whose provenance names the recorded evidence.
- With no local attempt facts, the calibration names itself as a shipped
  measured prior and carries no relative-error band or upper bound.
- Once local attempt facts exist, the per-model observed fit and its honest
  worst-error band supersede the prior exactly as before.
- The measured 34,798-character first prompt estimates within 2% of the
  provider's 10,766-token count instead of 19.2% low.

## Resolution — 2026-08-10

`:seon.config.ai/chars-per-token-prior` is now a registered cluster config fact,
shipped as 3.2 in `config/default.edn`. Its adjacent provenance records the 17
provider prompt samples and both research dates. `seon.cluster.prompt` passes
that effective fact into the existing `seon.ai.tokens/calibrate` owner; no
second estimator or budget decision was added.

The fallback basis is now `:seon.ai.tokens/shipped-prior`, with the 17-sample
provenance count and no `:seon.ai.tokens/relative-error`. The observed branch,
its fitted ratio, its worst-error band, and the loud near-limit verdict are
unchanged. Historical `:seon.ai.tokens/shipped-constant` values remain valid
schema data but are no longer produced.

The recurring regression estimates the observer's 34,798-character prompt at
10,874 tokens, a 1.0% miss against the provider's 10,766 tokens, and asserts
that the prior has no invented band. The existing observed-fit and near-limit
regressions remain green.

Focused proof: `bin/test seon.ai-test seon.ai.tokens-test
seon.cluster.prompt-test seon.dev.mcp-bridge-test
seon.config-application-test` passed 81 tests / 510 assertions / 0 failures / 0
errors. The config application census classifies the prior as next-turn live.
