---
type: issue
status: resolved
severity: blocker
tags: [issue, web, cljs]
---

# Plan HTML renderer does not match the render interface

## Problem

`my.plan.internal/plan-block-html` accepts only its render request, while the
existing dynamic HTML render interface supplies the render request plus the
nested-render callback. The compiled Bun child therefore instruments the
function against a one-argument schema and the root plan surface renders
`:malli.core/invalid-arity`.

## Evidence

After all maintained correctness suites passed, the real browser rendered the
root page with a visible `plan` surface error containing
`:malli.core/invalid-arity`. The browser console was otherwise clean. Source
inspection showed the neighboring AI plan renderer and the other dynamic
context renderers already accept the common two-argument interface.

## Owner

`my.plan.internal/plan-block-html` owns the plan HTML render. It must implement
the existing dynamic render function interface directly; no adapter or second
renderer is required.

## Acceptance

- The function schema and arguments accept the render request and nested-render
  callback.
- A focused test invokes the renderer through that two-argument interface.
- The complete root page renders the plan surface without a Malli or render
  error in the browser, pod log, or child result.

## Resolution

Commit `90a7cb29` makes `my.plan.internal/plan-block-html` implement the common
two-argument render interface. Focused plan proof passes 22 tests / 72
assertions. The live root page renders `plan — no plan yet` with a clean browser
console and no render fault in the pod log.
