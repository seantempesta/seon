;;; p6 — the decisive comparison. If the session image lives in DATAHIKE
;;; FACTS, do we already get the structural sharing p3 measured? Datahike's
;;; indexes ARE persistent-sorted-sets over konserve
;;; (datahike/src/datahike/index/persistent_set.cljc:388-455), so a one-datom
;;; transact should rewrite only the touched path — no second tree needed.
;;; Run: clojure -M:dev -i tmp/durable-env/p6_datahike_sharing.clj
(require '[datahike.api :as d]
         '[clojure.java.io :as io])

(defn line [& xs] (println (apply str xs)))
(defn ms [f] (let [t (System/nanoTime) v (f)] [(/ (- (System/nanoTime) t) 1e6) v]))

(def root "tmp/durable-env/store-p6")
(defn rm-rf [p] (let [f (io/file p)]
                  (when (.exists f) (doseq [c (reverse (file-seq f))] (.delete ^java.io.File c)))))
(rm-rf root)
(defn du [] (reduce + 0 (map #(.length ^java.io.File %)
                             (filter #(.isFile ^java.io.File %) (file-seq (io/file root))))))
(defn nfiles [] (count (filter #(.isFile ^java.io.File %) (file-seq (io/file root)))))

(def cfg {:store {:backend :file :path root :id #uuid "8a1f0c22-0000-4000-8000-00000000d0e6"}
          :keep-history? true
          :schema-flexibility :write
          :attribute-refs? false})
(d/delete-database cfg)
(d/create-database cfg)
(def conn (d/connect cfg))

(d/transact conn [{:db/ident :def/name  :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                  {:db/ident :def/value :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one}])

(line "\n=== A. seed 2 000 session-image entries (one entity per name) ===")
(let [b0 (du) f0 (nfiles)
      [t _] (ms #(d/transact conn (vec (for [i (range 2000)]
                                         {:def/name (str "my.agent/name" i)
                                          :def/value (pr-str {:i i :v (vec (range 5))})}))))]
  (line (format "  seed transact: %.1f ms   store=%d bytes, %d files" t (- (du) b0) (- (nfiles) f0))))

(line "\n=== B. ONE redefinition = one datom. How much store growth? ===")
(let [runs (doall
            (for [i (range 12)]
              (let [b (du) f (nfiles)
                    [t _] (ms #(d/transact conn [{:def/name "my.agent/name0"
                                                  :def/value (pr-str {:redef i})}]))]
                [(Math/round ^double t) (- (du) b) (- (nfiles) f)])))]
  (line "  per-redefinition [ms bytes newfiles]: " (pr-str (vec runs)))
  (line (format "  MEDIAN: %.0f ms, %d bytes, %d new files"
                (double (nth (sort (map first runs)) 6))
                (nth (sort (map second runs)) 6)
                (nth (sort (map #(nth % 2) runs)) 6))))

(line "\n=== C. a BIG value inline in a datom vs a blob ===")
(let [big (pr-str (vec (range 100000)))]
  (line (format "  the edn string is %d chars" (count big)))
  (let [b (du)
        [t _] (ms #(d/transact conn [{:def/name "my.agent/big" :def/value big}]))
        g1 (- (du) b)]
    (line (format "  transact it INLINE: %.1f ms, store grew %d bytes (%.1fx the payload)"
                  t g1 (/ (double g1) (count big)))))
  (let [b (du)
        [t _] (ms #(d/transact conn [{:def/name "my.agent/name1" :def/value "x"}]))]
    (line (format "  a LATER unrelated one-datom transact: %.1f ms, store grew %d bytes"
                  t (- (du) b)))))

(line "\n=== D. branch fork cost (the grader's branch path) ===")
(let [b (du)
      [t _] (ms #(d/branch! conn :db :probe-branch))]
  (line (format "  d/branch! : %.1f ms, store grew %d bytes" t (- (du) b)))
  (line (format "  branches now: %s" (pr-str (d/branches conn)))))

(d/release conn)
(System/exit 0)
