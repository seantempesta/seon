(ns seon.ai
  "One call, one attempt, one deadline. Failure is a value.

  ONE CALL PER MODEL STEP: no retry, no backoff, no failover, no backup
  provider (owner ruling, 2026-09-28). A failed or lost call is an
  error value the turn records and the agent reads; `targets` derives
  the one descriptor row from the effective config dials, and
  `complete` makes exactly one attempt, each leaving one durable
  `:seon.ai/attempt` fact.

  THE ONE LEGITIMATE DEADLINE. A remote HTTP call is genuinely
  unobservable external state — the process cannot see the other end
  die — so `:seon.ai/timeout-ms` is a real backstop rather than a tuned
  constant standing in for an event. Its firing is an ORDINARY ERROR
  VALUE, not a bug report: the model was slow, the run closes with
  something the agent can read. (Contrast the submission backstop in
  the eval path, whose firing IS a bug report.)

  THE DESCRIPTOR ROW IS endpoint, model, timeout, and exactly one
  authentication declaration: the NAME of the environment variable
  holding the credential, or explicit `:seon.config.ai/no-auth true`. A
  credential is read at the leaf, never becomes a datom, and never
  enters Git. A `:seon.ai/request` is a `:seon.ai/target` plus what to
  say, which is why the call site is `(assoc target :seon.ai/prompt …)`
  and there is no adapter in between.

  ERRORS ARE VALUES, ALWAYS. Timeout, transport failure, non-2xx,
  unparseable body — one flat `:seon.error` value each, none throwing.
  The run loop has no catch for a model call because there is nothing
  to catch.

  `:any` appears exactly once in this namespace's schemas, at the
  decoded HTTP response body: it is a foreign document from another
  system, which is the proven third-party boundary the rule allows.

  Crash walk: the call owns no durable state. A kill before it returns
  loses the call (row 3 of the crash walk — the deliberate
  at-least-once boundary, and the reason `fire-the-missiles!` safety
  comes from never refiring rather than from an identity scheme). A
  kill after it returns but before the plan commits loses the reply,
  and nothing re-calls."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.schema.internal :as internal])
  (:import [java.io BufferedReader IOException InputStreamReader]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(defn sink?
  "True for a partial sink: any function of one argument."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [x]
  (ifn? x))

(def sink-generator
  "An honest generator: a real function that ignores its snapshot,
  which is exactly what a sink is allowed to do."
  (gen/fmap (fn [_] (fn [_partial] nil)) (gen/return nil)))

;; the gate refuses a `[:fn]` naming anything not registered
(schema.edn/load! {})

(defn- attempt-without-private-provider-data
  [unit]
  (let [attempt (into (sorted-map-by #(compare (str %1) (str %2)))
                      (dissoc (:seon.render/value unit)
                              :seon.ai.attempt/reasoning
                              :seon.ai.attempt/reasoning-blob
                              :seon.ai.attempt/reasoning-size))]
    ;; The caller counted the value it handed in. Withholding the full provider
    ;; body and reasoning changes what this producer renders, so it restates
    ;; the total too — otherwise the elision machinery reports omitted children
    ;; that the ordinary projection is never meant to show.
    (assoc unit
           :seon.render/value attempt
           :seon.render.data/total (count attempt))))

(defn attempt-ai
  "Render an attempt without its prompt-bearing body or provider reasoning.
  A unit naming no root identity answers the floor's declared refusal."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :string :seon.render.value/missing-root-identity-error]]}
  [unit]
  (render.value/render-ai (attempt-without-private-provider-data unit)))

(defn attempt-html
  "Render ordinary attempt facts; body and reasoning stay queryable only.
  A unit naming no root identity answers the floor's declared refusal."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render/hiccup :seon.render.value/missing-root-identity-error]]}
  [unit]
  (render.value/render-html (attempt-without-private-provider-data unit)))

(def ^:private model-pull
  '[*])

(def ^:private model-detail-pull
  '[*
    {:seon.ai.model/provider [*]}
    {:seon.ai.model/deepseek-off-peak-windows [*]}])

(defn- pulled-ref-id
  [value]
  (if (map? value) (:db/id value) value))

(defn- ordinary-model-entity
  [entity]
  (cond-> entity
    (:seon.ai.model/provider entity)
    (update :seon.ai.model/provider pulled-ref-id)

    (:seon.ai.model/deepseek-off-peak-windows entity)
    (update :seon.ai.model/deepseek-off-peak-windows
            #(into #{} (map pulled-ref-id) %))

    (:seon.ai.model/input-modalities entity)
    (update :seon.ai.model/input-modalities set)

    (:seon.ai.model/thinking-dials entity)
    (update :seon.ai.model/thinking-dials set)))

(defn model-entity
  "The registered entity for one model id, or nil."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.ai.model/id]
    [:maybe :seon.ai.model/entity]]}
  [database model-id]
  (some-> (db/pull database model-pull [:seon.ai.model/id model-id])
          ordinary-model-entity))

(defn models
  "Every registered model entity, ordered by model id."
  {:malli/schema
   [:=> [:cat :seon.db/database-value] :seon.ai.model/models]}
  [database]
  (->> (db/q '[:find [?model-id ...]
               :where [_ :seon.ai.model/id ?model-id]]
             database)
       sort
       (mapv #(model-entity database %))))

(defn- model-details
  [database model-id]
  (some-> (db/pull database model-detail-pull
                   [:seon.ai.model/id model-id])
          (update :seon.ai.model/thinking-dials set)))

(defn- rendered-model
  [unit]
  (let [value (:seon.render/value unit unit)
        model-id (:seon.ai.model/id value)]
    (or (when (and model-id (:seon.db/db unit))
          (model-details (:seon.db/db unit) model-id))
        value)))

(defn- usd-per-million
  [value]
  (when (number? value)
    (format "$%.6f/M" (double value))))

(defn- labelled-price
  [label value]
  (when-let [price (usd-per-million value)]
    (str label " " price)))

(defn- model-ai-text
  "The model registry's one-line-plus text for one AI-face `data` map."
  {:malli/schema [:=> [:cat :seon.ai.model/ai-face] :string]}
  [data]
  (let [provider-id (:seon.ai.model/provider-id data)
        price-parts
        (keep identity
              [(when (contains? data :seon.ai.model/input-usd-per-mtok)
                 (str "input " (usd-per-million (:seon.ai.model/input-usd-per-mtok data))))
               (when (contains? data :seon.ai.model/cached-input-usd-per-mtok)
                 (str "cached input " (usd-per-million (:seon.ai.model/cached-input-usd-per-mtok data))))
               (when (contains? data :seon.ai.model/output-usd-per-mtok)
                 (str "output " (usd-per-million (:seon.ai.model/output-usd-per-mtok data))))])]
    (str
     "Model " (:seon.ai.model/id data)
     (when provider-id (str " · provider " provider-id))
     (when-let [context (:seon.ai.model/context-window-tokens data)]
       (str " · context " context " tokens"))
     (when-let [maximum (:seon.ai.model/max-output-tokens data)]
       (str " · maximum output " maximum " tokens"))
     (when (seq price-parts)
       (str "\nPricing: " (str/join ", " price-parts) "."))
     (when-let [modalities (seq (:seon.ai.model/input-modalities data))]
       (str "\nInputs: " (str/join ", " (sort (map name modalities))) "."))
     (when-let [latency (:seon.ai.model/last-latency-ms data)]
       (str "\nLatest: " latency " ms"
            (when-let [rate (:seon.ai.model/last-tokens-per-second data)]
              (str ", " (format "%.2f" (double rate)) " tokens/s"))
            (when-let [used-at (:seon.ai.model/last-used-at data)]
              (str " at " used-at))
            ".")))))

(defn model-ai
  "Render one model's capabilities, economics, and latest observation."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.ai.model/ai-face]}
  [unit]
  (let [model (rendered-model unit)]
    (cond-> {:seon.ai.model/id (:seon.ai.model/id model)}
      (:seon.ai.model/provider-id (:seon.ai.model/provider model))
      (assoc :seon.ai.model/provider-id
             (:seon.ai.model/provider-id (:seon.ai.model/provider model)))

      (contains? model :seon.ai.model/context-window-tokens)
      (assoc :seon.ai.model/context-window-tokens
             (:seon.ai.model/context-window-tokens model))

      (contains? model :seon.ai.model/max-output-tokens)
      (assoc :seon.ai.model/max-output-tokens
             (:seon.ai.model/max-output-tokens model))

      (contains? model :seon.ai.model/input-usd-per-mtok)
      (assoc :seon.ai.model/input-usd-per-mtok
             (:seon.ai.model/input-usd-per-mtok model))

      (contains? model :seon.ai.model/cached-input-usd-per-mtok)
      (assoc :seon.ai.model/cached-input-usd-per-mtok
             (:seon.ai.model/cached-input-usd-per-mtok model))

      (contains? model :seon.ai.model/output-usd-per-mtok)
      (assoc :seon.ai.model/output-usd-per-mtok
             (:seon.ai.model/output-usd-per-mtok model))

      (seq (:seon.ai.model/input-modalities model))
      (assoc :seon.ai.model/input-modalities
             (:seon.ai.model/input-modalities model))

      (contains? model :seon.ai.model/last-latency-ms)
      (assoc :seon.ai.model/last-latency-ms
             (:seon.ai.model/last-latency-ms model))

      (contains? model :seon.ai.model/last-tokens-per-second)
      (assoc :seon.ai.model/last-tokens-per-second
             (:seon.ai.model/last-tokens-per-second model))

      (contains? model :seon.ai.model/last-used-at)
      (assoc :seon.ai.model/last-used-at
             (:seon.ai.model/last-used-at model)))))

(defn model-html
  "Render one model as a readable registry card."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [model (rendered-model unit)
        tokens #(some-> % (str " tokens"))
        rate #(some-> % double (->> (format "%.2f")) (str " tokens/s"))]
    [:article {:class "seon-family-entry seon-ai-model-entry"}
     [:h3 (:seon.ai.model/id model)]
     (into [:dl]
           (keep (fn [[label value]] (when value [:div [:dt label] [:dd value]])))
           [["Provider" (get-in model [:seon.ai.model/provider :seon.ai.model/provider-id])]
            ["Context" (tokens (:seon.ai.model/context-window-tokens model))]
            ["Maximum output" (tokens (:seon.ai.model/max-output-tokens model))]
            ["Input" (usd-per-million (:seon.ai.model/input-usd-per-mtok model))]
            ["Cached input" (usd-per-million (:seon.ai.model/cached-input-usd-per-mtok model))]
            ["Output" (usd-per-million (:seon.ai.model/output-usd-per-mtok model))]
            ["Latest latency" (some-> (:seon.ai.model/last-latency-ms model) (str " ms"))]
            ["Latest speed" (rate (:seon.ai.model/last-tokens-per-second model))]])]))

(defn provider-ai
  "Render one model provider descriptor without its credential value."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/ai-data]}
  [unit]
  (let [provider (:seon.render/value unit unit)]
    {:seon.ai.model/provider-id (:seon.ai.model/provider-id provider)
     :seon.config.ai/endpoint (:seon.config.ai/endpoint provider)
     :seon.ai.model/output-token-wire-key
     (:seon.ai.model/output-token-wire-key provider)}))

