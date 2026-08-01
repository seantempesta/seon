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
  the CALLER's reduce over these values (`seon.cluster.loop`'s `:call`
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
            [datahike.api :as d]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form])
  (:import [java.io BufferedReader InputStreamReader]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/ai.edn
;;; ---------------------------------------------------------------------------

(defn sink?
  "True for a partial sink: any function of one argument."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [x]
  (ifn? x))

(def sink-generator
  "An honest generator: a real function that ignores its snapshot,
  which is exactly what a sink is allowed to do."
  (gen/fmap (fn [_] (fn [_partial] nil)) (gen/return nil)))

;; the gate refuses a `[:fn]` naming anything not registered
(schema/register-core-predicate! 'seon.ai/sink? sink?)

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Contracts
;;; ---------------------------------------------------------------------------

(defn- map-attributes
  [schema-key]
  (into #{}
        (comp (filter vector?) (map first))
        (schema/schema-definition schema-key)))

(defn settings
  "Resolved AI settings for one agent. Pure."
  {:malli/schema
   [:=> [:cat :seon.config/effective :seon.config/agent-overlay]
    :seon.config/effective]}
  [cluster-settings agent-settings]
  (merge cluster-settings agent-settings))

(defn agent-overlay
  "Declared setting overrides for one agent in a database value."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.cluster.agent/id]
    :seon.config/agent-overlay]}
  [db agent-id]
  (let [attributes (map-attributes :seon.config/agent-overlay)]
    (select-keys
     (or (d/pull db (vec attributes) [:seon.cluster.agent/id agent-id]) {})
     attributes)))

(defn- config-ai-ident->request-ident
  [config-ident]
  (keyword "seon.ai" (name config-ident)))

(defn- primary-setting-entries
  [dials]
  (into {}
        (keep
         (fn [[config-ident value]]
           (when (= "seon.config.ai" (namespace config-ident))
             [(if (= :seon.config.ai/no-auth config-ident)
                config-ident
                (config-ai-ident->request-ident config-ident))
              value])))
        dials))

(defn targets
  "The primary descriptor row and the OPTIONAL backup, from the dials.
  PURE, and the ONE assembly for both roles — a second hand-written
  provider map somewhere else is how two roles start to disagree about
  what a target is.

  THE BACKUP IS OVERRIDES OVER THE PRIMARY, and that shape is chosen to
  make a PARTIAL backup unrepresentable rather than merely refused.
  `:seon.config.ai.backup/model` decides whether a backup exists at
  all; endpoint, credential variable and deadline are optional and
  inherit the primary's. So there is no configuration in which three
  backup dials are set and the fourth silently voids them, and the
  common case — a different model at the same provider — is one dial
  rather than four copied lines.

  Absence, never nil: with no backup the key is simply not there, which
  is exactly what `:seon.ai/backup?` reads downstream."
  {:malli/schema [:=> [:cat :seon.config/effective] :seon.ai/targets]}
  [dials]
  (let [primary-settings (primary-setting-entries dials)
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
  [config-ident]
  (schema.form/attr-form-properties (schema/schema-definition config-ident)))

(defn- wire-setting-triples
  []
  (->> (map-attributes :seon.config/effective)
       (mapcat
        (fn [config-ident]
          (map (fn [[wire-key coercion]]
                 [config-ident wire-key coercion])
               (:seon.ai/wire
                (config-registration-properties config-ident)))))
       (sort-by (juxt (comp str first) second))
       vec))

(defn- coercion-function
  [coercion]
  #?(:clj (requiring-resolve coercion)
     :cljs (case coercion
             clojure.core/identity identity
             seon.ai/thinking-wire-value thinking-wire-value
             seon.ai/reasoning-effort-wire-value reasoning-effort-wire-value
             seon.ai/response-format-wire-value response-format-wire-value)))

;; DeepSeek documents these controls as silently ignored whenever thinking is
;; enabled. This is the one accepted provider constant; a second provider with
;; a different set is the trigger to move the fact into descriptor data.
;; `research/deepseek-thinking-mode-api-2026-08-01.md`, "Parameters silently
;; ignored in thinking mode"; owner ruling #34, 2026-08-01.
(def ^:private thinking-inert-settings
  #{:seon.config.ai/temperature
    :seon.config.ai/top-p
    :seon.config.ai/frequency-penalty
    :seon.config.ai/presence-penalty})

