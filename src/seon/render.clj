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
            [seon.config :as config]
            [seon.db :as db]
            [seon.render.hiccup :as hiccup]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]
            [seon.sci.kernel :as sci.kernel]))

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
     :seon.render.profile/blob-threshold
     (:seon.config.eval.result/blob-threshold effective)
     :seon.render.profile/composition
     (:seon.config.render.agent/composition effective)}))

(defn- request-profile
  [request]
  (or (:seon.render/profile request)
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
        (when effective
          (agent-render-profile effective)))))

(defn- render-argument
  [request]
  (let [value (render-value request)
        context (select-keys request
                             [:seon.db/db
                              :seon.sci.eval/ctx
                              :seon.cluster.agent/id
                              :seon.sci.admit/caps
                              :seon.sci.eval/time-limit-ms
                              :seon.config/on-core-error
                              :seon.store/branch-connection
                              :seon.render/distance
                              :seon.cluster.run/live-processes
                              :seon.ai/partial])
        profile (request-profile request)
        context (cond-> context
                  profile (assoc :seon.render/profile profile))]
    (if (map? value)
      (assoc (merge value context) :seon.render/value value)
      (assoc context :seon.render/value value))))

(defn- candidates
  "Contract-fitting public functions in the explicit owning namespace.

  The acquired database snapshot bounds candidates by explicit namespace and
  public-function facts. The immutable schema projection then validates the
  complete input and typed output contracts against the actual render argument.
  Results are sorted so database insertion order cannot decide ambiguity."
  {:malli/schema [:=> [:cat :seon.render/candidate-request]
                  [:vector :seon.fn/sym]]}
  [{ctx :seon.sci.eval/ctx
    namespace-name :seon.render/namespace
    output-schema :seon.render/output-schema
    :as request}]
  (if-not namespace-name
    []
    (let [projection (sci.kernel/context-projection ctx)
          argument (render-argument request)
          symbols (sci.kernel/public-functions-in ctx namespace-name)]
      (into []
            (comp
             (filter #(= namespace-name (symbol (namespace %))))
             (distinct)
             (filter #(schema/function-accepts-in?
                       projection % [argument]))
             (filter #(schema/function-returns-in?
                       projection % output-schema))
             (map str))
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
    :seon.render/candidates (vec candidate-symbols)}})

(defn- explicit-producer
  [request output]
  (let [value (render-value request)]
    (or (when (map? value) (get value output))
        (get request output))))

