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
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [jolt.http.core :as http] [clojure.data.json :as json]))

(defn estimate-tokens
  "Tokens of text, estimated at 4 characters a token (the stand-in's usage)."
  {:malli/schema [:=> [:cat :string] :int]}
  [s] (long (Math/ceil (/ (count s) 4.0))))

(defn- provider-error
  "The declared failure of a model call."
  {:malli/schema [:=> [:cat :string [:maybe :int]] :seon.model/provider-error]}
  [message status]
  (cond-> {:seon.error/type :seon.model/provider-error :seon.error/operation 'seon.model/complete
           :seon.error/message message}
    status (assoc :status status)))

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
                  [:or [:map [:status :int] [:body :string]] :seon.model/provider-error]]}
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

(defn- openai-compat
  {:malli/schema [:=> [:cat :seon.model/provider :seon.model/request]
                  [:or :seon.model/completion :seon.model/provider-error]]}
  [{:keys [base-url model api-key-env timeout-ms max-tokens] :or {timeout-ms 120000 max-tokens 2048}} request]
  (let [key (System/getenv api-key-env)
        write-json json/write-str
        read-json json/read-str]
    (if (str/blank? key)
      (provider-error (str "the environment variable " api-key-env " is not set") nil)
      (let [t0 (System/nanoTime)
            r (http-post (str (str/replace base-url #"/+$" "") "/chat/completions")
                         [["Authorization" (str "Bearer " key)] ["Content-Type" "application/json"] ["Accept" "application/json"]]
                         (write-json {:model model :messages (:messages request) :max_tokens max-tokens
                                      :temperature 0 :stream false})
                         timeout-ms)
            ms (/ (- (System/nanoTime) t0) 1e6)]
        (cond
          (:seon.error/type r) r
          (not= 200 (:status r)) (provider-error (str "HTTP " (:status r) ": " (subs (:body r) 0 (min 300 (count (:body r))))) (:status r))
          :else
          (let [j (read-json (:body r) :key-fn keyword)
                text (get-in j [:choices 0 :message :content])]
            (if (string? text)
              {:text text :prompt-tokens (long (get-in j [:usage :prompt_tokens] 0))
               :completion-tokens (long (get-in j [:usage :completion_tokens] 0)) :ms ms}
              (provider-error "the reply carries no message content" 200))))))))

(defn complete
  "The model's reply to request under provider."
  {:malli/schema [:=> [:cat :seon.model/provider :seon.model/request]
                  [:or :seon.model/completion :seon.model/provider-error]]}
  [provider request]
  (case (:kind provider)
    :scripted (scripted provider request)
    :openai-compat (openai-compat provider request)))
