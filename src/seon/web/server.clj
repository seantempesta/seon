(ns seon.web.server
  "Additive JVM web-render process for database-derived HTTP/SSE views."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as http-kit]
            [seon.config.resolve :as config.resolve]
            [seon.db :as db]
            [seon.db.host :as db.host]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.web.data :as data]
            [seon.web.feed :as feed])
  (:import [java.nio.file Files Path StandardCopyOption]
           [java.util.concurrent ExecutorService Executors]))

(def required-configuration-keys
  "Database config facts required before the web-render process can start."
  [::heartbeat-interval-ms
   ::mailbox-depth
   ::maximum-connections
   ::request-executor-size
   ::database-pool-size
   ::database-call-timeout-ms
   ::interest-reconnect-backoff-ms
   ::data-page-size
   ::maximum-request-body-bytes])

(def ^:private configuration-attributes
  {::heartbeat-interval-ms
   :seon.config.web-render/heartbeat-interval-ms
   ::mailbox-depth :seon.config.web-render/mailbox-depth
   ::maximum-connections :seon.config.web-render/maximum-connections
   ::request-executor-size :seon.config.web-render/request-executor-size
   ::database-pool-size :seon.config.web-render/database-pool-size
   ::database-call-timeout-ms
   :seon.config.web-render/database-call-timeout-ms
   ::interest-reconnect-backoff-ms
   :seon.config.web-render/interest-reconnect-backoff-ms
   ::data-page-size :seon.config.web-render/data-page-size
   ::maximum-request-body-bytes
   :seon.config.web-render/maximum-request-body-bytes})

(defn configuration
  "Project and validate web-render dials from the config singleton."
  [singleton]
  (let [facts (config.resolve/web-render-configuration singleton)
        selected
        (into {}
              (map (fn [[runtime-key attribute]]
                     [runtime-key (get facts attribute)]))
              configuration-attributes)
        missing (filterv #(not (pos-int? (get selected %)))
                         required-configuration-keys)]
    (if (seq missing)
      {:seon.error/message
       "The database configuration is missing required web-render dials."
       :seon.error/kind :configuration
       :seon.error/data {::missing-keys missing}}
      selected)))

(defn- bootstrap-configuration!
  [writer-socket-path database-name]
  (let [session (uds/open-session! writer-socket-path)]
    (try
      (let [acquired
            (uds/call!
             {::uds/session session
              ::uds/message
              (protocol/acquire-database-request
               {::protocol/request-id (str (random-uuid))
                ::protocol/database-name database-name
                ::protocol/database-advanced? false})})
            pulled
            (when (::protocol/success? acquired)
              (uds/call!
               {::uds/session session
                ::uds/message
                (protocol/pull-request
                 {::protocol/request-id (str (random-uuid))
                  :seon.db/db (:seon.db/db acquired)
                  ::protocol/selector '[*]
                  ::protocol/entity-id [:seon.config/id "cluster"]})}))]
        (if (and pulled (::protocol/success? pulled))
          (configuration (db/decode-edn-values (::protocol/result pulled)))
          {:seon.error/message
           "The web-render process could not acquire database configuration."
           :seon.error/kind :configuration
           :seon.error/data
           {::acquire-response acquired ::pull-response pulled}}))
      (finally
        (uds/close-session! session)))))

(defn- response
  [status content-type body]
  {:status status
   :headers {"Content-Type" content-type
             "Cache-Control" "no-store, no-cache, must-revalidate"}
   :body body})

(defn- resource-response
  [path content-type]
  (if-let [resource (io/resource path)]
    (response 200 content-type (io/input-stream resource))
    (response 404 "text/plain; charset=utf-8" "not found")))

(defn- compression
  [request]
  (let [enabled? (= "gzip" (System/getenv "SEON_FEED_COMPRESSION"))
        accepted (get-in request [:headers "accept-encoding"] "")]
    (if (and enabled? (str/includes? accepted "gzip")) :gzip :identity)))

(defn- data-feed-url
  [request view-id]
  (let [attribute (data/attribute request)]
    (str "/data/feed?view=" view-id
         (when attribute (str "&attr=" (subs (str attribute) 1))))))

