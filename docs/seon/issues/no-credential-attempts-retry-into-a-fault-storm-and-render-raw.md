---
type: issue
status: open
severity: blocker
tags: [issue, ai, faults, render, debug-page, fixture, class/total-boundary]
---

# A missing provider credential retries into nine faults in two seconds, and fault entities render as raw maps

Observed 2026-09-09 01:44 on a freshly reforked `default` right after the
Juniper fixture was seeded: Juniper woke on its seeded messages, the
provider attempt found `OPENROUTER_API_KEY` / `SEON_DESIGN_LAB_NO_CREDENTIAL`
unset, and NINE `:seon.ai/no-credential` faults were committed to the agent
between 01:44:30 and 01:44:31 — the retry policy retried a condition that
cannot change between attempts. On the debug page those faults appear in
the declared faults concern as raw entity maps through the generic printer
("map 19 items, depth 1 {:db/id 97763, :seon.error/agent …"), four printer
fallbacks on an otherwise clean page.

Three defects, one owner each:

1. **Retry.** A `:seon.ai/no-credential` (and any refusal whose cause is
   static configuration) is terminal for the attempt: one fault, no retry.
   The retry policy retries transport and rate-limit outcomes only.
2. **Fixture intent.** The fixture names a deliberately absent variable to
   mean "no provider". That intent is a per-agent SETTING (the settings
   component: a virtual/no-provider model), not an env var that fails at
   the seam and leaves a fault behind. Yesterday a scratch boot of the
   same fixture paid 6,472 tokens to DeepSeek because the intent was not a
   setting.
3. **Render pair.** `:seon.error` entities carry `seon.error/render-ai`
   and `render-html` on their schema, yet the faults concern renders them
   through the generic printer. The concern block must render each fault
   through its declared pair: HTML = kind, message, at, the function, the
   turn it happened in, and a link; AI = the flat error value the agent
   would see in its REPL.

Proof: reseed on a fresh cluster → at most one fault for the missing
credential, or none when the fixture's settings say no provider; the
faults block shows no "items, depth" text.
