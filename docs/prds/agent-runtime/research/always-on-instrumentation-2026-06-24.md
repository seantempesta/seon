---
type: research
status: completed
tags: [research, agent, schema]
---

# Always-on Malli instrumentation — current state, the async gap, and the plan

## What shipped (2026-06-24)

Implemented + live-verified on the pod (branch `feature/agent-fsm`):

- **Async fns are now instrumented** (were skipped entirely). Routing in
  `seon.instrument/register-target!`, driven by the COMPILED fn shape
  (`cljs$lang$maxFixedArity` — nil ⇒ simple single-arity):
  - sync fn → Malli stock wrapper (input+output), unchanged.
  - async **simple single-fixed-arity `:=>`** → `async-fschema`, a custom
    Malli function-schema OBJECT whose `-instrument-f` validates input
    synchronously and validates the RESOLVED value in a `.then` (output on
    Promise resolution). Reuses ALL of Malli's var-surgery — no custom
    registry. **31 of the schema'd async fns** take this path.
  - async **variadic / `:function`** (e.g. `seon.db/transact!`) → Malli
    stock with per-fn `{:scope #{:input}}` (input+arity; output deferred —
    a derived-Promise wrapper across Malli's variadic arg-marshalling is
    separate work, flagged as a follow-up task).
- **`SEON_INSTRUMENT` kill-switch** (default ON) — `seon.instrument/enabled?`,
  read by both `install!` (boot) and `eval.cljs/instrument-tee-fns!`
  (runtime/agent/REPL defs). `0`/`false`/`off`/`no` disables. Verified:
  boot logs "instrumentation DISABLED", 0 instrumented, pod healthy.
- Boot: **205 fns instrumented** (transact! excluded via skip-syms), agent
  resume replay **13/13 OK**. `bin/test-cljs` green.
- Surfaced + fixed one **real latent bug**: `seon.eval/result-var-ref?`
  declared `:boolean` but returned `nil` for empty input
  (`(and (seq s) …)` → nil) — broke resume once async/empty-source paths
  exercised it. Fixed to `(boolean (and (seq s) …))`. Blast radius was
  exactly this one fn (full ns sweep clean after the fix).
- Humanized errors confirmed end-to-end: the envelope carries
  `:seon.error.malli/humanized` (Malli `me/humanize`) and renders as the
  `;; ERROR malli/instrument-input … reason should be a string` block.

### Opt-out for safe-by-default fns (resolved)

`seon.db/transact!` documents "SAFE BY DEFAULT — never throws, returns
data" and has 4 `db-test` cases asserting it returns a `{::db/ok? false}`
envelope for a bad invocation shape. Instrumenting its INPUT made it THROW
`:malli.core/invalid-input` instead (the wrapper sits outside the fn body,
before its own try/catch) — breaking the tested contract. Resolved with an
opt-out: `seon.instrument/skip-syms`, a set of FQ `[ns sym]` pairs that
`register-target!` skips.

Important gotcha discovered: the opt-out CANNOT live in fn metadata OR in
the schema's properties — the **CLJS analyzer strips both** from the
`:malli/schema` value the `collect!` macro reads (verified: a
`[:function {:seon.instrument/skip true} …]` schema came back property-less
from the registry even after a clean two-pass rebuild). A FQ-symbol set
needs nothing from the analyzer.

### CRITICAL: clean-build instrumentation gap (found + fixed)

`collect!` reads global `ana/all-ns` at macroexpand time, and it was being
expanded inside `install!` in the LEAF ns `seon.instrument.cljc` — which
compiles early, when almost nothing is analyzed. In a **clean build** that
registered only **3 nses / 25 fns** (`seon.db`, `seon.schema`,
`seon.test.runner`); everything else (incl. all of `seon.eval`,
`seon.agent`, `seon.ctx`…) got NO boot instrumentation. Incremental
`cljs-watch` rebuilds masked it by accumulating analyzer state (→ ~188), so
it looked fine in dev but was effectively OFF in production / after a
cluster reset.

