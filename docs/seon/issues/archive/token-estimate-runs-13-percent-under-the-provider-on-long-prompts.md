---
type: issue
status: resolved
severity: friction
tags: [issue, tokens, ai, config, derive-or-die]
created: 2026-09-14
---

# `seon.ai.tokens/estimate` runs ~13 % under the provider's billed count on long prompts

## Observed (default, 2026-09-14, after the per-turn rebuild fix `63ac0608a`)

| turn | rebuilt bytes | estimate | billed prompt_tokens |
|---|---:|---:|---:|
| 404bfad994bc | 10,420 | 3,256 | 3,222 |
| 3ded5c0bc8a4 | 13,129 | 4,102 | 4,217 |
| a51f8821e5be | 111,859 | 34,955 | 40,169 |
| 1fb7c9d46552 | 177,378 | 55,430 | 63,514 |

The reconstruction is faithful (bytes track the turn). The estimator's
`:seon.config.ai/chars-per-token-prior 3.2` is right for prose-heavy short
prompts and ~13 % low once the context is mostly EDN (DeepSeek tokenises
`#:seon.repl{:value …}` denser than 3.2 chars/token).

## Wanted (derive, don't tune)

The prior is derivable: every attempt stores the prompt's bytes (via the
rebuilt context) and the billed `prompt_tokens`. Derive the agent's
effective chars-per-token from its own recent attempts when they exist and
fall back to the config prior only before the first attempt; show
"rebuilt ≈N tokens · billed N" on the debug page so drift is visible.
The prompt-token budget check should use the derived figure.

## Owner and resolution — 2026-09-15

The token-estimate slice containing this note adds `recent-calibration` to
`seon.ai.tokens` and scopes the prompt owner's existing capture/usage join
to the agent, model, and latest ten billed attempts. The prompt budget and
debug ledger use that fit. Before the first billed observation, the agent's
effective config prior applies. No stored calibration or new cache is needed.

## Acceptance

Recovered all 30 original run-7 captures from immutable commit
`6aa966c2-14ef-5256-b705-84912d5451f3`, totaling 244,933 billed prompt tokens.
Each of the last ten attempts is predicted using only preceding observations;
the largest relative error is **2.222%**, below the requested 5%.
The committed fixture fails loudly if the captures are absent. Canonical
database tests verify agent isolation, recent ordering, and config fallback.

Live Chrome observation on default also showed the derived ledger label,
including **rebuilt ≈12,737 tokens · billed 12,523** on the newer current run.
Exact evidence, gates, and boundaries are in the
[landing note](../../../prds/context-generation/research/page-speed-and-estimate-landing-2026-09-15.md).
