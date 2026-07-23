---
type: issue
status: open
severity: defect
tags: [issue, agent, ui, architecture]
---

# my.canvas :clj branch references a missing seon.render.canvas fn

## Problem

`src/my/canvas.cljc` is nominally portable, but its `field-signal` helper's
`:clj` branch (my/canvas.cljc:38-46) calls `seon.render.canvas/field-signal`
— a function that does not exist anywhere (`rg field-signal src/` matches
only my/canvas), in a namespace that is `.cljs`-only
(`src/seon/render/canvas.cljs`). Requiring `my.canvas` on the JVM therefore
fails at load: the `(:require [seon.render.canvas :as render-canvas])` has no
CLJ artifact, and even with one the fn is absent.

## Evidence

- my/canvas.cljc:42-43 — `#?(:clj (render-canvas/field-signal field) :cljs
  (str signal-prefix (.toString (.from js/Buffer …) "base64url")))`.
- `src/seon/render/canvas.cljs` top-level defs contain no `field-signal`.
- Prior grounding: the c2 js-bound audit
  (`docs/prds/sci-execution-runtime/research/c2-js-bound-audit-2026-07-20.md`
  §"field-signal") already named `java.util.Base64` as the JVM port shape;
  the CLJ branch was written against that intended port before it existed.

## Owner

`seon.render.canvas` (the port that must supply the base64url field-signal
projection — stage R3 of
`docs/prds/sci-execution-runtime/research/render-ctx-portability-research-2026-07-23.md`),
with `my.canvas` unchanged once the fn exists.

## Acceptance

- `(require 'my.canvas)` succeeds on the JVM.
- `field-signal` produces byte-identical signal names on both platforms for
  the same field keyword (base64url of the printed keyword, `seon_` prefix).
- The my.canvas census rows stop being blocked by this dangling reference.
