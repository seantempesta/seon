(ns seon.maintenance
  "Fact-derived maintenance result projection and reporting."
  (:require [seon.error.refusal]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [datahike.api :as d]
            [konserve.core :as k]
            [konserve.protocols]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.fs :as fs]
            [seon.operator.runtime :as runtime]
            [seon.db :as db]
            [seon.error :as error]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [malli.core :as m]
            [malli.registry :as mr])
  (:import [java.io RandomAccessFile]
           [java.nio.file Files StandardCopyOption]))

(schema.edn/load! {})



(defn- result-projections
  [schema-projection result]
  (let [forms (:seon.schema.projection/forms schema-projection)]
    (->> forms
         (keep (fn [[schema-key definition]]
                 (when-let [projection
                            (:seon.maintenance/result-projection
                             (m/properties (mr/schema (:seon.schema.projection/registry schema-projection) schema-key)))]
                   (when ((schema/projection-validator schema-projection schema-key) result)
                     [schema-key projection]))))
         (sort-by (comp str first))
         vec)))

(defn result-entity
  "Project an operation result through its declared persistence producer."
  {:seon.fn/invokes #{:seon.maintenance/result-projection}
   :malli/schema
   [:=> [:cat :seon.schema/projection :seon.maintenance/result-entity-request]
    :seon.maintenance/result-entity-response]}
  [projection result]
  (let [projections (result-projections projection result)]
    (cond
      (empty? projections) result

      (< 1 (count projections))
      {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.maintenance/projection
        :seon.error/operation 'seon.maintenance/result-entity
        :seon.error/message "Maintenance requires one matching persistence producer."
        :seon.error/offending projections
        :seon.maintenance/matching-producer-count (count projections)
        :seon.error/expected "one matching persistence producer"}

      :else
      (let [[schema-key projection] (first projections)]
        (try
          (if-let [producer (requiring-resolve projection)]
            (producer result)
            {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.maintenance/projection
        :seon.error/operation 'seon.maintenance/result-entity
        :seon.error/message "Maintenance requires a resolving persistence producer."
        :seon.error/offending {:seon.schema/key schema-key :seon.maintenance/result-projection projection}
        :seon.maintenance/unresolved-producer projection
        :seon.error/expected "a resolving persistence producer"})
          (catch Throwable cause
            {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.maintenance/projection
        :seon.error/operation 'seon.maintenance/result-entity
        :seon.error/message "Maintenance requires a successful persistence projection."
        :seon.error/offending {:seon.schema/key schema-key :seon.maintenance/result-projection projection :seon.error/exception cause}
        :seon.maintenance/failed-producer projection
        :seon.error/expected "a successful persistence projection"}))))))

(defn project-collect-result
  "Project one public collection value into queryable component facts."
  {:malli/schema
   [:=> [:cat :seon.operator.collect/result]
    :seon.maintenance.result/value]}
  [result]
  (-> (select-keys
       result
       [:seon.operator.collect/store-id
        :seon.operator.collect/managed-root
        :seon.operator.collect/objects-before
        :seon.operator.collect/objects-after
        :seon.operator.collect/swept-objects
        :seon.operator.collect/bytes-before
        :seon.operator.collect/bytes-after
        :seon.operator.collect/reclaimed-bytes
        :seon.operator.collect/verification-pass-swept
        :seon.operator.collect/complete?])
      (assoc :seon.maintenance.result/collect-branches
             (mapv #(select-keys % [:seon.store/branch
                                    :seon.source/commit-id])
                   (:seon.operator.collect/branches result)))))

(defn- collection-component
  "Project one cleanup's collection slot, whichever arm it carries.

  `seon.maintenance/collect-store!` returns its verified collect result or
  throws, so a completed cleanup fills the slot with the SAME value a
  `collect!` receipt stores and it projects through the same producer. An
  error value stays admitted for a cleanup whose collection refused. Absence
  of the error keys is not health: the success arm carries its own facts."
  [collection]
  ;; Debt: seon.operator.cluster-cleanup/collection declares :seon.error/value.
  (if (and (:seon.error/at collection) (:seon.error/layer collection)
           (:seon.error/operation collection))
    (select-keys collection [:seon.error/at :seon.error/layer
                             :seon.error/operation :seon.error/message])
    (project-collect-result collection)))

(defn project-cluster-cleanup-result
  "Project one public cluster cleanup value into queryable facts."
  {:malli/schema
   [:=> [:cat :seon.operator.cluster-cleanup/result]
    :seon.maintenance.result/value]}
  [result]
  (-> (select-keys
       result
       [:seon.operator.cluster-cleanup/managed-root
        :seon.boot/cluster-name
        :seon.store/branch
        :seon.operator.cluster-cleanup/live-instance-stopped?
        :seon.operator.cluster-cleanup/branch-retired?
        :seon.operator.cluster-cleanup/removed
        :seon.operator.cluster-cleanup/remaining
        :seon.operator.cluster-cleanup/reclaimed-bytes
        :seon.operator.cluster-cleanup/complete?])
      (assoc :seon.maintenance.result/cluster-cleanup-collection
             (collection-component
              (:seon.operator.cluster-cleanup/collection result)))))