(defn wire-settings
  "Wire fields honoured for a request and configured fields that are inert."
  {:malli/schema [:=> [:cat :seon.ai/request] :seon.ai/wire-settings]}
  [request]
  (let [thinking? (not= :disabled (:seon.ai/thinking request))
        inert (if thinking?
                (into #{}
                      (filter #(contains? request
                                          (config-ai-ident->request-ident %)))
                      thinking-inert-settings)
                #{})]
    (reduce
     (fn [result [config-ident wire-key coercion]]
       (let [request-ident (config-ai-ident->request-ident config-ident)]
         (if (or (contains? inert config-ident)
                 (not (contains? request request-ident)))
           result
           (let [wire-value ((coercion-function coercion)
                             (get request request-ident))]
             (if (= omit-wire-value wire-value)
               result
               (assoc-in result [:seon.ai/sent wire-key] wire-value))))))
     {:seon.ai/sent {}
      :seon.ai/inert inert}
     (wire-setting-triples))))

(defn- extra-body-request-ident
  []
  (some
   (fn [config-ident]
     (when (true? (:seon.ai/extra-body
                   (config-registration-properties config-ident)))
       (config-ai-ident->request-ident config-ident)))
   (map-attributes :seon.config/effective)))

(defn- extra-body
  [request]
  (if-let [encoded (get request (extra-body-request-ident))]
    (try
      (let [decoded (edn/read-string encoded)]
        (if (and (map? decoded) (every? string? (keys decoded)))
          decoded
          {:seon.error/kind ::invalid-extra-body
           :seon.error/message
           "The configured extra body must be an EDN map with string keys."
           :seon.error/data {::request-transmitted? false
                             ::response-started? false
                             ::output-observed? false}}))
      (catch #?(:clj Throwable :cljs :default) failure
        {:seon.error/kind ::invalid-extra-body
         :seon.error/message
         (str "The configured extra body is not readable EDN: "
              (ex-message failure))
         :seon.error/data {::request-transmitted? false
                           ::response-started? false
                           ::output-observed? false}}))
    {}))

(defn- request-headers
  [key]
  (cond-> {"content-type" "application/json"}
    key (assoc "authorization" (str "Bearer " key))))

(defn request-body
  "The provider request document, or a pre-call flat error. Pure."
  {:malli/schema [:=> [:cat :seon.ai/request] :seon.ai/request-body]}
  [{:keys [:seon.ai/model :seon.ai/system :seon.ai/prompt]
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
        sent (:seon.ai/sent (wire-settings request))
        builder-body (merge base sent)
        extra (extra-body request)]
    (if (:seon.error/kind extra)
      extra
      (let [builder-owned-keys
            (into (set (keys builder-body))
                  (keys (request-headers ::protected-placeholder)))
            conflicts (set/intersection builder-owned-keys
                                        (set (keys extra)))]
        (if (seq conflicts)
          {:seon.error/kind ::extra-body-conflict
           :seon.error/message
           "The configured extra body cannot override request-builder fields."
           :seon.error/data {::protected-keys (vec (sort conflicts))
                             ::request-transmitted? false
                             ::response-started? false
                             ::output-observed? false}}
          ;; LiteLLM's useful escape hatch, but with Seon's error-as-value
          ;; boundary: provider-owned fields merge last only after conflicts
          ;; with the builder's ACTUAL emitted keys have been refused.
          (merge builder-body extra))))))

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

  A line this cannot read is SKIPPED rather than fatal. A provider that
  sends a keep-alive comment, a blank line, or one malformed chunk has
  not failed the call, and turning presentation noise into a call
  failure would be the streaming path breaking the invariant it exists
  under."
  {:malli/schema [:=> [:cat :seon.ai/partial :string] :seon.ai/partial]}
  [snapshot line]
  (let [payload (when (str/starts-with? line "data:")
                  (str/trim (subs line 5)))]
    (if (or (nil? payload) (= "[DONE]" payload) (str/blank? payload))
      snapshot
      (let [chunk (try (json/read-str payload) (catch Throwable _ nil))
            choice (some-> chunk (get "choices") first)
            delta (some-> choice (get "delta") (get "content"))
            finish-reason (some-> choice (get "finish_reason"))
            usage (get chunk "usage")
            text (cond-> (:seon.ai/text snapshot)
                   (string? delta) (str delta))]
        (cond-> (assoc snapshot :seon.ai/text text)
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
                          (:seon.ai/tokens (update snapshot :seon.ai/tokens
                                                   (fnil inc 0)))
                          0)))))))

