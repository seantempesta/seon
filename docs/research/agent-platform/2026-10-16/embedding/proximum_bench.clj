;; Scratch benchmark: Datahike + Proximum secondary index, commit cost vs N.
;; Run from /Users/sean/src/seon (read-only use of its classpath, its own JVM, scratch store):
;;   clojure -M:dev docs/research/agent-platform/2026-10-16/embedding/proximum_bench.clj SCRATCH_DIR DIM CAPACITY
(require 'clojure.java.shell 'clojure.string)
(require '[datahike.api :as d] '[datahike.versioning :as dv]
         '[datahike.index.secondary :as sec] '[datahike.index.entity-set :as es]
         '[datahike.index.secondary.proximum])
(def scratch (first *command-line-args*))
(def dim (Long/parseLong (or (second *command-line-args*) "1024")))
(def cap (Long/parseLong (or (nth *command-line-args* 2 nil) "20000")))
(def root (str scratch "/run-" (System/currentTimeMillis)))
(.mkdirs (java.io.File. root))
(defn rv [] (let [v (float-array dim)] (dotimes [i dim] (aset v i (float (- (rand) 0.5)))) v))
(defmacro ms [& body] `(let [t# (System/nanoTime) r# (do ~@body)] [(/ (- (System/nanoTime) t#) 1e6) r#]))
(def cfg {:store {:backend :file :path (str root "/dh") :id (random-uuid)} :schema-flexibility :read :keep-history? false})
(d/create-database cfg)
(def conn (d/connect cfg))
(d/transact conn [{:db/ident :idx/vectors :db.secondary/type :proximum
                   :db.secondary/attrs [:doc/vec]
                   :db.secondary/config {:dim dim :distance :cosine :capacity cap
                                         :store-config {:backend :file :path (str root "/prox") :id (random-uuid)}
                                         :mmap-dir (str root "/mmap")}
                   :db.secondary/status :ready}])
(Thread/sleep 1500)
(def B 250)
(def n (atom 0))
(println "dim" dim "batch" B)
(println "capacity" cap "mmap file MB" (/ (* cap dim 4) 1e6))
(println "N-before  tx-ms(250 vec)  mmap-dir-apparent-MB  prox-store-MB  mmap-dir-disk-MB")
(defn du [dir] (/ (reduce + (map #(.length %) (filter #(.isFile %) (file-seq (java.io.File. dir))))) 1e6))
(defn disk [dir] (/ (Long/parseLong (first (clojure.string/split (:out (clojure.java.shell/sh "du" "-sk" dir)) #"\s+"))) 1024.0))
(doseq [target (filter #(<= % (quot cap 2)) [250 1000 2500 5000 10000 20000])]
  (while (< @n target)
    (let [batch (vec (for [_ (range B)] {:doc/vec (vec (rv))}))
          [t _] (ms (d/transact conn batch))]
      (swap! n + B)
      (when (= @n target)
        (println (format "%7d  %7.0f  %7.1f  %7.1f  %7.1f" (- @n B) t (du (str root "/mmap")) (du (str root "/prox")) (disk (str root "/mmap")))))))
  (flush))
;; query latency
(let [idx (get-in @conn [:secondary-indices :idx/vectors]) qs (repeatedly 200 rv)
      _ (doseq [q (take 20 qs)] (sec/-search idx {:vector q :k 10} nil))
      [t _] (ms (doseq [q qs] (sec/-search idx {:vector q :k 10} nil)))]
  (println (format "KNN k=10 over %d vectors: %.2f ms/query" @n (/ t 200))))
;; branch cost
(let [[t _] (ms (dv/branch! conn :db :exp))] (println (format "branch! %.0f ms" t)))
(let [bconn (d/connect (assoc cfg :branch :exp))
      [t _] (ms (d/transact bconn [{:doc/vec (vec (rv))}]))]
  (println (format "first tx on branch: %.0f ms" t)))
(let [[t _] (ms (d/transact conn [{:doc/vec (vec (rv))}]))] (println (format "1-vector tx on main at N=%d: %.0f ms" @n t)))
(println "dirs MB mmap" (du (str root "/mmap")) "prox" (du (str root "/prox")) "dh" (du (str root "/dh")))
(shutdown-agents) (System/exit 0)
