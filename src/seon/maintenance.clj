(ns seon.maintenance
  "Fact-derived maintenance result projection and reporting."
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.error :as error]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [malli.core :as m]
            [malli.registry :as mr]))

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
      (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.maintenance/projection
        :seon.error/operation 'seon.maintenance/result-entity
        :seon.error/message "Maintenance requires one matching persistence producer."
        :seon.error/diagnostic-layer :seon.maintenance/projection
        :seon.error/diagnostic-operation 'seon.maintenance/result-entity
        :seon.error/diagnostic-member :seon.maintenance/matching-producer-count
        :seon.error/diagnostic-expected "one matching persistence producer"
        :seon.error/diagnostic-offending projections
        :seon.error/offending projections
        :seon.error/diagnostic-cause :seon.maintenance/projection-refused
        :seon.error/diagnostic-evidence {}
        :seon.maintenance/matching-producer-count (count projections)})

      :else
      (let [[schema-key projection] (first projections)]
        (try
          (if-let [producer (requiring-resolve projection)]
            (producer result)
            (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.maintenance/projection
        :seon.error/operation 'seon.maintenance/result-entity
        :seon.error/message "Maintenance requires a resolving persistence producer."
        :seon.error/diagnostic-layer :seon.maintenance/projection
        :seon.error/diagnostic-operation 'seon.maintenance/result-entity
        :seon.error/diagnostic-member :seon.maintenance/unresolved-producer
        :seon.error/diagnostic-expected "a resolving persistence producer"
        :seon.error/diagnostic-offending {:seon.schema/key schema-key :seon.maintenance/result-projection projection}
        :seon.error/offending {:seon.schema/key schema-key :seon.maintenance/result-projection projection}
        :seon.error/diagnostic-cause :seon.maintenance/projection-refused
        :seon.error/diagnostic-evidence {}
        :seon.maintenance/unresolved-producer projection}))
          (catch Throwable cause
            (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.maintenance/projection
        :seon.error/operation 'seon.maintenance/result-entity
        :seon.error/message "Maintenance requires a successful persistence projection."
        :seon.error/diagnostic-layer :seon.maintenance/projection
        :seon.error/diagnostic-operation 'seon.maintenance/result-entity
        :seon.error/diagnostic-member :seon.maintenance/failed-producer
        :seon.error/diagnostic-expected "a successful persistence projection"
        :seon.error/diagnostic-offending {:seon.schema/key schema-key :seon.maintenance/result-projection projection :seon.error/exception cause}
        :seon.error/offending {:seon.schema/key schema-key :seon.maintenance/result-projection projection :seon.error/exception cause}
        :seon.error/diagnostic-cause :seon.maintenance/projection-refused
        :seon.error/diagnostic-evidence {}
        :seon.maintenance/failed-producer projection})))))))

(defn- process-identity
  [process]
  (select-keys process
               [:seon.dev.process/generation
                :seon.dev.process/pid
                :seon.dev.process/start-instant
                :seon.dev.process/root]))

(defn- process-observation
  [process]
  (select-keys process
               [:seon.dev.process/generation
                :seon.dev.process/pid
                :seon.dev.process/start-instant
                :seon.dev.process/root
                :seon.operator.process-census/alive?
                :seon.operator.process-census/responsive?
                :seon.operator.process-census/advertisements]))

(defn- root-claim
  [claim]
  (let [creator (:seon.operator.claim/creator claim)]
    {:seon.maintenance.result/root-claim-id
     (:seon.operator.claim/id claim)
     :seon.maintenance.result/root-claim-path
     (:seon.operator.claim/root claim)
     :seon.maintenance.result/root-claim-creator-pid
     (:seon.dev.process/pid creator)
     :seon.maintenance.result/root-claim-creator-start-instant
     (:seon.dev.process/start-instant creator)
     :seon.maintenance.result/root-claim-reap-on-owner-exit?
     (:seon.operator.claim/reap-on-owner-exit? claim)}))

