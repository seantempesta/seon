(ns flow.crash
  "Kill a claimant with SIGKILL between form 3 and form 4 of a 7-form reply,
   then have a survivor answer -- from a PURE QUERY over ordered receipts --
   which form was in flight and exactly what remains."
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [flow.driver :as driver]
            [flow.program :as program]
            [flow.store :as store]))

(defn -main [path & _]
  (let [java (str (System/getProperty "java.home") "/bin/java")
        pb (ProcessBuilder. ^java.util.List
                            [java "-cp" (System/getProperty "java.class.path")
                             "--add-modules" "jdk.incubator.vector"
                             "--enable-native-access=ALL-UNNAMED"
                             "--sun-misc-unsafe-memory-access=allow" "-Xmx512m"
                             "clojure.main" "-m" "flow.crashee" path])
        _ (.redirectErrorStream pb false)
        proc (.start pb)
        rdr (io/reader (.getInputStream proc))]
    (println "child started; killing it (SIGKILL) once form 3 is in flight")
    (loop []
      (when-let [line (.readLine rdr)]
        (println "  child:" line)
        (if (= line "RUNNING 3")
          (do (.destroyForcibly proc)
              (println "  SIGKILL sent; child exit code" (.waitFor proc)
                       "(137 = 128+9)"))
          (recur))))
    (Thread/sleep 200)
    (let [conn (store/reopen! path)
          run (d/q '[:find ?r . :where [?r :run/id "run/c1"]] (d/db conn))
          {:keys [total in-flight remaining]} (driver/resume (d/db conn) run)]
      (println "\nSURVIVOR, from receipts alone (no stored cursor, no reply re-parse):")
      (println "  total steps:" total)
      (println "  in flight:  " (pr-str in-flight))
      (println "  remaining:")
      (doseq [r remaining] (println "   " (:index r) (pr-str (:source r))))
      (println "  terminal receipts:"
               (sort (d/q '[:find ?i ?o :in $ ?r :where [?e :seon.eval/run ?r]
                            [?e :seon.eval/index ?i] [?e :seon.eval/outcome ?o]]
                          (d/db conn) run)))
      (let [msg {:agent-id "x" :body (pr-str {})}]
        (println "\nthe REPAIR case, why re-parsing the reply is wrong:")
        (println "  entries the model emitted:" (count (program/entries msg)))
        (println "  steps actually executed:  " (count (program/reply msg)))
        (println "  entry 3 text:" (pr-str (nth (program/entries msg) 3)))
        (println "  became steps:" (pr-str (subvec (program/reply msg) 3 5)))
        (println "  the committed step plan is the authority; this run has" total "steps."))
      (println "\nrun still open?"
               (:run/open? (d/pull (d/db conn) [:run/open? :run/claimant :run/epoch] run))
               "-- a survivor claims it by CAS on the epoch and continues at index"
               (:index in-flight))
      (println "\nRESUMING as a second claimant:")
      (driver/claim! conn run "survivor" 60000)
      (let [done (driver/drive-run! conn run "survivor")]
        (println "  survivor executed indices" (mapv :seon.eval/index done))
        (println "  agent log now:"
                 (sort (:agent/log (d/pull (d/db conn) [:agent/log] [:agent/id "c1"])))))
      (System/exit 0))))
