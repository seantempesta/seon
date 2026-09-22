(ns seon.ai
  "One call, one attempt, one deadline. Failure is a value.

  NOTHING RETRIES A PAID CALL (owner ruling, 2026-07-27 late). Not
  here, not in the loop, not on crash recovery. A lost call is lost;
  the agent is told and adapts.

  What that ruling forbids is re-calling a model that MAY HAVE DONE
  PAID WORK. It does not forbid a second call after a conclusively
  unpaid refusal, and `disposition` is the one place that distinction
  is computed — from transport-phase EVIDENCE the leaf recorded, never
  from a list of error kinds. Everything else in this namespace is data
  the caller reduces:

  - `targets` derives the primary descriptor row and the OPTIONAL
    backup from the effective config dials. ONE derivation for both
    roles, so a role can never drift into its own assembly code;
  - `disposition` answers `:failover-now` / `:backoff` / `:fail`;
  - `delays` derives the finite backoff schedule as a vector of waits.

  `complete` STILL MAKES EXACTLY ONE ATTEMPT. Failover and backoff are
  the CALLER's reduce over these values (`seon.turn`'s `:call`
  branch), which is why this namespace holds no attempt count and no
  state: every attempt is one `complete` call and one durable
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
  (:import [java.io BufferedReader InputStreamReader]
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
(schema/register-core-predicate! 'seon.ai/sink? sink?)

(schema.edn/load! {})

(defn- attempt-without-private-provider-data
  [unit]
  (let [attempt (into (sorted-map-by #(compare (str %1) (str %2)))
                      (dissoc (:seon.render/value unit unit)
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
  "Render an attempt without its prompt-bearing body or provider reasoning."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (render.value/render-ai (attempt-without-private-provider-data unit)))

(defn attempt-html
  "Render ordinary attempt facts; body and reasoning stay queryable only."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
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

(defn- ordinary-model-row
  [row]
  (cond-> row
    (:seon.ai.model/provider row)
    (update :seon.ai.model/provider pulled-ref-id)

    (:seon.ai.model/deepseek-off-peak-windows row)
    (update :seon.ai.model/deepseek-off-peak-windows
            #(into #{} (map pulled-ref-id) %))

    (:seon.ai.model/input-modalities row)
    (update :seon.ai.model/input-modalities set)

    (:seon.ai.model/thinking-dials row)
    (update :seon.ai.model/thinking-dials set)))

(defn model-row
  "The registered row for one model id, or nil."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.ai.model/id]
    [:maybe :seon.ai.model/entity]]}
  [database model-id]
  (some-> (db/pull database model-pull [:seon.ai.model/id model-id])
          ordinary-model-row))

(defn models
  "Every registered model row, ordered by model id."
  {:malli/schema
   [:=> [:cat :seon.db/database-value] :seon.ai.model/models]}
  [database]
  (->> (db/q '[:find [?model-id ...]
               :where [_ :seon.ai.model/id ?model-id]]
             database)
       sort
       (mapv #(model-row database %))))

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

(defn model-ai
  "Render one model's capabilities, economics, and latest observation."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (let [model (rendered-model unit)
        provider (:seon.ai.model/provider model)
        price-parts
        (keep identity
              [(labelled-price
                "input" (:seon.ai.model/input-usd-per-mtok model))
               (labelled-price
                "cached input"
                (:seon.ai.model/cached-input-usd-per-mtok model))
               (labelled-price
                "output" (:seon.ai.model/output-usd-per-mtok model))])]
    (str
     "Model " (:seon.ai.model/id model)
     (when-let [provider-id (:seon.ai.model/provider-id provider)]
       (str " · provider " provider-id))
     (when-let [context (:seon.ai.model/context-window-tokens model)]
       (str " · context " context " tokens"))
     (when-let [maximum (:seon.ai.model/max-output-tokens model)]
       (str " · maximum output " maximum " tokens"))
     (when (seq price-parts)
       (str "\nPricing: " (str/join ", " price-parts) "."))
     (when-let [modalities (seq (:seon.ai.model/input-modalities model))]
       (str "\nInputs: " (str/join ", " (sort (map name modalities))) "."))
     (when-let [latency (:seon.ai.model/last-latency-ms model)]
       (str "\nLatest: " latency " ms"
            (when-let [rate (:seon.ai.model/last-tokens-per-second model)]
              (str ", " (format "%.2f" (double rate)) " tokens/s"))
            (when-let [used-at (:seon.ai.model/last-used-at model)]
              (str " at " used-at))
            ".")))))

(defn model-html
  "Render one model as a readable registry card."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [model (rendered-model unit)
        provider (:seon.ai.model/provider model)
        details
        (keep identity
              [(when-let [provider-id (:seon.ai.model/provider-id provider)]
                 [:div [:dt "Provider"] [:dd provider-id]])
               (when-let [context (:seon.ai.model/context-window-tokens model)]
                 [:div [:dt "Context"] [:dd (str context " tokens")]])
               (when-let [maximum (:seon.ai.model/max-output-tokens model)]
                 [:div [:dt "Maximum output"]
                  [:dd (str maximum " tokens")]])
               (when-let [price (:seon.ai.model/input-usd-per-mtok model)]
                 [:div [:dt "Input"] [:dd (usd-per-million price)]])
               (when-let [price
                          (:seon.ai.model/cached-input-usd-per-mtok model)]
                 [:div [:dt "Cached input"] [:dd (usd-per-million price)]])
               (when-let [price (:seon.ai.model/output-usd-per-mtok model)]
                 [:div [:dt "Output"] [:dd (usd-per-million price)]])
               (when-let [latency (:seon.ai.model/last-latency-ms model)]
                 [:div [:dt "Latest latency"] [:dd (str latency " ms")]])
               (when-let [rate
                          (:seon.ai.model/last-tokens-per-second model)]
                 [:div [:dt "Latest speed"]
                  [:dd (str (format "%.2f" (double rate)) " tokens/s")]])])]
    [:article {:class "seon-family-entry seon-ai-model-entry"}
     [:h3 (:seon.ai.model/id model)]
     (into [:dl] details)]))

(defn provider-ai
  "Render one model provider descriptor without its credential value."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (let [provider (:seon.render/value unit unit)]
    (str "Model provider " (:seon.ai.model/provider-id provider)
         " · " (:seon.config.ai/endpoint provider)
         " · output budget field "
         (:seon.ai.model/output-token-wire-key provider) ".")))

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
                 (map #(model-ai {:seon.db/db database
                                  :seon.render/value %})
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
  (let [row (db/pull database '[(limit :seon.schema/references nil)]
                     [:seon.schema/key :seon.config/agent-overlay])]
    (cond
      (:seon.db/invalid-read row) row
      (seq (:seon.schema/references row))
      (set (:seon.schema/references row))
      :else
      (refusal/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.ai/settings
        :seon.error/operation 'seon.ai/agent-setting-attributes
        :seon.error/message "The agent overlay schema has no declared setting references."
        :seon.error/diagnostic-layer :seon.ai/settings
        :seon.error/diagnostic-operation 'seon.ai/agent-setting-attributes
        :seon.error/diagnostic-member :seon.schema/references
        :seon.error/diagnostic-expected "installed agent-overlay setting references"
        :seon.error/diagnostic-offending row
        :seon.error/diagnostic-cause :seon.ai/missing-setting-references
        :seon.error/diagnostic-evidence {}
        :seon.schema/refused-value row
        :seon.schema/expected-value :seon.config/agent-overlay}))))

(defn agent-overlay
  "Declared setting overrides for one agent in a database value."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.agent/id]
    [:or :seon.config/agent-overlay :seon.db/invalid-read-error :seon.schema/validation-refusal]]}
  [db agent-id]
  (let [attributes (agent-setting-attributes db)]
    (if (or (:seon.db/invalid-read attributes)
            (:seon.schema/expected-value attributes))
      attributes
      (let [row (db/pull db [{:seon.agent/settings (vec attributes)}]
                         [:seon.agent/id agent-id])]
        (if (:seon.db/invalid-read row)
          row
          (select-keys (:seon.agent/settings row) attributes))))))

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
   (let [primary-settings (primary-setting-entries projection dials)
         primary
         (if (:seon.config.ai/no-auth dials)
           (dissoc primary-settings :seon.ai/api-key-variable)
           (dissoc primary-settings :seon.config.ai/no-auth))]
     (cond-> {:seon.ai/primary primary}
       (:seon.config.ai.backup/model dials)
       (assoc :seon.ai/backup
              (cond-> (assoc primary :seon.ai/model
                             (:seon.config.ai.backup/model dials))
                (:seon.config.ai.backup/endpoint dials)
                (assoc :seon.ai/endpoint
                       (:seon.config.ai.backup/endpoint dials))
                (:seon.config.ai.backup/api-key-variable dials)
                (->
                 (dissoc :seon.config.ai/no-auth)
                 (assoc :seon.ai/api-key-variable
                        (:seon.config.ai.backup/api-key-variable dials)))
                (:seon.config.ai.backup/timeout-ms dials)
                (assoc :seon.ai/timeout-ms
                       (:seon.config.ai.backup/timeout-ms dials)))))))

