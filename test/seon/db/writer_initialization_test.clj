(ns seon.db.writer-initialization-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db.branch :as branch]
            [seon.db.executor :as executor]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.writer-test-support :as writer-test]
            [seon.db.writer :as writer]
            [seon.schema :as schema])
  (:import [java.io File]))

(defn- dependencies []
  {::writer/database-initializer (fn [_connection _database-name] nil)
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_db-value] [])
   ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
   ::writer/embedding-assertions (fn [_inputs] [])
   ::writer/revalidate-embedding-assertions (fn [_db-value _assertions] [])
   ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_db-value _vector _k _eids] [])})

(deftest start-rejects-missing-read-defaults-before-executor-allocation
  (let [executor-starts (atom 0)
        result
        (with-redefs [executor/start!
                      (fn [_request]
                        (swap! executor-starts inc)
                        (throw (ex-info "executor must not start" {})))]
          (writer/start!
           {::writer/dependencies (dependencies)
            ::writer/database-name "missing-read-defaults"
            ::writer/backend :memory
            ::writer/request-socket-path
            "tmp/missing-read-defaults.sock"}))
        error (:seon/error result)
        validation-errors
        (get-in error [:seon.error/data ::writer/validation-errors])]
    (is (zero? @executor-starts))
    (is (schema/valid-candidate-value? ::writer/start-response result))
    (is (= :user-input (:seon.error/kind error)))
    (is (str/includes? (:seon.error/message error)
                       ":seon.db.writer/read-defaults"))
    (is (some (fn [validation-error]
                (= {::writer/validation-input-path [::writer/read-defaults]
                    ::writer/validation-type :malli.core/missing-key}
                   validation-error))
              validation-errors))))

(def schema-forms
  {:seon.db/lookup-ref-value "[:or :string :uuid :keyword :symbol :int]"
   :seon.db/ref
   "[:or :int :string [:tuple :keyword :seon.db/lookup-ref-value]]"
   :seon.schema/key "[:keyword {:seon.db/identity true}]"
   :seon.schema/form ":string"
   :seon.db.id/generator
   "[:enum :seon.db.id.generator/human-readable :seon.db.id.generator/compact]"
   :seon.agent/id "[:string {:seon.db/identity true}]"
   :seon.db/user ":seon.db/ref"
   :seon.db/process ":seon.db/ref"
   :seon.db.process/id
   "[:and {:seon.db/identity true} [:enum :seon.db.process/boot :seon.db.process/config :seon.db.process/repl]]"
   :seon.user/id "[:string {:seon.db/identity true}]"
   :seon.render/full? ":boolean"
   :seon.ns/name "[:symbol {:seon.db/identity true}]"
   :seon.ns/source ":string"
   :seon.ns/doc ":string"
   :seon.ns/summary "[:string {:min 1}]"
   :seon.fn/sym "[:string {:seon.db/identity true}]"
   :seon.fn/ns ":seon.db/ref"
   :seon.fn/source ":string"
   :seon.fn/doc ":string"
   :seon.fn/arglists ":string"
   :seon.fn/private? ":boolean"
   :seon.user/entity
   "[:map {:seon.db/entity true} [:seon.user/id :seon.user/id]]"
   :seon.db.protocol/cursor ":int"
   :datahike.index-page/cursor ":seon.db.protocol/cursor"
   :seon.db.protocol/index-page-request
   "[:map [:datahike.index-page/cursor {:optional true} :datahike.index-page/cursor]]"})

