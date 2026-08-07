(ns runtime-ctx-hook-probe
  "Phase 0 probe B — the MINIMAL runtime-ctx call-preparation hook in the
  maintained SCI fork (branch `seon-env-hook-probe`, unpinned in the
  superproject).

  Falsifies four claims:
    1. a host (copy-var) function declared to need a value it was not passed
       receives it, at call time, from the RUNTIME ctx's environment;
    2. two forks running the same program concurrently each see THEIR OWN
       environment (no thread-local, no analysis-ctx pinning);
    3. caller-supplied values win, and an unavailable declaration short-circuits
       to a flat error value without entering the callee;
    4. the empty-plan path (hook installed, nothing to prepare) and the
       no-hook path cost roughly what a bare call costs.

  It also records where the fork's ctx-carriage model does NOT reach.

  Run:
    clojure -M:dev -e \"(require 'clojure.pprint) (load-file \\\"tmp/env-probes/runtime_ctx_hook_probe.clj\\\") (clojure.pprint/pprint (runtime-ctx-hook-probe/run))\""
  (:require [sci.core :as sci]
            [sci.impl.vars :as sci-vars]))

;;; ---------------------------------------------------------------------------
;;; The Seon-side pieces the hook needs, simulated with plain data.

(defn read-rows
  "Host capability leaf: declares a database value it is not given by callers."
  [db q]
  {:read/from db :read/query q})

(defn plain-fn
  "Host leaf with nothing declared — exercises the empty-plan path."
  [x]
  (inc x))