(defn targets
  "The primary descriptor row and the OPTIONAL backup, from the dials.
  PURE, and the ONE assembly for both roles — a second hand-written
  provider map somewhere else is how two roles start to disagree about
  what a target is.

  THE BACKUP IS OVERRIDES OVER THE PRIMARY, and that shape is chosen to
  make a PARTIAL backup unrepresentable rather than merely refused.
  `:seon.config.ai.backup/model` decides whether a backup exists at
  all; endpoint, credential variable and deadline are optional and
  inherit the primary's. Descriptor resolution replaces inherited
  authentication when the backup model names a different provider;
  an explicit backup credential variable still wins. So there is no
  configuration in which three backup dials are set and the fourth
  silently voids them, and the common case — a different model at the
  same provider — is one dial rather than four copied lines.

  Absence, never nil: with no backup the key is simply not there, which
  is exactly what `:seon.ai/backup?` reads downstream."
  {:malli/schema
   [:function
    [:=> [:cat :seon.schema/projection :seon.config/effective] :seon.ai/targets]
    [:=> [:cat :seon.schema/projection :seon.db/database-value :seon.config/effective]
     :seon.ai/targets]]}
  ([projection dials] (configured-targets projection dials))
  ([projection database dials]
   (let [{:seon.ai/keys [primary backup]} (configured-targets projection dials)
         primary-model (model-details database (:seon.ai/model primary))
         backup-model (some->> backup :seon.ai/model
                               (model-details database))
         primary-provider-id
         (get-in primary-model
                 [:seon.ai.model/provider :seon.ai.model/provider-id])
         backup-provider-id
         (get-in backup-model
                 [:seon.ai.model/provider :seon.ai.model/provider-id])
         ;; Authentication inherited while assembling a backup is a valid
         ;; caller choice only when both models use the same provider. Across
         ;; providers, the backup descriptor supplies its own declaration;
         ;; an explicit backup variable still wins below.
         backup
         (cond-> backup
           (and backup-provider-id
                (not= primary-provider-id backup-provider-id)
                (not (:seon.config.ai.backup/api-key-variable dials)))
           (dissoc :seon.ai/api-key-variable :seon.config.ai/no-auth))]
     (cond-> {:seon.ai/primary
              (resolved-target primary primary-model)}
       backup
       (assoc :seon.ai/backup
              (resolved-target backup backup-model))))))

