;; dh-fix store bench: Seon's hot-path queries on an APFS clone of default's store.
;; In a fork JVM: (System/setProperty "dhfix.scratch" DIR) (load-file "DIR/bench.clj") (bench/run-all)
(ns bench
  (:require [datahike.api :as d] [datahike.query :as q] [datahike.lru :as lru]))

(def store-path (str (System/getProperty "dhfix.scratch") "/store"))
(def mx (java.lang.management.ManagementFactory/getThreadMXBean))

(defn cfg [cache-size]
  {:store {:backend :file :path store-path
           :id (java.util.UUID/nameUUIDFromBytes (.getBytes "/Users/sean/src/seon/data/store" "UTF-8"))
           :config {:compressor {:type :lz4}}}
   :branch :cluster-default
   :writer {:backend :self}
   :schema-flexibility :write
   :store-cache-size cache-size})

(defn fresh-conn [] (d/connect (cfg 1)))
(defmacro with-conn [[c] & body] `(let [~c (fresh-conn)] (try ~@body (finally (d/release ~c)))))

(def receipts
  '[:find ?run-id ?ordinal ?source ?result ?error ?output ?at
    :in $ [?run-id ...]
    :where
    [?run :seon.turn/id ?run-id]
    [?form :seon.cluster.eval/run ?run]
    [?form :seon.cluster.eval/ordinal ?ordinal]
    [?form :seon.cluster.eval/source ?source]
    [?receipt :seon.cluster.eval/run ?run]
    [?receipt :seon.cluster.eval/ordinal ?ordinal]
    [?receipt :seon.cluster.eval/at ?at]
    [(get-else $ ?receipt :seon.eval/shown :seon.eval.drive/absent) ?result]
    [(get-else $ ?receipt :seon.cluster.eval/error :seon.eval.drive/absent) ?error]
    [(get-else $ ?receipt :seon.cluster.eval/output :seon.eval.drive/absent) ?output]])

(def analysis
  '[:find [(pull ?f [:seon.fn/sym :seon.fn/arglists :seon.fn/private?]) ...]
    :in $ [?name ...]
    :where [?n :seon.ns/name ?name] [?f :seon.fn/ns ?n] [?f :seon.fn/sym _]])

(def next-ordinal
  '[:find ?ordinal ?source :in $ ?run-id
    :where [?run :seon.turn/id ?run-id]
    [?evaluation :seon.cluster.eval/run ?run]
    [?evaluation :seon.cluster.eval/ordinal ?ordinal]
    [?evaluation :seon.cluster.eval/source ?source]
    (not-join [?evaluation]
              (or [?evaluation :seon.eval/shown _]
                  [?evaluation :seon.cluster.eval/error _]
                  [?evaluation :seon.cluster.eval/interrupted-at _]))])

(def latest-turn
  '[:find (max ?t) . :in $ ?agent-id
    :where [?agent :seon.agent/id ?agent-id]
    [?turn :seon.turn/agent ?agent]
    [?turn :seon.turn/id _ ?t]
    [?turn :seon.turn/closed-tx _]])

(def affected-rules
  '[[(affected ?key ?changed)
     [?schema :seon.schema/key ?changed]
     [(identity ?changed) ?key]]
    [(affected ?key ?changed)
     [?schema :seon.schema/key ?key]
     [?schema :seon.schema/references ?ref]
     (affected ?ref ?changed)]])

(def affected
  '[:find [?sym ...]
    :in $ % [?changed ...] [?attribute ...]
    :where (affected ?key ?changed)
    [?arity ?attribute ?key]
    [?function :seon.fn/arities ?arity]
    [?function :seon.fn/sym ?sym]])

(def shapes {:receipts receipts :analysis analysis :next-ordinal next-ordinal :latest-turn latest-turn
             :affected affected})

(defn inputs [db]
  (let [runs (->> (d/q '[:find ?r ?id :where [?r :seon.turn/id ?id] [_ :seon.cluster.eval/run ?r]] db)
                  (sort-by first) (map second))
        nss (->> (d/q '[:find ?name (count ?f) :where [?n :seon.ns/name ?name] [?f :seon.fn/ns ?n]] db)
                 (sort-by second >) (map first))
        agent (d/q '[:find ?id . :where [?a :seon.agent/id ?id] [?t :seon.turn/agent ?a]] db)]
    {:receipts [(vec (take-last 3 runs))]
     :analysis [(vec (take 2 nss))]
     :next-ordinal [(last runs)]
     :latest-turn [agent]
     :affected [affected-rules
                (vec (take 3 (sort (d/q '[:find [?k ...] :where [?s :seon.schema/key ?k] [?s :seon.schema/references _]] db))))
                [:seon.fn.arity/input-refs :seon.fn.arity/output-refs :seon.fn.arity/guard-refs]]}))

(defn tid [] (.threadId (Thread/currentThread)))
(defn alloc [] (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx (tid)))

(defn measure1 [db stats query args]
  (let [r0 (:reads @stats) a0 (alloc) t0 (System/nanoTime)
        res (binding [q/*query-result-cache?* false] (apply d/q query db args))
        ms (/ (- (System/nanoTime) t0) 1e6)]
    {:ms (/ (Math/round (* 10 ms)) 10.0) :bytes (- (alloc) a0) :reads (- (:reads @stats) r0)
     :rows (if (coll? res) (count res) 1) :answer (hash res)}))

(defn median-by [k xs] (nth (sort (map k xs)) (quot (count xs) 2)))

(defn sample
  "One cold execution on a fresh connection (cache size 1, plan cached) and one warm repeat."
  [query args]
  (with-conn [c]
    (let [db @c stats (-> db :store :storage :stats)]
      [(measure1 db stats query args) (measure1 db stats query args)])))

(defn run-shape [ins label]
  (let [query (shapes label) args (ins label)]
    (vreset! @#'q/plan-cache (lru/lru 100))
    (with-conn [c] (binding [q/*query-result-cache?* false] (apply d/q query @c args)))
    (let [ss (mapv (fn [_] (sample query args)) (range 5)) cold (map first ss) warm (map second ss)]
      {:shape label
       :cold {:ms (median-by :ms cold) :bytes (median-by :bytes cold) :reads (median-by :reads cold)}
       :warm {:ms (median-by :ms warm) :bytes (median-by :bytes warm) :reads (median-by :reads warm)}
       :rows (:rows (first cold)) :answer (:answer (first cold))})))

(defn run-all
  ([] (run-all [:receipts :analysis :next-ordinal :latest-turn :affected]))
  ([labels] (let [ins (with-conn [c] (inputs @c))] (mapv #(run-shape ins %) labels))))
