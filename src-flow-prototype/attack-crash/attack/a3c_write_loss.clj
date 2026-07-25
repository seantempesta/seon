(ns attack.a3c-write-loss
  "ATTACK 3c: quantify the loss. Two live processes each commit 40 distinct,
   process-tagged entities against the same file store, interleaved. Every
   d/transact RETURNS successfully in both. How many survive?"
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [flow.store :as store]))

(defn -main [path & _]
  (let [conn (store/fresh! path)
        java (str (System/getProperty "java.home") "/bin/java")
        proc (.start (doto (ProcessBuilder. ^java.util.List
                                            [java "-cp" (System/getProperty "java.class.path")
                                             "--enable-native-access=ALL-UNNAMED"
                                             "--sun-misc-unsafe-memory-access=allow" "-Xmx512m"
                                             "clojure.main" "-m" "attack.a3c-child-writer" path])
                       (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)))
        rdr (io/reader (.getInputStream proc))
        wtr (io/writer (.getOutputStream proc))]
    (println "=== A3c: two live writers, 40 committed entities each ===")
    (loop [] (let [l (.readLine rdr)] (when-not (and l (.startsWith ^String l "READY")) (recur))))
    (.write wtr "go\n") (.flush wtr)
    (let [errs (atom 0)]
      (dotimes [i 40]
        (try (d/transact conn {:tx-data [{:agent/id (str "parent-" i)}]})
             (catch Throwable _ (swap! errs inc)))
        (Thread/sleep 5))
      (println "parent committed 40 (transact errors:" @errs ")"))
    (loop [] (let [l (.readLine rdr)] (when l (println " " l) (when-not (.startsWith ^String l "CHILDWROTE") (recur)))))
    (.waitFor proc)
    (Thread/sleep 400)
    (d/release conn)
    (let [db (d/db (store/reopen! path))
          ids (into #{} (map first) (d/q '[:find ?s :where [?e :agent/id ?s]] db))
          p (count (filter #(.startsWith ^String % "parent-") ids))
          c (count (filter #(.startsWith ^String % "child-") ids))]
      (println "\nAFTER REOPEN:")
      (println "  parent entities surviving:" p "/ 40")
      (println "  child  entities surviving:" c "/ 40")
      (println "  total surviving:" (+ p c) "/ 80")
      (println "  VERDICT:" (if (= 80 (+ p c))
                              "no loss"
                              (format "SILENT LOSS of %d successfully-returned commits" (- 80 (+ p c))))))
    (System/exit 0)))