(def initialization
  {:seon.execution/artifact-digest
   "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
   :seon.db/attributes
   [:seon.agent/id :seon.db/user :seon.db/process :seon.db.process/id
    :seon.user/id :seon.ns/name :seon.ns/source :seon.ns/doc :seon.ns/summary
    :seon.fn/sym :seon.fn/ns
    :seon.fn/source :seon.fn/doc :seon.fn/arglists :seon.fn/private?
    :seon.render/full?]
   :seon.db/program
   (into [{:seon.ns/name 'my.core
           :seon.ns/source "(ns my.core)"
           :seon.ns/doc "Own core behavior.\n\nMore detail."
           :seon.ns/summary "Own core behavior."}
          {:seon.fn/sym "my.core/answer"
           :seon.fn/ns [:seon.ns/name 'my.core]
           :seon.fn/source "(defn answer [] 42)"
           :seon.fn/doc "Answer."
           :seon.fn/arglists "([])"
           :seon.fn/private? false}]
         (map (fn [[attribute form]]
                {:seon.schema/key attribute
                 :seon.schema/form form}))
         schema-forms)
   :seon.db/initial-data [{:seon.user/id "user"}]})

(deftest ensure-admits-one-program-and-converges-without-another-transaction
  (let [database-name (str "writer-initialization-" (random-uuid))
        socket-file (File. "tmp" (str database-name ".sock"))
        server
        (writer-test/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path (.getAbsolutePath socket-file)})
        runtime (::writer/runtime server)
        ensure
        (fn [request-id initialization]
          (writer/handle-request
           runtime
           (protocol/ensure-database-request
            {::protocol/request-id request-id
             ::protocol/database-name database-name
             ::protocol/backend :memory
             :seon.db/initialization initialization})))
        base-initialization
        (update initialization :seon.db/attributes
                #(vec (remove #{:seon.render/full?} %)))]
    (try
      (let [before
            (:max-tx
             (d/db
              (::registry/conn
               (registry/lookup-connection
                {::registry/database-name (keyword database-name)}))))
            admitted (ensure "initialization/first" base-initialization)
            after-first (:t (:seon.db/db admitted))
            upgraded (ensure "initialization/upgrade" initialization)
            after-upgrade (:t (:seon.db/db upgraded))
            converged (ensure "initialization/converged" initialization)
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))]
        (is (::protocol/success? admitted) (pr-str admitted))
        (is (= (+ before 2) after-first)
            "fresh admission is one genesis plus one boot transaction")
        (is (= (inc after-first) after-upgrade)
            "a populated database installs a newly selected attribute once")
        (is (= after-upgrade (:t (:seon.db/db converged)))
            "a converged ensure creates no transaction")
        (is (= "(defn answer [] 42)"
               (d/q '[:find ?source .
                      :where
                      [?function :seon.fn/sym "my.core/answer"]
                      [?function :seon.fn/source ?source]]
                    (d/db connection))))
        (is (= "[:map [:datahike.index-page/cursor {:optional true} :datahike.index-page/cursor]]"
               (d/q '[:find ?form .
                      :where
                      [?schema :seon.schema/key :seon.db.protocol/index-page-request]
                      [?schema :seon.schema/form ?form]]
                    (d/db connection)))
            "request schemas remain queryable program facts")
        (is (contains? (:schema (d/db connection)) :seon.user/id)
            "stored entity attributes are installed before initial data")
        (is (contains? (:schema (d/db connection)) :seon.render/full?)
            "explicit dataless scalar attributes are installed at initialization")
        (is (every? #(contains? (:schema (d/db connection)) %)
                    [:seon.ns/doc :seon.ns/summary])
            "namespace metadata attributes are installed before later domain writes")
        (is (not (contains? (:schema (d/db connection))
                            :datahike.index-page/cursor))
            "request fields are not installed as Datahike attributes")
        (is (= #{:seon.db.process/boot
                 :seon.db.process/config
                 :seon.db.process/repl}
               (set
                (d/q '[:find [?id ...]
                       :where [_ :seon.db.process/id ?id]]
                     (d/db connection)))))
        (let [before-domain (d/db connection)
              report
              (d/transact
               connection
               [{:seon.ns/name 'my.later
                 :seon.ns/source "(ns my.later)"
                 :seon.ns/doc "Own later behavior."
                 :seon.ns/summary "Own later behavior."}])
              after-domain (:db-after report)]
          (is (= (inc (:max-tx before-domain)) (:max-tx after-domain)))
          (is (= (:schema before-domain) (:schema after-domain))
              "the first later namespace write installs no schema lazily")))
      (finally
        (writer/stop! server)
        (.delete socket-file)))))

