---
type: issue
status: open
severity: cleanup
tags: [issue, pod, database]
---

# Bun 1.3.14 segfaults on AsyncLocalStorage.enterWith in ESM top-level continuations

## Problem

Bun 1.3.14 (macOS Silicon) crashes with
`panic(main thread): Segmentation fault` when `AsyncLocalStorage.enterWith`
is called from a continuation of the ESM top-level (top-level-await) scope —
either inside a `setTimeout` callback or after a top-level `await`. Node
v26.4.0 runs the identical code correctly. Minimal deterministic repro:

```js
import { AsyncLocalStorage } from 'node:async_hooks';
const als = new AsyncLocalStorage();
als.enterWith({ v: 'boot' });
setTimeout(() => { als.enterWith({ v: 'refreshed' }); }, 20);
await new Promise((r) => setTimeout(r, 60));   // <- segfault resuming this
```

Not affected: the same `enterWith` inside an ordinary async function (CJS or
ESM), and `.run` anywhere. The pod's shadow-cljs bundle shape is therefore
unexposed today, and the ALS per-operation config design deletes Seon's only
`enterWith` caller (`seon.db.internal/enter-tx-context!`), removing the class.

## Evidence

[[../../prds/source-cleanup/research/als-config-probe-2026-07-20]] — probe
transcripts, Bun crash-report links, and the Node comparison run
(2026-07-20).

## Acceptance criteria

- No `enterWith` remains in `src/` (lands with the per-operation config
  boundary work), or
- a Bun release fixes the crash and the minimal repro above runs clean under
  the pinned pod Bun; retest before any future `enterWith` reintroduction.
