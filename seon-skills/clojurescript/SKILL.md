---
name: clojurescript
description: "ClojureScript semantics for the Seon CLJS pod. Use when writing or debugging .cljs in the pod, when agent-eval'd forms behave unexpectedly, when working with ^:async / await / js/Promise, when a Promise leaks into agent output, when defs don't persist across eval-str, when instrumentation throws :malli.core/invalid-output on a Promise, or when reaching for core.async in the pod. Use when you see cljs.js, seon.eval, the bootstrap compile-state, or self-host compilation."
---

# ClojureScript -- Seon CLJS Pod Semantics

> Full grounded write-up (file:line + live proofs): `docs/prds/agent-fsm/research/cljs-async-await-2026-06-28.md`
> Library source (authoritative -- read it, don't guess): `reference-code/clojurescript/`

The pod is a long-running Node CLJS process. It compiles itself two ways, and
the difference is the source of almost every surprise:

- **The shadow build** (`.cljs` files in `src/seon/`) is compiled ahead-of-time
  by the JVM shadow-cljs compiler. This is the pod's own code.
- **Agent-eval'd forms** are compiled at runtime by the **self-host / bootstrap
  compiler** (`cljs.js`, a cljs-in-cljs compiler) against the process-shared
  compile-state `@seon.repl/!compile-state`. `seon.eval/eval` ->
  `seon.eval/raw-eval` -> `cljs/eval-str` runs here. This is NOT the JVM
  compiler; it has its own gotchas.

Versions: CLJS `1.12.145`, shadow-cljs `3.4.10` (`deps.edn`). Vendored
reference source is `1.12.41` -- the `await` macro and `:async` flag are
identical.

## `^:async` and `await` -- the core feature

The pod is **core.async-free**. Asynchrony is native JS `async`/`await` via
CLJS's built-in support (see `reference_cljs_async_await` memory note).

### How they compile (read the source)

- `await` is a **macro**, not a special form: `cljs/core.cljc:975-977`. It
  asserts `(:async &env)` and emits raw JS `(await ~{})` via `js*`. **It only
  expands inside an `^:async` fn body.**
- `^:async` is an analyzer flag set in `parse 'fn*`
  (`cljs/analyzer.cljc:2336-2341`) from the fn name's metadata. `defn ^:async`
  threads it through: `defn` merges name meta onto the def symbol
  (`cljs/core.cljc:3419`), and `parse 'def` analyzes the fn init passing that
  symbol as `name` (`cljs/analyzer.cljc:2118-2120`).
- The compiler emits a native `async function` when `(:async env)`
  (`cljs/compiler.cljc:945-959`, variadic `:975-1069`, iife `:704-708`).
- Runtime shape: a `^:async` fn is a JS `AsyncFunction` (its
  `.constructor.name` is `"AsyncFunction"`) and returns a `js/Promise`.

```clojure
;; In pod .cljs source -- the normal pattern (replaces core.async go-blocks):
(defn ^:async fetch-thing [url]
  (let [resp (await (js/fetch url))]
    (await (.json resp))))
```

### Self-host verdict (the part that bites agents)

When a form is eval'd through the bootstrap compile-state (the agent path):

- **Defining a `^:async` fn with internal `(await ...)` WORKS.** Self-host uses
  the same analyzer/compiler/macros (`cljs/js.cljs:17-18,121-124,843`).
- **A top-level `(await x)` in a single eval'd form FAILS.** The macro asserts
  `(:async &env)`, and a top-level form has no async env -> macroexpansion
  throws `"await can only be used in async contexts"`. (Even if it expanded,
  `js/eval` of top-level `await` is a JS SyntaxError outside a module.)

Implication: do NOT design agent ergonomics around a bare `(await result/<id>)`
-- it cannot work. Either wrap the `await` inside a `^:async` fn, or let the
auto-await mechanism (below) resolve it.

## Promise handling in agent eval -- data by default

Agents should get DATA, not Promises. `seon.eval` enforces this:

- `maybe-await-value` (`src/seon/eval.cljs:1192-1226`), called on the
  eval-batch path (`eval-form-entry!`, `eval.cljs:2433`): if a form's value is
  `(instance? js/Promise v)`, it awaits and records the **resolved value**.
  Agents never type `await`; calls to `^:async` core verbs (`seon.db/transact!`,
  `seon.agent.todo/add!`) feel synchronous.
- Bounded by `@seon.eval/!timeout-ms` (default 10000ms) or a one-shot
  `(seon.eval/budget <ms> <expr>)` override (`eval.cljs:97-120`) for a
  legitimately slow op.
- On timeout it returns `{:ok false :error <timeout>}`. **Known gap:** the
  pending Promise is currently dropped, not stashed -- so it can't be awaited
  later. (See research doc D for the stash-on-timeout + re-reference design.)

Note: bare `seon.eval/eval` does NOT auto-await; only the
`eval-batch!`/`eval-form-entry!` path does. New ergonomics plug in there.

## Promise-detection gotchas

- `(instance? js/Promise v)` is sound for native `^:async` returns and
  `js/Promise.*`, but **blind to non-`js/Promise` thenables** (a `{:then fn}`
  object) and **cross-realm Promises** (constructed in a different JS
  realm/`vm` context -- `instanceof` fails). The malli async wrapper instead
  duck-types `(fn? (.-then ret))` (`instrument.cljc:258`). For the
  single-realm pod the `instance?` test is adequate; flag it if foreign
  thenables ever enter agent forms.
- `seon.instrument/async-fn?` (`instrument.cljc:307-313`) checks
  `(= "AsyncFunction" (.. f -constructor -name))`. **Sound on a freshly
  compiled `^:async` var, UNSOUND on an already-instrumented wrapper:** malli's
  `-instrument-f` returns a plain `(fn [& args] ...)` (`instrument.cljc:247`)
  whose constructor is `"Function"` even though it still returns a Promise. A
  second instrument pass then mis-detects async as sync and routes the Promise
  through malli's SYNC output validator -> `:malli.core/invalid-output` -> pod
  wedges. This is the P0 double-instrument wedge, gated by the once-per-process
  `!instrumented?` atom (`instrument.cljc:365-376`). **Never run a second
  instrument pass over the program graph in one process.**

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

## Self-host eval / REPL gotchas (cross-`eval-str`)

These are from the `seon.eval` namespace docstring (`eval.cljs:26-34`) and are
self-host-specific -- they do NOT apply to ahead-of-time `.cljs`:

- **Bare value-def reads don't resolve across `eval-str` calls.** `(def x 42)`
  then `x` returns nil. Use an atom: `(def !x (atom 42))` + `@!x`. **Fns are
  unaffected** -- they cross namespaces fine.
- **`(in-ns 'foo)` is not bootstrapped.** Use `(ns foo)` to switch namespaces.
- A successful eval's value is stashed on `globalThis` and bound as the var
  `result/<id>` (`eval.cljs:962-1095`) -- that is the agent's value-reuse
  surface. The stash is process-scoped: it does NOT survive a pod restart
  (`lookup-result` then returns the honest "prior session -- re-run the form"
  miss).

## Verifying CLJS behavior live

Eval against the pod. To test the AGENT path specifically (self-host), go
through the bootstrap compile-state, not the shadow runtime:

```clojure
;; Through the bootstrap compile-state (what agents actually hit).
;; seon.eval/eval is ^:async -> returns a Promise; .then it into an atom
;; because the MCP eval does NOT await Promises.
(-> (seon.eval/eval @seon.repl/!compile-state
      "(defn ^:async f [x] (await (js/Promise.resolve (inc x))))")
    (.then (fn [_] (seon.eval/eval @seon.repl/!compile-state "(f 41)")))
    (.then (fn [r] (swap! !probe assoc :result r))))
```

Restart hygiene: `bin/seon restart pod` for a bad pod state, or
`bin/seon cluster reset default` for a fresh world (does not restart
cljs-watch). Never restart cljs-watch standalone -- it detaches the pod from
shadow.

## When to read which reference file

| Question | Read |
|----------|------|
| `await` semantics / why it throws | `reference-code/clojurescript/src/main/clojure/cljs/core.cljc:975` |
| `:async` analyzer flag / `defn` meta threading | `cljs/analyzer.cljc:2336`, `:2118`; `cljs/core.cljc:3374` |
| `async function` emission | `reference-code/clojurescript/src/main/clojure/cljs/compiler.cljc:945` |
| self-host compile/eval pipeline | `reference-code/clojurescript/src/main/cljs/cljs/js.cljs:843,1138` |
| seon's eval / auto-await / result stash | `src/seon/eval.cljs` (`maybe-await-value` :1192, `eval-form-entry!` :2358) |
| async instrumentation + the wedge | `src/seon/instrument.cljc:202-376` |