(defn- handler
  [service leaf configuration]
  (fn [request]
    (binding [db/*leaf* leaf]
      (case [(:request-method request) (:uri request)]
        [:get "/_seon/ready"]
        (response 200 "text/plain; charset=utf-8" "ready")

        [:get "/data"]
        (let [view-id (str (random-uuid))]
          (response 200 "text/html; charset=utf-8"
                    (data/shell {::data/feed-url
                                 (data-feed-url request view-id)})))

        [:get "/data/feed"]
        (let [selected-attribute (data/attribute request)
              render-input {::data/selected-attribute selected-attribute
                            ::data/page-size (::data-page-size configuration)}
              selected-service
              (assoc-in service [::feed/configuration ::feed/compression]
                        (compression request))]
          (feed/open! selected-service request
                      [:seon.web.feed/data selected-attribute]
                      render-input))

        [:get "/js/datastar.js"]
        (resource-response "public/js/datastar.js"
                           "text/javascript; charset=utf-8")

        [:get "/css/output.css"]
        (resource-response "public/css/output.css"
                           "text/css; charset=utf-8")

        (response 404 "text/plain; charset=utf-8" "not found")))))

(defn- publish-port!
  [port-file port]
  (let [path (Path/of port-file (make-array String 0))
        parent (.getParent path)
        temporary (Path/of (str port-file ".tmp") (make-array String 0))]
    (when parent (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0)))
    (spit (str temporary) (str port "\n"))
    (Files/move temporary path
                (into-array StandardCopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING])))
  nil)

(declare stop!)

(defn start!
  "Start the additive JVM database browser and return its owned resources."
  [{::keys [writer-socket-path database-name port-file port configuration]
    :as _request}]
  (let [configuration
        (or configuration
            (bootstrap-configuration! writer-socket-path database-name))]
    (when (:seon.error/message configuration)
      (throw (ex-info (:seon.error/message configuration)
                      (:seon.error/data configuration))))
    (let [started (atom {::port-file port-file})]
      (try
        (let [writer
              (db.host/writer-session
               {::db.host/writer-socket-path writer-socket-path
                ::db.host/database-name database-name
                ::db.host/pool-size (::database-pool-size configuration)
                ::db.host/call-deadline-ms
                (::database-call-timeout-ms configuration)
                ::db.host/interest-call-timeout-ms
                (::database-call-timeout-ms configuration)
                ::db.host/interest-reconnect-backoff-ms
                (::interest-reconnect-backoff-ms configuration)})
              _ (swap! started assoc ::writer writer)
              leaf (db.host/leaf writer (constantly nil))
              render
              (fn [database render-input]
                (binding [db/*leaf* leaf]
                  (data/render (assoc render-input ::data/database database))))
              feed-service
              (binding [db/*leaf* leaf]
                (feed/start!
                 {::feed/writer writer
                  ::feed/render render
                  ::feed/configuration
                  {::feed/heartbeat-interval-ms
                   (::heartbeat-interval-ms configuration)
                   ::feed/mailbox-depth (::mailbox-depth configuration)
                   ::feed/maximum-connections
                   (::maximum-connections configuration)
                   ::feed/compression :identity}}))
              _ (swap! started assoc ::leaf leaf ::feed-service feed-service)
              request-executor
              (Executors/newFixedThreadPool
               (int (::request-executor-size configuration)))
              _ (swap! started assoc ::request-executor request-executor)
              http-server
              (http-kit/run-server
               (handler feed-service leaf configuration)
               {:ip "127.0.0.1"
                :port port
                :worker-pool request-executor
                :max-body (::maximum-request-body-bytes configuration)
                :legacy-return-value? false
                :legacy-unsafe-remote-addr? false})
              server
              (swap! started assoc ::http-server http-server)]
          (publish-port! port-file (http-kit/server-port http-server))
          server)
        (catch Throwable throwable
          (stop! @started)
          (throw throwable))))))

(defn stop!
  "Stop the web-render resources in dependency order."
  [{::keys [writer feed-service request-executor http-server port-file]}]
  (when http-server (http-kit/server-stop! http-server))
  (when feed-service (feed/stop! feed-service))
  (when request-executor (.shutdownNow ^ExecutorService request-executor))
  (when writer (db.host/close-session! writer))
  (when port-file (Files/deleteIfExists (Path/of port-file (make-array String 0))))
  nil)

(defn -main
  "Run the supervised JVM web-render process from one EDN request."
  [& [request-edn]]
  (let [server (start! (edn/read-string request-edn))]
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. #(stop! server) "seon-web-render-shutdown"))
    (http-kit/server-join (::http-server server))))
