---
type: research
status: draft
tags: [research, agent]
---

# ClojureScript `^:async` / `await` in the agent eval path — grounded verdict

Settles the Promise-handling design for `seon.eval`: how `^:async`/`await`
compile, whether the pod's bootstrap (self-host) compiler lets agent-eval'd
forms use them, why `async-fn?` mis-fires, and the recommended
data-by-default + intuitive-Promise ergonomics.

Every load-bearing claim cites a file:line that was actually opened, and the
load-bearing ones are additionally LIVE-PROVEN against the running pod's
bootstrap compile-state (`@seon.repl/!compile-state`) on 2026-06-28.

Versions: project CLJS `1.12.145` (`deps.edn:317`), shadow-cljs `3.4.10`
(`deps.edn:319`); vendored reference source is `1.12.41`
(`reference-code/clojurescript/pom.xml`). The `await` macro and `:async`
analyzer flag are identical across both — and the running pod's self-host
bundle carries the same macro byte-for-byte
(`out/bootstrap/src/cljs.core$macros.cljc:975-977`).

## TL;DR

- **A.** `await` is a plain CLJS macro (`cljs/core.cljc:975-977`) that asserts
  `(:async &env)` and emits raw JS `(await ~{})` via `js*`. `^:async` is an
  analyzer flag set in `parse 'fn*` from the fn name's metadata
  (`cljs/analyzer.cljc:2336-2341`); the compiler then emits a native
  `async function` (`cljs/compiler.cljc:945-959`). A `^:async` fn compiles to
  a JS `AsyncFunction` returning a `js/Promise` — **live-proven** (ctor name
  `"AsyncFunction"`, return `instance? js/Promise`).
- **B — SELF-HOST VERDICT: CONDITIONAL.** The pod's bootstrap (cljs.js) uses
  the *same* analyzer + compiler + core macros (`cljs/js.cljs:17-18,121-124,843`),
  so:
  - **Defining a `^:async` fn with an internal `(await …)` → YES, works in
    self-host.** Live-proven through `@seon.repl/!compile-state`.
  - **A top-level `(await x)` in a single eval'd form → NO.** The macro's
    `(:async &env)` assert throws at macroexpansion because a top-level form
    has no async env. Live-proven (`{:ok false …}`, thrown from
    `out/bootstrap/src/cljs.core$macros.cljc:976`).
  - Therefore "await it in `result/<id>`" **cannot** be a top-level
    `(await result/<id>)`. It must be the auto-await-on-re-reference path, or
    happen inside a `^:async` fn the agent defines.
- **C.** `(instance? js/Promise v)` (the auto-await test, `eval.cljs:1206`) is
  sound for native `^:async` returns but blind to non-`js/Promise` thenables /
  cross-realm Promises. `async-fn?` (`instrument.cljc:307-313`) is sound on a
  *freshly compiled* `^:async` var but **mis-fires on an already-instrumented
  wrapper**: malli's `-instrument-f` returns a plain `(fn [& args] …)`
  (`instrument.cljc:247`) whose `.constructor.name` is `"Function"`, not
  `"AsyncFunction"` — even though it still returns a Promise. Live-proven.
  This is the P0 double-instrument wedge, gated by `!instrumented?`
  (`instrument.cljc:365-376`).
- **D.** Keep auto-await as the default (Promise → data). Fix the one gap: on
  auto-await **timeout** (and for an explicit opt-out), **stash the pending
  Promise itself into `result/<id>`** instead of discarding it. Resolution is
  then the already-working **re-reference auto-resolve**: a later bare
  `result/<id>` returns the Promise as the form value, and `maybe-await-value`
  awaits it — no top-level `await`, no new verb required for the common path.

## A. How `^:async` and `await` compile

### `await` is a macro, not a special form

`reference-code/clojurescript/src/main/clojure/cljs/core.cljc:975-977`:

```clojure
(core/defmacro await [expr]
  (core/assert (:async &env) "await can only be used in async contexts")
  (core/list 'js* "(await ~{})" expr))
```

Two facts that drive everything below:

1. It **refuses to expand** unless `(:async &env)` is truthy — i.e. it only
   compiles inside a `^:async` fn body.
2. When it does expand, it emits `(js* "(await ~{})" expr)` → literal JS
   `(await <expr>)`. No runtime helper; it leans entirely on the host JS
   `await` keyword.

There is **no `js-await` special form** in this version — grep of both
`analyzer.cljc` and `compiler.cljc` finds none; `await` the macro is the only
surface.

### The `:async` analyzer flag

`reference-code/clojurescript/src/main/clojure/cljs/analyzer.cljc:2336-2341`
(`parse 'fn*`):

```clojure
async (or
       #_(:async (meta form))   ; deliberately disabled (MetaFn interop)
       (:async (meta name))
       (:async (meta (first form))))
env (assoc env :async async)
```

