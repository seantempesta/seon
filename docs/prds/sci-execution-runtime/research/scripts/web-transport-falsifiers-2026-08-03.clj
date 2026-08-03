(ns web-transport-falsifiers-2026-08-03
  "Compare Hato and the JDK client at the `my.web` transport boundary."
  (:require [hato.client :as hato])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io InputStream]
           [java.net InetSocketAddress URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest
            HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util.concurrent CountDownLatch Executors TimeUnit]))

(defn- respond!
  [^HttpExchange exchange status headers body]
  (doseq [[name value] headers]
    (.add (.getResponseHeaders exchange) name value))
  (let [octets (.getBytes ^String body java.nio.charset.StandardCharsets/UTF_8)]
    (.sendResponseHeaders exchange status (alength octets))
    (with-open [output (.getResponseBody exchange)]
      (.write output octets))))

(defn- handler
  [f]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (f exchange)
        (finally
          (.close ^HttpExchange exchange))))))

(defn- start-server
  []
  (let [stream-ready (CountDownLatch. 1)
        stream-release (CountDownLatch. 1)
        timeout-release (CountDownLatch. 1)
        body-timeout-ready (CountDownLatch. 1)
        body-timeout-release (CountDownLatch. 1)
        executor (Executors/newVirtualThreadPerTaskExecutor)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server executor)
    (.createContext
     server "/redirect"
     (handler
      (fn [exchange]
        (.add (.getResponseHeaders exchange) "Location" "/final")
        (.sendResponseHeaders exchange 302 -1))))
    (.createContext
     server "/final"
     (handler #(respond! % 200 {"Content-Type" "text/plain"} "final")))
    (.createContext
     server "/stream"
     (handler
      (fn [exchange]
        (.add (.getResponseHeaders exchange) "Content-Type" "text/plain")
        (.sendResponseHeaders exchange 200 0)
        (let [output (.getResponseBody exchange)]
          (.write output (.getBytes "prefix" java.nio.charset.StandardCharsets/UTF_8))
          (.flush output)
          (.countDown stream-ready)
          (.await stream-release 5 TimeUnit/SECONDS)
          (.write output (.getBytes "-suffix" java.nio.charset.StandardCharsets/UTF_8))))))
    (.createContext
     server "/timeout"
     (handler
      (fn [exchange]
        (.await timeout-release 5 TimeUnit/SECONDS)
        (respond! exchange 200 {} "late"))))
    (.createContext
     server "/body-timeout"
     (handler
      (fn [exchange]
        (.sendResponseHeaders exchange 200 0)
        (let [output (.getResponseBody exchange)]
          (.write output (.getBytes "prefix"
                                    java.nio.charset.StandardCharsets/UTF_8))
          (.flush output)
          (.countDown body-timeout-ready)
          (.await body-timeout-release 5 TimeUnit/SECONDS)
          (.write output (.getBytes "-late"
                                    java.nio.charset.StandardCharsets/UTF_8))))))
    (.start server)
    {:server server
     :executor executor
     :base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)))
     :stream-ready stream-ready
     :stream-release stream-release
     :timeout-release timeout-release
     :body-timeout-ready body-timeout-ready
     :body-timeout-release body-timeout-release}))

(defn- jdk-client
  []
  (-> (HttpClient/newBuilder)
      (.followRedirects HttpClient$Redirect/NEVER)
      .build))

(defn- jdk-get
  [client url timeout-ms]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMillis timeout-ms))
                    .GET
                    .build)
        response (.send ^HttpClient client request
                        (HttpResponse$BodyHandlers/ofInputStream))]
    {:status (.statusCode response)
     :uri (str (.uri response))
     :headers (into {} (.map (.headers response)))
     :body (.body response)}))

(defn- hato-get
  [url timeout-ms]
  (let [response (hato/get url {:as :stream
                                :throw-exceptions false
                                :timeout timeout-ms
                                :http-client {:redirect-policy :never}})]
    {:status (:status response)
     :uri (:uri response)
     :headers (:headers response)
     :body (:body response)}))

(defn- timeout-result
  [f]
  (let [started (System/nanoTime)]
    (try
      (with-open [^InputStream body (:body (f))]
        (.readAllBytes body))
      {:completed? true}
      (catch Throwable error
        {:completed? false
         :throwable (.getName (class error))
         :elapsed-ms (long (/ (- (System/nanoTime) started) 1000000))}))))