(def ^:private receipt-pull
  '[*
    {:seon.maintenance.receipt/result [*]}
    {:seon.maintenance.receipt/error [*]}])

(defn- task-rows
  [database]
  (->> (db/q '[:find ?task ?task-id ?function
               :in $
               :where
               [?owner :seon.agent/id "root"]
               [?task :seon.schedule.task/owner ?owner]
               [?task :seon.schedule.task/id ?task-id]
               [?task :seon.schedule.task/function ?function-row]
               [?function-row :seon.fn/sym ?function]]
             database)
       (sort-by second)))

(defn- latest-receipt
  [database task-eid]
  (when-let [[receipt-eid]
             (->> (db/q '[:find ?receipt ?receipt-id ?started-at
                          :in $ ?task
                          :where
                          [?receipt :seon.maintenance.receipt/task ?task]
                          [?receipt :seon.maintenance.receipt/id ?receipt-id]
                          [?receipt :seon.maintenance.receipt/started-at
                           ?started-at]]
                        database task-eid)
                  (sort-by (fn [[_ receipt-id started-at]]
                             [started-at receipt-id]))
                  last)]
    (db/pull database receipt-pull receipt-eid)))

(defn- report-in
  [database]
  {:seon.maintenance/entries
   (mapv (fn [[task-eid task-id function]]
           (let [receipt (latest-receipt database task-eid)]
             (cond-> {:seon.schedule.task/id task-id
                      :seon.fn/sym function}
               receipt
               (assoc :seon.maintenance/receipt-facts
                      (dissoc receipt
                              :seon.maintenance.receipt/result
                              :seon.maintenance.receipt/error))
               (:seon.maintenance.receipt/result receipt)
               (assoc :seon.maintenance/result-facts
                      (:seon.maintenance.receipt/result receipt))
               (:seon.maintenance.receipt/error receipt)
               (assoc :seon.maintenance/error-facts
                      (:seon.maintenance.receipt/error receipt)))))
         (task-rows database))})

(defn report
  "Derive root's latest maintenance receipt for every declared task."
  {:malli/schema
   [:function
    [:=> [:cat] [:or :seon.maintenance/report :seon.db/error-result]]
    [:=> [:cat :seon.db/database-value] :seon.maintenance/report]]}
  ([]
   (let [database (db/db)]
     ;; Debt: seon.db/db declares seon.db/error-result, including :seon.error/value.
     (if (and (map? database) (:seon.error/at database)
              (:seon.error/layer database) (:seon.error/operation database))
       database
       (report-in database))))
  ([database]
   (report-in database)))

(def ^:private collection-facts
  [:seon.operator.collect/store-id
   :seon.operator.collect/objects-before
   :seon.operator.collect/objects-after
   :seon.operator.collect/swept-objects
   :seon.operator.collect/bytes-before
   :seon.operator.collect/bytes-after
   :seon.operator.collect/reclaimed-bytes
   :seon.operator.collect/verification-pass-swept
   :seon.operator.collect/complete?])

(defn- last-collection-in
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.operator/managed-root]
                  [:or :seon.maintenance/collection-record
                   :seon.maintenance/root-never-collected-error]]}
  [database managed-root]
  (let [rows
        (db/q '[:find ?completed-at ?receipt-id ?collection
                :in $ ?root
                :where
                [?collection :seon.operator.collect/managed-root ?root]
                ;; The collection a `collect!` receipt stores IS its result;
                ;; the collection a cleanup receipt stores hangs under the
                ;; cleanup result. Same attributes, two owners, one answer.
                (or-join [?collection ?receipt]
                         [?receipt :seon.maintenance.receipt/result ?collection]
                         (and [?result
                               :seon.maintenance.result/cluster-cleanup-collection
                               ?collection]
                              [?receipt :seon.maintenance.receipt/result ?result]))
                [?receipt :seon.maintenance.receipt/completed-at ?completed-at]
                [?receipt :seon.maintenance.receipt/id ?receipt-id]]
              database managed-root)]
    (if-let [[completed-at receipt-id collection]
             (last (sort-by (fn [[completed-at receipt-id]]
                              [completed-at receipt-id])
                            rows))]
      (merge {:seon.operator/managed-root managed-root
              :seon.maintenance.receipt/id receipt-id
              :seon.maintenance.receipt/completed-at completed-at}
             (db/pull database collection-facts collection))
      {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.maintenance/collection
        :seon.error/operation 'seon.maintenance/last-collection-in
        :seon.maintenance/uncollected-root managed-root
        :seon.error/offending managed-root
        :seon.error/message "No completed maintenance receipt records a collection of this root."
        :seon.error/member :seon.operator/managed-root
        :seon.error/expected :seon.operator.collect/managed-root
        :seon.error/data {:seon.error/layer :seon.maintenance :seon.error/source [:seon.operator.collect/managed-root managed-root]}})))

