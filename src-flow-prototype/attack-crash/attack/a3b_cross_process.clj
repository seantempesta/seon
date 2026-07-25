(ns attack.a3b-cross-process
  "ATTACK 3b: the prerequisite for every survivor claim.

   Q1 VISIBILITY: process B holds a connection to the same file store while
   process A commits. Does B see it? If not, B's epoch CAS is evaluated
   against a stale local value and the claim excludes nothing.

   Q2 SIMULTANEOUS CAS: both processes CAS the same epoch at the same
   wall-clock instant. Exactly one must win."
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [flow.driver :as driver]
            [flow.store :as store]))

(def sources
  (mapv (fn [i] (format "(do (host/block 150) {:note \"z step %d\"})" i)) (range 6)))

(defn read-until [rdr prefix]
  (loop []
    (if-let [l (.readLine rdr)]
      (if (.startsWith ^String l ^String prefix) l (recur))
      nil)))

(defn -main [path & _]
  (let [conn (store/fresh! path {:config/time-limit-ms 30000 :config/lease-ms 60000})]
    (d/transact conn {:tx-data [{:agent/id "z1"} {:agent/id "probe" :agent/counter 0}]})
    (let [run (driver/start-run! conn {:run-id "run/z1" :agent-id "z1" :sources sources})
          java (str (System/getProperty "java.home") "/bin/java")
          proc (.start (doto (ProcessBuilder. ^java.util.List
                                              [java "-cp" (System/getProperty "java.class.path")
                                               "--enable-native-access=ALL-UNNAMED"
                                               "--sun-misc-unsafe-memory-access=allow" "-Xmx512m"
                                               "clojure.main" "-m" "attack.a3b-child" path])
                         (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)))
          rdr (io/reader (.getInputStream proc))
          wtr (io/writer (.getOutputStream proc))]
      (println "=== A3b: cross-process visibility and simultaneous CAS ===")
      (println "child:" (read-until rdr "READY"))

      ;; Q1 -- parent commits AFTER the child already holds its connection
      (d/transact conn {:tx-data [[:db/add [:agent/id "probe"] :agent/counter 99]]})
      (println "parent committed :agent/counter 99 on [:agent/id \"probe\"]")
      (.write wtr "go\n") (.flush wtr)
      (let [v (read-until rdr "VISIBLE")]
        (println "child:" v)
        (println "  Q1 VERDICT:" (if (= v "VISIBLE 99")
                                   "the held connection SEES another process's commit"
                                   "STALE -- the held connection does NOT see it")))

      ;; Q2 -- simultaneous CAS
      (let [at (+ (System/currentTimeMillis) 1500)]
        (.write wtr (str at "\n")) (.flush wtr)
        (let [result (future (loop [] (when (< (System/currentTimeMillis) at) (recur)))
                             (driver/claim! conn run "parent" 60000))
              child-claim (read-until rdr "CLAIM")
              parent-report @result]
          (println "child: " child-claim)
          (println "parent: CLAIM" (if parent-report "WON" "LOST")
                   "my-view-epoch" (:run/epoch (d/pull (d/db conn) [:run/epoch] run)))
          (println "  Q2 VERDICT:"
                   (let [child-won (and child-claim (.contains ^String child-claim "WON"))]
                     (cond (and child-won parent-report) "BOTH WON -- the CAS excluded nothing"
                           (or child-won parent-report) "exactly one won"
                           :else "both lost")))
          (when parent-report
            (println "parent: DROVE"
                     (pr-str (mapv :seon.eval/index (driver/drive-run! conn run "parent")))))))
      (loop [] (let [l (.readLine rdr)] (when l (println "child:" l) (recur))))
      (.waitFor proc)
      (Thread/sleep 300)
      (d/release conn)
      (let [db (d/db (store/reopen! path))
            run2 (d/q '[:find ?r . :where [?r :run/id "run/z1"]] db)]
        (println "\nGROUND TRUTH after reopen:")
        (println "  run:" (pr-str (dissoc (d/pull db [:run/epoch :run/claimant :run/open?] run2) :db/id)))
        (println "  receipts:" (pr-str (sort (d/q '[:find ?i ?o :in $ ?r :where
                                                    [?e :seon.eval/run ?r] [?e :seon.eval/index ?i]
                                                    [?e :seon.eval/outcome ?o]] db run2))))
        (println "  agent/counter:" (:agent/counter (d/pull db [:agent/counter] [:agent/id "z1"]))
                 "(should equal the 6 committed steps)")
        (println "  agent/log lines:" (count (:agent/log (d/pull db [:agent/log] [:agent/id "z1"])))))
      (System/exit 0))))
