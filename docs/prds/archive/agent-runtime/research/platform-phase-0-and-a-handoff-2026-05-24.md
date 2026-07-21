---
type: research
status: active
tags: [research]
---

# Platform Phase 0 + Phase A — handoff brief for implementation agent

**Purpose:** Pre-digested context for the next Platform implementation agent to pick up Phase 0+A from the [[../STATUS]] migration plan (items 2-8). MVP is fully blocked on this queue. Each item has a research source already on disk; this brief points at it and frames the discrete deliverable.

## D13 status — COMPLETE

The ALS-survives-Promise probe was done 2026-05-22, captured in [[impl-finding-tx-context-promise-2026-05-22]]. Outcome: **CLJS `binding` is unsafe across async boundaries; use Node `AsyncLocalStorage` instead.** This is the substrate the eval-batch ALS fix (item 2) builds on. WASM-side ALS-equivalent is a Phase 3 question, not v1-blocking.

## Items requiring implementation (in order)

### Item 2 — `eval-batch!` ALS fix (HARD prereq for MVP item 10)

**Research source:** [[eval-batch-fragility-2026-05-23]] §"Option 1 — Install handler ONCE at boot, dispatch via fiber-local bucket (RECOMMENDED)"

**Problem:** `raw-eval` (`src/seon/eval.cljs:313-332`) uses `set!` on `cljs.analyzer/*cljs-warning-handlers*` to capture per-eval warnings. CLJS dynamic vars have no per-fiber binding stack — concurrent agent evals silently cross-wire warning buckets. Multi-agent v1 cannot ship while this is in place.

**Fix:** Install a per-fiber dispatcher ONCE at compile-state init. The dispatcher reads a per-eval warnings-bucket from an `AsyncLocalStorage`. Each `raw-eval` opens an ALS scope (`als.run`) with its own bucket; the global handler reads via `(.getStore als)`.

**Concrete deliverable:** ~30 LOC in `src/seon/eval.cljs`:
- New `defonce warnings-als` (Node `AsyncLocalStorage` instance).
- New `install-warning-dispatcher!` fn — idempotent; sets `ana/*cljs-warning-handlers*` to a closure reading from `warnings-als`.
- Call `install-warning-dispatcher!` from `init-bootstrap!` / `ensure-bootstrap!` (whichever owns the cljs.js init path; check `seon.repl/ensure-bootstrap!`).
- `raw-eval` body wraps the cljs.js call in `(.run warnings-als #js {} (fn [] …))`, then the handler closure pushes into the bucket via `(.getStore als)`.
- `defonce ^:private warning-dispatcher-installed?` version-stamped against `seon.eval/init-version` so hot-reload reinstalls.

**Verification:** REPL probe analogous to probe 13 + 14 in [[impl-finding-tx-context-promise-2026-05-22]] §"Probe transcripts." Two concurrent `eval-batch!` calls with overlapping awaits and shared undeclared-var pattern; each should see ONLY its own warnings.

**No agent-visible API change.** `raw-eval` returns the same shape.

### Item 3 — `truly-undeclared?` `defonce` false-positive fix

**Status:** Sean's locked decision #3 covers the multi-agent registry stance but does NOT explicitly call out this fix. MVP is right that it's not in the original 10. **Sean confirmation needed**, but the fix itself is straightforward and on the "would bite bulk-load resume" critical path per [[../STATUS]] Phase 0 item 3.

