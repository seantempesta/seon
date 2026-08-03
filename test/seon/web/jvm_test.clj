(ns seon.web.jvm-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as datahike]
            [seon.blob :as blob]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.fs :as filesystem]
            [seon.test-support :as support]
            [seon.web.jvm]
            [seon.web.search])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io ByteArrayOutputStream]
           [java.net InetAddress InetSocketAddress ServerSocket]
           [java.nio.charset StandardCharsets]
           [java.util Arrays]
           [java.util.concurrent CountDownLatch Executors TimeUnit]))

(defn- handler-var
  [namespace symbol]
  (deref (ns-resolve namespace symbol)))

(defn- with-file-database
  [body]
  (let [root (io/file "tmp/my-web-test" (str (random-uuid)))
        opened (store/open-store! {:seon.store/dir (str root "/store")})]
    (try
      ((ns-resolve 'seon.test-support 'populate-database!)
       (:seon.store/connection opened))
      (registry/branch! {:seon.store/store opened
                         :seon.cluster.registry/from :db
                         :seon.store/branch :my-web-test})
      (let [connection (store/open-branch! opened :my-web-test)]
        (try
          (db/transact!
           connection
           [{:seon.config/cluster "default"
             :seon.config.eval.result/blob-threshold 8}])
          (body connection)
          (finally
            (datahike/release connection))))
      (finally
        (store/release-store! opened)
        (when (.exists root)
          (filesystem/delete-recursively! (str root) (str root)))))))

(defn- response!
  [^HttpExchange exchange status headers octets]
  (doseq [[name value] headers]
    (.add (.getResponseHeaders exchange) name value))
  (.sendResponseHeaders exchange status (alength ^bytes octets))
  (when (pos? (alength ^bytes octets))
    (with-open [output (.getResponseBody exchange)]
      (.write output ^bytes octets))))

(defn- http-handler
  [f]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (f exchange)
        (finally
          (.close ^HttpExchange exchange))))))

(defn- start-server
  []
  (let [timeout-release (CountDownLatch. 1)
        search-request (promise)
        oversized (byte-array (map unchecked-byte (range 32)))
        search-body
        (.getBytes
         (json/write-str
          {"searchParameters" {"q" "web capability evidence"
                               "type" "search"
                               "num" 3
                               "engine" "google"}
           "organic" [{"title" "First" "link" "https://one.example/"
                       "snippet" "One snippet" "position" 1}
                      {"title" "Second" "link" "https://two.example/"
                       "snippet" "Two snippet" "position" 2}
                      {"title" "Third" "link" "https://three.example/"
                       "snippet" "Three snippet" "position" 3}]
           "credits" 1})
         StandardCharsets/UTF_8)
        executor (Executors/newVirtualThreadPerTaskExecutor)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server executor)
    (.createContext
     server "/oversized"
     (http-handler
      #(response! % 200 {"Content-Type" "application/octet-stream"}
                  oversized)))
    (.createContext
     server "/redirect-0"
     (http-handler
      (fn [exchange]
        (.add (.getResponseHeaders exchange) "Location" "/redirect-1")
        (.sendResponseHeaders exchange 302 -1))))
    (.createContext
     server "/redirect-1"
     (http-handler
      (fn [exchange]
        (.add (.getResponseHeaders exchange) "Location" "/html")
        (.sendResponseHeaders exchange 301 -1))))
    (.createContext
     server "/html"
     (http-handler
      #(response!
        % 200 {"Content-Type" "text/html; charset=UTF-8"}
        (.getBytes
         "<html><head><title>Page title</title><style>hidden</style></head><body><h1>Hello</h1><script>hidden</script><p>world</p></body></html>"
         StandardCharsets/UTF_8))))
    (.createContext
     server "/redirect-loop"
     (http-handler
      (fn [exchange]
        (.add (.getResponseHeaders exchange) "Location" "/redirect-loop")
        (.sendResponseHeaders exchange 302 -1))))
    (.createContext
     server "/timeout"
     (http-handler
      (fn [exchange]
        (.await timeout-release 5 TimeUnit/SECONDS)
        (response! exchange 200 {} (.getBytes "late" StandardCharsets/UTF_8)))))
    (.createContext
     server "/search"
     (http-handler
      (fn [exchange]
        (deliver search-request
                 {:method (.getRequestMethod exchange)
                  :api-key (.getFirst (.getRequestHeaders exchange) "X-API-KEY")
                  :content-type
                  (.getFirst (.getRequestHeaders exchange) "Content-Type")
                  :body (slurp (.getRequestBody exchange))})
        (response! exchange 200 {"Content-Type" "application/json"}
                   search-body))))
    (.start server)
    {:server server
     :executor executor
     :base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)))
     :oversized oversized
     :search-body search-body
     :search-request search-request
     :timeout-release timeout-release}))

(defn- with-server
  [body]
  (let [{:keys [server executor timeout-release] :as running}
        (start-server)]
    (try
      (body running)
      (finally
        (.countDown ^CountDownLatch timeout-release)
        (.stop ^HttpServer server 0)
        (.shutdownNow ^java.util.concurrent.ExecutorService executor)))))

(defn- public-addresses
  [_hostname]
  [(InetAddress/getByAddress
    (byte-array (map unchecked-byte [93 184 216 34])))])

