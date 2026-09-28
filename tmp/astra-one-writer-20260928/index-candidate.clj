(do
(require '[seon.db :as db] '[seon.fn :as index] '[seon.program :as program] '[seon.schema :as schema] '[seon.cluster.registry :as registry] '[seon.cluster.store :as store] '[seon.cluster.boot :as boot] '[seon.fn.analyzer :as analyzer] '[seon.fs :as fs] '[clojure.set :as set] '[clojure.java.io :as io] '[seon.id :as id])
(def declaration-forms @#'seon.fn/declaration-forms)
(def program-entities @#'seon.fn/program-entities)
(def published-index-entities @#'seon.fn/published-index-entities)
(def desired-entities @#'seon.fn/desired-entities)
(def assert-one-entity-per-identity! @#'seon.fn/assert-one-entity-per-identity!)
(def assert-populated! @#'seon.fn/assert-populated!)
(def reconciled-paths @#'seon.fn/reconciled-paths)
(def file-identities @#'seon.fn/file-identities)
(def changed-schema-keys @#'seon.fn/changed-schema-keys)
(def report-index-progress! @#'seon.fn/report-index-progress!)
(def require-committed! @#'seon.fn/require-committed!)
(def reconcile-call @#'seon.fn/reconcile-call)
(def report-identities @#'seon.fn/report-identities)
(def caller-files @#'seon.fn/caller-files)
(def analyzed-artifacts @#'seon.fn/analyzed-artifacts)
(def file-entities @#'seon.fn/file-entities)
(def reconcile-tx-in @#'seon.fn/reconcile-tx-in)
(defn index-after!
  "Reconcile one branch's program entities with static analysis.

  ONE path for every population: the desired entities replace the core
  entities of `:seon.source/previous-database` (default: the branch's own
  current value) in one writer transaction (`reconcile-call`). A first
  population is the same reconciliation against an empty value, so a change
  proven by an incremental publication is proven for a fresh store too. The
  optional callback receives bounded progress lines; observation never
  changes transaction boundaries."
  {:malli/schema
   [:function
    [:=> [:cat :seon.fn/index-request]
     [:or :seon.reconcile/result :seon.error/value]]
    [:=> [:cat :seon.fn/index-request [:fn clojure.core/ifn?]]
     [:or :seon.reconcile/result :seon.error/value]]]}
  ([request]
   (index-after! request (constantly nil)))
  ([{connection :seon.db/connection process :seon.db/process
     previous-database :seon.source/previous-database
     source-database :seon.source/database
     expected-head :seon.source/expected-head :as request}
    progress!]
   (let [request (cond-> request
                   (not source-database) (assoc :seon.program/rows (program-entities request)))
         entity-shapes (program/shapes-in
                     (or (:seon.schema/projection request)
                         (schema/build-projection (declaration-forms request))))
         entity-maps (if source-database
                (if-let [identities (:seon.reconcile/adopt-identities request)]
                  (published-index-entities source-database (vec identities))
                  (published-index-entities source-database))
                (let [declarations (desired-entities request progress!)
                      file-paths (into #{} (map :seon.fn.file/relative-path)
                                       (filter :seon.fn.file/relative-path (program-entities request)))
                      paths (:seon.fn/changed-paths request)
                      inputs (:seon.source/relative-file-digests request)]
                  (into declarations
                        (keep (fn [[path digest]]
                                (when (and (not (file-paths path))
                                           (or (nil? paths) (paths path)))
                                  {:seon.fn.file/relative-path path
                                   :seon.fn.file/digest digest
                                   :seon.schema.admission/source :core})))
                        inputs)))]
     (if (and (map? entity-maps)
              (contains? entity-maps :seon.error/at)
              (contains? entity-maps :seon.error/layer)
              (contains? entity-maps :seon.error/operation))
       entity-maps
       (let [_ (assert-one-entity-per-identity! entity-maps)
             _ (when-not (or (:seon.fn/changed-paths request)
                             (:seon.reconcile/adopt-identities request))
                 (assert-populated! entity-maps))
             previous-database (or previous-database (db/db connection))
             reconciled (when-not (:seon.reconcile/adopt-identities request)
                          (reconciled-paths request))
             previous-identities
             (if-let [identities (:seon.reconcile/adopt-identities request)]
               (vec identities)
               (if-let [paths reconciled]
               (let [surviving (into #{} (map program/row-identity) (:seon.program/rows request))]
                 (into (vec (map #(vector :seon.schema/key %) (changed-schema-keys request)))
                       (remove surviving)
                       (concat (file-identities previous-database (vec paths) :seon.fn/file)
                               (file-identities previous-database (vec paths) :seon.lint/file))))
             (into []
                   (mapcat
                    (fn [attribute]
                      (map (fn [value] [attribute value])
                           (db/q '[:find [?value ...]
                                   :in $ ?attribute
                                   :where
                                   [?entity ?attribute ?value]
                                   [?entity :seon.schema.admission/source :core]]
                                 previous-database attribute))))
                   (filter #(get (:schema previous-database) %)
                           program/identity-attributes))))
             previous-identities
             (if-let [inputs (:seon.source/relative-file-digests request)]
               (into previous-identities
                     (comp (remove #(contains? inputs %))
                           (map #(vector :seon.fn.file/relative-path %)))
                     (:seon.fn/changed-paths request))
               previous-identities)
             projection (schema/handed-projection)
             _ (report-index-progress! progress! "development reconciliation transaction")
             report
             (require-committed!
              (db/transact!
               connection
               (cond-> {:tx-data
                        (cond->> [(reconcile-call projection entity-shapes entity-maps previous-identities)]
                          ;; Entities derived from one published commit refuse at
                          ;; the writer once another publication moved its head.
                          expected-head (cons [:db.fn/call registry/head-guard-tx expected-head])
                          true vec)}
                 process (assoc :tx-meta {:seon.db/process process})))
              :seon.fn/population)
             changed-identities (require-committed! (report-identities report)
                                                     :seon.fn/population)
             caller-paths (when (and (not source-database) (:seon.fn/changed-paths request))
                            (set/difference (caller-files report) reconciled))
             findings-report
             (when (seq caller-paths)
               (report-index-progress! progress! (str "analysis callers: " (count caller-paths) " files"))
               (let [directory (or (:seon.fn/root request)
                                   (get-in request [:seon.fn/manifest :seon.fn.manifest/root])
                                   (fs/source-directory))
                     database (:db-after report)
                     artifacts (analyzed-artifacts
                                (or (db/carried-projection database) projection)
                                (or (:seon.fn/roots request)
                                    (get-in request [:seon.fn/manifest :seon.fn.manifest/relative-roots])) directory
                                (mapv #(fs/absolute-path directory %) (sort caller-paths))
                                database
                                (str (io/file directory analyzer/config-directory ".cache")))
                     findings (into [] (comp (mapcat :seon.fn.file/rows) (filter :seon.lint/id)) artifacts)
                     previous (require-committed! (file-entities database (vec caller-paths) :seon.lint/file)
                                                  :seon.fn/population)
                     tx-data (require-committed!
                              (reconcile-tx-in entity-shapes database findings
                                               (mapv program/row-identity previous))
                              :seon.fn/population)]
                 ;; This unpublished connection is owned by this population;
                 ;; nothing can change its database between this diff and write.
                 (when (seq tx-data)
                   (report-index-progress! progress! "caller findings transaction")
                   (require-committed!
                    (db/transact! connection
                                  (cond-> {:tx-data tx-data}
                                    process (assoc :tx-meta {:seon.db/process process})))
                    :seon.fn/population))))
             changed-identities (into changed-identities
                                      (when findings-report
                                        (require-committed! (report-identities findings-report)
                                                            :seon.fn/population)))]
         {:seon.reconcile/converged? (empty? changed-identities)
          :seon.reconcile/operations (count changed-identities)
          :seon.db/transaction-report report
          :seon.reconcile/adopt-identities changed-identities})))))

{:candidate-ready true})
