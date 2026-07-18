(ns seon.execution-process-test
  "Real Bun-child proof for one database-wide current program."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [datahike.api :as d]
   [seon.db.protocol :as protocol]
   [seon.db.registry :as registry]
   [seon.db.writer :as writer])
  (:import
   [java.io File]
   [java.nio.file Files Path]
   [java.security MessageDigest]
   [java.util HexFormat]
   [java.util.concurrent TimeUnit]))

(def execution-artifact "out/execution/main.js")
(def driver-artifact "out/execution-integration/client.js")

(def schema-forms
  {:seon.db/lookup-ref-value "[:or :string :uuid :keyword :int]"
   :seon.db/ref
   "[:or :int :string [:tuple :keyword :seon.db/lookup-ref-value]]"
   :seon.schema/key "[:keyword {:seon.db/identity true}]"
   :seon.schema/form ":string"
   :seon.agent/id "[:string {:seon.db/identity true}]"
   :seon.db/user ":seon.db/ref"
   :seon.db/process ":seon.db/ref"
   :seon.db.id/generator
   "[:enum :seon.db.id.generator/human-readable :seon.db.id.generator/compact]"
   :seon.db.process/id
   "[:and {:seon.db/identity true} [:enum :seon.db.process/boot :seon.db.process/config :seon.db.process/repl]]"
   :seon.user/id "[:string {:seon.db/identity true}]"
   :seon.ns/name "[:keyword {:seon.db/identity true}]"
   :seon.ns/source ":string"
   :seon.fn/sym "[:string {:seon.db/identity true}]"
   :seon.fn/ns ":seon.db/ref"
   :seon.fn/source ":string"
   :seon.execution-proof/user
   "[:map {:seon.db/entity true} [:seon.user/id :seon.user/id]]"
   :seon.execution-proof/agent
   "[:map {:seon.db/entity true} [:seon.agent/id :seon.agent/id]]"
   :seon.execution-proof/namespace
   "[:map {:seon.db/entity true} [:seon.ns/name :seon.ns/name] [:seon.ns/source :seon.ns/source]]"
   :seon.execution-proof/function
   "[:map {:seon.db/entity true} [:seon.fn/sym :seon.fn/sym] [:seon.fn/ns :seon.fn/ns] [:seon.fn/source :seon.fn/source]]"})

(def old-current-source
  (str "(defn current []\n"
       "  {:seon.execution-proof/agent (db/current-agent-id)\n"
       "   :seon.execution-proof/pid (.-pid js/process)\n"
       "   :seon.execution-proof/value :before\n"
       "   :seon.execution-proof/removed (removed)})"))

(def authored-program
  [{:seon.ns/name :my.execution-proof
    :seon.ns/source
    "(ns my.execution-proof (:require [seon.db :as db] [seon.eval :as eval] [seon.render :as render]))"}
   {:seon.fn/sym "my.execution-proof/current"
    :seon.fn/ns [:seon.ns/name :my.execution-proof]
    :seon.fn/source old-current-source}
   {:seon.fn/sym "my.execution-proof/removed"
    :seon.fn/ns [:seon.ns/name :my.execution-proof]
    :seon.fn/source "(defn removed [] :present)"}
   {:seon.fn/sym "my.execution-proof/publish!"
    :seon.fn/ns [:seon.ns/name :my.execution-proof]
    :seon.fn/source
    (str "(defn ^:async publish! [database tx-data]\n"
         "  (await (db/transact! {:seon.db/db database\n"
         "                        :seon.db/tx-data tx-data})))")}
   {:seon.fn/sym "my.execution-proof/spin"
    :seon.fn/ns [:seon.ns/name :my.execution-proof]
    :seon.fn/source "(defn spin [_] (loop [] (recur)))"}
   {:seon.fn/sym "my.execution-proof/nested-render"
    :seon.fn/ns [:seon.ns/name :my.execution-proof]
    :seon.fn/source
    (str "(defn nested-render []\n"
         "  (render/render :seon.render/ai {}\n"
         "                 {:seon.render/ai 'my.execution-proof/spin}))")}])