(deftest ensure-migrates-only-an-additive-index-and-then-converges
  (let [database-name (str "writer-additive-index-" (random-uuid))
        socket-file (File. "tmp" (str database-name ".sock"))
        server
        (writer-test/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path (.getAbsolutePath socket-file)})
        runtime (::writer/runtime server)
        ensure
        (fn [request-id initialization]
          (writer/handle-request
           runtime
           (protocol/ensure-database-request
            {::protocol/request-id request-id
             ::protocol/database-name database-name
             ::protocol/backend :memory
             :seon.db/initialization initialization})))
        with-event-schema
        (fn [initialization at-form]
          (-> initialization
              (update :seon.db/attributes into [:example.event/id
                                                :example.event/at])
              (update :seon.db/program into
                      [{:seon.schema/key :example.event/id
                        :seon.schema/form
                        "[:string {:seon.db/identity true}]"}
                       {:seon.schema/key :example.event/at
                        :seon.schema/form at-form}])
              (update :seon.db/initial-data conj
                      {:example.event/id "first"
                       :example.event/at (java.util.Date. 1000)})))
        initial (with-event-schema initialization ":inst")
        indexed (with-event-schema initialization
                                   "[:inst {:seon.db/index true}]")
        incompatible
        [(with-event-schema initialization ":string")
         (with-event-schema initialization "[:vector :inst]")]]
    (try
      (let [admitted (ensure "additive-index/initial" initial)
            before-index (:t (:seon.db/db admitted))
            migrated (ensure "additive-index/migrate" indexed)
            after-index (:t (:seon.db/db migrated))
            converged (ensure "additive-index/converged" indexed)
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            db-value (d/db connection)
            before-rejection (:max-tx db-value)]
        (is (::protocol/success? admitted) (pr-str admitted))
        (is (= (inc before-index) after-index)
            "the additive index migration commits exactly once")
        (is (true? (get-in db-value
                           [:schema :example.event/at :db/index])))
        (is (= [(java.util.Date. 1000)]
               (mapv :v (d/datoms db-value :avet :example.event/at)))
            "the existing value is backfilled into AVET")
        (is (= after-index (:t (:seon.db/db converged)))
            "converged writer admission emits no transaction")
        (doseq [[index candidate] (map-indexed vector incompatible)]
          (let [rejected
                (ensure (str "additive-index/incompatible-" index) candidate)]
            (is (false? (::protocol/success? rejected)) (pr-str rejected))))
        (is (= before-rejection (:max-tx (d/db connection)))
            "incompatible type and cardinality changes cannot advance the database"))
      (finally
        (writer/stop! server)
        (.delete socket-file)))))

