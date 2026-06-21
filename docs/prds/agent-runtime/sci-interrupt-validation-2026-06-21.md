---
type: research
status: active
tags: [research, agent]
---

# SCI interrupt — validation & how to test

This is the **downstream-testable** record of the fix approach for the tile/eval lockup: an
agent-authored function that does not terminate (an infinite loop, runaway recursion, or a
synchronous block) freezes the entire single-threaded Seon pod — heartbeat, HTTP, SSE, and every
other agent — until a manual restart.

## Status (read first)

- The approach is **proven** (the spike below), **designed** (the PRD,
  [[tile-isolation-prd-2026-06-21]]), and **IMPLEMENTED + live-verified** (2026-06-21) — Layer 1
  ships in `seon.render.sci` behind the `SEON_TILE_SCI` flag (default on).
- Two ways to exercise it: (1) **reproduce the standalone proof** by running the spike (below);
  (2) **test the live feature** on the pod (the "Test the feature" section below).

## The finding (what was proven)

A **synchronous** infinite loop cannot be interrupted from within the same JS thread — `try/catch`
never runs and the existing async eval timeout (`Promise.race` vs `js/setTimeout`) never fires
because the blocked event loop never schedules the timer. The fix is to run agent-authored tile
functions under **SCI** (the Small Clojure Interpreter), which controls its own eval loop and
fires a caller-supplied `:interrupt-fn` at the top of every interpreted `recur` iteration. A
wall-clock deadline in that `:interrupt-fn` aborts the loop in-process — no worker, no
serialization, the database value is passed by reference.

### Spike results (real runs on Node v24, SCI `0.13.53`)

| Check | `:none` (dev/watch) | `:simple` (release w/o `:advanced`) |
|-------|---------------------|-------------------------------------|
| `(loop [] (recur))` aborted | ✅ at 251ms vs 250ms budget, proper `:sci.impl/interrupt` marker | ✅ at 251ms, identical |
| Loop genuinely synchronous (no event-loop yield) | ✅ macrotask + microtask both unfired | ✅ identical |
| Un-catchable by hostile `catch` / throwing `finally` | ✅ `:swallowed? false` | ✅ identical |
| `js/Date.now` readable inside the blocked loop on Node | ✅ | ✅ |
| Per-render overhead | ~0.2ms warm median (~1000x under a 250ms budget) | ~0.2ms, identical |
| Compile | n/a | ✅ clean, 0 warnings |

`:advanced` is intentionally **not** validated — it is the only level that does the dead-code
elimination and global/property renaming that historically breaks SCI and malli in this codebase,
and the current target is the dev (`:none`) build (and `:simple` if a minified release is ever
wanted).

### What SCI does NOT cover (the residual class)

The interrupt fires only on **interpreted** entry. If a tile function calls into a **native host
loop** (a JS `while(true)`) or a **native regex** with catastrophic backtracking (ReDoS), the
interrupt never fires and the thread still blocks (the spike measured a 1.5s host loop and a ~33s
ReDoS, both unbounded). Bounding that class needs a killable process/worker (the PRD's Layer 2,
deferred). If the tile contract is "interpreted DB-query → hiccup" — the stated intent — Layer 1
covers essentially the whole realistic surface.

## How a downstream client tests it (reproduce the proof)

The spike is self-contained and committed at
`docs/prds/agent-runtime/spikes/sci-interrupt/` (see its `README.md`). It does not touch the live
pod or the shared build.

```bash
cd docs/prds/agent-runtime/spikes/sci-interrupt
clojure -M -m shadow.cljs.devtools.cli compile spike   # :none build (what the pod uses)
node out/spike.js
```

A passing run prints four labelled tests. The decisive line is TEST 1: a true synchronous
`(loop [] (recur))` is aborted within ~the deadline and the script CONTINUES (the process does not
hang), with the falsification probe confirming the loop never yielded the event loop. TEST 2 shows
the interrupt survives a hostile `catch`/`finally`. TEST 3 is the overhead budget. TEST 4 shows
the native-host residual class that Layer 1 cannot bound.

To also confirm `:simple`: add `:compiler-options {:optimizations :simple}` to the `:spike` build
in `shadow-cljs.edn`, then `release spike` instead of `compile spike`, and run the output — the
results are identical to `:none`.

## Test the feature (it is implemented)

Layer 1 lives in `src/seon/render/sci.cljs`; `seon.render/render-agent-tile` routes
AGENT-authored tile symbols through `seon.render.sci/invoke-bounded`. The core `welcome`, core
section fns, and literal hiccup stay on the fast compiled path untouched.