(defn- config
  [base-url]
  {:seon.config.web/timeout-ms 1000
   :seon.config.web/max-response-bytes 64
   :seon.config.web/max-inline-bytes 8
   :seon.config.web/max-redirects 3
   :seon.config.web/max-search-results 5
   :seon.config.web/search-endpoint (str base-url "/search")
   :seon.config.web/search-api-key-variable "SERPER_API_KEY"
   :seon.config.web/search-result-projection
   'seon.web.search/organic-results})

(defn- exact-blob
  [connection digest size]
  (let [output (ByteArrayOutputStream.)]
    (loop [offset 0]
      (when (< offset size)
        (let [octets (blob/read-chunk connection digest offset 7)]
          (.write output ^bytes octets)
          (recur (+ offset (alength ^bytes octets))))))
    (.toByteArray output)))

(defn- fetch
  [connection request effective]
  (binding [db/*conn* connection]
    (with-redefs-fn
      {(ns-resolve 'seon.web.jvm 'resolve-addresses) public-addresses}
      #((handler-var 'seon.web.jvm 'fetch) request effective))))

(deftest oversized-bodies-spill-byte-exactly-through-the-blob-tier
  (with-file-database
    (fn [connection]
      (with-server
        (fn [{:keys [base-url oversized]}]
          (let [result (fetch connection
                              {:my.web/url (str base-url "/oversized")}
                              (config base-url))
                body (:my.web/body result)
                actual (exact-blob connection (:my.web.body/blob body)
                                   (:my.web.body/bytes body))]
            (is (= 200 (:my.web/status result)))
            (is (= (alength ^bytes oversized) (:my.web.body/bytes body)))
            (is (string? (:my.web.body/digest body)))
            (is (= (:my.web.body/digest body) (:my.web.body/blob body)))
            (is (Arrays/equals ^bytes oversized ^bytes actual))
            (is (not (contains? body :my.web.body/octet-values)))))))))

(deftest redirects-are-bounded-recorded-and-extracted-from-raw-bytes
  (with-file-database
    (fn [connection]
      (with-server
        (fn [{:keys [base-url]}]
          (let [effective (config base-url)
                result (fetch connection
                              {:my.web/url (str base-url "/redirect-0")}
                              effective)
                chain (:my.web/redirects result)
                loop-result (fetch connection
                                   {:my.web/url
                                    (str base-url "/redirect-loop")}
                                   effective)]
            (is (= [302 301] (mapv :my.web.redirect/status chain)))
            (is (= (str base-url "/html") (:my.web/final-url result)))
            (is (= "text/html; charset=UTF-8" (:my.web/content-type result)))
            (is (= "Page title"
                   (get-in result [:my.web/extraction
                                   :my.web.extract/title])))
            (is (= "Page title Hello world"
                   (get-in result [:my.web/extraction
                                   :my.web.extract/text])))
            (is (true? (:my.web/redirect-loop loop-result)))
            (is (string? (:seon.error/message loop-result)))))))))

(deftest timeout-and-dead-host-fail-flat
  (with-file-database
    (fn [connection]
      (with-server
        (fn [{:keys [base-url]}]
          (let [started (System/nanoTime)
                timeout (fetch connection
                               {:my.web/url (str base-url "/timeout")}
                               (assoc (config base-url)
                                      :seon.config.web/timeout-ms 100))
                elapsed-ms (long (/ (- (System/nanoTime) started) 1000000))
                dead-port (with-open [socket (ServerSocket. 0)]
                            (.getLocalPort socket))
                dead (fetch connection
                            {:my.web/url
                             (str "http://127.0.0.1:" dead-port "/dead")}
                            (config base-url))]
            (is (true? (:my.web/timeout timeout)))
            (is (< elapsed-ms 1000))
            (is (string? (:seon.error/message timeout)))
            (is (true? (:my.web/transport-failed dead)))
            (is (string? (:seon.error/message dead)))))))))

(deftest search-projects-the-live-serper-shape-and-blobs-the-raw-response
  (with-file-database
    (fn [connection]
      (with-server
        (fn [{:keys [base-url search-body search-request]}]
          (let [effective (config base-url)
                result
                (binding [db/*conn* connection]
                  (with-redefs-fn
                    {(ns-resolve 'seon.web.jvm 'resolve-addresses)
                     public-addresses
                     (ns-resolve 'seon.web.jvm 'credential)
                     (constantly "test-serper-key")}
                    #((handler-var 'seon.web.jvm 'search)
                      {:my.web/query "web capability evidence"
                       :my.web/max-results 2}
                      effective)))
                observed (deref search-request 1000 ::not-observed)
                raw (exact-blob connection (:my.web/raw-response result)
                                (:my.web/raw-response-bytes result))]
            (is (= {:method "POST"
                    :api-key "test-serper-key"
                    :content-type "application/json"
                    :body (json/write-str
                           {"q" "web capability evidence" "num" 2})}
                   observed))
            (is (= 1 (:my.web/credits result)))
            (is (= 3 (:my.web/result-count result)))
            (is (= 2 (:my.web/returned result)))
            (is (= [{:my.web.result/title "First"
                     :my.web.result/link "https://one.example/"
                     :my.web.result/snippet "One snippet"
                     :my.web.result/position 1}
                    {:my.web.result/title "Second"
                     :my.web.result/link "https://two.example/"
                     :my.web.result/snippet "Two snippet"
                     :my.web.result/position 2}]
                   (:my.web/results result)))
            (is (Arrays/equals ^bytes search-body ^bytes raw))))))))