(defn retry-strategy
  "The backoff strategy row, from the dials. Pure projection.
  Its own function for the same reason `targets` is: the strategy is
  read in two places (the loop's handle and a seeded property) and
  neither may build it by hand."
  {:malli/schema [:=> [:cat :seon.config/effective] :seon.ai.retry/strategy]}
  [dials]
  {:seon.ai.retry/base-delay-ms (:seon.config.ai.retry/base-delay-ms dials)
   :seon.ai.retry/multiplier (:seon.config.ai.retry/multiplier dials)
   :seon.ai.retry/jitter-fraction
   (:seon.config.ai.retry/jitter-fraction dials)
   :seon.ai.retry/maximum-delay-ms
   (:seon.config.ai.retry/maximum-delay-ms dials)
   :seon.ai.retry/maximum-retries (:seon.config.ai.retry/maximum-retries dials)
   :seon.ai.retry/maximum-total-delay-ms
   (:seon.config.ai.retry/maximum-total-delay-ms dials)})

(defn delays
  "The finite backoff schedule as a vector of waits in milliseconds.
  PURE — randomness is INJECTED as a zero-arg function returning
  `[0,1)`, so a seeded property pins the whole schedule and there is no
  hidden clock or hidden generator anywhere in the retry path.

  The composition is `again`'s, read from
  `reference-code`-equivalent shape rather than reinvented:
  multiplicative growth, randomized by a jitter fraction, each delay
  clamped, the count bounded, and the CUMULATIVE budget bounded — the
  last one is what actually binds, because a turn holds its run's
  custody and a backed-off turn that waits forever is worse than a
  turn that gave up.

  The result is a VALUE, not a control structure: the caller reduces
  over it, each element is one wait before one more attempt, and an
  empty vector means `:backoff` degenerates to `:fail` with nothing
  special-cased."
  {:malli/schema [:=> [:cat :seon.ai.retry/strategy [:=> [:cat] :double]]
                  :seon.ai.retry/delays]}
  [{:seon.ai.retry/keys [base-delay-ms multiplier jitter-fraction
                         maximum-delay-ms maximum-retries
                         maximum-total-delay-ms]}
   random]
  (loop [remaining maximum-retries
         raw (double base-delay-ms)
         spent 0
         schedule []]
    (if (zero? remaining)
      schedule
      ;; jitter is SYMMETRIC around the raw delay — `again`'s
      ;; randomize-strategy spreads either side rather than only
      ;; lengthening, so the mean schedule is the schedule
      (let [spread (* raw jitter-fraction)
            jittered (+ (- raw spread) (* 2.0 spread (random)))
            clamped (long (min (double maximum-delay-ms) (max 0.0 jittered)))
            ;; the cumulative budget is a HARD stop, not a trim: a
            ;; final wait shortened to fit would be a wait nobody
            ;; configured, and the honest answer to "no budget left" is
            ;; to stop retrying
            budgeted (+ spent clamped)]
        (if (> budgeted maximum-total-delay-ms)
          schedule
          (recur (dec remaining)
                 (* raw multiplier)
                 budgeted
                 (conj schedule clamped)))))))

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
          (refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.ai/request
       :seon.error/operation 'seon.ai/extra-body
       :seon.error/diagnostic-layer :seon.ai/request
       :seon.error/diagnostic-operation 'seon.ai/extra-body
       :seon.error/diagnostic-member :seon.ai/extra-body-edn
       :seon.error/diagnostic-expected "an EDN map with string keys"
       :seon.error/diagnostic-offending encoded
       :seon.error/offending encoded
       :seon.error/diagnostic-cause :seon.ai/invalid-extra-body
       :seon.error/diagnostic-evidence {}
           :seon.error/message
           "The configured extra body must be an EDN map with string keys."
           :seon.error/data {::request-transmitted? false
                             ::response-started? false
                             ::output-observed? false}
           :seon.ai/extra-body-edn encoded})))
      (catch Throwable failure
        (refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.ai/request
       :seon.error/operation 'seon.ai/extra-body
       :seon.error/diagnostic-layer :seon.ai/request
       :seon.error/diagnostic-operation 'seon.ai/extra-body
       :seon.error/diagnostic-member :seon.ai/extra-body-edn
       :seon.error/diagnostic-expected "an EDN map with string keys"
       :seon.error/diagnostic-offending encoded
       :seon.error/offending encoded
       :seon.error/diagnostic-cause :seon.ai/invalid-extra-body
       :seon.error/diagnostic-evidence {:seon.error/throw-site-message (ex-message failure)}
         :seon.error/message
         "The configured extra body is not readable EDN; supply a map with string keys."
         :seon.error/data {::request-transmitted? false
                           ::response-started? false
                           ::output-observed? false}
         :seon.ai/extra-body-edn encoded})))
    {}))