(defn provider-html
  "Render one model provider descriptor as a readable card."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [provider (:seon.render/value unit unit)]
    [:article {:class "seon-family-entry seon-ai-provider-entry"}
     [:h3 (:seon.ai.model/provider-id provider)]
     [:dl
      [:div [:dt "Endpoint"] [:dd (:seon.config.ai/endpoint provider)]]
      [:div [:dt "Output budget field"]
       [:dd (:seon.ai.model/output-token-wire-key provider)]]]]))

(defn registry-ai
  "Render the query-derived model registry for agent context."
  {:malli/schema [:=> [:cat :seon.db/database-value] :string]}
  [database]
  (str "Available models\n"
       (str/join "\n"
                 (map #(model-ai-text (model-ai {:seon.db/db database
                                                 :seon.render/value %}))
                      (models database)))))

(defn registry-html
  "Render the query-derived model registry for the web UI."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.render/hiccup]}
  [database]
  (into [:section {:class "seon-ai-model-registry"}
         [:h3 "Available models"]]
        (map #(model-html {:seon.db/db database
                           :seon.render/value %}))
        (models database)))

;;; ---------------------------------------------------------------------------
;;; Contracts
;;; ---------------------------------------------------------------------------

;; Both helpers REQUIRE the population, so a future caller cannot silently
;; reintroduce the per-attribute shape these two derivations had.
(defn- map-attributes
  [projection schema-key]
  (into #{} (map first)
        (internal/entity-entries
         (mr/schema (:seon.schema.projection/registry projection) schema-key))))

(defn settings
  "Resolved AI settings for one agent. Pure."
  {:malli/schema
   [:=> [:cat :seon.config/effective :seon.config/agent-overlay]
    :seon.config/effective]}
  [cluster-settings agent-settings]
  (merge cluster-settings agent-settings))

(defn agent-setting-attributes
  "The override keys, queried from the installed overlay schema's references."
  {:malli/schema [:=> [:cat :seon.db/db]
                  [:or [:set :qualified-keyword] :seon.db/invalid-read-error :seon.schema/validation-refusal]]}
  [database]
  (let [entity (db/pull database '[(limit :seon.schema/references nil)]
                     [:seon.schema/key :seon.config/agent-overlay])]
    (cond
      (:seon.db/invalid-read entity) entity
      (seq (:seon.schema/references entity))
      (set (:seon.schema/references entity))
      :else
      (assoc (refusal/diagnostic (java.util.Date.) :seon.ai/settings 'seon.ai/agent-setting-attributes
       {:seon.error/message "The agent overlay schema has no declared setting references."
        :seon.schema/refused-value entity
        :seon.schema/expected-value :seon.config/agent-overlay
        :seon.error/member :seon.schema/references
        :seon.error/expected "installed agent-overlay setting references"}) :seon.error/type :seon.schema/validation-refusal))))

(defn agent-overlay
  "Declared setting overrides for one agent in a database value."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.agent/id]
    [:or :seon.config/agent-overlay :seon.db/invalid-read-error :seon.schema/validation-refusal]]}
  [db agent-id]
  (let [attributes (agent-setting-attributes db)]
    (if (db/read-refusal? attributes)
      attributes
      (let [entity (db/pull db [{:seon.agent/settings (vec attributes)}]
                         [:seon.agent/id agent-id])]
        (if (:seon.db/invalid-read entity)
          entity
          (select-keys (:seon.agent/settings entity) attributes))))))

(defn request-attributes
  "Declared config-to-request routes from one supplied generation."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:map-of :qualified-keyword :qualified-keyword]]}
  [projection]
  (into {}
        (keep (fn [config-ident]
                (when-let [attribute (:seon.ai/request-attribute
                                     (m/properties (mr/schema (:seon.schema.projection/registry projection) config-ident)))]
                  [config-ident attribute])))
        (keys (:seon.schema.projection/forms projection))))

(defn- primary-setting-entries
  [projection dials]
  (let [routes (request-attributes projection)]
    (into {}
        (keep
         (fn [[config-ident value]]
           (when-let [attribute (get routes config-ident)]
             [attribute value])))
        dials)))

(defn- resolved-target
  [target model]
  (if model
    (let [provider (:seon.ai.model/provider model)
          thinking-dials (:seon.ai.model/thinking-dials model)
          configured-thinking (:seon.ai/thinking target)
          target
          (if provider
            (let [resolved
                  (cond->
                   (assoc target
                          :seon.ai/endpoint
                          (:seon.config.ai/endpoint provider)
                          :seon.ai.model/output-token-wire-key
                          (:seon.ai.model/output-token-wire-key provider))
                    ;; Credential selection is already effective per-agent
                    ;; data. The descriptor supplies its default only when
                    ;; no caller selected one, so resolution cannot clobber
                    ;; an explicit variable name.
                    (not (contains? target :seon.ai/api-key-variable))
                    (assoc :seon.ai/api-key-variable
                           (:seon.config.ai/api-key-variable provider)))]
              (if (:seon.config.ai/no-auth target)
                (dissoc resolved :seon.ai/api-key-variable)
                (dissoc resolved :seon.config.ai/no-auth)))
            target)]
      (cond-> target

        (:seon.ai.model/max-output-tokens model)
        (update :seon.ai/max-tokens min
                (:seon.ai.model/max-output-tokens model))

        (not (contains? thinking-dials configured-thinking))
        (dissoc :seon.ai/thinking)))
    target))

