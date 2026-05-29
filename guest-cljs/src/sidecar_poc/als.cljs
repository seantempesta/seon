(ns sidecar-poc.als
  "Userland AsyncLocalStorage equivalent for the wasm-rquickjs guest.

   wasm-rquickjs does NOT expose `node:async_hooks`. V0's `seon.db` uses
   Node's `AsyncLocalStorage` for fiber-local tx-context + agent-id that
   survive across `await`. This namespace provides the same SEMANTICS via
   a snapshot-and-restore pattern that works under any JS runtime.

   Design — capture explicitly, restore explicitly:

   Inside one wasm-rquickjs guest the JS event loop is a single QuickJS
   fiber. There is no race between concurrent fibers in the same store.
   Promise chains within the fiber are serialized: only one continuation
   runs at a time. So the drift surface is narrow — it appears when
   `f` (passed to `with-context`) is async and the awaiter elsewhere
   resets the context atom before `f`'s tail callbacks fire.

   The contract here:
   - `(with-context k v f)` sets ctx[k]=v, runs `(f)`, restores prior on
     return. If `f` is sync, ALS-equivalent guarantees hold trivially.
   - If `f` is async (returns a Promise), the body of `f` sees the bound
     context UP TO its first await. After each await, the body MAY have
     observed a sibling chain's `with-context` mutation. Callers who need
     the binding to survive awaits use `with-async`, which threads a
     `restore` callback the body invokes after each await.

   For Seon's current usage (one agent per wasm-rquickjs Store, no parallel
   `Promise.all` in the agent loop), `with-context` is sufficient and
   matches V0's `(binding [*agent-id* id] ...)` semantics. `with-async`
   is the documented escape hatch for future code that needs cross-await
   propagation under parallel chains."
  (:require [cljs.core]))

(defonce ^:private !current (atom {}))

(defn current
  "Return the current context map. Empty map when no `with-context` is
   in scope."
  []
  @!current)

(defn current-value
  "Lookup a single key in the current context."
  [k]
  (get @!current k))

(defn with-context
  "Run `(f)` synchronously with `k` bound to `v` in the context. The
   prior context is restored on return (or on throw).

   Matches V0's `(binding [*var* v] (f))` shape. Under wasm-rquickjs's
   single-fiber model this is sufficient for the agent loop's tx-context
   and agent-id needs."
  [k v f]
  (let [prior @!current]
    (try
      (reset! !current (assoc prior k v))
      (f)
      (finally
        (reset! !current prior)))))

(defn with-context-async
  "Like `with-context` but `f` receives a `(restore)` callback. After
   each `await` inside `f`, call `(restore)` to re-bind the snapshot
   captured at `with-context-async` entry. Use this when the callee
   needs the binding to survive across multiple awaits in a chain that
   may interleave with other concurrent chains in the same fiber.

   Semantics: `f` is given `(fn restore [] ...)` that, when called,
   resets `!current` to a merged snapshot of {prior + {k v}}. After
   `f`'s returned Promise resolves OR rejects, the prior context is
   restored unconditionally.

   Returns whatever `(f restore)` returns (typically a Promise)."
  [k v f]
  (let [prior  @!current
        bound  (assoc prior k v)
        restore (fn restore [] (reset! !current bound))]
    (restore)
    (let [done (fn [] (reset! !current prior))]
      (try
        (let [result (f restore)]
          (if (and (some? result) (.-then result))
            (-> result
                (.then  (fn [v] (done) v))
                (.catch (fn [e] (done) (throw e))))
            (do (done) result)))
        (catch :default e
          (done)
          (throw e))))))
