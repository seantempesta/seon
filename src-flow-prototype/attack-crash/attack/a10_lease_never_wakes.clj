(ns attack.a10-lease-never-wakes
  "ATTACK 10: after the claimant dies, WHO wakes the survivor?

   driver/wake! is proud of being event-driven: 'One listen! on the committed
   transaction feed... No ticker, no poll, no sleep' (driver.clj:169-170).
   driver/claimable makes a run stealable when its lease has gone stale
   (driver.clj:157).

   But a lease going stale is NOT a committed transaction. It is the passage
   of wall-clock time. The committed-transaction feed cannot deliver it.

   And the moment this matters -- the only claimant just died -- is exactly
   the moment when no further transaction will ever commit. So the feed goes
   silent forever and the stranded run is never resumed.

   Method: kill the claimant mid-turn, start a survivor running the REAL
   wake! path, and then do nothing at all. Watch, read-only, for far longer
   than the lease. Then commit one unrelated fact and watch what happens."
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [flow.driver :as driver]
            [flow.program :as program]
            [flow.store :as store]))

(def lease-ms 3000)

(defn state [conn]
  (let [db (d/db conn)]
    (into (sorted-map)
          (for [[r id] (d/q '[:find ?r ?id :where [?r :run/id ?id]] db)]
            [id {:open? (:run/open? (d/pull db [:run/open?] r))
                 :receipts (count (d/q '[:find ?e :in $ ?r :where [?e :seon.eval/run ?r]] db r))
                 :claimable? (boolean (some #{r} (driver/claimable db)))}]))))

(defn -main [path & _]
  (when (.exists (io/file path)) (run! io/delete-file (reverse (file-seq (io/file path)))))
  (println "=== A10: does anything ever wake a survivor for a stale lease? ===")
  (let [proc (.start (doto (ProcessBuilder. ^java.util.List
                                            [(str (System/getProperty "java.home") "/bin/java")
                                             "-cp" (System/getProperty "java.class.path")
                                             "--enable-native-access=ALL-UNNAMED"
                                             "--sun-misc-unsafe-memory-access=allow" "-Xmx512m"
                                             "clojure.main" "-m" "attack.a8-child-chain" path])
                       (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD)))
        rdr (io/reader (.getInputStream proc))]
    (loop [] (let [l (.readLine rdr)]
               (when (and l (not (.startsWith ^String l "SEEDED"))) (recur))))
    (Thread/sleep 600)
    (.destroyForcibly proc) (.waitFor proc)
    (println "claimant SIGKILLed 600ms into the chain (exit" (.exitValue proc) ")"))
  (Thread/sleep 300)

  (let [conn (store/reopen! path)]
    (println "\nstate at crash:" (pr-str (state conn)))
    (println "lease is" lease-ms "ms\n")
    (println "survivor installs the REAL wake! path (d/listen on the committed feed)")
    (driver/wake! conn "SURVIVOR" program/reply nil)
    (println "now doing NOTHING -- no transaction, only read-only observation:\n")
    (let [t0 (System/currentTimeMillis)]
      (dotimes [i 6]
        (Thread/sleep 2000)
        (let [s (state conn)]
          (println (format "  t=%5dms  %s" (- (System/currentTimeMillis) t0) (pr-str s)))))
      (let [s (state conn)
            stranded (into {} (filter (fn [[_ v]] (:open? v)) s))]
        (println)
        (println "  after" (- (System/currentTimeMillis) t0) "ms with a"
                 lease-ms "ms lease, still open:" (pr-str (keys stranded)))
        (println "  those runs are claimable?" (pr-str (map (comp :claimable? val) stranded)))
        (println "  VERDICT:" (if (seq stranded)
                                "STRANDED -- claimable for many lease periods, and NOTHING EVER SCANNED"
                                "a survivor picked it up unprompted"))))

    (println "\nnow commit ONE unrelated fact (an entity nobody is waiting on):")
    (d/transact conn {:tx-data [{:agent/id "totally-unrelated"}]})
    (Thread/sleep 4000)
    (println "  state:" (pr-str (state conn)))
    (println "  VERDICT:" (if (some :open? (vals (state conn)))
                            "still stranded"
                            "COMPLETED -- the run only resumed because an unrelated write happened to tick the feed"))
    (d/release conn))
  (System/exit 0))
