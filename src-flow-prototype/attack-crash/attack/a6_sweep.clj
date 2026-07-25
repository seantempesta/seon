(ns attack.a6-sweep
  "ATTACK 6: kill at every interesting position in a turn, then let a real
   second process resume, and check the result rather than the story.

   Positions swept (7 steps, each a 200ms host call):
     RUNNING 0 +0ms    -- killed on the very first form, before any commit
     RUNNING 3 +0ms    -- mid-turn, between the :running receipt and the form
     RUNNING 3 +230ms  -- mid-turn, in/around the step's own commit
     RUNNING 6 +0ms    -- the last form
     RUNNING 6 +230ms  -- around the final step commit
     CLOSED   +0ms     -- after the run was closed
     double kill       -- kill at 2, survivor killed again at 4, third finishes

   After each, a SURVIVOR process resumes. Then check, from the store:
     * every index 0..6 has exactly one terminal :ok receipt
     * no index was skipped
     * agent/counter == 7 (one committed increment per step)
     * agent/log has 7 lines
     * the run is closed
   and count how many evals actually ran across all processes."
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [flow.driver :as driver]
            [flow.store :as store]))

(def n 7)

(defn spawn [path me]
  (.start (doto (ProcessBuilder. ^java.util.List
                                 [(str (System/getProperty "java.home") "/bin/java")
                                  "-cp" (System/getProperty "java.class.path")
                                  "--enable-native-access=ALL-UNNAMED"
                                  "--sun-misc-unsafe-memory-access=allow" "-Xmx512m"
                                  "clojure.main" "-m" "attack.a6-child" path me])
          (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD))))

(defn run-child
  "Run a claimant. kill-on = nil to let it finish, or [marker delay-ms].
   Returns {:ran [indices announced] :killed? bool :exit n}."
  [path me kill-on]
  (let [proc (spawn path me)
        rdr (io/reader (.getInputStream proc))
        ran (volatile! [])
        killed (volatile! false)]
    (loop []
      (when-let [line (.readLine rdr)]
        (when (.startsWith line "RUNNING ")
          (vswap! ran conj (parse-long (subs line 8))))
        (if (and kill-on (= line (first kill-on)))
          (do (Thread/sleep (long (second kill-on)))
              (.destroyForcibly proc) (.waitFor proc) (vreset! killed true))
          (recur))))
    (.waitFor proc)
    (Thread/sleep 150)
    {:ran @ran :killed? @killed :exit (.exitValue proc)}))

(defn inspect [path]
  (let [conn (store/reopen! path)
        db (d/db conn)
        run (d/q '[:find ?r . :where [?r :run/id "run/k1"]] db)
        receipts (sort (d/q '[:find ?i ?o :in $ ?r :where
                              [?e :seon.eval/run ?r] [?e :seon.eval/index ?i]
                              [?e :seon.eval/outcome ?o]] db run))
        res (driver/resume db run)
        counter (:agent/counter (d/pull db [:agent/counter] [:agent/id "k1"]))
        logs (:agent/log (d/pull db [:agent/log] [:agent/id "k1"]))
        r (dissoc (d/pull db [:run/open? :run/epoch :run/claimant] run) :db/id)]
    (d/release conn)
    {:receipts receipts :resume res :counter counter :logs (count (or logs []))
     :run r}))

(defn -main [base & _]
  (println "=== A6: SIGKILL sweep across a 7-step turn, real second process resumes ===")
  (doseq [[label kill-on] [["RUNNING 0 +0ms" ["RUNNING 0" 0]]
                           ["RUNNING 3 +0ms" ["RUNNING 3" 0]]
                           ["RUNNING 3 +230ms (in the step commit)" ["RUNNING 3" 230]]
                           ["RUNNING 6 +0ms (last form)" ["RUNNING 6" 0]]
                           ["RUNNING 6 +230ms (final step commit)" ["RUNNING 6" 230]]
                           ["CLOSED +0ms (after the run closed)" ["CLOSED" 0]]]]
    (let [path (str base "-" (abs (hash label)))
          _ (when (.exists (io/file path)) (run! io/delete-file (reverse (file-seq (io/file path)))))
          a (run-child path "A" kill-on)
          mid (inspect path)
          b (run-child path "SURVIVOR" nil)
          fin (inspect path)
          total-evals (+ (count (:ran a)) (count (:ran b)))
          idx (into (sorted-set) (map first) (:receipts fin))
          all-ok (every? #(= :ok (second %)) (:receipts fin))]
      (println (str "\n--- kill at " label " ---"))
      (println "  A announced:        " (pr-str (:ran a)) " exit" (:exit a))
      (println "  mid-crash resume:   " (pr-str (select-keys (:resume mid) [:total :next-index :in-flight])))
      (println "  mid-crash receipts: " (pr-str (:receipts mid)))
      (println "  SURVIVOR announced: " (pr-str (:ran b)))
      (println "  final receipts:     " (pr-str (:receipts fin)))
      (println "  final run:          " (pr-str (:run fin)))
      (println (format "  indices covered: %s   all :ok: %s" (pr-str (vec idx)) all-ok))
      (println (format "  counter=%s (want %d)   log-lines=%s (want %d)   evals-run=%d (want %d)"
                       (:counter fin) n (:logs fin) n total-evals n))
      (println "  VERDICT:"
               (cond (not= idx (into (sorted-set) (range n))) "INDEX SKIPPED OR MISSING"
                     (not all-ok) "a receipt is not terminal"
                     (not= (:counter fin) n) (format "COUNTER WRONG (%s)" (:counter fin))
                     (not= (:logs fin) n) (format "LOG WRONG (%s)" (:logs fin))
                     (:run/open? (:run fin)) "RUN LEFT OPEN"
                     (> total-evals n) (format "resumed correctly, but %d evals ran for %d steps (at-least-once)" total-evals n)
                     :else "clean"))))

  ;; double kill: crash the survivor too
  (let [path (str base "-double")]
    (when (.exists (io/file path)) (run! io/delete-file (reverse (file-seq (io/file path)))))
    (println "\n--- double kill: A dies at 2, SURVIVOR dies at 4, THIRD finishes ---")
    (let [a (run-child path "A" ["RUNNING 2" 0])
          m1 (inspect path)
          b (run-child path "SURVIVOR" ["RUNNING 4" 0])
          m2 (inspect path)
          c (run-child path "THIRD" nil)
          fin (inspect path)
          total (+ (count (:ran a)) (count (:ran b)) (count (:ran c)))]
      (println "  A:" (pr-str (:ran a)) " after A:" (pr-str (select-keys (:resume m1) [:next-index :in-flight])))
      (println "  SURVIVOR:" (pr-str (:ran b)) " after SURVIVOR:" (pr-str (select-keys (:resume m2) [:next-index :in-flight])))
      (println "  THIRD:" (pr-str (:ran c)))
      (println "  final receipts:" (pr-str (:receipts fin)))
      (println (format "  counter=%s (want %d)  log-lines=%s  evals-run=%d (want %d)"
                       (:counter fin) n (:logs fin) total n))
      (println "  final run:" (pr-str (:run fin)))))
  (System/exit 0))