(defn- configured-targets
  [projection dials]
  (let [primary-settings (primary-setting-entries projection dials)]
    {:seon.ai/primary
     (if (:seon.config.ai/no-auth dials)
       (dissoc primary-settings :seon.ai/api-key-variable)
       (dissoc primary-settings :seon.config.ai/no-auth))}))

(defn targets
  "The one provider descriptor row, from the dials. PURE.

  Returned as `{:seon.ai/primary target}`: one call per model step means
  one target, and the descriptor row (endpoint, model, timeout, one
  authentication declaration) resolves against the model's provider
  descriptor when a database is passed."
  {:malli/schema
   [:function
    [:=> [:cat :seon.schema/projection :seon.config/effective] :seon.ai/targets]
    [:=> [:cat :seon.schema/projection :seon.db/database-value :seon.config/effective]
     :seon.ai/targets]]}
  ([projection dials] (configured-targets projection dials))
  ([projection database dials]
   (let [{:seon.ai/keys [primary]} (configured-targets projection dials)]
     {:seon.ai/primary
      (resolved-target primary
                       (model-details database (:seon.ai/model primary)))})))

(def ^:private omit-wire-value ::omit-wire-value)

(defn thinking-wire-value
  "OpenAI-compatible thinking toggle for one resolved setting."
  {:malli/schema [:=> [:cat :seon.ai/thinking] :map]}
  [thinking]
  {"type" (if (= :disabled thinking) "disabled" "enabled")})

(defn reasoning-effort-wire-value
  "OpenAI-compatible reasoning effort, or the omission marker."
  {:malli/schema [:=> [:cat :seon.ai/thinking] [:or :string :keyword]]}
  [thinking]
  (if (= :disabled thinking) omit-wire-value (name thinking)))

(defn response-format-wire-value
  "OpenAI-compatible structured-output document for one setting."
  {:malli/schema [:=> [:cat :seon.ai/response-format] :map]}
  [response-format]
  {"type" (str/replace (name response-format) "-" "_")})

(defn- config-registration-properties
  [projection config-ident]
  (m/properties (mr/schema (:seon.schema.projection/registry projection) config-ident)))

(defn- wire-setting-triples
  [projection]
  (->> (map-attributes projection :seon.config/effective)
       (mapcat
        (fn [config-ident]
          (map (fn [[wire-key coercion]] [config-ident wire-key coercion])
               (:seon.ai/wire (config-registration-properties projection config-ident)))))
       (sort-by (juxt (comp str first) second))
       vec))

(defn- coercion-function
  [coercion]
  (requiring-resolve coercion))

(defn wire-settings
  "Wire fields honoured for a request and configured fields that are inert."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.ai/request] :seon.ai/wire-settings]}
  [projection request]
  (let [routes (request-attributes projection)
        thinking? (not= :disabled (:seon.ai/thinking request))
        inert (if thinking?
                (into #{}
                      (keep (fn [[config-ident attribute]]
                              (when (and (contains? request attribute)
                                         (:seon.ai/inert-when-thinking
                                          (config-registration-properties projection config-ident)))
                                config-ident)))
                      routes)
                #{})]
    (reduce
     (fn [result [config-ident wire-key coercion]]
       (let [request-ident (get routes config-ident)]
         (if (or (contains? inert config-ident)
                 (not (contains? request request-ident)))
           result
           (let [wire-key
                 (if (= :seon.config.ai/max-tokens config-ident)
                   (or (:seon.ai.model/output-token-wire-key request)
                       wire-key)
                   wire-key)
                 wire-value ((coercion-function coercion)
                             (get request request-ident))]
             (if (= omit-wire-value wire-value)
               result
               (assoc-in result [:seon.ai/sent wire-key] wire-value))))))
     {:seon.ai/sent {}
      :seon.ai/inert inert}
     (wire-setting-triples projection))))

;; The transport-phase evidence of a call that provably sent nothing and cost
;; nothing; `disposition` reads it.
(def ^:private unsent
  {::request-transmitted? false ::response-started? false ::output-observed? false})

(defn- answered
  "Transport evidence of a call the provider answered with `status`."
  {:malli/schema [:=> [:cat :int :boolean] :map]}
  [status output-observed?]
  {::status status ::http-status status
   ::request-transmitted? true ::response-started? true
   ::output-observed? output-observed?})

(defn- declared-error
  "A flat `:seon.ai/request` error of declared `schema`, produced by `operation`."
  {:malli/schema [:=> [:cat :qualified-keyword :seon.error/operation :map] :seon.error/base]}
  [schema operation members]
  (assoc (refusal/diagnostic (java.util.Date.) :seon.ai/request operation members) :seon.error/type schema))

(defn- unparseable
  "The declared error for a provider document with no textual assistant reply.
  `member` names the unreadable part; `members` adds the observation."
  {:malli/schema [:=> [:cat :seon.error/operation :string :string :map] :seon.ai/unparseable-body-error]}
  [operation member message members]
  (declared-error :seon.ai/unparseable-body-error operation
                  (assoc members
                         :seon.error/message message
                         :seon.ai/unreadable-response-member member
                         :seon.error/expected "provider JSON with a textual assistant response")))

(defn- extra-body-request-ident
  [projection]
    (some
     (fn [config-ident]
       (when (true? (:seon.ai/extra-body
                     (config-registration-properties projection config-ident)))
         (:seon.ai/request-attribute
          (config-registration-properties projection config-ident))))
     (map-attributes projection :seon.config/effective)))

(defn- extra-body
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.ai/request] [:or :seon.ai/sent :seon.ai/invalid-extra-body-error]]}
  [projection request]
  (if-let [encoded (get request (extra-body-request-ident projection))]
    (try
      (let [decoded (edn/read-string encoded)]
        (if (and (map? decoded) (every? string? (keys decoded)))
          decoded
          (declared-error :seon.ai/invalid-extra-body-error 'seon.ai/extra-body
                          {:seon.error/offending encoded
                           :seon.error/message "The configured extra body must be an EDN map with string keys."
                           :seon.error/data unsent
                           :seon.ai/extra-body-edn encoded
                           :seon.error/expected "an EDN map with string keys"})))
      (catch Throwable failure
        (declared-error :seon.ai/invalid-extra-body-error 'seon.ai/extra-body
                        (assoc unsent
                               :seon.error/offending encoded
                               :seon.error/message "The configured extra body is not readable EDN; supply a map with string keys."
                               :seon.ai/extra-body-edn encoded
                               :seon.error/expected "an EDN map with string keys"
                               :seon.ai/body-read-message (or (ex-message failure) "The provider-specific body could not be read.")))))
    {}))

(defn- request-headers
  [api-key]
  (cond-> {"content-type" "application/json"}
    api-key (assoc "authorization" (str "Bearer " api-key))))

(defn- text-part
  {:malli/schema [:=> [:cat [:string {:min 1}]] [:map ["type" [:= "text"]] ["text" [:string {:min 1}]]]]}
  [text]
  {"type" "text" "text" text})

(defn- transcript
  "One exchange as the REPL printed it: each form, then its reply."
  {:malli/schema [:=> [:cat :seon.ai/exchange] :string]}
  [exchange]
  (str/join "\n" (map (fn [{form :seon.ai/form reply :seon.ai/reply}]
                        (cond-> form reply (str "\n" reply)))
                      exchange)))

(defn- chat-messages
  "One exchange as the model's own assistant message of its forms, then a user
  message of what the REPL answered (none when nothing settled)."
  {:malli/schema [:=> [:cat :seon.ai/exchange] [:vector :seon.ai/message]]}
  [exchange]
  (let [replies (keep :seon.ai/reply exchange)]
    (cond-> [{"role" "assistant" "content" (str/join "\n" (map :seon.ai/form exchange))}]
      (seq replies) (conj {"role" "user" "content" (str/join "\n" replies)}))))

