;; Evaluate in an MCP JVM scratch namespace. No default Vars or tests are changed.
(do
(require '[seon.db :as db] '[seon.cluster.boot :as boot] '[seon.cluster.store :as store]
 '[seon.cluster.registry :as registry] '[seon.id :as id] '[seon.batch :as batch]
 '[datahike.api :as d] '[seon.cache])
(require '[datahike.db.interface :as dbi])
(def logical-datoms @#'seon.db/logical-datoms)
(def attribute-holders @#'seon.db/attribute-holders)
(def storage-q-in @#'seon.db/storage-q-in)
(def call-edges @#'seon.db/call-edges)
(def arity-comparison @#'seon.db/arity-comparison)
(defn touched-arity-comparison
  "Check only final call edges whose caller or callee this write changes.

  EAVT reads written callers; the calls AVET index finds callers of changed
  callees. Schema/default changes follow reverse schema references to the
  arities using them. Bounds are read only for the reached callees. No
  whole-program base or cache key is retained; coverage names this write."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/transaction-report] :map]}
  [projection report]
  (let [before (:db-before report) after (:db-after report)
        tx-data (logical-datoms projection report)
        touched (fn [attributes] (into #{} (comp (filter (comp attributes :a)) (map :e)) tx-data))
        values (fn [database entities attributes]
                 (into #{} (for [e entities a attributes :when (get (dbi/-schema database) a)
                                 datom (d/datoms database :eavt e a)] (:v datom))))
        holders (fn [database attribute value]
                  (when (get (dbi/-schema database) attribute)
                    (attribute-holders database attribute value)))
        both (fn [entities attributes]
               (into (values before entities attributes) (values after entities attributes)))
        changed-schemas (both (touched #{:seon.schema/key :seon.schema/form}) [:seon.schema/key])
        defaults (touched #(= "seon.call-preparation" (namespace %)))
        supplied (into #{} (for [database [before after]
                                 e (values database defaults [:seon.call-preparation/schema])
                                 k (values database [e] [:seon.schema/key])] k))
        schemas (loop [pending (into changed-schemas supplied) seen #{}]
                  (if-let [k (first pending)]
                    (recur (into (disj pending k)
                                 (remove (conj seen k))
                                 (values after (holders after :seon.schema/references k) [:seon.schema/key]))
                           (conj seen k))
                    seen))
        arities (into (touched #{:seon.fn.arity/min :seon.fn.arity/max})
                      (mapcat #(holders after :seon.fn.arity/input-refs %)) schemas)
        owners (into #{} (for [database [before after] arity arities
                               e (holders database :seon.fn/arities arity)] e))
        callees (both (into owners (touched #{:seon.fn/arities :seon.fn/sym :seon.fn/spec})) [:seon.fn/sym])
        written (touched #{:seon.fn/call-arities :seon.fn/sym :seon.test/sym})
        written-symbols (values after written [:seon.fn/sym :seon.test/sym])
        callers (into written-symbols
                      (values after (mapcat #(holders after :seon.fn/calls %) callees)
                              [:seon.fn/sym :seon.test/sym]))
        storage-q (partial storage-q-in projection)
        edges (if (seq callers)
                (into #{} (filter (fn [[caller [callee _]]]
                                    (or (written-symbols caller) (callees callee))))
                      (call-edges storage-q after callers))
                #{})
        targets (into #{} (map (comp first second)) edges)
        bounds (if (seq targets)
                 (reduce (fn [out [sym minimum maximum]]
                           (update out sym (fnil conj #{})
                                   (cond-> {:seon.fn.arity/min minimum}
                                     (nat-int? maximum) (assoc :seon.fn.arity/max maximum))))
                         {}
                         (storage-q '[:find ?sym ?minimum ?maximum :in $ [?sym ...]
                                      :where [?f :seon.fn/sym ?sym] [?f :seon.fn/arities ?a]
                                             [?a :seon.fn.arity/min ?minimum]
                                             [(get-else $ ?a :seon.fn.arity/max -1) ?maximum]] after targets))
                 {})]
    (assoc (arity-comparison edges bounds) :seon.db/bounds bounds)))

(def process-cache @#'seon.cache/process)
(defn evict-kinds-after!
  "Remove the entries of retired producer kinds, leaving other kinds intact.

  Reload calls this when it deletes a cache producer. This is O(cache entries)
  once at retirement, not an invalidation on reads or writes. In-flight values
  may finish for their existing callers but are no longer retained."
  {:malli/schema [:function
                  [:=> [:cat [:set :qualified-keyword]] nat-int?]
                  [:=> [:cat :seon.cache/cache [:set :qualified-keyword]] nat-int?]]}
  ([kinds] (evict-kinds-after! @process-cache kinds))
  ([{cache :seon.cache/cache} kinds]
   (let [entries (.asMap ^com.github.benmanes.caffeine.cache.AsyncCache cache)
         removed (reduce (fn [n [k pending]]
                           (if (and (kinds (first k)) (.remove entries k pending)) (inc n) n))
                         0 entries)]
     (.cleanUp (.synchronous ^com.github.benmanes.caffeine.cache.AsyncCache cache))
     removed)))


(let [held (:seon.store/store (#'boot/selected-instance "default"))
 base (db/db (boot/connection "default")) branch (keyword (str "astra-arity-" (id/id)))
 _ (registry/branch! {:seon.store/store held :seon.store/branch branch :seon.cluster.registry/from (:datahike.value/commit-id (db/committed-value-identity base))})
 conn (store/open-branch! held branch)]
 (try
  (let [database (db/db conn) projection (db/carried-projection database)
        caller (:db/id (db/pull database [:db/id] [:seon.fn/sym 'seon.id/evaluation]))
        arities (:seon.fn/arities (db/pull database [{:seon.fn/arities [:db/id]}] [:seon.fn/sym 'seon.id/id]))
        cases [[:wrong-call [[:db/add caller :seon.fn/call-arities ['seon.id/id 77]] [:db/add caller :seon.fn/calls 'seon.id/id]]]
               [:changed-callee (vec (mapcat (fn [a] [[:db/add (:db/id a) :seon.fn.arity/min 99] [:db/add (:db/id a) :seon.fn.arity/max 99]]) arities))]
               [:unrelated [[:db/add caller :seon.fn/doc "Arity audit, disposable value."]]]]]
    (mapv (fn [[label tx]]
      (let [report (d/with database tx)
            _ (evict-kinds-after! #{:seon.db/arity-base :seon.db/arity-revision :seon.db/arity-commit})
            answers (mapv (fn [f] (let [start (System/nanoTime)
                              comparison (f projection report)
                              compared (/ (- (System/nanoTime) start) 1e6)
                              result (#'db/arity-verdict (:db-after report) projection report comparison)]
                         {:ms compared :total-ms (/ (- (System/nanoTime) start) 1e6) :checked (:seon.db/checked comparison) :edges (:seon.db/edges comparison) :verdict result}))
                          [#'db/report-arity-comparison touched-arity-comparison])
            verdicts (mapv #(get-in % [:verdict :seon.fn/arity-mismatches]) answers)]
        (assert (every? vector? verdicts))
        (assert (apply = verdicts))
        {:case label :mismatches (count (first verdicts)) :same-verdict true
         :results (mapv #(dissoc % :verdict) answers)})) cases))
  (finally (store/release-branch! conn)
           (registry/retire-branch! {:seon.store/store held :seon.store/branch branch}))))
)
