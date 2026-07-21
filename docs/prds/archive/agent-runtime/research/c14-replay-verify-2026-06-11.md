---
type: research
status: active
tags: [research, agent]
---

# C-14 VERIFY — agent fn replay on pod boot, post-B4

**Verdict: FIXED by B4 (72f6aab) — the indexOf/undefined replay-death
class is dead. Live-proven 2026-06-11 23:34Z with a 3-agent restart.**
The ADJACENT audit finding (self-defeating-surfaces finding 5) is
confirmed RED: a replay failure surfaces ONLY in the disk log, never in
any agent's rendered context — fix-unit spec below (size S).

## TL;DR

- Minted 2 probe agents on the live cluster store, each with a
  persisted `(ns …)` row (one requiring host-bundled `my.kb` — the
  exact B4 class — one requiring `seon.db`), 2 `:seon.fn` rows each,
  and a wired live tile (`:seon.render.live-canvas/content` = qualified
  fn symbol). Plus one deliberately-broken ns row
  (`(:require [no.such.ns])`) as the forced-failure probe.
- `bin/seon restart pod` → roster resumed all 3 active agents; replay
  `{:replay-n-total 9 :replay-n-ok 8 :replay-n-fail 1}` — the ONLY
  failure is the deliberate bad row, with the legible error
  `Could not require no.such.ns <- ns no.such.ns not available`.
- `grep -c indexOf logs/pod.log` → **0**. Post-boot, both agents' fns
  were callable via their munged globalThis paths and BOTH tiles
  rendered as real hiccup tiles (`[:div [:h3 "C-14 probe one"] …]`)
  with no re-wiring — no raw entity dumps.
- `logs/pod-events.log` archaeology corroborates the timeline: the
  17:36–17:37Z entries (pre-B4, agent vGq-2606111337) show the exact
  downstream class — `replay of ns :my.kb.instruction failed: Could
  not require my.kb <- ns my.kb not available` cascading into
  `Cannot set properties of undefined (setting 'instructions_block')`
  per def. B4 landed 18:16. Every boot since (23:31Z replay 2/2,
  23:34Z 8/9 with only the deliberate failure) is clean.

## Why B4 kills the downstream repro

Read of `git show 72f6aab`:

- `seon.eval/guarded-load` (src/seon/eval.cljs ~line 410): `boot/load`
  throws `ns X not available` for any ns absent from the bootstrap
  bundle's index. Host-bundled nses (`my.kb`, `seon.db`, …) are live on
  globalThis but NOT in that index. The fallback answers such loads
  with empty `:js` when `ns-live-on-globalthis?` — so
  `(:require [my.kb])` succeeds at define time AND on replay.
- `seon.client/ensure-target-ns!` (src/seon/client.cljs ~line 694):
  now requires the analyzer entry AND the live JS object before
  skipping the bare-`(ns)` heal. Pre-B4, a half-failed `(ns …)` eval
  left an analyzer entry with no JS ns object; every subsequent def in
  that ns died on `undefined` — the downstream "Cannot read properties
  of undefined (reading 'indexOf')" / "Cannot set properties of
  undefined" crashes, and the un-rehydrated tile fns falling back to
  raw entity dumps.

The downstream repro was cut 17:42, B4 landed 18:16 — the repro
predates the fix by 34 minutes. Both legs of their symptom (19 WARNs,
2/4 tiles dumping raw) are the one root B4 removed.

## Live evidence (all observed in the running system)

Pre-restart (pod pid 67441, cluster store via wire-server DIS):

- agents minted: `FqR-2606111933`, `yPU-2606111933`
- 6/6 live evals ok, including `(ns my.agent.FqR-… (:require [my.kb :as kb]))`
- tx of 2 `:seon.ns` + 4 `:seon.fn` + 2 tile wirings + 1 bad ns row:
  `{:seon.db/ok? true}`
- both `(seon.render/render-agent-tile {:seon.agent/id …})` → hiccup

Post-restart (pod pid 69288):

- `logs/pod.log` 23:34:06–07Z: roster
  `[FqR-2606111933 nme-2606111920 yPU-2606111933]`; replay 9/8/1; the
  single WARN names the deliberate bad ns with the legible require
  error (NOT indexOf).
- `(js/goog.getObjectByName "my.agent.FqR_2606111933.c14_double")` →
  callable, `(f 21)` → 42; same for yPU's `c14-triple`.
