(ns seon.ai.http-test
  "Localized real-socket tests for the JVM LLM HTTP leaf."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jsonista.core :as json]
            [malli.core :as m]
            [seon.ai.http :as http]
            [seon.config.resolve :as config.resolve])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress ServerSocket]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private transport-config
  {:seon.config.model-transport/response-identity-cap 128
   :seon.config.model-transport/endpoint-cap 512
   :seon.config.model-transport/connect-timeout-ms 300000
   :seon.config.model-transport/maximum-response-bytes 16777216})

(defn- request
  [port stream? timeout-ms]
  {:seon.ai/system-prompt "Return one form."
   :seon.ai/ctx "Add one and two."
   :seon.ai/stream? stream?
   :seon.ai/reply-evaluation (if stream? :first-form :batch)
   :seon.ai/request-timeout-ms timeout-ms
   :seon.ai/config-resolution
   {:seon.ai/resolved-config
    (merge transport-config
           {:seon.ai/provider :deepseek
            :seon.ai/model "stub-model"
            :seon.ai/max-tokens 20
            :seon.ai/thinking "false"
            :seon.ai/completion-limit-field :max-tokens
            :seon.ai/base-url (str "http://127.0.0.1:" port "/v1")})
    :seon.ai/provenance {:seon.ai/provider :shipped-default}}})