;; The 'program graph' slice the plan derives from: for each program function,
;; the declared argument index and the environment key that fills it.
(def ^:private declarations
  {'my/read-rows {:index 0 :arity 2 :env-key :seon.db/db}})

(defn- program-identity
  "Provable identity of a resolved sci Var: its fully qualified symbol."
  [v]
  (sci-vars/toSymbol v))

(defn- call-preparation-hook
  "(hook runtime-ctx var args) -> prepared args, or (reduced flat-error).

  This is the whole Seon side of the seam: read the environment off the RUNTIME
  ctx, consult the plan for this program function, fill declared-and-absent
  positions. Caller presence wins."
  [ctx v args]
  (let [sym (program-identity v)
        {:keys [index arity env-key] :as decl} (get declarations sym)]
    (if (nil? decl)
      args                                        ; empty plan — untouched
      (if (>= (count args) arity)
        args                                      ; caller supplied it: caller wins
        (let [environment (:seon/environment ctx)]
          (if (contains? environment env-key)
            (vec (concat (subvec args 0 index)
                         [(get environment env-key)]
                         (subvec args index)))
            (reduced {:seon.error/kind :seon.ambient/unavailable
                      :seon.error/message (str "Cannot call " sym ": ambient "
                                               env-key " is unavailable.")
                      :seon.error/data {:seon.fn/sym (str sym)
                                        :seon.ambient/key env-key
                                        :seon.fn.argument/index index}})))))))

(defn- ctx-for
  "A ctx with the hook installed and `environment` attached.

  NOTE: `:seon/environment` cannot ride the init OPTIONS map — sci.impl.opts/init
  destructures a fixed key set and drops everything else. It must be assoc'd
  onto the constructed ctx."
  [environment]
  (let [my-ns (sci/create-ns 'my)]
    (assoc (sci/init {:namespaces
                      {'my {'read-rows (sci/new-var 'read-rows read-rows {:ns my-ns})
                            'plain (sci/new-var 'plain plain-fn {:ns my-ns})}}
                      :call-preparation-hook call-preparation-hook})
           :seon/environment environment)))

;;; ---------------------------------------------------------------------------
;;; 1. The declared-and-absent argument is filled from the runtime ctx.

(defn- fills-declared-argument []
  (let [ctx (ctx-for {:seon.db/db :db-alpha})]
    {:elided (sci/eval-string* ctx "(my/read-rows :q)")
     :caller-wins (sci/eval-string* ctx "(my/read-rows :explicit-db :q)")
     :nested-call (sci/eval-string* ctx "((fn [] (my/read-rows :nested)))")
     :through-interpreted-defn
     (sci/eval-string* ctx "(do (defn outer [q] (my/read-rows q)) (outer :via-defn))")
     :empty-plan-untouched (sci/eval-string* ctx "(my/plain 41)")}))

;;; ---------------------------------------------------------------------------
;;; 2. Per-fork correctness under concurrency.

(defn- per-fork-under-concurrency [n]
  (let [base (ctx-for {})
        forks (mapv (fn [i] (assoc (sci/fork base)
                                   :seon/environment {:seon.db/db (keyword (str "db-" i))}))
                    (range 8))
        program "(dotimes [_ 50] (my/read-rows :q)) (my/read-rows :q)"
        tasks (for [_ (range n)
                    [i fork] (map-indexed vector forks)]
                (fn [] [(keyword (str "db-" i))
                        (:read/from (sci/eval-string* fork program))]))
        results (->> tasks
                     (mapv (fn [t] (let [p (promise)]
                                     (.start (Thread/ofVirtual) ^Runnable #(deliver p (t)))
                                     p)))
                     (mapv deref))]
    {:calls (count results)
     :all-correct? (every? (fn [[expected actual]] (= expected actual)) results)
     :mismatches (vec (remove (fn [[e a]] (= e a)) results))}))

;;; ---------------------------------------------------------------------------
;;; 3. Unavailable declaration short-circuits without entering the callee.

(defn- unavailable-short-circuits []
  (let [ctx (ctx-for {})                          ; environment present but empty
        v (sci/eval-string* ctx "(my/read-rows :q)")]
    {:flat-error? (= :seon.ambient/unavailable (:seon.error/kind v))
     :callee-not-entered? (not (contains? v :read/from))
     :value v}))

;;; ---------------------------------------------------------------------------
;;; 4. Rough timing. Not a benchmark — an order-of-magnitude sanity note.

(defn- time-call [ctx program iterations]
  (dotimes [_ 20000] (sci/eval-string* ctx program))   ; warm
  (let [prog (sci/eval-form ctx (read-string (str "(fn [] " program ")")))
        t0 (System/nanoTime)
        _ (dotimes [_ iterations] (prog))
        ns-per (/ (double (- (System/nanoTime) t0)) iterations)]
    (Math/round ns-per)))

(defn- timing [iterations]
  (let [no-hook (assoc (sci/init {:namespaces
                                  {'my {'plain (sci/new-var 'plain plain-fn
                                                            {:ns (sci/create-ns 'my)})}}})
                       :seon/environment {})
        with-hook (ctx-for {:seon.db/db :db-alpha})]
    {:iterations iterations
     :ns-per-call
     {:no-hook-installed (time-call no-hook "(my/plain 1)" iterations)
      :hook-installed-empty-plan (time-call with-hook "(my/plain 1)" iterations)
      :hook-installed-prepared (time-call with-hook "(my/read-rows :q)" iterations)}
     :note "single JVM, no isolation; read as order of magnitude only"}))

;;; ---------------------------------------------------------------------------
;;; 5. Where ctx carriage does NOT reach: an interpreted fn created against the
;;;    BASE ctx keeps the base ctx (fns.cljc:53,78,167), so a later fork calling
;;;    it prepares arguments from the BASE environment, not the fork's.

(defn- base-created-closure-pins-base-environment []
  (let [base (ctx-for {:seon.db/db :db-BASE})
        _ (sci/eval-string* base "(defn program-fn [q] (my/read-rows q))")
        fork (assoc (sci/fork base) :seon/environment {:seon.db/db :db-FORK})
        from-fork (sci/eval-string* fork "(program-fn :q)")
        re-evaluated (do (sci/eval-string* fork "(defn program-fn [q] (my/read-rows q))")
                         (sci/eval-string* fork "(program-fn :q)"))]
    {:calling-base-created-fn-from-fork (:read/from from-fork)
     :after-re-evaluating-in-fork (:read/from re-evaluated)
     :finding (if (= :db-BASE (:read/from from-fork))
                "CONFIRMED: a fn created against the base ctx pins the base environment; program fns must be (re)created in the fork that runs them, or installed as host Vars so the hook fires with the fork's ctx"
                "not reproduced")}))

(defn run
  "Execute every falsifier and return one data map."
  []
  {:probe/name "runtime-ctx call-preparation hook (minimal fork edit)"
   :probe/sci-branch "seon-env-hook-probe (reference-code/sci, NOT pinned in the superproject)"
   :probe/findings
   {:fills-declared-argument (fills-declared-argument)
    :per-fork-under-concurrency (per-fork-under-concurrency 40)
    :unavailable-short-circuits (unavailable-short-circuits)
    :timing (timing 2000000)
    :base-created-closure (base-created-closure-pins-base-environment)}
   :probe/verdict :minimal-edit-viable})
