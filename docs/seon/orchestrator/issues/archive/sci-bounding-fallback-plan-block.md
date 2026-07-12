---
type: issue
status: resolved
tags: [issue, agent]
---

# SCI-bounding fallback — `my.plan.internal/plan-block` renders on the UNBOUNDED path

Severity: friction (latent blocking — the warning itself says an eventual hang
would freeze the pod). Lane: tooling (render mechanism). Found by the
post-merge acme smoke, 2026-07-02. **FIXED 2026-07-02** (both the root cause
and the fail-loud ruling), live-verified on acme.

## Symptom (was)

On every fresh acme boot (`logs/acme/pod.log`):

```
tile fn my.plan.internal/plan-block could not run under SCI bounding
(Unable to resolve symbol: db/*conn*) — rendering it on the UNBOUNDED compiled path
```

The tile still rendered, but via the unbounded compiled path — a hang in that
fn would wedge the pod with no bound to cut it.

## Root cause (found — refines the candidate)

The candidate ("require aliases not stored") was right, with a precise
mechanism: `seon.agent.ctx.namespaces/full-source-ns?` excluded HIDDEN nses
(`hidden-ns-name?` — any `*.internal`) before the `my.*` rule, so the boot
indexer (`seon.client/ns-row`) stored only the `(ns my.plan.internal)` STUB as
`:seon.ns/source`. The SCI cage rebuilds a render fn's lexical environment —
its `:require` `:as` aliases — from that stored source; the stub carries no
`(:require [seon.db :as db])`, so `db/*conn*` could not resolve and the fn
fell off the bounded path. (`*conn*` itself enumerates fine off the live
`seon.db` ns object via `ns-data-members` — proven by the new A-3 test.)

## The fix

1. **Root** — `full-source-ns?` now lets `my.*` WIN over the hidden rule:
   every `my.*` ns (including `.internal`) stores its REAL full file text.
   Render exclusion of `.internal` is untouched — `included-ns?` keeps it out
   of the prompt regardless of what is stored (storage ≠ selection).
2. **Fail-loud (owner ruling)** — `seon.render.sci/invoke-bounded` no longer
   returns `{::fallthrough true}`; when SCI cannot run a `my.*` render fn it
   returns `{:seon.render.sci/error <:seon.db/error map>}` and BOTH callers
   (`seon.render/render-agent-canvas`, the `resolve-render` slot path) render a
   `:seon/error` block IN PLACE via the ONE error mechanism (tile →
   `canvas/error-response`; slot → the walker guard → `canvas/error-tile`,
   both overridable seams — acme's overrides verified intact). The unbounded
   compiled fallback is GONE; the never-wedge safety property holds
   unconditionally. The one-per-sym log warning stays (new message names the
   fail-loud behavior).
3. **Unwrap-parity** — `valid-result-for-view?` now mirrors `seon.render`'s
   unwrap tolerance (envelope map OR bare hiccup vector / ai string / nil), and
   `::result` is the registered return schema. Without this, fail-loud broke
   bare-hiccup slot fns (`acme.world/world-tile`) that the old fallthrough had
   silently masked; the TILE caller still enforces its map-envelope contract
   with a legible in-place error.

## Acceptance — live evidence (acme, 2026-07-02)

- Fresh `bin/acme cluster reset` + boot + `/data` + `/agent/root` +
  `/agent/root/debug` fetches: **ZERO** "could not run under SCI bounding"
  warnings (pre-fix the same `/data` fetch warned deterministically).
- Stored source live-read via the wire REPL (7981): `:seon.ns/source` for
  `:my.plan.internal` = 23,465 chars, carries `[seon.db :as db]` (was the
  23-char stub).
- Bounded-path proof: `test/seon/render/sci_unspecced_helper_test.cljs` A-3
  reproduces the exact shape (ns requiring `[seon.db :as db]`, fn derefing
  `@db/*conn*`) → `invoke-bounded` returns the real render. With fail-loud
  there is no silent third path: an agent-authored sym either runs bounded or
  errors loudly — plan-block's silence IS the bounded path.
- Fail-loud live proof: wired `my.probe/bad-tile` (body references an
  un-required `bogus/x` alias) onto root's canvas → pod log
  `19:21:53 WARN … my.probe/bad-tile could not run under SCI bounding (Unable
  to resolve symbol: bogus/x) — rendering a :seon/error block in place` and the
  canvas rendered acme's OVERRIDDEN error card in place (override seam intact),
  never an unbounded render. Same observed for the deliberately-throwing
  `acme.widget/broken-tile` demo.

Related: [[tx-feed-pump-timeouts]] (same smoke). Channel entry:
`docs/prds/agent-ctx/coordination.md` 2026-07-02.
