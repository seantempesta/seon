(ns seon.model
  "The model, as one function of a provider value and a request: `complete` answers
  `:seon.model/completion` (the reply text, its calls of the one tool `eval`, and its token
  usage) or the declared `:seon.model/provider-error`. Every request declares that one tool
  (`eval-tool`), so the model sees each evaluation's answer before its next call.

  Providers (`:seon.model/provider`, dispatched on :kind):
  - :scripted, a deterministic stand-in: the function named by :script answers the reply
    text for a request (it sees the task id, turn and messages, as a test double may), sent
    as one eval call, its prose read as comments (seon.reader/forgive);
  - :openai-compat, an OpenAI-compatible chat API (DeepSeek's: base URL plus
    /chat/completions) over jolt-lang/http-client's transport (BSD sockets and OpenSSL
    through jolt.ffi). The API key is read from the environment variable NAMED by
    :api-key-env; it is never logged, printed or stored.

  Root loads the HTTP transport and JSON before it forks anything, so every agent holds them
  compiled."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str] [seon.reader :as reader] [jolt.http.core :as http] [clojure.data.json :as json] [seon.config :as config] [seon.print.select :as select] [seon.lib :as lib]))

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
  (let [t0 (profile/now-ns)
        text ((resolve script) request)]
    {:text "" :tool-calls [{:id (str "call-" (:turn request)) :code (:text (reader/forgive text))}]
     :prompt-tokens (reduce + (map (comp select/estimate :content) (:messages request)))
     :completion-tokens (select/estimate text) :estimated true :ms (profile/ms-since t0)}))

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

(def eval-tool
  "The model's one tool (v1's): its code is the REPL's input, read as reply text is."
  {:type "function"
   :function {:name "eval"
              :description "Evaluate Clojure forms at your REPL; the answer is this call's result."
              :parameters {:type "object"
                           :properties {:code {:type "string" :description "Clojure forms; prose in ;; comments."}}
                           :required ["code"]}}})

(defn- wire-message
  "A conversation message as the chat API reads it: an assistant's eval calls as tool_calls
  (their code as the JSON arguments), a tool result named by its call's id."
  {:malli/schema [:=> [:cat :seon.model/message] :map]}
  [{:keys [role content tool-calls tool-call-id]}]
  (cond-> {:role role :content content}
    (seq tool-calls) (assoc :tool_calls (mapv (fn [{:keys [id code]}]
                                                {:id id :type "function"
                                                 :function {:name "eval" :arguments (json/write-str {:code code})}})
                                              tool-calls))
    tool-call-id (assoc :tool_call_id tool-call-id)))

