(ns probe.main
  "sci-in-Bun execution-child feasibility probe.

   Phase-driven so an external driver (run.sh) can vmmap the process
   between phases. Protocol: prints `PHASE <name> READY` on stdout,
   then blocks until one line arrives on stdin. Timings and gap-probe
   results print as `DATA <edn>` lines.

   Research harness only — never production code."
  (:require
    [clojure.string :as str]
    [malli.core :as m]
    [sci.core :as sci]
    [seon.ai.tokens :as tokens]
    [seon.schema :as schema]))

;;; ---------------------------------------------------------------- util

(defn now [] (js/performance.now))

(defn time-best
  "Best-of-n wall ms for thunk f (min like the sci ADR harness)."
  [n f]
  (loop [i 0 best js/Number.POSITIVE_INFINITY]
    (if (< i n)
      (let [t0 (now)]
        (f)
        (recur (inc i) (min best (- (now) t0))))
      best)))

(defn data! [m]
  (println (str "DATA " (pr-str m))))

(defn heap-stats []
  (let [jsc (js/require "bun:jsc")
        s ((.-heapStats jsc))]
    {:heap-size-mb (js/Math.round (/ (.-heapSize s) 1048576))
     :heap-capacity-mb (js/Math.round (/ (.-heapCapacity s) 1048576))
     :object-count (.-objectCount s)}))

(defn rss-mb []
  (js/Math.round (/ (.-rss (js/process.memoryUsage)) 1048576)))

(defn gc! []
  (when (exists? js/Bun) (js/Bun.gc true)))

;;; ------------------------------------------------------- sci context

(def db-facts (atom []))

(defn fake-transact!
  "Models seon.db/transact! — an async host boundary returning a Promise
   of an envelope, like the real ^:async db verbs the child exposes."
  [tx]
  (js/Promise.resolve
    {:seon.db/ok? true
     :tx-data tx
     :db-after {:t (count (swap! db-facts conj tx))}}))

(defn fake-query
  [_q]
  (js/Promise.resolve @db-facts))

