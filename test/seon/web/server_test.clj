(ns seon.web.server-test
  "JVM `/data` shim and identity/gzip Datastar feed regressions."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.db.host :as db.host]
            [seon.db.protocol :as protocol]
            [seon.db.writer-test-support :as writer-test]
            [seon.db.writer :as writer]
            [seon.web.feed :as feed]
            [seon.web.server :as server])
  (:import [java.io BufferedReader File InputStream InputStreamReader]
           [java.net HttpURLConnection SocketTimeoutException URL]
           [java.nio.charset StandardCharsets]
           [java.util.zip GZIPInputStream]))

(defn- socket-path []
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory (str "web-server-" (random-uuid) ".sock")))))

(defn- port-file []
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory (str "web-server-" (random-uuid) ".port")))))

(defn- dependencies []
  {::writer/database-initializer (fn [_connection _database-name] nil)
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_db-value] [])
   ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
   ::writer/embedding-assertions (fn [_inputs] [])
   ::writer/revalidate-embedding-assertions (fn [_db-value _assertions] [])
   ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_db-value _vector _limit _entity-ids] [])})

(defn- wait-until!
  [timeout-ms predicate]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (predicate) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(defn- get-text
  [url]
  (let [connection ^HttpURLConnection (.openConnection (URL. url))]
    (.setRequestProperty connection "Accept-Encoding" "identity")
    (try
      [(.getResponseCode connection)
       (.getHeaderField connection "Content-Encoding")
       (slurp (.getInputStream connection))]
      (finally
        (.disconnect connection)))))

(defn- open-feed
  [url encoding]
  (let [connection ^HttpURLConnection (.openConnection (URL. url))]
    (.setRequestProperty connection "Accept-Encoding" encoding)
    (.setReadTimeout connection 5000)
    (let [status (.getResponseCode connection)
          content-encoding (.getHeaderField connection "Content-Encoding")
          input ^InputStream (.getInputStream connection)
          decoded (if (= "gzip" content-encoding)
                    (GZIPInputStream. input)
                    input)]
      {:connection connection
       :status status
       :content-encoding content-encoding
       :reader (BufferedReader.
                (InputStreamReader. decoded StandardCharsets/UTF_8))})))

(defn- read-event
  [^BufferedReader reader]
  (loop [lines []]
    (let [line (.readLine reader)]
      (cond
        (nil? line) lines
        (and (empty? line) (seq lines)) lines
        :else (recur (conj lines line))))))

(defn- read-events-through-basis
  [{:keys [^HttpURLConnection connection ^BufferedReader reader]} basis timeout-ms]
  (.setReadTimeout connection timeout-ms)
  (loop [events []]
    (let [event (try
                  (read-event reader)
                  (catch SocketTimeoutException _ ::timeout))]
      (cond
        (= ::timeout event) events
        (not (seq event)) events
        :else
        (let [events (conj events event)]
          (if (some #(str/includes? % (str "basis t=" basis)) event)
            events
            (recur events)))))))

(defn- close-feed!
  [{:keys [^BufferedReader reader ^HttpURLConnection connection]}]
  (try (.close reader) (catch Throwable _))
  (.disconnect connection))

(defn- transact!
  [writer-session database request-id transaction-data]
  (db.host/call!
   writer-session
   (protocol/transaction-request
    {::protocol/request-id request-id
     :seon.db/db database
     ::protocol/transaction-data transaction-data})))

(deftest shipped-static-assets-resolve-from-runtime-root
  (let [root (io/file "tmp" (str "web-runtime-" (random-uuid)))
        assets [["public/js/datastar.js" "export const ready = true;"]
                ["public/css/output.css" "#app-view { display: block; }"]]]
    (try
      (doseq [[path content] assets]
        (let [file (io/file root "resources" path)]
          (.mkdirs (.getParentFile file))
          (spit file content)))
      (with-redefs-fn
        {#'server/runtime-root (constantly (.getAbsolutePath root))}
        #(doseq [[path content] assets]
           (let [result ((deref #'server/resource-response)
                         path "text/plain; charset=utf-8")]
             (is (= 200 (:status result)))
             (is (= content (slurp (:body result)))))))
      (finally
        (doseq [file (reverse (file-seq root))]
          (io/delete-file file true))))))

(deftest data-shim-and-identity-feed-deliver-a-second-morph
  (let [database-name (str "web-server-" (random-uuid))
        request-path (socket-path)
        port-path (port-file)
        writer-server
        (writer-test/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/selected-processors 3
          ::writer/request-socket-path request-path})
        web-server
        (server/start!
         {::server/writer-socket-path request-path
          ::server/database-name database-name
          ::server/port-file port-path
          ::server/port 0
          ::server/configuration
          {::server/heartbeat-interval-ms 60000
           ::server/mailbox-depth 1
           ::server/maximum-connections 8
           ::server/request-executor-size 4
           ::server/database-pool-size 4
           ::server/database-call-timeout-ms 3000
           ::server/interest-reconnect-backoff-ms 10
           ::server/data-page-size 50
           ::server/maximum-request-body-bytes 1048576
           ::server/reactive-policy
           {:seon.config/reactive-settle-ms 200
            :seon.config/reactive-structural-settle-ms 200
            :seon.config/reactive-max-latency-ms 1000}}})
        port (org.httpkit.server/server-port (::server/http-server web-server))
        base-url (str "http://127.0.0.1:" port)
        feed-service (::server/feed-service web-server)]
    (try
      (let [[status content-encoding body] (get-text (str base-url "/data"))]
        (is (= 200 status))
        (is (nil? content-encoding))
        (is (str/includes? body "id=\"app-view\""))
        (is (str/includes? body "/data/feed?view=")))
      (let [identity (open-feed (str base-url "/data/feed?view=identity")
                                "identity")]
        (try
          (is (= 200 (:status identity)))
          (is (nil? (:content-encoding identity)))
          (let [initial-event (read-event (:reader identity))]
            (is (some #(str/includes? % "datastar-patch-elements")
                      initial-event))
            (is (some #(str/includes? % "id=\"app-view\"")
                      initial-event)))
          (is (wait-until!
               3000
               #(= 1 (::feed/connection-count (feed/snapshot feed-service)))))
          (let [database (db.host/resolve-db! (::server/writer web-server))
                report
                (transact!
                 (::server/writer web-server) database "web-server/schema"
                 [{:db/ident :web-server/value
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one}])
                identity-event (read-event (:reader identity))]
            (is (::protocol/success? report) (pr-str report))
            (is (some #(str/includes? % "datastar-patch-elements")
                      identity-event)))
          (let [database (db.host/resolve-db! (::server/writer web-server))
                reports
                (mapv
                 (fn [index]
                   (transact!
                    (::server/writer web-server) database
                    (str "web-server/burst-" index)
                    [{:web-server/value (str "value-" index)}]))
                 (range 20))
                latest-t (get-in (last reports) [:db-after :t])
                events (read-events-through-basis identity latest-t 3000)]
            (is (every? ::protocol/success? reports) (pr-str reports))
            (is (some #(str/includes? % (str "basis t=" latest-t))
                      events)
                "the delivered morph carries the newest database value"))
          (finally
            (close-feed! identity))))
      (is (wait-until!
           3000
           #(zero? (::feed/connection-count (feed/snapshot feed-service)))))
      (finally
        (server/stop! web-server)
        (writer/stop! writer-server)
        (.delete (File. request-path))
        (.delete (File. port-path))))))
