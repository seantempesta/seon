(ns seon.ai.http
  "JVM java.net.http leaf for ordinary and SSE LLM completions."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [seon.ai.anthropic.core :as anthropic]
            [seon.ai.core :as ai]
            [seon.ai.openai-compat.core :as openai])
  (:import [java.io BufferedReader IOException InputStream InputStreamReader]
           [java.net ConnectException URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest
            HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers
            HttpTimeoutException]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(set! *warn-on-reflection* true)

(def ^:dynamic *environment-value*
  "Call-time environment lookup; dynamically replaceable by focused tests."
  #(System/getenv ^String %))

(def ^:private json-mapper
  (json/object-mapper {:decode-key-fn true}))

(defonce ^:private client-state (atom nil))

(defn- failure
  ([message]
   {:seon.ai/error {:seon.ai/msg message}})
  ([message fields]
   {:seon.ai/error (merge {:seon.ai/msg message} fields)}))

(defn- process-client
  [connect-timeout-ms]
  (if-let [{configured-ms :connect-timeout-ms client :client} @client-state]
    (if (= configured-ms connect-timeout-ms)
      client
      (failure
       "The process-shared LLM HttpClient connect timeout changed; restart the JVM claimant."
       {:seon.ai/transport? true}))
    (let [created (-> (HttpClient/newBuilder)
                      (.connectTimeout (Duration/ofMillis connect-timeout-ms))
                      (.followRedirects HttpClient$Redirect/NORMAL)
                      (.build))
          candidate {:connect-timeout-ms connect-timeout-ms :client created}]
      (if (compare-and-set! client-state nil candidate)
        created
        (recur connect-timeout-ms)))))

(defn- resolved-credential
  [candidates]
  (some (fn [[environment-name credential-class]]
          (when-let [secret (*environment-value* environment-name)]
            {:secret secret
             :source {:seon.ai/credential-class credential-class
                      :seon.ai/api-key-env environment-name}}))
        candidates))

(defn- request-builder
  [{:seon.ai.http/keys
    [endpoint headers body request-timeout-ms credential-header
     credential-prefix]}
   secret]
  (let [builder (-> (HttpRequest/newBuilder (URI/create endpoint))
                    (.timeout (Duration/ofMillis request-timeout-ms))
                    (.POST
                     (HttpRequest$BodyPublishers/ofString
                      (json/write-value-as-string body)
                      StandardCharsets/UTF_8)))]
    (doseq [[header value] headers]
      (.header builder header value))
    (.header builder credential-header (str credential-prefix secret))
    (.build builder)))

(defn- header
  [^HttpResponse response name]
  (some-> (.firstValue (.headers response) name) (.orElse nil)))

(defn- status-failure
  [^HttpResponse response body]
  (let [retry-after-ms
        (some-> (header response "retry-after")
                (ai/parse-retry-after-ms (System/currentTimeMillis)))]
    (failure
     (str "The LLM provider returned HTTP " (.statusCode response) ".")
     (cond-> {:seon.ai/status (.statusCode response)
              :seon.ai/raw-body body}
       (some? retry-after-ms)
       (assoc :seon.ai/retry-after-ms retry-after-ms)))))

(defn- read-json
  [body]
  (json/read-value body json-mapper))

(defn- send-batch
  [^HttpClient client ^HttpRequest request maximum-response-bytes]
  (let [handler (HttpResponse$BodyHandlers/limiting
                 (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)
                 maximum-response-bytes)
        response (.send client request handler)
        body (.body response)]
    (if (<= 200 (.statusCode response) 299)
      {:seon.ai.http/body (read-json body)}
      (status-failure response body))))

(defn- sse-data
  [line]
  (when (str/starts-with? line "data:")
    (str/trim (subs line (count "data:")))))

(defn- send-stream
  [^HttpClient client ^HttpRequest request maximum-response-bytes
   initial step abort?]
  (let [handler (HttpResponse$BodyHandlers/limiting
                 (HttpResponse$BodyHandlers/ofInputStream)
                 maximum-response-bytes)
        response (.send client request handler)]
    (if-not (<= 200 (.statusCode response) 299)
      (with-open [^InputStream body (.body response)]
        (status-failure response
                        (slurp body :encoding (.name StandardCharsets/UTF_8))))
      (with-open [^InputStream body (.body response)
                  reader (BufferedReader.
                          (InputStreamReader. body StandardCharsets/UTF_8))]
        (loop [state initial]
          (if-let [line (.readLine reader)]
            (let [data (sse-data line)]
              (cond
                (or (nil? data) (str/blank? data))
                (recur state)

                (= "[DONE]" data)
                state

                :else
                (let [next-state (step state (read-json data))]
                  (if (abort? next-state)
                    (assoc next-state :seon.ai.http/aborted? true)
                    (recur next-state)))))
            state))))))

(defn request!
  "Execute one prepared provider request on the invocation worker thread."
  {:malli/schema [:=> [:catn [::request :map]] :map]}
  [{:seon.ai.http/keys
    [credential-candidates config-resolution connect-timeout-ms
     maximum-response-bytes request-timeout-ms stream? stream-initial
     stream-step stream-abort?]
    :as request}]
  (cond
    (not (pos-int? request-timeout-ms))
    (failure "The resolved LLM request timeout must be a positive integer.")

    (not (pos-int? connect-timeout-ms))
    (failure "The resolved LLM connect timeout must be a positive integer.")

    (not (pos-int? maximum-response-bytes))
    (failure "The resolved LLM response bound must be a positive integer.")

    :else
    (if-let [{:keys [secret source]} (resolved-credential credential-candidates)]
      (let [evidence (ai/config-evidence config-resolution source)]
        (try
          (let [client (process-client connect-timeout-ms)]
            (if (:seon.ai/error client)
              (assoc client :seon.ai/config-evidence evidence)
              (assoc
               (if stream?
                 (send-stream client
                              (request-builder request secret)
                              maximum-response-bytes
                              stream-initial stream-step stream-abort?)
                 (send-batch client
                             (request-builder request secret)
                             maximum-response-bytes))
               :seon.ai/config-evidence evidence)))
          (catch InterruptedException interrupted
            (throw interrupted))
          (catch HttpTimeoutException _
            (assoc
             (failure "The LLM HTTP request timed out."
                      {:seon.ai/timeout? true
                       :seon.ai/transport? true})
             :seon.ai/config-evidence evidence))
          (catch ConnectException _
            (assoc
             (failure "The LLM provider connection failed."
                      {:seon.ai/transport? true})
             :seon.ai/config-evidence evidence))
          (catch IOException _
            (if (.isInterrupted (Thread/currentThread))
              (throw (InterruptedException.
                      "LLM response consumption interrupted"))
              (assoc
               (failure "The LLM HTTP transport failed."
                        {:seon.ai/transport? true})
               :seon.ai/config-evidence evidence)))
          (catch IllegalArgumentException _
            (assoc
             (failure "The resolved LLM HTTP request is invalid.")
             :seon.ai/config-evidence evidence))
          (catch Throwable _
            (assoc
             (failure "The LLM provider returned an invalid response.")
             :seon.ai/config-evidence evidence))))
      (assoc
       (failure
        "No LLM API key resolved from the configured environment-variable names.")
       :seon.ai/config-evidence (ai/config-evidence config-resolution)))))

(defn complete
  "JVM claimant LLM transport installed on the driver host map."
  {:malli/schema [:=> [:catn [::request :map]] :map]}
  [request]
  (case (get-in request
                [:seon.ai/config-resolution
                 :seon.ai/resolved-config
                 :seon.ai/provider])
    :anthropic (anthropic/complete request request!)
    (:deepseek :openai-compat) (openai/complete request request!)
    (failure "The JVM LLM HTTP leaf does not support the resolved provider.")))