(defn stream-fold
  "Fold SSE `lines` into one snapshot, publishing to `sink` as it goes.

  PUBLISHING IS COALESCED BY THE SINK'S OWN CADENCE, not by this: it is
  called once per content chunk, and a sink that wants fewer is the one
  that knows how few. What this guarantees is the other half — a sink
  that THROWS cannot break the call. Presentation may lag, drop, or be
  broken; it may never affect transport, parsing, usage, or evaluation,
  which is the invariant streaming lives under.

  Returns the final snapshot. The caller turns it into a completion, so
  a streamed call and a one-shot call are the same value downstream."
  {:malli/schema [:=> [:cat [:sequential :string] [:maybe :seon.ai/sink]]
                  :seon.ai/partial]}
  [lines sink]
  (reduce (fn [snapshot line]
            (let [next-snapshot (stream-event snapshot line)]
              (when (and sink (not= (:seon.ai/text snapshot)
                                    (:seon.ai/text next-snapshot)))
                (try (sink next-snapshot) (catch Throwable _ nil)))
              next-snapshot))
          {:seon.ai/text "" :seon.ai/tokens 0}
          lines))

(defn- parsed-completion
  [content finish-reason usage tokens body-shape]
  (let [evidence (cond-> {::body-shape body-shape}
                   (string? finish-reason)
                   (assoc ::finish-reason finish-reason)
                   (map? usage) (assoc ::usage usage))]
    (cond
      (and (= "length" finish-reason) (str/blank? content))
      {:seon.error/kind ::token-starvation
       :seon.error/message
       "The provider exhausted the completion budget before replying."
       :seon.error/data evidence}

      (and (string? content) (seq content))
      (cond-> {:seon.ai/text content}
        (map? usage) (assoc :seon.ai/usage usage)
        (some? tokens) (assoc :seon.ai/tokens tokens)
        (string? finish-reason)
        (assoc :seon.ai/finish-reason finish-reason))

      :else
      {:seon.error/kind ::unparseable-body
       :seon.error/message
       "The provider's response carried no assistant text."
       :seon.error/data evidence})))

(defn completion-text
  "The assistant text in a decoded provider response, or a flat error.
  Pure, and the one `:any` in this namespace: the response is a foreign
  document. A body that does not carry the expected shape returns
  `::unparseable-body` with what was actually there — never nil, which
  would read downstream as an empty reply."
  {:malli/schema [:=> [:cat :any] :seon.ai/completion]}
  [body]
  (let [choice (when (map? body) (some-> (get body "choices") first))
        content (some-> choice (get "message") (get "content"))
        finish-reason (some-> choice (get "finish_reason"))
        usage (when (map? body) (get body "usage"))]
    (parsed-completion
     content
     finish-reason
     usage
     (some-> usage (get "completion_tokens"))
     (cond
       (map? body) (vec (sort (keys body)))
       (nil? body) ::nil
       :else (str (class body))))))

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
    #?(:clj (System/getenv variable)
       :cljs (some-> js/process .-env (aget variable)))))