- Both tiles re-render identically with zero re-wiring.

## Finding 5 (self-defeating-surfaces) — CONFIRMED RED

With the forced failure present, the owning agent's
`(seon.ctx/assemble-context {:seon.agent/id "yPU-…"})` was searched:

- `"replay of"` / `"log-replay-failure"` → **absent** from the
  rendered text. (The bad ns SOURCE renders in the `<namespace>`
  inventory — looking healthy — which is worse than silence: the agent
  sees the ns as code it owns, with no signal that it failed to load.)
- The `<warnings>` section is the `seon.warn` CODE-HYGIENE check
  registry (no-malli-schema, missing-test) — it has no replay check.
- Structurally unfixable via DB query today: `seon.log` deliberately
  writes NO datoms (ns docstring "Why no DB rows") — replay failures
  exist only in `logs/pod.log` + `logs/pod-events.log`.

### Fix-unit spec (size S)

Per the reactive-context principle, the surface should be a derived
section, not stored state — but the failure facts currently live
off-DB. Two compatible options; (a) is the principled one:

a. **Derived section over boot state**: `replay-program-graph!`
   already computes the pass-2 failure list; stash it in the
   process-lifetime `seon.client/!state` (genuinely-stateful runtime
   artifact, like compile-state) keyed by owning ns → agent. Add a
   `replay-failures-section` to `seon.ctx/substrate-default-ctx` that
   renders only when the list is non-empty AND the entry's ident is
   still missing from the live compile-state (self-healing: agent
   redefines the fn → query empties → section vanishes). ~2 files
   (client.cljs, ctx.cljs) + test.
b. Minimal: have the section read `seon.log/tail` filtered to
   `:seon.client/log-replay-failure!` since boot. Cheaper but
   acknowledgement-shaped (stale entries linger until rotation).

Either way, fix the **attribution bug** in the same unit: replay is
global, but `log-replay-failure!` stamps `:seon.log/agent` with the
PRIMARY agent (`agent-id` arg = first resumed), not the row's owner —
live: my FqR-tagged WARN for yPU's corpus; pod-events 17:37Z entries
tag vGq for `my.kb.instruction` rows (substrate corpus!). Derive the
owner from the entry's ns (`target-ns-for-entry` → `my.agent.<id>` →
id; substrate/unowned → no agent tag).

## Smells reported (out of scope, not fixed)

1. **`[open-todos] render failed: :malli.core/invalid-input`** in the
   LIVE assembled context of every agent (observed 23:36Z, both probe
   agents). A substrate section is crash-looping per render and
   surfacing its error string into every prompt. File:
   src/seon/ctx.cljs (open-todos section) — likely an
   instrumented fn called with a wrong-shaped request. Deserves its
   own small unit.
2. **Stale unsupervised pod, pid 65066** (started 2026-06-11 19:26,
   `node /Users/sean/src/seon/out/client/main.js`, absolute-path
   spawn — not via bin/seon). No listening ports, no store files →
   pure in-memory world. It is one of the TWO `:client` runtimes on
   the shadow watcher, and the MCP `default` session had silently
   pinned to it: my first repro round (agents EiA-2606111928,
   fKP-2606111928 + rows) landed in its throwaway world and survives
   only in that process's memory — could not be reached for
   `complete!` (no runtime hosts those ids; no I/O channel). Killing
   pid 65066 erases them and removes the pinning ambiguity; left
   running because another concurrent agent may be attached.
   Trap for every future unit: **always pass `agent_id` (a resumed
   agent's id) to `mcp__seon_cljs__eval` when the target is the live
   pod; never trust session `default`.**
3. `assemble-context` returned byte-identical text (197693 chars) for
   two different `:seon.agent/id` inputs — the per-agent parts
   differed under inspection per-section, so likely coincidental
   equal-length, but worth an eye if context cross-contamination ever
   suspects.

## Cleanup performed (hygiene)

- `complete!` → FqR-2606111933 and yPU-2606111933 (`{:seon.agent/ok?
  true}` both); active roster back to `#{nme-2606111920}`.
- Retracted all 7 seeded program-graph entities (eids 944–950,
  including the bad ns row — replay is GLOBAL, so leaving it would
  WARN on every future boot) + both tile-content datoms.
  `query-program-graph-entries` post-cleanup returns only the
  pre-existing B4 proof rows (`my.canvas.proof14`).
- Pod left healthy: pid 69288, `/agents` → 200, heartbeat live.
