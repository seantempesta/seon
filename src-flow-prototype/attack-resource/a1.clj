(ns a1
  "Adversarial battery against flow.eval/evaluate: try to escape the bounds.
   Every case is OBSERVED, never asserted. Each prints wall ms, the record the
   driver would commit, and what the agent would see."
  (:require [flow.eval :as eval]
            [flow.interrupt :as interrupt]))

(def TIME 500)
(def ALLOC (* 64 1024 1024))

(defn- short [v]
  (let [s (pr-str v)]
    (if (> (count s) 220) (str (subs s 0 220) " ...") s)))

(defn run!
  "Evaluate on a watchdog: if it does not return within `wait-ms`, say so and
   walk away (compute threads are daemons)."
  ([label source] (run! label source 60000 {}))
  ([label source wait-ms opts]
   (println (format "\n=== %s" label))
   (println "    source:" (short source))
   (let [t0 (System/nanoTime)
         fut (future (eval/evaluate (merge {:source source :db nil
                                            :time-limit-ms TIME
                                            :allocation-limit-bytes ALLOC}
                                           opts)))
         res (deref fut wait-ms ::still-running)
         wall (quot (- (System/nanoTime) t0) 1000000)]
     (if (= res ::still-running)
       (println (format "    OBSERVED: STILL RUNNING after %dms -- did not return. permits now %d"
                        wall (eval/available)))
       (let [{:flow/keys [value record]} res]
         (println (format "    wall %dms | outcome %s | fn-entries %,d | alloc %,d | permits-free %d"
                          wall (:seon.eval/outcome record) (:seon.eval/fn-entries record)
                          (:seon.eval/allocated-bytes record) (eval/available)))
         (println "    agent sees:" (short (if (eval/error? value)
                                             (:seon.error/message value)
                                             value)))))
     res)))

(defn -main [& _]
  (eval/open! 8)
  (println "time-limit" TIME "ms  allocation-limit" ALLOC "bytes  permits 8")
  (println "sample interval: every 1024 fn entries (interrupt.clj:14,42)")

  ;; ---- 1. tight loop, no allocation ----------------------------------
  (run! "A1 tight non-allocating loop"
        "(loop [i 0] (if (< i 1000000000000) (recur (unchecked-inc i)) i))")

  ;; ---- 2. fewer than 1024 fn entries, expensive host work per entry ---
  (run! "A2 27 fn entries, each one a huge host BigInteger squaring"
        "(loop [x 2N i 0] (if (< i 26) (recur (* x x) (inc i)) (mod x 1000)))"
        180000 {})

  ;; ---- 3. many host allocations, still under 1024 entries -------------
  (run! "A3 1000 x 1MB host allocations in a loop (entries stay < 1024)"
        "(loop [i 0] (if (< i 1000) (do (byte-array 1000000) (recur (inc i))) :done))")

  ;; ---- 4. same, crossing the sample -- quantify the overshoot ---------
  (run! "A4 20000 x 1MB host allocations (crosses the 1024 sample)"
        "(loop [i 0] (if (< i 20000) (do (byte-array 1000000) (recur (inc i))) :done))")

  ;; ---- 5. one huge host allocation, zero fn entries -------------------
  (run! "A5 single (byte-array 200000000) -- 200MB, 0 fn entries"
        "(count (byte-array 200000000))")

  ;; ---- 6. deep recursion -> StackOverflowError ------------------------
  (run! "A6 unbounded recursion (StackOverflowError)"
        "(do (defn f [n] (inc (f (inc n)))) (f 0))")

  ;; ---- 7. try to swallow the interrupt --------------------------------
  (run! "A7 catch Throwable around a runaway"
        "(try (loop [i 0] (recur (inc i))) (catch Throwable t [:swallowed (str t)]))")

  (run! "A7b catch Throwable in an inner fn, retried forever by an outer loop"
        "(loop [n 0] (let [r (try (loop [i 0] (recur (inc i))) (catch Throwable t :caught))] (recur (inc n))))")

  ;; ---- 8. SOE caught repeatedly ---------------------------------------
  (run! "A8 StackOverflowError caught in a loop, forever"
        "(do (defn f [n] (inc (f (inc n)))) (loop [k 0] (try (f 0) (catch Throwable t nil)) (recur (inc k))))")

  ;; ---- 9. finally clause that outlives the interrupt -------------------
  (run! "A9 (try <runaway> (finally (host/block 3000)))"
        "(try (loop [i 0] (recur (inc i))) (finally (host/block 3000)))")

  (run! "A9b (try <runaway> (finally <runaway>))"
        "(try (loop [i 0] (recur (inc i))) (finally (loop [j 0] (recur (inc j)))))")

  ;; ---- 10. catastrophic backtracking -----------------------------------
  (let [s (str (apply str (repeat 26 "a")) "b")]
    (run! "A10 ReDoS via clojure.string/replace (clojure-string overrides NOT merged)"
          (format "(clojure.string/replace %s #\"^(a+)+$\" \"x\")" (pr-str s))
          120000 {})
    (run! "A10b ReDoS via re-matches (override IS merged -- control)"
          (format "(re-matches #\"^(a+)+$\" %s)" (pr-str s))
          120000 {})
    (run! "A10c ReDoS via clojure.string/split"
          (format "(clojure.string/split %s #\"^(a+)+$\")" (pr-str s))
          120000 {}))

  ;; ---- 11. reader-level escape ------------------------------------------
  (println "\n=== A11 read-string and *read-eval*")
  (println "    *read-eval* here =" *read-eval*)
  (run! "A11 #= inside the source string"
        "[:read-eval-ran #=(java.lang.System/getProperty \"java.version\")]")
  (run! "A11b #= that writes a file (host effect, outside sci entirely)"
        "[:wrote #=(do (spit \"/private/tmp/claude-501/-Users-sean-src-seon/ad6e7227-ef9f-4cc7-954e-ea6dbabccdff/scratchpad/flow/attack-resource/PWNED.txt\" \"read-eval\") :ok)]")

  ;; ---- 12. interop probes -----------------------------------------------
  (run! "A12 (.pow (biginteger 10) 50000000) -- host CPU burn via interop"
        "(str (.bitLength (.pow (biginteger 10) 50000000)))" 120000 {})
  (run! "A12b (Thread/sleep 2000) via interop"
        "(java.lang.Thread/sleep 2000)")

  ;; ---- 13. permits: block them all --------------------------------------
  (println "\n=== A13 permit exhaustion via un-overridden blocking host call")
  (println "    permits free before:" (eval/available))
  (let [blockers (mapv (fn [i]
                         (future (eval/evaluate {:source "(host/block 600000)"
                                                 :db nil :time-limit-ms TIME
                                                 :allocation-limit-bytes ALLOC})))
                       (range 8))]
    (Thread/sleep 1500)
    (println "    8 evals of (host/block 600000) submitted, time-limit 500ms")
    (println "    permits free after 1.5s:" (eval/available))
    (let [t0 (System/nanoTime)
          healthy (future (eval/evaluate {:source "(+ 1 1)" :db nil
                                          :time-limit-ms TIME
                                          :allocation-limit-bytes ALLOC}))
          r (deref healthy 5000 ::still-running)]
      (println (format "    a healthy (+ 1 1) submitted behind them: %s after %dms"
                       (if (= r ::still-running) "STILL QUEUED" (str "returned " (:flow/value r)))
                       (quot (- (System/nanoTime) t0) 1000000)))
      (println "    (blockers left running:" (count blockers) ")")))

  (println "\ndone.")
  (shutdown-agents)
  (System/exit 0))
