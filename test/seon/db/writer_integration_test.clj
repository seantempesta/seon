(ns seon.db.writer-integration-test
  "End-to-end canonical writer request and publication tests."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db.backend :as backend]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer]
            [seon.embed :as embed]
            [seon.schema :as schema])
  (:import [java.io File]
           [java.nio.channels Channels SocketChannel]))

(defn- socket-path
  [label]
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory
            (str "seon-writer-" label "-" (random-uuid) ".sock")))))

(defn- dependencies
  ([]
   (dependencies (fn [_connection _database-name] nil)))
  ([database-initializer]
   {::writer/database-initializer database-initializer
    ::writer/transaction-transform (fn [_db-value transaction-data]
                                     transaction-data)
    ::writer/knn-search (fn [_db-value _request] {:seon.embed/hits []})}))

(defn- wait-for-subscriber!
  [publisher]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (cond
        (seq @(::uds/subscribers publisher)) true
        (> (System/currentTimeMillis) deadline)
        (throw (ex-info "publisher did not accept its subscriber" {}))
        :else (do (Thread/sleep 10) (recur))))))

(defn- call!
  [channel request]
  (uds/call! {::uds/channel channel ::uds/message request}))

(deftest writer-stop-surfaces-release-failure-and-retains-database-identity
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})
        database-name (str "writer-release-failure-" (random-uuid))
        database-keyword (keyword database-name)
        request-path (socket-path "release-failure-request")
        publish-path (socket-path "release-failure-publish")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        coordinate-before
        (::registry/coordinate
         (registry/resolve-connection
          {::registry/database-name database-keyword}))]
    (try
      (let [result
            (with-redefs [d/release
                          (fn [_]
                            (throw (ex-info "writer release failed" {})))]
              (writer/stop! server))
            failure (first (::writer/release-results result))
            retained
            (first
             (filter #(= database-keyword (::registry/database-name %))
                     (::registry/databases (registry/list-databases {}))))]
        (is (false? (::writer/stopped? result)))
        (is (schema/valid-candidate-value? ::writer/stop-response result))
        (is (= database-keyword (::registry/database-name failure)))
        (is (= (coordinate/attachment coordinate-before)
               (::registry/attachment failure)))
        (is (= coordinate-before (::registry/coordinate failure)))
        (is (false? (::registry/released? failure)))
        (is (re-find #"writer release failed"
                     (::registry/release-error failure)))
        (is (= (::registry/release-error failure)
               (::registry/release-error retained))
            "the failed database identity remains inspectable"))
      (finally
        (registry/restore-registry! {::registry/snapshot snapshot})
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest writer-stop-returns-the-pre-release-coordinate-and-outcome
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})
        database-name (str "writer-release-success-" (random-uuid))
        database-keyword (keyword database-name)
        request-path (socket-path "release-success-request")
        publish-path (socket-path "release-success-publish")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        before
        (registry/resolve-connection
         {::registry/database-name database-keyword})]
    (try
      (let [result (writer/stop! server)
            release (first (::writer/release-results result))]
        (is (true? (::writer/stopped? result)))
        (is (schema/valid-candidate-value? ::writer/stop-response result))
        (is (= 1 (count (::writer/release-results result))))
        (is (= {::registry/database-name database-keyword
                ::registry/attachment (::registry/attachment before)
                ::registry/coordinate (::registry/coordinate before)
                ::registry/released? true}
               release))
        (is (nil? (::registry/conn
                   (registry/resolve-connection
                    {::registry/database-name database-keyword})))))
      (finally
        (registry/restore-registry! {::registry/snapshot snapshot})
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest existing-database-must-already-match-the-protocol-candidate
  (doseq [[label initial-tx]
          [["missing" nil]
           ["incompatible"
            [{:db/ident ::protocol/request-id
              :db/valueType :db.type/keyword
              :db/cardinality :db.cardinality/one
              :db/unique :db.unique/identity}]]]]
    (let [database-name (str "writer-protocol-candidate-" label "-"
                             (random-uuid))
          database-keyword (keyword database-name)
          config
          (backend/datahike-config
           (cond-> {::backend/database-name database-keyword
                    ::backend/backend :memory}
             (seq initial-tx) (assoc ::backend/initial-tx initial-tx)))
          request-path (socket-path (str label "-request"))
          publish-path (socket-path (str label "-publish"))]
      (try
        (d/create-database config)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Initial database ensure failed"
             (writer/start!
              {::writer/dependencies (dependencies)
               ::writer/database-name database-name
               ::writer/backend :memory
               ::writer/request-socket-path request-path
               ::writer/publish-socket-path publish-path})))
        (is (empty? (::registry/databases (registry/list-databases {})))
            "a rejected schema never publishes a registry entry")
        (finally
          (when (d/database-exists? config)
            (d/delete-database config))
          (.delete (File. request-path))
          (.delete (File. publish-path)))))))

