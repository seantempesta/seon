---
type: research
status: active
tags: [research, agent, cljs, pod]
---

# D13 Node-side probe — `*tx-context*` propagation across Promise boundaries

**Date:** 2026-05-22
**Probe:** v1.md §11 Risk 1 — does the v1-specified
`seon.db/*tx-context*` dynvar survive across Promise/await
boundaries reliably enough to be the auto-causality-bundle
mechanism?
**Runtime:** V0 CLJS pod (`node out/client/main.js`), shadow-cljs
session, CLJS 1.12.145 with native `^:async`/`(await ...)`.
**Outcome:** **CLJS `binding` is not safe.** It works only under a
single-binder invariant; concurrent binders silently clobber each
other across await yields. **Recommendation: don't use a CLJS
`^:dynamic` var at all. Use Node `AsyncLocalStorage` under the
hood, exposed as `seon.db/with-tx-context` + `seon.db/current-tx-context`
in CLJS.** Spec needs a small update; design intact.

## TL;DR

CLJS `binding` macroexpands to `(set! *var* new-val)` followed by
`(try ... (finally (set! *var* orig-val)))`. There is one global
slot per Var; the "binding stack" is just lexical try/finally
discipline. This is fine for single-threaded synchronous code and
correct when bindings strictly nest (inner finishes before outer
exits). It **breaks silently** when two bindings of the same Var
overlap in time across async yields.

Node's `AsyncLocalStorage` (from `node:async_hooks`) IS
fiber-local. The same adversarial interleaving that corrupts the
CLJS-`binding` case preserves correct per-async-context values
under ALS.

## Probe matrix

13 probes total. All ran in the V0 pod's `:client` build,
`cljs.user` ns, via `mcp__seon_cljs__eval`. `*probe*` is a stand-in
for a generic `^:dynamic` var; behavior is identical to whatever
`seon.db/*tx-context*` would have been.

### CLJS `binding` — single-binder cases (PASS)

| # | Setup | Reads | Result |
|---|---|---|---|
| 3 | `(defn ^:async f [] (binding [*p* :v] … (await …) *p*))` | After await | `:v` ✅ |
| 5 | Same, awaited Promise resolved by `setTimeout` (real async) | After await | `:v` ✅ |
| 6 | Multiple sequential `(await …)` inside one `binding` | After each await | `:v` ✅ |
| 7 | Outer `^:async` fn calls inner `^:async` fn that also awaits | Outer + inner reads | `:v` ✅ all |
| 8 | `(await rejected-promise)` caught by `try/catch` | In catch + after catch | `:v` ✅ |

**Conclusion:** `binding` survives `await` inside the SAME async
fn (and inside non-overlapping nested async fns), as long as
nothing else binds the same var concurrently. This is the
"happy path" the v1 spec assumes.

### CLJS `binding` — broken cases (FAIL)

| # | Setup | What read | Saw | Should have seen |
|---|---|---|---|---|
| 1 | `(binding [*p* :v] (.then promise cb))` at top level | `*p*` inside `cb` | `nil` | `:v` |
| 2 | `(binding [*p* :v] (run-async-fn))` — async fn reads `*p*` after its own await | After await | `nil` | `:v` |
| 4 | Inside `^:async` fn: fire-and-forget `(.then p cb)` (not awaited) | `*p*` inside `cb` | `nil` | `:v` |
| 13 | Two `^:async` fns each `binding` the same var, overlapping awaits | First fn's after-await | `:other-fn-value` | `:own-value` |

Probes 1, 2, 4 are the "binding finally already ran" cases — the
synchronous wrapper completes and pops before the microtask
continuation runs. Avoidable by structural discipline.

**Probe 13 is the dealbreaker.** Reproducible interleaving:

```
T+0  : A binds *p*=:A (sync set!, orig=nil)        ; *p* global = :A
T+0  : A awaits 20ms (yields)
T+5  : B binds *p*=:B (sync set!, orig=:A)         ; *p* global = :B
T+5  : B awaits 100ms (yields)
T+20 : A's promise resolves. A reads *p* → :B      ; WRONG
T+20 : A's binding finally → set *p* = nil         ; clobbers B's binding
T+105: B's promise resolves. B reads *p* → nil     ; WRONG
T+105: B's finally → set *p* = :A                  ; leaves stale value
```

