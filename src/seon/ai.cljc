(ns seon.ai
  "One provider, one attempt, one deadline. Failure is a value.

  DRAFT CONTRACT LAYER FOR ORCHESTRATOR SEAL (drafted 2026-07-27 — N3,
  package 2, from n3-plan §7.3). Nothing here is implemented: every
  body throws `awaits implementation`.

  NOTHING RETRIES A PAID CALL (owner ruling, 2026-07-27 late). Not
  here, not in the loop, not on crash recovery. A lost call is lost;
  the agent is told and adapts. That single sentence deletes the
  quarry's whole retry authority (`seon.agent.turn/call-llm!`), its
  attempt entity, and the backoff policy that came with them — and it
  is why this namespace has no attempt count, no jitter, and no state.

  THE ONE LEGITIMATE DEADLINE. A remote HTTP call is genuinely
  unobservable external state — the process cannot see the other end
  die — so `:seon.ai/timeout-ms` is a real backstop rather than a tuned
  constant standing in for an event. Its firing is an ORDINARY ERROR
  VALUE, not a bug report: the model was slow, the run closes with
  something the agent can read. (Contrast the submission backstop in
  the eval path, whose firing IS a bug report.)

  THE SEAM TO B2 IS EXACTLY THREE FACTS: endpoint, model, and the NAME
  of the environment variable holding the credential. The credential is
  read at the leaf, never becomes a datom, and never enters Git. When
  B2's provider descriptor rows absorb those three, this call site does
  not change.

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
            [seon.schema.edn :as schema.edn])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/ai.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Contracts
;;; ---------------------------------------------------------------------------

(defn request-body
  "The provider request document for one completion. Pure.
  Quarried from `src-old/seon/ai/openai_compat/core.cljc:11` — the
  shape is right and survives; what dies with the quarry is everything
  around it (streaming, thinking modes, provider dispatch)."
  {:malli/schema [:=> [:cat :seon.ai/request] [:map]]}
  [{:keys [:seon.ai/model :seon.ai/system :seon.ai/prompt]}]
  ;; STRING keys: this is the wire document, not Clojure data. It is
  ;; built as strings and read back as strings, so nothing in between
  ;; has to remember which side of the boundary it is on.
  {"model" model
   "stream" false
   "messages" (cond-> []
                system (conj {"role" "system" "content" system})
                true (conj {"role" "user" "content" prompt}))})

(defn completion-text
  "The assistant text in a decoded provider response, or a flat error.
  Pure, and the one `:any` in this namespace: the response is a foreign
  document. A body that does not carry the expected shape returns
  `::unparseable-body` with what was actually there — never nil, which
  would read downstream as an empty reply."
  {:malli/schema [:=> [:cat :any]
                  [:or [:map {:closed true} [:seon.ai/text :seon.ai/text]]
                   :seon.error/value]]}
  [body]
  (let [content (when (map? body)
                  (some-> (get body "choices") first
                          (get "message") (get "content")))]
    (if (and (string? content) (seq content))
      {:seon.ai/text content}
      {:seon.error/kind ::unparseable-body
       :seon.error/message
       "The provider's response carried no assistant text."
       :seon.error/data {::body-shape (cond
                                        (map? body) (vec (sort (keys body)))
                                        (nil? body) ::nil
                                        :else (str (class body)))}})))

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

(defn complete
  "Call the model once and return its text, or a flat error value.
  ONE attempt. No retry, no backoff, no fallback provider. Reads the
  credential from the environment variable the request NAMES.

  Flat `:seon.error` values, never throws:
  - `::no-credential` — the named environment variable is unset. This
    is a configuration fault, and it is loud in the value rather than
    silent in a nil;
  - `::timeout` — the deadline fired. An ordinary outcome;
  - `::transport-failure` — the request never completed;
  - `::provider-error` — a non-2xx response, carrying its status;
  - `::unparseable-body` — 2xx with a body this cannot read."
  {:malli/schema [:=> [:cat :seon.ai/request] :seon.ai/completion]}
  [{:keys [:seon.ai/endpoint :seon.ai/api-key-variable :seon.ai/timeout-ms]
    :as request}]
  (if-let [key (credential api-key-variable)]
    (let [client (.build (HttpClient/newBuilder))
          ;; THE one deadline, and it is the HTTP client's own: a
          ;; request timeout the JVM enforces, not a wrapper thread
          ;; racing the call.
          http-request
          (-> (HttpRequest/newBuilder (URI/create endpoint))
              (.timeout (Duration/ofMillis (long timeout-ms)))
              (.header "content-type" "application/json")
              (.header "authorization" (str "Bearer " key))
              (.POST (HttpRequest$BodyPublishers/ofString
                      (json/write-str (request-body request))))
              (.build))]
      (try
        ;; ONE attempt. Synchronous send on the calling thread — the
        ;; loop proc is :io and may block; there is no retry, no
        ;; backoff, and no second provider to fall back to.
        (let [response (.send client http-request
                              (HttpResponse$BodyHandlers/ofString))
              status (.statusCode response)]
          (if (<= 200 status 299)
            (try
              (completion-text (json/read-str (.body response)))
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
            (let [body (subs (str (.body response)) 0
                              (min 500 (count (str (.body response)))))]
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
                               ::output-observed? false}}))))
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
                       ::output-observed? false}}))