(defn- status-class
  "One HTTP status as a normalized class.
  Ranges and the four statuses that mean something specific — the
  provider's own vocabulary, read rather than re-invented. 408/429 and
  5xx are the provider saying \"not now\", which is a different fact
  from 400 saying \"not this request\"."
  [status]
  (cond
    (= 401 status) :authentication
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
  - a request that provably never left this machine — no credential, a
    refused connection, an unresolved host, a failed handshake, a
    CONNECT timeout — cost nothing, so a backup may be called
    immediately and, with no backup, the same call may be retried;
  - a provider REJECTION carrying no output cost nothing either, and
    splits by what it says: `:rate-limit`/`:server` mean \"not now\"
    (fail over, else back off), `:authentication`/`:authorization`/
    `:model`/`:credential` mean \"not here\" (fail over to a different
    target, but never back off — repeating it changes nothing), and
    `:request` means \"not this\" (terminal: a backup would reject the
    same request).

  Returns `:failover-now`, `:backoff`, or `:fail`. The caller decides
  what to do with each; this decides nothing about execution."
  {:malli/schema [:=> [:cat :seon.ai/disposition-request] :seon.ai/disposition]}
  [{value :seon.error/value backup? :seon.ai/backup?}]
  (let [{::keys [error-class output-observed? request-transmitted?]}
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
      ;; a different target may work; the same one never will, so this
      ;; is the one free class that must NOT back off
      (if backup? :failover-now :fail)

      (= :transport-before-send error-class)
      (if backup? :failover-now :backoff)

      ;; :request (the provider rejected this request), :response (we
      ;; could not read a 2xx), :timeout and :transport-unknown (the
      ;; request may have been transmitted) are all terminal
      :else :fail)))

(defn- streamed-completion
  "Read an SSE body to natural EOF and return one completion value.

  A streamed call and a one-shot call return the SAME shape — that is
  the point, and it is why this lives beside `completion-text` rather
  than replacing it: everything downstream, including the failover
  disposition and the attempt facts, is untouched by which transport
  ran.

  Reads to natural EOF because reply evaluation is `:batch`
  (`docs/seon/reference/llm-adapters.md:545-556`): the complete program
  is parsed once, so aborting on the first form would be a different
  evaluation mode, not an optimization.

  Empty text is an error, not an empty reply, exactly as
  `completion-text` treats it — a provider that streamed nothing has
  failed the call however cleanly it closed the socket."
  [body sink]
  (with-open [reader (BufferedReader. (InputStreamReader. body "UTF-8"))]
    (let [snapshot (stream-fold (line-seq reader) sink)]
      (parsed-completion
       (:seon.ai/text snapshot)
       (:seon.ai/finish-reason snapshot)
       (:seon.ai/usage snapshot)
       (:seon.ai/tokens snapshot)
       ::empty-stream))))

(defn- http-request-data
  "Ordinary request data for the JDK leaf."
  [request key body]
  (cond-> {:seon.ai/endpoint (:seon.ai/endpoint request)
           :seon.ai/timeout-ms (:seon.ai/timeout-ms request)
           :seon.ai/stream? (boolean (:seon.ai/stream? request))
           :seon.ai.http/headers (request-headers key)
           :seon.ai.http/body (json/write-str body)}
    (:seon.ai/sink request)
    (assoc :seon.ai/sink (:seon.ai/sink request))))

