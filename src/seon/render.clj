(ns seon.render
  "The one typed projection selector and guarded SCI invocation boundary.

  AI and HTML each follow the same ordered chain: an explicit producer on the
  value, the unique contract-fitting function in the value's explicitly owned
  namespace, a matching schema's declared property, then the prepared value
  floor. Zero matches is ordinary. Multiple matches are one deterministic flat
  error. Selected failures do not fall through to another producer.

  Every selected qualified symbol resolves to the live SCI Var in the cluster
  context and executes through `seon.sci.kernel`; there is no compiled renderer
  lane. A redefinition therefore changes the next call and a cold context
  re-derives the same symbol from its database program row."
  (:require [clojure.core.async :as async]
            [seon.await :as await]
            [seon.ai.tokens :as tokens]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.print :as print]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]
            [seon.sci.admit :as admit]
            [seon.sci.kernel :as sci.kernel])
  (:import [java.util Date]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Contract-derived renderer selection and guarded invocation
;;; ---------------------------------------------------------------------------

(defn- render-value
  [request]
  (get request :seon.render/value request))

(defn agent-render-profile
  "Derive the agent generic-value fit profile from effective config facts."
  {:malli/schema
   [:=> [:cat [:or :seon.config/effective
                :seon.config/missing-effective-error]]
    [:or :seon.render.profile/profile
     :seon.config/missing-effective-error]]}
  [effective]
  (if (:seon.config/missing-effective effective)
    effective
    {:seon.render.profile/id :seon.render.profile/agent
     :seon.render.profile/token-budget
     (:seon.config.render.agent/token-budget effective)
     :seon.render.profile/max-depth
     (:seon.config.render.agent/max-depth effective)
     :seon.render.profile/max-children
     (:seon.config.render.agent/max-children effective)
     :seon.render.profile/composition
     (:seon.config.render.agent/composition effective)}))

(def ^:private default-agent-profile
  (agent-render-profile (config/defaults)))

(defn request-profile
  "Return the profile carried by one request, deriving it once when absent."
  {:malli/schema [:=> [:cat :map]
                  [:or :seon.render.profile/profile :seon.error/value]]}
  [request]
  (or (:seon.render/profile request)
      (if (schema/handed-projection)
        (let [database (:seon.db/db request)
              agent-id (:seon.cluster.agent/id request)
              cluster-name
              (when (and database agent-id)
                (db/q '[:find ?cluster-name .
                        :in $ ?agent-id
                        :where
                        [?agent :seon.cluster.agent/id ?agent-id]
                        [?agent :seon.cluster.agent/cluster ?cluster]
                        [?cluster :seon.cluster/name ?cluster-name]]
                      database agent-id))
              effective (when cluster-name
                          (config/effective database cluster-name))]
          (if (:seon.error/kind effective)
            effective
            (or (when effective (agent-render-profile effective))
                default-agent-profile)))
        (error/diagnostic
         {:seon.error/kind ::missing-projection
          :seon.error/message
          "Rendering requires a carried profile or handed projection."
          :seon.error/diagnostic-layer :render
          :seon.error/diagnostic-operation 'seon.render/request-profile
          :seon.error/diagnostic-member :seon.schema/projection
          :seon.error/diagnostic-expected
          [:or :seon.render/profile :seon.schema/handed-projection]
          :seon.error/diagnostic-offending :seon.error/unknown
          :seon.error/diagnostic-cause ::missing-projection
          :seon.error/diagnostic-evidence nil}))))

(defn- target-profile
  [request]
  (let [value (render-value request)
        database (:seon.db/db request)
        identity-requery
        (when (and database (map? value))
          (some (fn [attribute]
                  (when-let [entry (find value attribute)]
                    [attribute (val entry)]))
                (db/identity-attributes database)))]
    (cond-> (request-profile request)
    (:seon.cluster.eval/result-blob request)
    (assoc :seon.print/requery-id
           [:seon.blob/digest (:seon.cluster.eval/result-blob request)])

    (and (nil? (:seon.cluster.eval/result-blob request)) identity-requery)
    (assoc :seon.print/requery-id identity-requery)

    (and (nil? (:seon.cluster.eval/result-blob request))
         (nil? identity-requery)
         (:seon.render.call/id request))
    (assoc :seon.print/requery-id
           [:seon.render.call/id (:seon.render.call/id request)])

    (and (nil? (:seon.cluster.eval/result-blob request))
         (nil? identity-requery)
         (nil? (:seon.render.call/id request)))
    (assoc :seon.print/requery-refusal
           "The rendered value has no stable requery identity."))))

(defn- render-argument
  [request]
  (let [context (select-keys request
                             [:seon.db/db
                              :seon.sci.eval/ctx
                              :seon.cluster.agent/id
                              :seon.cluster.run/id
                              :seon.render.call/id
                              :seon.sci.admit/caps
                              :seon.sci.eval/time-limit-ms
                              :seon.config/on-core-error
                              :seon.db/connection
                              :seon.cluster.eval/result-blob
                              :seon.render.data/total
                              :seon.render/distance
                              :seon.render/profile
                              :seon.render.value/root
                              :seon.render.data/cursor
                              :seon.render.walk/attribute
                              :seon.cluster.run/live-processes
                              :seon.ai/partial
                              ;; the producers already rendering this
                              ;; chain — carried so a producer that
                              ;; delegates its own value onward cannot
                              ;; be selected for it a second time
                              :seon.render/rendering])
        profile (target-profile request)
        value (render-value request)
        context (cond-> context
                  profile (assoc :seon.render/profile profile)
                  (and (counted? value)
                       (not (contains? context :seon.render.data/total)))
                  (assoc :seon.render.data/total (count value)))]
    ;; The floor unit and the value are different data. A floor unit carries
    ;; only qualified render inputs; an arbitrary map remains wholly under
    ;; `:seon.render/value`. Merging a value into this unit made ordinary maps
    ;; with unqualified keys unconstructable at the one total floor.
    (assoc context :seon.render/value value)))

