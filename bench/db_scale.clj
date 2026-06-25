(ns bench.db-scale
  "PURE-datahike + Proximum-HNSW scalability benchmark. Isolated THROWAWAY file
   store (tmp/bench-store) + sibling HNSW index store (tmp/bench-store/embedding-index)
   — does NOT touch the live default cluster. SYNTHETIC random relational data +
   RANDOM (not real / no-Gemini) L2-normalized 1536-dim vectors.

   Run ONE size per JVM (clean heap measurement):
     clojure -J-Xmx24g -M:simd:fork-deps:test -i bench/db_scale.clj -e '(bench.db-scale/-main)'
   driven by env:
     BENCH_SIZE   total entity count for this run (int)
     BENCH_CAP    HNSW capacity (mmap preallocation); default ceil(size*0.4)+2000
     BENCH_FRAC   fraction of entities carrying a random embedding; default 0.30
   Appends one result row (EDN) to tmp/bench-results.edn and prints ROW."
  (:require [datahike.api :as d]
            [datahike.index.secondary :as sec]
            [datahike.index.secondary.proximum]   ; registers :proximum sec-index type
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.util Random Date]))

(def store-path "tmp/bench-store")
(def index-path "tmp/bench-store/embedding-index")  ; sibling-ish; explicit dir
(def dim 1536)
(def index-ident :seon.embed/index)

;;; --- isolated store ---------------------------------------------------------

(defn rm-rf [p]
  (let [f (io/file p)]
    (when (.exists f) (doseq [c (reverse (file-seq f))] (.delete c)))))

(defn fresh-conn []
  (rm-rf store-path)
  (rm-rf index-path)
  (let [cfg {:keep-history? false
             :schema-flexibility :write
             :index :datahike.index/persistent-set
             :store {:backend :file :path store-path
                     :id (java.util.UUID/nameUUIDFromBytes (.getBytes "db-scale-bench"))}}]
    (when (d/database-exists? cfg) (d/delete-database cfg))
    (d/create-database cfg)
    (d/connect cfg)))

