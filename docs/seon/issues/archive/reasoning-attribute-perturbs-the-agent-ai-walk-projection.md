---
type: issue
status: resolved
severity: friction
tags: [issue, render, agent]
---

# Model reasoning perturbs the agent AI projection's elision

## Resolution (2026-08-08, render floor repair lane)

The "likely cause" guessed below (the render-selection recursion guard) is
REFUTED. The real cause is a total that describes a value its producer no
longer renders.

`seon.render/render-ai` stamps `:seon.render.data/total (count value)` onto the
context BEFORE calling the producer (`src/seon/render.clj:102-105`).
`seon.ai/attempt-ai` then withholds three keys, so the elision machinery
compares 9 counted children against 8 rendered ones and reports one phantom
omission — the trailing `…`. Reasoning itself was never shown; only the marker
appeared, and only because the count was stale.

Fixed at the producer that reshaped the value: `attempt-without-reasoning`
(`src/seon/ai.clj`) restates `:seon.render.data/total` for what it actually
renders. A producer that withholds keys owns the total of its own projection;
the caller's count describes the caller's value.

Live falsifier before the fix (cluster `default`, `eval_clj`, one attempt map
rendered twice through `seon.ai/attempt-ai`):

```text
without reasoning → {…, :seon.ai/model "m"}
with reasoning    → {…, :seon.ai/model "m", ...}
```

Proof after: `bin/test seon.render.transcript-test` → 12 tests, 207
assertions, **0 failures, 0 errors**, with
`reasoning-is-html-only-and-inline-blob-history-has-one-disclosure` asserting
the byte-identity it always claimed. `bin/test --changed src/seon/ai.clj
--changed src/seon/render/transcript.clj` → 213 tests, 1,161 assertions, 0
failures.

## Problem

`:seon.ai.attempt/reasoning` is meant to be HTML-only — invisible to agent
context. `seon.ai/attempt-ai` exists precisely to render an attempt WITHOUT it
(`src/seon/ai.clj:94-107`, `attempt-without-reasoning` dissocs
`:seon.ai.attempt/reasoning`, `-reasoning-blob`, `-reasoning-size`, declared as
the attempt's producer in `resources/seon/schemas/seon.ai.edn:7-8`). Yet adding
reasoning to an attempt entity changes the AI walk projection's bytes: the
attempt map renders through the value floor WITH reasoning counted, so one more
child elides (a trailing `…`) even though reasoning itself is not shown.

`seon.render.transcript-test/reasoning-is-html-only-and-inline-blob-history-has-one-disclosure`
asserts (`transcript_test.clj:648`) the complete agent projection is
byte-identical when reasoning appears. It fails:

```text
before: …:seon.ai/model "fixture-thinking"}
after:  …:seon.ai/model "fixture-thinking", …}
```

Both hide reasoning; only `after` carries the elision marker, because the
`after` attempt map has one more key (reasoning) inflating the child count.

## Likely cause

The declared `attempt-ai` producer is not effectively stripping reasoning at
the walk LEAF. The most probable interaction is the render-selection recursion
guard added by the render-proc stop-completion fix
(`src/seon/render.clj:294-317`, the `:seon.render/rendering` chain set): a
producer that renders its own value through the floor is refused re-entry and
"falls through to its children" — but the children it falls through to appear
to be the ORIGINAL (unstripped) attempt value rather than the
reasoning-stripped one. This needs a virtual-thread-aware probe of
`project-node*` for the attempt entity to confirm which value's children the
refusal falls through to.

## Owner / scope

Owner is the render-selection / walk / attempt-producer seam:
`src/seon/render.clj` (selection recursion guard), `src/seon/render/walk.clj`
(leaf value rendering), and the `seon.ai/attempt-ai` producer
(`src/seon/ai.clj`). NONE of these is the transcript floor
(`src/seon/render/transcript.clj`) or `seon.render.value` — the render floor
repair lane that found this owns only those, so it did not touch the cause.

## Evidence

- Falsified by code inspection: `seon.render.transcript/reasoning-is-html-only…`
  exercises `full-agent-ai` → `walk/neighborhood` + `walk/prose`
  (`transcript_test.clj:44-56`), a path independent of the transcript floor.
  The render floor repair lane changed only `transcript.clj` and
  `resources/seon/schemas/seon.render.edn` (schema declarations, no rendering
  logic), so this failure is present with or without that change — pre-existing.
- Reproduces deterministically: 1/1 failure across three runs of
  `seon.render.transcript-test`, always this one assertion.

## Acceptance

- Adding `:seon.ai.attempt/reasoning` (inline or blob) to an attempt entity
  leaves the agent AI walk projection byte-identical (basis aside).
- One regression asserts the class: reasoning never enters the AI-side child
  count of the attempt block, whatever the render selection path.
- `seon.render.transcript-test` is fully green.
