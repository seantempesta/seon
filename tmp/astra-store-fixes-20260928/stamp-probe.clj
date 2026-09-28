(require '[seon.db :as db] '[seon.cluster.boot :as boot] '[seon.fn :as functions] '[seon.program :as program] '[seon.fs] '[seon.id :as id] '[seon.cluster.store :as store] '[seon.cluster.registry :as registry] '[datahike.index.interface :as idx] '[konserve.impl.defaults :as defaults] '[clojure.java.io :as io])
(do
(require '[seon.schema :as schema])
(def normalized-index-entity #'seon.fn/normalized-index-entity)
(def compile-index-transaction #'seon.fn/compile-index-transaction)
(def seek #'seon.fn/seek)
(defn same-indexed-entity? [current desired]
 (or (= current desired)
     (and (:seon.program/analyzed-source-digest current)
          (:seon.program/analyzed-source-digest desired)
          (= (dissoc current :seon.program/analyzed-source-digest)
             (dissoc desired :seon.program/analyzed-source-digest)))))
(defn reconcile-without-stamp
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
                (same-indexed-entity? normalized-current normalized-desired)
                (recur (next pending) changes)
                (nil? current)
                (recur (next pending) (conj changes {:seon.program/row entity-map :seon.fn/retractions []}))
                :else
                (let [replacement (program/exact-replacement-tx-in
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
(def stamp-base (db/db (boot/connection "default")))
(def stamp-file "src/seon/fn.clj")
(def stamp-file-row (first (functions/published-index-entities stamp-base [[:seon.fn.file/relative-path stamp-file]])))
(def stamp-rows (vec (functions/published-index-entities stamp-base
 (mapv #(vector :seon.fn/sym %) (take 100 (sort (db/q '[:find [?s ...] :in $ ?path :where [?f :seon.fn.file/relative-path ?path] [?e :seon.fn/file ?f] [?e :seon.fn/sym ?s]] stamp-base stamp-file)))))))
(def stamp-digest (:seon.fn.file/digest (#'seon.fn/text-context
                    (str (slurp (io/file (seon.fs/source-directory) stamp-file)) "\n; analysis stamp probe\n"))))
{:rows (count stamp-rows) :old-file-digest (:seon.fn.file/digest stamp-file-row) :new-file-digest stamp-digest})

(do
(defn measure-stamp [n]
 (let [held (:seon.store/store (#'boot/selected-instance "default")) prefix (str "astra-stamp-" (id/id))
       wanted (subvec stamp-rows 0 n) shapes (program/shapes-in (db/carried-projection stamp-base))
       desired (conj (mapv #(assoc % :seon.program/analyzed-source-digest stamp-digest) wanted)
                     (assoc stamp-file-row :seon.fn.file/digest stamp-digest))
       mark (fn [database seen]
              (let [nodes (reduce (fn [out a] (into out (idx/-mark (get database a) seen))) #{}
                                  [:eavt :aevt :avet :temporal-eavt :temporal-aevt :temporal-avet])
                    files (filter #(.isFile %) (map #(io/file (:seon.store/dir held) (defaults/key->store-key %)) nodes))]
                {:nodes (count nodes) :bytes (reduce + (map #(.length %) files))}))
       initial (let [seen (atom #{})] (mark stamp-base seen) @seen)
       outputs
       (mapv (fn [candidate?]
        (let [b (keyword (str prefix "-" candidate?))
              _ (registry/branch! {:seon.store/store held :seon.store/branch b
                                   :seon.cluster.registry/from (:datahike.value/commit-id (db/committed-value-identity stamp-base))})
              conn (store/open-branch! held b)]
         (try
          (let [tx (if candidate? (reconcile-without-stamp shapes (db/db conn) desired [])
                                 (functions/reconcile-tx (db/db conn) desired []))
                t0 (System/nanoTime) report (db/transact! conn tx) ms (/ (- (System/nanoTime) t0) 1e6)
                _ (assert (:db-after report))
                found (functions/published-index-entities (:db-after report) (mapv program/row-identity desired))]
            {:n n :candidate? candidate? :commits 1 :ms ms :datoms (count (:tx-data report))
             :stamp-datoms (count (filter #(= :seon.program/analyzed-source-digest (:a %)) (:tx-data report)))
             :storage (mark (:db-after report) (atom initial))
             :answer (set (map #(dissoc % :seon.program/analyzed-source-digest) found))})
          (finally (store/release-branch! conn)
                   (registry/retire-branch! {:seon.store/store held :seon.store/branch b})))))
        [false true])]
  (assert (apply = (map :answer outputs)))
  (mapv #(dissoc % :answer) outputs)))
(mapv measure-stamp [1 10 100]))

