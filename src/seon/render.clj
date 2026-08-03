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
  (:require [seon.config :as config]
            [seon.db :as db]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as value]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
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
                              :seon.ai/partial])]
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

(defn- schema-producer
  [projection value output]
  (when (map? value)
    (let [producers
          (->> (schema/matching-shapes-in projection (value/transacted value))
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

(defn- invoke-producer
  [{ctx :seon.sci.eval/ctx
    caps :seon.sci.admit/caps
    time-limit-ms :seon.sci.eval/time-limit-ms
    on-core-error :seon.config/on-core-error
    :as request}
   output output-schema]
  (let [selected (producer request output output-schema)]
    (if (:seon.error/kind selected)
      selected
      (:seon.sci.admit/value
       (sci.kernel/invoke
        {:seon.sci.eval/ctx ctx
         :seon.db/db (:seon.db/db request)
         :seon.fn/sym (str selected)
         :seon.sci.eval/args [(render-argument request)]
         :seon.sci.eval/time-limit-ms time-limit-ms
         :seon.sci.admit/caps caps
         :seon.config/on-core-error on-core-error})))))

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