Concurrent `binding` of the same Var across async yields **silently
corrupts both sides**. The CLJS macro is structurally incapable of
fiber-local scoping because Vars are JS globals.

### Node AsyncLocalStorage (PASS under adversarial interleaving)

| # | Setup | Reads | Result |
|---|---|---|---|
| 14 | Two `^:async` fns each `.run`'d with own store, overlapping awaits | Each fn's before + after | Own values ✅ |

```clojure
(def AsyncLocalStorage (.-AsyncLocalStorage (js/require "node:async_hooks")))
(def als (AsyncLocalStorage.))

(defn ^:async work-A []
  (let [before (.getStore als)
        _ (await (js/Promise. (fn [r _] (js/setTimeout #(r 1) 20))))
        after (.getStore als)]
    {:before (.-label before) :after (.-label after)}))

(.run als #js {:label "A"} work-A)
;; Concurrently:
(.run als #js {:label "B"} work-B)
;; A returns {:before "A" :after "A"}; B returns {:before "B" :after "B"}
;; Even under the same adversarial timing that broke probe 13.
```

ALS is implemented in V8 via `async_hooks` — it instruments the
async context propagation at the engine level. Microtask
resumption restores the context that was active when the await
yielded.

## V0 substrate state (verified 2026-05-22)

Greps confirm:

- `seon.db/*tx-context*` does NOT exist in V0. Phase 2.5
  introduces it; no migration risk.