(deftest writer-opens-and-routes-an-explicit-native-branch-attachment
  (let [database-name (str "writer-branch-main-" (random-uuid))
        branch-name (str "writer-branch-route-" (random-uuid))
        request-path (socket-path "branch-request")
        publish-path (socket-path "branch-publish")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        main
        (registry/resolve-connection
         {::registry/database-name (keyword database-name)})
        attachment
        (assoc (::registry/attachment main)
               ::coordinate/branch :experiment/writer)]
    (try
      (d/branch! (::registry/conn main) :db :experiment/writer)
      (with-open [channel (uds/connect! request-path)]
        (let [ensure-response
              (call! channel
                     (protocol/ensure-database-request
                      {::protocol/database-name branch-name
                       ::protocol/backend :memory
                       ::coordinate/attachment attachment}))
              transaction-response
              (call! channel
                     (protocol/transaction-request
                      {::protocol/database-name branch-name
                       ::protocol/request-id "branch/routed"
                       ::protocol/transaction-data []}))]
          (is (::protocol/success? ensure-response))
          (is (= attachment
                 (coordinate/attachment
                  (::coordinate/coordinate ensure-response))))
          (is (::protocol/success? transaction-response))
          (is (= attachment
                 (coordinate/attachment
                  (::protocol/coordinate transaction-response))))))
      (finally
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest native-branch-open-restores-proximum-without-running-main-initializer
  (let [database-name (str "writer-proximum-main-" (random-uuid))
        branch-name (str "writer-proximum-branch-" (random-uuid))
        database-root (File. "tmp" (str "writer-proximum-" (random-uuid)))
        database-path (.getPath (File. database-root "db"))
        request-path (socket-path "proximum-branch-request")
        publish-path (socket-path "proximum-branch-publish")
        initializer-calls (atom [])
        server
        (writer/start!
         {::writer/dependencies
          (dependencies
           (fn [connection initialized-database-name]
             (swap! initializer-calls conj initialized-database-name)
             (embed/install! connection)))
          ::writer/database-name database-name
          ::writer/backend :file
          ::writer/database-path database-path
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        main
        (registry/resolve-connection
         {::registry/database-name (keyword database-name)})
        branch :experiment/proximum
        attachment
        (assoc (::registry/attachment main) ::coordinate/branch branch)]
    (try
      (is (embed/index-declared? (::registry/conn main)))
      (is (embed/index-live? (::registry/conn main)))
      (d/branch! (::registry/conn main)
                 (::coordinate/commit-id (::registry/coordinate main))
                 branch)
      (let [before (coordinate/resolved
                    (d/branch-as-db (::registry/conn main) branch))]
        (with-open [channel (uds/connect! request-path)]
          (let [response
                (call! channel
                       (protocol/ensure-database-request
                        {::protocol/database-name branch-name
                         ::protocol/backend :file
                         ::protocol/database-path database-path
                         ::coordinate/attachment attachment}))
                opened
                (registry/resolve-connection
                 {::registry/database-name (keyword branch-name)})]
            (is (::protocol/success? response))
            (is (= before (::registry/coordinate opened)))
            (is (embed/index-declared? (::registry/conn opened)))
            (is (embed/index-live? (::registry/conn opened)))
            (is (= [(keyword database-name)] @initializer-calls)
                "the writing database initializer runs only for main"))))
      (finally
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))
        (when (.exists database-root)
          (run! (fn [^File file] (.delete file))
                (reverse (file-seq database-root))))))))

(deftest typed-native-branch-lifecycle-routes-through-one-writer
  (let [source-name (str "writer-lifecycle-source-" (random-uuid))
        target-name (str "writer-lifecycle-target-" (random-uuid))
        request-path (socket-path "life-req")
        publish-path (socket-path "life-pub")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name source-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})]
    (try
      (with-open [channel (uds/connect! request-path)]
        (let [initial-head
              (::registry/coordinate
               (registry/resolve-connection
                {::registry/database-name (keyword source-name)}))
              source-write
              (call! channel
                     (protocol/transaction-request
                      {::protocol/database-name source-name
                       ::protocol/request-id "lifecycle/source-schema"
                       ::protocol/transaction-data
                       [{:db/ident :writer.lifecycle/value
                         :db/valueType :db.type/string
                         :db/cardinality :db.cardinality/one}
                        {:writer.lifecycle/value "shared"}]}))
              source-head (::protocol/coordinate source-write)
              branch :experiment/typed-lifecycle
              stale
              (call! channel
                     (protocol/create-branch-request
                      {::protocol/source-database-name source-name
                       ::protocol/target-database-name target-name
                       ::protocol/source-coordinate source-head
                       ::protocol/expected-source-head initial-head
                       ::protocol/target-branch branch}))
              created
              (call! channel
                     (protocol/create-branch-request
                      {::protocol/source-database-name source-name
                       ::protocol/target-database-name target-name
                       ::protocol/source-coordinate source-head
                       ::protocol/expected-source-head source-head
                       ::protocol/target-branch branch}))
              target-attachment (::protocol/target-attachment created)
              target-write
              (call! channel
                     (protocol/transaction-request
                      {::protocol/database-name target-name
                       ::protocol/request-id "lifecycle/target-write"
                       ::protocol/transaction-data
                       [{:writer.lifecycle/value "target-only"}]}))
              target-head (::protocol/coordinate target-write)
              source-connection
              (::registry/conn
               (registry/resolve-connection
                {::registry/database-name (keyword source-name)}))
              target-connection
              (::registry/conn
               (registry/resolve-connection
                {::registry/database-name (keyword target-name)}))]
          (let [unopened-branch :experiment/unopened
                _ (d/branch! source-connection
                             (::coordinate/commit-id source-head)
                             unopened-branch)
                observation
                (call! channel
                       (protocol/observe-database-lifecycle-request
                        {::protocol/database-name source-name}))]
            (is (::protocol/success? observation))
            (is (= source-head (::protocol/main-coordinate observation)))
            (is (= (set (or (d/parent-commit-ids (d/db source-connection)) []))
                   (::protocol/main-parent-commit-ids observation)))
            (is (= [] (::protocol/restore-completions observation)))
            (is (= #{} (::protocol/completed-restore-ids observation)))
            (is (= {}
                   (::protocol/restore-completion-coordinates observation)))
            (is (= #{:db branch unopened-branch}
                   (::protocol/branch-roster observation)))
            (is (= (::protocol/branch-roster observation)
                   (set (keys (::protocol/branch-coordinates observation)))))
            (is (= (assoc source-head ::coordinate/branch unopened-branch)
                   (get (::protocol/branch-coordinates observation)
                        unopened-branch))))
          (is (= protocol/stale-source-head-error
                 (::protocol/error-kind stale)))
          (is (::protocol/success? created))
          (is (true? (::protocol/created? created)))
          (is (false? (::protocol/adopted? created)))
          (is (= (assoc source-head ::coordinate/branch branch)
                 (::protocol/coordinate created)))
          (is (::protocol/success? target-write))
          (is (some? (d/q '[:find ?entity .
                             :where [?entity :writer.lifecycle/value "target-only"]]
                           (d/db target-connection))))
          (is (nil? (d/q '[:find ?entity .
                            :where [?entity :writer.lifecycle/value "target-only"]]
                          (d/db source-connection))))
          (let [released
                (call! channel
                       (protocol/release-database-request
                        {::protocol/target-database-name target-name
                         ::protocol/target-attachment target-attachment
                         ::protocol/expected-target-head target-head}))
                deleted
                (call! channel
                       (protocol/delete-branch-request
                        {::protocol/source-database-name source-name
                         ::protocol/target-database-name target-name
                         ::protocol/target-attachment target-attachment
                         ::protocol/expected-target-head target-head}))]
            (is (::protocol/success? released))
            (is (true? (::protocol/released? released)))
            (is (::protocol/success? deleted))
            (is (false? (::protocol/released? deleted)))
            (is (true? (::protocol/deleted? deleted)))
            (is (not (contains? (d/branches source-connection) branch))))))
      (finally
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest canonical-writes-route-commit-and-publish-exactly-once
  (let [database-name (str "writer-integration-" (random-uuid))
        request-path (socket-path "request")
        publish-path (socket-path "publish")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        ^SocketChannel request-channel (uds/connect! request-path)
        ^SocketChannel publish-channel (uds/connect! publish-path)]
    (try
      (wait-for-subscriber! (::writer/publisher server))
      (let [publish-input (Channels/newInputStream publish-channel)
            ping-response
            (call! request-channel (protocol/ping-request))
            ensure-response
            (call! request-channel
                   (protocol/ensure-database-request
                    {::protocol/database-name database-name
                     ::protocol/backend :memory}))
            initial-coordinate (::coordinate/coordinate ensure-response)
            writer-connection
            (::registry/conn
             (registry/resolve-connection
              {::registry/database-name (keyword database-name)}))
            invalid-response
            (call! request-channel
                   {::protocol/operation protocol/transact-operation
                    ::protocol/request-id "missing-route"
                    ::protocol/transaction-data []})
            ensure-after-invalid
            (call! request-channel
                   (protocol/ensure-database-request
                    {::protocol/database-name database-name
                     ::protocol/backend :memory}))
            unknown-response
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name "not-open"
                     ::protocol/request-id "unknown-route"
                     ::protocol/transaction-data []}))
            schema-request
            (protocol/transaction-request
             {::protocol/database-name database-name
              ::protocol/request-id "writer/schema"
              ::protocol/transaction-data
              [{:db/ident :writer.person/id
                :db/valueType :db.type/string
                :db/cardinality :db.cardinality/one
                :db/unique :db.unique/identity}
               {:db/ident :writer.person/status
                :db/valueType :db.type/keyword
                :db/cardinality :db.cardinality/one}
               {:db/ident :writer.person/score
                :db/valueType :db.type/double
                :db/cardinality :db.cardinality/one}]})
            schema-response (call! request-channel schema-request)
            entity-request
            (protocol/transaction-request
             {::protocol/database-name database-name
              ::protocol/request-id "writer/entity"
              ::protocol/transaction-data
              [{:db/id "person-temp"
                :writer.person/id "alice"
                :writer.person/status :writer.status/ready
                :writer.person/score 1}]})
            entity-response (call! request-channel entity-request)
            schema-event (uds/read-frame publish-input)
            entity-event (uds/read-frame publish-input)
            recovered-response (call! request-channel entity-request)
            unexpected-event (future (uds/read-frame publish-input))
            no-event-sentinel ::no-event
            observed-after-retry (deref unexpected-event 250 no-event-sentinel)
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            stored
            (d/pull (d/db connection) '[*]
                    [:writer.person/id "alice"])]
        (is (= {::protocol/success? true ::protocol/pong? true}
               ping-response))
        (is (every? protocol/valid-response?
                    [ensure-response invalid-response ensure-after-invalid
                     unknown-response schema-response entity-response
                     recovered-response]))
        (is (coordinate/same-attachment?
             (coordinate/resolved (d/db connection))
             (::coordinate/coordinate ensure-response)))
        (is (= initial-coordinate
               (::coordinate/coordinate ensure-after-invalid))
            "an invalid request and an idempotent ensure write nothing")
        (is (= protocol/protocol-error
               (::protocol/error-kind invalid-response)))
        (is (= protocol/not-found-error
               (::protocol/error-kind unknown-response))
            "a named unknown database cannot fall back to the open database")
        (is (true? (::protocol/success? schema-response)))
        (is (= protocol/reserved-attributes
               (set (filter #(contains? (:schema (d/db writer-connection)) %)
                            protocol/reserved-attributes)))
            "the canonical Malli-derived receipt schema exists at publication")
        (is (true? (::protocol/success? entity-response)))
        (is (pos-int?
             (get (::protocol/temporary-ids entity-response) "person-temp")))
        (is (= [(::protocol/coordinate schema-response)
                (::protocol/coordinate entity-response)]
               (mapv ::protocol/coordinate
                     [schema-event entity-event])))
        (is (= ["writer/schema" "writer/entity"]
               (mapv ::protocol/request-id [schema-event entity-event])))
        (is (every? #(= protocol/transaction-event (::protocol/event %))
                    [schema-event entity-event]))
        (is (every?
             empty?
             (for [message [schema-response entity-response
                            schema-event entity-event]]
               (filter protocol/reserved-attributes
                       (map second (::protocol/transaction-data message)))))
            "public response and event datoms omit receipt implementation")
        (is (every?
             empty?
             (for [message [schema-response entity-response
                            schema-event entity-event]]
               (filter protocol/reserved-attributes
                       (keys (or (::protocol/transaction-meta message) {})))))
            "public response and event metadata omit receipt implementation")
        (is (= (count (::protocol/transaction-data entity-response))
               (+ (::protocol/datoms-added entity-response)
                  (::protocol/datoms-retracted entity-response))))
        (is (true? (::protocol/recovered? recovered-response)))
        (is (= no-event-sentinel observed-after-retry)
            "a recovered delivery emits no duplicate transaction event")
        (is (= :writer.status/ready (:writer.person/status stored)))
        (is (instance? Double (:writer.person/score stored)))
        (is (= 1.0 (:writer.person/score stored))))
      (finally
        (try (.close request-channel) (catch Throwable _))
        (try (.close publish-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest expected-basis-is-enforced-inside-the-serialized-writer
  (let [database-name (str "writer-fence-" (random-uuid))
        request-path (socket-path "fence-request")
        publish-path (socket-path "fence-publish")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        ^SocketChannel request-channel (uds/connect! request-path)]
    (try
      (let [opened
            (call! request-channel
                   (protocol/ensure-database-request
                    {::protocol/database-name database-name
                     ::protocol/backend :memory}))
            schema
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name database-name
                     ::protocol/request-id "fence/schema"
                     ::protocol/transaction-data
                     [{:db/ident :writer.fence/id
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/unique :db.unique/identity}
                      {:db/ident :writer.fence/value
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one}]}))
            frozen (::protocol/coordinate schema)
            accepted
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name database-name
                     ::protocol/request-id "fence/accepted"
                     ::protocol/expected-coordinate frozen
                     ::protocol/transaction-data
                     [{:writer.fence/id "one"
                       :writer.fence/value "accepted"}]}))
            committed (::protocol/coordinate accepted)
            wrong-branch
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name database-name
                     ::protocol/request-id "fence/wrong-branch"
                     ::protocol/expected-coordinate
                     (assoc committed ::coordinate/branch :experiment)
                     ::protocol/transaction-data
                     [{:writer.fence/id "one"
                       :writer.fence/value "wrong-branch-must-not-land"}]}))
            rejected
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name database-name
                     ::protocol/request-id "fence/rejected"
                     ::protocol/expected-coordinate frozen
                     ::protocol/transaction-data
                     [{:writer.fence/id "one"
                       :writer.fence/value "must-not-land"}]}))
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            stored (d/pull (d/db connection) '[*]
                           [:writer.fence/id "one"])]
        (is (true? (::protocol/success? opened)))
        (is (true? (::protocol/success? accepted)))
        (is (= protocol/stale-coordinate-error
               (::protocol/error-kind wrong-branch)))
        (is (= committed (::protocol/current-coordinate wrong-branch))
            "equal t and commit cannot cross a different branch attachment")
        (is (protocol/valid-response? rejected))
        (is (= protocol/stale-coordinate-error
               (::protocol/error-kind rejected)))
        (is (= frozen (::protocol/expected-coordinate rejected)))
        (is (= committed (::protocol/current-coordinate rejected)))
        (is (= (::coordinate/t committed) (:max-tx (d/db connection)))
            "the rejected request creates no receipt or transaction")
        (is (= "accepted" (:writer.fence/value stored))
            "none of the stale request lands"))
      (finally
        (try (.close request-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))
