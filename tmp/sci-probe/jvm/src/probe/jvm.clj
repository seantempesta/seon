(ns probe.jvm
  "JVM sci agent-host probe: per-context marginal footprint with N contexts
   sharing one process, thread interruption of a runaway sci loop, and a
   memory-bomb blast-radius check.

   Phase protocol identical to the Bun probe: prints `PHASE <name> READY`,
   waits for a stdin line; `DATA <edn>` lines carry results."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [sci.core :as sci]
            [sci.interrupt :as interrupt])
  (:gen-class))

(defn data! [m] (println (str "DATA " (pr-str m))) (flush))

(defn used-heap-kb
  "Used JVM heap after a settle GC, in KB."
  []
  (System/gc)
  (Thread/sleep 200)
  (System/gc)
  (let [rt (Runtime/getRuntime)]
    (quot (- (.totalMemory rt) (.freeMemory rt)) 1024)))

(defn used-heap-mb
  "Used JVM heap after a settle GC, in MB."
  []
  (quot (used-heap-kb) 1024))

;;; ------------------------------------------------ shared program data

(def shared-schemas
  "One shared 'committed projection'-shaped value: compiled Malli schemas
   built ONCE in the host, referenced by every context's binding table.
   Models the ~91 MB band that every Bun child rebuilds privately."
  (into {}
        (for [i (range 500)]
          [(keyword "seon.probe" (str "attr" i))
           (m/schema [:map [:id :int] [:name :string] [:tags [:vector :keyword]]])])))

(def shared-program
  "Shared library data: a large immutable structure standing in for the
   program graph every child currently materializes privately."
  (vec (for [i (range 20000)]
         {:seon.fn/name (symbol (str "seon.x" (mod i 100)) (str "f" i))
          :seon.fn/arity [1 2]
          :seon.fn/doc (str "docstring for fn " i " with some padding text")})))

(def agent-lib-src
  "Per-agent source each context evals at admission (10 defns)."
  (str/join "\n"
            (for [i (range 10)]
              (str "(defn agent-fn-" i " [x] (+ (* x 2) " i "))"))))

