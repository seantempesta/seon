(ns seon.db.server
  "Assemble and run the authoritative database process."
  (:require [clojure.core.server :as core-server]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            ;; Register the optional Proximum index before any database opens.
            [datahike.index.secondary.proximum]
            [seon.db.protocol :as protocol]
            [seon.db.restore-admin :as restore-admin]
            [seon.db.writer :as writer]
            [seon.dev.restore :as restore]
            [seon.embed :as embed]
            [seon.schema :as schema])
  (:import [java.io BufferedWriter FileInputStream FileOutputStream
            OutputStreamWriter]
           [java.nio.charset StandardCharsets]
           [java.nio.channels FileChannel]
           [java.nio.file CopyOption Files OpenOption StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(schema/register! ::arguments [:sequential :string])
(schema/register! ::database-name :seon.db.writer/database-name)
(schema/register! ::backend :seon.db.writer/backend)
(schema/register! ::database-path :seon.db.writer/database-path)
(schema/register! ::request-socket-path :seon.db.writer/request-socket-path)
(schema/register! ::publish-socket-path :seon.db.writer/publish-socket-path)
(schema/register! ::repl-port [:int {:min 0 :max 65535}])
(schema/register! ::repl-port-file [:string {:min 1}])
(schema/register! ::intent-path [:string {:min 1}])
(schema/register! ::result-path [:string {:min 1}])
(schema/register!
 ::admin-options
 [:map {:closed true}
  [::intent-path ::intent-path]
  [::result-path ::result-path]])
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
(schema/register! ::release-results :seon.db.writer/release-results)
(schema/register! ::stop-response :seon.db.protocol/server-stop-response)

(def ^:private process-generation-environment "SEON_PROCESS_GENERATION")
(def ^:private application-result-path-environment
  "SEON_APPLICATION_RESULT_PATH")
(def ^:private stop-error-limit 4096)
(def ^:private admin-input-limit (* 1024 1024))

(defn- terminal-configuration
  [environment]
  (let [generation (not-empty (get environment process-generation-environment))
        result-path (not-empty (get environment
                                    application-result-path-environment))]
    (cond
      (and (nil? generation) (nil? result-path)) nil
      (or (nil? generation) (nil? result-path))
      (throw
       (ex-info "Writer terminal-result environment is incomplete."
                {:seon.error/kind :configuration
                 ::missing-environment
                 (cond-> []
                   (nil? generation) (conj process-generation-environment)
                   (nil? result-path)
                   (conj application-result-path-environment))}))
      (not (schema/valid-candidate-value?
            :seon.db.terminal/generation generation))
      (throw
       (ex-info "Writer process generation is not a canonical UUID string."
                {:seon.error/kind :configuration
                 ::environment process-generation-environment
                 ::value generation}))
      :else
      {::generation generation ::result-path result-path})))

(defn- bounded-stop-error
  [throwable]
  (let [message (.toString ^Throwable throwable)]
    (subs message 0 (min stop-error-limit (count message)))))

(defn- completed-terminal-result
  [generation stop-response]
  {:seon.db.terminal/generation generation
   :seon.db.terminal/process protocol/writer-process
   :seon.db.terminal/completed? true
   :seon.db.terminal/stop-response stop-response})

(defn- failed-terminal-result
  [generation throwable]
  {:seon.db.terminal/generation generation
   :seon.db.terminal/process protocol/writer-process
   :seon.db.terminal/completed? false
   :seon.db.terminal/stop-error (bounded-stop-error throwable)})

(defn- atomic-write-edn!
  [result-path value]
  (let [target (.toAbsolutePath (.toPath (io/file result-path)))
        parent (.getParent target)
        temp (Files/createTempFile
              parent
              (str (.getFileName target) ".")
              ".tmp"
              (make-array FileAttribute 0))]
    (try
      (with-open [stream (FileOutputStream. (.toFile temp))
                  writer (BufferedWriter.
                          (OutputStreamWriter.
                           stream StandardCharsets/UTF_8))]
        (.write writer (str (pr-str value) "\n"))
        (.flush writer)
        (.sync (.getFD stream)))
      (Files/move temp target
                  (into-array CopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))
      (with-open [channel
                  (FileChannel/open parent (make-array OpenOption 0))]
        (.force channel true))
      value
      (finally
        (Files/deleteIfExists temp)))))

(defn- terminal-publisher
  [{::keys [result-path]}]
  (let [claimed? (atom false)]
    (fn [result]
      (when (compare-and-set! claimed? false true)
        (when-not (protocol/valid-writer-terminal-result? result)
          (throw
           (ex-info "Refusing to publish an invalid writer terminal result."
                    {::terminal-result result})))
        (atomic-write-edn! result-path result)))))

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

(defn- admin-configuration [arguments]
  (when (some #{"--restore-admin-intent" "--restore-admin-result"} arguments)
    (let [options
          (loop [result {} remaining arguments]
            (case (first remaining)
              "--restore-admin-intent"
              (recur (assoc result ::intent-path (second remaining))
                     (drop 2 remaining))

              "--restore-admin-result"
              (recur (assoc result ::result-path (second remaining))
                     (drop 2 remaining))

              nil result

              (throw
               (ex-info "Unknown restore-admin argument."
                        {::argument (first remaining)}))))]
      (when-not (and (= 4 (count arguments))
                     (not-empty (::intent-path options))
                     (not-empty (::result-path options)))
        (throw
         (ex-info "Restore administration requires exact intent and result paths."
                  {::arguments arguments})))
      options)))

(defn- read-bounded-edn [path]
  (with-open [stream (FileInputStream. (io/file path))]
    (let [bytes (.readNBytes stream (inc admin-input-limit))
          byte-count (alength bytes)]
      (when (> byte-count admin-input-limit)
        (throw
         (ex-info "Restore admin intent exceeds the bounded input size."
                  {::byte-count byte-count
                   ::byte-limit admin-input-limit})))
      (edn/read-string (String. bytes StandardCharsets/UTF_8)))))

(defn- invalid-admin-result [throwable]
  {::restore-admin/error-kind protocol/protocol-error
   ::restore-admin/error (bounded-stop-error throwable)
   ::restore-admin/force-invoked? false
   ::restore-admin/connection-state
   :seon.db.restore-admin.connection/not-opened})

(defn- unknown-admin-result [intent throwable]
  (merge
   (restore-admin/result-base intent)
   {::restore-admin/error-kind protocol/internal-error
    ::restore-admin/error (bounded-stop-error throwable)
    ::restore-admin/effect-state :seon.db.restore-admin.effect/unknown
    ::restore-admin/connection-state
    :seon.db.restore-admin.connection/cleanup-unproved}))

(defn run-restore-admin!
  "Consume one retained intent and atomically publish one closed admin result."
  {:malli/schema [:=> [:cat ::admin-options] ::restore-admin/result]}
  [{::keys [intent-path result-path]}]
  (let [parsed
        (try
          {::intent
           (restore/validate-intent (read-bounded-edn intent-path))}
          (catch Throwable throwable
            {::result (invalid-admin-result throwable)}))
        intent (::intent parsed)
        result
        (or (::result parsed)
            (try
              (let [candidate
                    (writer/admin-restore!
                     {::restore-admin/intent intent})]
                (if (restore-admin/valid-result? candidate)
                  candidate
                  (unknown-admin-result
                   intent
                   (ex-info "Writer returned an invalid restore-admin result."
                            {::explanation
                             (restore-admin/explain-result candidate)}))))
              (catch Throwable throwable
                (unknown-admin-result intent throwable))))
        result
        (if (restore-admin/valid-result? result)
          result
          (throw
           (ex-info "Restore-admin result construction violated its contract."
                    {::explanation (restore-admin/explain-result result)})))]
    (atomic-write-edn! result-path result)
    result))

(defn writer-runtime
  "Construct the immutable database-writer dependencies once."
  {:malli/schema [:=> [:cat] :seon.db.writer/dependencies]}
  []
  (let [embeddables (embed/default-embeddables)]
    {::writer/database-initializer
     (fn [connection _database-name]
       (embed/initialize-database! embeddables connection))
     ::writer/embedding-enabled? (embed/embed-feature-enabled?)
     ::writer/embedding-entity-ids
     (fn [db-value]
       (:seon.embed/eids
        (embed/embedding-entity-ids
         {:seon.embed/embeddables embeddables
          :seon.embed/db-value db-value})))
     ::writer/embedding-inputs-for-eids
     (fn [db-value entity-ids]
       (if (embed/embed-feature-enabled?)
         (:seon.embed/inputs
          (embed/embedding-inputs-for-eids
           {:seon.embed/embeddables embeddables
            :seon.embed/db-value db-value
            :seon.embed/eids entity-ids}))
         []))
     ::writer/embedding-assertions
     (fn [inputs]
       (:seon.embed/assertions
        (embed/embedding-assertions {:seon.embed/inputs inputs})))
     ::writer/revalidate-embedding-assertions
     (fn [db-value assertions]
       (:seon.embed/assertions
        (embed/revalidate-embedding-assertions
         {:seon.embed/embeddables embeddables
          :seon.embed/db-value db-value
          :seon.embed/assertions assertions})))
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
            :accept 'clojure.core.server/io-prepl})]
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
  "Stop one database server and surface every database release."
  {:malli/schema [:=> [:catn [::server ::server]] ::stop-response]}
  [server]
  (when-let [repl-server (::repl-server server)]
    (try (.close ^java.net.ServerSocket repl-server)
         (catch Throwable _)))
  (when-let [port-file (::repl-port-file server)]
    (try (.delete (io/file port-file)) (catch Throwable _)))
  (let [result (writer/stop! (::writer-server server))
        embedding (embed/stop! {})]
    {::stopped? (and (::writer/stopped? result)
                     (:seon.embed/stopped? embedding))
     ::release-results (::writer/release-results result)}))

(defn- run-shutdown!
  [server {::keys [generation] :as configuration}]
  (if-not configuration
    (stop! server)
    (let [result (try
                   (completed-terminal-result generation (stop! server))
                   (catch Throwable throwable
                     (failed-terminal-result generation throwable)))]
      ((terminal-publisher configuration) result)
      result)))

(defn -main
  "Run the database process or its optional embedding preflight."
  {:malli/schema [:=> [:cat [:* :string]] :any]}
  [& arguments]
  (if-let [admin (admin-configuration arguments)]
    (let [result (run-restore-admin! admin)]
      (flush)
      (System/exit (if (restore-admin/success-result? result) 0 1)))
    (if (some #{"--preflight"} arguments)
    (let [code ((requiring-resolve 'seon.embed.preflight/run-preflight!))]
      (flush)
      (System/exit code))
    (let [terminal-config (terminal-configuration (System/getenv))
          server (start! arguments)
          shutdown-hook
          (Thread.
           ^Runnable
           (fn []
             (try
               (let [result (run-shutdown! server terminal-config)
                     stop-response
                     (if terminal-config
                       (:seon.db.terminal/stop-response result)
                       result)]
                 (cond
                   (and terminal-config
                        (false? (:seon.db.terminal/completed? result)))
                   (binding [*out* *err*]
                     (println "[database] shutdown failed:"
                              (:seon.db.terminal/stop-error result)))

                   (not (::stopped? stop-response))
                   (binding [*out* *err*]
                     (println "[database] shutdown incomplete:"
                              (pr-str stop-response)))))
               (catch Throwable throwable
                 (binding [*out* *err*]
                   (println "[database] shutdown failed:"
                            (.toString throwable))))))
           "seon-database-shutdown")]
      (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
      (.. (Thread/currentThread) join)))))
