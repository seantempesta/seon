(ns attack.a2-kill-in-commit
  "ATTACK 2: SIGKILL landing INSIDE a d/transact.

   The design says 'any process may die at any time and a survivor resumes'.
   The most dangerous moment is inside the commit itself. If the file store
   can be left half-written, the survivor does not resume -- it reopens a
   corrupt database, and every receipt in it is suspect.

   Method: each transaction writes 200 entities sharing one :agent/counter
   tag. Parent kills at a RANDOM delay after the child announces PRE i, so
   the kill lands somewhere inside the commit. On reopen, every tag must
   contain exactly 0 or 200 entities."
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [flow.store :as store]))

(def width 200)

(defn spawn [main path]
  (let [java (str (System/getProperty "java.home") "/bin/java")]
    (.start (ProcessBuilder. ^java.util.List
                             [java "-cp" (System/getProperty "java.class.path")
                              "--enable-native-access=ALL-UNNAMED"
                              "--sun-misc-unsafe-memory-access=allow" "-Xmx512m"
                              "clojure.main" "-m" main path]))))

(defn trial [path kill-at delay-ms]
  (let [proc (spawn "attack.a2-child-wide" path)
        rdr (io/reader (.getInputStream proc))
        last-ok (volatile! -1)]
    (loop []
      (when-let [line (.readLine rdr)]
        (cond
          (.startsWith line "OK ") (do (vreset! last-ok (parse-long (subs line 3))) (recur))
          (and (.startsWith line "PRE ") (= kill-at (parse-long (subs line 4))))
          (do (Thread/sleep (long delay-ms))
              (.destroyForcibly proc) (.waitFor proc))
          :else (recur))))
    (Thread/sleep 250)
    {:last-ok @last-ok :exit (.exitValue proc)}))

(defn -main [path & _]
  (println "=== A2: SIGKILL inside d/transact -- torn write / corrupt store? ===")
  (let [results
        (doall
         (for [[kill-at delay-ms] [[11 0] [23 1] [37 3] [51 6] [70 10] [90 0] [110 2] [131 5]]]
           (let [_ (do (.delete (io/file path)) nil)
                 {:keys [last-ok exit]} (trial path kill-at delay-ms)
                 reopened (try {:conn (store/reopen! path)}
                               (catch Throwable t {:err t}))]
             (if (:err reopened)
               (do (println (format "kill PRE-%d +%dms : REOPEN FAILED -- %s"
                                    kill-at delay-ms (.getMessage ^Throwable (:err reopened))))
                   {:corrupt true})
               (let [db (d/db (:conn reopened))
                     tags (into (sorted-map)
                                (map (fn [[c n]] [c n]))
                                (d/q '[:find ?c (count ?e) :where
                                       [?e :agent/counter ?c] [?e :agent/id _]] db))
                     torn (into {} (remove (fn [[_ n]] (= n width)) tags))
                     highest (when (seq tags) (key (last tags)))]
                 (println (format "kill PRE-%d +%dms  exit=%d  last-OK=%d  tags=%d  highest-tag=%s  torn=%s"
                                  kill-at delay-ms exit last-ok (count tags) highest
                                  (if (seq torn) (pr-str torn) "none")))
                 (when (and (pos? last-ok) (not (contains? tags last-ok)))
                   (println (format "    !! tag %d RETURNED from d/transact but is ABSENT after reopen"
                                    last-ok)))
                 (d/release (:conn reopened))
                 {:torn (seq torn)
                  :lost (and (>= last-ok 0) (not (contains? tags last-ok)))})))))]
    (println)
    (println "SUMMARY:"
             (cond (some :corrupt results) "STORE CORRUPTION on at least one kill"
                   (some :torn results) "TORN TRANSACTION observed"
                   (some :lost results) "LOST a returned commit"
                   :else "all kills left an atomic, reopenable store"))
    (System/exit 0)))
