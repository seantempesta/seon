(ns seon.maintenance
  "Fact-derived maintenance result projection and reporting."
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]))

(schema.edn/load! {})

(defn- error-value
  [kind message data]
  {kind true
   :seon.error/kind kind
   :seon.error/message message
   :seon.error/data data})

(defn- result-projections
  [result]
  ;; ONE declaration population for the whole scan. The population is already
  ;; in hand from `registered-schemas`; asking `valid-candidate-value?` with
  ;; the ambient arity threw it away and re-read all 152 schema resources per
  ;; candidate key (2026-08-07).
  (let [forms (schema/registered-schemas)]
    (->> forms
         (keep (fn [[schema-key definition]]
                 (when-let [projection
                            (:seon.maintenance/result-projection
                             (schema.form/namespaced-properties definition))]
                   (when (schema/valid-candidate-value?
                          forms schema-key result)
                     [schema-key projection]))))
         (sort-by (comp str first))
         vec)))

(defn result-entity
  "Project an operation result through its declared persistence producer."
  {:malli/schema
   [:=> [:cat :seon.maintenance/result-entity-request]
    :seon.maintenance/result-entity-response]}
  [result]
  (let [projections (result-projections result)]
    (cond
      (empty? projections) result

      (< 1 (count projections))
      (error-value
       ::ambiguous-result-projection
       "The maintenance result matches several persistence producers."
       {:seon.maintenance/result-projections projections})

      :else
      (let [[schema-key projection] (first projections)]
        (try
          (if-let [producer (requiring-resolve projection)]
            (producer result)
            (error-value
             ::result-projection-unresolved
             "The declared maintenance result producer does not resolve."
             {:seon.schema/key schema-key
              :seon.maintenance/result-projection projection}))
          (catch Throwable cause
            (error-value
             ::result-projection-failed
             (or (ex-message cause)
                 "The maintenance result producer failed.")
             {:seon.schema/key schema-key
              :seon.maintenance/result-projection projection
              :seon.maintenance/exception-class
              (.getName (class cause))})))))))

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
  (cond-> {:seon.error/message (:seon.error/message error)}
    (:seon.error/kind error)
    (assoc :seon.error/kind (:seon.error/kind error))
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
             (select-keys
              (:seon.operator.cluster-cleanup/collection result)
              [:seon.error/kind :seon.error/message]))))

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

(def ^:private receipt-pull
  '[*
    {:seon.maintenance.receipt/result [*]}
    {:seon.maintenance.receipt/error [*]}])

(defn- task-rows
  [database]
  (->> (db/q '[:find ?task ?task-id ?function
               :in $
               :where
               [?owner :seon.cluster.agent/id "root"]
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
    [:=> [:cat] [:or :seon.maintenance/report :seon.error/value]]
    [:=> [:cat :seon.db/database-value] :seon.maintenance/report]]}
  ([]
   (let [database (db/db)]
     (if (:seon.error/kind database)
       database
       (report-in database))))
  ([database]
   (report-in database)))

(defn- attention-rules
  []
  (->> (schema/registered-schemas)
       (keep (fn [[attribute definition]]
               (when-let [rule
                          (:seon.maintenance/attention-when
                           (schema.form/attr-form-properties definition))]
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
  [entry]
  (let [receipt (:seon.maintenance/receipt-facts entry)
        result (:seon.maintenance/result-facts entry)
        error (:seon.maintenance/error-facts entry)]
    (cond
      (nil? receipt) :not-run
      error :error
      (:seon.maintenance.receipt/interrupted-at receipt) :interrupted
      (nil? result) :unterminated
      :else (some #(when (rule-triggered? result %) %) (attention-rules)))))

(defn- succeeded?
  [entry]
  (nil? (entry-attention entry)))

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
  [entry]
  (let [attention (entry-attention entry)
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
  [report-value]
  (let [entries (:seon.maintenance/entries report-value)
        ran (filter :seon.maintenance/receipt-facts entries)
        succeeded (count (filter succeeded? entries))
        attention (remove succeeded? entries)]
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
            (map attention-detail)
            attention))))

(defn render-report-ai
  "`:seon.render/ai` — root's concise latest maintenance report."
  {:malli/schema [:=> [:cat :seon.maintenance/report] [:string {:min 1}]]}
  [report-value]
  (str/join "\n" (report-lines report-value)))

(defn render-report-html
  "`:seon.render/html` — root's latest maintenance report card."
  {:malli/schema
   [:=> [:cat :seon.maintenance/report] :seon.render/hiccup]}
  [report-value]
  (let [[summary & details] (report-lines report-value)]
    (cond-> [:article {:class "seon-family-entry seon-maintenance-entry"}
             [:h3 "Maintenance"]
             [:p summary]]
      (seq details)
      (conj (into [:ul {:class "seon-maintenance-attention"}]
                  (map (fn [detail] [:li detail]))
                  details)))))
