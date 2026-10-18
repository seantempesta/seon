;; Storage and time per one-vector commit on a Proximum index at Seon's size (dim 256, capacity 16384, 7.5k vectors).
(require '[datahike.api :as d] 'datahike.index.secondary.proximum 'clojure.java.shell)
(defmacro ms [& body] `(let [t# (System/nanoTime) r# (do ~@body)] [(/ (- (System/nanoTime) t#) 1e6) r#]))
(def dim 256)
(defn rv [] (let [v (vec (repeatedly dim #(- (rand) 0.5))) n (Math/sqrt (reduce + (map * v v)))] (mapv #(float (/ % n)) v)))
(def root (str (System/getProperty "user.dir") "/tmp/embeddings/store4-" (System/currentTimeMillis)))
(def cfg {:store {:backend :file :path (str root "/dh") :id (random-uuid)} :schema-flexibility :write :keep-history? true})
(d/create-database cfg)
(def conn (d/connect cfg))
(d/transact conn [{:db/ident :decl/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                  {:db/ident :decl/vec :db/valueType :db.type/tuple :db/cardinality :db.cardinality/one :db.secondary/only true}])
(d/transact conn [{:db/ident :idx/v :db.secondary/type :proximum :db.secondary/attrs [:decl/vec]
                   :db.secondary/config {:dim dim :distance :cosine :capacity 16384
                                         :store-config {:backend :file :path (str root "/prox") :id (random-uuid)}
                                         :mmap-dir (str root "/mmap")}
                   :db.secondary/status :ready}])
(doseq [part (partition-all 1000 (range 7500))] (d/transact conn (mapv (fn [i] {:decl/name (str "n" i) :decl/vec (rv)}) part)))
(defn disk [dir] (/ (Long/parseLong (first (clojure.string/split (:out (clojure.java.shell/sh "du" "-sk" dir)) #"\s+"))) 1024.0))
(println "after 7500: MB prox" (disk (str root "/prox")) "mmap" (disk (str root "/mmap")) "dh" (disk (str root "/dh")))
(let [ts (vec (for [i (range 20)] (first (ms (d/transact conn [{:decl/name (str "m" i) :decl/vec (rv)}])))))]
  (println "20 one-vector commits ms" (mapv #(Math/round %) ts)))
(println "after +20: MB prox" (disk (str root "/prox")) "mmap" (disk (str root "/mmap")) "dh" (disk (str root "/dh")))
(let [ts (vec (for [i (range 10)] (first (ms (d/transact conn [{:decl/name (str "plain" i)}])))))]
  (println "10 no-vector commits ms" (mapv #(Math/round %) ts)))
(let [[t _] (ms (d/transact conn (mapv (fn [i] {:decl/name (str "b" i) :decl/vec (rv)}) (range 50))))] (println "one 50-vector commit ms" t))
(println "after: MB prox" (disk (str root "/prox")) "mmap" (disk (str root "/mmap")))
(shutdown-agents) (System/exit 0)