(defn messages
  "The wire messages of one model call: `system`, the agent's `exchanges` laid
  out by `wrapping`, and the `block` as the last content before the model's turn.

  `:transcript` sends one user message of the exchanges then the block; `:chat`
  sends each exchange as `chat-messages` and the block as a second text part of
  the last user message, or as the only user message before the first exchange."
  {:malli/schema [:=> [:cat :seon.ai/wrapping [:maybe :seon.ai/system] :seon.ai/exchanges :seon.ai/prompt]
                  :seon.ai/messages]}
  [wrapping system exchanges block]
  (let [head (if system [{"role" "system" "content" system}] [])]
    (case wrapping
      :transcript
      (conj head {"role" "user" "content" (str/join "\n\n" (conj (mapv transcript exchanges) block))})
      :chat
      (let [history (into head (mapcat chat-messages) exchanges)
            {role "role" replies "content"} (peek history)]
        (if (= "user" role)
          (conj (pop history) {"role" "user" "content" [(text-part replies) (text-part block)]})
          (conj history {"role" "user" "content" block}))))))

(def ^:private eval-tool
  "The model's one tool: its `code` is the REPL's input, read as reply text is."
  {"type" "function"
   "function" {"name" "eval"
               "description" "Evaluate Clojure forms at your REPL; what the REPL answers comes back in the next message."
               "parameters" {"type" "object"
                             "properties" {"code" {"type" "string"
                                                   "description" "Clojure forms, each starting at column zero; prose in ;; comments."}}
                             "required" ["code"]}}})

(defn request-body
  "The provider request document, or a pre-call flat error. Pure.
  Every provider Seon speaks is OpenAI-compatible chat, so every request
  declares the one tool, `eval`."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.ai/request] :seon.ai/request-body]}
  [projection {:keys [:seon.ai/model :seon.ai/system :seon.ai/prompt]
    stream? :seon.ai/stream?
    :as request}]
  ;; STRING keys: this is the wire document, not Clojure data. It is
  ;; built as strings and read back as strings, so nothing in between
  ;; has to remember which side of the boundary it is on.
  (let [base (cond->
               {"model" model
                "stream" (boolean stream?)
                "tools" [eval-tool]
                ;; a request naming no wrapping sends its prompt as one transcript
                "messages" (messages (:seon.ai/wrapping request :transcript) system
                                     (:seon.ai/exchanges request []) prompt)}
               ;; `docs/seon/reference/llm-adapters.md:169-179`.
               stream? (assoc "stream_options" {"include_usage" true}))
        sent (:seon.ai/sent (wire-settings projection request))
        builder-body (merge base sent)
        extra (extra-body projection request)]
    (if (contains? extra :seon.ai/extra-body-edn)
      extra
      (let [builder-owned-keys
            (into (set (keys builder-body))
                  (keys (request-headers ::protected-placeholder)))
            conflicts (set/intersection builder-owned-keys
                                        (set (keys extra)))]
        (if (seq conflicts)
          (declared-error :seon.ai/extra-body-conflict-error 'seon.ai/request-body
                          {:seon.error/offending extra
                           :seon.error/message "The configured extra body cannot override request-builder fields."
                           :seon.error/data (assoc unsent ::protected-keys (vec (sort conflicts)))
                           :seon.ai/protected-keys conflicts
                           :seon.error/member :seon.ai/extra-body-edn
                           :seon.error/expected "fields not owned by the request builder"})
          ;; LiteLLM's useful escape hatch, but with Seon's error-as-value
          ;; boundary: provider-owned fields merge last only after conflicts
          ;; with the builder's ACTUAL emitted keys have been refused.
          (merge builder-body extra))))))

(defn- invalid-optional-string?
  [document field]
  (and (map? document)
       (contains? document field)
       (some? (get document field))
       (not (string? (get document field)))))

(defn- unreadable-stream-data
  {:malli/schema [:=> [:cat :string :string] :seon.ai/unparseable-body-error]}
  [payload reason]
  (unparseable 'seon.ai/unreadable-stream-data "body"
               (str "The provider stream carried unreadable data: " reason ".")
               {:seon.error/data {::body payload}}))

(defn- tool-call-fragment?
  "Whether `fragment` is one streamed piece of a tool call: an indexed object
  whose function name and arguments, when present, are text."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [fragment]
  (and (map? fragment)
       (int? (get fragment "index"))
       (let [function (get fragment "function")]
         (or (nil? function)
             (and (map? function)
                  (not (invalid-optional-string? function "name"))
                  (not (invalid-optional-string? function "arguments")))))))

(defn- fold-tool-calls
  "`calls`, the tool calls streamed so far by index, with `fragments` added:
  a name is kept, argument text is appended."
  {:malli/schema [:=> [:cat :seon.ai/tool-calls [:sequential :map]] :seon.ai/tool-calls]}
  [calls fragments]
  (reduce (fn [calls {index "index" {tool "name" arguments "arguments"} "function"}]
            (update calls index
                    (fn [call]
                      (cond-> (or call {"arguments" ""})
                        (string? tool) (assoc "name" tool)
                        (string? arguments) (update "arguments" str arguments)))))
          calls
          fragments))

(defn- stream-chunk-error
  {:malli/schema [:=> [:cat :string :seon.schema/value] [:maybe :seon.ai/unparseable-body-error]]}
  [payload document]
  (let [choices (when (map? document) (get document "choices"))
        choice (when (sequential? choices) (first choices))
        delta (when (map? choice) (get choice "delta"))
        usage (when (map? document) (get document "usage"))
        reason
        (cond
          (not (map? document)) "the JSON value was not an object"
          (some? (get document "error"))
          "the provider returned an error document"
          (and (contains? document "choices")
               (some? choices)
               (not (sequential? choices)))
          "the choices field was not an array"
          (and (seq choices) (not (map? choice)))
          "the first choice was not an object"
          (and (map? choice)
               (contains? choice "delta")
               (some? delta)
               (not (map? delta)))
          "the choice delta was not an object"
          (invalid-optional-string? delta "content")
          "the content delta was not text"
          (invalid-optional-string? delta "reasoning_content")
          "the reasoning delta was not text"
          (and (map? delta)
               (some? (get delta "tool_calls"))
               (not (and (sequential? (get delta "tool_calls"))
                         (every? tool-call-fragment? (get delta "tool_calls")))))
          "the tool calls delta was not an array of indexed calls"
          (invalid-optional-string? choice "finish_reason")
          "the finish reason was not text"
          (and (contains? document "usage")
               (some? usage)
               (not (map? usage)))
          "the usage field was not an object")]
    (when reason
      (unreadable-stream-data payload reason))))

(defn stream-event
  "One SSE line folded into the accumulating snapshot. PURE.

  THE WHOLE STREAMING PARSER, and it is small on purpose: an SSE line is
  either `data: <json>`, `data: [DONE]`, a comment, or blank. Everything
  else about streaming — when to publish, where partials land, what a
  token count is for — belongs to somebody else.

  Two provider shapes, one fold. An OpenAI-compatible provider emits a
  final choices-empty chunk carrying usage; Gemini attaches cumulative
  usage to content chunks and never sends a usage-only one
  (`docs/seon/reference/llm-adapters.md:169-186`). So usage is retained
  as the NEWEST seen, independently of choices, and neither shape is
  assumed.

  Content-free protocol lines are skipped: comments, blank lines,
  `[DONE]`, and non-data SSE fields lose nothing. A nonblank `data:`
  payload that is not readable provider JSON returns a flat error.
  Dropping it would join text from either side into bytes the provider
  never sent, so the fold must stop before another delta is accepted."
  {:malli/schema [:=> [:cat :seon.ai/partial :string]
                  [:or :seon.ai/partial :seon.ai/unparseable-body-error]]}
  [snapshot line]
  (let [payload (when (str/starts-with? line "data:")
                  (str/trim (subs line 5)))]
    (if (or (nil? payload) (= "[DONE]" payload) (str/blank? payload))
      snapshot
      (let [document (try
                       (json/read-str payload)
                       (catch Throwable failure
                         (unreadable-stream-data payload
                                                 (ex-message failure))))]
        (if (and (map? document) (contains? document :seon.ai/unreadable-response-member))
          document
          (if-let [failure (stream-chunk-error payload document)]
            failure
            (let [choice (some-> document (get "choices") first)
                  delta-document (some-> choice (get "delta"))
                  delta (some-> delta-document (get "content"))
                  reasoning-delta
                  (some-> delta-document (get "reasoning_content"))
                  finish-reason (some-> choice (get "finish_reason"))
                  fragments (some-> delta-document (get "tool_calls"))
                  usage (get document "usage")
                  text (cond-> (:seon.ai/text snapshot)
                         (string? delta) (str delta))
                  reasoning
                  (when (or (contains? snapshot :seon.ai/reasoning-partial)
                            (string? reasoning-delta))
                    (str (:seon.ai/reasoning-partial snapshot)
                         reasoning-delta))]
              (cond-> (assoc snapshot :seon.ai/text text)
                (seq fragments)
                (update :seon.ai/tool-calls (fnil fold-tool-calls (sorted-map)) fragments)
                (some? reasoning)
                (assoc :seon.ai/reasoning-partial reasoning)
                ;; newest usage wins, and only when the provider sent one
                (map? usage) (assoc :seon.ai/usage usage)
                (string? (get document "model")) (assoc :seon.ai/response-model (get document "model"))
                (string? finish-reason)
                (assoc :seon.ai/finish-reason finish-reason)
                ;; THE LIVE TOKEN COUNT, from the same fold rather than a
                ;; second mechanism: the provider's own completion count when
                ;; it has told us, and the chunk count until then — which is
                ;; exactly one token per chunk for every provider we speak to,
                ;; and is honestly an approximation rather than a promise.
                true (assoc :seon.ai/tokens
                            (or (some-> usage (get "completion_tokens"))
                                (:seon.ai/tokens
                                 (update snapshot :seon.ai/tokens (fnil inc 0)))
                                0))))))))))