(defn- request-headers
  [api-key]
  (cond-> {"content-type" "application/json"}
    api-key (assoc "authorization" (str "Bearer " api-key))))

(defn request-body
  "The provider request document, or a pre-call flat error. Pure."
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
                "messages" (cond-> []
                             system (conj {"role" "system" "content" system})
                             true (conj {"role" "user" "content" prompt}))}
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
          (refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.ai/request
       :seon.error/operation 'seon.ai/request-body
       :seon.error/diagnostic-layer :seon.ai/request
       :seon.error/diagnostic-operation 'seon.ai/request-body
       :seon.error/diagnostic-member :seon.ai/extra-body-edn
       :seon.error/diagnostic-expected "fields not owned by the request builder"
       :seon.error/diagnostic-offending extra
       :seon.error/offending extra
       :seon.error/diagnostic-cause :seon.ai/extra-body-conflict
       :seon.error/diagnostic-evidence {}
           :seon.error/message
           "The configured extra body cannot override request-builder fields."
           :seon.error/data {::protected-keys (vec (sort conflicts))
                             ::request-transmitted? false
                             ::response-started? false
                             ::output-observed? false}
           :seon.ai/protected-keys (set conflicts)})
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
  (refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.ai/request
       :seon.error/operation 'seon.ai/unreadable-stream-data
       :seon.error/diagnostic-layer :seon.ai/request
       :seon.error/diagnostic-operation 'seon.ai/unreadable-stream-data
       :seon.error/diagnostic-member :seon.ai/unreadable-response-member
       :seon.error/diagnostic-expected "provider JSON with a textual assistant response"
       :seon.error/diagnostic-offending payload
       :seon.error/offending payload
       :seon.error/diagnostic-cause :seon.ai/unparseable-body
       :seon.error/diagnostic-evidence {}
   :seon.error/message
   (str "The provider stream carried unreadable data: " reason ".")
   :seon.error/data
   {::body payload}
   :seon.ai/unreadable-response-member "body"}))

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
                  usage (get document "usage")
                  text (cond-> (:seon.ai/text snapshot)
                         (string? delta) (str delta))
                  reasoning
                  (when (or (contains? snapshot :seon.ai/reasoning-partial)
                            (string? reasoning-delta))
                    (str (:seon.ai/reasoning-partial snapshot)
                         reasoning-delta))]
              (cond-> (assoc snapshot :seon.ai/text text)
                (some? reasoning)
                (assoc :seon.ai/reasoning-partial reasoning)
                ;; newest usage wins, and only when the provider sent one
                (map? usage) (assoc :seon.ai/usage usage)
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
            (let [next-snapshot (stream-event snapshot line)]
              (if (contains? next-snapshot :seon.ai/unreadable-response-member)
                (reduced next-snapshot)
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

(defn- parsed-completion
  {:malli/schema [:=> [:cat :seon.schema/value :seon.schema/value :seon.schema/value :seon.schema/value :seon.schema/value :seon.schema/value] :seon.ai/parsed-completion]}
  [content reasoning-content finish-reason usage tokens body-shape]
  (let [evidence (cond-> {::body-shape body-shape}
                   (and (string? reasoning-content) (seq reasoning-content))
                   (assoc ::reasoning-content reasoning-content)
                   (string? finish-reason)
                   (assoc ::finish-reason finish-reason)
                   (map? usage) (assoc ::usage usage))]
    (cond
      (and (some? content) (not (string? content)))
      (refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.ai/request
       :seon.error/operation 'seon.ai/parsed-completion
       :seon.error/diagnostic-layer :seon.ai/request
       :seon.error/diagnostic-operation 'seon.ai/parsed-completion
       :seon.error/diagnostic-member :seon.ai/unreadable-response-member
       :seon.error/diagnostic-expected "provider JSON with a textual assistant response"
       :seon.error/diagnostic-offending content
       :seon.error/offending content
       :seon.error/diagnostic-cause :seon.ai/unparseable-body
       :seon.error/diagnostic-evidence {}
       :seon.error/message
       "The provider's assistant content was not text."
       :seon.error/data evidence
       :seon.ai/unreadable-response-member "choices.message"})

      (and (some? reasoning-content) (not (string? reasoning-content)))
      (refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.ai/request
       :seon.error/operation 'seon.ai/parsed-completion
       :seon.error/diagnostic-layer :seon.ai/request
       :seon.error/diagnostic-operation 'seon.ai/parsed-completion
       :seon.error/diagnostic-member :seon.ai/unreadable-response-member
       :seon.error/diagnostic-expected "provider JSON with a textual assistant response"
       :seon.error/diagnostic-offending reasoning-content
       :seon.error/offending reasoning-content
       :seon.error/diagnostic-cause :seon.ai/unparseable-body
       :seon.error/diagnostic-evidence {}
       :seon.error/message
       "The provider's assistant reasoning was not text."
       :seon.error/data evidence
       :seon.ai/unreadable-response-member "choices.message"})

      (and (string? reasoning-content)
           (seq reasoning-content)
           (not= "stop" finish-reason)
           (str/blank? content))
      (refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.ai/request
       :seon.error/operation 'seon.ai/parsed-completion
       :seon.error/diagnostic-layer :seon.ai/request
       :seon.error/diagnostic-operation 'seon.ai/parsed-completion
       :seon.error/diagnostic-member :seon.ai/text
       :seon.error/diagnostic-expected "assistant text after reasoning"
       :seon.error/diagnostic-offending reasoning-content
       :seon.error/offending reasoning-content
       :seon.error/diagnostic-cause :seon.ai/reasoning-without-answer
       :seon.error/diagnostic-evidence {}
       :seon.ai/unanswered-reasoning-count (count reasoning-content)
       :seon.error/message
       (str "The provider finished after streaming "
            (count reasoning-content)
            " characters of reasoning and no assistant text.")
       :seon.error/data
       (assoc evidence
              ::reasoning-received (count reasoning-content)
              ::text-received 0)})

      (and (= "length" finish-reason) (str/blank? content))
      (refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.ai/request
       :seon.error/operation 'seon.ai/parsed-completion
       :seon.error/diagnostic-layer :seon.ai/request
       :seon.error/diagnostic-operation 'seon.ai/parsed-completion
       :seon.error/diagnostic-member :seon.ai/finish-reason
       :seon.error/diagnostic-expected "a completion budget sufficient for assistant text"
       :seon.error/diagnostic-offending finish-reason
       :seon.error/offending finish-reason
       :seon.error/diagnostic-cause :seon.ai/token-starvation
       :seon.error/diagnostic-evidence {}
       :seon.error/message
       "The provider exhausted the completion budget before replying."
       :seon.error/data evidence
       :seon.ai/exhausted-finish-reason finish-reason})

      (or (and (string? content) (seq content))
          (and (= "stop" finish-reason) (or (nil? content) (string? content))))
      (cond-> {:seon.ai/text (or content "")}
        (and (string? reasoning-content) (seq reasoning-content))
        (assoc :seon.ai/reasoning-content reasoning-content)
        (map? usage) (assoc :seon.ai/usage usage)
        (some? tokens) (assoc :seon.ai/tokens tokens)
        (string? finish-reason)
        (assoc :seon.ai/finish-reason finish-reason))

      :else
      (refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.ai/request
       :seon.error/operation 'seon.ai/parsed-completion
       :seon.error/diagnostic-layer :seon.ai/request
       :seon.error/diagnostic-operation 'seon.ai/parsed-completion
       :seon.error/diagnostic-member :seon.ai/unreadable-response-member
       :seon.error/diagnostic-expected "provider JSON with a textual assistant response"
       :seon.error/diagnostic-offending evidence
       :seon.error/offending evidence
       :seon.error/diagnostic-cause :seon.ai/unparseable-body
       :seon.error/diagnostic-evidence {}
       :seon.error/message
       "The provider's response carried no assistant text."
       :seon.error/data evidence
       :seon.ai/unreadable-response-member "choices.message"}))))

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
      (refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.ai/request
       :seon.error/operation 'seon.ai/completion-text
       :seon.error/diagnostic-layer :seon.ai/request
       :seon.error/diagnostic-operation 'seon.ai/completion-text
       :seon.error/diagnostic-member :seon.ai/unreadable-response-member
       :seon.error/diagnostic-expected "provider JSON with a textual assistant response"
       :seon.error/diagnostic-offending body
       :seon.error/offending body
       :seon.error/diagnostic-cause :seon.ai/unparseable-body
       :seon.error/diagnostic-evidence {}
       :seon.error/message "The provider returned an error document."
       :seon.error/data {::body-shape body-shape}
       :seon.ai/unreadable-response-member "body"})
      (let [choice (when (map? body) (some-> (get body "choices") first))
            message (some-> choice (get "message"))
            content (some-> message (get "content"))
            reasoning-content (some-> message (get "reasoning_content"))
            finish-reason (some-> choice (get "finish_reason"))
            usage (when (map? body) (get body "usage"))]
        (parsed-completion
         content
         reasoning-content
         finish-reason
         usage
         (some-> usage (get "completion_tokens"))
         body-shape)))))

