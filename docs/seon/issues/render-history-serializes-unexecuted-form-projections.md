---
type: issue
status: open
severity: blocker
tags: [issue, render, runtime, class/n11, wave/generated-receipts]
---

# Execute generated form projections before they enter history

## Problem

The current provider path serializes each neighborhood member's declared
`:seon.render/form` beside its declared `:seon.render/ai` output and appends the
pair directly to retained prompt history. It never executes that form and does
not query or join an eval receipt.

The resulting bytes look like a remembered REPL exchange even though the form
was never evaluated. Ruling 24 requires the generated opening to execute as an
ordinary system-authored run with real receipts; ruling 28 permits executed
receipts, not a render-time simulation of them.

## Evidence

`src/seon/render/walk.clj:741-785` joins form units to value units only by walk
lookup and path, then writes `namespace=> form` plus the already rendered value.
No receipt identity, run identity, authorship, or terminal result participates.
`src/seon/render/walk.clj:787-808` returns those direct observations as the
entire generic history.

`src/seon/render/web.clj:997-1044` calls this history function during provider
context acquisition and appends its bytes to the retained prompt. Thus this is
not an inactive preview: unexecuted render projections currently feed the
provider.

The in-flight generated-opening change makes the gap visible at the root:
`src/seon/cluster/agent.clj:114-120` now returns a `{comment, form}` map from
the situation form render, while `generic-history-entries` admits only a
sequential form at `src/seon/render/walk.clj:777`. This audit does not edit
either production owner.

## Owner

`seon.render.walk/ordered-episode` owns generated form order;
`seon.cluster.run` owns system-run execution and receipts; the render proc owns
retained history only after those facts exist.

## Acceptance

- A generated form projection enters prompt history only after an ordinary
  system-authored run records that exact source and a terminal eval receipt.
- The history value is read from the receipt's admitted result/error/output,
  never copied from the pre-execution AI projection.
- `{comment, form}` remains accretion-open input to episode construction, but
  the comment and form cannot appear as settled history without their executed
  receipt.
- One recurring proof shows that a generated form whose execution returns a
  value different from its pre-execution render displays only the actual
  receipt value.

## Partial correction — 2026-09-06

`seon.render.transcript/history-entries` now emits only durable submitted form
sources and stored evaluation results. It no longer converts message facts or
undisposed-run facts into synthetic forms paired with current rendered values.
Those facts remain independently visible through their existing family and
HTML renderers.

This issue remains open because `seon.render.walk/generic-history-entries`
still performs the separate neighborhood `:seon.render/form` plus
`:seon.render/ai` pairing described above. Removing that provider-context path
requires the stored generated-run integration; the transcript boundary fix
does not claim that work complete.