(defn stream-fold
  "Fold SSE `lines` into one snapshot, publishing to `sink` as it goes.

  PUBLISHING IS COALESCED BY THE SINK'S OWN CADENCE, not by this: it is
  called once per content chunk, and a sink that wants fewer is the one
  that knows how few. What this guarantees is the other half — a sink
  that THROWS cannot break the call. Presentation may lag, drop, or be
  broken; it may never affect transport, parsing, usage, or evaluation,
  which is the invariant streaming lives under.

  Returns the final snapshot or the first flat data error. The caller
  turns that value into a completion, so a streamed call and a one-shot
  call keep the same success/error boundary downstream."
  {:malli/schema [:=> [:cat [:fn {:error/message "must be a sequential stream"
                                 :gen/schema [:sequential :string]}
                            clojure.core/sequential?]
                       [:maybe :seon.ai/sink]]
                  [:or :seon.ai/partial :seon.ai/unparseable-body-error]]}
  [lines sink]
  (reduce (fn [snapshot line]
            (let [next-snapshot (when-not (map? line) (stream-event snapshot line))]
              (cond
                ;; The transport's terminal event: the read failure that
                ;; ended the line source, beside everything that arrived.
                (map? line)
                (reduced (assoc snapshot ::read-failure (::read-failure line)))

                (contains? next-snapshot :seon.ai/unreadable-response-member)
                (reduced next-snapshot)

                :else
                (do
                  (when (and sink
                             (or (not= (:seon.ai/text snapshot)
                                       (:seon.ai/text next-snapshot))
                                 (not= (:seon.ai/reasoning-partial snapshot)
                                       (:seon.ai/reasoning-partial
                                        next-snapshot))))
                    (try (sink next-snapshot) (catch Throwable _ nil)))
                  ;; A provider finish reason is terminal evidence. When the
                  ;; accumulated output is reasoning-only, continuing to read
                  ;; cannot turn that finished choice into assistant text; it
                  ;; can only park until EOF or the request time limit. End the
                  ;; fold here so `streamed-completion` closes this attempt's
                  ;; body and returns the typed refusal immediately.
                  (if (and (string? (:seon.ai/finish-reason next-snapshot))
                           (str/blank? (:seon.ai/text next-snapshot))
                           (seq (:seon.ai/reasoning-partial next-snapshot)))
                    (reduced next-snapshot)
                    next-snapshot)))))
          {:seon.ai/text "" :seon.ai/tokens 0}
          lines))

(defn- eval-code
  "The `code` text of one `eval` call's JSON `arguments`, or nil when they are
  not an object carrying it."
  {:malli/schema [:=> [:cat :seon.schema/value] [:maybe :string]]}
  [arguments]
  (when (string? arguments)
    (let [decoded (try (json/read-str arguments) (catch Exception _ nil))]
      (when (and (map? decoded) (string? (get decoded "code")))
        (get decoded "code")))))