(defn- claim-error
  [error]
  (cond-> (select-keys error [:seon.error/at :seon.error/layer
                             :seon.error/operation :seon.error/message])
    (get-in error [:seon.error/data :seon.operator.claim/path])
    (assoc :seon.operator.claim/path
           (get-in error [:seon.error/data :seon.operator.claim/path]))))

(defn project-process-census-result
  "Project one public census value into queryable component facts."
  {:malli/schema
   [:=> [:cat :seon.operator.process-census/result]
    :seon.maintenance.result/value]}
  [result]
  {:seon.operator.process-census/observed-at
   (:seon.operator.process-census/observed-at result)
   :seon.operator.process-census/complete?
   (:seon.operator.process-census/complete? result)
   :seon.maintenance.result/process-census-roots
   (mapv root-claim (:seon.operator.process-census/roots result))
   :seon.maintenance.result/process-census-processes
   (mapv process-observation
         (:seon.operator.process-census/processes result))
   :seon.maintenance.result/process-census-dead
   (mapv process-identity (:seon.operator.process-census/dead result))
   :seon.maintenance.result/process-census-unresponsive
   (mapv process-identity
         (:seon.operator.process-census/unresponsive result))
   :seon.maintenance.result/process-census-unclaimed
   (mapv process-identity
         (:seon.operator.process-census/unclaimed result))
   :seon.maintenance.result/process-census-claim-errors
   (mapv claim-error (:seon.operator.process-census/claim-errors result))})

(defn project-reap-result
  "Project one public reap value into queryable component facts."
  {:malli/schema
   [:=> [:cat :seon.operator.reap/result]
    :seon.maintenance.result/value]}
  [result]
  {:seon.operator.reap/observed-at
   (:seon.operator.reap/observed-at result)
   :seon.maintenance.result/reap-census
   (project-process-census-result (:seon.operator.reap/census result))
   :seon.operator.reap/eligible-root-claims
   (:seon.operator.reap/eligible-root-claims result)
   :seon.maintenance.result/reap-stopped-processes
   (mapv #(select-keys %
                       [:seon.dev.process/generation
                        :seon.dev.process/pid
                        :seon.dev.process/start-instant
                        :seon.operator.reap/stop-path])
         (:seon.operator.reap/stopped-processes result))
   :seon.maintenance.result/reap-roots
   (mapv #(select-keys %
                       [:seon.operator.claim/id
                        :seon.operator.claim/root
                        :seon.operator.cleanup/reclaimed-bytes])
         (:seon.operator.reap/roots result))
   :seon.maintenance.result/reap-refused
   (mapv #(select-keys %
                       [:seon.operator.claim/id
                        :seon.operator.reap/reason
                        :seon.error/message])
         (:seon.operator.reap/refused result))
   :seon.operator.reap/reclaimed-bytes
   (:seon.operator.reap/reclaimed-bytes result)
   :seon.operator.reap/complete?
   (:seon.operator.reap/complete? result)})

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

  `seon.operator/collect-store!` returns its verified collect result or
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
      (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.maintenance/collection
        :seon.error/operation 'seon.maintenance/last-collection-in
        :seon.maintenance/uncollected-root managed-root
        :seon.error/offending managed-root
        :seon.error/message
        "No completed maintenance receipt records a collection of this root."
        :seon.error/diagnostic-layer :seon.maintenance
        :seon.error/diagnostic-operation 'seon.maintenance/last-collection-in
        :seon.error/diagnostic-member :seon.operator/managed-root
        :seon.error/diagnostic-expected :seon.operator.collect/managed-root
        :seon.error/diagnostic-offending managed-root
        :seon.error/diagnostic-cause :seon.db/not-found
        :seon.error/diagnostic-evidence
        [:seon.operator.collect/managed-root managed-root]}))))

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
