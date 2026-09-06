---
type: issue
status: resolved
severity: friction
tags: [issue, render]
---

# Debug selected value keeps the enclosing entity's renderers

## Evidence — 2026-09-06

`debug-page-result` applies `seon.render.data/at` to the URL cursor for the
structural display, but sends the entire acquired entity to `render-call` and
both candidate experiments. Thus two adjacent inspection sections describe
different values without saying so.

Live browser reproduction on `lab-browser-0906`, subject 32011, path
`[:my.plan.item/title]`: the structural value is the title string, but AI's
chosen renderer is `my.plan/render-item-ai`. The expected scalar floor renderer
is not selected. The browser probe
`docs/prds/context-generation/research/debug_selected_value_probe_2026_09_06.cjs`
fails positively on that mismatch before the correction.

## Correction

Derive the selected value once through the existing cursor function and use it
for the structural view, actual output and paired candidate experiments. Keep
the enclosing entity's subject, refs and viewer; label the selected path and
provide a return-to-entity link. Missing paths must show the existing diagnostic
instead of silently rendering the whole entity. Include path in retained call
identities so simultaneously inspected paths cannot overwrite each other.

## Live verification

Hot-reloaded the corrected `seon.render.web` JVM Var definitions and restored
instrumentation using the handed cluster projection. The browser probe now
clicks the stored title value, positively observes the AI and HTML scalar floor
outputs, and returns to the entity through the visible link. An invalid path
shows `no-such-path` instead of the plan item. No JavaScript errors; the graph
still selects entity 32011. Database basis remained 536871436 before and after
the browser sequence. This is hot-reload evidence on the existing branch, not a
claim that its indexed source was replaced.

The header now labels acquisition request bounds as bounds, rather than implying
they are measured work/result counts. The displayed duration remains measured.