(defn- dependencies []
  {::writer/database-initializer (fn [_connection _database-name] nil)
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_db-value] [])
   ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
   ::writer/embedding-assertions (fn [_inputs] [])
   ::writer/revalidate-embedding-assertions (fn [_db-value _assertions] [])
   ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_db-value _vector _k _eids] [])})

(defn- initialization []
  {:seon.execution/artifact-digest
   "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
   :seon.db/attributes []
   :seon.db/program
   (into [{:seon.ns/name :seon.execution-proof.bootstrap
           :seon.ns/source "(ns seon.execution-proof.bootstrap)"}
          {:seon.fn/sym "seon.execution-proof.bootstrap/present"
           :seon.fn/ns [:seon.ns/name :seon.execution-proof.bootstrap]
           :seon.fn/source "(defn present [] true)"}]
         (map (fn [[attribute form]]
                {:seon.schema/key attribute :seon.schema/form form}))
         schema-forms)
   :seon.db/initial-data
   [{:seon.user/id "user"}
    {:seon.agent/id "agent-a"}
    {:seon.agent/id "agent-b"}]})

(defn- socket-path [database-name]
  (.getAbsolutePath (File. "tmp" (str database-name ".sock"))))

(defn- sha256 [path]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (Files/readAllBytes (Path/of path (make-array String 0))))]
    (.formatHex (HexFormat/of) digest)))

(defn- start-driver!
  [socket database-name digest database output-file]
  (-> (ProcessBuilder.
       ^java.util.List
       ["bun" driver-artifact socket database-name
        (.getAbsolutePath (File. execution-artifact)) digest (pr-str database)])
      (.directory (File. "."))
      (.redirectErrorStream true)
      (.redirectOutput output-file)
      (.start)))

(defn- parse-evidence [output]
  (->> (str/split-lines output)
       (keep (fn [line]
               (when (str/starts-with? line "{")
                 (try (edn/read-string line) (catch Throwable _ nil)))))
       last))

(defn- transport-count [database-name]
  (count
   (::registry/transport-connections
    (get-in (registry/snapshot-registry {})
            [::registry/snapshot ::registry/registry
             (keyword database-name)]))))

(defn- wait-until! [description timeout-ms predicate]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (predicate) true
        (< (System/currentTimeMillis) deadline) (do (Thread/sleep 10) (recur))
        :else (throw (ex-info (str "Timed out waiting for " description ".")
                              {:seon.execution-proof/description description}))))))