(def syn-schema
  [{:db/ident :syn/id      :db/valueType :db.type/string  :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :syn/kind    :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   ;; embedding: secondary-only float vector, HNSW lives in the proximum store
   {:db/ident :seon/embedding :db/valueType :db.type/tuple :db/cardinality :db.cardinality/one
    :db.secondary/only true}
   {:db/ident :seon.embed/source-hash :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :syn.expense/amount   :db/valueType :db.type/double  :db/cardinality :db.cardinality/one}
   {:db/ident :syn.expense/category :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :syn.expense/date     :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}
   {:db/ident :syn.sub/name  :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :syn.sub/cost  :db/valueType :db.type/double :db/cardinality :db.cardinality/one}
   {:db/ident :syn.maint/task         :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :syn.maint/cadence-days :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
   {:db/ident :syn.run/distance-km :db/valueType :db.type/double :db/cardinality :db.cardinality/one}
   {:db/ident :syn.project/name        :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :syn.project/description :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :syn.task/title      :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :syn.task/project    :db/valueType :db.type/ref    :db/cardinality :db.cardinality/one}
   {:db/ident :syn.task/depends-on :db/valueType :db.type/ref    :db/cardinality :db.cardinality/many}
   {:db/ident :syn.source/title  :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :syn.source/author :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :syn.note/body   :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :syn.note/source :db/valueType :db.type/ref    :db/cardinality :db.cardinality/one}])

(defn index-def [capacity]
  {:db/ident            index-ident
   :db.secondary/type   :proximum
   :db.secondary/attrs  [:seon/embedding]
   :db.secondary/config {:dim          dim
                         :distance     :cosine
                         :capacity     capacity
                         :store-config {:backend :file :path index-path
                                        :id (java.util.UUID/nameUUIDFromBytes
                                              (.getBytes (str index-ident)))}}})

;;; --- synthetic data ---------------------------------------------------------

(def categories [:groceries :dining :transport :utilities :entertainment :health :home])
(def words (str/split "agent context render embed vector query datahike proximum cluster wire schema datom transact knn cosine index backfill retrieval semantic pod soul namespace function reduce parallel batch token budget throughput latency memory persistence corpus synthetic relational invoice receipt grocery dinner commute electric water netflix spotify gym marathon furnace filter brake oil project sprint backlog migrate refactor"
                      #" "))

(def ^Random rng (Random. 42))
(defn rand-int* [n] (.nextInt rng n))
(defn rand-nth* [v] (nth v (rand-int* (count v))))
(defn lorem [n] (str/join " " (repeatedly n #(rand-nth* words))))

(defn rand-vec
  "Random L2-normalized 1536-float vector (gaussian sample then normalize).
   No Gemini, no real embedding — purely for HNSW index/search scaling."
  []
  (let [arr (float-array dim)]
    (loop [i 0 ss 0.0]
      (if (< i dim)
        (let [g (.nextGaussian rng)]
          (aset arr i (float g))
          (recur (inc i) (+ ss (* g g))))
        (let [n (Math/sqrt ss)]
          (dotimes [i dim] (aset arr i (float (/ (aget arr i) n))))
          (vec arr))))))

(def !uid (atom 0))
(defn uid [] (str "e" (swap! !uid inc)))

(defn embed-fields []
  {:seon/embedding (rand-vec) :seon.embed/source-hash (str (rand-int* Integer/MAX_VALUE))})

(defn gen-chunk
  "tx-data vector of `n` synthetic entities across kinds, refs resolved by
   tempid strings WITHIN the chunk. ~28-30% carry a random embedding (notes +
   project descriptions), so the HNSW grows with the corpus."
  [n]
  (let [src-ids  (vec (repeatedly (max 1 (quot n 10)) uid))
        proj-ids (vec (repeatedly (max 1 (quot n 8))  uid))
        task-ids (atom [])]
    (vec
     (concat
      (for [id src-ids]
        {:db/id id :syn/id id :syn/kind :source
         :syn.source/title (lorem 4) :syn.source/author (rand-nth* words)})
      (for [id proj-ids]
        (merge {:db/id id :syn/id id :syn/kind :project
                :syn.project/name (str "Project-" id)
                :syn.project/description (lorem (+ 30 (rand-int* 200)))}
               (embed-fields)))
      (for [_ (range (- n (count src-ids) (count proj-ids)))]
        (let [id (uid) roll (rand-int* 100)]
          (cond
            (< roll 25)                       ; task → project + depends-on prior task
            (let [prior @task-ids
                  dep (when (seq prior) (rand-nth* prior))
                  m (cond-> {:db/id id :syn/id id :syn/kind :task
                             :syn.task/title (lorem 5)
                             :syn.task/project (rand-nth* proj-ids)}
                      dep (assoc :syn.task/depends-on dep))]
              (swap! task-ids conj id) m)
            (< roll 45)                       ; note → source, embedded body
            (merge {:db/id id :syn/id id :syn/kind :note
                    :syn.note/body (lorem (+ 20 (rand-int* 300)))
                    :syn.note/source (rand-nth* src-ids)}
                   (embed-fields))
            (< roll 70)                       ; expense
            {:db/id id :syn/id id :syn/kind :expense
             :syn.expense/amount (double (rand-int* 50000))
             :syn.expense/category (rand-nth* categories)
             :syn.expense/date (Date.)}
            (< roll 85)                       ; subscription
            {:db/id id :syn/id id :syn/kind :sub
             :syn.sub/name (lorem 2) :syn.sub/cost (double (rand-int* 200))}
            (< roll 93)                       ; maintenance
            {:db/id id :syn/id id :syn/kind :maint
             :syn.maint/task (lorem 4) :syn.maint/cadence-days (long (inc (rand-int* 365)))}
            :else                             ; run
            {:db/id id :syn/id id :syn/kind :run
             :syn.run/distance-km (double (/ (rand-int* 4200) 100))})))))))

;;; --- measurement ------------------------------------------------------------

(defn ms [start] (/ (- (System/nanoTime) start) 1e6))
(defn median [xs] (let [s (sort xs)] (nth s (quot (count s) 2))))
(defn r2 [x] (/ (Math/round (* (double x) 100.0)) 100.0))

(defn used-mb []
  (System/gc) (Thread/sleep 200) (System/gc) (Thread/sleep 200)
  (let [r (Runtime/getRuntime)]
    (r2 (/ (double (- (.totalMemory r) (.freeMemory r))) 1048576.0))))

(defn cold+warm
  "Returns {:cold ms :warm ms}. `fns` is a vector of 0-arg thunks; cold = the
   FIRST one (novel, uncached work), warm = median of repeating the LAST one 5x.
   When a single thunk is given, cold = its first call, warm = median of 5 more."
  [thunk-fn]
  ;; thunk-fn takes :cold? -> returns a thunk producing a NOVEL query each cold
  (let [c0 (System/nanoTime) _ ((thunk-fn :cold)) cold (ms c0)
        warm (median (vec (repeatedly 5 (fn [] (let [t (System/nanoTime)] ((thunk-fn :warm)) (ms t))))))]
    {:cold (r2 cold) :warm (r2 warm)}))

(defn du-mb [path]
  (try
    (let [out (:out (clojure.java.shell/sh "du" "-sk" path))
          kb  (Long/parseLong (first (str/split (str/trim out) #"\s+")))]
      (r2 (/ kb 1024.0)))
    (catch Throwable _ -1.0)))

(defn -main [& _]
  (require 'clojure.java.shell)
  (let [size (Long/parseLong (or (System/getenv "BENCH_SIZE") "1000"))
        frac (Double/parseDouble (or (System/getenv "BENCH_FRAC") "0.30"))
        cap  (Long/parseLong (or (System/getenv "BENCH_CAP")
                                 (str (+ 2000 (long (Math/ceil (* size 0.40)))))))
        max-heap (r2 (/ (.maxMemory (Runtime/getRuntime)) 1048576.0))
        conn (fresh-conn)]
    (println "BENCH start size=" size "cap=" cap "maxHeapMB=" max-heap)
    (d/transact conn syn-schema)
    (d/transact conn [(index-def cap)])
    ;; ---- transact in self-contained batches; measure throughput ----
    ;; Each batch is generated by its OWN gen-chunk call so string tempids
    ;; (refs) resolve WITHIN that single transaction — they do not resolve
    ;; across tx boundaries.
    (reset! !uid 0)
    (let [batch 2000
          batch-sizes (let [full (quot size batch) rem (mod size batch)]
                        (cond-> (vec (repeat full batch)) (pos? rem) (conj rem)))
          t0 (System/nanoTime)
          _  (doseq [bs batch-sizes] (d/transact conn (gen-chunk bs)))
          tx-ms (ms t0)
          db (d/db conn)
          datoms (count (d/datoms db :eavt))
          n-embed (ffirst (d/q '[:find (count ?e) :where [?e :seon/embedding _]] db))
          ;; a real project eid for the ref-join
          proj-eids (mapv first (d/q '[:find ?p :where [?p :syn/kind :project]] db))
          ;; queries — cold variant picks a NOVEL bind each time
          q-byattr (cold+warm (fn [_] (let [cat (rand-nth* categories)]
                                        #(d/q '[:find (count ?e) :in $ ?c
                                                :where [?e :syn.expense/category ?c]] db cat))))
          q-join   (cold+warm (fn [_] (let [p (rand-nth* proj-eids)]
                                        #(d/q '[:find (count ?t) :in $ ?p
                                                :where [?t :syn.task/project ?p]] db p))))
          q-2hop   (cold+warm (fn [_] #(d/q '[:find (count ?a)
                                              :where [?a :syn.task/depends-on ?b]
                                                     [?b :syn.task/depends-on ?c]] db)))
          q-agg    (cold+warm (fn [_] #(d/q '[:find ?cat (sum ?amt)
                                              :where [?e :syn.expense/category ?cat]
                                                     [?e :syn.expense/amount ?amt]] db)))
          q-scan   (cold+warm (fn [_] #(d/q '[:find (count ?e) :where [?e :syn/kind ?k]] db)))
          vt       (get-in db [:secondary-indices index-ident])
          knn      (cold+warm (fn [_] (let [qv (float-array (rand-vec))]
                                        #(sec/-slice-ordered vt {:vector qv :k 10} nil nil :asc nil))))
          heap (used-mb)
          store-mb (du-mb store-path)
          idx-mb   (du-mb index-path)
          row {:size size :cap cap :datoms datoms :n-embed n-embed
               :tx-sec (r2 (/ tx-ms 1000.0)) :tx-rate (long (/ size (/ tx-ms 1000.0)))
               :store-mb store-mb :idx-mb idx-mb :total-mb (r2 (+ store-mb idx-mb))
               :heap-mb heap :max-heap-mb max-heap
               :byattr q-byattr :join q-join :twohop q-2hop
               :agg q-agg :scan q-scan :knn knn}]
      (println "ROW" (pr-str row))
      (let [f (io/file "tmp/bench-results.edn")
            prev (if (.exists f) (read-string (slurp f)) [])]
        (spit f (pr-str (conj prev row))))
      (d/release conn)
      (println "DONE size=" size)
      (System/exit 0))))
