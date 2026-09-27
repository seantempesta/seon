;; Replay of boot's `:seon.source/current` publication on a throwaway branch of a
;; `current-src` commit, in default's JVM (lane boot-source-perf-opus, 2026-09-27).
;;
;; Inputs (System properties, so no Var is interned):
;;   seon.boot-perf.P     base commit uuid (the published head the boot started from)
;;   seon.boot-perf.mode  "incr" = analyze the changed files only; "full" = the :all
;;                        pass boot runs when the analyzer seal changes
;;   seon.boot-perf.only  optional comma list overriding the changed paths
;; Run: (eval (read-string (slurp "tmp/boot-source-perf/replay.clj"))), then deref
;; (.get (System/getProperties) (str "seon.boot-perf.run-" mode)). The branch is
;; retired in `finally`. Samples: all RUNNABLE threads with seon frames, 5 ms,
;; during populate only.
;;
;; Measured, warm, armed (cold unarmed boot is ~1.5x):
;;  cd4ca3d39 set (17 paths, base 6ab98892):  incr analyze 2.9 s + populate 9.1 s;
;;    full analyze 13.9-16.5 s (9,794 rows; clj-kondo ~40%) + populate 6.4 s.
;;  e999fdd1b set (18 paths, base 6ab99276):  incr analyze 1.9 s + populate 3.8 s;
;;    full analyze 13.9 s (9,786 rows) + populate 5.2 s.
;;  One file (src/seon/test.clj): analyze 0.54 s, populate 1.34 s
;;    (report-arity-comparison/arity-base ~0.5 s of it).
;;  Both sets: the full and incr transaction reports are set-equal (differing
;;    only in :db/txInstant) and fn digests equal: the :all pass changed nothing.
(let [mode (System/getProperty "seon.boot-perf.mode")] (.put (System/getProperties) (str "seon.boot-perf.run-" mode) (future (let [inst (get @seon.operator.runtime/running-instances "default")
      store (:seon.store/store inst)
      P (java.util.UUID/fromString (System/getProperty "seon.boot-perf.P"))
      T (keyword (str "boot-perf-replay-" (System/currentTimeMillis)))
      now #(System/nanoTime)
      ms (fn [t] (/ (Math/round (/ (- (System/nanoTime) t) 1e4)) 100.0))
      target (Thread/currentThread)
      samples (java.util.concurrent.ConcurrentHashMap.)
      selfs (java.util.concurrent.ConcurrentHashMap.)
      running (atom false)
      zero (apply str (repeat 64 "0"))
      plus (reify java.util.function.BiFunction (apply [_ a b] (+ a b)))
      sampler (future (while (not (Thread/interrupted))
                        (when @running
                        (doseq [[^Thread th st] (Thread/getAllStackTraces)
                                :when (and (not= th (Thread/currentThread))
                                           (not= "WAITING" (str (.getState th))) (not= "TIMED_WAITING" (str (.getState th)))
                                           (some (fn [^StackTraceElement e] (.startsWith (.getClassName e) "seon.")) st)
                                           (not (some (fn [^StackTraceElement e] (.startsWith (.getClassName e) "seon.operator.prepl")) (take 3 st))))]
                        (let [names (distinct (keep (fn [^StackTraceElement e] (let [c (.getClassName e)] (when (and (.startsWith c "seon.") (not (.startsWith c "seon.operator")) (not (.startsWith c "seon.call_prep"))) (let [[a b] (clojure.string/split c #"\$" 3)] (str a "/" b))))) st))]
                          (doseq [n names] (.merge samples n 1 plus))
                          (when-let [^StackTraceElement top (first st)] (.merge selfs (str (.getClassName top) "." (.getMethodName top)) 1 plus)))))
                        (Thread/sleep 5)))
      t (atom {})
      result
      (try
        (let [t0 (now)
              _ (seon.fn.analyzer/ensure-dependency-analysis!)
              _ (swap! t assoc :ensure-dependency-analysis (ms t0))
              t0 (now)
              analyzer (#'seon.cluster/loaded-analyzer-digest store)
              _ (swap! t assoc :loaded-analyzer-digest (ms t0))
              roots (merge (#'seon.cluster/publication-roots) analyzer)
              directory (:seon.fn/root roots)
              requested (let [db0 (seon.cluster.source/database store P)
                              paths (seon.cluster.source/discover-paths directory seon.cluster/source-roots)
                              files (seon.cluster.source/path-digests directory paths)
                              stored (into {} (datahike.api/q '[:find ?path ?digest :where [?f :seon.fn.file/relative-path ?path] [?f :seon.fn.file/digest ?digest]] db0))]
                          (try (if-let [only (System/getProperty "seon.boot-perf.only")] (clojure.string/split only #",") (into [] (comp (distinct) (remove #(= (get files %) (get stored %)))) (sort (concat (keys files) (keys stored)))))
                               (finally (datahike.api/release-materialized-db db0))))]
          (seon.schema/call-with-projection
           (seon.schema/declaration-projection (seon.schema.edn/packaged-forms))
           (fn []
             (let [t0 (now)
                   [observed captured] (seon.cluster.source/capture-paths directory (if (= mode "full") (seon.cluster.source/discover-paths directory (:seon.source/roots roots)) requested))
                   database (seon.cluster.source/database store P)
                   stored (into {} (datahike.api/q '[:find ?path ?digest :where [?file :seon.fn.file/relative-path ?path] [?file :seon.fn.file/digest ?digest]] database))
                   inputs (merge (apply dissoc stored requested) observed)
                   changed (if (= mode "full") (into (set requested) (seon.cluster.source/discover-paths directory (:seon.source/roots roots))) (set requested))
                   _ (swap! t assoc :capture+read (ms t0) :requested requested)
                   t0 (now)
                   analyzed (seon.fn/analyze-entities {:seon.fn/root directory :seon.fn/roots (:seon.fn/roots roots) :seon.fn/changed-paths changed :seon.fn.analyzer/sources captured :seon.source/relative-file-digests inputs :seon.source/previous-database database})
                   _ (swap! t assoc :analyze-entities (ms t0) :rows (count analyzed))
                   t0 (now)
                   sealed (#'seon.cluster/selected-analyzer-digest database changed analyzed inputs (:seon.cluster/loaded-files roots {}))
                   _ (swap! t assoc :selected-analyzer-digest (ms t0))
                   t0 (now)
                   _ (seon.cluster.registry/branch! {:seon.store/store store :seon.cluster.registry/from P :seon.store/branch T})
                   conn (seon.cluster.store/open-branch! store T)
                   _ (swap! t assoc :branch+open (ms t0))]
               (try
                 (let [t0 (now)
                       _ (reset! running true)
                       pop (seon.db/call-with-custody {:seon.db/connection conn}
                             (fn [] (seon.cluster/populate-source!
                                     {:seon.db/connection conn :seon.source/digest zero
                                      :seon.program/rows analyzed :seon.fn/root directory :seon.fn/roots (:seon.fn/roots roots)
                                      :seon.source/relative-file-digests inputs :seon.source/previous-database database
                                      :seon.fn/changed-paths changed :seon.source/change-classes #{:program :schema-resource}})))
                       _ (reset! running false)
                       _ (swap! t assoc :populate-source (ms t0))
                       t0 (now)
                       issues (#'seon.cluster.source/index-issues! conn zero directory [])
                       tasks (#'seon.cluster.source/index-tasks! conn zero directory database)
                       _ (swap! t assoc :index-issues+tasks (ms t0))
                       after (datahike.api/db conn)]
                   {:pop-keys (keys pop) :adopt (count (:seon.reconcile/adopt-identities pop)) :err (some-> (:seon.error/message pop) (subs 0 300))
                    :head-commit (seon.db/commit-id after)
                    :fn-digests (hash (sort (datahike.api/q '[:find ?s ?d :where [?f :seon.fn/sym ?s] [?f :seon.program/definition-digest ?d]] after)))
                    :datoms (count (datahike.api/datoms after :eavt))
                    :tx (let [rep (:seon.db/transaction-report pop) tx (:tx-data rep)
                              ident (fn [e] (let [d (if (:added (first tx)) (:db-after rep) (:db-after rep))
                                                  m (datahike.api/pull after [:seon.fn/sym :seon.test/sym :seon.ns/name :seon.fn.file/relative-path :seon.schema/key :seon.lint/id] e)]
                                              (or (first (vals (dissoc m :db/id))) e)))]
                          (into #{} (map (fn [x] [(ident (:e x)) (:a x) (let [v (:v x)] (if (number? v) :n v)) (:added x)])) tx))})
                 (finally (datahike.api/release conn)
                          (seon.cluster.registry/retire-branch! {:seon.store/store store :seon.store/branch T})
                          (datahike.api/release-materialized-db database)))))))
        (catch Throwable e {:error (subs (str e) 0 (min 600 (count (str e))))})
        (finally (future-cancel sampler)))]
  {:t @t :result result
   :inclusive (take 90 (sort-by (comp - val) (into {} samples)))
   :self (take 30 (sort-by (comp - val) (into {} selfs)))}))))