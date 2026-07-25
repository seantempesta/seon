(ns attack.a3-two-live-claimants
  "ATTACK 3: does the epoch CAS exclude a second LIVE process?

   The survivor story is 'runs are claimable database state (claimant + epoch
   CAS)'. crash.clj only ever tests a claim after the other process is dead.
   Here two live JVMs claim the same run at the same instant. If both win,
   the claim is not a claim, and every crash-resume result in the prototype
   is an artifact of only ever having one live process."
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [flow.driver :as driver]
            [flow.program :as program]
            [flow.store :as store]))

(def sources
  (mapv (fn [i] (format "(do (host/block 120) {:note \"z step %d\"})" i)) (range 6)))

(defn -main [path & _]
  (let [conn (store/fresh! path {:config/time-limit-ms 30000 :config/lease-ms 60000})]
    (d/transact conn {:tx-data [{:agent/id "z1"}]})
    (let [run (driver/start-run! conn {:run-id "run/z1" :agent-id "z1" :sources sources})
          java (str (System/getProperty "java.home") "/bin/java")
          proc (.start (ProcessBuilder. ^java.util.List
                                        [java "-cp" (System/getProperty "java.class.path")
                                         "--enable-native-access=ALL-UNNAMED"
                                         "--sun-misc-unsafe-memory-access=allow" "-Xmx512m"
                                         "clojure.main" "-m" "attack.a3-child-claim" path]))
          rdr (io/reader (.getInputStream proc))
          err (io/reader (.getErrorStream proc))
          wtr (io/writer (.getOutputStream proc))]
      (println "=== A3: two LIVE processes claim the same run ===")
      (println "child says:" (.readLine rdr))
      ;; both processes race on the claim at the same instant
      (let [child-log (future (loop [acc []]
                                (if-let [l (.readLine rdr)]
                                  (do (println "  child:" l) (recur (conj acc l)))
                                  acc)))]
        (.write wtr "go\n") (.flush wtr)
        (let [report (driver/claim! conn run "parent" 60000)]
          (println "  parent: CLAIM" (if report "WON" "LOST")
                   "epoch-now" (:run/epoch (d/pull (d/db conn) [:run/epoch] run)))
          (when report
            (println "  parent: DROVE"
                     (pr-str (mapv :seon.eval/index (driver/drive-run! conn run "parent"))))))
        (println "  parent: COUNTER"
                 (:agent/counter (d/pull (d/db conn) [:agent/counter] [:agent/id "z1"])))
        (.waitFor proc)
        @child-log
        (loop [] (when-let [l (.readLine err)] (println "  child-stderr:" l) (recur))))
      ;; ground truth after both processes are finished
      (Thread/sleep 300)
      (d/release conn)
      (let [conn2 (store/reopen! path)
            db (d/db conn2)
            run2 (d/q '[:find ?r . :where [?r :run/id "run/z1"]] db)
            receipts (sort (d/q '[:find ?i ?o :in $ ?r :where
                                  [?e :seon.eval/run ?r] [?e :seon.eval/index ?i]
                                  [?e :seon.eval/outcome ?o]] db run2))
            logs (:agent/log (d/pull db [:agent/log] [:agent/id "z1"]))]
        (println "\nGROUND TRUTH after reopen:")
        (println "  run:" (pr-str (dissoc (d/pull db [:run/epoch :run/claimant :run/open?] run2) :db/id)))
        (println "  receipts:" (pr-str receipts))
        (println "  agent/counter:" (:agent/counter (d/pull db [:agent/counter] [:agent/id "z1"]))
                 " <- one increment per COMMITTED step; 6 steps means 6")
        (println "  agent/log:" (pr-str (sort logs)))
        (println "  steps in plan:" (count sources)))
      (System/exit 0))))