(defn last-collection
  "When `managed-root` was last collected, and what that collection reclaimed.

  One answer over the facts every collection already stores, whether the
  receipt was a `collect!` firing or a cluster cleanup that collected on its
  way out. A root with no completed collection receipt gets the typed
  refusal, never an empty map read as a clean store."
  {:malli/schema
   [:function
    [:=> [:cat :seon.operator/managed-root]
     [:or :seon.maintenance/collection-record :seon.maintenance/root-never-collected-error :seon.db/error-result]]
    [:=> [:cat :seon.db/database-value :seon.operator/managed-root]
     [:or :seon.maintenance/collection-record :seon.maintenance/root-never-collected-error :seon.db/error-result]]]}
  ([managed-root]
   (let [database (db/db)]
     ;; Debt: seon.db/db declares seon.db/error-result, including :seon.error/value.
     (if (and (map? database) (:seon.error/at database)
              (:seon.error/layer database) (:seon.error/operation database))
       database
       (last-collection-in database managed-root))))
  ([database managed-root]
   (last-collection-in database managed-root)))

(defn- attention-rules
  [projection]
  (->> (keys (:seon.schema.projection/forms projection))
       (keep (fn [attribute]
               (when-let [rule
                          (:seon.maintenance/attention-when
                           (m/properties (mr/schema (:seon.schema.projection/registry projection) attribute)))]
                 [attribute rule])))
       (sort-by (comp str first))))

(defn- rule-triggered?
  [result [attribute rule]]
  (let [present? (contains? result attribute)
        value (get result attribute)]
    (and present?
         (case rule
           :truthy (boolean value)
           :false (false? value)
           :non-empty (boolean (seq value))))))

