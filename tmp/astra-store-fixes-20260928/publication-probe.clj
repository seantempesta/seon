(do
(require '[seon.cluster.boot :as boot] '[seon.cluster.store :as store] '[seon.cluster.registry :as registry]
 '[seon.db :as db] '[seon.fn] '[seon.program :as program] '[seon.schema :as schema] '[clojure.set :as set]
 '[datahike.index.interface :as idx] '[konserve.impl.defaults :as defaults] '[clojure.java.io :as io])
(def normalized-index-entity #'seon.fn/normalized-index-entity)
(def compile-index-transaction #'seon.fn/compile-index-transaction)
(def seek #'seon.fn/seek)
(defn replacement-candidate
  "Retract removed set members, preserving unchanged members and component cascades."
  {:malli/schema [:=> [:cat :seon.program/shapes [:map [:db/id :int]] :map]
                  [:vector :seon.schema/value]]}
  [entity-shapes current desired]
  (let [entity-id (:db/id current)
        changed (program/changed-attributes entity-shapes current desired)
        many (get-in entity-shapes [(first (program/row-identity desired)) :seon.program/set-attributes] #{})]
    (conj
     (vec
      (mapcat (fn [attribute]
              (if (many attribute)
                (map #(vector :db/retract entity-id attribute %)
                     (set/difference (set (get current attribute)) (set (get desired attribute))))
                (when (contains? current attribute)
             ;; Datahike validates tuple values before dispatching retraction.
             ;; Cardinality-many values are sets; validate one existing tuple,
             ;; not the set. retractAttribute still removes the entire attribute.
             ;; The current entity comes from the writer's transaction database.
             (let [value (get current attribute)]
               [[:db.fn/retractAttribute entity-id attribute
                (if (or (set? value)
                        (and (sequential? value) (sequential? (first value))))
                  (first value) value)]]))))
              (sort changed)))
     (reduce (fn [row attribute]
               (let [old (set (get current attribute)) new (set (get desired attribute))]
                 (if (= old new) (dissoc row attribute)
                     (assoc row attribute (set/difference new old)))))
             (assoc desired :db/id entity-id) many))))

(defn reconcile-candidate
  "Replace source definitions, owning entities by the shapes `entity-shapes` carries."
  {:malli/schema
   [:=> [:cat :seon.program/shapes :seon.db/database-value
         [:vector :map] [:sequential :seon.db/ref]]
    [:or :seon.db/tx-data :seon.error/value]]}
  [entity-shapes database entity-maps previous-identities]
  (let [projection (or (db/carried-projection database)
                       (schema/projection-from-database database))
        identity-attributes (db/identity-attributes database)
        ;; ONE READ for every desired entity: inside the transaction each
        ;; `db/pull` paid its read's declaration lookup, 0.42 ms a call (6,106
        ;; calls, 2.56 s of a 306-file publication, default 2026-09-27).
        identities (mapv program/row-identity entity-maps)
        pulled (db/pull-many database '[*] identities)
        held (when (vector? pulled) (zipmap identities pulled))
        entity (memoize #(if-let [entry (find held %)] (val entry) (db/pull database '[*] %)))
        desired-identities (set identities)
        removed (remove desired-identities previous-identities)
        ;; A note citing a removed file is uncited text. Its citation
        ;; component's `:seon.issue.citation/file` is a required ref, so the
        ;; component leaves in the removal's own transaction; one AVET seek per
        ;; removed identity, never a scan of the notes.
        uncited (when (get (:schema database) :seon.issue.citation/file)
                  (for [[attribute value] removed
                        removed-datom (seek database :avet attribute value)
                        citation (seek database :avet :seon.issue.citation/file (:e removed-datom))]
                    (:e citation)))
        removed (into (vec removed) (sort uncited))
        desired (vec entity-maps)]
    (if-not held
      pulled
    (loop [pending desired changes []]
      (if-let [entity-map (first pending)]
        (let [current (entity (program/row-identity entity-map))]
          (if (and (map? current)
                   (contains? current :seon.error/at)
                   (contains? current :seon.error/layer)
                   (contains? current :seon.error/operation))
            current
            (let [normalized-current
                  (normalized-index-entity entity-shapes database current
                                        identity-attributes entity)
                  normalized-desired
                  (normalized-index-entity entity-shapes database entity-map
                                        identity-attributes entity)
                  refusal (some #(when (and (map? %)
                                            (contains? % :seon.error/at)
                                            (contains? % :seon.error/layer)
                                            (contains? % :seon.error/operation)) %)
                                [normalized-current normalized-desired])]
              (cond
                refusal refusal
                (= normalized-current normalized-desired)
                (recur (next pending) changes)
                (nil? current)
                (recur (next pending) (conj changes {:seon.program/row entity-map :seon.fn/retractions []}))
                :else
                (let [replacement (replacement-candidate
                                   entity-shapes (assoc normalized-current :db/id (:db/id current)) normalized-desired)
                      set-attributes (get-in entity-shapes [(first (program/row-identity entity-map))
                                                            :seon.program/set-attributes])]
                (recur
                 (next pending)
                 (conj changes
                       {:seon.program/row
                        (merge
                          (select-keys entity-map
                            (conj (program/changed-attributes entity-shapes normalized-current normalized-desired)
                                  (first (program/row-identity entity-map))))
                          (select-keys (last replacement) set-attributes))
                        :seon.fn/retractions
                        (vec (butlast replacement))})))))))
        (let [{entities :seon.fn/index-entities
               identity-operations :seon.fn/index-identity-operations
               keyword-operations :seon.fn/index-keyword-operations}
              (compile-index-transaction projection
                                         (mapv :seon.program/row changes)
                                         identity-attributes)]
          (into (into (into (into (mapv #(vector :db/retractEntity %) removed)
                                  (mapcat :seon.fn/retractions) changes)
                            identity-operations)
                      entities)
                keyword-operations)))))))

)


(do
(def held (:seon.store/store (#'boot/selected-instance "default")))
(def publication-branch :astra-store-publication-20260928)
(registry/branch! {:seon.store/store held :seon.store/branch publication-branch :seon.cluster.registry/from :cluster-default})
(def connection (store/open-branch! held publication-branch))
(def publication-base (db/db connection))
(def publication-row (first (seon.fn/published-index-entities publication-base [[:seon.fn/sym 'seon.render/invoke-selected]])))
(def replacement-shapes
  (let [many (into #{} (keep (fn [[a s]] (when (and (= :db.cardinality/many (:db/cardinality s)) (not (:db/isComponent s))) a))) (:schema publication-base))]
    (update-vals (program/shapes-in (db/carried-projection publication-base)) #(assoc % :seon.program/set-attributes many))))
(defn new-node-bytes [database seen]
 (let [nodes (reduce (fn [out a] (into out (idx/-mark (get database a) seen))) #{}
                     [:eavt :aevt :avet :temporal-eavt :temporal-aevt :temporal-avet])
       files (filter #(.isFile %) (map #(io/file (:seon.store/dir held) (defaults/key->store-key %)) nodes))]
  {:nodes (count nodes) :files (count files) :bytes (reduce + (map #(.length %) files))}))
{:branch publication-branch :calls (count (:seon.fn/calls publication-row))
 :shape-sets (count (get-in replacement-shapes [:seon.fn/sym :seon.program/set-attributes]))})

(do
(defn measure-publication [n]
 (let [namespace-row (first (seon.fn/published-index-entities (db/db connection) [[:seon.ns/name 'seon.render]]))
       digest (fn [row] (assoc row :seon.program/definition-digest (db/definition-digest-of (db/carried-projection (db/db connection)) row namespace-row)))
       row (digest (assoc publication-row :seon.fn/calls (set (take n (sort (:seon.fn/calls publication-row))))))
       seed (db/transact! connection (reconcile-candidate replacement-shapes (db/db connection) [row] []))
       _ (assert (:db-after seed))
       basis (db/db connection)
       commit (registry/connection-branch-commit-id connection publication-branch)
       initial (let [seen (atom #{})] (new-node-bytes basis seen) @seen)
       desired (digest (update row :seon.fn/calls disj (first (sort (:seon.fn/calls row)))))
       outputs
       (mapv (fn [candidate?]
         (let [b (keyword (str "astra-store-pub-" n "-" candidate?))
               _ (registry/branch! {:seon.store/store held :seon.store/branch b :seon.cluster.registry/from commit})
               conn (store/open-branch! held b)]
          (try
           (let [tx ((if candidate? #(reconcile-candidate replacement-shapes %1 %2 []) #(seon.fn/reconcile-tx %1 %2 [])) (db/db conn) [desired])
                 started (System/nanoTime)
                 report (db/transact! conn tx)
                 elapsed (/ (- (System/nanoTime) started) 1e6)
                 _ (assert (:db-after report))
                 after (:db-after report)]
             {:candidate? candidate? :commits 1 :n n :ms elapsed
              :datoms (count (:tx-data report))
              :call-datoms (count (filter #(= :seon.fn/calls (:a %)) (:tx-data report)))
              :storage (new-node-bytes after (atom initial))
              :answer (first (seon.fn/published-index-entities after [[:seon.fn/sym 'seon.render/invoke-selected]]))})
           (finally (store/release-branch! conn)
                    (registry/retire-branch! {:seon.store/store held :seon.store/branch b})))))
        [false true])]
  (assert (apply = (map :answer outputs)))
  (mapv #(dissoc % :answer) outputs)))
(mapv measure-publication [1 10 100]))

;; Release this lane's parent after inspecting the returned measurements.
(do (store/release-branch! connection)
    (registry/retire-branch! {:seon.store/store held :seon.store/branch publication-branch}))