(defn- producer-argument
  [request]
  ;; Existing declared producers accept the qualified attributes of the value
  ;; they render together with render custody. Keep that established contract
  ;; for producer selection and invocation. The universal floor is the sole
  ;; exception below: arbitrary value keys never become its unit keys.
  (let [argument (render-argument request)
        value (:seon.render/value argument)
        producer-value (if (map? value)
                         (cond-> (render.value/transacted
                                  value (:seon.db/db request))
                           (find value :db/id)
                           (assoc :db/id (:db/id value)))
                         value)]
    (if (map? value)
      (assoc (merge producer-value
                    (dissoc argument :seon.render/value
                            :seon.render.call/id))
             :seon.render/value value)
      (dissoc argument :seon.render.call/id))))

(defn- floor-producer?
  [selected]
  (contains? #{'seon.render.value/render-ai
               'seon.render.value/render-html}
             selected))

(defn- namespace-candidates
  "Ordered public-function evidence from the explicit owning namespace.

  The acquired database snapshot bounds candidates by explicit namespace and
  public-function facts. The immutable schema projection then validates the
  complete input and typed output contracts against the actual render argument.
  Results are sorted so database insertion order cannot decide ambiguity."
  [{ctx :seon.sci.eval/ctx
    namespace-name :seon.render/namespace
    output-schema :seon.render/output-schema
    :as request}]
  (if-not namespace-name
    []
    (let [projection (sci.kernel/context-projection ctx)
          argument (producer-argument request)
          symbols (sci.kernel/public-functions-in ctx namespace-name)]
      (into []
            (comp
             (filter #(= namespace-name (symbol (namespace %))))
             (distinct)
             (map
              (fn [candidate]
                (if (schema/function-accepts-and-returns-in?
                     projection candidate [argument] output-schema)
                  {:seon.render.selection.candidate/producer candidate
                   :seon.render.selection.candidate/status :compatible}
                  {:seon.render.selection.candidate/producer candidate
                   :seon.render.selection.candidate/status :rejected
                   :seon.render.selection.candidate/reason
                   :no-same-arity-match}))))
            (sort-by str symbols)))))

(defn- ambiguity
  [namespace-name output candidate-symbols]
  {:seon.error/kind ::ambiguous
   :seon.error/message
   (str "More than one function in " namespace-name
        " accepts this value and returns " output ".")
   :seon.error/data
   {:seon.render/namespace namespace-name
    :seon.render/output output
    :seon.render/candidates (vec candidate-symbols)} :seon.render/ambiguous true})

(defn transacted
  "Restore a pulled entity to the transaction shape used for selection."
  {:malli/schema [:=> [:cat :map] :map]}
  [entity]
  (render.value/transacted entity))

(defn- schema-producers
  [projection request value output]
  (schema/call-with-projection
   projection
   (fn []
     (when (map? value)
       (let [transacted-matches
             (schema/matching-shapes-in
              projection (render.value/transacted value (:seon.db/db request)))
             ;; A pull has two honest shapes. Refs and cardinality-many values
             ;; validate in transaction form, while tuple/vector value attributes
             ;; validate exactly as pulled. A pulled entity admits only shapes
             ;; whose required attributes are database-storable; open request
             ;; envelopes must not acquire a one-key value renderer.
             pulled-matches (schema/matching-shapes-in projection value)
             matches
             (->> (concat transacted-matches pulled-matches)
                  (filter
                   #(or (not (:db/id value))
                        (every?
                         (partial schema.datahike/storable-attribute-in?
                                  projection)
                         (:seon.schema/required-attrs %))))
                  (reduce (fn [by-key row]
                            (assoc by-key (:seon.schema/key row) row))
                          (sorted-map))
                  vals)
             matches
             (filter #(get % output) matches)
             matches
             (if (:db/id value)
               (let [specificity (apply max 0
                                        (map (comp count
                                                   :seon.schema/required-attrs)
                                             matches))]
                 (filter #(= specificity
                             (count (:seon.schema/required-attrs %)))
                         matches))
               matches)
             producers
             (->> matches
                  (map #(get % output))
                  distinct
                  (sort-by str)
                  vec)]
         producers)))))

(defn- schema-producer
  [projection request value output]
  (let [producers (schema-producers projection request value output)]
    (cond
      (= 1 (count producers)) (first producers)
      (> (count producers) 1) (ambiguity nil output producers))))

(defn- attribute-producer
  [projection request output]
  (when-let [attribute (:seon.render.walk/attribute request)]
    (some-> (get-in projection [:seon.schema.projection/forms attribute])
            schema.form/attr-form-properties
            (get output))))

(defn- render-invocation-argument
  "Supply an attribute declaration with that attribute's value."
  [projection request selected]
  (let [attribute (:seon.render.walk/attribute request)
        declared (when attribute
                   (attribute-producer projection request :seon.render/form))]
    (if (= selected declared)
      (get (render.value/transacted (render-value request)
                                    (:seon.db/db request))
           attribute)
      (if (floor-producer? selected)
        (render-argument request)
        (producer-argument request)))))

(defn- declared-producer
  [projection request value output]
  (if (and (= :seon.render/form output)
           (:seon.render.walk/attribute request))
    (or (attribute-producer projection request output)
        'seon.render/render-form)
    (schema-producer projection request value output)))

(def ^:private selection-stage-order
  [:explicit-value :explicit-request :namespace :schema :floor])

(defn- selection-candidate
  [producer]
  {:seon.render.selection.candidate/producer producer
   :seon.render.selection.candidate/status :compatible})

(defn- selection-stage
  ([stage-name status]
   {:seon.render.selection.stage/name stage-name
    :seon.render.selection.stage/status status})
  ([stage-name status candidates]
   (assoc (selection-stage stage-name status)
          :seon.render.selection.stage/candidates (vec candidates)))
  ([stage-name status candidates error]
   (assoc (selection-stage stage-name status candidates)
          :seon.render.selection.stage/error error)))

(defn- selected-stage
  [stage selected]
  {:seon.render.selection/stage stage
   :seon.render.selection/selected selected})

(defn- finish-selection
  [stages selected remaining-stage-names]
  {:seon.render.selection/stages
   (into (vec stages)
         (map #(selection-stage % :not-consulted))
         remaining-stage-names)
   :seon.render.selection/selected selected})

(defn- floor-producer
  [request output]
  (case output
    :seon.render/form 'seon.render/render-form
    :seon.render/html 'seon.render.value/render-html
    (if (:seon.render.call/source-output? request)
      'seon.render/render-default-ai-source
      'seon.render.value/render-ai)))

(defn- explicit-value-stage
  [request output]
  (let [value (render-value request)
        explicit (when (map? value) (find value output))]
    (if explicit
      (selected-stage
       (if (qualified-symbol? (val explicit))
         (selection-stage :explicit-value :selected
                          [(selection-candidate (val explicit))])
         (assoc (selection-stage :explicit-value :selected)
                :seon.render.selection.stage/value (val explicit)))
       (val explicit))
      {:seon.render.selection/stage
       (selection-stage :explicit-value :no-match)})))

(defn- explicit-request-stage
  [request output]
  (if-let [explicit (get request output)]
    (selected-stage
     (selection-stage :explicit-request :selected
                      [(selection-candidate explicit)])
     explicit)
    {:seon.render.selection/stage
     (selection-stage :explicit-request :no-match)}))

(defn- namespace-stage
  [request output output-schema]
  (let [evidence
        (namespace-candidates
         (assoc request :seon.render/output-schema output-schema))
        compatible
        (into []
              (comp
               (filter #(= :compatible
                           (:seon.render.selection.candidate/status %)))
               (map :seon.render.selection.candidate/producer))
              evidence)
        selection-error (when (> (count compatible) 1)
                          (ambiguity (:seon.render/namespace request) output
                                     (mapv str compatible)))
        status (cond selection-error :ambiguous
                     (seq compatible) :selected
                     :else :no-match)
        stage (if selection-error
                (selection-stage :namespace status evidence selection-error)
                (selection-stage :namespace status evidence))]
    (cond
      selection-error (selected-stage stage selection-error)
      (= 1 (count compatible)) (selected-stage stage (first compatible))
      :else {:seon.render.selection/stage stage})))

(defn- schema-stage
  [request projection value output]
  (let [producers
        (if (and (= :seon.render/form output)
                 (:seon.render.walk/attribute request))
          [(or (attribute-producer projection request output)
               'seon.render/render-form)]
          (or (schema-producers projection request value output) []))
        selection-error (when (> (count producers) 1)
                          (ambiguity nil output producers))
        status (cond selection-error :ambiguous
                     (seq producers) :selected
                     :else :no-match)
        evidence (mapv selection-candidate producers)
        stage (if selection-error
                (selection-stage :schema status evidence selection-error)
                (selection-stage :schema status evidence))]
    (cond
      selection-error (selected-stage stage selection-error)
      (= 1 (count producers)) (selected-stage stage (first producers))
      :else {:seon.render.selection/stage stage})))

(defn- selection-stage-result
  [stage-name request projection value output output-schema]
  (case stage-name
    :explicit-value (explicit-value-stage request output)
    :explicit-request (explicit-request-stage request output)
    :namespace (namespace-stage request output output-schema)
    :schema (schema-stage request projection value output)
    :floor (let [selected (floor-producer request output)]
             (selected-stage
              (selection-stage :floor :selected
                               [(selection-candidate selected)])
              selected))))

(defn selection
  "Explain and return the exact producer decision used for one render call."
  {:malli/schema
   [:=> [:catn [::request :seon.render/selection-request]]
    :seon.render/selection]}
  [{ctx :seon.sci.eval/ctx
    output :seon.render/output
    :as request}]
  (let [profile (request-profile request)]
    (if (:seon.error/kind profile)
      (finish-selection [] profile selection-stage-order)
      (let [request (assoc request :seon.render/profile profile)
            value (render-value request)
            projection (sci.kernel/context-projection ctx)
            output-schema (case output
                            :seon.render/ai :seon.render/ai
                            :seon.render/html :seon.render/hiccup
                            :seon.render/form :seon.render/form)]
        (loop [stage-names selection-stage-order
               stages []]
          (let [stage-name (first stage-names)
                result (selection-stage-result
                        stage-name request projection value output
                        output-schema)
                stages (conj stages (:seon.render.selection/stage result))]
            (if-let [selected (find result :seon.render.selection/selected)]
              (finish-selection stages (val selected) (rest stage-names))
              (recur (rest stage-names) stages))))))))

(defn selection-inspection
  "Inspect every priority stage while preserving the actual decision."
  {:malli/schema
   [:=> [:catn [::request :seon.render/selection-request]]
    :seon.render/selection]}
  [{ctx :seon.sci.eval/ctx
    output :seon.render/output
    :as request}]
  (let [decision (selection request)
        profile (request-profile request)]
    (if (:seon.error/kind profile)
      decision
      (let [request (assoc request :seon.render/profile profile)
            value (render-value request)
            projection (sci.kernel/context-projection ctx)
            output-schema (case output
                            :seon.render/ai :seon.render/ai
                            :seon.render/html :seon.render/hiccup
                            :seon.render/form :seon.render/form)
            stages (mapv (fn [stage-name]
                           (:seon.render.selection/stage
                            (selection-stage-result
                             stage-name request projection value output
                             output-schema)))
                         selection-stage-order)
            actual-stages (:seon.render.selection/stages decision)]
        (assoc decision :seon.render.selection/stages
               (mapv (fn [stage actual]
                       (if (= :not-consulted
                              (:seon.render.selection.stage/status actual))
                         (assoc stage :seon.render.selection.stage/status
                                :not-consulted)
                         actual))
                     stages actual-stages))))))

(defn- producer
  [request output _output-schema]
  (:seon.render.selection/selected
   (selection (assoc request :seon.render/output output))))

(defn- invocation-argument-evidence
  [projection request selected]
  (let [argument (render-invocation-argument projection request selected)
        value (when (map? argument) (:seon.render/value argument))]
    (cond-> (if (map? argument)
              (dissoc argument
                      :seon.db/db
                      :seon.sci.eval/ctx
                      :seon.db/connection)
              argument)
      (map? value)
      (assoc :seon.render/value (dissoc value :seon.db/db)))))

(defn- call-static-evidence
  [request decision selected]
  (let [
        projection (sci.kernel/context-projection (:seon.sci.eval/ctx request))
        argument (invocation-argument-evidence projection request selected)]
    {:seon.render/selection decision
     :seon.render.call/producer selected
     :seon.render/would-fall-to-floor? (floor-producer? selected)
     :seon.render.call/declaration-row
     (sci.kernel/program-function (:seon.sci.eval/ctx request) selected)
     :seon.render.call/argument argument}))

(defn- compatible-selection-candidate?
  [decision candidate-producer]
  (some (fn [stage]
          (some #(and (= candidate-producer
                         (:seon.render.selection.candidate/producer %))
                      (= :compatible
                         (:seon.render.selection.candidate/status %)))
                (:seon.render.selection.stage/candidates stage)))
        (:seon.render.selection/stages decision)))

(defn- call-cache-evidence
  [request selected]
  (let [ctx (:seon.sci.eval/ctx request)
        projection (sci.kernel/context-projection ctx)]
    {::program-snapshot
     (some-> (:seon.sci.kernel/program-snapshot ctx) deref)
     ::projection projection
     ::selection-input
     [selected
      (:seon.render/output request)
      (invocation-argument-evidence projection request selected)
      (select-keys request [:seon.sci.admit/caps
                            :seon.sci.eval/time-limit-ms
                            :seon.config/on-core-error])]}))

(declare same-call-cache-evidence?)

(defn- invocation-cache-key
  [request selected evidence]
  [selected
   (:seon.render/output request)
   (System/identityHashCode (::program-snapshot evidence))
   (get (::projection evidence) :seon.schema.projection/fingerprint)
   (hash (::selection-input evidence))])

(defn- reusable-invocation
  [database bucket evidence]
  (some (fn [entry]
          (when (and (same-call-cache-evidence? entry evidence)
                     (db/read-evidence-current?
                      database (:seon.render.call/read-evidence entry)))
            entry))
        bucket))

(defn- retain-invocation!
  [captured cache-key retained-bucket entry]
  (when captured
    (swap! captured assoc cache-key
           (conj (into []
                       (remove #(same-call-cache-evidence? % entry))
                       retained-bucket)
                 entry))))

(defn- same-call-cache-evidence?
  [previous current]
  (and (some? (::program-snapshot current))
       (some? (::projection current))
       (identical? (::program-snapshot previous)
                   (::program-snapshot current))
       (identical? (::projection previous) (::projection current))
       (= (::selection-input previous) (::selection-input current))))

(defn- refresh-read-evidence
  [database previous]
  (assoc previous :seon.render.call/read-evidence
         (mapv (fn [retained current]
                 (assoc retained
                        :datahike.read/revision
                        (:datahike.read/revision current)))
               (:seon.render.call/read-evidence previous)
               (db/read-evidence
                (mapv
                 (fn [evidence]
                   {:seon.db/db database
                    :seon.db/source-argument-position
                    (:seon.db/source-argument-position evidence)
                    :datahike.read/dependency-plan
                    (:datahike.read/dependency-plan evidence)})
                 (:seon.render.call/read-evidence previous))))))

(defn- cost-shape-key
  [projection request selected output]
  (let [value (render-value request)
        matches (concat
                 (schema/matching-shapes-in
                  projection
                  (render.value/transacted value (:seon.db/db request)))
                 (schema/matching-shapes-in projection value))]
    (or (->> matches
             (filter #(= selected (get % output)))
             (sort-by (juxt (comp - count :seon.schema/required-attrs)
                            (comp str :seon.schema/key)))
             first
             :seon.schema/key)
        :seon.schema/value)))

(defn- render-cost-fact
  [request selected output rendered]
  {:seon.render.cost/shape-key
   (cost-shape-key
    (sci.kernel/context-projection (:seon.sci.eval/ctx request))
    request selected output)
   :seon.render.cost/profile
   (get-in request [:seon.render/profile :seon.render.profile/id])
   :seon.render.cost/estimated-tokens
   (tokens/estimate (if (string? rendered) rendered (pr-str rendered)))
   :seon.render.cost/at (Date.)})

(defn- invoke-selected
  [{ctx :seon.sci.eval/ctx
    caps :seon.sci.admit/caps
    time-limit-ms :seon.sci.eval/time-limit-ms
    on-core-error :seon.config/on-core-error
    :as request}
   selected]
  (let [projection (sci.kernel/context-projection ctx)
        ;; RECORD WHAT IS RENDERING. A producer may hand its own value
        ;; to another producer — the value floor is the common case —
        ;; so the producers already on this chain travel with the
        ;; argument. `project-node*` refuses to select one that is
        ;; already there, which makes self-re-entrance unconstructable
        ;; rather than merely unlikely.
        request (update request :seon.render/rendering
                        (fnil conj #{}) selected)
        argument (render-invocation-argument projection request selected)]
    (:seon.sci.admit/value
     (schema/call-with-projection
      projection
      #(sci.kernel/invoke
        (cond->
         {:seon.sci.eval/ctx ctx
          :seon.db/db (:seon.db/db request)
          :seon.fn/sym (str selected)
          :seon.sci.eval/args [argument]
          :seon.sci.admit/unbounded? true
          :seon.sci.eval/time-limit-ms time-limit-ms
          :seon.sci.admit/caps caps
          :seon.config/on-core-error on-core-error}
          (:seon.render.call/captured-reads request)
          (assoc :seon.db/read-evidence-sink
                 (:seon.render.call/captured-reads request))))))))

(defn- valid-projection?
  [projection output value]
  (or (:seon.error/kind value)
      (schema/valid-candidate-value?
       (:seon.schema.projection/forms projection) output value)))

(declare project-node*)

(defn- project-entry
  [request output path entry]
  (if (vector? entry)
    (mapv (fn [index child]
            (project-node* request output (conj path index) child
                           (admit/semantic-value child)))
          (range)
          entry)
    entry))

(defn- project-children
  [request output path children]
  (mapv (fn [index child]
          (project-node* request output (conj path index) child
                         (admit/semantic-value child)))
        (range)
        children))

(defn- bounded-error-node
  [request error]
  (:seon.sci.admit/print-node
   (admit/admit-value
    {:seon.sci.admit/value error
     :seon.sci.admit/caps (:seon.sci.admit/caps request)
     :seon.sci.admit/interrupt-fn (fn [])
     :seon.config/on-core-error (:seon.config/on-core-error request)})))

(defn- project-node*
  [request output path node value]
  (let [projection (sci.kernel/context-projection
                    (:seon.sci.eval/ctx request))
        ;; A PRODUCER IS NEVER RE-ENTERED INSIDE ITS OWN WALK. A
        ;; producer that renders its value THROUGH the floor —
        ;; `seon.ai/attempt-html` calls `render.value/render-html` for
        ;; an attempt's ordinary facts — hands the floor the very value
        ;; whose schema selected it. The floor's `prepare` projects that
        ;; value, selection answers `seon.ai/attempt-html` again, and
        ;; the chain never returns. Measured 2026-08-07: the render
        ;; proc's virtual thread past 1024 frames of
        ;; project-node → attempt-html → prepare → project-node, so its
        ;; transform never ended, its `::flow/stop` transition never
        ;; ran, and the completion `disarm-agents!` joins never arrived.
        ;; `invoke-selected` records what it is running, so the cycle is
        ;; unconstructable rather than depth-capped; a refused node
        ;; falls through to its children, which is what the delegating
        ;; producer asked for.
        rendering (:seon.render/rendering request #{})
        selected (when (map? value)
                   (or (get value output)
                       (declared-producer projection request value output)))
        selected (when-not (contains? rendering selected) selected)]
    (cond
      (:seon.error/kind selected) (bounded-error-node request selected)

      selected
      (let [rendered (invoke-selected
                      (assoc request :seon.render/value value)
                      selected)]
        (if (valid-projection? projection output rendered)
          (if (:seon.error/kind rendered)
            node
            {:seon.print/face :seon.print/projected
             :seon.render/output output
             :seon.print/value rendered})
          node))

      :else
      (case (:seon.print/face node)
        (:seon.print/vector :seon.print/list :seon.print/set)
        (update node :seon.print/items
                #(project-children request output path %))

        (:seon.print/map :seon.print/record)
        (update node :seon.print/entries
                #(mapv (fn [index entry]
                         (project-entry request output (conj path index)
                                        entry))
                       (range)
                       %))

        :seon.print/throwable
        (update node :seon.print/value
                #(project-node* request output
                                (conj path :seon.print/throwable) %
                                (admit/semantic-value %)))

        node))))

(defn project-node
  "Apply explicit/schema producer precedence recursively to one print node.

  A selected producer's output is terminal projection data: it is never fed
  back into selection. Admission remains wholly owned by the guarded kernel."
  {:malli/schema
   [:=> [:cat :map :any :seon.print/node :seon.render/output]
    :seon.print/node]}
  [request value node output]
  (project-node* request output [] node value))

(defn- invoke-producer
  [request output output-schema]
  (let [selected (or (:seon.render.call/selected-producer request)
                     (producer request output output-schema))]
    (if (:seon.error/kind selected)
      selected
      (invoke-selected request selected))))

(defn- fit-terminal
  [request output rendered]
  (if (or (nil? rendered) (:seon.error/kind rendered))
    rendered
    (let [profile (target-profile request)]
      (if (:seon.error/kind profile)
        profile
        (let [node (print/fit
                    {:seon.print/face :seon.print/projected
                     :seon.render/output output
                     :seon.print/value rendered}
                    profile)
              emitted (print/emit-both node (print/default-options))]
          (if (= output :seon.render/html)
            (:seon.print/hiccup emitted)
            (:seon.print/text emitted)))))))

(defn- raw-output
  [request output selected]
  (let [projection (sci.kernel/context-projection
                    (:seon.sci.eval/ctx request))
        rendered (invoke-selected request selected)]
    (case output
      :seon.render/ai
      (if (or (nil? rendered) (string? rendered) (:seon.error/kind rendered))
        rendered
        {:seon.error/kind ::invalid-ai-output
         :seon.render/invalid-output :ai
         :seon.error/message "The selected AI renderer did not return text."
         :seon.error/data {:seon.render/output rendered}})

      :seon.render/html
      (if (or (nil? rendered)
              (:seon.error/kind rendered)
              (hiccup/hiccup? rendered))
        rendered
        {:seon.error/kind ::invalid-html-output
         :seon.render/invalid-output :html
         :seon.error/message "The selected HTML renderer did not return Hiccup."
         :seon.error/data {:seon.render/output rendered}})

      :seon.render/form
      (if (valid-projection? projection :seon.render/form rendered)
        rendered
        {:seon.error/kind ::invalid-form-output
         :seon.render/invalid-output :form
         :seon.error/message "The selected form renderer did not return a form."
         :seon.error/data {:seon.render/output rendered}}))))

(defn- present-output
  [request output raw]
  (if (= output :seon.render/form)
    raw
    (fit-terminal request output raw)))

(defn render-ai
  "Render one value as text through the unique selected live SCI Var."
  {:malli/schema [:=> [:cat :seon.render/call-request]
                  [:or :nil :string :seon.error/value]]
   :seon.fn/external-sink :ai-visible-text
  :seon.fn/projection-boundary :seon.render/ai}
  [request]
  (let [profile (request-profile request)]
    (if (:seon.error/kind profile)
      profile
      (let [request (assoc request :seon.render/profile profile)
            selected (or (:seon.render.call/selected-producer request)
                         (producer request :seon.render/ai :seon.render/ai))]
        (if (:seon.error/kind selected)
          selected
          (present-output request :seon.render/ai
                          (raw-output request :seon.render/ai selected)))))))

(defn render-html
  "Render one value as Hiccup through the unique selected live SCI Var."
  {:malli/schema [:=> [:cat :seon.render/call-request]
                  [:or :nil :seon.render/hiccup :seon.error/value]]
   :seon.fn/external-sink :html-response
  :seon.fn/projection-boundary :seon.render/html}
  [request]
  (let [profile (request-profile request)]
    (if (:seon.error/kind profile)
      profile
      (let [request (assoc request :seon.render/profile profile)
            selected (or (:seon.render.call/selected-producer request)
                         (producer request :seon.render/html
                                   :seon.render/html))]
        (if (:seon.error/kind selected)
          selected
          (present-output request :seon.render/html
                          (raw-output request :seon.render/html selected)))))))

(defn- entity-lookup
  [database entity]
  (let [identity-attribute
        (->> (db/identity-attributes database)
             (filter #(contains? entity %))
             first)]
    (if identity-attribute
      [identity-attribute (get entity identity-attribute)]
      (:db/id entity))))

(defn- source-provenance-error
  [unit]
  {:seon.error/kind ::missing-source-provenance
   :seon.error/message
   "Default AI source requires an entity identity or stored evaluation result reference."
   :seon.error/diagnostic-layer :render
   :seon.error/diagnostic-operation 'seon.render/render-form
   :seon.error/diagnostic-member :seon.render.value/root
   :seon.error/diagnostic-expected
   [:or :seon.render.walk/lookup :seon.cluster.eval/result-blob]
   :seon.error/diagnostic-offending
   (select-keys unit [:seon.render.value/root
                      :seon.cluster.eval/result-blob])
   :seon.error/diagnostic-cause ::missing-source-provenance
   :seon.error/diagnostic-evidence nil})

(defn render-form
  "Spell the structural read that reproduces one reached database value."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render/form :seon.error/value]]}
  [unit]
  (let [value (:seon.render/value unit)
        root (or (:seon.render.value/root unit)
                 (when (map? value)
                   (entity-lookup (:seon.db/db unit) value)))
        cursor (:seon.render.data/cursor unit)
        path (:seon.render.data/path cursor)]
    (cond
      (nil? root) (source-provenance-error unit)
      (seq path) (list 'seon.render.data/pull-at
                       (list 'quote '[*]) root cursor)
      :else (list 'seon.db/pull (list 'quote '[*]) root))))

(defn render-default-ai-source
  "Return authored source that reproduces a value for terminal rendering."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :string :seon.error/value]]}
  [unit]
  (let [form (render-form unit)]
    (if (:seon.error/kind form)
      form
      (pr-str form))))

(defn render-form-value
  "Render one value as the Clojure form that reads it."
  {:malli/schema [:=> [:cat :seon.render/call-request]
                  [:or :seon.render/form :seon.error/value]]}
  [request]
  (let [profile (request-profile request)]
    (if (:seon.error/kind profile)
      profile
      (let [request (assoc request :seon.render/profile profile)
            projection (sci.kernel/context-projection
                        (:seon.sci.eval/ctx request))
            rendered (invoke-producer request :seon.render/form
                                      :seon.render/form)]
        (if (valid-projection? projection :seon.render/form rendered)
          rendered
          {:seon.error/kind ::invalid-form-output
           :seon.render/invalid-output :form
           :seon.error/message
           "The selected form renderer did not return a form."
           :seon.error/data {:seon.render/output rendered}})))))

(defn render-call
  "Reuse one retained projection while its input, code, and reads are current."
  {:malli/schema [:=> [:cat :seon.render/call-request]
                  [:or :nil :string :seon.render/hiccup
                   :seon.render/form :seon.error/value]]}
  [{database :seon.db/db
    output :seon.render/output
    call-id :seon.render.call/id
    retained-calls :seon.render/retained-calls
    captured-calls :seon.render/captured-calls
    candidate-call-ids :seon.render/candidate-call-ids
    :as request}]
  (let [profile (request-profile request)]
    (if (:seon.error/kind profile)
      profile
      (let [request (assoc request :seon.render/profile profile)
            previous (when (and call-id retained-calls)
                       (get retained-calls call-id))
            check-read-evidence?
            (or (nil? candidate-call-ids)
                (contains? candidate-call-ids call-id))
            previous-selected
            (get-in previous [:seon.render.call/static-evidence
                              :seon.render.call/producer])
            fast-evidence (when previous-selected
                            (call-cache-evidence request previous-selected))
            fast-reusable?
            (and previous
                 (same-call-cache-evidence? previous fast-evidence)
                 (or (not check-read-evidence?)
                     (db/read-evidence-current?
                      database (:seon.render.call/read-evidence previous))))]
        (if fast-reusable?
          (let [entry (if check-read-evidence?
                        (refresh-read-evidence database previous)
                        previous)]
            (when (and call-id captured-calls)
              (swap! captured-calls assoc call-id entry))
            (:seon.render.call/output previous))
          (let [requested-candidate
                (:seon.render.call/selected-producer request)
                decision (or (:seon.render/selection-inspection request)
                             (if requested-candidate
                               (selection-inspection request)
                               (selection request)))
                selected
                (if requested-candidate
                  (if (compatible-selection-candidate? decision
                                                       requested-candidate)
                    requested-candidate
                    (error/diagnostic
                     {:seon.error/kind ::candidate-not-applicable
                      :seon.error/message
                      "The requested renderer is not an applicable candidate."
                      :seon.error/diagnostic-layer :render
                      :seon.error/diagnostic-operation 'seon.render/render-call
                      :seon.error/diagnostic-member
                      :seon.render.call/selected-producer
                      :seon.error/diagnostic-expected :compatible
                      :seon.error/diagnostic-offending requested-candidate
                      :seon.error/diagnostic-cause ::candidate-not-applicable
                      :seon.error/diagnostic-evidence decision}))
                  (:seon.render.selection/selected decision))]
            (if (:seon.error/kind selected)
              selected
              (let [static-evidence (call-static-evidence request decision
                                                          selected)
                    cache-evidence (call-cache-evidence request selected)
                    invocation-key (invocation-cache-key request selected
                                                         cache-evidence)
                    retained-invocations (:seon.render/invocations request)
                    captured-invocations
                    (:seon.render/captured-invocations request)
                    retained-invocation
                    (reusable-invocation database
                                         (get retained-invocations
                                              invocation-key)
                                         cache-evidence)
                    reusable? (and previous
                                   (same-call-cache-evidence?
                                    previous cache-evidence)
                                   (= static-evidence
                                      (:seon.render.call/static-evidence
                                       previous))
                                   (or (not check-read-evidence?)
                                       (db/read-evidence-current?
                                        database
                                        (:seon.render.call/read-evidence
                                         previous))))
                    captured (atom [])
                    invocation-reusable? (some? retained-invocation)
                    raw (if invocation-reusable?
                          (:seon.render.call/output retained-invocation)
                          (raw-output
                           (assoc request
                                  :seon.render.call/selected-producer selected
                                  :seon.render.call/captured-reads captured)
                           output selected))
                    invocation-entry
                    (merge
                     (if invocation-reusable?
                       (refresh-read-evidence database retained-invocation)
                       {:seon.render.call/read-evidence
                        (db/read-evidence
                         @captured {:seon.db/retain-read-results? true})
                        :seon.render.call/basis-transaction
                        (db/basis-t database)
                        :seon.render.call/output raw})
                     cache-evidence)
                    _ (retain-invocation!
                       captured-invocations invocation-key
                       (get retained-invocations invocation-key)
                       invocation-entry)
                    rendered (if reusable?
                               (:seon.render.call/output previous)
                               (present-output request output raw))
                    entry (merge
                           (if reusable?
                             (if check-read-evidence?
                               (refresh-read-evidence database previous)
                               previous)
                             {:seon.render.call/static-evidence static-evidence
                              :seon.render.call/read-evidence
                              (:seon.render.call/read-evidence invocation-entry)
                              :seon.render.call/basis-transaction
                              (db/basis-t database)
                              :seon.render.call/output rendered})
                           cache-evidence
                           {:seon.render.call/invocation-key invocation-key})]
            (when (and call-id captured-calls)
              (swap! captured-calls assoc call-id entry))
            ;; Render cost serves the agent-context consumer. A real prompt
            ;; request structurally carries the held run id through
            ;; `context-pass`; web page, root, and debug renders do not. They
            ;; still retain call evidence, but a read-only page observation
            ;; must never transact.
            (when (and (not reusable?)
                       (not invocation-reusable?)
                       call-id
                       captured-calls
                       (:seon.cluster.run/id request)
                       (:seon.db/connection request))
              (db/transact!
               (:seon.db/connection request)
               [(render-cost-fact request selected output rendered)]))
                rendered))))))))

(defn acquire-context!
  "Acquire an agent's exact retained AI bytes and database value.

  The cluster render proc supplies the bytes and database value; prompt
  assembly adds its capture contribution."
  {:malli/schema [:=> [:cat :seon.flow/channel :seon.cluster.prompt/request]
                  [:or :seon.render/acquired-context :seon.error/value]]}
  [context-channel request]
  (let [reply (async/promise-chan)
        agent-id (:seon.cluster.agent/id request)
        run-id (:seon.cluster.run/id request)]
    (await/await!
     {:seon.await/bound
      {:seon.await/config-attribute :seon.config.eval/time-limit-ms
       :seon.await/config-value (:seon.sci.eval/time-limit-ms request)}
      :seon.await/diagnostic
      {:seon.error/diagnostic-layer :render
       :seon.error/diagnostic-operation ::context-acquisition
       :seon.error/diagnostic-member
       {:seon.cluster.agent/id agent-id
        :seon.cluster.run/id run-id
        :seon.render/context-channel context-channel}
       :seon.error/diagnostic-expected ::context-reply
       :seon.error/diagnostic-offending ::pending
       :seon.error/diagnostic-evidence
       {:seon.cluster.agent/id agent-id
        :seon.cluster.run/id run-id}}
      :seon.await/port-operations
      [[context-channel
        {:seon.render.context/request request
         :seon.render.context/reply reply}]
       reply]})))

(defn- failure-message-id
  [namespace-name failure]
  (str "render-failure-"
       (schema/sha-256
        [(.getBytes (pr-str [namespace-name failure]) "UTF-8")])))

(defn- namespace-owner
  [database namespace-name]
  (db/q '[:find ?agent-id .
          :in $ ?namespace-name
          :where
          [?namespace :seon.ns/name ?namespace-name]
          [?agent :seon.cluster.agent/namespace ?namespace]
          [?agent :seon.cluster.agent/id ?agent-id]]
        database namespace-name))

(defn renderer-failure
  "Prepare the audience-safe render failure and its owner message.

  The browser receives only an unavailable state. The namespace owner, when
  one is explicitly assigned, receives one idempotent durable message carrying
  the internal evidence. No loading state is inferred: without a recorded
  repair-acceptance event, unavailable is the only honest state. An agentless
  namespace has no queryable stakeholders yet, so its transaction data is
  empty rather than guessed."
  {:malli/schema [:=> [:cat :seon.render/failure-request]
                  :seon.render/failure]}
  [{database :seon.db/db
    namespace-name :seon.render/namespace
    failure :seon.error/value}]
  (let [owner (namespace-owner database namespace-name)
        message-id (failure-message-id namespace-name failure)
        already-recorded?
        (some? (db/q '[:find ?message .
                       :in $ ?message-id
                       :where
                       [?message :seon.cluster.message/id ?message-id]]
                     database message-id))
        at (:db/txInstant
            (db/pull database [:db/txInstant] (db/basis-t database)))
        message
        (str "A renderer in " namespace-name " failed. "
             (:seon.error/message failure)
             " Inspect the render failure and repair its declared contract.")]
    {:seon.render/ai "Renderer unavailable."
     :seon.render/html
     [:div {:class "seon-render-unavailable"} "renderer unavailable"]
     :seon.db/tx-data
     (cond-> []
       (and owner (not already-recorded?))
       (conj
        (cond-> {:seon.cluster.message/id
                 message-id
                 :seon.cluster.message/to
                 [:seon.cluster.agent/id owner]
                 :seon.cluster.message/content message}
          at (assoc :seon.cluster.message/at at))))}))

;;; ---------------------------------------------------------------------------
;;; Ambient walk custody
;;; ---------------------------------------------------------------------------

(def ^:dynamic ^:private *walk-context* nil)

(defn call-with-walk-context
  "Call `body` with one agent's ambient walk custody."
  {:malli/schema
   [:=>
    [:catn
     [:seon.render.walk/context
      [:map
       [:seon.cluster.agent/id :seon.cluster.agent/id]
       [:seon.db/db {:optional true} :seon.db/database-value]
       [:seon.sci.admit/caps {:optional true} :seon.sci.admit/caps]
       [:seon.sci.eval/ctx {:optional true} :seon.sci.eval/ctx]
       [:seon.sci.eval/time-limit-ms
        {:optional true}
        :seon.sci.eval/time-limit-ms]
       [:seon.config/on-core-error
        {:optional true}
        :seon.config/on-core-error]
       [:seon.db/connection
        {:optional true}
        :seon.db/connection]]]
     [:seon.render.walk/body [:fn clojure.core/ifn?]]]
    :any]}
  [context body]
  (binding [*walk-context* context
            db/*conn*
            (or (:seon.db/connection context)
                db/*conn*)]
    (body)))

(defn- walk-error
  [message]
  (pr-str
   {:seon.error/kind ::walk-failed
    :seon.error/message message :seon.render/walk-failed true}))

(defn- ambient-database-value
  []
  (or (:seon.db/db *walk-context*)
      (db/db)))

(defn- custody-cluster-name
  [db agent-id]
  (db/q '[:find ?cluster-name .
         :in $ ?agent-id
         :where
         [?agent :seon.cluster.agent/id ?agent-id]
         [?agent :seon.cluster.agent/cluster ?cluster]
         [?cluster :seon.cluster/name ?cluster-name]]
       db agent-id))

(defn- repl-state
  [db agent-id]
  (let [basis (db/basis-t db)
        namespace-name
        (db/q '[:find ?name .
               :in $ ?agent-id
               :where
               [?agent :seon.cluster.agent/id ?agent-id]
               [?agent :seon.cluster.agent/namespace ?namespace]
               [?namespace :seon.ns/name ?name]]
             db agent-id)
        instant (:db/txInstant (db/pull db [:db/txInstant] basis))]
    {:seon.ns/name namespace-name
     :seon.render.history/basis-transaction basis
     :db/txInstant instant}))

(defn- selected-walk-units
  [units branch]
  (if (nil? branch)
    units
    (into []
          (filter
           (fn [unit]
             (let [path (:seon.render.walk/path unit)]
               (and (<= (count branch) (count path))
                    (= branch (subvec path 0 (count branch)))))))
          units)))

(defn walk
  "Return the calling agent's labeled database walk as text.

  With no arguments, root is the agent whose held run supplied this eval's
  custody, depth is 2, and the database value is dereferenced here from that
  cluster's live branch connection. Prompt assembly binds its exact immutable
  database value and calls this same function. `:branch` is a labeled PATH
  from the output and restricts the result to that `get-in` subtree.

  Failures are text the agent can inspect; this boundary never throws."
  {:malli/schema
   [:function
    [:=> [:cat] :string]
    [:=>
     [:cat
      [:map
       [:root {:optional true} :seon.render.walk/lookup]
       [:depth {:optional true} [:int {:min 0}]]
       [:branch
        {:optional true}
        [:vector [:or :keyword :int]]]]]
     :string]]}
  ([]
   (walk {}))
  ([options]
   (try
     (let [db (ambient-database-value)
           agent-id (:seon.cluster.agent/id *walk-context*)]
       (cond
         (nil? db)
         (walk-error "No live cluster database is bound to this evaluation.")

         (nil? agent-id)
         (walk-error "No calling agent is bound to this evaluation.")

         :else
         (let [cluster-name (custody-cluster-name db agent-id)
               effective (when cluster-name
                           (config/effective db cluster-name))
               caps (or (:seon.sci.admit/caps *walk-context*)
                        (some-> effective config/result-caps))
               profile (when effective
                         (agent-render-profile effective))]
           (cond
             (or (empty? caps) (some nil? (vals caps)))
             (walk-error
              (if cluster-name
                (str "Cluster " (pr-str cluster-name)
                     " has no complete render caps.")
                (str "Agent " (pr-str agent-id)
                     " has neither ambient render caps nor a cluster "
                     "connection from which to derive them.")))

             :else
             (let [root (get options :root
                             [:seon.cluster.agent/id agent-id])
                   depth (long (get options :depth 2))
                   branch (:branch options)
                   units
                   ((requiring-resolve 'seon.render.walk/neighborhood)
                    (cond->
                     {:seon.db/db db
                      :seon.sci.eval/ctx (:seon.sci.eval/ctx *walk-context*)
                      :seon.cluster.agent/id agent-id
                      :seon.cluster.run/id
                      (:seon.cluster.run/id *walk-context*)
                      :seon.render/retained-calls
                      (:seon.render/retained-calls *walk-context*)
                      :seon.render/captured-calls
                      (:seon.render/captured-calls *walk-context*)
                      :seon.render.walk/lookup root
                      :seon.render/output :seon.render/ai
                      :seon.render/distance depth
                      :seon.sci.admit/caps caps
                      :seon.sci.eval/time-limit-ms
                      (:seon.sci.eval/time-limit-ms *walk-context*)
                      :seon.config/on-core-error
                      (:seon.config/on-core-error *walk-context*)}
                      profile (assoc :seon.render/profile profile)))
                   selected (selected-walk-units units branch)]
               (if (and branch (empty? selected))
                 (walk-error (str "No walk branch exists at "
                                  (pr-str branch) "."))
                 (pr-str
                  (cond->
                   {:seon.render.walk/lookup root
                    :seon.render/distance depth
                    :seon.render.walk/units selected
                    :seon.render/value (repl-state db agent-id)}
                    branch
                    (assoc :seon.render.walk/branch branch)))))))))
     (catch Throwable failure
       (walk-error (str "Walk failed: "
                        (or (ex-message failure)
                            (.getName (class failure)))))))))