Fixed by expanding `collect!` from the ENTRY ns (`seon.client`, compiled
last, full transitive closure): `install!` no longer calls `collect!`;
`seon.client` does `(when (enabled?) (collect!))` before `(install!)` via
`:refer-macros [collect!]`. Clean-build result: **48 nses / 205 fns**;
`result-var-ref?` instrumented again. Full writeup +
reproduction: issue `instrumentation-collect-clean-build-empty`. Malli's
own CLJS `collect!` defaults to `{:ns *ns*}` (per-ns self-registration) —
the seon global-scan deviated, which is what made it ordering-fragile.

## TL;DR

Most of what the user asked for **already exists and is on by default** in
the CLJS pod. The real, large gap is that **`^:async` fns are skipped
entirely** — and ~half the runtime is async (121 `^:async` fns vs 272
`:malli/schema` annotations). So today "validate everything" is false for
async fns: they get neither input nor output validation. Closing that gap
+ an env-var kill-switch + a non-crashing reporter for live (non-eval)
contexts is the work.

Humanized errors already ship (`me/humanize` in `seon.error.instrument`).
There is no `clojure.spec` here — "spec" = Malli schema colloquially.

## What already exists (works, on by default)

1. **Boot-time instrumentation** — `seon.instrument/install!`, called from
   `seon.client/-main` (`client.cljs:2380`). A compile-time macro
   `collect!` walks every FIRST-PARTY CLJS namespace via the cljs analyzer
   (`cljs.analyzer.api/all-ns` + `ns-publics`), finds every def carrying
   `:malli/schema`, and emits `(m/-register-function-schema! …)` calls to
   populate `malli.core/-function-schemas*` (the atom `mi/instrument!`
   reads — CLJS has no JVM-style `collect!` that reads source files). Then
   `(mi/instrument! {:report ei/report-fn})` wraps each var **in place**.
   - In-place var mutation is why **every** caller is covered: agent eval,
     MCP `seon_cljs/eval`, socket REPL, UI-click handler — all hit the
     wrapper. No per-call-site opt-in.
2. **Eval-path instrumentation** — `eval.cljs/instrument-tee-fns!`
   (`eval.cljs:1208`, called at `:2587`). Runtime-authored fns (agent or
   REPL/MCP `defn`) get registered + instrumented as they are tee'd to the
   program graph. So REPL-defined fns are instrumented too (sync ones).
3. **Structured + humanized error envelope** — `seon.error.instrument`.
   The reporter `report-fn` throws an `ex-info` whose ex-data is a rich
   envelope: `me/humanize` output, `:expected` / `:got` / `:got-type`,
   coercion hints ("use (keyword x)…"), did-you-mean for missing keys.
   Flows through `seon.error/->map` into `:seon.eval/error-data`, rendered
   by `render-malli-error` as the `;; ERROR malli/instrument-input …`
   block the agent reads in recent-evals.
4. **JVM track** — `seon.dev.instrumentation` (Integrant
   `:seon.dev/instrumentation`), rich `agent-reporter`, ghost-schema
   pruning across reloads. This is the PAUSED track (`./bin/run`); the
   live runtime is the pod + the `wire-server` writer.

## The mechanism (reference-code dive)

`malli.instrument/instrument!` → `-strument!` → per registered (ns,sym):
`-replace-fn` swaps the JS var binding for `(m/-instrument opts original)`.
The wrapper (`core.cljc:2209`):

```clojure
(fn [& args]
  (let [args (vec args), arity (count args)]
    (when wrap-input  … (report ::invalid-arity …) (report ::invalid-input …))
    (let [value (apply f args)]                 ; <-- SYNC return assumed
      (when (and wrap-output (not (validate-output value)))
        (report ::invalid-output …))
      (when wrap-guard … (report ::invalid-guard …))
      value)))
```

