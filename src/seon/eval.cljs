(ns seon.eval
  "Agent eval surface (spec-02 §2.5 / §7.5). SAFE BY DEFAULT —
   `eval` returns {:ok true :value v} or {:ok false :error <error-map>}.
   A throw, compile error, or async rejection — all return as values.
   The agent session continues.

   The unadorned name `eval` is the safe one; we do NOT ship a public
   strict variant. Callers that want raw throw semantics can drop down
   to `cljs.js/eval-str` themselves.

   `eval` shadows clojure.core/eval inside this namespace. That's
   deliberate — agents type `(seon.eval/eval ...)` or, via the verb
   table, just `(eval ...)`. seon internals that need clojure.core's
   `eval` should import as `core/eval`.

   Vars defined in one eval persist for the next (compile-state is
   process-shared, defonce'd at boot in seon.client)."
  (:refer-clojure :exclude [eval])
  (:require [cljs.js :as cljs]
            [shadow.cljs.bootstrap.node :as boot]
            [seon.error :as error]))

(defn ^:async ^:private raw-eval
  "Internal — returns a Promise that resolves with the value or rejects
   with the error. The public `eval` catches both."
  [compile-state form-str & {:keys [ns] :or {ns 'cljs.user}}]
  (js/Promise.
    (fn [resolve reject]
      (cljs/eval-str compile-state form-str 'seon.dynamic
        {:eval          cljs/js-eval
         :load          (partial boot/load compile-state)
         :ns            ns
         :context       :statement
         :def-emits-var true}
        (fn [{:keys [error value]}]
          (if error (reject error) (resolve value)))))))

(defn ^:async eval
  "Evaluate a string of CLJS in the agent's persistent compile-state.
   Returns:
     {:ok true  :value v}                    on success
     {:ok false :error <seon.error/->map>}   on any failure
   Never throws; never rejects.

   cljs.js wraps both compile errors AND user-thrown runtime exceptions
   in an outer ExceptionInfo with the original on ex-cause. The caller
   can walk :seon.error/cause to inspect what actually happened."
  [compile-state form-str]
  (try
    (let [v (await (raw-eval compile-state form-str))]
      {:ok true :value v})
    (catch :default e
      {:ok false :error (error/->map e)})))
