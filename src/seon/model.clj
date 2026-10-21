(ns seon.model
  "The model, as one function of a provider value and a request: `complete` answers
  `:seon.model/completion` (the reply text and its token usage) or the declared
  `:seon.model/provider-error`.

  Providers (`:seon.model/provider`, dispatched on :kind):
  - :scripted, a deterministic stand-in: the function named by :script answers the reply
    text for a request (it sees the task id and turn, as a test double may);
  - :openai-compat, an OpenAI-compatible chat API (DeepSeek's: base URL plus
    /chat/completions) over jolt-lang/http-client's transport (BSD sockets and OpenSSL
    through jolt.ffi). The API key is read from the environment variable NAMED by
    :api-key-env; it is never logged, printed or stored.

  Root loads the HTTP transport and JSON before it forks anything, so every agent holds them
  compiled."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str] [jolt.http.core :as http] [clojure.data.json :as json]))

(defn estimate-tokens
  "Tokens of text, estimated at 4 characters a token (the stand-in's usage)."
  {:malli/schema [:=> [:cat :string] :int]}
  [s] (long (Math/ceil (/ (count s) 4.0))))

(defn- provider-error
  "The declared failure of a model call, as the one error map: the provider's HTTP 429 is
  :seon.model/rate-limited (the :model owner keeps the request and pauses, for its
  Retry-After when it gives one), any other failure :seon.model/provider-error. data: the
  status, the whole response body, the Retry-After in ms."
  {:malli/schema [:=> [:cat :string [:maybe :int] :map] :seon.model/failure]}
  [message status data]
  (contract/error (if (= 429 status) :seon.model/rate-limited :seon.model/provider-error) 'seon.model/complete message
                  (cond-> data status (assoc :status status))))