(defn normalize-usage
  "Comparable token counts derived from one open provider usage document."
  {:malli/schema [:=> [:cat :seon.ai/usage] :seon.ai/normalized-usage]}
  [usage]
  (let [cached (or (get usage "cached_tokens")
                   (get usage "prompt_cache_hit_tokens")
                   (get-in usage ["prompt_tokens_details" "cached_tokens"]))]
    (cond-> {}
      (int? (get usage "prompt_tokens"))
      (assoc :seon.ai.usage/prompt-tokens (get usage "prompt_tokens"))

      (int? (get usage "completion_tokens"))
      (assoc :seon.ai.usage/completion-tokens
             (get usage "completion_tokens"))

      (int? (get usage "total_tokens"))
      (assoc :seon.ai.usage/total-tokens (get usage "total_tokens"))

      (int? cached)
      (assoc :seon.ai.usage/cached-tokens cached))))

(defn model-observation-tx
  "Return the noHistory gauge upsert, or no transaction data.
  data when the effective model has no registry row. Durable attempt facts
  remain the usage authority; this contains only current display gauges."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.ai.model/observation-request]
    :seon.db/tx-data]}
  [database observation]
  (if (model-row database (:seon.ai.model/id observation))
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
  Its own function so a test can supply a key without a network and
  without exporting anything."
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

(defn disposition
  "What to do about one model failure: fail over, back off, or stop.
  PURE, and computed from the EVIDENCE the leaf recorded rather than
  from the error's kind — a kind list would be the hand-maintained
  classification the standing rule bans, and it could not answer the
  only question that matters anyway.

  That question is: DID THIS CALL COST ANYTHING? The owner's ruling
  forbids re-calling a model that may have done paid work; it does not
  forbid a second call after a conclusively unpaid refusal. So:

  - any evidence of OUTPUT is terminal — `:fail`. A 2xx body we could
    not parse is generated text somebody paid for;
  - a TRANSMITTED request with no proof of the provider's answer is
    ambiguously paid, and ambiguously paid is terminal. This is why a
    plain `::timeout` does NOT fail over, and it is the strictest
    reading of the ruling rather than the convenient one;
  - a transient request failure that provably never left this machine — a
    refused connection, an unresolved host, a failed handshake, a
    CONNECT timeout — cost nothing, so a backup may be called
    immediately and, with no backup, the same call may be retried;
  - a provider REJECTION carrying no output cost nothing either, and
    splits by what it says: `:rate-limit`/`:server` mean \"not now\"
    (fail over, else back off), `:authentication`/`:authorization`/
    `:model`/`:credential` mean \"not here\" (terminal; repeating or
    switching targets does not repair configuration), and
    `:request` means \"not this\" (terminal: a backup would reject the
    same request).

  Returns `:failover-now`, `:backoff`, or `:fail`. The caller decides
  what to do with each; this decides nothing about execution."
  {:malli/schema [:=> [:cat :seon.ai/disposition-request] :seon.ai/disposition]}
  [{value :seon.error/value backup? :seon.ai/backup?}]
  (let [{::keys [error-class output-observed?]}
        (:seon.error/data value)]
    (cond
      ;; somebody paid for something. Nothing re-calls that.
      output-observed? :fail

      ;; a rejection is free even though the request was transmitted:
      ;; the provider told us it did no work
      (contains? #{:rate-limit :server} error-class)
      (if backup? :failover-now :backoff)

      (contains? #{:credential :authentication :authorization :model}
                 error-class)
      ;; Static configuration refusals are terminal for this turn.
      :fail

      (= :transport-before-send error-class)
      (if backup? :failover-now :backoff)

      ;; :request (the provider rejected this request), :response (we
      ;; could not read a 2xx), :timeout and :transport-unknown (the
      ;; request may have been transmitted) are all terminal
      :else :fail)))

