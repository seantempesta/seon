(ns seon.ctx.tree
  "Pure 2-index (`:eavt` forward + reverse `:avet` refs) Datahike tree projection and bidirectional `diff-entity-tx` for `@seon.ctx/ctx`."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [datahike.reads :as reads]
            [seon.render.links :as rlinks]
            [seon.store :as store]))

(def readonly-env-keys
  "Computed metadata keys attached to `@ctx` maps that are never written back as entity datoms."
  #{:db/id :seon.ctx/branch :seon.ctx/tx :seon.ctx/commit})

(defn reverse-attr?
  "True when `k` is a Datahike reverse-ref keyword like `:seon.agent.eval/_agent-ref`."
  {:malli/schema [:=> [:cat :any] :boolean]
   :cost/ticks 500}
  [k]
  (boolean (and (qualified-keyword? k) (.startsWith (name k) "_"))))

(defn reverse->forward-attr
  "Converts reverse-ref keyword `:ns/_attr` to forward keyword `:ns/attr`, or nil."
  {:malli/schema [:=> [:cat :any] [:maybe :qualified-keyword]]
   :cost/ticks 1000}
  [k]
  (when (reverse-attr? k)
    (keyword (namespace k) (subs (name k) 1))))

(defn forward->reverse-attr
  "Converts forward-ref keyword `:ns/attr` to reverse keyword `:ns/_attr`, or nil."
  {:malli/schema [:=> [:cat :any] [:maybe :qualified-keyword]]
   :cost/ticks 1000}
  [k]
  (when (and (qualified-keyword? k) (not (.startsWith (name k) "_")))
    (keyword (namespace k) (str "_" (name k)))))

(defn all-ref-attrs
  "Returns all `:db.type/ref` attribute keywords present in `db`'s schema (zero hardcoded entity lists)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :qualified-keyword]]
   :cost/ticks 500000}
  [db]
  (let [dh (rlinks/db-schema db)]
    (->> dh
         (keep (fn [[k m]]
                 (when (and (qualified-keyword? k) (= :db.type/ref (:db/valueType m)))
                   k)))
         sort vec)))

(defn- entity-sort-key [m]
  [(long (or (:seon.agent.eval/turn m) 0))
   (long (or (:seon.agent.eval/call-index m) 0))
   (long (or (:db/id m) 0))])