(defn- tool-call-reply
  "The reply of `content` and the tool `calls`: the content text followed by
  each `eval` call's code, in order, and the names of calls to any other tool;
  or the declared error for a call this cannot read."
  {:malli/schema [:=> [:cat [:maybe :string] [:sequential :seon.schema/value]]
                  [:or :seon.ai/tool-call-reply :seon.ai/unparseable-body-error]]}
  [content calls]
  (reduce (fn [reply {tool "name" arguments "arguments" :as call}]
            (let [code (when (= "eval" tool) (eval-code arguments))]
              (cond
                (string? code)
                (update reply :seon.ai/text #(if (str/blank? %) code (str % "\n" code)))

                (and (string? tool) (seq tool) (not= "eval" tool))
                (update reply :seon.ai/unknown-tool-calls (fnil conj []) tool)

                :else
                (reduced (unparseable 'seon.ai/tool-call-reply "choices.message.tool_calls"
                                      "Your tool call had no name, or its eval arguments were not a JSON object with code text (a reply cut off mid-call); nothing ran. Send the forms again."
                                      {:seon.error/offending call :seon.error/data {::tool-call call}})))))
          {:seon.ai/text (or content "")}
          calls))

(defn- parsed-completion
  {:malli/schema [:=> [:cat :seon.schema/value :seon.schema/value :seon.schema/value :seon.schema/value :seon.schema/value :seon.schema/value [:sequential :seon.schema/value] :seon.schema/value] :seon.ai/parsed-completion]}
  [content reasoning-content finish-reason usage tokens body-shape tool-calls response-model]
  (let [reply (when (or (nil? content) (string? content)) (tool-call-reply content tool-calls))
        text (:seon.ai/text reply)
        unknown (:seon.ai/unknown-tool-calls reply)
        evidence (cond-> {::body-shape body-shape}
                   (and (string? reasoning-content) (seq reasoning-content))
                   (assoc ::reasoning-content reasoning-content)
                   (string? finish-reason)
                   (assoc ::finish-reason finish-reason)
                   (map? usage) (assoc ::usage usage))]
    (cond
      (and (some? content) (not (string? content)))
      (unparseable 'seon.ai/parsed-completion "choices.message"
                   "The provider's assistant content was not text."
                   {:seon.error/offending content :seon.error/data evidence})

      (and (some? reasoning-content) (not (string? reasoning-content)))
      (unparseable 'seon.ai/parsed-completion "choices.message"
                   "The provider's assistant reasoning was not text."
                   {:seon.error/offending reasoning-content :seon.error/data evidence})

      (contains? reply :seon.ai/unreadable-response-member) reply

      (and (string? reasoning-content)
           (seq reasoning-content)
           (str/blank? text)
           (empty? unknown))
      (declared-error :seon.ai/reasoning-without-answer-error 'seon.ai/parsed-completion
                      {:seon.error/offending reasoning-content
                       :seon.ai/unanswered-reasoning-count (count reasoning-content)
                       :seon.error/message (str "The provider finished after streaming "
                                                (count reasoning-content)
                                                " characters of reasoning and no assistant text.")
                       :seon.error/data (assoc evidence
                                               ::reasoning-received (count reasoning-content)
                                               ::text-received 0)
                       :seon.error/member :seon.ai/text
                       :seon.error/expected "assistant text after reasoning"})

      (and (= "length" finish-reason) (str/blank? text) (empty? unknown))
      (declared-error :seon.ai/token-starvation-error 'seon.ai/parsed-completion
                      {:seon.error/offending finish-reason
                       :seon.error/message "The provider exhausted the completion budget before replying."
                       :seon.error/data evidence
                       :seon.ai/exhausted-finish-reason finish-reason
                       :seon.error/member :seon.ai/finish-reason
                       :seon.error/expected "a completion budget sufficient for assistant text"})

      (or (seq text) (seq unknown) (= "stop" finish-reason))
      (cond-> {:seon.ai/text text}
        (seq unknown) (assoc :seon.ai/unknown-tool-calls unknown)
        (and (string? reasoning-content) (seq reasoning-content))
        (assoc :seon.ai/reasoning-content reasoning-content)
        (map? usage) (assoc :seon.ai/usage usage)
        (and (string? response-model) (seq response-model)) (assoc :seon.ai/response-model response-model)
        (some? tokens) (assoc :seon.ai/tokens tokens)
        (string? finish-reason)
        (assoc :seon.ai/finish-reason finish-reason))

      :else
      (unparseable 'seon.ai/parsed-completion "choices.message"
                   "The provider's response carried no assistant text."
                   {:seon.error/offending evidence :seon.error/data evidence}))))

(defn completion-text
  "The assistant text in a decoded provider response, or a flat error.
  Pure, and the one `:any` in this namespace: the response is a foreign
  document. A body that does not carry the expected shape returns
  `::unparseable-body` with what was actually there — never nil, which
  would read downstream as an empty reply. A normal stop with empty content
  is an ordinary completion; the reply reader diagnoses the missing form."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The decoded provider HTTP body is foreign data of any JSON shape; this total boundary reports malformed bodies as error values.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.ai/parsed-completion]}
  [body]
  (let [body-shape (cond
                     (map? body) (vec (sort (keys body)))
                     (nil? body) ::nil
                     :else (str (class body)))]
    (if (and (map? body) (some? (get body "error")))
      (unparseable 'seon.ai/completion-text "body"
                   "The provider returned an error document."
                   {:seon.error/offending body
                    :seon.error/data {::body-shape body-shape}})
      (let [choice (when (map? body) (some-> (get body "choices") first))
            message (some-> choice (get "message"))
            content (some-> message (get "content"))
            reasoning-content (some-> message (get "reasoning_content"))
            tool-calls (some-> message (get "tool_calls"))
            finish-reason (some-> choice (get "finish_reason"))
            usage (when (map? body) (get body "usage"))]
        (if (and (some? tool-calls) (not (and (sequential? tool-calls) (every? map? tool-calls))))
          (unparseable 'seon.ai/completion-text "choices.message.tool_calls"
                       "The provider's tool calls were not an array of objects."
                       {:seon.error/offending tool-calls :seon.error/data {::body-shape body-shape}})
          (parsed-completion
           content
           reasoning-content
           finish-reason
           usage
           (some-> usage (get "completion_tokens"))
           body-shape
           (mapv #(get % "function" %) tool-calls)
           (when (map? body) (get body "model"))))))))

(defn normalize-usage
  "Comparable token counts derived from one open provider usage document."
  {:malli/schema [:=> [:cat :seon.ai/usage] :seon.ai/normalized-usage]}
  [usage]
  (into {}
        (keep (fn [[attribute value]] (when (int? value) [attribute value])))
        {:seon.ai.usage/prompt-tokens (get usage "prompt_tokens")
         :seon.ai.usage/completion-tokens (get usage "completion_tokens")
         :seon.ai.usage/total-tokens (get usage "total_tokens")
         :seon.ai.usage/cached-tokens (or (get usage "cached_tokens")
                                          (get usage "prompt_cache_hit_tokens")
                                          (get-in usage ["prompt_tokens_details" "cached_tokens"]))}))

(defn model-observation-tx
  "Return the noHistory gauge upsert, or no transaction data.
  data when the effective model has no registry row. Durable attempt facts
  remain the usage authority; this contains only current display gauges."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.ai.model/observation-request]
    :seon.db/tx-data]}
  [database observation]
  (if (model-entity database (:seon.ai.model/id observation))
    (let [latency-ms (:seon.ai.model/last-latency-ms observation)
          completion-tokens
          (some-> (:seon.ai/usage observation)
                  normalize-usage
                  :seon.ai.usage/completion-tokens)]
      (cond-> [[:db/add [:seon.ai.model/id (:seon.ai.model/id observation)]
                 :seon.ai.model/last-used-at (:seon.ai.model/last-used-at observation)]]
        (some? latency-ms)
        (conj [:db/add [:seon.ai.model/id (:seon.ai.model/id observation)]
               :seon.ai.model/last-latency-ms latency-ms])
        (and (pos? (or latency-ms 0)) (some? completion-tokens))
        (conj [:db/add [:seon.ai.model/id (:seon.ai.model/id observation)]
               :seon.ai.model/last-tokens-per-second
               (/ (* 1000.0 completion-tokens) latency-ms)])))
    []))

(defn credential
  "The API key named by `:seon.ai/api-key-variable`, or nil.
  THE one place the environment is read, and the reason a credential
  never becomes a datom: the database carries the variable's NAME, this
  reads its value at the leaf, and nothing in between ever holds it.
  A request carrying `:seon.ai/environment` resolves the name there
  instead, so a test supplies a key without exporting anything."
  {:malli/schema [:=> [:cat :seon.ai/api-key-variable] [:maybe :string]]}
  [variable]
  ;; a nil name is our own configuration mistake, and errors-as-values
  ;; covers our mistakes too: (System/getenv nil) throws an NPE, which
  ;; would reach the loop as an unclassifiable failure instead of the
  ;; no-credential value the caller already knows how to read
  (when (string? variable)
    (System/getenv variable)))