(defn- cause-chain
  "Every throwable in `failure`'s cause chain as `class: message` strings.

  THE JDK PUTS THE REAL CAUSE IN THE CAUSE. A body whose stream ends
  early surfaces as `java.io.IOException: closed` with the actual
  failure attached underneath
  (`ResponseSubscribers.java:355-380`, openjdk 26.0.1 — `throw new
  IOException(\"closed\", failed)`). Recording only `ex-message` is how
  seven consecutive production failures said `closed` and named nothing:
  the diagnosis was thrown away at the catch site. A diagnostic that
  omits what it holds is a defect even while it 'works'.

  Private and unschema'd on purpose: its argument is a host Throwable,
  which is not a declarable value shape, and a `:seon.ai/throwable`
  placeholder would be an invented schema for a host object."
  [^Throwable failure]
  (loop [failure failure chain []]
    (if (nil? failure)
      chain
      (recur (.getCause failure)
             (conj chain (str (.getName (class failure)) ": "
                              (ex-message failure)))))))

(defn- caused-by?
  [failure throwable-class]
  (boolean
   (some #(.isInstance ^Class throwable-class %)
         (take-while some? (iterate ex-cause failure)))))

(defn- interruptible-lines
  "Lines from `reader`, recording a read failure in `failure` rather
  than throwing it.

  This is the whole reason a truncated stream can keep what arrived: the
  fold above consumes an ordinary sequence, and a sequence that ENDS on
  a transport failure leaves the accumulated snapshot in the caller's
  hands instead of unwinding past it. The atom is invocation-local
  coordination between this seq and its one consumer, nothing durable."
  [^BufferedReader reader failure]
  (lazy-seq
   (let [line (try (.readLine reader)
                   (catch Throwable read-failure
                     (reset! failure read-failure)
                     nil))]
     (when (some? line)
       (cons line (interruptible-lines reader failure))))))

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
                                      java.net.http.HttpTimeoutException)
        chain (cause-chain failure)]
  (refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.ai/request
       :seon.error/operation 'seon.ai/truncation
       :seon.error/diagnostic-layer :seon.ai/request
       :seon.error/diagnostic-operation 'seon.ai/truncation
       :seon.error/diagnostic-member :seon.ai/text
       :seon.error/diagnostic-expected "a terminal stream event"
       :seon.error/diagnostic-offending snapshot
       :seon.error/offending snapshot
       :seon.error/diagnostic-cause :seon.ai/stream-truncated
       :seon.error/diagnostic-evidence {}
   :seon.ai/interrupted-text-count received
   :seon.error/message
     "The provider stream ended before its terminal event; inspect the retained partial output and transport evidence."
     :seon.error/data
     (cond-> {::cause-chain chain
              ::text-received received
              ::time-limit-fired? time-limit-fired?
              ::thread-interrupted? (.isInterrupted (Thread/currentThread))}
       (pos? reasoning-received)
       (assoc ::reasoning-received reasoning-received))})))

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
      (cond-> {:seon.ai/text (:seon.ai/text snapshot)
               :seon.ai/truncation ended}
        (:seon.ai/reasoning-partial snapshot)
        (assoc :seon.ai/reasoning-content (:seon.ai/reasoning-partial snapshot))
        (:seon.ai/usage snapshot) (assoc :seon.ai/usage (:seon.ai/usage snapshot))
        (:seon.ai/tokens snapshot) (assoc :seon.ai/tokens (:seon.ai/tokens snapshot))
        (:seon.ai/finish-reason snapshot)
        (assoc :seon.ai/finish-reason (:seon.ai/finish-reason snapshot)))
      ended)))

