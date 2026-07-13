(ns seon.db.server
  "Assemble and run the authoritative database process."
  (:require [clojure.core.server :as core-server]
            [clojure.java.io :as io]
            ;; Register the optional Proximum index before any database opens.
            [datahike.index.secondary.proximum]
            [seon.db.writer :as writer]
            [seon.embed :as embed]
            [seon.schema :as schema])
  (:gen-class))

(schema/register! ::arguments [:sequential :string])
(schema/register! ::database-name :seon.db.writer/database-name)
(schema/register! ::backend :seon.db.writer/backend)
(schema/register! ::database-path :seon.db.writer/database-path)
(schema/register! ::request-socket-path :seon.db.writer/request-socket-path)
(schema/register! ::publish-socket-path :seon.db.writer/publish-socket-path)
(schema/register! ::repl-port [:int {:min 0 :max 65535}])
(schema/register! ::repl-port-file [:string {:min 1}])
(schema/register!
 ::options
 [:map
  [::database-name ::database-name]
  [::backend ::backend]
  [::database-path {:optional true} ::database-path]
  [::request-socket-path ::request-socket-path]
  [::publish-socket-path ::publish-socket-path]
  [::repl-port {:optional true} ::repl-port]
  [::repl-port-file {:optional true} ::repl-port-file]])
(schema/register! ::repl-server :any)
(schema/register!
 ::server
 [:map
  [::writer-server :seon.db.writer/server]
  [::repl-server {:optional true} ::repl-server]
  [::repl-port-file {:optional true} ::repl-port-file]])
(schema/register! ::stopped? :boolean)
(schema/register! ::stop-response [:map [::stopped? ::stopped?]])

(defn- parse-arguments
  [arguments]
  (loop [options
         {::database-name "default"
          ::backend :file
          ::request-socket-path "tmp/seon-client-runtime-req.sock"
          ::publish-socket-path "tmp/seon-client-runtime-pub.sock"}
         remaining arguments]
    (case (first remaining)
      "--backend"
      (recur (assoc options ::backend (keyword (second remaining)))
             (drop 2 remaining))

      "--db-name"
      (recur (assoc options ::database-name (second remaining))
             (drop 2 remaining))

      "--path"
      (recur (assoc options ::database-path (second remaining))
             (drop 2 remaining))

      "--req-sock"
      (recur (assoc options ::request-socket-path (second remaining))
             (drop 2 remaining))

      "--pub-sock"
      (recur (assoc options ::publish-socket-path (second remaining))
             (drop 2 remaining))

      "--repl-port"
      (recur (assoc options ::repl-port
                    (Long/parseLong (second remaining)))
             (drop 2 remaining))

      "--repl-port-file"
      (recur (assoc options ::repl-port-file (second remaining))
             (drop 2 remaining))

      nil options

      (throw
       (ex-info "Unknown database-server argument."
                {::argument (first remaining)})))))

(defn writer-runtime
  "Construct the immutable database-writer dependencies once."
  {:malli/schema [:=> [:cat] :seon.db.writer/dependencies]}
  []
  (let [embeddables (embed/default-embeddables)]
    {::writer/database-initializer
     (fn [connection _database-name]
       (embed/initialize-database! embeddables connection))
     ::writer/transaction-transform
     (partial embed/augment-tx-with-embeddings embeddables)
     ::writer/knn-search embed/knn-search}))

(defn- resolve-repl-port-file
  [{::keys [database-name repl-port-file]}]
  (or repl-port-file
      (System/getenv "SEON_WRITER_REPL_PORT_FILE")
      (str "tmp/seon-writer-repl-port-" database-name)))

(defn- start-repl-server!
  [port port-file]
  (let [file (io/file port-file)
        parent (.getParentFile file)]
    (when parent (.mkdirs parent))
    (let [server
          (core-server/start-server
           {:name "seon-database-repl"
            :address "127.0.0.1"
            :port port
            :accept 'clojure.core.server/repl})]
      (spit file (str (.getLocalPort ^java.net.ServerSocket server)))
      (.deleteOnExit file)
      server)))

(defn start!
  "Start one fully composed database server."
  {:malli/schema [:=> [:catn [::arguments ::arguments]] ::server]}
  [arguments]
  (println "[database] booting pid="
           (.pid (java.lang.ProcessHandle/current)))
  (let [{::keys [database-name backend database-path request-socket-path
                 publish-socket-path repl-port]
         :as options}
        (parse-arguments arguments)
        dependencies (writer-runtime)
        writer-server
        (writer/start!
         (cond->
          {::writer/dependencies dependencies
           ::writer/database-name database-name
           ::writer/backend backend
           ::writer/request-socket-path request-socket-path
           ::writer/publish-socket-path publish-socket-path}
           database-path
           (assoc ::writer/database-path database-path)))
        resolved-repl-port-file (when repl-port
                                  (resolve-repl-port-file options))
        repl-server
        (try
          (when repl-port
            (let [server (start-repl-server! repl-port
                                             resolved-repl-port-file)]
              (println "[database] dev REPL:"
                       (str "127.0.0.1:"
                            (.getLocalPort ^java.net.ServerSocket server))
                       "port-file:" resolved-repl-port-file)
              server))
          (catch Throwable throwable
            (writer/stop! writer-server)
            (throw throwable)))]
    (println "[database] request socket:" request-socket-path)
    (println "[database] publish socket:" publish-socket-path)
    (println "[database] ready")
    (cond-> {::writer-server writer-server}
      repl-server
      (assoc ::repl-server repl-server
             ::repl-port-file resolved-repl-port-file))))

(defn stop!
  "Stop one database server and release its live resources."
  {:malli/schema [:=> [:catn [::server ::server]] ::stop-response]}
  [server]
  (when-let [repl-server (::repl-server server)]
    (try (.close ^java.net.ServerSocket repl-server)
         (catch Throwable _)))
  (when-let [port-file (::repl-port-file server)]
    (try (.delete (io/file port-file)) (catch Throwable _)))
  (writer/stop! (::writer-server server))
  {::stopped? true})

(defn -main
  "Run the database process or its optional embedding preflight."
  {:malli/schema [:=> [:cat [:* :string]] :any]}
  [& arguments]
  (if (some #{"--preflight"} arguments)
    (let [code ((requiring-resolve 'seon.embed.preflight/run-preflight!))]
      (flush)
      (System/exit code))
    (let [server (start! arguments)
          shutdown-hook
          (Thread.
           ^Runnable
           (fn []
             (try (stop! server) (catch Throwable _)))
           "seon-database-shutdown")]
      (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
      (.. (Thread/currentThread) join))))
