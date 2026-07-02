---
name: clojurescript
description: "ClojureScript semantics for the Seon CLJS pod. Use when writing or debugging .cljs in the pod, when agent-eval'd forms behave unexpectedly, when working with ^:async / await / js/Promise, when a Promise leaks into agent output, when defs don't persist across eval-str, when instrumentation throws :malli.core/invalid-output on a Promise, or when reaching for core.async in the pod. Use when you see cljs.js, seon.eval, the bootstrap compile-state, or self-host compilation."
---

# ClojureScript -- Seon CLJS Pod Semantics

You run inside a long-running Node CLJS process (the pod). Every form you
submit is compiled and run at RUNTIME by the pod's **self-host / bootstrap
compiler** (`cljs.js`, a cljs-in-cljs compiler) against the process-shared
compile-state. This is a real, full-featured CLJS compiler -- but it is not
identical to how the pod's own core code was built ahead-of-time, and that
gap is the source of almost every surprise below.

## `^:async` and `await` -- the core feature

You have no `core.async`. Asynchrony is native JS `async`/`await`:

- `await` is a **macro**, not a special form. It asserts it is inside an
  `^:async` fn body and emits raw JS `(await ~{})`. **It only expands inside
  an `^:async` fn body.**
- A `^:async` fn compiles to a native JS `async function` and returns a
  `js/Promise` at the JS level.

```clojure
;; the normal pattern for a fn that needs to await something -- e.g. a
;; verb you write in your own home ns that waits on a write:
(defn ^:async bump! [_]
  (let [env (await (seon.db/transact! {:seon.db/tx-data [{:my.agent.me/counter 1}]}))]
    (:seon.db/ok? env)))
```

**Defining a `^:async` fn with internal `(await ...)` WORKS** in your eval —
proven live: evaluating `(defn ^:async f [x] (await (js/Promise.resolve (inc
x))))` then `(f 41)` compiles and runs cleanly.

**A top-level `(await x)` in a single form you submit FAILS.** A top-level
form has no async env, so macroexpansion throws `"await can only be used in
async contexts"` — proven live: submitting a bare `(await (js/Promise.resolve
1))` throws exactly that. Do NOT write a bare top-level `(await result/<id>)`
-- it cannot work. Either wrap the `await` inside an `^:async` fn, or let the
auto-await mechanism (below) resolve the Promise for you.

## Promise handling in your eval -- data by default

You get DATA, not Promises. Every form you submit in a normal turn: if its
value is a `js/Promise`, the runtime awaits it and records the **resolved
value** for you. You never type `await` at the top level; calls to `^:async`
verbs (`seon.db/transact!`, `my.plan/plan!`, `my.plan/done!`, …) feel
synchronous — you write `(seon.db/transact! {...})` and read back the
envelope directly, same turn.

- Bounded by a default timeout (~10s) or a one-shot longer budget for a
  legitimately slow op.
- On timeout the recorded value is a `:seon.eval/pending` placeholder and
  the still-running Promise is stashed at `result/<id>` — re-reference
  `result/<id>` in a LATER eval and it auto-awaits to data. Nothing is
  dropped; for a slow op you can also split it into smaller units.

## Promise-detection gotchas

- Auto-await detects a Promise via `(instance? js/Promise v)` -- sound for a
  native `^:async` return, but blind to a foreign thenable (a plain `{:then
  fn}` object built by hand). If you construct your own thenable instead of
  returning a real `^:async` fn's result, it will NOT be auto-awaited and
  will leak into your eval result as a raw object. Return real Promises.
- If a fn you call returns `:malli.core/invalid-output` on what looks like a
  Promise, that is the async/sync instrumentation-detection wedge: a fn
  compiled once as `^:async`, then re-instrumented, can be mis-detected as
  sync and have its Promise routed through the sync validator. This is a
  core-level concern, not something you cause from your eval -- if you see
  it, report it rather than working around it.

## Callable gotchas -- arity-0 thunks and keyword callbacks

Two silent footguns from "what is actually callable" in CLJS:

- **`(fn [])` is a strict zero-arity fn; `constantly` is variadic.** A callback
  the caller invokes WITH args (a `.then` handler, a tile/render fn, a
  multimethod/`reduce` step, an event handler) blows up on `(fn [] body)` with
  an `Invalid arity: 1` -- the fn declares exactly zero params. Use
  `(constantly v)` (-> `(fn [& _] v)`, swallows any args) for a value-returning
  callback, or `(fn [_] body)` to take-and-ignore the one arg. Reach for
  `(fn [])` ONLY for a genuine no-arg thunk you call yourself as `(f)`.
- **`(.then promise :some-keyword)` SILENTLY no-ops.** A keyword is callable on
  a CLJS map (`(:k m)`), but it is NOT a JS function, and `Promise.prototype.then`
  ignores any non-function argument -- the value passes through UNCHANGED, so
  `(:some-keyword value)` is never applied and there is no error. Wrap it:
  `(.then promise #(:some-keyword %))` or `(.then promise (fn [v] (:some-keyword v)))`.
  Same trap for any JS API taking a callback (`.map`, `.forEach`, `setTimeout`).

## Values don't persist as bare defs across turns/forms

Each form you submit is a separate compile against the shared namespace
state -- fns and vars land, but a bare value-`def` does not reliably read
back in a LATER form:

- **`(def x 42)` then `x` on its own returns `nil`.** Proven live. Use an
  atom instead: `(def !x (atom 42))`, then `@!x` reads it back. **Fns are
  unaffected** -- `(defn f [x] (inc x))` then `(f 41)` works fine, in the
  same eval or a later one.
- **`(in-ns 'foo)` does not work.** Use `(ns foo)` to switch your current
  namespace.
- A successful eval's return value is stashed for you and bound as
  `result/<id>` -- that is YOUR value-reuse surface across forms. It does
  NOT survive a pod restart; a dead id reads back a graceful "re-run the
  form" message instead of throwing.
- **A bare `result/<id>` on its own line RE-REFERENCES the stashed value —
  it is not a call.** Do not wrap it in `(await ...)` or `(identity ...)`;
  writing the bare symbol on its own line IS how you use it.