(defn- entry-attention
  [rules entry]
  (let [receipt (:seon.maintenance/receipt-facts entry)
        result (:seon.maintenance/result-facts entry)
        error (:seon.maintenance/error-facts entry)]
    (cond
      (nil? receipt) :not-run
      error :error
      (:seon.maintenance.receipt/interrupted-at receipt) :interrupted
      (nil? result) :unterminated
      :else (some #(when (rule-triggered? result %) %) rules))))

(defn- succeeded?
  [rules entry]
  (nil? (entry-attention rules entry)))

(defn- latest-at
  [entries]
  (->> entries
       (mapcat (fn [entry]
                 (let [receipt (:seon.maintenance/receipt-facts entry)]
                   (keep receipt
                         [:seon.maintenance.receipt/completed-at
                          :seon.maintenance.receipt/interrupted-at
                          :seon.maintenance.receipt/started-at]))))
       sort
       last))

(defn- operation-name
  [entry]
  (let [function (:seon.fn/sym entry)
        slash (.lastIndexOf ^String function "/")]
    (if (neg? slash) function (subs function (inc slash)))))

(defn- gibibytes
  [byte-count]
  (format "%.1f" (/ (double byte-count) 1073741824.0)))

(defn- percent
  [ratio]
  (format "%.1f" (* 100.0 (double ratio))))

(defn- attention-detail
  [rules entry]
  (let [attention (entry-attention rules entry)
        receipt (:seon.maintenance/receipt-facts entry)
        result (:seon.maintenance/result-facts entry)
        error (:seon.maintenance/error-facts entry)
        operation (operation-name entry)
        receipt-id (:seon.maintenance.receipt/id receipt)]
    (cond
      (= :not-run attention)
      (str operation ": no receipt.")

      (= :error attention)
      (str operation ": error " (:seon.error/id error)
           "; receipt " receipt-id ".")

      (= :interrupted attention)
      (str operation ": receipt " receipt-id " was interrupted.")

      (= :unterminated attention)
      (str operation ": receipt " receipt-id " is unterminated.")

      (and (= :seon.operator/low-space? (first attention))
           (:seon.operator.footprint/usable-bytes result)
           (:seon.operator.footprint/usable-ratio result))
      (str operation ": "
           (gibibytes (:seon.operator.footprint/usable-bytes result))
           " GiB usable ("
           (percent (:seon.operator.footprint/usable-ratio result))
           "%); receipt " receipt-id ".")

      :else
      (let [[attribute] attention
            value (get result attribute)
            amount (if (coll? value) (count value) 1)]
        (str operation ": " amount " " attribute
             "; receipt " receipt-id ".")))))

(defn- report-lines
  [rules report-value]
  (let [entries (:seon.maintenance/entries report-value)
        ran (filter :seon.maintenance/receipt-facts entries)
        succeeded (count (filter (partial succeeded? rules) entries))
        attention (remove (partial succeeded? rules) entries)]
    (cond
      (empty? ran)
      ["Maintenance: no task has run yet."]

      (empty? attention)
      [(str "Maintenance: " succeeded " tasks succeeded; latest "
            (.toString (.toInstant ^java.util.Date (latest-at entries)))
            "; 0 errors.")]

      :else
      (into [(str "Maintenance: " succeeded " succeeded; "
                  (count attention) " need attention.")]
            (map (partial attention-detail rules))
            attention))))

(defn render-report-ai
  "`:seon.render/ai` — root's concise latest maintenance report."
  {:malli/schema [:=> [:cat :seon.maintenance/report :seon.db/database-value] [:string {:min 1}]]}
  [report-value database]
  (str/join "\n" (report-lines (attention-rules (db/carried-projection database)) report-value)))

(defn render-report-html
  "`:seon.render/html` — root's latest maintenance report card."
  {:malli/schema
   [:=> [:cat :seon.maintenance/report :seon.db/database-value] :seon.render/hiccup]}
  [report-value database]
  (let [rules (attention-rules (db/carried-projection database))
        entries (:seon.maintenance/entries report-value)]
    [:article {:class "seon-family-entry seon-maintenance-entry"}
     [:h3 "Maintenance"]
     (if (seq entries)
       (into [:ul {:class "seon-maintenance-attention"}]
             (map (fn [entry]
                    (let [attention (entry-attention rules entry)
                          result (:seon.maintenance/error-facts entry)
                          at (when (:seon.maintenance/receipt-facts entry)
                               (latest-at [entry]))]
                      [:li
                       [:strong (operation-name entry)]
                       [:span {:class (if (succeeded? rules entry) "is-success" "is-attention")}
                        "● " (case attention
                                :not-run "not run" :error "failed"
                                :interrupted "interrupted" :unterminated "running"
                                (if (succeeded? rules entry) "succeeded" "needs attention"))]
                       (when at
                         [:time {:datetime (str (.toInstant ^java.util.Date at))
                                 :title (str at)}
                          (.format (java.text.SimpleDateFormat. "MMM d, HH:mm") at)])
                       (when-let [message (:seon.error/message result)] [:p message])])) )
             entries)
       [:p "No maintenance tasks are recorded."])]))

(defn- exception-value
  {:malli/schema [:=> [:cat :seon.error/throwable] :seon.operator/failed-error]}
  [failure]
  (let [data (ex-data failure)]
    (seon.error.refusal/diagnostic
     (merge data
            {:seon.error/at (java.util.Date.)
             :seon.error/layer ::operation
             :seon.error/operation 'seon.maintenance/exception-value
             :seon.operator/exception-class (symbol (.getName (class failure)))
             :seon.error/message (or (ex-message failure) "The operator call failed.")
             :seon.error/throwable failure
             :seon.error/member ::request
             :seon.error/expected "an operation completing without an exception"
             :seon.error/data (or data {})}))))

(defn- attempt
  {:malli/schema [:=> [:cat [:fn clojure.core/ifn?]] [:or :seon.operator/log-result :seon.operator.collect/result :seon.operator/footprint-observation :seon.operator/failed-error]]}
  [f]
  (try
    (f)
    (catch Throwable error
      (exception-value error))))


(defn- archive-path
  {:malli/schema [:=> [:cat :string :int] :string]}
  [log-path index]
  (str log-path "." index))

(defn rotate-logs!
  "Bound one live log while preserving its inode for the detached JVM."
  {:malli/schema
   [:=> [:cat :seon.operator/log-request]
    [:or :seon.operator/log-result :seon.operator/failed-error]]}
  [{log-dir :seon.boot/log-dir
    max-bytes :seon.config.maintenance/log-max-bytes
    retained :seon.config.maintenance/log-retained-files}]
  (attempt
   #(let [log-file (io/file log-dir "seon.log")
          log-path (.getCanonicalPath log-file)
          before (if (.isFile log-file) (.length log-file) 0)
          rotate? (> before max-bytes)]
      (when rotate?
        (doseq [index (range retained 1 -1)]
          (let [from (io/file (archive-path log-path (dec index)))
                to (io/file (archive-path log-path index))]
            (when (.isFile from)
              (Files/move (.toPath from) (.toPath to)
                          (into-array java.nio.file.CopyOption
                                      [StandardCopyOption/REPLACE_EXISTING])))))
        (when (pos? retained)
          (Files/copy (.toPath log-file)
                      (.toPath (io/file (archive-path log-path 1)))
                      (into-array java.nio.file.CopyOption
                                  [StandardCopyOption/REPLACE_EXISTING])))
        ;; The detached process keeps this inode open. Truncating it bounds
        ;; future writes immediately; renaming it would strand the live fd.
        (with-open [file (RandomAccessFile. log-file "rw")]
          (.setLength file 0)))
      {:seon.operator.log/path log-path
       :seon.operator.log/bytes-before before
       :seon.operator.log/bytes-after (if (.isFile log-file)
                                        (.length log-file) 0)
       :seon.operator.log/rotated? rotate?
       :seon.operator.log/retained-files retained})))


(defn- store-dir
  {:malli/schema [:=> [:cat :string] :string]}
  [managed-root]
  (.getCanonicalPath
   (io/file managed-root "data" "store")))

(defn- valid-store?
  {:malli/schema [:=> [:cat [:or :nil :map]] :boolean]}
  [value]
  (boolean (and (map? value)
                (some-> ^java.nio.channels.FileLock (:seon.store/lock value)
                        .isValid))))

(defn- acquire-operation-store!
  {:malli/schema [:=> [:cat :string [:or :nil :seon.store/store]] [:tuple :seon.store/store :boolean]]}
  [managed-root supplied]
  (let [path (store-dir managed-root)
        held (some-> (get @runtime/root-store-holder path)
                     :seon.store/store)]
    (cond
      (valid-store? supplied) [supplied false]
      (valid-store? held) [held false]
      :else [(store/open-store! {:seon.store/dir path}) true])))


(defn konserve-store?
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value] (satisfies? konserve.protocols/PEDNKeyValueStore value))

(defn- operation-konserve
  {:malli/schema [:=> [:cat :seon.store/store] [:fn seon.maintenance/konserve-store?]]}
  [operation-store]
  (:store @(:seon.store/connection-object operation-store)))

(defn- collection-observation
  {:malli/schema [:=> [:cat :seon.store/store] [:map [:seon.operator.collect/objects [:int {:min 0}]] [:seon.operator.collect/bytes [:int {:min 0}]]]]}
  [operation-store]
  {:seon.operator.collect/objects
   (count (k/keys (operation-konserve operation-store) {:sync? true}))
   :seon.operator.collect/bytes
   (:seon.operator.footprint/file-bytes
    (fs/footprint (:seon.store/dir operation-store)))})

(defn- resolves-to-digest?
  {:malli/schema [:=> [:cat [:map-of :keyword :seon.schema/malli-form] :seon.schema/malli-form] :boolean]}
  [forms form]
  (loop [current form
         visited #{}]
    (cond
      (= :seon.blob/digest current) true
      (or (not (keyword? current))
          (contains? visited current)
          (not (contains? forms current))) false
      :else (recur (get forms current) (conj visited current)))))

(defn- digest-attributes
  {:malli/schema [:=> [:cat :seon.db/db] [:vector :qualified-keyword]]}
  [database]
  (let [rows
        (d/q '[:find ?attribute ?form
               :where
               [?schema :seon.schema/key ?attribute]
               [?schema :seon.schema/form ?form]]
             database)
        forms (into {} (map (fn [[schema-key form]]
                              [schema-key (edn/read-string form)]))
                    rows)]
    (into []
          (keep (fn [[attribute form]]
                  (when (resolves-to-digest? forms (edn/read-string form))
                    attribute)))
          rows)))

(defn- branch-digests
  {:malli/schema [:=> [:cat :seon.store/store :seon.store/branch] [:set :seon.blob/digest]]}
  [operation-store branch]
  (let [database (d/branch-as-db
                  (:seon.store/connection-object operation-store) branch)]
    (try
      (let [history-value (db/history database)
            searchable (if ;; PRD 1.3 debt: seon.db/history's :seon.db/error-result includes :seon.error/value.
                           (and (:seon.error/at history-value)
                                (:seon.error/layer history-value)
                                (:seon.error/operation history-value))
                         database
                         history-value)]
        (into #{}
              (mapcat
               (fn [attribute]
                 (d/q '[:find [?digest ...]
                        :in $ ?attribute
                        :where [_ ?attribute ?digest]]
                      searchable attribute)))
              (digest-attributes database)))
      (finally
        (d/release-materialized-db database)))))