(defn- body-timeout-result
  [get-fn url body-ready body-release]
  (let [started (System/nanoTime)]
    (try
      (let [response (get-fn url 500)
            body ^InputStream (:body response)
            reading (future
                      (try
                        (.readAllBytes body)
                        {:completed? true}
                        (catch Throwable error
                          {:completed? false
                           :throwable (.getName (class error))})))]
        (.await ^CountDownLatch body-ready 1 TimeUnit/SECONDS)
        (let [result (deref reading 750 ::still-reading)]
          (.close body)
          (.countDown ^CountDownLatch body-release)
          {:response-returned? true
           :request-timeout-bounded-body?
           (and (not= ::still-reading result)
                (false? (:completed? result)))
           :read result
           :elapsed-ms (long (/ (- (System/nanoTime) started) 1000000))}))
      (catch Throwable error
        (.countDown ^CountDownLatch body-release)
        {:response-returned? false
         :throwable (.getName (class error))
         :elapsed-ms (long (/ (- (System/nanoTime) started) 1000000))}))))

(defn- one-transport
  [name get-fn base-url stream-ready stream-release
   body-timeout-ready body-timeout-release]
  (let [redirect (get-fn (str base-url "/redirect") 1000)
        stream (future
                 (let [response (get-fn (str base-url "/stream") 1000)
                       body ^InputStream (:body response)
                       prefix (String. (.readNBytes body 6)
                                        java.nio.charset.StandardCharsets/UTF_8)]
                   (.countDown ^CountDownLatch stream-release)
                   (let [suffix (String. (.readAllBytes body)
                                         java.nio.charset.StandardCharsets/UTF_8)]
                     (.close body)
                     {:status (:status response)
                      :prefix prefix
                      :suffix suffix})))
        ready? (.await ^CountDownLatch stream-ready 1 TimeUnit/SECONDS)
        streamed (deref stream 2000 ::timed-out)
        timeout (timeout-result #(get-fn (str base-url "/timeout") 100))
        body-timeout (body-timeout-result
                      get-fn (str base-url "/body-timeout")
                      body-timeout-ready body-timeout-release)]
    (when-let [body (:body redirect)]
      (.close ^InputStream body))
    {:transport name
     :redirect {:status (:status redirect)
                :uri (:uri redirect)
                :location (or (get (:headers redirect) "location")
                              (get (:headers redirect) "Location"))}
     :stream {:first-chunk-observed-before-release? ready?
              :result streamed}
     :timeout-before-headers timeout
     :timeout-after-headers body-timeout}))

(defn -main
  [& _]
  (let [{:keys [server executor base-url stream-ready stream-release
                timeout-release body-timeout-ready body-timeout-release]}
        (start-server)]
    (try
      (let [jdk (one-transport :jdk
                               (fn [url timeout-ms]
                                 (jdk-get (jdk-client) url timeout-ms))
                               base-url stream-ready stream-release
                               body-timeout-ready body-timeout-release)]
        ;; A fresh server is required because each streaming endpoint owns its
        ;; one pair of event latches.
        (.stop ^HttpServer server 0)
        (.shutdownNow ^java.util.concurrent.ExecutorService executor)
        (let [{server-2 :server executor-2 :executor base-url-2 :base-url
               stream-ready-2 :stream-ready stream-release-2 :stream-release
               timeout-release-2 :timeout-release
               body-timeout-ready-2 :body-timeout-ready
               body-timeout-release-2 :body-timeout-release}
              (start-server)]
          (try
            (prn {:jdk jdk
                  :hato (one-transport :hato hato-get base-url-2
                                       stream-ready-2 stream-release-2
                                       body-timeout-ready-2
                                       body-timeout-release-2)})
            (finally
              (.countDown ^CountDownLatch timeout-release-2)
              (.countDown ^CountDownLatch body-timeout-release-2)
              (.stop ^HttpServer server-2 0)
              (.shutdownNow
               ^java.util.concurrent.ExecutorService executor-2)))))
      (finally
        (.countDown ^CountDownLatch timeout-release)
        (.countDown ^CountDownLatch body-timeout-release)
        (.stop ^HttpServer server 0)
        (.shutdownNow ^java.util.concurrent.ExecutorService executor)))))

(apply -main *command-line-args*)
