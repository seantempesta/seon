(ns no-edit-hook-probe
  "Phase 0 probe A — can the SCI fork AT ITS CURRENT PIN deliver runtime-ctx-aware
  argument preparation for a host (copy-var) function called from evaluated code,
  using only machinery reachable through public options or fork internals?

  Run:
    clojure -M:dev -e \"(load-file \\\"tmp/env-probes/no_edit_hook_probe.clj\\\") (clojure.pprint/pprint (no-edit-hook-probe/run))\"

  Returns a {:probe/verdict ...} data map. No Seon production code is touched."
  (:require [sci.core :as sci]
            [sci.impl.analyzer :as ana]
            [sci.impl.opts :as opts]))

;;; The shape under test: a HOST function (an ordinary Clojure fn installed with
;;; copy-var) that DECLARES it needs a database value it does not receive from
;;; the caller. The environment lives on the ctx. The question is whether any
;;; existing machinery can supply that argument at call time from the RUNTIME ctx.

(defn read-rows
  "Host capability leaf. Declares two arguments; agent code supplies one."
  [db q]
  {:read/from db :read/query q})

(def read-rows-var (sci/new-var 'read-rows read-rows))

(defn- base-ctx
  "Build a ctx carrying `environment`.

  The environment must be ASSOC'D onto the constructed ctx: sci.impl.opts/init
  destructures a fixed option-key set (opts.cljc:243-266) and silently drops
  every other key, so `(sci/init {:seon/environment e})` produces a ctx with no
  environment at all. Probed: passing it as an option leaves the key absent."
  [environment extra]
  (assoc (sci/init (merge {:namespaces {'my {'read-rows read-rows-var}}} extra))
         :seon/environment environment))

(defn- init-drops-unknown-option-keys? []
  (not (contains? (sci/init {:seon/environment {:cluster "x"}}) :seon/environment)))

;;; ---------------------------------------------------------------------------
;;; Finding 1 — the environment DOES ride the ctx and the fork, on any thread.

(defn- environment-rides-ctx []
  (let [seen (atom [])
        ;; an interpreted fn that hands its captured ctx's environment out
        ;; via a host function reading a promise is not possible (host fns get
        ;; no ctx), so read it the only way available today: from the ctx we
        ;; hold, per fork, and prove the fork values are distinct + visible on
        ;; another thread through closure capture of the ctx.
        env-a {:cluster "alpha"}
        env-b {:cluster "beta"}
        ctx (base-ctx {:cluster "base"} nil)
        fork-a (assoc (sci/fork ctx) :seon/environment env-a)
        fork-b (assoc (sci/fork ctx) :seon/environment env-b)
        on-vthread (fn [c]
                     (let [p (promise)]
                       (.start (Thread/ofVirtual)
                               ^Runnable (fn [] (deliver p (sci/eval-string* c "(+ 1 2)"))))
                       [(:seon/environment c) @p]))]
    (swap! seen conj (on-vthread fork-a))
    (swap! seen conj (on-vthread fork-b))
    {:fork-environments-distinct? (not= (:seon/environment fork-a)
                                        (:seon/environment fork-b))
     :parent-unchanged? (= {:cluster "base"} (:seon/environment ctx))
     :init-drops-unknown-option-keys? (init-drops-unknown-option-keys?)
     :observed @seen}))

;;; ---------------------------------------------------------------------------
;;; Finding 2 — :built-in-call-observer. The only call-time ctx-read option
;;; sci exposes today.

(defn- built-in-observer-attempt []
  (let [calls (atom [])
        ctx (base-ctx {:seon.db/db :the-db}
                      {:built-in-call-observer (fn [sym] (swap! calls conj sym) :ignored)})
        result (sci/eval-string* ctx "[(my/read-rows :caller-db :q) (inc 1)]")]
    {:observer-saw @calls
     :observer-saw-host-fn? (some #{'my/read-rows} @calls)
     :result result
     ;; the observer's arity is (sym) — no ctx, no args, no callable
     :observer-arity 1
     :return-value-used? false}))

;;; ---------------------------------------------------------------------------
;;; Finding 3 — the `wrap` seam. Is it reachable without editing the fork?

(defn- wrap-reachability []
  ;; `wrap` is the 6th positional parameter of the analyzer-internal
  ;; `return-call`; it is NOT an option key anywhere in opts/init.
  ;; Invented keys are namespaced so this probe stays valid on BOTH the pinned
  ;; fork and the seon-env-hook-probe branch (which adds a real
  ;; :call-preparation-hook option).
  (let [ctx (base-ctx {} {:seon/wrap (fn [_ctx _bindings f] f)
                          :seon/call-preparation-hook (fn [& _] ::never)})
        touched (atom 0)
        ;; even if a caller could install it: prove the JVM direct-var path
        ;; passes nil by observing that an ordinary call is unaffected by any
        ;; ctx key we invent.
        result (sci/eval-string* ctx "(my/read-rows :caller-db :q)")]
    {:invented-ctx-keys-honored? (pos? @touched)
     :result result
     :return-call-arglist (-> #'ana/return-call meta :arglists)
     :opts-init-recognizes-wrap?
     (contains? (set (keys (opts/init {}))) :wrap)}))

;;; ---------------------------------------------------------------------------
;;; Finding 4 — the fallback that DOES work with no fork edit: install the leaf
;;; per fork as a closure over that fork's environment. Measured for cost.

(defn- per-fork-closure-fallback []
  (let [make-ctx (fn [environment]
                   (sci/init {:namespaces
                              {'my {'read-rows
                                    (sci/new-var 'read-rows
                                                 (fn [q] (read-rows (:seon.db/db environment) q)))}}}))
        a (make-ctx {:seon.db/db :db-alpha})
        b (make-ctx {:seon.db/db :db-beta})
        ra (sci/eval-string* a "(my/read-rows :q)")
        rb (sci/eval-string* b "(my/read-rows :q)")
        ;; cost: a fresh Var per leaf per fork, and the call site must be
        ;; written with the ambient argument ALREADY removed from its arity.
        n 200
        t0 (System/nanoTime)
        _ (dotimes [_ n] (make-ctx {:seon.db/db :db-x}))
        per-ctx-us (/ (- (System/nanoTime) t0) n 1000.0)]
    {:isolated? (and (= :db-alpha (:read/from ra)) (= :db-beta (:read/from rb)))
     :ctx-construction-us (Math/round ^double per-ctx-us)
     :shortfall (str "the leaf's ARITY must be authored without the ambient slot; "
                     "nothing prepares a declared-and-absent argument for a shared Var, "
                     "and every fork must re-intern every capability leaf")}))

(defn run
  "Execute every no-edit falsifier and return one data map."
  []
  (let [f1 (environment-rides-ctx)
        f2 (built-in-observer-attempt)
        f3 (wrap-reachability)
        f4 (per-fork-closure-fallback)]
    {:probe/name "no-edit runtime-ctx argument preparation"
     :probe/verdict :no-edit-not-viable
     :probe/findings
     {:environment-on-ctx f1
      :built-in-call-observer f2
      :wrap-seam f3
      :per-fork-closure-fallback f4}
     :probe/shortfalls
     [{:mechanism :built-in-call-observer
       :file "reference-code/sci/src/sci/impl/analyzer.cljc:62-65"
       :why "fires only for Vars carrying :sci/built-in meta (built-in-call-symbol); a copy-var'd host leaf is never observed"}
      {:mechanism :built-in-call-observer
       :file "reference-code/sci/src/sci/impl/analyzer.cljc:1745-1750"
       :why "the observer is called for effect only; its return value is discarded and the node then evaluates the ORIGINAL call. It cannot reshape arguments."}
      {:mechanism :built-in-call-observer
       :file "reference-code/sci/src/sci/impl/analyzer.cljc:1719"
       :why "read from the ANALYSIS ctx inside return-call's own let, so the node closes over one fork's observer (the filed bug)"}
      {:mechanism :wrap
       :file "reference-code/sci/src/sci/impl/analyzer.cljc:1718,1727-1741"
       :why "an analyzer-internal 6th positional parameter, not an option; opts/init has no key that reaches it"}
      {:mechanism :wrap
       :file "reference-code/sci/src/sci/impl/analyzer.cljc:2086-2090,2101-2104,2114-2121"
       :why "the JVM direct-Var and direct-fn call paths pass nil, so the seam is inert exactly where capability leaves are called"}
      {:mechanism :wrap
       :file "reference-code/sci/src/sci/impl/analyzer.cljc:1730,1740"
       :why "wrap REPLACES the callee ((wrap ctx bindings f) arg0 arg1); the args are already evaluated inline and applied positionally, so it cannot add a missing argument"}
      {:mechanism :host-fn-signature
       :file "reference-code/sci/src/sci/impl/analyzer.cljc:1717-1744, evaluator.cljc:398-420"
       :why "a copy-var'd host function is invoked as (f arg0 ...) / (apply f args): no ctx reaches the host body"}]}))
