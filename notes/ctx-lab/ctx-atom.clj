;; The ctx atom whole (after ctx.clj): read (the state hook patches it), write (its validator turns a
;; swap!'s change into one validated transaction). The atom holds the tree; the path index is the
;; mechanism's own (ctx-ix). A prototype to measure.
(require '[clojure.set :as set])
(remove-watch agent/state ::ctx)

(def ctx (atom nil))
(def ctx-ix (atom nil))
(def ^:dynamic *writing* false)   ; inside the validator's transact!: the state hook leaves ctx to it
(def ^:dynamic *patching* false)  ; ctx is being set from the db: nothing to write
(def pending (volatile! nil))     ; the db's own form of a write, installed after its swap!
(def sch (volatile! nil))         ; the db's schema, read once (d/schema is 175K ticks a call)
(defn one-attr [_ a] (let [s (get @sch a)] {:many? (= :db.cardinality/many (:db/cardinality s)) :ref? (= :db.type/ref (:db/valueType s))}))

(defn load! []
  (vreset! sch (d/schema (agent/db)))
  (let [t (binding [*schema* @sch] (tree (me)))]
    (reset! ctx-ix (index-paths :seon.db/agent-fact t))
    (binding [*patching* true] (reset! ctx t))))

;; read: every move of this process's db (agent/transact!, the REPL's records, merge-main!) patches ctx
(defn on-state [_ _ old new]
  (let [a (:db old) b (:db new)]
    (when (and a b @ctx (not *writing*) (not (identical? a b)) (not= (:max-tx a) (:max-tx b)))
      (let [[gone added] (d/datoms-diff a b :eavt [])
            c (binding [*schema* @sch] (patch {:tree @ctx :ix @ctx-ix} b (concat (map #(dm % false) gone) (map #(dm % true) added))))]
        (reset! ctx-ix (:ix c))
        (binding [*patching* true] (reset! ctx (:tree c)))))))

;; write: the change between two trees as transaction data, each value checked by its schema
(defn units-keys [s] (let [{:keys [rev fwd]} (units-of s)] (set (concat (vals rev) (filter #(:ref? (one-attr nil %)) fwd)))))
(defn plain "an entity as transaction data: no units, refs as their ids" [s m]
  (into {} (for [[a v] (apply dissoc m :db/id (units-keys s))] [a (if (and (map? v) (:db/id v)) (:db/id v) v)])))
(defn explain [k v] (when-not (contract/valid? k v) {:at k :value v :explain (m/explain (contract/schema k) v)}))

(defn diff-entity
  "{:tx :invalid :paths} from old to new of entity schema s, eid e at path p: O(what differs), a
  subtree identical to its old self skipped whole."
  [s old new e p acc]
  (if (identical? old new) acc
    (let [{:keys [rev]} (units-of s) us-keys (units-keys s)
          attrs (remove (conj us-keys :db/id) (set/union (set (keys old)) (set (keys new))))
          acc (reduce (fn [acc a]
                        (let [o (get old a) n (get new a) {:keys [many?]} (one-attr nil a)]
                          (cond
                            (= o n) acc
                            many? (let [o (set o) n (set n)
                                        bad (keep #(explain a #{%}) (set/difference n o))]
                                    (-> acc (update :tx into (concat (for [x (set/difference n o)] [:db/add e a x])
                                                                    (for [x (set/difference o n)] [:db/retract e a x])))
                                        (update :invalid into bad) (update :paths conj (conj p a))))
                            (nil? n) (-> acc (update :tx conj [:db/retract e a (if (map? o) (:db/id o) o)]) (update :paths conj (conj p a)))
                            :else (let [v (if (and (map? n) (:db/id n)) (:db/id n) n)]
                                    (-> acc (update :tx conj [:db/add e a v]) (update :invalid into (keep identity [(explain a n)]))
                                        (update :paths conj (conj p a)))))))
                      acc attrs)]
      (reduce
       (fn [acc u]
         (let [us (unit-schema u) om (get old u) nm (get new u)]
           (cond
             (identical? om nm) acc
             (rev? u)
             (let [id-a (ident-of us)
                   acc (reduce-kv (fn [acc id m]
                                    (let [o (get om id)]
                                      (cond (identical? o m) acc
                                            (nil? o) (let [ent (assoc (plain us m) (fwd u) e id-a id)]
                                                       (-> acc (update :tx conj ent) (update :paths conj (conj p u id))
                                                           (update :invalid into (keep identity [(explain us ent)]))))
                                            :else (diff-entity us o m (:db/id o) (conj p u id) acc))))
                                  acc nm)]
               (reduce-kv (fn [acc id o] (if (contains? nm id) acc
                                             (-> acc (update :tx conj [:db/retractEntity (:db/id o)]) (update :paths conj (conj p u id)))))
                          acc om))
             (and (map? om) (map? nm)) (diff-entity us om nm (:db/id om) (conj p u) acc)
             (nil? nm) (-> acc (update :tx conj [:db/retract e u (:db/id om)]) (update :paths conj (conj p u)))
             :else acc)))
       acc (concat (vals rev) (remove #(not (:ref? (one-attr nil %))) (:fwd (units-of s))))))))

(defn strip [x] (if (map? x) (into {} (for [[k v] (dissoc x :db/id)] [k (strip v)])) x))

(defn write!
  "The validator's work for a swap! from old to nv: its change as one transaction, every value valid
  by its schema; the db's own form of it (d/with, then patch) must hold what nv says at every path it
  changed; then transact!, and the db's form is installed after the swap (pending). Throws, refusing
  the swap, when invalid or not held."
  [old nv]
  (let [{:keys [tx invalid paths]} (diff-entity :seon.db/agent-fact old nv (:db/id old) [] {:tx [] :invalid [] :paths []})]
    (cond
      (empty? tx) true
      (seq invalid) (throw (ex-info (str "ctx: invalid " (pr-str (map :at invalid)))
                                    {:seon.error/type :seon.ctx/invalid :invalid (mapv #(update % :explain (comp seq :errors)) invalid)}))
      :else
      (let [rep (d/with (agent/db) tx)
            canon (binding [*schema* @sch] (patch {:tree old :ix @ctx-ix} (:db-after rep) (map #(dm % (:added %)) (:tx-data rep))))
            held? (fn [w h] (if (and (map? w) (map? h)) (= (strip w) (strip (select-keys h (keys w)))) (= w h)))
            unheld (remove #(held? (get-in nv %) (get-in (:tree canon) %)) paths)]
        (when (seq unheld)
          (throw (ex-info (str "ctx: the db does not hold " (pr-str (vec unheld)))
                          {:seon.error/type :seon.ctx/not-held :paths (vec unheld)
                           :wrote (mapv #(get-in nv %) unheld) :held (mapv #(get-in (:tree canon) %) unheld)})))
        (binding [*writing* true] (agent/transact! tx))
        (vreset! pending canon)
        true))))

(set-validator! ctx (fn [nv] (or *patching* (nil? @ctx) (write! @ctx nv))))
;; after a written swap!: ctx holds the db's form of it (its new entities' :db/id)
(add-watch ctx ::held (fn [_ _ _ _] (when-let [c @pending] (vreset! pending nil) (reset! ctx-ix (:ix c)) (binding [*patching* true] (reset! ctx (:tree c))))))
(add-watch agent/state ::ctx on-state)
(load!)
:ctx