(defn- eavt-map
  "Converts forward `:eavt` datoms of `eid` directly into a map (expanding `:db.type/ref` values via `expand-ref` when provided)."
  [db eid expand-ref]
  (let [dh (rlinks/db-schema db)
        ds (vec (d/datoms db :eavt eid))]
    (if (empty? ds)
      {:db/id eid}
      (reduce-kv
       (fn [acc a adatoms]
         (let [sp (get dh a)
               ref? (= :db.type/ref (:db/valueType sp))
               many? (= :db.cardinality/many (:db/cardinality sp))
               xf (fn [v]
                    (if ref?
                      (if expand-ref (expand-ref a v) {:db/id v})
                      v))]
           (assoc acc a
                  (if many?
                    (into #{} (map #(xf (:v %))) adatoms)
                    (xf (:v (first adatoms)))))))
       {:db/id eid}
       (group-by :a ds)))))

(defn pull-reverse-entry
  "Reads Index 2 (`:avet rev-attr eid`) with `datahike.reads` tracking and converts referencing entities via `:eavt` (zero attribute whitelists)."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :int :qualified-keyword] :map]
                  [:=> [:cat :seon.host/db :int :qualified-keyword [:set :int]] :map]]
   :cost/ticks 15000000}
  ([db eid rev-attr] (pull-reverse-entry db eid rev-attr #{eid}))
  ([db eid rev-attr skip-eids]
   (let [rk (forward->reverse-attr rev-attr)
         [v rs] (reads/with-reads
                  (fn []
                    (let [dh (rlinks/db-schema db)]
                      (if-not (contains? dh rev-attr)
                        []
                        (let [eids (->> (d/datoms db :avet rev-attr eid)
                                        (map :e)
                                        (remove #(contains? skip-eids %))
                                        distinct)]
                          (->> eids
                               (map #(eavt-map db % nil))
                               (sort-by entity-sort-key)
                               vec))))))]
     {:key rk :rev-attr rev-attr
      :val (if (seq v) v :seon.ctx/absent)
      :reads-idx (reads/reads-index rs)})))

(defn entity->tree
  "Converts Datahike's two indexes (`:eavt` forward + `:avet` reverse across all `:db.type/ref` attrs) around `eid` into a Clojure tree."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :int] :map]
                  [:=> [:cat :seon.host/db :int :int [:set :int] [:maybe :seon.host/conn]] :map]]
   :cost/ticks 25000000}
  ([db eid] (entity->tree db eid 1 #{} nil))
  ([db eid depth visited conn]
   (let [vis (conj visited eid)
         ref-attrs (all-ref-attrs db)
         cur-branch (get-in db [:config :branch] :main)
         expand-fwd (when (pos? depth)
                      (fn [_a target-eid]
                        (if (contains? vis target-eid)
                          {:db/id target-eid}
                          (let [;; If conn is available and target is an entity living on another branch (e.g. :seon.agent/target), read from its branch!
                                tb (when (and conn (contains? (rlinks/db-schema db) :seon.agent/branch))
                                     (:v (first (d/datoms db :eavt target-eid :seon.agent/branch))))
                                use-db (if (and tb (not= tb cur-branch))
                                         (or (try (store/head conn tb) (catch Throwable _ nil)) db)
                                         db)
                                mdb (when (and conn tb (not= tb cur-branch))
                                      (try (store/head conn :main) (catch Throwable _ nil)))
                                sub (entity->tree use-db target-eid (dec depth) vis nil)]
                            (if (and mdb (not= tb :main))
                              (let [main-sub (eavt-map mdb target-eid nil)]
                                (merge sub (select-keys main-sub [:seon.agent/paused? :seon.agent/auto-merge? :seon.agent/tested-tx :seon.agent/merged-tx])
                                {:seon.ctx/branch tb}))
                              sub)))))
         fwd (eavt-map db eid expand-fwd)]
     (if-not (pos? depth)
       fwd
       (reduce (fn [acc ra]
                 (let [eids (->> (d/datoms db :avet ra eid)
                                 (map :e)
                                 (remove #(contains? vis %))
                                 distinct)]
                   (if (seq eids)
                     (assoc acc (forward->reverse-attr ra)
                            (->> eids (map #(eavt-map db % nil)) (sort-by entity-sort-key) vec))
                     acc)))
               fwd
               ref-attrs)))))

(defn pull-forward-entry
  "Reads Index 1 (`:eavt eid`) + 1-hop forward refs (each expanded with its 2-index subtree) under `datahike.reads` tracking."
  {:malli/schema [:=> [:cat :seon.host/db :int [:maybe :seon.host/conn]] :map]
   :cost/ticks 20000000}
  [db eid conn]
  (let [[m rs] (reads/with-reads
                 (fn []
                   (let [cur-branch (get-in db [:config :branch] :main)
                         expand-fwd (fn [_a target-eid]
                                      (if (= target-eid eid)
                                        {:db/id target-eid}
                                        (let [tb (when (and conn (contains? (rlinks/db-schema db) :seon.agent/branch))
                                                   (:v (first (d/datoms db :eavt target-eid :seon.agent/branch))))
                                              use-db (if (and tb (not= tb cur-branch))
                                                       (or (try (store/head conn tb) (catch Throwable _ nil)) db)
                                                       db)
                                              mdb (when (and conn tb (not= tb cur-branch))
                                                    (try (store/head conn :main) (catch Throwable _ nil)))
                                              sub (entity->tree use-db target-eid 1 #{eid} nil)]
                                          (if (and mdb (not= tb :main))
                                            (let [main-sub (eavt-map mdb target-eid nil)]
                                              (merge sub
                                              (select-keys main-sub [:seon.agent/paused? :seon.agent/auto-merge? :seon.agent/tested-tx :seon.agent/merged-tx])
                                              {:seon.ctx/branch tb}))
                                            sub))))]
                     (eavt-map db eid expand-fwd))))]
    {:key :seon.ctx/forward :val m :reads-idx (reads/reads-index rs)}))

(defn assemble-tree-map
  "Merges forward `:eavt` map, reverse `:avet` entries, and optional `:seon.ctx/neighborhood` into the live `@ctx` value."
  {:malli/schema [:=> [:cat :seon.host/db :int :map [:vector :map] [:maybe :map]] :map]
   :cost/ticks 2000000}
  [db eid fwd-map rev-entries nb-entry]
  (let [with-rev (reduce (fn [acc {:keys [key val]}]
                           (if (= val :seon.ctx/absent) acc (assoc acc key val)))
                         (assoc fwd-map :db/id eid)
                         rev-entries)]
    (with-meta
      (cond-> (assoc with-rev
                     :seon.ctx/branch (get-in db [:config :branch] :main)
                     :seon.ctx/tx (:max-tx db)
                     :seon.ctx/commit (d/commit-id db))
        nb-entry (assoc :seon.ctx/neighborhood (:val nb-entry)))
      (when (:seon.agent/id with-rev) {:seon/schema :seon.agent/entity}))))

(declare diff-entity-tx)

(defn- diff-reverse-ref-tx [db eid rev-k ov nv]
  (let [fwd-a (reverse->forward-attr rev-k)
        old-ms (filterv #(and (map? %) (integer? (:db/id %))) (if (coll? ov) ov []))
        new-ms (filterv map? (if (coll? nv) nv []))
        old-by-id (into {} (map (juxt :db/id identity)) old-ms)
        new-ids (into #{} (keep :db/id) new-ms)
        retracts (vec (keep (fn [om]
                              (when-not (contains? new-ids (:db/id om))
                                [:db/retractEntity (:db/id om)]))
                            old-ms))
        updates (reduce (fn [acc nm]
                          (if-let [om (when (integer? (:db/id nm)) (get old-by-id (:db/id nm)))]
                            (into acc (diff-entity-tx db (:db/id om) om nm))
                            (if (and fwd-a (nil? (:db/id nm)))
                              (conj acc (assoc nm fwd-a eid))
                              acc)))
                        [] new-ms)]
    (into retracts updates)))

(defn- diff-nested-entities [db ov nv]
  (cond
    (or (identical? ov nv) (= ov nv)) []

    (and (map? ov) (map? nv) (integer? (:db/id ov)) (= (:db/id ov) (:db/id nv)))
    (diff-entity-tx db (:db/id ov) ov nv)

    (and (map? ov) (map? nv))
    (reduce-kv (fn [acc k nv']
                 (if-let [ov' (get ov k)]
                   (into acc (diff-nested-entities db ov' nv'))
                   acc))
               [] nv)

    (and (coll? ov) (coll? nv))
    (let [old-by-id (into {} (keep #(when (and (map? %) (integer? (:db/id %))) [(:db/id %) %])) ov)]
      (reduce (fn [acc nm]
                (if-let [om (when (and (map? nm) (integer? (:db/id nm))) (get old-by-id (:db/id nm)))]
                  (into acc (diff-entity-tx db (:db/id om) om nm))
                  acc))
              [] nv))

    :else []))

(defn diff-entity-tx
  "Diffs pulled 2-index tree maps `old-m`/`new-m` for `eid` into Datahike tx-data, including reverse-ref vectors (`[:db/retractEntity eid]` on deletion) and `:seon.ctx/neighborhood`."
  {:malli/schema [:=> [:cat :seon.host/db :int :map :map] [:vector :any]]
   :cost/ticks 10000000}
  [db eid old-m new-m]
  (let [schema (rlinks/db-schema db)
        all-ks (disj (set/union (set (keys old-m)) (set (keys new-m)))
                     :db/id :seon.ctx/branch :seon.ctx/tx :seon.ctx/commit)]
    (reduce
     (fn [acc k]
       (cond
         (contains? readonly-env-keys k) acc

         (reverse-attr? k)
         (let [ov (get old-m k ::absent) nv (get new-m k ::absent)]
           (if (or (identical? ov nv) (= ov nv))
             acc
             (into acc (diff-reverse-ref-tx db eid k (if (= ov ::absent) [] ov) (if (= nv ::absent) [] nv)))))

         (= :seon.ctx/neighborhood k)
         (into acc (diff-nested-entities db (get old-m k) (get new-m k)))

         :else
         (let [ov (get old-m k ::absent) nv (get new-m k ::absent)]
           (cond
             (or (identical? ov nv) (= ov nv)) acc

             (and (map? ov) (map? nv) (integer? (:db/id ov)) (= (:db/id ov) (:db/id nv)))
             (into acc (diff-entity-tx db (:db/id ov) ov nv))

             (and (= :db.cardinality/many (get-in schema [k :db/cardinality]))
                  (= :db.type/ref (get-in schema [k :db/valueType]))
                  (or (and (coll? ov) (some #(and (map? %) (integer? (:db/id %))) ov))
                      (and (coll? nv) (some #(and (map? %) (integer? (:db/id %))) nv))))
             (let [eid-of #(if (map? %) (:db/id %) %)
                   os (into #{} (keep eid-of) (when-not (= ov ::absent) ov))
                   ns (into #{} (keep eid-of) (when-not (= nv ::absent) nv))]
               (-> acc
                   (into (diff-nested-entities db (if (= ov ::absent) [] ov) (if (= nv ::absent) [] nv)))
                   (into (map #(vector :db/retract eid k %) (set/difference os ns)))
                   (into (map #(vector :db/add eid k %) (set/difference ns os)))))

             (and (or (map? ov) (coll? ov)) (or (map? nv) (coll? nv))
                  (nil? (get schema k)) (seq (diff-nested-entities db ov nv)))
             (into acc (diff-nested-entities db ov nv))

             (= nv ::absent)
             (if (= :db.cardinality/many (get-in schema [k :db/cardinality]))
               (into acc (map (fn [item] [:db/retract eid k item]) ov))
               (conj acc [:db/retract eid k ov]))

             (= :db.cardinality/many (get-in schema [k :db/cardinality]))
             (let [os (set (when-not (= ov ::absent) ov))
                   ns (set (if (coll? nv) nv [nv]))]
               (-> acc
                   (into (map (fn [item] [:db/retract eid k item]) (set/difference os ns)))
                   (into (map (fn [item] [:db/add eid k item]) (set/difference ns os)))))

             :else (conj acc [:db/add eid k nv])))))
     [] all-ks)))

(defn ^:seon.flow/edge commit-target-diff!
  "Commits cross-branch edits to `:seon.agent/target` (eval retractions/edits on target branch, `:paused?` on `:main`; zero process hacks)."
  {:malli/schema [:=> [:cat :seon.host/conn :keyword :map :map] :map]
   :cost/ticks 15000000}
  [conn cur-branch old-target new-target]
  (let [taid (:seon.agent/id old-target)
        tb (:seon.agent/branch old-target :main)
        teid (:db/id old-target)]
    (if-not (and taid (integer? teid) (not= tb cur-branch))
      {:handled? false :tx-count 0}
      (let [tdb (or (try (store/head conn tb) (catch Throwable _ nil)) @conn)
            raw-tx (diff-entity-tx tdb teid old-target new-target)]
        (if (empty? raw-tx)
          {:handled? true :tx-count 0}
          (let [main-attrs #{:seon.agent/paused? :seon.agent/auto-merge?}
                main-tx (vec (keep (fn [op]
                                     (when (and (vector? op) (>= (count op) 3) (main-attrs (nth op 2)))
                                       (assoc op 1 [:seon.agent/id taid])))
                                   raw-tx))
                br-tx (vec (remove #(and (vector? %) (>= (count %) 3) (main-attrs (nth % 2))) raw-tx))
                sync-fn (when-let [actx-v (try (requiring-resolve 'seon.fork.calls/*agent-ctx*) (catch Throwable _ nil))]
                        (when-let [actx @actx-v]
                          (when (and (:conn actx)
                                     (= (get-in @conn [:config :store :id])
                                        (get-in @(:conn actx) [:config :store :id])))
                            (try (requiring-resolve 'seon.fork.calls/tx-main-sync!) (catch Throwable _ nil)))))]
            (when (seq br-tx)
              (if sync-fn
                (sync-fn {:op :transact-branch :branch tb :tx-data br-tx :reason [:ctx-target-edit taid]})
                (let [bconn (store/connect (:config @conn) tb)]
                  (try (d/transact bconn {:tx-data br-tx}) (finally (d/release bconn))))))
            (when (seq main-tx)
              (if sync-fn
                (sync-fn {:op :transact :tx-data main-tx :reason [:ctx-target-main taid]})
                (let [mconn (store/connect (:config @conn) :main)]
                  (try (d/transact mconn {:tx-data main-tx}) (finally (d/release mconn))))))
            {:handled? true :tx-count (+ (count br-tx) (count main-tx)) :branch tb}))))))

(defn ^:seon.flow/edge apply-ctx-reset!
  "Applies `CtxAtom.reset` write-back: commits cross-branch `:seon.agent/target` diffs and local branch `diff-entity-tx` (zero process hacks)."
  {:malli/schema [:=> [:cat :seon.host/conn :int :map :map] :map]
   :cost/ticks 25000000}
  [conn eid old-val new-val]
  (let [db @conn
        cur-branch (get-in db [:config :branch] :main)
        ot (:seon.agent/target old-val)
        nt (:seon.agent/target new-val)
        cross-target? (and (map? ot) (map? nt) (:seon.agent/id ot) (= (:db/id ot) (:db/id nt))
                           (not= (:seon.agent/branch ot :main) cur-branch)
                           (not= ot nt))
        tres (when cross-target?
               (commit-target-diff! conn cur-branch ot nt))
        tx (diff-entity-tx db eid
                           (if cross-target? (dissoc old-val :seon.agent/target) old-val)
                           (if cross-target? (dissoc new-val :seon.agent/target) new-val))]
    {:res (when (seq tx) (d/transact conn {:tx-data tx}))
     :target-moved? (pos? (long (or (:tx-count tres) 0)))}))
