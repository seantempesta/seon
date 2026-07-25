(ns attack.a1-durability
  "ATTACK 1: is a RETURNED d/transact durable across SIGKILL?

   The whole design rests on 'a survivor resumes from receipts'. If a commit
   that already returned can vanish when the JVM is killed, the survivor
   resumes from a position that is BEHIND what actually happened, and every
   step between the lost commit and the truth is re-executed -- including any
   external effect it performed.

   Method: child commits {:agent/id aN} one at a time, printing OK n after
   d/transact RETURNS. Parent kills on a chosen OK marker (kill lands strictly
   after that commit returned, while the child is starting the next one).
   Then reopen the store and count which agents survived."
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [flow.store :as store]))

(defn spawn [main path]
  (let [java (str (System/getProperty "java.home") "/bin/java")
        pb (ProcessBuilder. ^java.util.List
                            [java "-cp" (System/getProperty "java.class.path")
                             "--enable-native-access=ALL-UNNAMED"
                             "--sun-misc-unsafe-memory-access=allow" "-Xmx512m"
                             "clojure.main" "-m" main path])]
    (.start pb)))

(defn run-once [path kill-at]
  (let [proc (spawn "attack.a1-child-commit" path)
        rdr (io/reader (.getInputStream proc))
        last-ok (volatile! -1)
        last-pre (volatile! -1)]
    (loop []
      (when-let [line (.readLine rdr)]
        (cond
          (.startsWith line "OK ")
          (let [n (parse-long (subs line 3))]
            (vreset! last-ok n)
            (if (= n kill-at)
              (do (.destroyForcibly proc) (.waitFor proc))
              (recur)))
          (.startsWith line "PRE ")
          (do (vreset! last-pre (parse-long (subs line 4))) (recur))
          :else (recur))))
    (Thread/sleep 300)
    {:last-ok @last-ok
     :last-pre @last-pre
     :exit (.exitValue proc)}))

(defn -main [path & _]
  (println "=== A1: durability of a RETURNED commit across SIGKILL ===")
  (doseq [kill-at [7 63 211]]
    (let [{:keys [last-ok exit]} (run-once path kill-at)
          conn (try (store/reopen! path) (catch Throwable t (println "  REOPEN FAILED:" t) nil))]
      (if-not conn
        (println (format "kill-after-OK-%d: STORE WOULD NOT REOPEN" kill-at))
        (let [db (d/db conn)
              present (into (sorted-set)
                            (map (fn [[s]] (parse-long (subs s 1))))
                            (d/q '[:find ?s :where [?e :agent/id ?s]] db))
              expected (into (sorted-set) (range 0 (inc last-ok)))
              missing (clojure.set/difference expected present)
              extra (clojure.set/difference present expected)]
          (println (format "kill-after-OK-%d  exit=%d  max-tx=%s" kill-at exit (:max-tx db)))
          (println (format "  commits that RETURNED: 0..%d (%d)" last-ok (inc last-ok)))
          (println (format "  agents found in reopened store: %d" (count present)))
          (println (format "  MISSING (returned but lost): %s" (if (seq missing) (vec missing) "none")))
          (println (format "  EXTRA (never returned but present): %s" (if (seq extra) (vec extra) "none")))
          (println (format "  VERDICT: %s"
                           (if (seq missing) "DURABILITY HOLE" "durable")))
          (d/release conn)))))
  (System/exit 0))