(deftest installed-implicit-indexes-do-not-request-schema-removal
  (let [database-name (str "writer-implicit-index-" (random-uuid))
        socket-file (File. "tmp" (str database-name ".sock"))
        server
        (writer-test/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path (.getAbsolutePath socket-file)})
        runtime (::writer/runtime server)]
    (try
      (let [admitted
            (writer/handle-request
             runtime
             (protocol/ensure-database-request
              {::protocol/request-id "implicit-index/initialization"
               ::protocol/database-name database-name
               ::protocol/backend :memory
               :seon.db/initialization initialization}))
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            db-value (d/db connection)
            declarations
            (#'writer/compile-schema-declarations
             db-value (:seon.db/program initialization)
             #{:seon.agent/id :seon.db/user})]
        (is (::protocol/success? admitted) (pr-str admitted))
        (is (seq (d/datoms db-value :avet :seon.agent/id))
            "identity values are implicitly indexed")
        (is (empty? declarations)
            "canonical identity and ref forms never request index removal"))
      (finally
        (writer/stop! server)
        (.delete socket-file)))))

(deftest initialization-installs-recursively-aliased-config-attributes
  (let [database-name (str "writer-schema-alias-" (random-uuid))
        socket-file (File. "tmp" (str database-name ".sock"))
        server
        (writer-test/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path (.getAbsolutePath socket-file)})
        runtime (::writer/runtime server)
        sha-256 (apply str (repeat 64 "a"))
        aliased-initialization
        (-> initialization
            (update :seon.db/attributes conj
                    :seon.config.render-context/sha-256)
            (update :seon.db/program into
                    [{:seon.schema/key :seon.content-hash/digest
                      :seon.schema/form "[:string {:min 64 :max 64}]"}
                     {:seon.schema/key :seon.config.render-context/digest
                      :seon.schema/form ":seon.content-hash/digest"}
                     {:seon.schema/key :seon.config.render-context/sha-256
                      :seon.schema/form
                      ":seon.config.render-context/digest"}])
            (update-in [:seon.db/initial-data 0]
                       assoc
                       :seon.config.render-context/sha-256
                       sha-256))]
    (try
      (let [response
            (writer/handle-request
             runtime
             (protocol/ensure-database-request
              {::protocol/request-id "schema-alias/initialization"
               ::protocol/database-name database-name
               ::protocol/backend :memory
               :seon.db/initialization aliased-initialization}))
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            db-value (d/db connection)]
        (is (::protocol/success? response) (pr-str response))
        (is (= :db.type/string
               (get-in (:schema db-value)
                       [:seon.config.render-context/sha-256
                        :db/valueType])))
        (is (= #{[sha-256]}
               (d/q
                '[:find ?digest
                  :where
                  [_ :seon.config.render-context/sha-256 ?digest]]
                db-value))))
      (finally
        (writer/stop! server)
        (.delete socket-file)))))

(deftest failed-or-branch-initialization-never-publishes-a-writing-route
  (let [database-name (str "writer-initialization-main-" (random-uuid))
        failed-name (str "writer-initialization-failed-" (random-uuid))
        branch-name (str "writer-initialization-branch-" (random-uuid))
        socket-file (File. "tmp" (str database-name ".sock"))
        server
        (writer-test/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path (.getAbsolutePath socket-file)})
        runtime (::writer/runtime server)
        main
        (registry/lookup-connection
         {::registry/database-name (keyword database-name)})
        branch :experiment/initialization
        branch-connection-id
        (assoc (::registry/connection-id main) 1 branch)
        invalid-initialization
        (assoc initialization :seon.db/program [])]
    (try
      (let [failed
            (writer/handle-request
             runtime
             (protocol/ensure-database-request
              {::protocol/request-id "initialization/failed"
               ::protocol/database-name failed-name
               ::protocol/backend :memory
               :seon.db/initialization invalid-initialization}))]
        (is (false? (::protocol/success? failed)))
        (is (nil?
             (::registry/conn
              (registry/resolve-connection
               {::registry/database-name (keyword failed-name)})))
            "a failed fresh initialization is not published"))

      (d/branch! (::registry/conn main) :db branch)
      (let [before
            (branch/head
             (d/branch-as-db (::registry/conn main) branch))
            rejected
            (writer/handle-request
             runtime
             (protocol/ensure-database-request
              {::protocol/request-id "initialization/branch"
               ::protocol/database-name branch-name
               ::protocol/backend :memory
               ::branch/connection-id branch-connection-id
               :seon.db/initialization initialization}))
            after
            (branch/head
             (d/branch-as-db (::registry/conn main) branch))]
        (is (false? (::protocol/success? rejected)))
        (is (= before after) "branch initialization cannot advance its head")
        (is (nil?
             (::registry/conn
              (registry/resolve-connection
               {::registry/database-name (keyword branch-name)})))))
      (finally
        (writer/stop! server)
        (.delete socket-file)))))