(defn- status-class
  "One HTTP status as a normalized class.
  Ranges and the four statuses that mean something specific — the
  provider's own vocabulary, read rather than re-invented. 408/429 and
  5xx are the provider saying \"not now\", which is a different fact
  from 400 saying \"not this request\"."
  [status]
  (cond
    (contains? #{401 402} status) :authentication
    (= 403 status) :authorization
    (= 404 status) :model
    (= 408 status) :rate-limit
    (= 429 status) :rate-limit
    (<= 500 status 599) :server
    :else :request))

(defn- transport-before-send?
  "True when the JDK PROVES the request never left this machine.
  Derived from the JDK's own exception taxonomy — a connection that was
  refused, a host that does not resolve, a TLS handshake that failed —
  because phase is not something to guess at. Anything else is
  `:transport-unknown` and is treated as transmitted: the no-retry
  ruling is strict, and \"we cannot prove it was free\" is not \"it was
  free\"."
  [failure]
  (boolean
   (some (fn [candidate] (instance? candidate failure))
         [java.net.ConnectException
          java.net.UnknownHostException
          java.net.NoRouteToHostException
          javax.net.ssl.SSLHandshakeException
          java.net.http.HttpConnectTimeoutException])))

(defn- caused-by?
  [failure throwable-class]
  (boolean
   (some #(.isInstance ^Class throwable-class %)
         (take-while some? (iterate ex-cause failure)))))

(defn- interrupt-exit!
  "Throw a stop's interrupt when `failure` is one; nil otherwise.

  A stop interrupts the turn thread (`seon.cluster.agent/interrupt-turn!`),
  and interrupted execution never resumes: an interrupt is not a transport
  outcome to record and continue past. `HttpClient.send` throws it bare with
  the flag cleared (`HttpClientImpl.java:1010-1018`, openjdk 26.0.1); an
  interrupted body read throws `IOException` caused by it
  (`ResponseSubscribers.java:397-403`). Either way this restores the flag and
  throws an `InterruptedException`, the wrapped one carrying the whole chain as
  its cause, so the turn's declared `InterruptedException` case receives it."
  {:malli/schema [:=> [:cat :seon.error/throwable] :nil]}
  [failure]
  (when (caused-by? failure InterruptedException)
    (.interrupt (Thread/currentThread))
    (throw (if (instance? InterruptedException failure)
             failure
             (doto (InterruptedException. (str (ex-message failure)))
               (.initCause failure))))))

(defn- interruptible-lines
  "Lines from `reader`; a read failure ends the sequence as its last element,
  `{::read-failure throwable}`, rather than throwing.

  This is the whole reason a truncated stream can keep what arrived: the
  fold consumes an ordinary sequence, and a sequence that ENDS on a
  transport failure leaves the accumulated snapshot, with the failure as its
  terminal event, in the caller's hands instead of unwinding past it."
  [^BufferedReader reader]
  (lazy-seq
   (let [line (try (.readLine reader)
                   (catch IOException read-failure
                     (interrupt-exit! read-failure)
                     {::read-failure read-failure}))]
     (cond
       (map? line) (list line)
       (some? line) (cons line (interruptible-lines reader))))))

(defn- truncation
  "ONE flat `::stream-truncated` error value for a stream that ended
  before its terminal event.

  One value, both arms. When nothing arrived this IS the call's outcome;
  when text arrived it rides beside the text as
  `:seon.ai/truncation`, so the same fact reaches the durable record
  either way and nothing has to reconstruct \"how did this end\" from a
  second shape. Its data is EVIDENCE ONLY: the characters actually
  folded, the JDK's whole cause chain, and whether this thread was
  interrupted — an interrupt reaches a reader as a closed stream too,
  and the two have completely different owners."
  {:malli/schema [:=> [:cat :seon.ai/partial [:maybe :seon.error/throwable]] :seon.ai/stream-truncated-error]}
  [snapshot failure]
  (let [received (count (:seon.ai/text snapshot))
        reasoning-received (count (:seon.ai/reasoning-partial snapshot))
        time-limit-fired? (caused-by? failure
                                      java.net.http.HttpTimeoutException)]
    ;; THE JDK PUTS THE REAL CAUSE IN THE CAUSE: an early-ended body is
    ;; `IOException: closed` with the actual failure underneath
    ;; (`ResponseSubscribers.java:355-380`, openjdk 26.0.1). The diagnostic
    ;; records that whole chain as `:seon.error/chain`.
    (declared-error :seon.ai/stream-truncated-error 'seon.ai/truncation
                    (cond-> {:seon.error/offending snapshot
                             :seon.ai/interrupted-text-count received
                             :seon.error/message "The provider stream ended before its terminal event; inspect the retained partial output and transport evidence."
                             :seon.error/data (cond-> {::text-received received
                                                       ::time-limit-fired? time-limit-fired?
                                                       ::thread-interrupted? (.isInterrupted (Thread/currentThread))}
                                                (pos? reasoning-received)
                                                (assoc ::reasoning-received reasoning-received))
                             :seon.error/member :seon.ai/text
                             :seon.error/expected "a terminal stream event"}
                      failure (assoc :seon.error/throwable failure)))))

(defn- truncated-completion
  "One completion value for a 2xx stream that ended before its terminal.

  THE TURN KEEPS WHAT ARRIVED. The provider generated and charged for
  the text already folded, so discarding it to report a clean failure
  costs money AND the agent's progress. When any text arrived this is an
  ORDINARY completion carrying the truncation fact beside it —
  downstream settles the forms that arrived, and the truncation is
  queryable rather than inferred. When nothing arrived there is no
  partial to keep, so the truncation IS the outcome, and it says that
  explicitly instead of blaming an unreadable body.

  NOTHING HERE RETRIES (owner ruling, 2026-07-27 late). The evidence
  says output was observed exactly when it was, `disposition` reads that
  evidence, and a re-request is never issued on either arm."
  {:malli/schema [:=> [:cat :seon.ai/partial [:maybe :seon.error/throwable]] [:or :seon.ai/successful-completion :seon.ai/stream-truncated-error]]}
  [snapshot failure]
  (let [ended (truncation snapshot failure)]
    (if (seq (:seon.ai/text snapshot))
      (-> snapshot
          (select-keys [:seon.ai/text :seon.ai/reasoning-partial :seon.ai/usage
                        :seon.ai/tokens :seon.ai/finish-reason :seon.ai/response-model])
          (set/rename-keys {:seon.ai/reasoning-partial :seon.ai/reasoning-content})
          (assoc :seon.ai/truncation ended))
      ended)))

(defn- streamed-completion
  "Read an SSE body and return one completion value.

  A streamed call and a one-shot call return the SAME shape — that is
  the point, and it is why this lives beside `completion-text` rather
  than replacing it: everything downstream, including the attempt
  facts, is untouched by which transport
  ran.

  Reads visible-text replies to natural EOF because reply evaluation is `:batch`
  (`docs/seon/reference/llm-adapters.md:545-556`): the complete program
  is parsed once, so aborting on the first form would be a different
  evaluation mode, not an optimization.

  THREE WAYS A STREAM ENDS, and all three are values here: its terminal
  event (an ordinary completion), a payload this cannot read (a flat
  data error — the fold stops rather than joining text across the gap),
  and the transport ending early (`truncated-completion`, which keeps
  what arrived). A read failure never unwinds past the snapshot, so a
  mid-stream disconnect cannot discard billed output.

  A reasoning-only choice ends at its provider finish reason rather than
  waiting for EOF or the HTTP time limit: that finish signal proves no
  assistant text will follow for the finished choice. A normal stop with
  empty text reaches the reply reader, just as `completion-text` does."
  {:malli/schema [:=> [:cat :seon.blob/input-stream [:maybe :seon.ai/sink]] [:or :seon.ai/parsed-completion :seon.ai/stream-truncated-error]]}
  [body sink]
  (with-open [reader (BufferedReader. (InputStreamReader. body "UTF-8"))]
    (let [ended (stream-fold (interruptible-lines reader) sink)
          failure (::read-failure ended)
          snapshot (dissoc ended ::read-failure)]
      (cond
        (contains? snapshot :seon.ai/unreadable-response-member) snapshot
        (some? failure) (truncated-completion snapshot failure)
        :else
        (parsed-completion
         (:seon.ai/text snapshot)
         (:seon.ai/reasoning-partial snapshot)
         (:seon.ai/finish-reason snapshot)
         (:seon.ai/usage snapshot)
         (:seon.ai/tokens snapshot)
         ::empty-stream
         (vec (vals (:seon.ai/tool-calls snapshot)))
         (:seon.ai/response-model snapshot))))))

(defn- output-observed?
  [evidence]
  (if (or (contains? evidence ::text-received)
          (contains? evidence ::reasoning-received))
    (or (pos? (get evidence ::text-received 0))
        (pos? (get evidence ::reasoning-received 0)))
    true))

(defn- http-request-data
  "Ordinary request data for the JDK leaf."
  [request api-key body]
  (cond-> {:seon.ai/endpoint (:seon.ai/endpoint request)
           :seon.ai/timeout-ms (:seon.ai/timeout-ms request)
           :seon.ai/stream? (boolean (:seon.ai/stream? request))
           :seon.ai.http/headers (request-headers api-key)
           ;; Serialize once. This exact string is both what the JDK posts and
           ;; what the attempt records, so observability cannot rebuild a body
           ;; that differs from the transmitted bytes.
           :seon.ai.http/body (json/write-str body)}
    (:seon.ai/sink request)
    (assoc :seon.ai/sink (:seon.ai/sink request))))

(defonce ^{:private true :tag HttpClient
           :doc "THE process's one HTTP client, exactly as `seon.web.jvm`
  holds one (`src/seon/web/jvm.clj:21-24`). A JDK `HttpClient` owns a
  connection pool and a selector thread; building one per request throws
  both away every call, so nothing is ever kept alive and each provider
  call pays a fresh connection and a fresh thread. The deadline stays
  per-request on the request builder, which is where the JDK puts it.

  This is NOT the fix for the mid-stream disconnects — that hypothesis
  was tested and refuted (`tmp/provider-transport/`, and the JDK holds an
  operation reference for the whole body read:
  `Http1Response.java:119-147`, `Http2Connection.java:1565-1580`). It is
  the right shape on its own merits."}
  client
  (.build (HttpClient/newBuilder)))

(defn- send-request
  "Send one ordinary request map through the JDK HTTP leaf.
  A transport `IOException` is a value; a stop's interrupt throws
  (`interrupt-exit!`) with the thread's interrupt flag set."
  {:malli/schema [:=> [:cat [:map [:seon.ai/endpoint :seon.ai/endpoint] [:seon.ai/timeout-ms :seon.ai/timeout-ms] [:seon.ai/stream? :boolean] [:seon.ai.http/headers [:map-of :string :string]] [:seon.ai.http/body :seon.ai.http/body] [:seon.ai/sink {:optional true} :seon.ai/sink]]] :seon.ai/http-completion]}
  [{:keys [:seon.ai/endpoint :seon.ai/timeout-ms
           :seon.ai.http/headers :seon.ai.http/body]
    stream? :seon.ai/stream?
    sink :seon.ai/sink}]
  (let [;; THE one deadline, and it is the HTTP client's own: a request
        ;; timeout the JVM enforces, not a wrapper thread racing the call.
        builder (-> (HttpRequest/newBuilder (URI/create endpoint))
                    (.timeout (Duration/ofMillis (long timeout-ms))))
        http-request
        (-> (reduce-kv (fn [request-builder header-name value]
                         (.header request-builder header-name value))
                       builder
                       headers)
            (.POST (HttpRequest$BodyPublishers/ofString body))
            (.build))]
    (try
      ;; ONE attempt. Synchronous send on the calling thread — the
      ;; loop proc is :io and may block; there is no retry, no
      ;; backoff, and no second provider to fall back to.
      (let [response (.send client http-request
                            (if stream?
                              ;; ofInputStream, not ofLines. `ofLines`
                              ;; does not yield incrementally on a
                              ;; stream the client holds open, which
                              ;; is exactly the trap the web slice hit
                              ;; reading SSE: it reported nothing for
                              ;; a feed that was working perfectly.
                              ;; Reading the stream ourselves is the
                              ;; behaviour we have proven.
                              (HttpResponse$BodyHandlers/ofInputStream)
                              (HttpResponse$BodyHandlers/ofString)))
            status (.statusCode response)
            ;; Response-body custody is born here and never recovered from
            ;; the shared client or looked up again through the response.
            ;; Exactly this attempt passes it to exactly one reader, whose
            ;; `with-open` owns its close. Another attempt has a different
            ;; body value even though both share the process's client.
            body (.body response)
            ;; a non-2xx body has to be readable either way, and a
            ;; stream's body is only readable once
            read-body (fn [] (if stream?
                               (slurp body)
                               body))]
        (if (<= 200 status 299)
          (try
            (let [completion
                  (if stream?
                    (streamed-completion body sink)
                    (completion-text (json/read-str body)))]
              (if (or (contains? completion :seon.ai/unreadable-response-member)
                      (contains? completion :seon.ai/unanswered-reasoning-count)
                      (contains? completion :seon.ai/exhausted-finish-reason)
                      (contains? completion :seon.ai/interrupted-text-count))
                ;; DERIVED, NEVER ASSERTED. `::output-observed?` used to be a
                ;; hardcoded `true` on every 2xx failure, so the attempt entity
                ;; said "output WAS seen" about streams that delivered nothing,
                ;; and `disposition` reads it. A truncation counted its
                ;; characters; every other 2xx failure holds a body the
                ;; provider generated and charged for.
                (update completion :seon.error/data merge
                        (answered status (output-observed? (:seon.error/data completion)))
                        {::error-class :response})
                completion))
            ;; `json/read-str` refuses with a plain `Exception`
            ;; (clojure.data.json); a stop's interrupt is not unreadable JSON.
            (catch Exception failure
              (interrupt-exit! failure)
              ;; a 2xx body EXISTS, so the provider generated and charged for
              ;; output even though we cannot read it
              (unparseable 'seon.ai/send-request "body"
                           "The provider response was not readable JSON; inspect the retained response evidence."
                           {:seon.error/throwable failure
                            :seon.error/offending body
                            :seon.error/data (assoc (answered status true) ::error-class :response)})))
          (let [body (str (read-body))]
            ;; a rejection carries no generated output; a 2xx would not be here
            (assoc {:seon.error/at (java.util.Date.)
                        :seon.error/layer :seon.ai/request
                        :seon.error/operation 'seon.ai/send-request
                        :seon.error/message (str "The provider answered " status ".")
                        :seon.error/data (assoc (answered status false)
                                                ::body body
                                                ::error-class (status-class status))
                        :seon.ai/provider-error status
                        :seon.error/member :seon.ai/http-status
                        :seon.error/expected "an HTTP success response"} :seon.error/type :seon.ai/provider-error-error))))
      (catch java.net.http.HttpTimeoutException failure
        ;; an ordinary outcome: the model was slow. Never a bug report.
        ;; A CONNECT timeout never transmitted anything; any other
        ;; timeout may have. The JDK distinguishes them by class
        ;; (`HttpConnectTimeoutException extends HttpTimeoutException`),
        ;; which is the only honest way to know, and "cannot prove it
        ;; was free" is not "it was free".
        (let [connect? (instance? java.net.http.HttpConnectTimeoutException
                                  failure)]
          (declared-error :seon.ai/timeout-error 'seon.ai/send-request
                          {:seon.error/offending timeout-ms
                           :seon.error/message (str "The model did not answer within " timeout-ms "ms.")
                           :seon.error/data (assoc unsent
                                                   :seon.ai/timeout-ms timeout-ms
                                                   ::error-class (if connect? :transport-before-send :timeout)
                                                   ::request-transmitted? (not connect?))
                           :seon.ai/timeout timeout-ms
                           :seon.error/member :seon.ai/timeout-ms
                           :seon.error/expected "a response within the request deadline"})))
      (catch InterruptedException stop
        (interrupt-exit! stop))
      ;; The declared transport case: the JDK reports every exchange failure
      ;; as an `IOException` (`HttpClientImpl.java:1019-1062`). Anything else
      ;; is a fault of this code and propagates to the turn's boundary.
      (catch IOException failure
        (interrupt-exit! failure)
        (let [before-send? (transport-before-send? failure)]
          (declared-error :seon.ai/transport-failure-error 'seon.ai/send-request
                          {:seon.error/message (or (ex-message failure) (.getName (class failure)))
                           :seon.error/data (assoc unsent
                                                   :seon.ai/endpoint endpoint
                                                   ::throwable (.getName (class failure))
                                                   ::error-class (if before-send? :transport-before-send :transport-unknown)
                                                   ::request-transmitted? (not before-send?))
                           :seon.ai/transport-failure endpoint
                           :seon.error/throwable failure
                           :seon.error/member :seon.ai/endpoint
                           :seon.error/expected "a completed HTTP exchange"}))))))

(defn complete
  "Call the model once and return its text, or a flat error value.
  ONE attempt. No retry, no backoff, no fallback provider. A request
  either names its credential variable or explicitly declares no-auth.

  Flat `:seon.error` values; the one throw is a stop's
  `InterruptedException`, because interrupted execution never resumes:
  - `::no-credential` — the named environment variable is unset. This
    is a configuration fault, and it is loud in the value rather than
    silent in a nil;
  - `::timeout` — the deadline fired. An ordinary outcome;
  - `::transport-failure` — the request never completed;
  - `::provider-error` — a non-2xx response, carrying its status;
  - `::unparseable-body` — 2xx with a body this cannot read;
  - `::stream-truncated` — 2xx whose stream ended before ANY assistant
    text arrived. A stream that ends early after some text arrived is
    NOT an error: it returns the ordinary completion carrying a
    `:seon.ai/truncation` fact, so the turn keeps what was paid for.

  The request may carry `:seon.ai/send!`, the HTTP leaf (the JDK leaf when
  absent), and `:seon.ai/environment`, the names-to-values map its
  credential variable resolves in (the process environment when absent)."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.ai/request] :seon.ai/completion]}
  [projection {:keys [:seon.ai/api-key-variable]
    no-auth :seon.config.ai/no-auth
    ;; The HTTP leaf and the environment credential names resolve in, as
    ;; values: a caller supplies a local leaf or a test key without
    ;; redefining a Var or exporting a variable.
    send! :seon.ai/send!
    environment :seon.ai/environment
    :or {send! send-request}
    :as request}]
  (let [body (request-body projection request)
        api-key (when-not no-auth
                  (if environment
                    (get environment api-key-variable)
                    (credential api-key-variable)))]
    (cond
      (or (contains? body :seon.ai/extra-body-edn)
          (contains? body :seon.ai/protected-keys))
      body

      (or no-auth api-key)
      (let [started (System/nanoTime)
            request-data (http-request-data request api-key body)
            result (send! request-data)]
        (assoc result
               :seon.ai.model/last-latency-ms
               (long (/ (- (System/nanoTime) started) 1000000))))

      :else
      ;; NO NETWORK CALL HAPPENED. This is the one failure that is provably free.
      (declared-error :seon.ai/no-credential-error 'seon.ai/complete
                      {:seon.error/offending api-key-variable
                       :seon.error/message (if (string? api-key-variable)
                                             (str "The environment variable " api-key-variable " is not set.")
                                             "No credential variable was configured.")
                       :seon.error/data (assoc unsent
                                               :seon.ai/api-key-variable api-key-variable
                                               ::error-class :credential)
                       :seon.ai/missing-credential-variable api-key-variable
                       :seon.error/member :seon.ai/api-key-variable
                       :seon.error/expected "a nonempty credential in the named environment variable"}))))