(defn- collection-evidence
  {:malli/schema [:=> [:cat :seon.store/store] [:map [:seon.operator.collect/branches :seon.operator.collect/branches] [:seon.operator.collect/digests [:set :seon.blob/digest]]]]}
  [operation-store]
  (let [branches (sort-by str (registry/roster operation-store))]
    {:seon.operator.collect/branches
     (mapv (fn [branch]
             {:seon.store/branch branch
              :seon.source/commit-id
              (registry/branch-commit-id
               {:seon.store/store operation-store
                :seon.store/branch branch})})
           branches)
     :seon.operator.collect/digests
     (into #{} (mapcat #(branch-digests operation-store %)) branches)}))

(defn- branch-reopens?
  {:malli/schema [:=> [:cat :seon.store/store :seon.store/branch [:or :nil :seon.source/commit-id]] :boolean]}
  [operation-store branch expected]
  (boolean
   (when expected
     (let [database
           (d/branch-as-db
            (:seon.store/connection-object operation-store) branch)]
       (try
         (= expected (d/commit-id database))
         (finally
           (d/release-materialized-db database)))))))

(defn- digest-reads?
  {:malli/schema [:=> [:cat :seon.store/store :seon.blob/digest] :boolean]}
  [operation-store digest]
  (true?
   (k/bget (operation-konserve operation-store)
           digest
           (fn [{input :input-stream}]
             ;; Force one physical read. EOF is a valid empty blob, so
             ;; successful callback entry—not a positive byte—is proof.
             (.read ^java.io.InputStream input)
             true)
           {:sync? true})))

(defn- konserve-key-set
  {:malli/schema [:=> [:cat :seon.store/store] [:set :seon.schema/value]]}
  [operation-store]
  (into #{} (map :key) (k/keys (operation-konserve operation-store)
                               {:sync? true})))

(defn- root-verification
  "Verify every recorded root and NAME the first one that fails.

  This is the collection's completeness criterion, and the only one: a
  collection is complete when every roster branch reopens at its recorded
  commit ID and every referenced blob this store HELD BEFORE the sweep still
  reads physically. It answers a value rather than a bare boolean so the
  refusal can say WHICH root — the branch, or the digest — was not preserved.

  It is evaluated unconditionally. Conjoined behind a second-pass fixed
  point, `and` short-circuited and this check never ran, while the refusal's
  message still told the caller that root preservation had been checked and
  had failed.

  `held-before` is what keeps the criterion a DIFFERENTIAL rather than a
  shape test. Referenced digests are derived from every attribute whose
  schema resolves to `:seon.blob/digest`, and some of those attributes carry
  content digests that were never konserve keys at all —
  `:seon.db/read-result-digest` is one, measured on a freshly forked cluster
  on 2026-09-17, where it made this check refuse a collection that had lost
  nothing. A digest this store did not hold before the collection is not a
  root the collection lost: it is counted as
  `:seon.operator.collect/unstored-digests`, never silently dropped and never
  a refusal here."
  {:malli/schema [:=> [:cat :seon.store/store [:map [:seon.operator.collect/branches :seon.operator.collect/branches] [:seon.operator.collect/digests [:set :seon.blob/digest]]] [:set :seon.schema/value]] [:map [:seon.operator.collect/roots-verified? :boolean] [:seon.operator.collect/unstored-digests [:int {:min 0}]] [:seon.operator.collect/unverified-branch {:optional true} :seon.store/branch] [:seon.operator.collect/unverified-digest {:optional true} :seon.blob/digest]]]}
  [operation-store evidence held-before]
  (let [digests (:seon.operator.collect/digests evidence)
        stored (filterv held-before digests)
        unverified-branch
        (some (fn [{branch :seon.store/branch
                    expected :seon.source/commit-id}]
                (when-not (branch-reopens? operation-store branch expected)
                  branch))
              (:seon.operator.collect/branches evidence))
        unverified-digest
        (when-not unverified-branch
          (some (fn [digest]
                  (when-not (digest-reads? operation-store digest) digest))
                stored))]
    (cond-> {:seon.operator.collect/roots-verified?
             (not (or unverified-branch unverified-digest))
             :seon.operator.collect/unstored-digests
             (- (count digests) (count stored))}
      unverified-branch
      (assoc :seon.operator.collect/unverified-branch unverified-branch)

      unverified-digest
      (assoc :seon.operator.collect/unverified-digest unverified-digest))))

(defn- unverified-root-clause
  {:malli/schema [:=> [:cat [:map [:seon.operator.collect/unverified-branch {:optional true} :seon.store/branch] [:seon.operator.collect/unverified-digest {:optional true} :seon.blob/digest]]] :string]}
  [result]
  (let [branch (:seon.operator.collect/unverified-branch result)
        digest (:seon.operator.collect/unverified-digest result)]
    (cond
      branch (str " Branch " branch
                  " did not reopen at its recorded commit ID.")
      digest (str " Referenced blob digest " digest " did not read.")
      :else "")))

(defn- incomplete-collection!
  {:malli/schema [:=> [:cat :seon.operator.collect/result [:or :nil :seon.error/throwable]] :nil]}
  [result failure]
  (throw
   (ex-info
    (if failure
      (str "Collection failed before it could verify every recorded root: "
           (ex-message failure))
      (str "Collection did not preserve and verify every recorded root."
           (unverified-root-clause result)))
    {:seon.error/at (java.util.Date.)
         :seon.error/layer ::operation
         :seon.error/operation 'seon.maintenance/incomplete-collection!
         :seon.error/message "Collection must preserve and verify every recorded root."
         :seon.error/offending result
         :seon.operator.collect/result result
         :seon.error/expected :seon.operator.collect/roots-verified?}
    failure)))

(defn- inventory-facts
  "Project one registry inventory into the collection result's own keys.

  A number the inventory does not carry is ABSENT here, never a stored nil."
  {:malli/schema [:=> [:cat :seon.cluster.registry/inventory] [:map [:seon.operator.collect/retained-files {:optional true} :int] [:seon.operator.collect/candidate-files {:optional true} :int] [:seon.operator.collect/candidate-bytes {:optional true} :int] [:seon.operator.collect/mark-duration-ms {:optional true} :int]]]}
  [inventory]
  (into {}
        (keep (fn [[from to]]
                (when-let [value (get inventory from)] [to value])))
        {:seon.cluster.registry/retained-files
         :seon.operator.collect/retained-files
         :seon.cluster.registry/candidate-files
         :seon.operator.collect/candidate-files
         :seon.cluster.registry/candidate-bytes
         :seon.operator.collect/candidate-bytes
         :seon.cluster.registry/mark-duration-ms
         :seon.operator.collect/mark-duration-ms}))

(def ^:private projected-delete-ms-per-file
  ;; The sealed runbook's middle projection: one existence check, delete, and
  ;; FileStore sync per candidate at an illustrative five milliseconds.
  5)

(defn- dry-run-store!
  {:malli/schema [:=> [:cat :string :seon.store/store] :seon.operator.collect/result]}
  [managed-root operation-store]
  (let [inventory
        (registry/collect!
         operation-store
         (java.util.Date.)
         {:seon.operator.collect/dry-run? true})
        retained (:seon.cluster.registry/retained-files inventory)
        candidates (:seon.cluster.registry/candidate-files inventory)
        files (+ retained candidates)
        file-bytes (:seon.cluster.registry/file-bytes inventory)
        mark-duration (:seon.cluster.registry/mark-duration-ms inventory)
        verification
        (root-verification
         operation-store (collection-evidence operation-store)
         (konserve-key-set operation-store))
        result
        (merge
         (inventory-facts inventory)
         verification
         {:seon.operator.collect/store-id
          (get-in @(:seon.store/connection-object operation-store)
                  [:config :store :id])
          :seon.operator.collect/managed-root managed-root
          :seon.operator.collect/branches
          (:seon.cluster.registry/branches inventory)
          :seon.operator.collect/objects-before files
          :seon.operator.collect/objects-after files
          :seon.operator.collect/swept-objects 0
          :seon.operator.collect/bytes-before file-bytes
          :seon.operator.collect/bytes-after file-bytes
          :seon.operator.collect/reclaimed-bytes 0
          :seon.operator.collect/verification-pass-swept 0
          :seon.operator.collect/complete?
          (:seon.operator.collect/roots-verified? verification)
          :seon.operator.collect/dry-run? true
          :seon.operator.collect/projected-duration-ms
          (+ mark-duration (* projected-delete-ms-per-file candidates))})]
    ;; A dry run deletes nothing, so it cannot damage a root — but it reads
    ;; every one, and a root that does not reopen is already lost. Reporting
    ;; the candidate inventory of a store that cannot answer for its own roots
    ;; would be the same absence-as-health the real path just stopped doing.
    (if (:seon.operator.collect/roots-verified? verification)
      result
      (incomplete-collection! result nil))))

(defn- collect-store!
  {:malli/schema [:=> [:cat :string :seon.store/store] :seon.operator.collect/result]}
  [managed-root operation-store]
  (let [store-id
        (get-in @(:seon.store/connection-object operation-store)
                [:config :store :id])
        before (collection-observation operation-store)
        ;; The roots this store HELD before anything was swept. Verification
        ;; is a differential against this set, never a shape test over
        ;; whatever the schema says looks like a digest.
        held-before (konserve-key-set operation-store)
        base-result
        {:seon.operator.collect/store-id store-id
         :seon.operator.collect/managed-root managed-root
         :seon.operator.collect/branches []
         :seon.operator.collect/objects-before
         (:seon.operator.collect/objects before)
         :seon.operator.collect/objects-after
         (:seon.operator.collect/objects before)
         :seon.operator.collect/swept-objects 0
         :seon.operator.collect/bytes-before
         (:seon.operator.collect/bytes before)
         :seon.operator.collect/bytes-after
         (:seon.operator.collect/bytes before)
         :seon.operator.collect/reclaimed-bytes 0
         :seon.operator.collect/verification-pass-swept 0
         :seon.operator.collect/roots-verified? false
         :seon.operator.collect/complete? false}]
    (try
      (let [inventory
            (registry/collect! operation-store (java.util.Date.) {})
            swept (:seon.cluster.registry/swept inventory)
            after-first (collection-observation operation-store)
            first-result
            (merge
             (inventory-facts inventory)
             (assoc base-result
                    :seon.operator.collect/objects-after
                    (:seon.operator.collect/objects after-first)
                    :seon.operator.collect/swept-objects swept
                    :seon.operator.collect/bytes-after
                    (:seon.operator.collect/bytes after-first)
                    :seon.operator.collect/reclaimed-bytes
                    (max 0 (- (:seon.operator.collect/bytes before)
                              (:seon.operator.collect/bytes after-first)))))]
        (try
          (let [verification-swept
                (registry/collect! operation-store (java.util.Date.))
                after (collection-observation operation-store)
                evidence (collection-evidence operation-store)
                verification
                (root-verification operation-store evidence held-before)
                complete?
                (:seon.operator.collect/roots-verified? verification)
                result
                (merge
                 (assoc first-result
                        :seon.operator.collect/branches
                        (:seon.operator.collect/branches evidence)
                        :seon.operator.collect/objects-after
                        (:seon.operator.collect/objects after)
                        :seon.operator.collect/bytes-after
                        (:seon.operator.collect/bytes after)
                        :seon.operator.collect/reclaimed-bytes
                        (max 0 (- (:seon.operator.collect/bytes before)
                                  (:seon.operator.collect/bytes after)))
                        :seon.operator.collect/verification-pass-swept
                        verification-swept
                        :seon.operator.collect/complete? complete?)
                 verification)]
            (if complete?
              result
              (incomplete-collection! result nil)))
          (catch Throwable failure
            (if (:seon.operator.collect/result (ex-data failure))
              (throw failure)
              (incomplete-collection! first-result failure)))))
      (catch Throwable failure
        (if (:seon.operator.collect/result (ex-data failure))
          (throw failure)
          (incomplete-collection! base-result failure))))))

(def ^:private documented-request-keys
  "The keys `collect!` consults, and therefore the ones it documents.

  This is the ONE place they are named: the entry point reads its options
  through this set, and the misspelling check below derives its family from
  the same set, so the two can never disagree. It is not a mirror of the
  declaration either —
  `seon.maintenance-test/the-documented-collection-request-keys-are-the-declared-ones`
  fails on drift against `:seon.operator.collect/request` in
  `resources/seon/schemas/`.

  It is a value rather than a read of the authored schema population at call
  time. Reading that population here made a collection fail because ANOTHER
  namespace's declaration sat in the wrong resource file — an entry point
  fetching its world at call time, which §2.1 exists to prevent."
  #{:seon.operator/repository-root
    :seon.operator/managed-root
    :seon.operator.collect/dry-run?})

(defn- refuse-misspelled-options!
  "Refuse a request key carrying a documented key's name in another namespace.

  Datahike's `gc-storage!` IGNORES option keys it does not know
  (`datahike/gc.cljc`), so `{:dry-run? true}` — the unqualified spelling of
  `:seon.operator.collect/dry-run?` — reached this entry point, was consulted
  by nobody, and performed a REAL collection on `default` once. A silently
  ignored option is the absence-as-health class with the widest possible blast
  radius, so the one Seon entry point names it.

  Maps stay OPEN (§2.5): a key with an unrelated name is ordinary extra data
  and is ignored, which is what the scheduler's merged maintenance request
  needs. What is refused is only a key that means to be a documented one."
  {:malli/schema [:=> [:cat :seon.operator.collect/request] :seon.operator.collect/request]}
  [request]
  (let [by-name (into {} (map (juxt name identity)) documented-request-keys)]
    (doseq [supplied (keys request)
            :when (and (keyword? supplied)
                       (not (contains? documented-request-keys supplied)))
            :let [declared (get by-name (name supplied))]
            :when declared]
      (throw
       (ex-info
        (str "Collection option " supplied " is not a request key. "
             "Did you mean " declared "?")
        {:seon.error/at (java.util.Date.)
         :seon.error/layer ::operation
         :seon.error/operation 'seon.maintenance/refuse-misspelled-options!
         :seon.error/message "The supplied option uses the wrong namespace; use its declared key."
         :seon.error/offending supplied
         :seon.operator.collect/option-key supplied
         :seon.error/expected declared}))))
  request)


(defn collect!
  "Collect the held store and verify every retained branch and blob."
  {:malli/schema [:=> [:cat :seon.operator.collect/request]
                      [:or :seon.operator.collect/result :seon.operator/failed-error]]}
  [{root :seon.operator/managed-root :as request}]
  (attempt
   #(let [_ (refuse-misspelled-options! request)
          root (.getCanonicalPath (io/file root))
          [held release?] (acquire-operation-store! root nil)]
      (try
        (if (:seon.operator.collect/dry-run? request)
          (dry-run-store! root held)
          (collect-store! root held))
        (finally (when release? (store/release-store! held)))))))

(defn observe-footprint!
  "Measure only this managed data directory; the schedule records the result."
  {:malli/schema [:=> [:cat :seon.operator/footprint-request]
                      [:or :seon.operator/footprint-observation :seon.operator/failed-error]]}
  [{root :seon.operator/managed-root :as request}]
  (attempt
   #(let [observation (fs/footprint (str (io/file root "data")))]
      (assoc observation :seon.operator/low-space?
             (boolean
              (or (when-let [minimum (:seon.config.maintenance/min-usable-bytes request)]
                    (< (:seon.operator.footprint/usable-bytes observation) minimum))
                  (when-let [minimum (:seon.config.maintenance/min-usable-ratio request)]
                    (< (:seon.operator.footprint/usable-ratio observation) minimum))))))))