So `:async` is read from the metadata of the fn's name symbol (or the `fn*`
symbol) and stashed on `env`. Every form analyzed inside that fn body sees
`(:async &env) == true`, which is exactly what the `await` macro's assert
requires.

### How `defn ^:async` reaches `parse 'fn*`

The single-arity `defn` macro (`cljs/core.cljc:3374-3437`) expands to
`(def (with-meta name m) (cons 'fn fdecl))`, and crucially merges the name's
own metadata into `m` (`:3419` — `m (conj (if (meta name) (meta name) {}) m)`).
So `^:async` ends up on the **def's symbol**. Then `parse 'def`
(`cljs/analyzer.cljc:2118-2120`) analyzes the fn init **passing that symbol as
the `name` argument**:

```clojure
(analyze (assoc env :context :expr) (:init args) sym)
```

`sym` carries `:async`, so `parse 'fn*` reads it at `:2339`. That is the
complete `(defn ^:async f …)` → `async function` chain. (`fn ^:async [...]`
works too, via `(:async (meta (first form)))`.)

### Compiler emission → native `async function`

`reference-code/clojurescript/src/main/clojure/cljs/compiler.cljc`:

- `emit-fn-method` (`:945-959`): `(emits "(" (when async "async ") "function " …)`
  — emits the `async` keyword when `(:async env)`.
- `emit-variadic-fn-method` (`:975-1069`): same, for variadic/multi-arity.
- `iife-open`/`iife-close` (`:704-708`): when an async body is used in
  expression context, wraps `(await (async function (){ … })())` so the value
  flows out.

### Live proof (runtime shape)

Through the pod's bootstrap compile-state:

```clojure
(seon.eval/eval @seon.repl/!compile-state
  "(let [f my-async-double r (f 10)]
     {:fn-ctor (.. f -constructor -name) :ret-promise? (instance? js/Promise r)})")
;; => {:ok true :value {:fn-ctor "AsyncFunction" :ret-promise? true} …}
```

A `^:async` fn compiles to a JS `AsyncFunction` and returns a `js/Promise`.
Confirmed.

## B. THE CRUX — self-host verdict: CONDITIONAL

### Self-host uses the same analyzer/compiler/macros

`cljs.js` (the bootstrap compiler the pod evals agent forms through) requires
the very same namespaces: `[cljs.analyzer :as ana]` and
`[cljs.compiler :as comp]` (`cljs/js.cljs:17-18`). It emits JS via
`(comp/emit ast)` (`cljs/js.cljs:843,965`) and runs it with `js-eval`, which is
just `(js/eval source)` (`cljs/js.cljs:121-124`). The `await` macro and the
`:async` flag are therefore fully present in self-host — nothing is stripped.

The pod's seed of this is real on disk: the bootstrap bundle's macro source
`out/bootstrap/src/cljs.core$macros.cljc:975-977` is the same `await` macro as
above. seon evals every agent form through `cljs/eval-str` against that
compile-state (`seon.eval/raw-eval`, `eval.cljs:786-880`); the compile-state is
the process-shared `@seon.repl/!compile-state` (`repl.cljs:76,87-104`).

### Defining a `^:async` fn → YES

Live, through the bootstrap compile-state (the exact agent path):

```clojure
(seon.eval/eval @seon.repl/!compile-state
  "(defn ^:async my-async-double [x] (await (js/Promise.resolve (* 2 x))))")
;; => {:ok true :value #'cljs.user/my-async-double :ns cljs.user}
(seon.eval/eval @seon.repl/!compile-state "(my-async-double 21)")
;; => {:ok true :value #object[Promise …] :ns cljs.user}
```

The `(await …)` inside the `^:async` body compiles and runs in self-host. The
fn returns a real Promise.

### Top-level `(await x)` in one form → NO

Live, same path:

```clojure
(seon.eval/eval @seon.repl/!compile-state "(await (js/Promise.resolve 5))")
;; => {:ok false
;;     :error {:seon.error/message "Could not eval seon.dynamic" …
;;             … :clojure.error/phase :macroexpansion
;;             … cause: "Assert failed: await can only be used in async contexts
;;                       (:async &env)"}}
```