(defn- send-request
  "Send one ordinary request map through the JDK HTTP leaf."
  [{:keys [:seon.ai/endpoint :seon.ai/timeout-ms
           :seon.ai.http/headers :seon.ai.http/body]
    stream? :seon.ai/stream?
    sink :seon.ai/sink}]
  (let [client (.build (HttpClient/newBuilder))
        ;; THE one deadline, and it is the HTTP client's own: a request
        ;; timeout the JVM enforces, not a wrapper thread racing the call.
        builder (-> (HttpRequest/newBuilder (URI/create endpoint))
                    (.timeout (Duration/ofMillis (long timeout-ms))))
        http-request
        (-> (reduce-kv (fn [request-builder name value]
                         (.header request-builder name value))
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
            ;; a non-2xx body has to be readable either way, and a
            ;; stream's body is only readable once
            read-body (fn [] (if stream?
                               (slurp (.body response))
                               (.body response)))]
        (if (<= 200 status 299)
          (try
            (let [completion
                  (if stream?
                    (streamed-completion (.body response) sink)
                    (completion-text (json/read-str (.body response))))]
              (if (:seon.error/kind completion)
                (update completion :seon.error/data merge
                        {::status status
                         ::error-class :response
                         ::http-status status
                         ::request-transmitted? true
                         ::response-started? true
                         ::output-observed? true})
                completion))
            (catch Throwable failure
              {:seon.error/kind ::unparseable-body
               :seon.error/message (str "The provider's response was not "
                                        "readable JSON: "
                                        (ex-message failure))
               :seon.error/data {::status status
                                 ::error-class :response
                                 ::http-status status
                                 ::request-transmitted? true
                                 ::response-started? true
                                 ;; a 2xx body EXISTS, so the provider
                                 ;; generated and charged for output
                                 ;; even though we cannot read it
                                 ::output-observed? true}}))
          (let [text (str (read-body))
                body (subs text 0 (min 500 (count text)))]
            {:seon.error/kind ::provider-error
             :seon.error/message (str "The provider answered " status ".")
             :seon.error/data {::status status
                               ::body body
                               ::error-class (status-class status)
                               ::http-status status
                               ::request-transmitted? true
                               ::response-started? true
                               ;; a rejection carries no generated
                               ;; output; a 2xx would not be here
                               ::output-observed? false}})))
      (catch java.net.http.HttpTimeoutException failure
        ;; an ordinary outcome: the model was slow. Never a bug report.
        ;; A CONNECT timeout never transmitted anything; any other
        ;; timeout may have. The JDK distinguishes them by class
        ;; (`HttpConnectTimeoutException extends HttpTimeoutException`),
        ;; which is the only honest way to know, and "cannot prove it
        ;; was free" is not "it was free".
        (let [connect? (instance? java.net.http.HttpConnectTimeoutException
                                  failure)]
          {:seon.error/kind ::timeout
           :seon.error/message (str "The model did not answer within "
                                    timeout-ms "ms.")
           :seon.error/data {:seon.ai/timeout-ms timeout-ms
                             ::error-class (if connect?
                                             :transport-before-send
                                             :timeout)
                             ::request-transmitted? (not connect?)
                             ::response-started? false
                             ::output-observed? false}}))
      (catch Throwable failure
        (let [before-send? (transport-before-send? failure)]
          {:seon.error/kind ::transport-failure
           :seon.error/message (or (ex-message failure)
                                   (.getName (class failure)))
           :seon.error/data {:seon.ai/endpoint endpoint
                             ::throwable (.getName (class failure))
                             ::error-class (if before-send?
                                             :transport-before-send
                                             :transport-unknown)
                             ::request-transmitted? (not before-send?)
                             ::response-started? false
                             ::output-observed? false}})))))

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
  - `::unparseable-body` — 2xx with a body this cannot read."
  {:malli/schema [:=> [:cat :seon.ai/request] :seon.ai/completion]}
  [{:keys [:seon.ai/api-key-variable]
    no-auth :seon.config.ai/no-auth
    :as request}]
  (let [body (request-body request)
        key (when-not no-auth (credential api-key-variable))]
    (cond
      (:seon.error/kind body)
      body

      (or no-auth key)
      (send-request (http-request-data request key body))

      :else
      {:seon.error/kind ::no-credential
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
                         ::output-observed? false}})))