(deftest ensuring-an-absent-native-branch-reports-branch-missing
  (let [database-name (str "writer-missing-main-" (random-uuid))
        branch-name (str "writer-missing-branch-" (random-uuid))
        socket-file (File. "tmp" (str database-name ".sock"))
        server
        (writer-test/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path (.getAbsolutePath socket-file)})
        runtime (::writer/runtime server)
        main
        (registry/resolve-connection
         {::registry/database-name (keyword database-name)})
        connection-id
        (assoc (::registry/connection-id main) 1 :experiment/absent)]
    (try
      (let [response
            (writer/handle-request
             runtime
             (protocol/ensure-database-request
              {::protocol/request-id "branch/absent"
               ::protocol/database-name branch-name
               ::protocol/backend :memory
               ::branch/connection-id connection-id}))]
        (is (false? (::protocol/success? response)))
        (is (= protocol/branch-missing-error
               (::protocol/error-kind response))
            (pr-str response)))
      (finally
        (writer/stop! server)
        (.delete socket-file)))))

(deftest initialization-installs-attributes-from-entity-schema-forms
  (let [database-name (str "writer-entity-schema-" (random-uuid))
        socket-file (File. "tmp" (str database-name ".sock"))
        server
        (writer-test/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path (.getAbsolutePath socket-file)})
        runtime (::writer/runtime server)
        initialization
        (-> initialization
            (assoc :seon.db/attributes [])
            (update :seon.db/program into
                    [{:seon.schema/key :example/id
                      :seon.schema/form
                      "[:and {:seon.db/identity true} :string]"}
                     {:seon.schema/key :example/entity
                      :seon.schema/form
                      "[:map {:seon.db/entity true} [:example/id :example/id]]"}
                     {:seon.schema/key :example/namespace
                      :seon.schema/form
                      "[:symbol {:seon.db/identity true}]"}
                     {:seon.schema/key :example/block-name
                      :seon.schema/form
                      "[:keyword {:seon.db/identity true}]"}
                     {:seon.schema/key :example/full-source
                      :seon.schema/form
                      "[:vector :example/namespace]"}
                     {:seon.schema/key :example/block
                      :seon.schema/form
                      (str "[:map {:seon.db/entity true} "
                           "[:example/block-name :example/block-name] "
                           "[:example/full-source {:optional true} :example/full-source]]")}])
            (assoc :seon.db/initial-data
                   [{:example/id "singleton"}
                    {:example/block-name :first
                     :example/full-source ['my.shared]}
                    {:example/block-name :second
                     :example/full-source ['my.shared]}]))]
    (try
      (let [response
            (writer/handle-request
             runtime
             (protocol/ensure-database-request
              {::protocol/request-id "entity-schema/initialization"
               ::protocol/database-name database-name
               ::protocol/backend :memory
               :seon.db/initialization initialization}))
            db-value
            (d/db (::registry/conn
                   (registry/resolve-connection
                    {::registry/database-name (keyword database-name)})))]
        (is (::protocol/success? response) (pr-str response))
        (is (= :db.unique/identity
               (get-in db-value [:schema :example/id :db/unique])))
        (is (= {:db/valueType :db.type/symbol
                :db/cardinality :db.cardinality/many}
               (select-keys (get-in db-value [:schema :example/full-source])
                            [:db/valueType :db/cardinality :db/unique])))
        (is (= 2
               (count
                (d/q '[:find ?block
                       :in $ ?namespace
                       :where [?block :example/full-source ?namespace]]
                     db-value 'my.shared)))
            "two stored blocks may select the same namespace symbol")
        (is (= "singleton" (:example/id (d/entity db-value
                                                   [:example/id "singleton"])))))
      (finally
        (writer/stop! server)
        (.delete socket-file)))))
