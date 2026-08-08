---
type: issue
status: open
severity: friction
tags: [issue, render, agent]
---

# Model reasoning perturbs the agent AI projection's elision

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