The design is a **pure safety net for hangs** — it never breaks a working tile. Only the wall-clock
INTERRUPT triggers recovery; ANY other SCI failure (missing source, env-reconstruction gap, a
genuine throw) falls through to the proven compiled `html-render` path. `invoke-bounded` is
outer-guarded so it can never throw — SCI may fail but it never crashes the pod.

The agent fn body must be INTERPRETED for the interrupt to fire, so its stored `:seon.fn/source`
is evaluated into the SCI ctx. Its lexical environment is rebuilt from the DB: the ns's `:require`
`:as` aliases + `:refer`s come from the stored `:seon.ns/source`, and each required `seon.*`/agent
namespace is exposed as SCI host vars by enumerating its members from the `:seon.fn` index. So an
agent tile written the normal way — `(db/query …)` with `[seon.db :as db]` in the ns — resolves and
renders under bounding. Both "define a new fn and wire it the same turn" and "wire an existing fn
symbol" keep working (and if the env can't be rebuilt yet, the tile renders compiled, unbounded
for that turn, with a one-time warn).

### What was verified LIVE on the pod (2026-06-21)

Driven by direct eval against the running pod (agent `gwM-…`), each via
`mcp__seon_cljs__eval` / `bin/seon`:

- **Hang is bounded, pod survives.** A tile wired to `(loop [] (recur))` →
  `render-agent-tile` returned the `welcome` fallback in ~316ms (not a forever-freeze); a
  `setTimeout` liveness canary fired (event loop resumed); `curl /agents` answered HTTP 200 in
  ~30–120ms throughout.
- **Self-heal + notify.** The hung tile's `:seon.render.live-tile/content` was retracted (→
  `welcome`); the agent received exactly ONE force'd message ("…did not terminate within 250ms and
  was reset…"). Deduped — repeated renders during the write-propagation window do not double-post.
- **Real aliased tile renders bounded.** An agent's own `(db/query …)` stats tile rendered under
  SCI in ~52ms with no error (alias `db→seon.db` reconstructed from the agent's `:seon.ns/source`).
- **A throwing tile shows a calm card + notifies.** A compiled tile that throws → the human sees the
  "Updating this panel" card (NOT a scary error), content is KEPT (so a fix takes effect), the agent
  is notified once, render returned in ~49ms (no crash).
- **The DeepSeek agent self-recovered end-to-end.** Woken by the warning, it wrote a proper
  terminating `db→hiccup` tile fn and re-wired it — which renders under SCI in ~7ms.
- **Flag toggles.** `SEON_TILE_SCI=0` → `bounding-enabled?` false (agent tiles use the compiled
  path); unset → on.

### Hardening from the adversarial review (2026-06-21)

A multi-agent adversarial review of the diff + new ns surfaced four real issues, all fixed and
re-verified:

- **`invoke-bounded` now validates its result** — a tile that returns a non-map under SCI falls
  through to the compiled path instead of tripping the `:map` return contract (non-brittle).
- **The throwing-tile notification dedup now clears** on a clean render (`note-tile-ok!`), so a
  tile that breaks → is fixed → breaks again re-notifies the agent, and the dedup set stays
  bounded (no slow leak).
- **`:require … :refer :all` is supported** — the whole referred ns is exposed by simple name, so
  such tiles run bounded (verified: an unqualified `installed-schema` resolved under SCI).
- **`notify-tile-error!`'s `sym` arg is `:symbol`** (was `:any`), consistent with
  `recover-hung-tile!`.

One finding (the recovery promise chains "leak an unhandled rejection") was a FALSE POSITIVE: the
`(fn [_] (when-let [m …] (m {…})))` callback returns the `message!` promise, so it IS chained and
the trailing `.catch` handles any rejection — no unhandled rejection, no crash.

### Reproduce the live hang test yourself

```clojure
;; in the pod REPL (mcp__seon_cljs__eval), against a live agent id:
(seon.db/transact!
  {:seon.db/tx-data
   [{:seon.ns/name :my.t}
    {:seon.fn/sym "my.t/hang" :seon.fn/ns [:seon.ns/name :my.t]
     :seon.fn/source "(defn hang [m] (loop [] (recur)))" :seon.fn/created-at (js/Date.)}
    {:seon.agent/id "<AGENT-ID>" :seon.render.live-tile/content 'my.t/hang}]})
;; then render — returns the welcome fallback within ~budget, never hangs:
(seon.render/render-agent-tile {:seon.agent/id "<AGENT-ID>"})
;; observe: content retracted to welcome, agent messaged, pod still answers HTTP.
```

## Related

- Design / decision / migration plan: [[tile-isolation-prd-2026-06-21]]
- The rejected reactive stopgap (external watchdog + crash-marker recovery), for comparison:
  [[tile-lockup-safety-2026-06-21]]
- Vendored SCI source (for review): `reference-code/sci` (tag `v0.13.53`).
