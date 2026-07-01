(ns seon.embed.stash
  "Per-turn embedding-retrieval stash — an AsyncLocalStorage bridge from the
   ASYNC prefetch (`seon.agent/run-turn!`, which awaits the wire `knn-search`)
   to the SYNCHRONOUS section render (`seon.agent.ctx.relevant/relevant-source-block`,
   read by the value-returning `seon.agent.ctx/assemble-context`).

   WHY ALS, not a `^:dynamic` Var or an atom: the pod runs CONCURRENT agents in
   one Node process. A global slot (atom OR a CLJS `binding`, which macroexpands
   to `set!` against one global) would clobber under overlapping awaits — agent
   A's prefetched hits could be read by agent B's render. `AsyncLocalStorage` is
   fiber-local: a `.run`-scoped store survives every `await` inside `f` AND does
   not leak into a concurrent `.run` in another fiber. This MIRRORS the proven
   pattern in `seon.db.internal` (`agent-id-als` / `run-with-agent`).

   The seam (default-OFF, env-gated in `run-turn!` — when the toggle is unset
   the prefetch never fires and NOTHING runs `with-hits`, so `current-hits`
   returns nil and the section renders \"\"; the OFF path is byte-identical):

     run-turn! (async)                         render → section (sync)
     ─────────────────                         ───────────────────────
     hits = await (embed/search-pull …)
     (with-hits hits #(render-prompt id)) ───► (current-hits) → hits vector
                                               relevant-source-block renders

   The require of `node:async_hooks` is top-level so a pod missing it fails
   loudly at ns load, not silently at first render."
  (:require
    [seon.embed :as-alias embed]))

(defonce retrieval-als
  (let [AsyncLocalStorage (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (AsyncLocalStorage.)))

(defn current-hits
  "The hits vector stashed by the active `with-hits` scope, or nil.

   It is nil outside any scope (the default-OFF path — no prefetch ran,
   nothing called `with-hits`).
   `seon.agent.ctx.relevant/relevant-source-block` reads this synchronously."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [store (.getStore retrieval-als)]
    ;; Outside a `.run` the JS getStore returns undefined → nil in CLJS. Be
    ;; explicit — callers treat nil as \"no retrieval this turn\".
    (when (some? store) store)))

(defn with-hits
  "Run `f` inside a `.run` scope that stashes `hits`.

   `f` is a 0-arg thunk (may return a Promise). `hits` is the
   `:seon.embed/hits` vector from `embed/search-pull` (or nil on a fail-soft
   prefetch), so `(current-hits)` returns it across every await inside `f`.
   Returns whatever `f` returns."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [hits f]
  (.run retrieval-als hits f))