(defn make-ctx
  "A realistic-ish binding table: compiled Seon fns (seon.ai.tokens),
   the schema registry surface, malli, db-shaped async host verbs, and
   js interop enabled (the child is not a security boundary)."
  []
  (sci/init
    {:classes {'js js/globalThis :allow :all}
     :namespaces
     {'seon.ai.tokens {'estimate tokens/estimate
                       'chars->tokens tokens/chars->tokens
                       'estimate-chars tokens/estimate-chars
                       'chars-per-token tokens/chars-per-token}
      'seon.schema {'register! schema/register!
                    'current-keys schema/current-keys
                    'valid-candidate-value? schema/valid-candidate-value?}
      'malli.core {'validate m/validate
                   'explain m/explain
                   'schema m/schema}
      'seon.db {'transact! fake-transact!
                'query fake-query}}}))

(def !ctx (atom nil))

;;; --------------------------------------------------------- workloads

(def plan-form
  ;; my.plan-shaped: 250 tasks x 3 children = 1000 nodes.
  '(do
     (defn make-plan [n]
       (vec (for [i (range n)]
              {:my.plan/id i
               :my.plan/title (str "task-" i)
               :my.plan/status (if (zero? (mod i 3)) :todo :doing)
               :my.plan/children
               (vec (for [j (range 3)]
                      {:my.plan/id (+ (* 1000 i) j)
                       :my.plan/title (str "sub-" i "-" j)
                       :my.plan/status :todo}))})))
     (defn complete-all [plan]
       (mapv (fn [t]
               (-> t
                   (assoc :my.plan/status :done)
                   (update :my.plan/children
                           (fn [cs] (mapv #(assoc % :my.plan/status :done) cs)))))
             plan))
     (defn plan-stats [plan]
       (reduce (fn [acc t]
                 (-> acc
                     (update (:my.plan/status t) (fnil inc 0))
                     (update :children + (count (:my.plan/children t)))))
               {:children 0}
               plan))
     (def plan (make-plan 250))
     (plan-stats (complete-all plan))))

(def loop-form
  '(loop [i 0 acc 0]
     (if (< i 1000000)
       (recur (inc i) (+ acc i))
       acc)))

(def burst-forms
  ;; 100 defns + invocations, distinct bodies.
  (vec (mapcat (fn [i]
                 [(list 'defn (symbol (str "f" i)) '[x]
                        (list '+ (list '* 'x 2) i))
                  (list (symbol (str "f" i)) i)])
               (range 100))))

(def heavy-form
  ;; MB-scale pr-str probes mirroring the bisect's heavy eval burst.
  '(let [big (vec (range 200000))]
     (count (pr-str (vec (repeat 10 big))))))

(defn sci-eval [src]
  (sci/eval-string* @!ctx src))

;; Compiled-CLJS baselines (same shapes, AOT-compiled by cljs.main).
(defn compiled-loop []
  (loop [i 0 acc 0]
    (if (< i 1000000)
      (recur (inc i) (+ acc i))
      acc)))

(defn compiled-plan []
  (let [mk (fn [n]
             (vec (for [i (range n)]
                    {:my.plan/id i
                     :my.plan/title (str "task-" i)
                     :my.plan/status (if (zero? (mod i 3)) :todo :doing)
                     :my.plan/children
                     (vec (for [j (range 3)]
                            {:my.plan/id (+ (* 1000 i) j)
                             :my.plan/title (str "sub-" i "-" j)
                             :my.plan/status :todo}))})))
        plan (mk 250)
        done (mapv (fn [t]
                     (-> t
                         (assoc :my.plan/status :done)
                         (update :my.plan/children
                                 (fn [cs] (mapv #(assoc % :my.plan/status :done) cs)))))
                   plan)]
    (reduce (fn [acc t]
              (-> acc
                  (update (:my.plan/status t) (fnil inc 0))
                  (update :children + (count (:my.plan/children t)))))
            {:children 0}
            done)))

(defn run-workloads! []
  (let [jit? (not (unchecked-get js/globalThis "SCI_DISABLE_JIT"))
        mode (if jit? :jit :interp)
        plan-src (pr-str plan-form)
        loop-src (pr-str loop-form)
        heavy-src (pr-str heavy-form)]
    ;; warm each once, then best-of-5
    (sci-eval plan-src)
    (data! {:workload :plan-transform-1000-node :mode mode
            :ms (time-best 5 #(sci-eval plan-src))})
    (sci-eval loop-src)
    (data! {:workload :tight-loop-1e6 :mode mode
            :ms (time-best 5 #(sci-eval loop-src))})
    (data! {:workload :tight-loop-1e6 :mode :compiled-cljs
            :ms (time-best 5 compiled-loop)})
    (data! {:workload :plan-transform-1000-node :mode :compiled-cljs
            :ms (time-best 5 compiled-plan)})
    ;; eval burst: 100 defn + invocations
    (let [srcs (mapv pr-str burst-forms)]
      (data! {:workload :defn-burst-100 :mode mode
              :ms (time-best 3 (fn [] (doseq [s srcs] (sci-eval s))))}))
    (sci-eval heavy-src)
    (data! {:workload :heavy-pr-str :mode mode
            :ms (time-best 3 #(sci-eval heavy-src))})))

(defn run-heavy-burst! []
  ;; sustained MB-scale burst for the retention phase
  (let [heavy-src (pr-str heavy-form)]
    (dotimes [_ 10] (sci-eval heavy-src))
    (data! {:burst :done :heap (heap-stats) :rss-mb (rss-mb)})))

;;; -------------------------------------------------------- gap probes

(defn probe-async! []
  ;; Gap 1: ^:async/await inside sci; awaited-Promise contract on top.
  ;; Deliberately NOT a compiled ^:async fn: a try in expression position
  ;; inside a compiled-CLJS async fn becomes an AWAITED async IIFE and
  ;; silently unwraps Promises (observed here first-hand).
  (let [r1 (sci-eval "(defn af [x] (inc x)) (af 1)")
        r2 (sci-eval "(defn ^:async slow-inc [x]
                        (let [v (await (js/Promise.resolve x))]
                          (inc v)))
                      (slow-inc 41)")
        r3 (sci-eval "(seon.db/transact! [[:db/add 1 :a 1]])")
        r4 (try (sci-eval "(await (js/Promise.resolve 1))")
                (catch :default e {:err-message (.-message e)}))]
    ;; maybe-await-value semantics: eval once; a Promise value awaits to data.
    (-> (js/Promise.all #js [(js/Promise.resolve r2) (js/Promise.resolve r3)])
        (.then (fn [arr]
                 (data! {:gap :async
                         :plain r1
                         :async-defn-native-promise? (instance? js/Promise r2)
                         :awaited-value (aget arr 0)
                         :db-verb-native-promise? (instance? js/Promise r3)
                         :db-envelope (select-keys (aget arr 1) [:seon.db/ok?])
                         :top-level-await r4}))))))

(defn probe-macros! []
  ;; Gap 2: core macros, user defmacro, repo idioms (->, cond->, doseq).
  (let [core (try (sci-eval "(->> (range 10) (map inc) (filter odd?) (reduce +))")
                  (catch :default e {:err (str e)}))
        condm (try (sci-eval "(cond-> {:a 1} true (assoc :b 2) false (assoc :c 3))")
                   (catch :default e {:err (str e)}))
        userm (try (sci-eval "(defmacro unless [test then else]
                                (list 'if test else then))
                              (unless false :yes :no)")
                   (catch :default e {:err (str e)}))
        whenlet (try (sci-eval "(when-let [x (:a {:a 5})] (* x x))")
                     (catch :default e {:err (str e)}))]
    (data! {:gap :macros :threading core :cond-> condm
            :user-defmacro userm :when-let whenlet})))

(defn probe-instrument! []
  ;; Gap 3: malli-validating wrapper around a sci var producing the
  ;; errors-as-values envelope on bad input.
  (sci-eval "(defn add2 [x] (+ x 2))")
  (let [v (sci-eval "#'add2")
        raw @v
        wrapped (fn [& args]
                  (if (m/validate [:cat :int] (vec args))
                    (apply raw args)
                    {:seon/error {:seon.error/kind :seon.error/invalid-input
                                  :seon.error/message "add2 expects one int"
                                  :seon.error/explain
                                  (pr-str (m/explain [:cat :int] (vec args)))}}))]
    (sci/alter-var-root v (constantly wrapped))
    (data! {:gap :instrument
            :good (sci-eval "(add2 40)")
            :bad (select-keys (:seon/error (sci-eval "(add2 :kw)"))
                              [:seon.error/kind :seon.error/message])})))

(defn probe-vars! []
  ;; Gap 4: def/redef persistence, ns switching, vars-as-data.
  (sci-eval "(def counter 1)")
  (let [before (sci-eval "counter")]
    (sci-eval "(def counter 2)")
    (let [after (sci-eval "counter")
          fnv (do (sci-eval "(defn shout [s] (str s \"!\"))")
                  (sci-eval "(shout \"hi\")"))
          redefd (do (sci-eval "(defn shout [s] (str s \"?\"))")
                     (sci-eval "(shout \"hi\")"))
          nsref (try (sci-eval "(ns my.scratch) (def local-thing 42) (in-ns 'user) (deref (resolve 'my.scratch/local-thing))")
                     (catch :default e {:err (str e)}))
          publics (try (sci-eval "(vec (sort (keys (ns-publics 'user))))")
                       (catch :default e {:err (str e)}))]
      (data! {:gap :vars
              :def-then-read before
              :redef-read after
              :fn fnv
              :fn-redef redefd
              :cross-ns nsref
              :ns-publics-sample (if (vector? publics) (vec (take 10 publics)) publics)}))))

;;; ------------------------------------------------------- phase loop

(def phases
  [["ctx-created" (fn [] (reset! !ctx (make-ctx)) (gc!))]
   ["bindings-warm"
    (fn []
      (data! {:warm (sci-eval "[(seon.ai.tokens/estimate \"hello world this is a probe\") (malli.core/validate :int 42)]")})
      (gc!))]
   ["workloads-done"
    (fn []
      (run-workloads!)
      (probe-macros!)
      (probe-instrument!)
      (probe-vars!)
      (probe-async!) ; ^:async; its DATA line prints when resolved
      (gc!))]
   ["burst-done" (fn [] (run-heavy-burst!))]
   ["post-gc-retention"
    (fn []
      (gc!)
      (js/setTimeout
        (fn [] (data! {:post-gc (heap-stats) :rss-mb (rss-mb)}))
        2000))]])

(defn -main [& _]
  (let [i (atom 0)
        readline (js/require "readline")
        rl (.createInterface readline #js {:input js/process.stdin})
        run-phase!
        (fn []
          (let [[nm f] (nth phases @i)]
            (f)
            ;; give async probes / timers a beat before declaring ready
            (js/setTimeout
              (fn []
                (data! {:phase nm :heap (heap-stats) :rss-mb (rss-mb)})
                (println (str "PHASE " nm " READY")))
              3000)))]
    (.on rl "line"
         (fn [_]
           (swap! i inc)
           (if (< @i (count phases))
             (run-phase!)
             (do (println "DONE") (js/process.exit 0)))))
    (run-phase!)))

(set! *main-cli-fn* -main)