(defn- streamed-completion
  "Read an SSE body and return one completion value.

  A streamed call and a one-shot call return the SAME shape — that is
  the point, and it is why this lives beside `completion-text` rather
  than replacing it: everything downstream, including the failover
  disposition and the attempt facts, is untouched by which transport
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
    (let [failure (atom nil)
          snapshot (stream-fold (interruptible-lines reader failure) sink)]
      (cond
        (contains? snapshot :seon.ai/unreadable-response-member) snapshot
        (some? @failure) (truncated-completion snapshot @failure)
        :else
        (parsed-completion
         (:seon.ai/text snapshot)
         (:seon.ai/reasoning-partial snapshot)
         (:seon.ai/finish-reason snapshot)
         (:seon.ai/usage snapshot)
         (:seon.ai/tokens snapshot)
         ::empty-stream)))))

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
  "Send one ordinary request map through the JDK HTTP leaf."
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
                (update completion :seon.error/data merge
                        {::status status
                         ::error-class :response
                         ::http-status status
                         ::request-transmitted? true
                         ::response-started? true
                         ;; DERIVED, NEVER ASSERTED. This flag used to be
                         ;; a hardcoded `true` on every 2xx failure, so
                         ;; the attempt row said "output WAS seen" about
                         ;; streams that delivered nothing — a durable
                         ;; fact that lied, and one that `disposition`
                         ;; reads. A truncation counted its characters;
                         ;; every other 2xx failure holds a body the
                         ;; provider generated and charged for.
                         ::output-observed?
                         (output-observed? (:seon.error/data completion))})
                completion))
            (catch Throwable failure
              (refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.ai/request
       :seon.error/operation 'seon.ai/send-request
       :seon.error/diagnostic-layer :seon.ai/request
       :seon.error/diagnostic-operation 'seon.ai/send-request
       :seon.error/diagnostic-member :seon.ai/unreadable-response-member
       :seon.error/diagnostic-expected "provider JSON with a textual assistant response"
       :seon.error/diagnostic-offending body
       :seon.error/offending body
       :seon.error/diagnostic-cause :seon.ai/unparseable-body
       :seon.error/diagnostic-evidence {}
               :seon.error/message "The provider response was not readable JSON; inspect the retained response evidence."
               :seon.error/data {::status status
                                 ::error-class :response
                                 ::http-status status
                                 ::cause-chain (cause-chain failure)
                                 ::request-transmitted? true
                                 ::response-started? true
                                 ;; a 2xx body EXISTS, so the provider
                                 ;; generated and charged for output
                                 ;; even though we cannot read it
                                 ::output-observed? true}
               :seon.ai/unreadable-response-member "body"})))
          (let [body (str (read-body))]
            (refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.ai/request
       :seon.error/operation 'seon.ai/send-request
       :seon.error/diagnostic-layer :seon.ai/request
       :seon.error/diagnostic-operation 'seon.ai/send-request
       :seon.error/diagnostic-member :seon.ai/http-status
       :seon.error/diagnostic-expected "an HTTP success response"
       :seon.error/diagnostic-offending body
       :seon.error/offending body
       :seon.error/diagnostic-cause :seon.ai/provider-error
       :seon.error/diagnostic-evidence {}
             :seon.error/message (str "The provider answered " status ".")
             :seon.error/data {::status status
                               ::body body
                               ::error-class (status-class status)
                               ::http-status status
                               ::request-transmitted? true
                               ::response-started? true
                               ;; a rejection carries no generated
                               ;; output; a 2xx would not be here
                               ::output-observed? false}
             :seon.ai/provider-error status}))))
      (catch java.net.http.HttpTimeoutException failure
        ;; an ordinary outcome: the model was slow. Never a bug report.
        ;; A CONNECT timeout never transmitted anything; any other
        ;; timeout may have. The JDK distinguishes them by class
        ;; (`HttpConnectTimeoutException extends HttpTimeoutException`),
        ;; which is the only honest way to know, and "cannot prove it
        ;; was free" is not "it was free".
        (let [connect? (instance? java.net.http.HttpConnectTimeoutException
                                  failure)]
          (refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.ai/request
       :seon.error/operation 'seon.ai/send-request
       :seon.error/diagnostic-layer :seon.ai/request
       :seon.error/diagnostic-operation 'seon.ai/send-request
       :seon.error/diagnostic-member :seon.ai/timeout-ms
       :seon.error/diagnostic-expected "a response within the request deadline"
       :seon.error/diagnostic-offending timeout-ms
       :seon.error/offending timeout-ms
       :seon.error/diagnostic-cause :seon.ai/timeout
       :seon.error/diagnostic-evidence {}
           :seon.error/message (str "The model did not answer within "
                                    timeout-ms "ms.")
           :seon.error/data {:seon.ai/timeout-ms timeout-ms
                             ::error-class (if connect?
                                             :transport-before-send
                                             :timeout)
                             ::request-transmitted? (not connect?)
                             ::response-started? false
                             ::output-observed? false}
           :seon.ai/timeout timeout-ms})))
      (catch Throwable failure
        (let [before-send? (transport-before-send? failure)]
          (refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.ai/request
       :seon.error/operation 'seon.ai/send-request
       :seon.error/diagnostic-layer :seon.ai/request
       :seon.error/diagnostic-operation 'seon.ai/send-request
       :seon.error/diagnostic-member :seon.ai/endpoint
       :seon.error/diagnostic-expected "a completed HTTP exchange"
       :seon.error/diagnostic-offending failure
       :seon.error/offending failure
       :seon.error/diagnostic-cause :seon.ai/transport-failure
       :seon.error/diagnostic-evidence {}
           :seon.error/message (or (ex-message failure)
                                   (.getName (class failure)))
           :seon.error/data {:seon.ai/endpoint endpoint
                             ::throwable (.getName (class failure))
                             ::error-class (if before-send?
                                             :transport-before-send
                                             :transport-unknown)
                             ::request-transmitted? (not before-send?)
                             ::response-started? false
                             ::output-observed? false}
           :seon.ai/transport-failure endpoint}))))))

