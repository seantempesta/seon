---
type: issue
status: open
severity: friction
tags: [tokens, ai, config, derive-or-die]
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