(deftest two-real-children-reconstruct-one-current-program
  (is (.isFile (File. execution-artifact))
      "Compile the execution artifact before this selective process proof.")
  (is (.isFile (File. driver-artifact))
      "Compile the execution integration client before this selective proof.")
  (let [database-name (str "execution-proof-" (random-uuid))
        socket (socket-path database-name)
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/selected-processors 8
          ::writer/request-socket-path socket})
        runtime (::writer/runtime server)
        output-file (File. "tmp" (str database-name ".driver.log"))
        process (atom nil)]
    (try
      (let [admitted
            (writer/handle-request
             runtime
             (protocol/ensure-database-request
              {::protocol/request-id "execution-proof/initialize"
               ::protocol/database-name database-name
               ::protocol/backend :memory
               :seon.db/initialization (initialization)}))
            seeded
            (writer/handle-request
             runtime
             (protocol/transaction-request
              {::protocol/request-id "execution-proof/program"
               :seon.db/db (:seon.db/db admitted)
               ::protocol/transaction-data authored-program
               ::protocol/transaction-meta
               {:seon.db/user [:seon.agent/id "agent-a"]
                :seon.db/process
                [:seon.db.process/id :seon.db.process/repl]}}))
            _ (is (::protocol/success? admitted) (pr-str admitted))
            _ (is (::protocol/success? seeded) (pr-str seeded))
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            authored-rows
            (d/q '[:find ?sym ?process-id
                   :where
                   [?function :seon.fn/sym ?sym]
                   [?function :seon.fn/source _ ?tx]
                   [?tx :seon.db/process ?process]
                   [?process :seon.db.process/id ?process-id]]
                 (d/db connection))
            _ (is (= #{["my.execution-proof/current" :seon.db.process/repl]
                       ["my.execution-proof/removed" :seon.db.process/repl]
                       ["my.execution-proof/publish!" :seon.db.process/repl]
                       ["my.execution-proof/spin" :seon.db.process/repl]
                       ["my.execution-proof/nested-render"
                        :seon.db.process/repl]}
                     (set (filter #(= :seon.db.process/repl (second %))
                                  authored-rows)))
                  (pr-str authored-rows))
            child (start-driver! socket database-name (sha256 execution-artifact)
                                 (:db-after seeded) output-file)
            _ (reset! process child)
            exited? (.waitFor child 45 TimeUnit/SECONDS)
            _ (when-not exited?
                (.destroyForcibly child)
                (.waitFor child 5 TimeUnit/SECONDS))
            output (slurp output-file)
            evidence (parse-evidence output)
            before (:seon.execution-proof/before evidence)
            after (:seon.execution-proof/after evidence)
            spawns (:seon.execution-proof/spawns evidence)
            initial-database (:seon.execution-proof/initial-database evidence)
            current-database (:seon.execution-proof/current-database evidence)]
        (is exited? output)
        (is (and exited? (zero? (.exitValue child))) output)
        (is (map? evidence) output)
        (is (not (:seon.execution-proof/failed? evidence)) output)
        (is (= 2 (count before)))
        (is (= #{"agent-a" "agent-b"}
               (set (map #(get-in % [:seon.execution.integration-driver/value
                                     :seon.execution-proof/agent])
                         before))))
        (is (every? #(= :before
                        (get-in % [:seon.execution.integration-driver/value
                                   :seon.execution-proof/value]))
                    before))
        (is (every? #(= :present
                        (get-in % [:seon.execution.integration-driver/value
                                   :seon.execution-proof/removed]))
                    before))
        (is (= [initial-database initial-database]
               (mapv :seon.execution.integration-driver/database before)))
        (is (< (:t initial-database) (:t current-database)))
        (is (= [current-database current-database]
               (mapv :seon.execution.integration-driver/database after)))
        (is (every? #(= :after
                        (get-in % [:seon.execution.integration-driver/value
                                   :seon.execution-proof/value]))
                    after))
        (is (every? true?
                    (map #(get-in % [:seon.execution.integration-driver/value
                                     :seon.execution-proof/removed-absent?])
                         after)))
        (is (every? true?
                    (map #(get-in % [:seon.execution.integration-driver/value
                                     :seon.execution-proof/publish-absent?])
                         after)))
        (is (= #{"agent-a" "agent-b"} (set (keys spawns))))
        (is (every? #(= 2 (count %)) (vals spawns))
            (pr-str spawns))
        (is (every? #(apply not= %) (vals spawns))
            "each agent replaces exactly one process")
        (is (= "The invocation was canceled."
               (get-in evidence
                       [:seon.execution-proof/stuck-result
                        :seon.execution.integration-driver/failure
                        :seon.execution/error :seon.error/message])))
        (is (= :after
               (get-in evidence
                       [:seon.execution-proof/agent-b-during-stuck
                        :seon.execution.integration-driver/value
                        :seon.execution-proof/value]))
            "another agent remains responsive while one renderer is stuck")
        (is (= :after
               (get-in evidence
                       [:seon.execution-proof/agent-a-replacement
                        :seon.execution.integration-driver/value
                        :seon.execution-proof/value]))
            "the retired child reloads current program source")
        (is (not=
             (get-in (second after)
                     [:seon.execution.integration-driver/value
                      :seon.execution-proof/pid])
             (get-in evidence
                     [:seon.execution-proof/agent-a-replacement
                      :seon.execution.integration-driver/value
                      :seon.execution-proof/pid]))
            "the replacement runs in a new Bun process")
        (wait-until! "execution proof session release" 5000
                     #(zero? (transport-count database-name))))
      (finally
        (when-let [^Process child @process]
          (when (.isAlive child) (.destroyForcibly child)))
        (writer/stop! server)
        (.delete output-file)
        (.delete (File. socket))))))