(defn- transport-error
  "The connection failed or died (connect refused or timed out, a reset, EOF mid-reply):
  :seon.model/transport, transient, the request is kept and retried after a pause."
  {:malli/schema [:=> [:cat :string :map] :seon.model/failure]}
  [message data]
  (contract/error :seon.model/transport 'seon.model/complete (str "transport: " message) data))

(defn- scripted
  {:malli/schema [:=> [:cat :seon.model/provider :seon.model/request] :seon.model/completion]}
  [{:keys [script]} request]
  (let [t0 (System/nanoTime)
        text ((resolve script) request)]
    {:text text :prompt-tokens (reduce + (map (comp estimate-tokens :content) (:messages request)))
     :completion-tokens (estimate-tokens text) :ms (/ (- (System/nanoTime) t0) 1e6)}))

(defn- body-text
  "A read response's whole body as text: its buffered bytes, or every piece its pull yields
  until the server ends it."
  {:malli/schema [:=> [:cat :map] :string]}
  [r]
  (String. ^bytes (if-let [pull (:body-pull r)]
                    (http/concat-bas (loop [acc []] (if-let [b (pull)] (recur (conj acc b)) acc)))
                    (or (:body r) (byte-array 0)))
           "UTF-8"))

(defn- http-post
  "POST body (a JSON string) to url with headers and read the reply until the server ends
  it: {:status :body :headers} (body text). No clock bounds the connect or the reply (a
  reasoning model streams for minutes): the OS's own connect failure or the socket's death
  ends it. The interop edge, the named dragon seon.model/http-post: a connect or socket
  failure is :seon.model/transport."
  {:malli/schema [:=> [:cat :string [:vector [:tuple :string :string]] :string]
                  [:or [:map [:status :int] [:body :string] [:headers :any]] :seon.model/failure]]}
  [url headers body]
  (let [u (try (http/parse-url url) (catch Throwable e e))]
    (if (instance? Throwable u)
      (provider-error (str "the provider's URL does not parse: " url) nil {})
      (try
        (let [s (http/connect-stream (http/tget u :host) (http/effective-port u) (= "https" (http/tget u :protocol)) false nil nil)]
          (try
            (http/s-write s (http/build-request "POST" u headers body))
            (let [r (http/read-response s nil "POST" nil true true)]
              {:status (:status r) :headers (:header-pairs r) :body (body-text r)})
            (finally (http/s-close s))))
        (catch Throwable e (transport-error (or (ex-message e) (str (type e))) {:url url}))))))

(def default-max-tokens
  "A reply's token budget when the provider sets no :max-tokens."
  2048)

(defn request-body
  "The chat-completions request (data, before JSON) for request under an :openai-compat
  provider, streamed with its usage: its model, the messages, its :max-tokens (default-max-tokens), and DeepSeek's
  thinking mode and effort only when the provider sets them (absent: the provider's own
  default)."
  {:malli/schema [:=> [:cat :seon.model/provider :seon.model/request] :map]}
  [{:keys [model max-tokens thinking reasoning-effort]} request]
  (cond-> {:model model :messages (:messages request) :max_tokens (or max-tokens default-max-tokens)
           ;; streamed: the reply arrives as it is made, so no clock has to guess its length
           :temperature 0 :stream true :stream_options {:include_usage true}}
    thinking (assoc :thinking {:type (name thinking)})
    reasoning-effort (assoc :reasoning_effort (name reasoning-effort))))

(defn reply-of
  "A 200 reply (its chat-completion JSON as data) as the completion, with what it cost (prompt, completion,
  reasoning and cache-hit tokens) and ms; or the declared failure: no content at all, or an
  empty content beside non-empty reasoning (:seon.model/reasoning-ate-reply: thinking spent
  the budget of max-tokens)."
  {:malli/schema [:=> [:cat :map number? [:maybe :int]] [:or :seon.model/completion :seon.model/failure]]}
  [j ms max-tokens]
  (let [msg (get-in j [:choices 0 :message])
        text (:content msg)
        reasoning (:reasoning_content msg)
        u (:usage j)
        rt (get-in u [:completion_tokens_details :reasoning_tokens])
        ;; the reply stopped at max-tokens: its text is cut (kept; the forgiving reader repairs it)
        cut? (= "length" (get-in j [:choices 0 :finish_reason]))]
    (cond
      (and (str/blank? text) (string? reasoning) (not (str/blank? reasoning)))
      (contract/error :seon.model/reasoning-ate-reply 'seon.model/complete
                      (str "the reply is empty: reasoning took " (or rt (count reasoning)) (if rt " tokens" " characters")
                           " of a " max-tokens "-token budget")
                      (cond-> {:reasoning-chars (count reasoning)}
                        rt (assoc :reasoning-tokens (long rt))
                        (:completion_tokens u) (assoc :completion-tokens (long (:completion_tokens u)))
                        max-tokens (assoc :max-tokens max-tokens)))
      (string? text)
      (cond-> {:text text :prompt-tokens (long (:prompt_tokens u 0)) :completion-tokens (long (:completion_tokens u 0)) :ms ms}
        (get-in j [:choices 0 :finish_reason]) (assoc :finish-reason (str (get-in j [:choices 0 :finish_reason])))
        (string? (:model j)) (assoc :model (:model j))
        (and (string? reasoning) (not (str/blank? reasoning))) (assoc :reasoning reasoning)
        rt (assoc :reasoning-tokens (long rt))
        (:prompt_cache_hit_tokens u) (assoc :cache-hit-tokens (long (:prompt_cache_hit_tokens u)))
        cut? (assoc :truncated? true))
      :else (provider-error "the reply carries no message content" 200 {}))))

(defn sse-reply
  "A streamed reply's text (server-sent events: data: lines, each a chat-completion chunk,
  ending data: [DONE]) folded into the one reply it streams: the content and reasoning
  concatenated in order, the last finish_reason, the usage the final chunk carries."
  {:malli/schema [:=> [:cat :string] :map]}
  [text]
  (let [chunks (for [line (str/split-lines text)
                     :let [l (str/trim line)]
                     :when (and (str/starts-with? l "data:") (not= "[DONE]" (str/trim (subs l 5))))]
                 (json/read-str (str/trim (subs l 5)) :key-fn keyword))
        deltas (keep #(get-in % [:choices 0 :delta]) chunks)
        joined (fn [k] (let [xs (keep k deltas)] (when (seq xs) (apply str xs))))]
    (cond-> {:choices [{:message (cond-> {}
                                   (joined :content) (assoc :content (joined :content))
                                   (joined :reasoning_content) (assoc :reasoning_content (joined :reasoning_content)))
                        :finish_reason (last (keep #(get-in % [:choices 0 :finish_reason]) chunks))}]}
      (some :usage chunks) (assoc :usage (last (keep :usage chunks)))
      (some :model chunks) (assoc :model (some :model chunks)))))

(defn parse-reply
  "A 200 reply's body text as the completion: a stream of server-sent events, or one
  chat-completion JSON document."
  {:malli/schema [:=> [:cat :string number? [:maybe :int]] [:or :seon.model/completion :seon.model/failure]]}
  [body ms max-tokens]
  (reply-of (if (str/starts-with? (str/triml body) "data:") (sse-reply body) (json/read-str body :key-fn keyword))
            ms max-tokens))

(defn- retry-after-ms
  "The response's Retry-After (seconds) in ms, or nil."
  {:malli/schema [:=> [:cat :any] [:maybe :int]]}
  [header-pairs]
  (some-> (http/header-ci header-pairs "retry-after") str/trim parse-long (* 1000)))

(defn- openai-compat
  {:malli/schema [:=> [:cat :seon.model/provider :seon.model/request]
                  [:or :seon.model/completion :seon.model/failure]]}
  [{:keys [base-url api-key-env max-tokens] :as provider} request]
  (let [key (System/getenv api-key-env)]
    (if (str/blank? key)
      (provider-error (str "the environment variable " api-key-env " is not set") nil {})
      (let [t0 (System/nanoTime)
            r (http-post (str (str/replace base-url #"/+$" "") "/chat/completions")
                         [["Authorization" (str "Bearer " key)] ["Content-Type" "application/json"] ["Accept" "text/event-stream"]]
                         (json/write-str (request-body provider request)))
            ms (/ (- (System/nanoTime) t0) 1e6)]
        (cond
          (:seon.error/type r) r
          (not= 200 (:status r)) (provider-error (str "HTTP " (:status r)) (:status r)
                                                 (cond-> {:body (:body r)}
                                                   (retry-after-ms (:headers r)) (assoc :retry-after-ms (retry-after-ms (:headers r)))))
          :else (parse-reply (:body r) ms (or max-tokens default-max-tokens)))))))

(defn complete
  "The model's reply to request under provider."
  {:malli/schema [:=> [:cat :seon.model/provider :seon.model/request]
                  [:or :seon.model/completion :seon.model/failure]]}
  [provider request]
  (case (:kind provider)
    :scripted (scripted provider request)
    :openai-compat (openai-compat provider request)))
