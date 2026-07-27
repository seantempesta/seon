(ns seon.web.server
  "Additive JVM web-render process for database-derived HTTP/SSE views."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as http-kit]
            [seon.config.resolve :as config.resolve]
            [seon.content-hash :as content-hash]
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
        reactive-policy
        (merge config.resolve/default-reactive-policy
               (select-keys singleton
                            (keys config.resolve/default-reactive-policy)))
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
      (assoc selected ::reactive-policy reactive-policy))))

(defn- short-digest [digest]
  (if (and (string? digest) (<= 8 (count digest)))
    (subs digest 0 8)
    (pr-str digest)))

(defn- base-initialization-fingerprint!
  [path expected-digest]
  (let [text (slurp path)
        actual-digest (content-hash/sha-256 text)
        artifact (edn/read-string text)]
    (when-not (= expected-digest actual-digest)
      (throw
       (ex-info "The admitted base-projection artifact digest changed."
                {:seon.dev.artifact/base-projection-path path
                 :seon.dev.artifact/expected-digest expected-digest
                 :seon.dev.artifact/actual-digest actual-digest})))
    (:seon.db.initialization/fingerprint artifact)))

(defn- bootstrap-configuration!
  [{::keys [writer-socket-path database-name]
    :seon.startgate/keys [release-digest config-manifest-digest
                          base-projection-path base-projection-digest]}]
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
                  ::protocol/entity-id
                  config.resolve/cluster-config-lookup-ref})}))
            identity
            (when (::protocol/success? acquired)
              (uds/call!
               {::uds/session session
                ::uds/message
                (protocol/pull-request
                 {::protocol/request-id (str (random-uuid))
                  :seon.db/db (:seon.db/db acquired)
                  ::protocol/selector '[*]
                  ::protocol/entity-id
                  [:seon.db.initialization/id "database"]})}))
            actual
            (when (and identity (::protocol/success? identity))
              (select-keys
               (db/decode-edn-values (::protocol/result identity))
               [:seon.db.initialization/fingerprint
                :seon.db.initialization/status
                :seon.db.initialization/release-digest
                :seon.db.initialization/config-manifest-digest]))
            expected
            {:seon.db.initialization/fingerprint
             (base-initialization-fingerprint!
              base-projection-path base-projection-digest)
             :seon.db.initialization/release-digest release-digest
             :seon.db.initialization/config-manifest-digest
             config-manifest-digest}]
        (when-not
         (and (= :seon.db.initialization.status/complete
                 (:seon.db.initialization/status actual))
              (= expected (select-keys actual (keys expected))))
          (throw
           (ex-info
            (str "this cluster was applied at release "
                 (short-digest
                  (:seon.db.initialization/release-digest actual))
                 "/config "
                 (short-digest
                  (:seon.db.initialization/config-manifest-digest actual))
                 "; this artifact is "
                 (short-digest release-digest)
                 "/config "
                 (short-digest config-manifest-digest)
                 "; run `bin/seon cluster apply " database-name "`.")
            {:seon.startgate/cluster database-name
             :seon.startgate/applied-identity actual
             :seon.startgate/launch-identity expected
             :seon.startgate/remedy
             (str "bin/seon cluster apply " database-name)})))
        (if (and pulled (::protocol/success? pulled))
          (configuration
           (db/decode-edn-values (::protocol/result pulled)))
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

(defn- runtime-root []
  (some-> (System/getenv "SEON_RUNTIME_ROOT") str/trim not-empty))

(defn- resource-source
  "Resolve one shipped resource from the immutable runtime before classpath."
  [path]
  (or
   (when-let [root (runtime-root)]
     (let [file (io/file root "resources" path)]
       (when (.isFile file) file)))
   (io/resource path)))

(defn- resource-response
  [path content-type]
  (if-let [resource (resource-source path)]
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
    :as request}]
  (let [configuration
        (or configuration
            (bootstrap-configuration! request))]
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
                   ::feed/reactive-policy (::reactive-policy configuration)
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
