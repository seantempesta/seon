---
type: issue
status: open
severity: friction
tags: [issue, ai, wave/provider-context]
---

# Cheapest configured trial provider refused for insufficient credits

The cookbook's one authorized comprehension request selected
`deepseek/deepseek-v4-flash-20260731` through the configured OpenRouter descriptor.
The credential existed and its priced estimate was **$0.00071448** for the
8,104-byte request and 2,048-token output bound. The provider returned **HTTP 402**
in **229 ms**, with `limit_source: openrouter_credits`, request transmitted,
response started, and no output observed.

No model reply, usage, or comprehension score is available. The original
harness incorrectly scored absent text as 1/12; its assessment now reports
unavailable evidence. The original response and corrected assessment are retained
in `docs/prds/context-generation/research/context_cookbook_trial_2026_09_09.edn`
and `context_cookbook_trial_assessment_2026_09_09.edn` in the same directory.

No second provider request was made: the assignment permits one harness run.
A successful follow-up needs credits for the selected provider or an owner
instruction to use another configured candidate in a new trial. A present
credential alone does not prove that a priced provider can serve a request.