For an `^:async` fn, `(apply f args)` is a **Promise**, and
`validate-output` checks the Promise against a schema describing the
RESOLVED value → always `::invalid-output`. That is exactly why
`seon.instrument/collect-registrations` filters out `:async` defs and
`eval.cljs` does the same. Confirmed the schemas describe the resolved
value: `db/transact!` is `^:async` with
`[:=> [:cat ::transact-request] ::transact-response]` where
`::transact-response` is the awaited envelope.

`:scope` is configurable per the wrapper: `#{:input :output :guard}` →
we can instrument async fns with `:scope #{:input}` to get input
validation immediately with zero false output failures.

## Gaps vs. the ask

| Ask | Status |
|-----|--------|
| Enabled for all schema'd CLJS fns | sync ✅ / **async ❌ (skipped)** |
| Live for all REPL sessions | ✅ (in-place var wrap + eval tee) |
| Live for all invocations (agents, UI) | sync ✅ / async ❌ |
| Validate everything at all times | ❌ until async closed |
| Feed errors to agents | ✅ eval path; ⚠️ live non-eval path throws |
| Humanized messages | ✅ `me/humanize` |
| Env var, default on | ❌ none today (hardcoded on) |
| Don't crash shit | ⚠️ reporter throws everywhere |

## Plan

### 1. Close the async gap (the big one)

Add an **async-aware instrument wrapper**: input validates synchronously
(throw/report as today); when the return is thenable, attach
`.then`/`.catch` that validates the RESOLVED value against the output
schema and reports on mismatch, then re-resolves/-rejects unchanged. Stop
filtering `:async` out of `collect-registrations` + `collect-instrument-
targets`; route async fns through the async wrapper, sync fns through
malli's stock wrapper. Existing schemas need no change (they already
describe resolved values).

Cheap intermediate if the async wrapper is risky: instrument async fns
with `:scope #{:input}` first (input validation now, output later).

### 2. Env-var kill-switch — `SEON_INSTRUMENT` (default ON)

Gate `instrument/install!` and `eval.cljs/instrument-tee-fns!` on
`SEON_INSTRUMENT != "0"/"false"`. Default ON. Mirror `SEON_NO_AUTO_BOOT`
style. Lets us disable in one place if it ever destabilizes the pod.

### 3. Non-crashing reporter for LIVE contexts ("don't crash shit") — KEY DECISION

The reporter currently THROWS on every violation. In the **eval/REPL**
path that is correct — the throw becomes the eval-result envelope the
agent reads. But in **live, non-eval** paths (UI-click handler, agent-loop
internals, a background render), a throw can crash a render or wedge the
loop. Two regimes:

- **eval/REPL context** → throw (envelope becomes the eval result). Keep.
- **live/background context** → **record the violation as a datom + log,
  do NOT throw.** A section function then surfaces "live schema
  violations" to the agent (reactive-context: fix the bug → the violation
  stops recurring → the surface vanishes; self-healing, nothing to clear).

This is the one genuine design fork to confirm with the user before
building. It is also the most on-brand answer to "feed errors to the
agents without crashing shit": derive a violations surface, don't throw in
the live system.

### 4. (Optional) wire-server / JVM

The `wire-server` writer is JVM (`seon.server.*`). It is the sole DB
writer; its public fns could run `seon.dev.instrumentation/start!` behind
the same env var. Lower priority — the pod is the active surface and the
agent-facing one. Note it; defer unless the user wants it.

## Open questions for the user

1. **Reporter regime (§3):** throw in eval, record-don't-throw in live —
   agreed? Or throw everywhere (simpler, but can crash UI/loop)?
2. **Async output validation:** full await-then-validate wrapper now, or
   input-only (`:scope #{:input}`) first and output later?
3. **Scope:** pod only, or also instrument the JVM `wire-server` writer?
