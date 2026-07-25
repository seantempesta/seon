(ns a2
  "Round two. Round one produced three results with fn-entries 0, meaning the
   form never ran -- those cases proved nothing. Fix them, print the RAW
   throwable message, and push the confirmed holes harder."
  (:require [flow.eval :as eval]))

(def TIME 500)
(def ALLOC (* 64 1024 1024))

(defn- short [v]
  (let [s (pr-str v)]
    (if (> (count s) 200) (str (subs s 0 200) " ...") s)))

(defn run!
  ([label source] (run! label source 120000 {}))
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
       (println (format "    OBSERVED: STILL RUNNING after %dms. permits free %d"
                        wall (eval/available)))
       (let [{:flow/keys [value record]} res]
         (println (format "    wall %dms | outcome %s | fn-entries %,d | alloc %,d"
                          wall (:seon.eval/outcome record) (:seon.eval/fn-entries record)
                          (:seon.eval/allocated-bytes record)))
         (if (eval/error? value)
           (do (println "    agent message:" (:seon.error/message value))
               (println "    agent kind   :" (:seon.error/kind value))
               (println "    agent raw    :" (short (:seon.error/raw value))))
           (println "    value:" (short value)))))
     res)))

(defn -main [& _]
  (eval/open! 8)

  ;;; ---- B1 what can agent code actually CATCH? -------------------------
  (println "\n---------- B1: which catch clauses even analyze? ----------")
  (doseq [c ["Throwable" "Exception" "java.lang.Exception" "RuntimeException"
             "ArithmeticException" ":default" "Error" "StackOverflowError"]]
    (run! (str "B1 (catch " c " ...) around (/ 1 0)")
          (format "(try (/ 1 0) (catch %s e [:caught (str e)]))" c)))

  ;;; ---- B2 swallow attempts with a catch clause that WORKS -------------
  (println "\n---------- B2: swallow the interrupt ----------")
  (run! "B2 catch <working-class> around a runaway"
        "(try (loop [i 0] (recur (inc i))) (catch Exception e [:swallowed (str e)]))")
  (run! "B2b catch Error around a runaway"
        "(try (loop [i 0] (recur (inc i))) (catch Error e [:swallowed (str e)]))")
  (run! "B2c retry forever: inner catch, outer loop"
        "(loop [n 0] (try (loop [i 0] (recur (inc i))) (catch Exception e :caught)) (recur (inc n)))")
  (run! "B2d StackOverflowError caught in a loop, forever"
        "(do (defn f [n] (inc (f (inc n)))) (loop [k 0] (try (f 0) (catch Exception e nil)) (recur (inc k))))")

  ;;; ---- B3 fewer than 1024 fn entries, pushed harder --------------------
  (println "\n---------- B3: under the 1024-entry sample ----------")
  (run! "B3 29 fn entries of host BigInteger squaring (time-limit 500ms)"
        "(loop [x 2N i 0] (if (< i 28) (recur (* x x) (inc i)) (mod x 1000)))" 300000 {})
  (run! "B3b bare (byte-array 200000000), no count override, 0 fn entries"
        "(do (byte-array 200000000) :done)")
  (run! "B3c 1023 x 1MB host allocations -- exactly under the sample"
        "(loop [i 0] (if (< i 1022) (do (byte-array 1000000) (recur (inc i))) :done))")

  ;;; ---- B4 heap exhaustion between samples ------------------------------
  (println "\n---------- B4: retain 1GB inside 1000 entries under -Xmx512m ----------")
  (run! "B4 retain 1000 x 1MB (heap is 512m; alloc cap is 64MB)"
        "(loop [i 0 acc []] (if (< i 1000) (recur (inc i) (conj acc (byte-array 1000000))) (count acc)))"
        120000 {})
  (run! "B4b does the host still work after that?" "(+ 1 1)")

  ;;; ---- B5 StackOverflowError, full error value -------------------------
  (println "\n---------- B5: what the agent is told about a StackOverflowError ----------")
  (run! "B5 unbounded recursion" "(do (defn f [n] (inc (f (inc n)))) (f 0))")

  ;;; ---- B6 catastrophic backtracking, guarded vs unguarded --------------
  (println "\n---------- B6: regex backtracking ----------")
  (let [s (str (apply str (repeat 40 "a")) "!")]
    (doseq [[nm pat] [["nested-plus" "^(a+)+$"] ["alternation" "^(a|a)+$"]
                      ["nested-star" "^(a*)*$"] ["classic" "^(([a-z])+.)+[A-Z]$"]]]
      (run! (str "B6 " nm " via clojure.string/replace (UNGUARDED: clojure-string not merged)")
            (format "(clojure.string/replace %s #%s \"x\")" (pr-str s) (pr-str pat)) 30000 {})
      (run! (str "B6 " nm " via re-matches (GUARDED control)")
            (format "(re-matches #%s %s)" (pr-str pat) (pr-str s)) 30000 {})))

  ;;; ---- B7 read-eval ----------------------------------------------------
  (println "\n---------- B7: #= at read time ----------")
  (run! "B7 #= static method" "[:v #=(java.lang.System/getProperty \"os.name\")]")
  (run! "B7b #= fully qualified spit"
        "[:x #=(clojure.core/spit \"/private/tmp/claude-501/-Users-sean-src-seon/ad6e7227-ef9f-4cc7-954e-ea6dbabccdff/scratchpad/flow/attack-resource/PWNED.txt\" \"read-eval escaped sci\")]")
  (run! "B7c #= reading a file the agent was never given" "[:h #=(clojure.core/subs (clojure.core/System-getProperty-or \"x\") 0 1)]")
  (run! "B7d #= constructing a Runtime handle" "[:r #=(clojure.core/str (java.lang.Runtime/getRuntime))]")

  ;;; ---- B8 the diagnosis the agent gets --------------------------------
  (println "\n---------- B8: :time vs :memory labelling of the SAME defect ----------")
  (run! "B8 runaway loop, default 64MB alloc cap" "(loop [i 0] (recur (inc i)))")
  (run! "B8b the SAME runaway with a 4GB alloc cap" "(loop [i 0] (recur (inc i)))"
        120000 {:allocation-limit-bytes (* 4 1024 1024 1024)})
  (run! "B8c genuinely blocked host call, 4GB cap" "(host/block 900)"
        120000 {:allocation-limit-bytes (* 4 1024 1024 1024)})

  ;;; ---- B9 finally that outlives the kill, forever ----------------------
  (println "\n---------- B9: finally outliving the interrupt ----------")
  (run! "B9 (try <runaway> (finally (host/block 600000)))"
        "(try (loop [i 0] (recur (inc i))) (finally (host/block 600000)))" 4000 {})
  (println "    permits free now:" (eval/available))

  (println "\ndone.")
  (shutdown-agents)
  (System/exit 0))