(defn complete
  "Call the model once and return its text, or a flat error value.
  ONE attempt. No retry, no backoff, no fallback provider. A request
  either names its credential variable or explicitly declares no-auth.

  Flat `:seon.error` values, never throws:
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
    `:seon.ai/truncation` fact, so the turn keeps what was paid for."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.ai/request] :seon.ai/completion]}
  [projection {:keys [:seon.ai/api-key-variable]
    no-auth :seon.config.ai/no-auth
    :as request}]
  (let [body (request-body projection request)
        api-key (when-not no-auth (credential api-key-variable))]
    (cond
      (or (contains? body :seon.ai/extra-body-edn)
          (contains? body :seon.ai/protected-keys))
      body

      (or no-auth api-key)
      (let [started (System/nanoTime)
            request-data (http-request-data request api-key body)
            result (send-request request-data)]
        (assoc result
               :seon.ai.model/last-latency-ms
               (long (/ (- (System/nanoTime) started) 1000000))))

      :else
      (refusal/diagnostic
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.ai/request
       :seon.error/operation 'seon.ai/complete
       :seon.error/diagnostic-layer :seon.ai/request
       :seon.error/diagnostic-operation 'seon.ai/complete
       :seon.error/diagnostic-member :seon.ai/api-key-variable
       :seon.error/diagnostic-expected "a nonempty credential in the named environment variable"
       :seon.error/diagnostic-offending api-key-variable
       :seon.error/offending api-key-variable
       :seon.error/diagnostic-cause :seon.ai/no-credential
       :seon.error/diagnostic-evidence {}
       :seon.error/message (if (string? api-key-variable)
                             (str "The environment variable "
                                  api-key-variable " is not set.")
                             "No credential variable was configured.")
       :seon.error/data {:seon.ai/api-key-variable api-key-variable
                         ::error-class :credential
                         ;; NO NETWORK CALL HAPPENED. This is the one
                         ;; failure that is provably free.
                         ::request-transmitted? false
                         ::response-started? false
                         ::output-observed? false}
       :seon.ai/missing-credential-variable api-key-variable}))))