**Problem:** `defonce` returns `:ok? false` in `eval-batch!` despite working at runtime. The culprit: `truly-undeclared?` (`src/seon/eval.cljs:285-294`) checks warnings for `:undeclared-var` but `defonce` macroexpands to a form that the analyzer warns on during first compilation (the var is being defined, not used — but the analyzer doesn't always know that for `defonce`-style forms). The globalThis fallback works for runtime resolution but not for the warning suppression.

**Suggested fix path:** When the warning's `:extra` indicates the var being warned about is the SAME var that the form is defining (head-position `def` / `defonce` / `defmacro` etc.), suppress the warning. Need to inspect the warning payload shape; the fix is likely "filter warnings against the form's binding head before consulting `truly-undeclared?`."

**Effort:** ~1-2 hours of REPL probing + ~10 LOC.

**Verification:** Eval `(defonce my-thing (atom 0))` in agent's home-ns — `:ok?` should be `true`.

### Item 4 — `seon.schema/current-keys` accessor

**Research source:** [[../STATUS]] decision #2; trivial.

**Deliverable:** 3-line PR in `src/seon/schema.cljc`:

```clojure
(defn current-keys
  "Snapshot of all currently-registered schema keywords. Used by
   detect-and-tee in eval-batch! for atom-diff schema detection."
  []
  (set (keys @*schemas)))

```

Test: `(count (seon.schema/current-keys))` returns the current registration count.

### Item 5 — `shadow-cljs.edn` `:bootstrap :entries` expansion

**Research source:** [[../STATUS]] Phase A item 5; deliverable is mechanical.

**Deliverable:** Edit `shadow-cljs.edn`. The `:bootstrap` build target's `:entries` vector currently lists `cljs.core` + `cljs.core$macros` (verify by reading the file). Add:
- `seon.schema`
- `malli.core`
- `malli.registry`
- `cljs.analyzer.api`
- (whatever `malli.instrument` transitively pulls in — let shadow-cljs warn if missing)

**Effort:** ~15 minutes including verification rebuild.

**Verification:** `clj -M:cljs compile bootstrap` succeeds; resulting `out/bootstrap/` size grows by ~2MB (per the plan estimate); `(require '[malli.core])` from the pod's eval succeeds without "no such namespace."

### Item 6 — bundle `malli.instrument` + transitive deps

**Deliverable:** Add `malli.instrument` to the `:client` build's required namespaces (`src/seon/client.cljs` `:require`, OR the appropriate eval-smoke entry depending on which build the agent runs in). Verify the bundle compiles and the resulting `out/client/main.js` (or pod equivalent) grows by ~150KB.

**Subtle bit:** `malli.instrument` calls `malli.dev`-adjacent code that may pull in heavier deps. Check the bundle delta after add and trim if needed via shadow-cljs `:exclude`.

**Effort:** ~30 minutes.

### Item 7 — build-time `(mi/collect!)`

**Research source:** [[../STATUS]] Phase A item 7. `mi/collect!` walks loaded nses and collects `:malli/schema` metadata into Malli's `-function-schemas*` atom.

**Deliverable:** A boot-time call to `(malli.instrument/collect! {:ns 'seon.*})` after all `seon.*` nses load, before `mi/instrument!` runs. Lives wherever the boot sequence is orchestrated (`seon.client/main!` or `seon.repl/dev-init!`).

**Caveat:** `mi/collect!` reads source files at JVM build time on the JVM side, but on CLJS it reads from `-function-schemas*` populated by the compiler. Verify the CLJS path actually works for our `:malli/schema` metadata pattern. If not, the alternative is `mi/-collect!-from-meta` walking the analyzer env.

**Effort:** ~1-2 hours including verification.

### Item 8 — `mi/instrument!` boot hook + `:seon.eval/error-data` attr

**Research source:** [[instrumentation-error-envelope-2026-05-24]] (full design done by prior agent); [[../STATUS]] decision #8.

**Deliverable in two coherent commits:**

(a) **`:seon.eval/error-data :map` attr** — register the schema in `seon.agent` (or wherever `:seon.eval/*` lives, check the current bootstrap-attrs list). Add to `agent-bootstrap-attrs` vector. **Sean confirmation noted: decision #8 explicitly calls this out.**

(b) **`mi/instrument!` boot hook + reporter wiring**:
- Reporter fn lives in a new (or existing) `seon.error.instrument` ns. Constructs the canonical envelope per [[instrumentation-error-envelope-2026-05-24]] §Q4.
- Boot call: `(mi/instrument! {:report seon.error.instrument/report-fn :scope #{:input :output}})` after `mi/collect!`.
- `eval-batch!`'s error-handling path catches the instrumentation exception, extracts the envelope from `(ex-data e)`, attaches it to the eval entity as `:seon.eval/error-data`.
- Renderer update in `format-eval-row` (or equivalent in `seon.render.default`) — render the 5-line block per [[instrumentation-error-envelope-2026-05-24]] §Q5.

**Effort:** ~4-6 hours including the renderer integration.

**Verification:** REPL probe — define a fn `(defn ^{:malli/schema [:=> [:cat :int] :int]} f [x] (str x))` (output schema violation); call `(f 1)`; the resulting `:seon.eval/error-data` map should contain `:seon.error/kind :malli.core/invalid-output` + `:seon.error.malli/fn-sym`, `:value`, `:explain` per the envelope spec.

## Sean confirmations needed before implementation starts

1. **Item 3 (`truly-undeclared?` `defonce` fix)** — not in the original 10 decisions but on the Phase 0 critical path. Confirm we should ship.
2. **`:seon.eval/error-data` attr (item 8a)** — implicit in decision #8 but worth explicit nod since it modifies `agent-bootstrap-attrs`.

If Sean greenlights both: ship in the order above. Items 4 and 5 are sub-day standalone PRs and could land first as warmup; items 2 and 8 are the substantive ones; items 6 + 7 are bundle/build mechanical work in between.

## Order recommendation for the implementation agent

Day 1: items 4 + 5 + 6 (small, mechanical, unblocks item 7's deps).
Day 2: item 7 + item 3 (the defonce fix needs REPL probing time).
Day 3-4: item 2 (eval-batch ALS — the substantive substrate change).
Day 5: item 8 (instrumentation wiring + renderer + envelope attr).

Total estimate: ~5 working days for a focused implementation agent.

## What MVP unblocks once each item lands

- Item 2 → MVP item 10 (`eval-batch!` detect-and-tee modifications).
- Items 4 + 5 → MVP item 9 (`seon.analyzer-info` shared module — can theoretically start without these but cleaner after).
- Items 6 + 7 + 8 → MVP can rely on instrumentation in their test scaffolding for items 10+.
- Item 3 → unblocks MVP's bulk-load resume work in Phase D.

## What this brief deliberately does NOT cover

- Phases C / D / E. Those are downstream of Phase 0+A landing and have their own research files. The Platform implementation agent shouldn't pre-emptively touch them.
- The multi-agent v1 work (scaffold + ALS for agent-id propagation) from [[multi-runtime-architecture-2026-05-24]] §14. That's also downstream — it depends on item 2 landing first (same ALS substrate), but is a separate work-item queue. Ship Phase 0+A first; the multi-agent v1 work picks up after.

## Reference

- Migration plan: [[../STATUS]] §"Revised migration plan (16 items, 5 phases)"
- D13 result: [[impl-finding-tx-context-promise-2026-05-22]]
- Item 2 design: [[eval-batch-fragility-2026-05-23]]
- Item 8 design: [[instrumentation-error-envelope-2026-05-24]]
- Multi-agent v1 fix list (downstream of this brief): [[multi-runtime-architecture-2026-05-24]] §14
