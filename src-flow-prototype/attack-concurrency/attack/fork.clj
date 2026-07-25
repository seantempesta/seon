(ns attack.fork
  "ATTACK 1 -- fork isolation and cross-agent leaks through the shared base ctx.

   Every eval goes through the REAL path (flow.eval/evaluate), so each form
   gets its own sci/fork, its own interrupt-fn and its own :compute thread."
  (:require [flow.ctx :as ctx]
            [flow.eval :as eval]
            [sci.core :as sci]
            [sci.interrupt :as sci-interrupt])
  (:import (java.util.concurrent CountDownLatch Executors)))

(defn ev
  "One agent form through the real eval path. Returns the value."
  [source]
  (:flow/value (eval/evaluate {:source source :db nil
                               :time-limit-ms 2000
                               :allocation-limit-bytes (* 256 1024 1024)})))

(defn section [n title] (println (format "\n--- %s. %s" n title)))

(defn -main [& _]
  (eval/open! 8)

  (section "1a" "capability probe: what can agent code reach?")
  (doseq [s ["(resolve 'alter-var-root)"
             "(resolve 'defmulti)"
             "(resolve 'extend-protocol)"
             "(resolve 'in-ns)"
             "(resolve 'intern)"
             "(resolve 'ns-map)"
             "(resolve 'atom)"
             "(inc 1)"]]
    (println "   " s "=>" (pr-str (ev s))))

  (section "1b" "two agents define the SAME var name in their own forks")
  (let [n 8
        start (CountDownLatch. 1)
        done (CountDownLatch. n)
        results (atom {})
        pool (Executors/newVirtualThreadPerTaskExecutor)]
    (dotimes [i n]
      (.submit pool ^Runnable
               (fn []
                 (.await start)
                 (swap! results assoc i
                        (ev (format "(do (def shared-name %d) (Thread/sleep 20) shared-name)" i)))
                 (.countDown done))))
    (.countDown start)
    (.await done)
    (println "    each agent read back its own value?"
             (= @results (into {} (map (fn [i] [i i])) (range n)))
             (pr-str (into (sorted-map) @results))))
  (println "    does shared-name exist in a FRESH fork afterwards?"
           (pr-str (ev "(resolve 'shared-name)")))

  (section "1c" "alter-var-root on a BASE var (clojure.core/inc) from one fork")
  (println "    before, fresh fork:      (inc 1) =>" (pr-str (ev "(inc 1)")))
  (println "    attacker:" (pr-str (ev "(alter-var-root #'clojure.core/inc (constantly (fn [_] :PWNED)))")))
  (println "    attacker's own view:     (inc 1) =>" (pr-str (ev "(inc 1)")))
  (println "    SIBLING fresh fork:      (inc 1) =>" (pr-str (ev "(inc 1)")))
  ;; repair so the rest of the run is meaningful
  (ev "(alter-var-root #'clojure.core/inc (constantly (fn [x] (+ x 1))))")
  (println "    after repair:            (inc 1) =>" (pr-str (ev "(inc 1)")))

  (section "1d" "alter-var-root on a BASE HOST var (db/basis, host/block)")
  (println "    before:" (pr-str (ev "(host/block 1)")))
  (println "    attacker:" (pr-str (ev "(alter-var-root #'host/block (constantly (fn [_] :HIJACKED)))")))
  (println "    SIBLING fresh fork: (host/block 1) =>" (pr-str (ev "(host/block 1)")))
  (ev "(alter-var-root #'host/block (constantly (fn [ms] (Thread/sleep (long ms)) :done)))")
  (println "    after repair:" (pr-str (ev "(host/block 1)")))

  (section "1e" "in-ns + def onto an existing base var (no alter-var-root)")
  (println "    attacker:" (pr-str (ev "(do (in-ns 'clojure.core) (def max-key :CLOBBERED) :done)")))
  (println "    SIBLING fresh fork: clojure.core/max-key =>" (pr-str (ev "clojure.core/max-key")))
  (println "    attacker via intern:" (pr-str (ev "(intern 'clojure.core 'min-key :INTERNED)")))
  (println "    SIBLING fresh fork: clojure.core/min-key =>" (pr-str (ev "clojure.core/min-key")))

  (section "1f" "defmulti / defmethod: fork-local multimethod isolation")
  (println "    A:" (pr-str (ev "(do (defmulti shape :k) (defmethod shape :a [_] :A) (shape {:k :a}))")))
  (println "    B (fresh fork) sees shape?" (pr-str (ev "(resolve 'shape)")))

  (section "1g" "THE CLASS: a base var whose value is mutable (atom / multimethod)")
  ;; NOT the prototype's base -- a deliberately-unsafe base built here to show
  ;; exactly what the design's invariant is protecting against.
  (let [unsafe-base (sci/init
                     {:namespaces
                      {'clojure.core sci-interrupt/clojure-core
                       'app {'registry (atom {})}}})
        run (fn [src] (sci/eval-form (sci/fork unsafe-base) (read-string src)))]
    (println "    A swaps the atom in a base var:" (pr-str (run "(swap! app/registry assoc :owned true)")))
    (println "    B (fresh fork) sees it?        " (pr-str (run "@app/registry"))))
  (let [unsafe-base (sci/init {:namespaces {'clojure.core sci-interrupt/clojure-core}})
        _ (sci/eval-form unsafe-base (read-string "(defmulti render :k)"))
        run (fn [src] (sci/eval-form (sci/fork unsafe-base) (read-string src)))]
    (println "    A defmethods a BASE multimethod:" (pr-str (run "(do (defmethod render :x [_] :from-A) :ok)")))
    (println "    B (fresh fork) dispatches it?  " (pr-str (try (run "(render {:k :x})")
                                                                (catch Throwable t (.getMessage t))))))

  (section "1h" "dynamic var: does a fork's binding/set! escape?")
  (println "    A:" (pr-str (ev "(do (def ^:dynamic *cfg* :base) (alter-var-root #'*cfg* (constantly :mutated)) *cfg*)")))
  (println "    B (fresh fork) resolves *cfg*?" (pr-str (ev "(resolve '*cfg*)")))

  (section "1i" "concurrent hammer: 200 forks racing to define + read the same name")
  (let [n 200
        pool (Executors/newVirtualThreadPerTaskExecutor)
        start (CountDownLatch. 1)
        done (CountDownLatch. n)
        bad (atom [])]
    (dotimes [i n]
      (.submit pool ^Runnable
               (fn []
                 (.await start)
                 (let [v (ev (format "(do (def racer %d) (dotimes [_ 200] nil) racer)" i))]
                   (when-not (= v i) (swap! bad conj [i v])))
                 (.countDown done))))
    (.countDown start)
    (.await done)
    (println "    cross-fork bleed (expected []):" (pr-str @bad)))

  (println "\nOK")
  (System/exit 0))
