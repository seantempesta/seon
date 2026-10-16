;; Scratch benchmark: Datahike + Proximum (:proximum secondary index, pinned fork 9846d3e)
;; commit cost vs N, 1-vector commit at N, unrelated commit, branch!, kNN latency/recall,
;; and the share of per-commit time spent hashing/copying the mmap generation.
;; Never touches the Seon store. Run from the checkout (own JVM, scratch dir):
;;   clojure -M:dev docs/research/agent-platform/2026-10-16/vector-db/vector_bench.clj \
;;     SCRATCH DIM NMAX CAPACITY BATCH SECONDARY-ONLY?
(require 'clojure.java.shell 'clojure.string)
(require '[datahike.api :as d] '[datahike.index.secondary :as sec]
         '[datahike.index.secondary.proximum])
(let [[s dim nmax cap batch only] *command-line-args*]
  (def scratch s) (def dim (parse-long dim)) (def nmax (parse-long nmax))
  (def cap (parse-long cap)) (def B (parse-long batch)) (def only? (= "true" only)))
(def root (str scratch "/run-" dim "-" nmax "-" (System/currentTimeMillis)))
(.mkdirs (java.io.File. root))
(def rng (java.util.Random. 42))
(defn rv [] (let [v (float-array dim)]
              (dotimes [i dim] (aset v i (float (.nextGaussian rng))))
              (let [n (Math/sqrt (areduce v i a 0.0 (+ a (* (aget v i) (aget v i)))))]
                (dotimes [i dim] (aset v i (float (/ (aget v i) n)))))
              v))
(defmacro ms [& body] `(let [t# (System/nanoTime) r# (do ~@body)] [(/ (- (System/nanoTime) t#) 1e6) r#]))
(defn files [dir] (filter #(.isFile ^java.io.File %) (file-seq (java.io.File. ^String dir))))
(defn usage [dir] {:apparent-mb (/ (reduce + 0 (map #(.length ^java.io.File %) (files dir))) 1e6)
                   :disk-mb (/ (parse-long (first (clojure.string/split (:out (clojure.java.shell/sh "du" "-sk" dir)) #"\s+"))) 1024.0)
                   :files (count (files dir))})
(def dirs {:dh (str root "/dh") :prox (str root "/prox") :mmap (str root "/prox-mmap")})
(defn snapshot [] (into {} (for [[k v] dirs] [k (usage v)])))
(defn delta [a b] (into {} (for [k (keys a)]
                             [k {:mb (- (:apparent-mb (b k)) (:apparent-mb (a k)))
                                 :disk-mb (- (:disk-mb (b k)) (:disk-mb (a k)))
                                 :files (- (:files (b k)) (:files (a k)))}])))
(def cfg {:store {:backend :file :path (:dh dirs) :id (random-uuid)}
          :schema-flexibility :read :keep-history? true})
(d/create-database cfg)
(def conn (d/connect cfg))
(d/transact conn [(cond-> {:db/ident :doc/vec :db/cardinality :db.cardinality/one}
                    only? (assoc :db.secondary/only true))
                  {:db/ident :doc/n :db/cardinality :db.cardinality/one}])
(println {:baseline-commit-ms-without-index
          (vec (for [i (range 5)] (first (ms (d/transact conn [{:doc/n (- i)}])))))})
(d/transact conn [{:db/ident :idx/vectors :db.secondary/type :proximum
                   :db.secondary/attrs [:doc/vec]
                   :db.secondary/config {:dim dim :distance :cosine :capacity cap
                                         :store-config {:backend :file :path (:prox dirs) :id (random-uuid)}}
                   :db.secondary/status :ready}])
(def stored (atom []))   ; [eid float-array] for brute-force recall
(defn add! [n]
  (let [vs (vec (repeatedly n rv))
        [t r] (ms (d/transact conn (vec (map-indexed (fn [i v] {:db/id (str "v" i) :doc/vec (vec v)}) vs))))]
    (swap! stored into (map-indexed (fn [i v] [(get (:tempids r) (str "v" i)) v]) vs))
    t))
(defn gen-files [] (filter #(re-find #"vectors-generation-" (.getName ^java.io.File %)) (files (:mmap dirs))))
(defn prune-generations!
  "Keep only the newest generation file (the bench's stand-in for proximum.gc/gc!)."
  []
  (doseq [f (butlast (sort-by #(.lastModified ^java.io.File %) (gen-files)))] (.delete ^java.io.File f)))
(defn sha-ms []
  (when-let [f (last (sort-by #(.lastModified ^java.io.File %) (gen-files)))]
    (let [md (java.security.MessageDigest/getInstance "SHA-256") buf (byte-array (* 1024 1024))
          [t _] (ms (with-open [in (java.io.FileInputStream. ^java.io.File f)]
                      (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur))))))]
      {:file-mb (/ (.length ^java.io.File f) 1e6) :sha256-ms t})))
(defn median [xs] (nth (sort xs) (quot (count xs) 2)))
(println {:dim dim :nmax nmax :capacity cap :batch B :secondary-only only?
          :mmap-file-mb (/ (* cap dim 4.0) 1e6)})
(def n (atom 0))
(def checkpoints (set (filter #(<= % nmax) [1000 10000 100000])))
(def batch-times (atom []))
(def loaded (atom 0))
(while (< @loaded nmax)
  (let [t (add! B)]
    (swap! n + B) (swap! batch-times conj t) (swap! loaded + B)
    (prune-generations!)
    (when (checkpoints @loaded)
      (let [before (snapshot)
            one (vec (for [_ (range 3)] (first (ms (d/transact conn [{:doc/vec (vec (rv))}])))))
            mid (snapshot)
            unrelated (vec (for [i (range 3)] (first (ms (d/transact conn [{:doc/n i}])))))
            after (snapshot)]
        (swap! n + 3)
        (println {:N @n :batch-commit-ms (median (take-last 5 @batch-times))
                  :one-vector-commit-ms one :one-vector-commit-delta (delta before mid)
                  :unrelated-commit-ms unrelated :unrelated-delta (delta mid after)
                  :generation-sha (sha-ms) :usage after})
        (prune-generations!)
        (flush)))))
;; kNN latency and recall@10 against brute force over the proximum index itself
(let [idx (get-in @conn [:secondary-indices :idx/vectors])
      qs (vec (repeatedly 100 rv))
      _ (doseq [q (take 10 qs)] (sec/-search idx {:vector q :k 10} nil))
      [t res] (ms (mapv #(sec/-slice-ordered idx {:vector % :k 10} nil nil :asc 10) qs))
      dot (fn [^floats a ^floats b] (areduce a i acc 0.0 (+ acc (* (aget a i) (aget b i)))))
      exact (fn [q] (set (map first (take 10 (sort-by (fn [[_ v]] (- (dot q v))) @stored)))))
      recall (/ (reduce + (map (fn [q r] (count (filter (exact q) (map :entity-id r)))) qs res)) 1000.0)]
  (println {:knn-k10-ms-per-query (/ t 100) :recall-at-10 recall :n-vectors (count @stored)}))
(let [[t _] (ms (d/branch! conn :db :exp))
      before (snapshot)]
  (println {:branch-ms t})
  (let [bconn (d/connect (assoc cfg :branch :exp))
        [t2 _] (ms (d/transact bconn [{:doc/vec (vec (rv))}]))]
    (println {:first-branch-commit-ms t2 :delta (delta before (snapshot))})
    (d/release bconn)))
(println {:final (snapshot)})
(shutdown-agents) (System/exit 0)
