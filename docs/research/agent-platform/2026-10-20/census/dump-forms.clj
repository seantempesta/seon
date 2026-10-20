;; The three JVM-mode reads run on default as agent "root" (mcp eval_clj, mode jvm, cluster "default"), each bounded and read-only.
;; They write EDN under the gitignored tmp/census/ for analyze.clj. Reproduced here for the record; run in a Seon JVM REPL.
(require '[datahike.api :as d])

;; 1. every function entity (7,716 on default; 5,555 in src/script, the rest in test/)
(let [out "/Users/sean/src/seon/tmp/census/"
      db (d/db (seon.cluster.boot/connection "default"))
      pat [:seon.fn/sym :seon.fn/ns :seon.fn/private? :seon.fn/host-bound? :seon.fn/defined-by :seon.fn/macro? :seon.fn/spec :seon.fn/malformed-spec :seon.fn/calls :seon.fn/doc :seon.fn/source :seon.fn/workload :seon.fn/constant? {:seon.fn/file [:seon.fn.file/relative-path]}]
      ids (vec (d/q '[:find [?e ...] :where [?e :seon.fn/sym]] db))
      row (fn [m] (let [src (:seon.fn/source m)]
                    (-> m (dissoc :seon.fn/source :seon.fn/doc)
                        (assoc :lines (when src (inc (count (filter #{\newline} src))))
                               :doc? (boolean (:seon.fn/doc m))
                               :file (get-in m [:seon.fn/file :seon.fn.file/relative-path]))
                        (dissoc :seon.fn/file :db/id))))]
  (with-open [w (clojure.java.io/writer (str out "raw-fns.edn"))]
    (binding [*out* w *print-length* nil *print-level* nil]
      (doseq [b (partition-all 400 ids) m (d/pull-many db pat b)] (prn (row m))))))

;; 2. every test entity (3,471) and the stored member runs (3)
(let [out "/Users/sean/src/seon/tmp/census/"
      db (d/db (seon.cluster.boot/connection "default"))
      pat [:seon.test/sym :seon.test/long :seon.test/long-ms :seon.test/platform :seon.test/check-time-limit-ms :seon.test/usage :seon.test/fixture :seon.fn/calls :seon.test/source {:seon.fn/file [:seon.fn.file/relative-path]}]
      ids (vec (d/q '[:find [?e ...] :where [?e :seon.test/sym]] db))
      row (fn [m] (let [src (:seon.test/source m)]
                    (-> m (dissoc :seon.test/source :db/id)
                        (assoc :lines (when src (inc (count (filter #{\newline} src))))
                               :asserts (when src (count (re-seq #"\((?:is|are|thrown\?|thrown-with-msg\?)[\s\)]" src)))
                               :file (get-in m [:seon.fn/file :seon.fn.file/relative-path]))
                        (dissoc :seon.fn/file))))
      runs (d/q '[:find (pull ?m [:seon.test.member/symbol :seon.test.member/pass-count :seon.test.member/fail-count :seon.test.member/error-count :seon.test.member/run-ms :seon.test.member/calls :seon.test.member/alloc-bytes]) :where [?m :seon.test.member/symbol]] db)]
  (with-open [w (clojure.java.io/writer (str out "raw-tests.edn"))]
    (binding [*out* w *print-length* nil *print-level* nil]
      (doseq [b (partition-all 400 ids) m (d/pull-many db pat b)] (prn (row m)))))
  (spit (str out "raw-runs.edn") (pr-str (mapv first runs))))

;; 3. emptiness checks quoted in static.md: counts of :seon.test/reach, :seon.test/run, :seon.test.run/id, and the same in (d/history db)
