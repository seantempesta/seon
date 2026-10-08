;; Throwaway-store proof: bytes/files/ms per small commit and cold read ms,
;; uncompressed (parent) vs konserve LZ4 (64 KiB frames, konserve c73cbde).
;; Run: clojure -Sdeps '{:deps {org.replikativ/konserve {:local/root "reference-code/konserve"}}}' -M tmp/store-writes/lz4_proof.clj
(require '[datahike.api :as d] '[clojure.java.io :as io])
;; probe.clj's workload without its seon.cluster.store-bound `measure`
(load-string (let [src (slurp "tmp/store-writes/probe.clj")
                   i (.indexOf src "(defn measure")
                   j (.indexOf src "(defn delete-store!")]
               (str (subs src 0 i) (subs src j))))
(alias 'p 'tmp.store-probe)
(in-ns 'user)
(def root "/Users/sean/src/seon/tmp/store-writes")

(defn cfg [dir compress?]
  (let [path (.getCanonicalPath (io/file dir))]
    {:store {:backend :file :path path
             :id (java.util.UUID/nameUUIDFromBytes (.getBytes path "UTF-8"))
             :config (cond-> {:defer-immutable-sync? true}
                       compress? (assoc :compressor {:type :lz4}))}
     :writer {:backend :self} :keep-history? true :fuse-index-roots? true
     :index-config {:branching-factor 128 :diff-buf-size 32}
     :schema-flexibility :write}))

(defn header-compressors [dir]
  (frequencies (for [^java.io.File f (.listFiles (io/file dir)) :when (.endsWith (.getName f) ".ksv")]
                 (with-open [in (java.io.FileInputStream. f)] (.read in) (.read in) (.read in)))))

(defn ms-since [t] (/ (- (System/nanoTime) t) 1e6))

(defn build [label compress? base commits]
  (let [dir (str root "/probe-" label)
        c (cfg dir compress?)
        _ (d/create-database c)
        conn (d/connect c)
        _ (d/transact conn p/schema)
        _ (d/transact conn (vec (for [a (range 8)] {:agent/id (str "agent-" a) :agent/count 0})))
        _ (doseq [chunk (partition-all 500 (range base))]
            (d/transact conn (mapv #(p/evaluation % (str "agent-" (mod % 8))) chunk)))
        _ (doseq [i (range 10)] (p/small-commit! conn (+ base i)))
        b (p/dir-stats dir)
        t1 (System/nanoTime)
        _ (doseq [i (range commits)] (p/small-commit! conn (+ base 10 i)))
        commit-ms (/ (ms-since t1) commits)
        a (p/dir-stats dir)
        as-of-t (- (:max-tx @conn) 5)
        _ (d/release conn)]
    {:label label :base b :as-of-t as-of-t :headers (header-compressors dir)
     :per-commit {:files (/ (- (:files a) (:files b)) (double commits))
                  :bytes (long (/ (- (:bytes a) (:bytes b)) commits))
                  :disk (long (/ (- (:disk a) (:disk b)) commits))
                  :ms commit-ms}}))

(defn cold-read [label compress? as-of-t]
  (let [t (System/nanoTime)
        conn (d/connect (cfg (str root "/probe-" label) compress?))
        n (+ (count (d/datoms @conn :eavt)) (count (d/datoms @conn :aevt))
             (count (d/datoms (d/history @conn) :eavt)))
        scan-ms (ms-since t)
        answers [(d/q '[:find (count ?e) . :where [?e :ev/status :error]] @conn)
                 (d/q '[:find (count ?e) . :where [?e :ev/id _]] (d/as-of @conn as-of-t))
                 (count (d/datoms (d/history @conn) :aevt :agent/count))]]
    (d/release conn)
    {:datoms-scanned n :scan-ms scan-ms :answers answers}))

(let [runs [["parent" false] ["lz4" true]]
      built (into {} (for [[l c] runs] [l (build l c 3000 30)]))
      reads (for [_ (range 3) [l c] runs] [l (:scan-ms (cold-read l c (get-in built [l :as-of-t])))])]
  (prn {:built built
        :answers (into {} (for [[l c] runs] [l (:answers (cold-read l c (get-in built [l :as-of-t])))]))
        :cold-scan-ms (reduce (fn [m [l ms]] (update m l (fnil conj []) (Math/round (double ms)))) {} reads)})
  (doseq [[l _] runs] (p/delete-store! l))
  (shutdown-agents)
  (System/exit 0))
