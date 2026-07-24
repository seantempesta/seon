(ns seon.host-authored-invocation-writer-test
  "Interaction claims invoke authored functions through the surviving JVM door."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [seon.agent.driver :as driver]
            [seon.agent.driver.host :as driver.host]
            [seon.agent.interaction :as interaction]
            [seon.agent.interaction.render :as interaction.render]
            [seon.content-hash :as content-hash]
            [seon.db :as db]
            [seon.db.host :as db.host]
            [seon.db.id :as db.id]
            [seon.db.protocol :as protocol]
            [seon.db.writer-test-support :as writer-test]
            [seon.db.writer :as writer]
            [seon.host :as host]
            [seon.host.context :as context]
            [seon.host-registry-writer-test :as registry-test]
            [seon.schema :as schema])
  (:import [java.io File]))

(def ^:private dependencies
  (var-get #'registry-test/dependencies))
(def ^:private socket-path
  (var-get #'registry-test/socket-path))
(def ^:private value-sampling-policy
  (var-get #'registry-test/value-sampling-policy))
(def ^:private runtime-policy
  (merge
   value-sampling-policy
   {:seon.config.watchdog/stale-ms 120000
    :seon.config.claim-driver/invocation-deadline-ms 30000
    :seon.config.claim-driver/invocation-result-maximum-bytes 1048576
    :seon.config.shell/default-timeout-ms 30000
    :seon.config.shell/kill-grace-ms 1000}))

(def ^:private config-manifest-digest
  "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789")

(defn- selected-manifest []
  (edn/read-string
   (slurp (System/getenv "SEON_WRITER_ARTIFACT_MANIFEST"))))

(defn- host-startgate []
  (let [manifest (selected-manifest)
        runtime-root
        (java.nio.file.Path/of
         (:seon.dev.artifact/runtime-root manifest)
         (make-array String 0))]
    {:seon.startgate/release-digest
     (:seon.dev.artifact/application-digest manifest)
     :seon.startgate/execution-digest
     (:seon.dev.artifact/execution-digest manifest)
     :seon.startgate/config-manifest-digest config-manifest-digest
     :seon.startgate/base-projection-path
     (str (.resolve runtime-root
                    (:seon.dev.artifact/base-projection-path manifest)))
     :seon.startgate/base-projection-digest
     (:seon.dev.artifact/base-projection-digest manifest)}))

(defn- initialization-fingerprint [session]
  (ffirst
   (context/query-writer!
    session
    '[:find ?fingerprint
      :where
      [?initialization :seon.db.initialization/id "database"]
      [?initialization :seon.db.initialization/fingerprint ?fingerprint]]
    [])))

(defn- aligned-host-startgate [session]
  (let [startgate (host-startgate)
        artifact
        (-> (slurp (:seon.startgate/base-projection-path startgate))
            edn/read-string
            (assoc :seon.db.initialization/fingerprint
                   (initialization-fingerprint session)))
        text (str (pr-str artifact) "\n")
        path (str "tmp/r52-base-projection-" (random-uuid) ".edn")]
    (spit path text)
    (assoc startgate
           :seon.startgate/base-projection-path path
           :seon.startgate/base-projection-digest
           (content-hash/sha-256 text)
           ::base-projection-path path)))

(defn- install-divergence-cache! [session agent-id]
  (let [base-artifact
        (-> (host-startgate)
            :seon.startgate/base-projection-path
            slurp
            edn/read-string)
        base (:seon.dev.artifact/base-projection base-artifact)
        delta {}
        head (context/resolve-head! session)
        row
        {:seon.runtime.admission.cache/id "committed-projection"
         :seon.runtime.admission.cache/base-fingerprint
         (:seon.schema.projection/fingerprint base)
         :seon.runtime.admission.cache/divergence-fingerprint
         (schema/canonical-data-fingerprint delta)
         :seon.runtime.admission.cache/composed-fingerprint
         (:seon.schema.projection/fingerprint
          (schema/compose-projection-data base delta))
         :seon.runtime.admission.cache/basis-t (inc (:t head))
         :seon.runtime.admission.cache/delta (pr-str delta)}
        report
        (binding [context/*agent-id* agent-id]
          (context/transact-writer! session head [row]))]
    (is (:seon.db/ok? report) (pr-str report))))

(def ^:private square-source
  "(defn square {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))")

(defn- function-row [handler]
  {:seon.fn/sym (str handler)
   :seon.fn/ns {:seon.ns/name (symbol (namespace handler))}
   :seon.fn/source square-source
   :seon.fn/source-fingerprint (content-hash/sha-256 square-source)
   :seon.fn/execution-tier :nursery
   :seon.fn/fn-var? true
   :seon.fn/arglists "([x])"
   :seon.fn/doc "R52 guarded interaction fixture."
   :seon.fn/private? false
   :seon.fn/spec "[:=> [:cat :int] :int]"
   :seon.fn/created-at (java.util.Date.)})

(defn- seed! [session database-name agent-id agent-candidates handler]
  (let [seed
        (writer-test/seed-canonical-schema!
         session database-name
         [runtime-policy
          {:seon.user/id "user"}
          {:seon.db.process/id :seon.db.process/repl}])]
    (is (true? (::protocol/success? seed)) (pr-str seed)))
  (let [allocated
        (db.host/call!
         session
         (protocol/transaction-request
          {::protocol/request-id (str (random-uuid))
           :seon.db/db (db.host/resolve-db! session nil false)
           ::protocol/transaction-data [{:seon.agent/id agent-id}]
           ::protocol/generated-candidates agent-candidates}))]
    (is (true? (::protocol/success? allocated)) (pr-str allocated)))
  (let [startgate (host-startgate)
        report
        (binding [context/*agent-id* agent-id]
          (context/transact-writer!
           session
           [{:seon.db.initialization/id "database"
             :seon.db.initialization/release-digest
             (:seon.startgate/release-digest startgate)
             :seon.db.initialization/config-manifest-digest
             (:seon.startgate/config-manifest-digest startgate)}
            (function-row handler)]))]
    (is (:seon.db/ok? report) (pr-str report)))
  (install-divergence-cache! session agent-id))

(deftest interaction-fact-claims-executes-settles-and-renders
  (let [database-name (str "interaction-" (random-uuid))
        request-path (socket-path "interaction-writer")
        host-socket (socket-path "interaction-host")
        agent-candidates
        (db.id/candidate-manifest
         {:seon.agent/id :seon.db.id.generator/human-readable}
         [{:seon.db.id/key ::agent
           :seon.db.id/identity-attr :seon.agent/id}])
        agent-id (:seon.db.id/value (first agent-candidates))
        handler (symbol (str "my.agent." agent-id "/square"))
        server
        (writer-test/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
        session
        (context/writer-session
         {::context/writer-socket-path request-path
          ::context/database-name database-name
          ::context/backend :memory})
        started (atom nil)
        startgate (atom nil)]
    (try
      (seed! session database-name agent-id agent-candidates handler)
      (reset! started
              (host/start!
               (merge
                (reset! startgate (aligned-host-startgate session))
                {::host/socket-path host-socket
                 ::context/writer-socket-path request-path
                 ::context/database-name database-name
                 ::context/backend :memory})))
      (testing "one queued fact enters the existing claim and guarded eval path"
        (let [database-leaf
              (driver.host/database-leaf (::host/writer @started))
              now (java.util.Date.)
              allocation
              (binding [db/*leaf* database-leaf]
                (db.id/allocate!
                 {::db/db (context/resolve-head! session)
                  ::db.id/allocations
                  [{::db.id/key ::interaction-id
                    ::db.id/identity-attr :seon.agent.interaction/id}
                   {::db.id/key ::run-id
                    ::db.id/identity-attr :seon.agent.run/id}]
                  ::db.id/transaction-builder
                  (fn [ids]
                    {::db/tx-data
                     (interaction/open-tx-data
                      {:seon.agent.interaction/id
                       (get ids ::interaction-id)
                       :seon.agent.run/id (get ids ::run-id)
                       :seon.agent/id agent-id
                       :seon.agent.interaction/handler handler
                       :seon.agent.interaction/handler-source-fingerprint
                       (content-hash/sha-256 square-source)
                       :seon.agent.interaction/arguments [6]
                       :seon.agent.interaction/subjects
                       #{[:seon.agent/id agent-id]}
                       :seon.agent.interaction/requested-at now
                       :seon.agent.run/deadline
                       (java.util.Date. (+ (.getTime now) 60000))})})}))
              interaction-id
              (get-in allocation [::db.id/ids ::interaction-id])
              platform-leaf
              {:seon.agent.driver/capabilities
               #{:seon.agent.driver.capability/interaction}
               :seon.agent.driver/now #(java.util.Date.)
               :seon.agent.driver/execute-step!
               #(#'driver.host/execute-step! @started nil %)}
              driven
              (driver/call-with-leaf
               platform-leaf database-leaf
               #(driver/scan!))
              head (context/resolve-head! session)
              stored
              (binding [db/*leaf* database-leaf]
                (db/pull
                 {::db/db head
                  ::db/pull-pattern
                  [:seon.agent.interaction/status
                   :seon.agent.interaction/result
                   :seon.agent.interaction/error
                   :seon.agent.run/status
                   :seon.agent.run/closed-reason]
                  ::db/ref
                  [:seon.agent.interaction/id interaction-id]}))
              provenance
              (binding [db/*leaf* database-leaf]
                (db/query
                 {::db/db head
                  ::db/query
                  '[:find ?user-id ?process-id
                    :in $ ?interaction-id
                    :where
                    [?interaction :seon.agent.interaction/id
                     ?interaction-id ?tx]
                    [?tx :seon.db/user ?user]
                    [?user :seon.user/id ?user-id]
                    [?tx :seon.db/process ?process]
                    [?process :seon.db.process/id ?process-id]]
                  ::db/args [interaction-id]}))
              rendered
              (binding [db/*leaf* database-leaf]
                (interaction.render/render-html
                 {::db/db head
                  :seon.agent/id agent-id
                  :seon.render/node
                  {:seon.agent.ctx/token-cap 512}}))]
          (is (not (:seon.error/message allocation)) (pr-str allocation))
          (is (= :completed
                 (get-in driven [0 :seon.agent.run/closed-reason]))
              (pr-str driven))
          (is (= {:seon.agent.interaction/status :done
                  :seon.agent.interaction/result 36
                  :seon.agent.run/status :closed
                  :seon.agent.run/closed-reason :completed}
                 stored))
          (is (= #{["user" :seon.db.process/repl]} provenance))
          (is (some? rendered))
          (is (re-find #"36" (pr-str rendered)))))
      (finally
        (when @started (host/stop! @started))
        (when-let [path (::base-projection-path @startgate)]
          (.delete (File. ^String path)))
        (context/close-session! session)
        (writer/stop! server)
        (.delete (File. ^String request-path))
        (.delete (File. ^String host-socket))))))