(defn request-body
  "The chat-completions request (data, before JSON) for request under an :openai-compat
  provider, streamed with its usage: its model, the messages, the one tool `eval-tool`, its
  :max-tokens, and DeepSeek's thinking mode and effort only when the provider sets them
  (absent: the provider's own default)."
  {:malli/schema [:=> [:cat :seon.model/provider :seon.model/request] :map]}
  [{:keys [model max-tokens thinking reasoning-effort]} request]
  (cond-> {:model model :messages (mapv wire-message (:messages request)) :tools [eval-tool] :max_tokens max-tokens
           ;; streamed: the reply arrives as it is made, so no clock has to guess its length
           :temperature 0 :stream true :stream_options {:include_usage true}}
    thinking (assoc :thinking {:type (name thinking)})
    reasoning-effort (assoc :reasoning_effort (name reasoning-effort))))

(defn- call-code
  "An eval call's code: the `code` of its JSON arguments, or the arguments as they came when
  they are not that JSON (a reply cut mid-call): the reader shows what they hold."
  {:malli/schema [:=> [:cat [:maybe :string]] :string]}
  [arguments]
  (let [a (or arguments "")
        j (try (json/read-str a) (catch Throwable _ nil))]
    (if (and (map? j) (string? (get j "code"))) (get j "code") a)))

(defn reply-of
  "A 200 reply (its chat-completion JSON as data) as the completion: its text, its eval calls
  ({:id :code}, in order), what it cost (prompt, completion, reasoning and cache-hit tokens)
  and ms; or the declared failure: no content and no call, or an empty content beside
  non-empty reasoning and no call (:seon.model/reasoning-ate-reply: thinking spent the
  budget of max-tokens)."
  {:malli/schema [:=> [:cat :map number? [:maybe :int]] [:or :seon.model/completion :seon.model/failure]]}
  [j ms max-tokens]
  (let [msg (get-in j [:choices 0 :message])
        calls (mapv (fn [c] {:id (str (:id c)) :code (call-code (get-in c [:function :arguments]))}) (:tool_calls msg))
        text (or (:content msg) (when (seq calls) ""))
        reasoning (:reasoning_content msg)
        u (:usage j)
        rt (get-in u [:completion_tokens_details :reasoning_tokens])
        ;; the reply stopped at max-tokens: its text is cut (kept; the forgiving reader repairs it)
        cut? (= "length" (get-in j [:choices 0 :finish_reason]))]
    (cond
      (and (str/blank? text) (empty? calls) (string? reasoning) (not (str/blank? reasoning)))
      (contract/error :seon.model/reasoning-ate-reply 'seon.model/complete
                      (str "the reply is empty: reasoning took " (or rt (count reasoning)) (if rt " tokens" " characters")
                           " of a " max-tokens "-token budget")
                      (cond-> {:reasoning-chars (count reasoning)}
                        rt (assoc :reasoning-tokens (long rt))
                        (:completion_tokens u) (assoc :completion-tokens (long (:completion_tokens u)))
                        max-tokens (assoc :max-tokens max-tokens)))
      (string? text)
      ;; a count the provider did not send is absent, never 0: nobody measured it
      (cond-> {:text text :ms ms}
        (:prompt_tokens u) (assoc :prompt-tokens (long (:prompt_tokens u)))
        (:completion_tokens u) (assoc :completion-tokens (long (:completion_tokens u)))
        (seq calls) (assoc :tool-calls calls)
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
  concatenated in order, each tool call's pieces joined by its index (its id and name kept,
  its arguments appended), the last finish_reason, the usage the final chunk carries."
  {:malli/schema [:=> [:cat :string] :map]}
  [text]
  (let [chunks (for [line (str/split-lines text)
                     :let [l (str/trim line)]
                     :when (and (str/starts-with? l "data:") (not= "[DONE]" (str/trim (subs l 5))))]
                 (json/read-str (str/trim (subs l 5)) :key-fn keyword))
        deltas (keep #(get-in % [:choices 0 :delta]) chunks)
        joined (fn [k] (let [xs (keep k deltas)] (when (seq xs) (apply str xs))))
        calls (reduce (fn [acc {:keys [index id function]}]
                        (update acc index (fn [c] (cond-> (or c {:function {:arguments ""}})
                                                    id (assoc :id id)
                                                    (:name function) (assoc-in [:function :name] (:name function))
                                                    (:arguments function) (update-in [:function :arguments] str (:arguments function))))))
                      (sorted-map) (mapcat :tool_calls deltas))]
    (cond-> {:choices [{:message (cond-> {}
                                   (seq calls) (assoc :tool_calls (vec (vals calls)))
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
  (let [key (config/secret api-key-env)]
    (if (str/blank? key)
      (provider-error (str "the environment variable " api-key-env " is not set") nil {})
      (let [t0 (profile/now-ns)
            r (http-post (str (str/replace base-url #"/+$" "") "/chat/completions")
                         [["Authorization" (str "Bearer " key)] ["Content-Type" "application/json"] ["Accept" "text/event-stream"]]
                         (json/write-str (request-body provider request)))
            ms (profile/ms-since t0)]
        (cond
          (:seon.error/type r) r
          (not= 200 (:status r)) (provider-error (str "HTTP " (:status r)) (:status r)
                                                 (cond-> {:body (:body r)}
                                                   (retry-after-ms (:headers r)) (assoc :retry-after-ms (retry-after-ms (:headers r)))))
          :else (parse-reply (:body r) ms max-tokens))))))

(defn complete
  "The model's reply to request under provider."
  {:malli/schema [:=> [:cat :seon.model/provider :seon.model/request]
                  [:or :seon.model/completion :seon.model/failure]]}
  [provider request]
  (case (:kind provider)
    :none (contract/error :seon.model/no-provider 'seon.model/complete
                          "no model provider is configured: set SEON_PROFILE=deepseek (a paid model) or SEON_PROFILE=scripted (the stand-in), or :seon.config.ai/provider" {})
    :scripted (scripted provider request)
    :openai-compat (openai-compat provider request)))