(defn- server
  [handler]
  (let [instance
        (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext instance "/v1/chat/completions" handler)
    (.start instance)
    instance))

(defn- reset-client!
  []
  (reset! (var-get (ns-resolve 'seon.ai.http 'client-state)) nil))

(defn- write-response!
  [exchange content-type body]
  (let [bytes (.getBytes body StandardCharsets/UTF_8)]
    (.add (.getResponseHeaders exchange) "Content-Type" content-type)
    (.sendResponseHeaders exchange 200 (count bytes))
    (with-open [output (.getResponseBody exchange)]
      (.write output bytes))))

(deftest http-dials-are-resolved-config-facts
  (let [manifest
        {:seon.config/model-transport
         {:seon.config.model-transport/connect-timeout-ms 1234
          :seon.config.model-transport/maximum-response-bytes 5678}}
        singleton
        (config.resolve/resolve-config-singleton
         manifest {}
         {:seon.hardware/cores 8
          :seon.hardware/system-memory-bytes (* 32 1024 1024 1024)
          :seon.hardware/fd-soft-limit 2048})]
    (is (m/validate :seon.config/manifest manifest))
    (is (= 1234
           (:seon.config.model-transport/connect-timeout-ms singleton)))
    (is (= 5678
           (:seon.config.model-transport/maximum-response-bytes singleton)))
    (doseq [[attribute schema] config.resolve/model-transport-dial-schemas]
      (is (re-find #"Default" (get-in schema [1 :description]))
          (str attribute))
      (is (str/includes? (get-in schema [1 :description]) (str attribute))
          (str attribute)))))

(deftest batch-completion-uses-the-ordinary-response-envelope
  (let [observed (promise)
        instance
        (server
         (reify HttpHandler
           (handle [_ exchange]
             (deliver observed
                      {:authorization
                       (.getFirst (.getRequestHeaders exchange)
                                  "Authorization")
                       :body
                       (json/read-value
                        (slurp (.getRequestBody exchange))
                        (json/object-mapper {:decode-key-fn true}))})
             (Thread/sleep 10)
             (write-response!
              exchange "application/json"
              (json/write-value-as-string
               {:id "stub-request"
                :model "stub-model"
                :choices [{:message {:content "(+ 1 2)"}
                           :finish_reason "stop"}]
                :usage {:prompt_tokens 5
                        :completion_tokens 4
                        :total_tokens 9}})))))]
    (try
      (binding [http/*environment-value*
                #(when (= "DEEPSEEK_API_KEY" %) "not-a-real-secret")]
        (let [result (http/complete
                      (request (.getPort (.getAddress instance)) false 2000))]
          (is (= "(+ 1 2)" (:seon.ai/text result)))
          (is (= 200 (:seon.ai/status result)))
          (is (<= 10000000 (:seon.ai/provider-duration-ns result))
              "provider timing includes response-body receipt and parsing")
          (is (= 9 (get-in result [:seon.ai/usage :total_tokens])))
          (is (= "Bearer not-a-real-secret"
                 (:authorization (deref observed 1000 nil))))
          (is (not (true? (get-in @observed [:body :stream]))))))
      (finally
        (.stop instance 0)))))

(deftest long-lived-http-failures-retain-flat-causes
  (let [bounded (fn [_ request] (http/complete request))
        host nil]
    (try
      (reset-client!)
      (binding [http/*environment-value* (constantly nil)]
        (let [result (bounded host (request 1 false 1000))]
          (is (str/includes?
               (get-in result [:seon.ai/error :seon.ai/msg])
               "No LLM API key"))
          (is (nil? (get-in result [:seon.ai/error
                                    :seon.ai/transport?])))))

      (reset! (var-get (ns-resolve 'seon.ai.http 'client-state))
              {:connect-timeout-ms 1 :client ::not-used})
      (binding [http/*environment-value* (constantly "stub-key")]
        (let [result (bounded host (request 1 false 1000))]
          (is (str/includes?
               (get-in result [:seon.ai/error :seon.ai/msg])
               "connect timeout changed"))
          (is (true? (get-in result [:seon.ai/error
                                     :seon.ai/transport?])))))

      (reset-client!)
      (let [invalid-request!
            (var-get (ns-resolve 'seon.ai.http 'request!))]
        (binding [http/*environment-value* (constantly "stub-key")]
          (let [result
                (invalid-request!
                 {:seon.ai.http/endpoint "://invalid"
                  :seon.ai.http/credential-candidates
                  [["DEEPSEEK_API_KEY" :configured]]
                  :seon.ai.http/config-resolution
                  (:seon.ai/config-resolution (request 1 false 1000))
                  :seon.ai.http/credential-header "Authorization"
                  :seon.ai.http/credential-prefix "Bearer "
                  :seon.ai.http/headers {"Content-Type" "application/json"}
                  :seon.ai.http/body {}
                  :seon.ai.http/request-timeout-ms 1000
                  :seon.ai.http/connect-timeout-ms 300000
                  :seon.ai.http/maximum-response-bytes 1024
                  :seon.ai.http/stream? false})]
            (is (= "java.lang.IllegalArgumentException"
                   (get-in result [:seon.ai/error
                                   :seon.ai/exception-class])))
            (is (string? (get-in result [:seon.ai/error
                                         :seon.ai/exception-message]))))))

      (reset-client!)
      (let [closed-port
            (with-open [socket (ServerSocket. 0)]
              (.getLocalPort socket))]
        (binding [http/*environment-value* (constantly "stub-key")]
          (let [result (bounded host (request closed-port false 1000))]
            (is (true? (get-in result [:seon.ai/error
                                       :seon.ai/transport?])))
            (is (string? (get-in result [:seon.ai/error
                                         :seon.ai/exception-class])))
            (is (string? (get-in result [:seon.ai/error
                                         :seon.ai/exception-message])))
            (is (nil? (get-in result [:seon.ai/error
                                      :seon.ai/status]))))))

      (reset-client!)
      (let [status-server
            (server
             (reify HttpHandler
               (handle [_ exchange]
                 (.add (.getResponseHeaders exchange) "Retry-After" "1")
                 (let [body "{\"error\":\"diagnostic\"}"
                       bytes (.getBytes body StandardCharsets/UTF_8)]
                   (.sendResponseHeaders exchange 503 (count bytes))
                   (with-open [output (.getResponseBody exchange)]
                     (.write output bytes))))))]
        (try
          (binding [http/*environment-value* (constantly "stub-key")]
            (let [result
                  (bounded host
                           (request (.getPort (.getAddress status-server))
                                    false 1000))]
              (is (= 503 (get-in result [:seon.ai/error :seon.ai/status])))
              (is (= "{\"error\":\"diagnostic\"}"
                     (get-in result [:seon.ai/error :seon.ai/raw-body])))
              (is (int? (get-in result
                                [:seon.ai/error
                                 :seon.ai/retry-after-ms])))))
          (finally
            (.stop status-server 0))))

      (reset-client!)
      (let [invalid-json-server
            (server
             (reify HttpHandler
               (handle [_ exchange]
                 (write-response! exchange "application/json" "not-json"))))]
        (try
          (binding [http/*environment-value* (constantly "stub-key")]
            (let [result
                  (bounded host
                           (request (.getPort (.getAddress invalid-json-server))
                                    false 1000))]
              (is (= "not-json"
                     (get-in result [:seon.ai/error :seon.ai/raw-body])))
              (is (= 200 (get-in result [:seon.ai/error
                                         :seon.ai/status])))
              (is (string? (get-in result [:seon.ai/error
                                           :seon.ai/exception-class])))
              (is (string? (get-in result [:seon.ai/error
                                           :seon.ai/exception-message])))))
          (finally
            (.stop invalid-json-server 0))))
      (finally
        (reset-client!)))))

(deftest stream-aborts-after-the-portable-first-form-predicate
  (let [instance
        (server
         (reify HttpHandler
           (handle [_ exchange]
             (.add (.getResponseHeaders exchange)
                   "Content-Type" "text/event-stream")
             (.sendResponseHeaders exchange 200 0)
             (with-open [output (.getResponseBody exchange)]
               (doseq [event [{:choices [{:delta {:content "("}}]}
                              {:choices [{:delta {:content "+ 1 2)"}}]}
                              {:choices [{:delta {:content " unwanted"}}]}]]
                 (.write output
                         (.getBytes
                          (str "data: " (json/write-value-as-string event)
                               "\n\n")
                          StandardCharsets/UTF_8))
                 (.flush output))))))]
    (try
      (binding [http/*environment-value* (constantly "stub-key")]
        (let [result
              (http/complete
               (request (.getPort (.getAddress instance)) true 2000))]
          (is (= "(+ 1 2)" (:seon.ai/text result)))
          (is (true? (:seon.ai/estimated? result)))
          (is (pos-int? (get-in result
                                [:seon.ai/usage :completion_tokens])))))
      (finally
        (.stop instance 0)))))

(deftest batch-stream-retains-all-forms-terminal-usage-and-sink-isolation
  (let [offers (atom [])
        usage {:prompt_tokens 5
               :completion_tokens 8
               :total_tokens 13}
        instance
        (server
         (reify HttpHandler
           (handle [_ exchange]
             (.add (.getResponseHeaders exchange)
                   "Content-Type" "text/event-stream")
             (.sendResponseHeaders exchange 200 0)
             (with-open [output (.getResponseBody exchange)]
               (doseq [event [{:choices [{:delta {:content "(+ 1 2)"}}]}
                              {:choices [{:delta {:content "\n(+ 3 4)"}
                                          :finish_reason "stop"}]}
                              {:choices [] :usage usage}]]
                 (.write output
                         (.getBytes
                          (str "data: " (json/write-value-as-string event)
                               "\n\n")
                          StandardCharsets/UTF_8)))
               (.write output (.getBytes "data: [DONE]\n\n"
                                         StandardCharsets/UTF_8))))))]
    (try
      (binding [http/*environment-value* (constantly "stub-key")]
        (let [result
              (http/complete
               (assoc (request (.getPort (.getAddress instance)) true 2000)
                      :seon.ai/reply-evaluation :batch
                      :seon.ai/progress!
                      (fn [prefix]
                        (swap! offers conj prefix)
                        (throw (ex-info "presentation-only" {})))))]
          (is (= "(+ 1 2)\n(+ 3 4)" (:seon.ai/text result)))
          (is (= usage (:seon.ai/usage result)))
          (is (not (:seon.ai/estimated? result)))
          (is (= ["(+ 1 2)" "(+ 1 2)\n(+ 3 4)"] @offers))))
      (finally
        (.stop instance 0)))))

(deftest hung-http-timeout-is-a-flat-timeout
  (let [release (CountDownLatch. 1)
        entered (CountDownLatch. 1)
        instance
        (server
         (reify HttpHandler
           (handle [_ exchange]
             (.add (.getResponseHeaders exchange)
                   "Content-Type" "text/event-stream")
             (.sendResponseHeaders exchange 200 0)
             (.countDown entered)
             (.await release 5 TimeUnit/SECONDS)
             (.close exchange))))
        ]
    (try
      (binding [http/*environment-value* (constantly "stub-key")]
        (let [result
              (http/complete
               (request (.getPort (.getAddress instance)) true 100))]
          (is (.await entered 1 TimeUnit/SECONDS))
          (is (true? (get-in result [:seon.ai/error :seon.ai/timeout?])))
          (is (string? (get-in result [:seon.ai/error :seon.ai/msg])))
          (is (map? result))))
      (finally
        (.countDown release)
        (.stop instance 0)))))
