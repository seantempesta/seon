;; Phase 1 probe: embed declaration texts, store them as Proximum vector datoms, kNN through Datalog.
;; Run: clojure -M:dev tmp/embeddings/probe1.clj
(require '[clojure.data.json :as json] '[clojure.edn :as edn] '[datahike.api :as d]
         '[datahike.versioning :as dv] '[datahike.index.secondary.proximum])
(import '(java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers HttpRequest$BodyPublishers)
        '(java.net URI) '(java.nio ByteBuffer ByteOrder) '(java.util Base64))

(defmacro ms [& body] `(let [t# (System/nanoTime) r# (do ~@body)] [(/ (- (System/nanoTime) t#) 1e6) r#]))
(def client (HttpClient/newHttpClient))
(defn embed [texts task dim]
  (let [req (-> (HttpRequest/newBuilder (URI. "http://127.0.0.1:8701/v1/embed"))
                (.header "content-type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString
                        (json/write-str {:texts texts :task task :dim dim :encoding "f32"})))
                .build)
        body (json/read-str (.body (.send client req (HttpResponse$BodyHandlers/ofString))) :key-fn keyword)
        fb (.asFloatBuffer (doto (ByteBuffer/wrap (.decode (Base64/getDecoder) ^String (:vectors body)))
                             (.order ByteOrder/LITTLE_ENDIAN)))]
    {:ms (:ms body) :tokens (:input_tokens body)
     :vectors (vec (for [i (range (:n body))]
                     (let [a (float-array dim)] (.get (.position fb (int (* i dim))) a) (vec a))))}))

(def dim 256)
(def decls (edn/read-string (slurp "tmp/embeddings/decls.edn")))
(def doc-text (fn [{:keys [sym text]}] (str "title: " sym " | text: " text)))

;; 1. one embedding round trip
(let [[t r] (ms (embed ["seon.error/nearest-names"] "code" dim))]
  (println "one query embedding, wall ms" t "server ms" (:ms r) "dim" (count (first (:vectors r)))))
(dotimes [_ 5] (embed ["seon.db/transact!"] "code" dim))
(let [ts (for [q ["seon.db/transcat!" "write facts to the database" "seon.error/neerest" "pull an entity"
                  "run the tests reaching a definition"]]
           (first (ms (embed [q] "code" dim))))]
  (println "warm single query ms" (vec ts)))

;; 2. whole program backfill, batched
(def batch-size 256)
(let [[t vs] (ms (into [] (mapcat (fn [part] (:vectors (embed (mapv doc-text part) nil dim))))
                       (partition-all batch-size decls)))]
  (def all-vectors vs)
  (println "backfill" (count decls) "declarations, batch" batch-size ":" t "ms"))

;; 3. Datahike + Proximum on a scratch store, schema on write as Seon declares it
(def root (str (System/getProperty "user.dir") "/tmp/embeddings/store-" (System/currentTimeMillis)))
(def cfg {:store {:backend :file :path (str root "/dh") :id (random-uuid)}
          :schema-flexibility :write :keep-history? true})
(d/create-database cfg)
(def conn (d/connect cfg))
(d/transact conn [{:db/ident :decl/sym :db/valueType :db.type/symbol :db/cardinality :db.cardinality/one}
                  {:db/ident :decl/digest :db/valueType :db.type/string :db/cardinality :db.cardinality/one
                   :db/unique :db.unique/identity}
                  {:db/ident :decl/vec :db/valueType :db.type/tuple :db/cardinality :db.cardinality/one
                   :db.secondary/only true}])
(let [[t _] (ms (d/transact conn [{:db/ident :idx/decl-vectors :db.secondary/type :proximum
                                   :db.secondary/attrs [:decl/vec]
                                   :db.secondary/config {:dim dim :distance :cosine :capacity 16384
                                                         :store-config {:backend :file :path (str root "/prox") :id (random-uuid)}
                                                         :mmap-dir (str root "/mmap")}
                                   :db.secondary/status :ready}]))]
  (println "declare index ms" t))
(def n20 20)
(let [rows (map (fn [{:keys [sym digest]} v] {:decl/sym sym :decl/digest digest :decl/vec v}) (take n20 decls) all-vectors)
      [t _] (ms (d/transact conn (vec rows)))]
  (println "transact 20 vectors ms" t))
(let [[t _] (ms (d/transact conn [{:decl/sym 'x/one :decl/digest "one" :decl/vec (nth all-vectors 30)}]))]
  (println "transact 1 vector ms" t))
(let [[t _] (ms (d/transact conn [{:decl/sym 'x/novec :decl/digest "novec"}]))]
  (println "transact no vector ms" t))
(let [[t _] (ms (doseq [part (partition-all 1000 (map vector (drop n20 decls) (drop n20 all-vectors)))]
                  (d/transact conn (mapv (fn [[{:keys [sym digest]} v]] {:decl/sym sym :decl/digest digest :decl/vec v}) part))))]
  (println "transact rest" (- (count decls) n20) "in 1000s ms" t))
(let [[t _] (ms (d/transact conn [{:decl/sym 'x/two :decl/digest "two" :decl/vec (nth all-vectors 31)}]))]
  (println "transact 1 vector at N=all ms" t))
(defn nearest [db q k]
  (->> (d/q '[:find ?s ?d :in $ ?q ?k
              :where [(datahike.index.secondary.proximum/nearest :idx/decl-vectors ?q ?k) [[?e ?d]]]
              [?e :decl/sym ?s]] db q k)
       (sort-by second) (mapv (fn [[s d]] [s (Math/round (* 1000 (- 1 d)))]))))
(doseq [q ["seon.db/transcat!" "seon.error/neerest-name" "my.program/serch" "write facts to the database"
           "find the declarations whose names are nearest a misspelled symbol" "run the tests that reach a definition"
           "pull an entity by its id"]]
  (let [[te r] (ms (first (:vectors (embed [q] "code" dim))))
        db @conn
        [tq hits] (ms (nearest db r 8))]
    (println (format "%-70s embed %.1f ms knn %.1f ms" q te tq))
    (println "   " hits)))
(let [db @conn q (first (:vectors (embed ["seon.db/transcat!"] "code" dim)))
      _ (dotimes [_ 50] (nearest db q 8))
      [t _] (ms (dotimes [_ 200] (nearest db q 8)))]
  (println "warm knn+datalog per query ms" (/ t 200)))
(let [[t _] (ms (dv/branch! conn :db :exp))] (println "branch! ms" t))
(println "store MB" (/ (reduce + (map #(.length %) (filter #(.isFile %) (file-seq (java.io.File. root))))) 1e6))
(shutdown-agents) (System/exit 0)