(defn make-agent-ctx
  "One agent's sci context: same shared host bindings, own var space."
  []
  (let [ctx (sci/init
              {:namespaces
               {'seon.schema {'projection shared-schemas
                              'validate (fn [k v] (m/validate (get shared-schemas k) v))}
                'seon.program {'graph shared-program
                               'lookup (fn [n] (first (filter #(= n (:seon.fn/name %)) shared-program)))}}})]
    (sci/eval-string* ctx agent-lib-src)
    ctx))

(defonce !ctxs (atom []))

;;; --------------------------------------------------------- interrupt

(defn interrupt-probe
  "Run a runaway sci loop on a thread; prove Thread/interrupt stops it."
  []
  (let [ctx (sci/init {:interrupt-fn
                       (fn []
                         (when (.isInterrupted (Thread/currentThread))
                           (interrupt/interrupt! "runaway agent eval interrupted")))})
        !result (atom :running)
        t (Thread. (fn []
                     (try
                       (sci/eval-string* ctx "(loop [i 0] (recur (inc i)))")
                       (reset! !result :completed?!)
                       (catch Throwable e
                         (reset! !result [:stopped (.getMessage e)])))))]
    (.start t)
    (Thread/sleep 300)
    (let [t0 (System/nanoTime)]
      (.interrupt t)
      (.join t 3000)
      {:alive? (.isAlive t)
       :result @!result
       :interrupt->join-ms (quot (- (System/nanoTime) t0) 1000000)})))

(defn swallow-probe
  "Can sandboxed code swallow the interrupt with try/catch?"
  []
  (let [ctx (sci/init {:interrupt-fn
                       (fn []
                         (when (.isInterrupted (Thread/currentThread))
                           (interrupt/interrupt! "interrupted")))})
        !result (atom :running)
        t (Thread. (fn []
                     (try
                       (sci/eval-string* ctx
                         "(loop [i 0]
                            (let [x (try (inc i) (catch Throwable e :swallowed))]
                              (recur (if (number? x) x 0))))")
                       (reset! !result :eval-returned)
                       (catch Throwable e
                         (reset! !result [:host-caught (.getMessage e)])))))]
    (.start t)
    (Thread/sleep 300)
    (.interrupt t)
    (.join t 3000)
    {:alive? (.isAlive t) :result @!result}))

(defn memory-bomb-probe
  "One agent context allocates unboundedly; what is the blast radius?"
  []
  (let [ctx (sci/init {})
        !result (atom :running)
        t (Thread. (fn []
                     (try
                       (sci/eval-string* ctx "(count (vec (range 4000000000)))")
                       (reset! !result :completed?!)
                       (catch OutOfMemoryError e
                         (reset! !result [:oom (.getMessage e)]))
                       (catch Throwable e
                         (reset! !result [:threw (str (type e))])))))]
    (.start t)
    (.join t 60000)
    ;; is the process still functional afterwards?
    {:bomb @!result
     :process-still-works (sci/eval-string* (first @!ctxs) "(agent-fn-0 20)")
     :used-heap-mb (used-heap-mb)}))

;;; ------------------------------------------------------------ phases

(def phases
  [["baseline" (fn [] (data! {:used-heap-mb (used-heap-mb)}))]
   ["shared-loaded"
    (fn []
      (data! {:schemas (count shared-schemas)
              :program (count shared-program)
              :used-heap-mb (used-heap-mb)}))]
   ["ctx-1"
    (fn []
      (let [h0 (used-heap-mb)]
        (swap! !ctxs conj (make-agent-ctx))
        (data! {:first-ctx-mb (- (used-heap-mb) h0)
                :sanity (sci/eval-string* (first @!ctxs) "(agent-fn-3 10)")})))]
   ["ctx-20"
    (fn []
      (let [h0 (used-heap-kb)]
        (dotimes [_ 99] (swap! !ctxs conj (make-agent-ctx)))
        (let [h1 (used-heap-kb)]
          (data! {:ctxs (count @!ctxs)
                  :marginal-kb-per-ctx (/ (- h1 h0) 99.0)
                  :used-heap-kb h1
                  :cross-ctx-isolated?
                  ;; var defined in ctx-0 must not leak into ctx-19
                  (try (sci/eval-string* (last @!ctxs) "probe-leak")
                       :leaked
                       (catch Exception _ true))}))))]
   ["workload"
    (fn []
      ;; every ctx runs the plan-shaped workload once
      (let [src "(let [plan (vec (for [i (range 250)]
                                   {:id i :status :todo
                                    :children (vec (for [j (range 3)] {:id j :status :todo}))}))]
                   (count (mapv (fn [t] (assoc t :status :done)) plan)))"
            t0 (System/nanoTime)]
        (doseq [c @!ctxs] (sci/eval-string* c src))
        (data! {:all-20-plan-ms (quot (- (System/nanoTime) t0) 1000000)
                :used-heap-mb (used-heap-mb)})))]
   ["interrupt"
    (fn []
      (sci/eval-string* (first @!ctxs) "(def probe-leak :here)")
      (data! (merge {:probe :interrupt} (interrupt-probe)))
      (data! (merge {:probe :interrupt-swallow} (swallow-probe))))]
   ["memory-bomb"
    (fn [] (data! (merge {:probe :memory-bomb} (memory-bomb-probe))))]])

(defn -main [& _]
  (let [rdr (java.io.BufferedReader. (java.io.InputStreamReader. System/in))]
    (doseq [[nm f] phases]
      (f)
      (println (str "PHASE " nm " READY"))
      (flush)
      (.readLine rdr))
    (println "DONE")
    (System/exit 0)))
