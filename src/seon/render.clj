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
  re-derives the same symbol from its database program row.

  The generic router below remains only while its already-scheduled callers are
  converted to the two typed outputs. No new caller may use it; the final debris
  wave deletes it together with the obsolete generic-kind schemas and tests."
  (:require [seon.config :as config]
            [seon.db :as db]
            [seon.render.hiccup :as hiccup]
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
          (->> (schema/matching-shapes-in projection value)
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
  (let [rendered (invoke-producer request :seon.render/ai :string)]
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
                                  :seon.render/hiccup)]
    (if (or (nil? rendered)
            (:seon.error/kind rendered)
            (hiccup/hiccup? rendered))
      rendered
      {:seon.error/kind ::invalid-html-output
       :seon.error/message "The selected HTML renderer did not return Hiccup."
       :seon.error/data {:seon.render/output rendered}})))

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
      [:map {:closed true}
       [:seon.cluster.agent/id :seon.cluster.agent/id]
       [:seon.db/db {:optional true} :seon.db/database-value]
       [:seon.sci.admit/caps {:optional true} :seon.sci.admit/caps]
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
      [:map {:closed true}
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
                   neighborhood
                   ((requiring-resolve 'seon.render.walk/neighborhood)
                    {:seon.db/db db
                     :seon.render.walk/lookup root
                     :seon.render/kind :seon.render/ai
                     :seon.render/floor 'seon.render.block/data-prose
                     :seon.render/overrides {}
                     :seon.render/distance depth
                     :seon.sci.admit/caps caps})
                   selected (when (contains? options :branch)
                              (get-in neighborhood branch))]
               (if (and (contains? options :branch) (not (map? selected)))
                 (walk-error (str "No walk branch exists at "
                                  (pr-str branch) "."))
                 (str ((requiring-resolve 'seon.render.walk/prose)
                       db neighborhood
                       (cond-> {}
                         (contains? options :branch)
                         (assoc :seon.render.walk/branch branch)))
                      "\n" (repl-state db agent-id))))))))
     (catch Throwable failure
       (walk-error (str "Walk failed: "
                        (or (ex-message failure)
                            (.getName (class failure)))))))))

;;; ---------------------------------------------------------------------------
;;; Contract
;;; ---------------------------------------------------------------------------

(defn declaration?
  "True when `value` is a projection declaration rather than data.

  THREE SHAPES, and the narrowness is the mechanism rather than a
  restriction: a qualified SYMBOL to resolve and apply, a STRING that is
  its own output, or a VECTOR that is its own output. Anything else on a
  `seon.render`-namespaced key is presentation data —
  `:seon.render/priority 3` is the standing example, and admitting
  numbers would silently turn it into a kind."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (or (qualified-symbol? value)
      (string? value)
      (vector? value)))

(defn kinds
  "The output kinds `unit` declares.
  Every key in the `seon.render` namespace whose value is a
  `declaration?`. COMPUTED from the unit, so a producer that adds a kind
  is discoverable without an edit here and without a registry. The
  router's own request keys (`:seon.render/unit`, `:seon.render/kind`)
  can never be mistaken for declarations — a map and a keyword are
  neither symbol, string nor vector — which is why the rule needs no
  exclusion list.
  Returns the empty set for a map that declares nothing; a unit with no
  projections is an ordinary value, not an error."
  {:malli/schema [:=> [:cat :seon.render/unit] [:set :seon.render/kind]]}
  [unit]
  (let [value (get unit :seon.render/value unit)]
    (into #{}
          (keep (fn [[render-key declaration]]
                  (when (and (qualified-keyword? render-key)
                             (= "seon.render" (namespace render-key))
                             (declaration? declaration))
                    render-key)))
          (concat
           (when (map? value) value)
           (when (and (map? unit) (not (identical? unit value))) unit)))))

(defn- value-declaration
  [unit kind]
  (let [value (get unit :seon.render/value unit)]
    (or (when (map? value)
          (let [declaration (get value kind)]
            (when (declaration? declaration)
              declaration)))
        (let [declaration (get unit kind)]
          (when (declaration? declaration)
            declaration)))))

