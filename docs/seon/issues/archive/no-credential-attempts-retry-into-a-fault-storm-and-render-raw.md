---
type: issue
status: resolved
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

## 2026-09-09 faults-render verification

AI static disposition is terminal (`3f26b8af3`, 52 tests / 236 assertions).
The live isolated credential probe still produced two turns, one ordinal-zero
attempt each, and four fault rows. The loop records the attempt error and
terminal settlement independently, and later admits another turn. This issue
remains open: fixing AI disposition alone does not establish one fault.
The lane requested permission to extend its loop ownership beyond no-provider;
see the dated faults-render landing note for the exact boundary and seed proof.

The no-provider setting and fixture landed in `a4a0d457c`; canonical gate
1 test / 13 assertions. Scratch reseed produced zero provider attempts,
provider faults and token usage. The default served faults concern was then
measured at 11,618 bytes, nine error cards, zero `items, depth` occurrences.
The committed HTML/AI evidence and browser-tool limitation are recorded in
[the landing note](../../prds/context-generation/research/faults-render-landing-2026-09-09.md).
The remaining defect is duplicate loop settlement/automatic new turns.

## 2026-09-09 turn owner completion

The terminal attempt branch now closes without minting a second fault.
The work reader derives deferral from the closed failed provider turn;
pending wakes remain unanswered until a new outside wake permits opening.
The canonical no-provider-absent, credential-absent regression measures
one fault, one attempt, one closed turn and no next work. It also verifies
that a new outside message permits opening. Isolated gate: 40 tests /
215 assertions; platform: 83 tests / 490 assertions; both green.
Together with the earlier fixture and rendering proofs above, this resolves
the issue. Exact paths and live adoption evidence are in the
[turn landing note](../../prds/context-generation/research/turn-rename-landing-2026-09-09.md).