- `:keep-history? false` appears at three call sites in
  `src/seon/client.cljs` (lines 136, 158, 390 per `grep -n`).
  Lines 136 + 158 are smoke-test conns; line 390 is the live
  agent conn (per the file's order). Phase 2.5 flips line 390
  only; smoke conns stay as they are unless we want history on
  them too. Confirm with Sean before flipping the others.
- `:tx-meta` is NOT used in `src/seon/db.cljs` today. Phase 2.5
  wires it.
- Kick handler IS state-guarded (`src/seon/agent.cljs:327-336`):
  `(when-not (= :running state) …)`. So in V0 the next-kick path
  cannot start a new turn while one is in flight — single-binder
  invariant holds for the only concurrent-call path.

## Implication for the v1 spec

The v1.md §2.3 design says:

> Auto-propagation. `seon.eval/eval-batch!` binds
> `seon.db/*tx-context*` around each form's eval;
> `seon.db/transact!` reads the binding and merges it into every
> tx's `:tx-meta`.

The mechanism described (a `^:dynamic` Var named `*tx-context*`)
is **NOT SAFE** for the spec's stated goal of "every tx in an
eval scope carries the bundle without manual plumbing." It will
silently drop the bundle the first time anything binds it
concurrently. We can't catch the bug in tests because the
clobber requires specific interleaving timing.

**Proposed spec amendment (small):**

- `seon.db/*tx-context*` is NOT a `^:dynamic` Var; it's an
  AsyncLocalStorage-backed primitive accessed via two fns:
  - `(seon.db/with-tx-context ctx-map f)` — establishes the
    context for the dynamic extent of `f` (which may be `^:async`
    and await; ALS preserves the store across any awaits inside
    `f` AND across awaits in any function `f` calls).
  - `(seon.db/current-tx-context)` — returns the current bundle
    map, or nil if no context active.
- `seon.eval/eval-batch!` wraps its per-form work in
  `with-tx-context` instead of `binding`.
- `seon.db/transact!` reads via `(current-tx-context)` and
  deep-merges into `:tx-meta`; explicit `opts.tx-meta` wins
  per-key.
- Agent-visible API doesn't change. Agents call
  `(seon.db/transact! …)` and the bundle auto-merges, same as
  before. They don't see ALS or the dynvar.

This makes v1 reliable under any concurrent execution shape we
might add later (cross-agent in one pod, kick listener races,
parallel tx streams). It costs ~30 LOC of CLJS to wrap ALS.

The only loss vs the dynvar story: agent code can't write
`(binding [seon.db/*tx-context* …] …)` to override the bundle.
Replace with: `(seon.db/with-tx-context {:overrides …} (fn [] …))`.
Slightly more verbose; not a hot path; clearer error
behavior when used incorrectly.

## Implication for the WASM-boundary probe (still open)

`AsyncLocalStorage` is a Node-specific API
(`node:async_hooks`). It does **not** exist in wasm-rquickjs /
QuickJS yet. This means:

- v1 ships on Node V0 pod with ALS — bulletproof there.
- Phase 3 (WASM cutover) needs a parallel mechanism. Options:
  - Polyfill ALS in QuickJS via a Rust-side host implementation
    (Tauri owns the async context; pod imports a WIT-typed
    `with-context` / `get-context`).
  - Explicit-arg threading on the WASM side only — every
    `transact!` call site receives the bundle as an arg.
  - Wait for QuickJS to ship native `async_hooks`-equivalent
    (unlikely soon).
- The Phase 3 D13 probe is now: "does ALS-equivalent exist or
  can it be provided in wasm-rquickjs?" — separate scope, not v1
  blocking.

## Updated Phase 2.5 patch

Items 1–3 unchanged. Item 4 becomes:

**4. `seon.db/with-tx-context` + `current-tx-context` + tx-meta
auto-merge.** Wrap Node `AsyncLocalStorage` in CLJS. Register
the 7 `:seon.db/*` tx-meta attrs at boot. `seon.db/transact!`
reads `(current-tx-context)` and merges into `:tx-meta`;
explicit `opts.tx-meta` wins per-key. KI-1 invocation-shape
precondition lives in the same patch. **Spec amendment to v1.md
§2.3 required first** — Sean sign-off.

Conflict rule for MVP's parallel scaffolding stays the same:
explicit `:seon.db/opts {:tx-meta …}` works today and continues
to win after item 4 lands. MVP's `run-turn!` code that passes
explicit `:tx-meta` doesn't need to change when the auto-merge
ships.

## Probe transcripts (preserved for archaeology)

All probes used `cljs.user/*probe*` as a stand-in dynvar with no
side effects beyond reads. Pod state was unchanged after probes
(no DB writes, no agent state mutation).

Reproducer for probe 13 (the dealbreaker):

```clojure
(def ^:dynamic *probe* nil)

(defn ^:async fn-A-tight []
  (binding [*probe* :A]
    (let [_ (await (js/Promise. (fn [r _] (js/setTimeout #(r 1) 20))))]
      *probe*)))

(defn ^:async fn-B-tight []
  (binding [*probe* :B]
    (let [_ (await (js/Promise. (fn [r _] (js/setTimeout #(r 2) 100))))]
      *probe*)))

;; Start both. A's await is shorter; A resumes WHILE B still bound.
(.then (fn-A-tight) #(prn :A-saw %))  ;; prints :A-saw :B  ← WRONG
(.then (fn-B-tight) #(prn :B-saw %))  ;; prints :B-saw nil ← WRONG
```

Reproducer for probe 14 (the fix):

```clojure
(def AsyncLocalStorage (.-AsyncLocalStorage (js/require "node:async_hooks")))
(def als (AsyncLocalStorage.))

(defn ^:async work-A []
  (let [_ (await (js/Promise. (fn [r _] (js/setTimeout #(r 1) 20))))]
    (.-label (.getStore als))))

(defn ^:async work-B []
  (let [_ (await (js/Promise. (fn [r _] (js/setTimeout #(r 2) 100))))]
    (.-label (.getStore als))))

(.then (.run als #js {:label "A"} work-A) #(prn :A-saw %))  ;; :A-saw "A" ✅
(.then (.run als #js {:label "B"} work-B) #(prn :B-saw %))  ;; :B-saw "B" ✅
```

## Files touched

None. Probe was REPL-only. The 14 probes mutated `cljs.user`
vars (`*probe*`, several `result-N` atoms) but no agent state,
no DB, no production source.
