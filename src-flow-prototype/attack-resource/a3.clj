(ns a3
  "Round three. Generalise the confirmed hole (one host call of unbounded cost
   with fewer than 1024 fn entries) and settle the regex question."
  (:require [flow.eval :as eval]))

(def TIME 500)
(def ALLOC (* 64 1024 1024))

(defn- short [v]
  (let [s (pr-str v)] (if (> (count s) 150) (str (subs s 0 150) " ...") s)))

(defn run!
  ([label source] (run! label source 120000 {}))
  ([label source wait-ms opts]
   (println (format "\n=== %s" label))
   (let [t0 (System/nanoTime)
         fut (future (eval/evaluate (merge {:source source :db nil :time-limit-ms TIME
                                            :allocation-limit-bytes ALLOC} opts)))
         res (deref fut wait-ms ::still-running)
         wall (quot (- (System/nanoTime) t0) 1000000)]
     (if (= res ::still-running)
       (println (format "    STILL RUNNING after %dms. permits free %d" wall (eval/available)))
       (let [{:flow/keys [value record]} res]
         (println (format "    wall %5dms | outcome %-7s | fn-entries %,12d | alloc %,15d"
                          wall (:seon.eval/outcome record) (:seon.eval/fn-entries record)
                          (:seon.eval/allocated-bytes record)))
         (println "    ->" (short (if (eval/error? value) (:seon.error/message value) value)))))
     res)))

(defn -main [& _]
  (eval/open! 8)

  ;;; C1 -- which catch classes resolve at all (round one's swallow tests were void)
  (println "\n---------- C1: catch classes sci resolves by default ----------")
  (doseq [c ["Throwable" "Exception" "java.lang.Throwable" "java.lang.Exception"]]
    (let [r (eval/evaluate {:source (format "(try (/ 1 0) (catch %s e :caught))" c)
                            :db nil :time-limit-ms TIME :allocation-limit-bytes ALLOC})]
      (println (format "    catch %-22s -> %s" c (short (:flow/value r))))))

  ;;; C2 -- canonical catastrophic backtracking, guarded vs unguarded
  (println "\n---------- C2: (x+x+)+y -- the canonical ReDoS ----------")
  (doseq [n [22 26 30]]
    (let [s (apply str (repeat n "x"))]
      (run! (format "C2 re-find #\"(x+x+)+y\" on %d x's (GUARDED override)" n)
            (format "(re-find #\"(x+x+)+y\" %s)" (pr-str s)) 30000 {})
      (run! (format "C2 clojure.string/replace same pattern, %d x's (UNGUARDED)" n)
            (format "(clojure.string/replace %s #\"(x+x+)+y\" \"z\")" (pr-str s)) 30000 {})))

  ;;; C3 -- more instances of "one host call, unbounded cost, < 1024 entries"
  (println "\n---------- C3: other unbounded single host calls ----------")
  (run! "C3a (str x) decimal conversion of 2^(2^24)"
        "(let [x (loop [v 2N i 0] (if (< i 24) (recur (* v v) (inc i)) v))] (count (str x)))"
        300000 {})
  (run! "C3b (apply * (repeat 300 big)) -- host variadic reduce, no ifn inside apply"
        "(let [b (loop [v 2N i 0] (if (< i 18) (recur (* v v) (inc i)) v))] (mod (apply * (repeat 300 b)) 7))"
        300000 {})
  (run! "C3c (sort (repeat ...)) control -- overridden producer, should be caught"
        "(count (sort (repeat 100000000 1)))" 30000 {})

  ;;; C4 -- how much legitimate interpreted work fits in the 64MB budget?
  (println "\n---------- C4: false positives -- honest work under the default cap ----------")
  (doseq [n [100000 500000 1000000 2000000 5000000]]
    (run! (format "C4 (reduce + (range %,d)) -- ordinary agent work" n)
          (format "(reduce + (range %d))" n) 60000 {}))

  (println "\ndone.")
  (shutdown-agents)
  (System/exit 0))
