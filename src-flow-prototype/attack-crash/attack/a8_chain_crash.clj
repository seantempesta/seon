(ns attack.a8-chain-crash
  "ATTACK 8: SIGKILL a claimant while a DIFFERENT agent is mid-turn.

   The child runs the real event-driven path -- one d/listen on the committed
   transaction feed, one virtual thread per scan (driver.clj:186-195) -- over
   a b1->b2->b3 chain. The parent kills it at an arbitrary instant, so the
   kill lands wherever it lands: inside b1's turn, inside b2's, between a
   message commit and the run that message opens.

   A survivor process then scans to quiescence. Checks:
     * every agent that was woken has EXACTLY ONE run (no duplicate runs
       opened for the same message)
     * every run's receipts cover 0..6 and are all terminal
     * exactly one message per chain hop, no duplicates and none lost
     * agent/counter == 7 per agent that ran"
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [flow.driver :as driver]
            [flow.program :as program]
            [flow.store :as store]))

(defn spawn [path]
  (.start (doto (ProcessBuilder. ^java.util.List
                                 [(str (System/getProperty "java.home") "/bin/java")
                                  "-cp" (System/getProperty "java.class.path")
                                  "--enable-native-access=ALL-UNNAMED"
                                  "--sun-misc-unsafe-memory-access=allow" "-Xmx512m"
                                  "clojure.main" "-m" "attack.a8-child-chain" path])
          (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD))))

(defn survivor-scan!
  "Scan to quiescence, as a real survivor process would when it takes over."
  [conn]
  (loop [rounds 0 drove 0]
    (let [res (driver/scan! conn "SURVIVOR" program/reply)
          n (count res)]
      (if (or (> rounds 25) (and (zero? n) (empty? (driver/waking-inbound (d/db conn)))))
        {:rounds rounds :runs-driven (+ drove n)}
        (do (Thread/sleep 100) (recur (inc rounds) (+ drove n)))))))

(defn report [conn label]
  (let [db (d/db conn)
        runs (d/q '[:find ?r ?id ?aid :where [?r :run/id ?id]
                    [?r :run/agent ?a] [?a :agent/id ?aid]] db)
        msgs (sort (map (fn [[i to]] [i to])
                        (d/q '[:find ?mid ?to :where [?m :message/id ?mid]
                               [?m :message/to ?a] [?a :agent/id ?to]] db)))
        per-run (into (sorted-map)
                      (for [[r id aid] runs]
                        [id {:agent aid
                             :open? (:run/open? (d/pull db [:run/open?] r))
                             :receipts (vec (sort (d/q '[:find ?i ?o :in $ ?r :where
                                                         [?e :seon.eval/run ?r]
                                                         [?e :seon.eval/index ?i]
                                                         [?e :seon.eval/outcome ?o]] db r)))}]))]
    (println (str "  " label ":"))
    (println "    messages:" (pr-str msgs))
    (doseq [[id {:keys [agent open? receipts]}] per-run]
      (println (format "    run %-22s agent=%s open?=%s receipts=%s"
                       id agent open? (pr-str (mapv (fn [[i o]] [i o]) receipts)))))
    (doseq [a ["b1" "b2" "b3"]]
      (println (format "    %s counter=%s log-lines=%s" a
                       (:agent/counter (d/pull db [:agent/counter] [:agent/id a]))
                       (count (or (:agent/log (d/pull db [:agent/log] [:agent/id a])) [])))))
    {:runs (mapv second runs) :msgs msgs :per-run per-run}))

(defn -main [base & _]
  (println "=== A8: kill mid-chain, survivor scans to quiescence ===")
  (doseq [delay-ms [600 1400 2600 4200]]
    (let [path (str base "-" delay-ms)]
      (when (.exists (io/file path)) (run! io/delete-file (reverse (file-seq (io/file path)))))
      (let [proc (spawn path)
            rdr (io/reader (.getInputStream proc))]
        (loop [] (let [l (.readLine rdr)]
                   (when (and l (not (.startsWith ^String l "SEEDED"))) (recur))))
        (Thread/sleep (long delay-ms))
        (.destroyForcibly proc)
        (.waitFor proc)
        (Thread/sleep 300)
        (println (str "\n--- killed " delay-ms "ms after seeding (exit " (.exitValue proc) ") ---"))
        (let [conn (store/reopen! path)
              before (report conn "AT CRASH")
              _ (println "  ...survivor scanning...")
              {:keys [rounds runs-driven]} (survivor-scan! conn)
              after (report conn (format "AFTER SURVIVOR (%d scan rounds, %d runs driven)"
                                         rounds runs-driven))
              db (d/db conn)
              dup-runs (->> (:runs after) frequencies (filter (fn [[_ n]] (> n 1))) (into {}))
              dup-msgs (->> (map first (:msgs after)) frequencies (filter (fn [[_ n]] (> n 1))) (into {}))
              bad-runs (into {} (for [[id {:keys [receipts open?]}] (:per-run after)
                                      :let [idx (into (sorted-set) (map first) receipts)
                                            ok (every? #(= :ok (second %)) receipts)]
                                      :when (or open? (not ok) (not= idx (into (sorted-set) (range 7))))]
                                  [id {:open? open? :all-ok ok :indices (vec idx)}]))
              counters (into {} (for [a ["b1" "b2" "b3"]]
                                  [a (:agent/counter (d/pull db [:agent/counter] [:agent/id a]))]))]
          (println "  CHECKS:")
          (println "    duplicate runs:" (if (seq dup-runs) (pr-str dup-runs) "none"))
          (println "    duplicate messages:" (if (seq dup-msgs) (pr-str dup-msgs) "none"))
          (println "    incomplete/open runs:" (if (seq bad-runs) (pr-str bad-runs) "none"))
          (println "    counters:" (pr-str counters) " (7 per agent that ran a full turn)")
          (println "    chain reached b3?" (boolean (some #(= "b3" (second %)) (:msgs after))))
          (println "  VERDICT:"
                   (cond (seq dup-runs) "DUPLICATE RUN for one message"
                         (seq dup-msgs) "DUPLICATE MESSAGE"
                         (seq bad-runs) "a run did not complete"
                         (not (some #(= "b3" (second %)) (:msgs after))) "CHAIN LOST -- b3 was never reached"
                         (not-every? #(= 7 %) (vals counters)) (format "COUNTER DRIFT %s" (pr-str counters))
                         :else "clean"))
          (d/release conn)))))
  (System/exit 0))