The throw originates at `out/bootstrap/src/cljs.core$macros.cljc:976` — the
self-host bundle's copy of the assert. A bare top-level form has no `:async`
env, so the macro refuses. (Independently, even if it expanded,
`(js/eval "(await x)")` is a JS `SyntaxError` outside a module/async function —
two reasons it can't work.) `seon.eval/eval` returns this as a value, never
throws (errors-are-values, `eval.cljs:880-935`).

### Consequence for the design

- "Await the Promise in `result/<id>`" **cannot** be expressed as a top-level
  `(await result/<id>)` in a single agent eval.
- It **can** be expressed as: re-referencing `result/<id>` and letting the
  existing auto-await resolve it (the form returns the Promise, and
  `maybe-await-value` awaits it — see D), or by the agent wrapping the await
  inside a `^:async` fn they define and call.

## C. Promise-detection soundness

### `instance? js/Promise` (the auto-await test)

`maybe-await-value` gates on `(instance? js/Promise v)` (`eval.cljs:1206`).

- **Sound** for everything a `^:async` fn returns and everything
  `js/Promise.*` produces (proven in A and below).
- **Blind** to thenables that are not `js/Promise` instances (a plain
  `{:then fn}` object, a Bluebird-style thenable) and to cross-realm Promises
  (a Promise constructed in a different JS realm/`vm` context fails
  `instanceof` against this realm's `Promise`). Note the asymmetry: malli's
  async wrapper instead duck-types with `(fn? (.-then ret))`
  (`instrument.cljc:258`), which would catch those — the two paths disagree.
  For the pod (single Node realm, seon's own `^:async` fns) the `instance?`
  test is adequate; flag it only if foreign thenables ever enter agent forms.

### `async-fn?` and why it mis-fires (the P0 wedge)

`instrument.cljc:307-313`:

```clojure
(defn async-fn? [f]
  (and (fn? f) (= "AsyncFunction" (.. f -constructor -name))))
```

This reads the JS constructor name. It is **sound on a freshly compiled
`^:async` var** but **unsound on an already-instrumented wrapper**, because
malli's `-instrument-f` returns a plain function `(fn [& args] (apply f args))`
(`instrument.cljc:247-265`) — it calls the inner async fn and returns its
Promise *without* `await`, so the wrapper is an ordinary `Function`, not an
`AsyncFunction`.

Live proof (a real `^:async` var, then the malli-shaped wrapper around it):

```clojure
(defn ^:async real-async [x] (* 2 x))
(let [wrapper (fn [& args] (apply real-async args))]
  {:real-async-ctor   (.. real-async -constructor -name) ; "AsyncFunction"
   :real-async-fn?    (seon.instrument/async-fn? real-async) ; true
   :wrapper-ctor      (.. wrapper -constructor -name) ; "Function"
   :wrapper-async-fn? (seon.instrument/async-fn? wrapper) ; false  <-- mis-fire
   :wrapper-still-returns-promise? (instance? js/Promise (wrapper 5))}) ; true
```

So a second instrument pass that re-reads the wrapper var sees `async-fn? ⇒
false`, routes the (still-async) fn through malli's **synchronous** output
validator, which validates the *Promise object* against the resolved-value
schema → `:malli.core/invalid-output`, and the pod wedges. This is precisely
the wedge documented at `instrument.cljc:365-376`, mitigated by the
once-per-process gate `!instrumented?`. The detection is correct *as used*
(only on fresh vars, via `collect-registrations` reading the analyzer
`:async` meta, `instrument.cljc:117-136`, and the eval-tee); the soundness
hole is "don't run it on a wrapper."

## D. Recommended Promise ergonomics

### Default — data, not Promises (keep as-is)

`maybe-await-value` (`eval.cljs:1192-1226`), called from `eval-form-entry!`
(`eval.cljs:2433`) on the agent's eval-batch path, already implements the good
default: if a form's value is a Promise, await it (bounded by `@!timeout-ms` or
a one-shot `(budget …)` override) and record the **resolved value**. Agents
never write `await`; calls to `^:async` core verbs (`seon.db/transact!`,
`seon.agent.todo/add!`) feel synchronous.

Live-proven:

```clojure
(seon.eval/maybe-await-value (js/Promise.resolve {:resolved 99}))
;; => {:ok true :value {:resolved 99}}
(seon.eval/maybe-await-value [:plain :data])
;; => {:ok true :value [:plain :data]}     ; non-Promise passes through
```

Note the agent-facing surface is `eval-batch!`/`eval-form-entry!` (which calls
`maybe-await-value`); the bare `seon.eval/eval` does **not** auto-await — it
returns the raw Promise. Any new design plugs into the `eval-form-entry!` path.

### The gap — a timed-out / long-running Promise is LOST

On timeout, `maybe-await-value` returns `{:ok false :error <timeout>}` and
**does not carry the Promise** (`eval.cljs:1213-1218`). Then
`eval-form-entry!` stashes and binds `result/<id>` **only** `(when (:ok
result))` (`eval.cljs:2487-2493`). A timeout is `:ok false`, so the pending
Promise is never stashed — it is dropped. The agent has no handle to await it
later.

Live-proven:

```clojure
(seon.eval/maybe-await-value (js/Promise. (fn [_ _] nil))) ; never resolves
;; => {:ok false :error {:seon.error/message "auto-await timed out after 10000ms" …}}
```

The underlying Promise keeps running in the background (no JS preemption — see
`race-timeout`, `eval.cljs:124-145`), but nothing holds its handle.

### Recommendation (ONE mechanism)

**Stash the pending Promise into `result/<id>`; resolve it later by
re-reference.** Concretely:

1. **On auto-await timeout** (and for an explicit opt-out — below), have the
   eval path stash the **Promise object itself** via `stash-result-raw!` +
   `bind-result-var!` (`eval.cljs:962-1095`) and record a value/error line
   that says: *"still running — re-reference `result/<id>` to await it; its
   result is not yet available."* This is a tiny change to the timeout branch
   of the `eval-form-entry!` flow, not a new subsystem.
2. **Resolution is the existing re-reference auto-resolve.** A later bare
   `result/<id>` read evaluates in `:expr` context and returns the bound value
   (`raw-eval`, `eval.cljs:815-822`). If that value is the pending Promise, the
   same `maybe-await-value` on the eval-batch path awaits it and records the
   resolved data — exactly the behavior proven above for any returned Promise.
   No top-level `await` is needed (which B forbids), and no new verb is needed
   for the common path. If it is *still* pending, the agent simply gets another
   timeout and can re-reference again.

This is the turtles-all-the-way-down option: the value-reuse surface
(`result/<id>`) and the auto-await are reused; "a Promise goes to results so it
can be awaited there" becomes literally true.

**Opt-in / explicit long-op (optional verb).** Because `maybe-await-value`
always unwraps a *bare* returned Promise, an agent that wants the Promise
**handle** (not the resolved value) needs a way to hide it from the
`instance? js/Promise` test, or a verb that stashes-without-awaiting. Recommend
a single small verb, e.g. `(seon.eval/defer <expr>)`, that evaluates `<expr>`
to a Promise and binds it to `result/<id>` without awaiting — same stash, same
later re-reference auto-resolve. This is the only addition beyond the timeout
fix, and it is opt-in, so the default stays "data, not Promises."

### Caveats to encode

- **Process-scoped.** The stash lives on `globalThis`, not the DB
  (`eval.cljs:945-961`). Across a pod restart the Promise is gone, and
  `lookup-result` returns the honest "prior session — re-run the form" miss
  (`eval.cljs:975-1019`). A long-op that must survive a restart should persist
  its **result** to the DB, not rely on an in-memory Promise. Acceptable and
  consistent with the "DB is the durable memory" doctrine.
- **`budget` already exists** for the *known-slow* case: `(seon.eval/budget ms
  expr)` (`eval.cljs:97-120`) raises the one-shot auto-await timeout, so a
  legitimately slow op resolves to data in one turn without ever becoming a
  stashed Promise. The stash-on-timeout path is the fallback for ops that
  exceed even an explicit budget or are genuinely fire-and-forget.

## Sources opened

- `reference-code/clojurescript/src/main/clojure/cljs/core.cljc` — `await`
  macro `:975-977`; `defn` expansion `:3374-3437` (`:3419` meta-merge).
- `reference-code/clojurescript/src/main/clojure/cljs/analyzer.cljc` —
  `:async` flag in `parse 'fn*` `:2336-2341`; `parse 'def` init analysis
  `:2041-2120`.
- `reference-code/clojurescript/src/main/clojure/cljs/compiler.cljc` —
  `iife-open/close` `:704-708`; `emit-fn-method` `:945-959`;
  `emit-variadic-fn-method` `:975-1069`.
- `reference-code/clojurescript/src/main/cljs/cljs/js.cljs` — analyzer/compiler
  requires `:17-18`; `js-eval` `:121-124`; `comp/emit` `:843,:965`;
  `eval-str` `:1138`.
- `out/bootstrap/src/cljs.core$macros.cljc:975-977` — the running pod's
  self-host `await` macro (the assert that fires live).
- `src/seon/eval.cljs` — `raw-eval` `:786-880`; `eval` `:880-935`;
  `stash-result-raw!`/`lookup-result`/`bind-result-var!` `:962-1095`;
  `maybe-await-value` `:1192-1226`; `eval-form-entry!` `:2358-2493`;
  `race-timeout`/`budget` `:97-145`.
- `src/seon/repl.cljs` — `!compile-state` `:76`; `ensure-bootstrap!` `:87-104`.
- `src/seon/instrument.cljc` — `collect-registrations` `:117-136`;
  `async-fschema -instrument-f` `:202-265`; `register-target!` `:267-303`;
  `async-fn?` `:307-313`; P0 once-gate `:365-376`.
- `deps.edn:317-319` — CLJS `1.12.145`, shadow-cljs `3.4.10`.

All `seon.eval` / `seon.instrument` claims were live-exercised against the
running pod's bootstrap compile-state on 2026-06-28.
