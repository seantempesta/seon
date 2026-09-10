---
type: issue
status: open
severity: friction
tags: [issue, ai, config, docs, wave/ai-provider-protocol]
---

# Reconcile provider references and weekday pricing semantics

## Problem

The September 10 trial verified the thinking toggle and corrected Flash's
numeric prices, but exposed adjacent stale provider assumptions outside that
bounded trial assignment.

## Evidence

- `docs/seon/reference/llm-adapters.md` still names `deepseek-v4-flash`,
  `resources/seon/schema.edn`, `seon.cluster.loop`, and agent overlays on
  `:seon.cluster.agent/id`; current owners are the split schema resources,
  `seon.turn`, and `:seon.agent/settings` components.
- `.agents/skills/llm-providers/SKILL.md` retains obsolete model IDs and
  line references in its shipped-row section; default switched in
  `abc021957` to `deepseek-flash`.
- The provider's [thinking documentation](https://api-docs.deepseek.com/guides/thinking_mode/)
  retrieved September 10 says `top_p` takes effect in thinking mode with
  a 0.95 floor and is fixed at 1.0 in non-thinking mode. The production
  `seon.ai/thinking-inert-settings` still classifies it with the opposite
  behavior. No sampling dial was configured for this trial.
- The [official rate card](https://api-docs.deepseek.com/quick_start/pricing/)
  now specifies weekday-only peak windows. Existing
  `:seon.ai.model/deepseek-off-peak-windows` facts represent daily clock
  windows only. The corrected Flash row omits those inaccurate refs;
  the trial explicitly computes its verified off-peak cost. Pro prices
  and the legacy Flash row in the live default database still carry
  their old values. The trial's captured candidates preserve that evidence.
- At `2026-09-10T20:03:42.541342Z`, default's Flash row still carried
  0.14/0.0028/0.28 despite the edited manifest; file publication is not
  evidence of live adoption. The trial selected `deepseek-flash`, tied
  with its legacy alias, and made exactly one paid call.

## Owner

`seon.ai`, provider model schema/config declarations, and the provider
reference and skill. The trial did not alter sampling behavior, implement
time-dependent billing, reconcile other provider rows, or restart default.

## Acceptance

Verify current provider documentation, reconcile maintained references,
test sampling-field construction through actual descriptor rows, and
represent weekdays before claiming exact time-dependent prices. Compare
manifest rows with adopted default facts through the supported config path.
No paid request is necessary for these request/data checks.