(defn- namespace-declaration
  [unit kind]
  (when-let [namespace-name (:seon.render/namespace unit)]
    (let [candidate
          (symbol (str namespace-name)
                  (str "render-" (name kind)))]
      (when
       (try
         (some? (requiring-resolve candidate))
         (catch Throwable _ false))
        candidate))))

(defn- schema-declaration
  [unit kind]
  (let [value (get unit :seon.render/value unit)]
    (when (map? value)
      (try
        (some
         (fn [row]
           (let [declaration (get row kind)]
             (when (declaration? declaration)
               declaration)))
         (schema/matching-shapes value))
        (catch Throwable _ nil)))))

(defn- floor-declaration
  [kind]
  (if (= :seon.render/html kind)
    'seon.render.block/data-panel
    'seon.render.block/data-prose))

(defn resolve-unit
  "Resolve one unit and derive whether only the floor can render it.

  The boolean records WHICH resolution branch won. It is deliberately not a
  symbol comparison: a value may explicitly name the generic floor, and that
  explicit choice did not fall through to it. The selected declaration is
  associated under `kind`, so every caller hands the same resolved unit to the
  projection and W4 can filter HTML without reimplementing precedence."
  {:malli/schema [:=> [:cat :seon.render/request] :seon.render/unit]}
  [{:seon.render/keys [unit kind]}]
  (let [specific (or (value-declaration unit kind)
                     (namespace-declaration unit kind)
                     (schema-declaration unit kind))
        floor? (nil? specific)]
    (assoc unit
           kind (or specific (floor-declaration kind))
           :seon.render/would-fall-to-floor? floor?)))

(defn render
  "Project `:seon.render/unit` into `:seon.render/kind`.
  Resolves the unit's declared symbol with `requiring-resolve` — loading
  the owning namespace if needed — and INVOKES THE VAR with the unit,
  so a re-evaluated projection takes effect immediately. On success:
  `{:seon.render/kind <kind> :seon.render/output <the projection>}`.

  Flat `:seon.error` values, never throws — this router runs on the
  error path and may not fault into it:
  - `::unresolvable` — the declared symbol does not resolve, naming the
    symbol. This is the same failure `:seon.source/populate` refuses
    on, and it is a bug in the producer, not in the caller;
  - `::projection-failed` — the projection itself threw, naming the
    symbol and the throwable's class. The projection is named because
    the projection is what is broken."
  {:malli/schema [:=> [:cat :seon.render/request]
                  [:or :seon.render/rendered :seon.error/value]]}
  [{:seon.render/keys [unit kind]}]
  (let [resolved-unit (resolve-unit {:seon.render/unit unit
                                     :seon.render/kind kind})
        declaration (get resolved-unit kind)]
    (cond
      ;; A LITERAL IS ITS OWN OUTPUT. No resolution, nothing to invoke,
      ;; and therefore nothing that can throw — a fixed string or a
      ;; fixed hiccup vector is the answer, and a block that just says a
      ;; fixed thing should not have to define a function to say it.
      (not (qualified-symbol? declaration))
      {:seon.render/kind kind
       :seon.render/output declaration}

      :else
      ;; the VAR, never a fn value taken once: re-evaluating the
      ;; projection's defn must change the next render
      (if-let [projection (try
                            (requiring-resolve declaration)
                            (catch Throwable _ nil))]
        (try
          {:seon.render/kind kind
           :seon.render/output (projection resolved-unit)}
          (catch Throwable failure
            {:seon.error/kind ::projection-failed
             :seon.error/message (str "The " declaration " projection threw: "
                                      (or (ex-message failure)
                                          (.getName (class failure))))
             :seon.error/data {:seon.render/kind kind
                               :seon.render/projection declaration
                               :seon.error/class (.getName (class failure))}}))
        {:seon.error/kind ::unresolvable
         :seon.error/message (str "The projection " declaration
                                  " does not resolve.")
         :seon.error/data {:seon.render/kind kind
                           :seon.render/projection declaration}}))))
