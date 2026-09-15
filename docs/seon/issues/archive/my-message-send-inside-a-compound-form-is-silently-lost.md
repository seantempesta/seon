---
type: issue
status: resolved
severity: blocker
tags: [my.message, effects, turn, core-functions, live-test]
created: 2026-09-15
---

# `my.message/send` inside a `let`/`do` is silently lost; the model believes it reported

## Observed (live run 7, 2026-09-15 15:39Z; the model's account in `research/explain_probe_run7_2026_09_15.edn`)

The model's step-6 form was one `let` that queried the live orders, called
`largest-customer`, wrote the note with `my.note/add!`, and called
`(my.message/send {:my.message/to "root" …})` — with the note write as the
last expression. The evaluation's shown value is `{:customer "Ada",
:total 155}`; no message from Juniper exists in the database. The model:
"I … sent root a message with the customer and both totals." Then it
marked steps 6 and 7 complete.

## Why

`my.message/send` "returns an addressed message for the turn to deliver"
— an effect as a returned value, interpreted only when it is the FORM's
result. Nested inside a compound form its value is discarded and nothing
says so. `my.note/add!` writes directly, so the two agent-facing writes
behave differently for the same call shape.

## Wanted

- `my.message/send` writes the message and its inbox edge itself (the
  turn supplies identity through call preparation, as `my.note/add!`
  already does); its return is the written message with its id. The
  loop's disposition interpretation stays for `done`/`wait` only, and
  those are documented as "must be the reply's last form".
- Regression on the canonical harness: `send` inside a `let` delivers;
  `done` inside a `let` returns a flat error naming the rule.
- Help says which calls must be a reply's last form (only `done`).

## Resolution — 2026-09-15

`my.message/send` and `decline` now write through the message owner's
transaction function with call-prepared connection and sender identity.
The loop no longer interprets message-shaped return values. The loop proof
delivers a send discarded inside `let` and records a flat rule error for
nested `done`, including a discarded nested result. The message test checks
the stored identity, inbox edge, answered message, and refused recipient.
Evidence and the exact gate boundary:
[run7-wave landing](../../../prds/context-generation/research/run7-wave-landing-2026-09-15.md).