(defn transacted
  "Restore a pulled entity to the transaction shape used for selection."
  {:malli/schema [:=> [:cat :map] :map]}
  [entity]
  (into {}
        (map (fn [[attribute value]]
               [attribute
                (cond
                  (and (map? value) (contains? value :db/id)) (:db/id value)
                  (and (sequential? value)
                       (seq value)
                       (every? #(and (map? %) (contains? % :db/id)) value))
                  (into #{} (map :db/id) value)
                  (sequential? value) (set value)
                  :else value)]))
        (dissoc entity :db/id)))

(defn- schema-producer
  [projection value output]
  (when (map? value)
    (let [transacted-matches
          (schema/matching-shapes-in projection (transacted value))
          ;; A pull has two honest shapes. Refs and cardinality-many values
          ;; validate in transaction form, while tuple/vector value attributes
          ;; validate exactly as pulled. `:seon.schema/entity?` is the declared
          ;; discriminator: an entity must never accidentally acquire a
          ;; one-key value renderer merely because maps are open.
          pulled-matches (schema/matching-shapes-in projection value)
          matches
          (->> (concat transacted-matches pulled-matches)
               (filter #(or (not (:db/id value))
                            (:seon.schema/entity? %)))
               (reduce (fn [by-key row]
                         (assoc by-key (:seon.schema/key row) row))
                       (sorted-map))
               vals)
          producers
          (->> matches
               (keep #(get % output))
               distinct
               (sort-by str)
               vec)]
      (cond
        (= 1 (count producers)) (first producers)
        (> (count producers) 1)
        (ambiguity nil output producers)))))

(defn- producer
  [{ctx :seon.sci.eval/ctx
    namespace-name :seon.render/namespace
    :as request}
   output output-schema]
  (let [value (render-value request)
        projection (sci.kernel/context-projection ctx)
        explicit (explicit-producer request output)]
    (if explicit
      explicit
      (let [fits (candidates (assoc request
                                    :seon.render/output-schema output-schema))]
        (cond
          (= 1 (count fits)) (symbol (first fits))
          (> (count fits) 1) (ambiguity namespace-name output fits)
          :else (or (schema-producer projection value output)
                    (if (= output :seon.render/html)
                      'seon.render.value/render-html
                      'seon.render.value/render-ai)))))))

(defn- call-static-evidence
  [request selected]
  (let [argument (dissoc (render-argument request)
                         :seon.db/db
                         :seon.sci.eval/ctx
                         :seon.store/branch-connection)
        value (:seon.render/value argument)]
    {:seon.render.call/producer selected
     :seon.render.call/program-row
     (sci.kernel/program-function (:seon.sci.eval/ctx request) selected)
     :seon.render.call/argument
     (cond-> argument
       (map? value)
       (assoc :seon.render/value (dissoc value :seon.db/db)))}))

(defn- invoke-selected
  [{ctx :seon.sci.eval/ctx
    caps :seon.sci.admit/caps
    time-limit-ms :seon.sci.eval/time-limit-ms
    on-core-error :seon.config/on-core-error
    :as request}
   selected]
  (:seon.sci.admit/value
   (sci.kernel/invoke
    (cond->
     {:seon.sci.eval/ctx ctx
      :seon.db/db (:seon.db/db request)
      :seon.fn/sym (str selected)
      :seon.sci.eval/args [(render-argument request)]
      :seon.sci.eval/time-limit-ms time-limit-ms
      :seon.sci.admit/caps caps
      :seon.config/on-core-error on-core-error}
      (:seon.render.call/captured-reads request)
      (assoc :seon.db/capture-context
             (:seon.render.call/captured-reads request))))))

(defn- valid-projection?
  [output value]
  (or (:seon.error/kind value)
      (case output
        :seon.render/ai (string? value)
        :seon.render/html (hiccup/hiccup? value))))

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

(defn- project-node*
  [request output path node value]
  (let [projection (sci.kernel/context-projection
                    (:seon.sci.eval/ctx request))
        selected (when (map? value)
                   (or (get value output)
                       (schema-producer projection value output)))]
    (cond
      (:seon.error/kind selected) node

      selected
      (let [rendered (invoke-selected
                      (assoc request :seon.render/value value)
                      selected)]
        (if (valid-projection? output rendered)
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

(defn render-ai
  "Render one value as text through the unique selected live SCI Var."
  {:malli/schema [:=> [:cat :seon.render/call-request]
                  [:or :nil :string :seon.error/value]]}
  [request]
  (let [rendered (invoke-producer request :seon.render/ai :seon.render/ai)]
    (if (or (nil? rendered) (string? rendered) (:seon.error/kind rendered))
      rendered
      {:seon.error/kind ::invalid-ai-output
       :seon.error/message "The selected AI renderer did not return text."
       :seon.error/data {:seon.render/output rendered}})))

(defn render-html
  "Render one value as Hiccup through the unique selected live SCI Var."
  {:malli/schema [:=> [:cat :seon.render/call-request]
                  [:or :nil :seon.render/hiccup :seon.error/value]]}
  [request]
  (let [rendered (invoke-producer request :seon.render/html
                                  :seon.render/html)]
    (if (or (nil? rendered)
            (:seon.error/kind rendered)
            (hiccup/hiccup? rendered))
      rendered
      {:seon.error/kind ::invalid-html-output
       :seon.error/message "The selected HTML renderer did not return Hiccup."
       :seon.error/data {:seon.render/output rendered}})))

(defn render-call
  "Reuse one retained projection while its input, code, and reads are current."
  {:malli/schema [:=> [:cat :seon.render/call-request]
                  [:or :nil :string :seon.render/hiccup :seon.error/value]]}
  [{database :seon.db/db
    output :seon.render/output
    call-id :seon.render.call/id
    retained-calls :seon.render/retained-calls
    captured-calls :seon.render/captured-calls
    :as request}]
  (let [output-schema (case output
                        :seon.render/ai :seon.render/ai
                        :seon.render/html :seon.render/html)
        selected (producer request output output-schema)]
    (if (:seon.error/kind selected)
      selected
      (let [static-evidence (call-static-evidence request selected)
            previous (when (and call-id retained-calls)
                       (get retained-calls call-id))
            reusable? (and previous
                           (= static-evidence
                              (:seon.render.call/static-evidence previous))
                           (db/read-evidence-current?
                            database
                            (:seon.render.call/read-evidence previous)))
            captured (atom [])
            rendered (if reusable?
                       (:seon.render.call/output previous)
                       (let [prepared-request
                             (assoc request
                                    :seon.render.call/selected-producer selected
                                    :seon.render.call/captured-reads captured)
                             rendered
                             ((case output
                                :seon.render/ai render-ai
                                :seon.render/html render-html)
                              prepared-request)]
                         rendered))
            entry (if reusable?
                    previous
                    {:seon.render.call/static-evidence static-evidence
                     :seon.render.call/read-evidence
                     (db/read-evidence @captured)
                     :seon.render.call/output rendered})]
        (when (and call-id captured-calls)
          (swap! captured-calls assoc call-id entry))
        rendered))))

(defn acquire-context!
  "Acquire an agent's exact retained AI bytes and database value.

  The cluster render proc supplies the bytes and database value; prompt
  assembly adds its capture contribution."
  {:malli/schema [:=> [:cat :seon.flow/channel :seon.cluster.prompt/request]
                  :seon.render/acquired-context]}
  [context-channel request]
  (let [reply (async/promise-chan)]
    (async/>!! context-channel
               {:seon.render.context/request request
                :seon.render.context/reply reply})
    (async/<!! reply)))

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
            (db/pull database [:db/txInstant] (:max-tx database)))
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
       [:seon.store/branch-connection
        {:optional true}
        :seon.store/branch-connection]]]
     [:seon.render.walk/body [:fn clojure.core/ifn?]]]
    :any]}
  [context body]
  (binding [*walk-context* context
            db/*conn*
            (or (:seon.store/branch-connection context)
                db/*conn*)]
    (body)))

(defn- walk-error
  [message]
  (str ";; (seon.render/walk) => error\n" message))

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
  (let [basis (long (:max-tx db))
        namespace-name
        (db/q '[:find ?name .
               :in $ ?agent-id
               :where
               [?agent :seon.cluster.agent/id ?agent-id]
               [?agent :seon.cluster.agent/namespace ?namespace]
               [?namespace :seon.ns/name ?name]]
             db agent-id)
        instant (:db/txInstant (db/pull db [:db/txInstant] basis))]
    (str ";; REPL state namespace=" (pr-str namespace-name)
         " basis=" basis
         " time=" (pr-str instant))))

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
               caps (or (:seon.sci.admit/caps *walk-context*)
                        (when cluster-name
                          (config/result-caps
                           (config/effective db cluster-name))))]
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
                    {:seon.db/db db
                     :seon.sci.eval/ctx (:seon.sci.eval/ctx *walk-context*)
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
                     (:seon.config/on-core-error *walk-context*)})
                   selected? (or (not (contains? options :branch))
                                 (some (fn [unit]
                                         (let [path (:seon.render.walk/path unit)]
                                           (and (<= (count branch) (count path))
                                                (= branch (subvec path 0 (count branch))))))
                                       units))]
               (if-not selected?
                 (walk-error (str "No walk branch exists at "
                                  (pr-str branch) "."))
                 (str ((requiring-resolve 'seon.render.walk/prose)
                       db units
                       (cond-> {}
                         (contains? options :branch)
                         (assoc :seon.render.walk/branch branch)))
                      "\n" (repl-state db agent-id))))))))
     (catch Throwable failure
       (walk-error (str "Walk failed: "
                        (or (ex-message failure)
                            (.getName (class failure)))))))))
