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
  (:require [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str] [jolt.http.core :as http] [clojure.data.json :as json] [seon.config :as config]))

(defn estimate-tokens
  "Tokens of text, estimated at 4 characters a token (the stand-in's usage)."
  {:malli/schema [:=> [:cat :string] :int]}
  [s] (long (Math/ceil (/ (count s) 4.0))))

(defn- provider-error
  "The declared failure of a model call, as the one error map: the provider's HTTP 429 is
  :seon.model/rate-limited (seon.fx/settle halves the model bucket's rate on it), any
  other failure :seon.model/provider-error."
  {:malli/schema [:=> [:cat :string [:maybe :int]] :seon.model/failure]}
  [message status]
  (contract/error (if (= 429 status) :seon.model/rate-limited :seon.model/provider-error) 'seon.model/complete message
                  (when status {:status status})))

(defn- scripted
  {:malli/schema [:=> [:cat :seon.model/provider :seon.model/request] :seon.model/completion]}
  [{:keys [script]} request]
  (let [t0 (System/nanoTime)
        text ((resolve script) request)]
    {:text text :prompt-tokens (reduce + (map (comp estimate-tokens :content) (:messages request)))
     :completion-tokens (estimate-tokens text) :ms (/ (- (System/nanoTime) t0) 1e6)}))

(defn- http-post
  "POST body (a JSON string) to url with headers; answers {:status :body} (body text).
  The interop edge: a transport failure is the provider error."
  {:malli/schema [:=> [:cat :string [:vector [:tuple :string :string]] :string pos-int?]
                  [:or [:map [:status :int] [:body :string]] :seon.model/failure]]}
  [url headers body timeout-ms]
  (let [parse-url http/parse-url tget http/tget port http/effective-port connect http/connect-stream
        build http/build-request write http/s-write read-response http/read-response close http/s-close]
    (try
      (let [u (parse-url url)
            s (connect (tget u :host) (port u) (= "https" (tget u :protocol)) false timeout-ms 10000)]
        (try
          (write s (build "POST" u headers body))
          (let [r (read-response s (+ (System/currentTimeMillis) timeout-ms) "POST")]
            {:status (:status r) :body (String. ^bytes (:body r) "UTF-8")})
          (finally (close s))))
      (catch Throwable e (provider-error (str "transport: " (or (ex-message e) (str (type e)))) nil)))))

(def default-max-tokens
  "A reply's token budget when the provider sets no :max-tokens: :seon.config.ai/max-tokens."
  (config/value :seon.config.ai/max-tokens))

(defn request-body
  "The chat-completions request (data, before JSON) for request under an :openai-compat
  provider: its model, the messages, its :max-tokens (default-max-tokens), and DeepSeek's
  thinking mode and effort only when the provider sets them (absent: the provider's own
  default)."
  {:malli/schema [:=> [:cat :seon.model/provider :seon.model/request] :map]}
  [{:keys [model max-tokens thinking reasoning-effort]} request]
  (cond-> {:model model :messages (:messages request) :max_tokens (or max-tokens default-max-tokens)
           :temperature 0 :stream false}
    thinking (assoc :thinking {:type (name thinking)})
    reasoning-effort (assoc :reasoning_effort (name reasoning-effort))))

(defn parse-reply
  "A 200 reply's JSON body text as the completion, with what it cost (prompt, completion,
  reasoning and cache-hit tokens) and ms; or the declared failure: no content at all, or an
  empty content beside non-empty reasoning (:seon.model/reasoning-ate-reply: thinking spent
  the budget of max-tokens)."
  {:malli/schema [:=> [:cat :string number? [:maybe :int]] [:or :seon.model/completion :seon.model/failure]]}
  [body ms max-tokens]
  (let [j (json/read-str body :key-fn keyword)
        msg (get-in j [:choices 0 :message])
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
        rt (assoc :reasoning-tokens (long rt))
        (:prompt_cache_hit_tokens u) (assoc :cache-hit-tokens (long (:prompt_cache_hit_tokens u)))
        cut? (assoc :truncated? true))
      :else (provider-error "the reply carries no message content" 200))))

(defn- openai-compat
  {:malli/schema [:=> [:cat :seon.model/provider :seon.model/request]
                  [:or :seon.model/completion :seon.model/failure]]}
  [{:keys [base-url api-key-env timeout-ms max-tokens] :or {timeout-ms 120000} :as provider} request]
  (let [key (config/secret api-key-env)]
    (if (str/blank? key)
      (provider-error (str "the environment variable " api-key-env " is not set") nil)
      (let [t0 (System/nanoTime)
            r (http-post (str (str/replace base-url #"/+$" "") "/chat/completions")
                         [["Authorization" (str "Bearer " key)] ["Content-Type" "application/json"] ["Accept" "application/json"]]
                         (json/write-str (request-body provider request))
                         timeout-ms)
            ms (/ (- (System/nanoTime) t0) 1e6)]
        (cond
          (:seon.error/type r) r
          (not= 200 (:status r)) (provider-error (str "HTTP " (:status r) ": " (:body r)) (:status r))
          :else (parse-reply (:body r) ms (or max-tokens default-max-tokens)))))))

(defn complete
  "The model's reply to request under provider."
  {:malli/schema [:=> [:cat :seon.model/provider :seon.model/request]
                  [:or :seon.model/completion :seon.model/failure]]}
  [provider request]
  (case (:kind provider)
    :scripted (scripted provider request)
    :openai-compat (openai-compat provider request)))
