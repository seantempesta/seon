(ns seon.db.writer-initialization-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cognitect.transit :as transit]
            [datahike.api :as d]
            [seon.db.branch :as branch]
            [seon.db.executor :as executor]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.transport.uds :as uds]
            [seon.db.writer-test-support :as writer-test]
            [seon.db.writer :as writer]
            [seon.error :as error]
            [seon.schema :as schema])
  (:import [java.io ByteArrayOutputStream File]))

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
  {:inst "inst?"
   :seon.db/lookup-ref-value "[:or :string :uuid :keyword :symbol :int]"
   :seon.db/ref
   "[:or :int :string [:tuple :keyword :seon.db/lookup-ref-value]]"
   :seon.schema/key "[:keyword {:seon.db/identity true}]"
   :seon.schema/form ":string"
   :seon.schema/ns ":seon.db/ref"
   :seon.db.id/generator
   "[:enum :seon.db.id.generator/human-readable :seon.db.id.generator/compact]"
   :seon.db.id/legacy-value "[:string {:min 14 :max 14}]"
   :seon.db.id/compact-value
   "[:or :seon.db.id/legacy-value [:and :string [:re \"^[a-z][a-z0-9]{11}$\"]]]"
   :my.plan/id
   "[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]"
   :seon.db.initialization/id "[:string {:seon.db/identity true}]"
   :seon.db.initialization/fingerprint "[:string {:min 1}]"
   :seon.db.initialization/page-fingerprint "[:string {:min 1}]"
   :seon.db.initialization/identities ":string"
   :seon.db.initialization/page-count "[:int {:min 1}]"
   :seon.db.initialization/status
   "[:enum :seon.db.initialization.status/in-progress :seon.db.initialization.status/complete]"
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
   :seon.fn/spec ":string"
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
   :seon.db.initialization/config-manifest-digest
   "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
   :seon.db.initialization/page-rows 4
   :seon.db/attributes
   [:seon.agent/id :seon.db/user :seon.db/process :seon.db.process/id
    :seon.user/id :seon.ns/name :seon.ns/source :seon.ns/doc :seon.ns/summary
    :seon.fn/sym :seon.fn/ns
    :seon.fn/source :seon.fn/spec :seon.fn/doc :seon.fn/arglists
    :seon.fn/private?
    :seon.render/full?]
   :seon.db/program
   (into [{:seon.ns/name 'my.core
           :seon.ns/source "(ns my.core)"
           :seon.ns/doc "Own core behavior.\n\nMore detail."
           :seon.ns/summary "Own core behavior."}
          {:seon.fn/sym "my.core/answer"
           :seon.fn/ns [:seon.ns/name 'my.core]
           :seon.fn/source "(defn answer [] 42)"
           :seon.fn/spec "[:=> [:cat] :int]"
           :seon.fn/doc "Answer."
           :seon.fn/arglists "([])"
           :seon.fn/private? false}]
         (map (fn [[attribute form]]
                {:seon.schema/key attribute
                 :seon.schema/form form}))
         schema-forms)
   :seon.db/initial-data [{:seon.user/id "user"}]})

(defn- ensure-initialization!
  ([runtime database-name initialization]
   (ensure-initialization! runtime database-name initialization nil))
  ([runtime database-name initialization connection-id]
   (reduce
    (fn [_response page]
      (writer/handle-request
       runtime
       (protocol/ensure-database-request
        (cond-> {::protocol/request-id
                 (str "test-ensure/"
                      (:seon.db.initialization/fingerprint page)
                      "/"
                      (:seon.db.initialization/page-index page))
                 ::protocol/database-name database-name
                 ::protocol/backend :memory
                 :seon.db/initialization-page page}
          connection-id
          (assoc ::branch/connection-id connection-id)))))
    nil
    (protocol/initialization-pages initialization))))

(defn- ensure-page!
  [runtime database-name page]
  (writer/handle-request
   runtime
   (protocol/ensure-database-request
    {::protocol/request-id
     (str "test-page/" (:seon.db.initialization/page-index page))
     ::protocol/database-name database-name
     ::protocol/backend :memory
     :seon.db/initialization-page page})))

(defn- transit-bytes
  [value]
  (let [output (ByteArrayOutputStream.)]
    (transit/write (transit/writer output :json) value)
    (.size output)))

(defn- delete-tree!
  [root]
  (when (.exists ^File root)
    (run! (fn [^File file] (.delete file))
          (reverse (file-seq root)))))

(deftest config-manifest-digest-participates-in-page-fingerprint
  (let [changed
        (assoc initialization
               :seon.db.initialization/config-manifest-digest
               "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210")
        fingerprint
        (comp :seon.db.initialization/fingerprint
              first
              protocol/initialization-pages)]
    (is (not= (fingerprint initialization)
              (fingerprint changed)))))

(deftest bare-file-ensure-refuses-an-absent-store
  (let [database-name (str "writer-existing-" (random-uuid))
        absent-name (str "writer-absent-" (random-uuid))
        root (File. "tmp" (str "writer-ensure-path-" (random-uuid)))
        database-path (.getPath (File. root "existing/db"))
        absent-path (.getPath (File. root "absent/db"))
        socket-file (File. root "writer.sock")
        server
        (writer-test/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :file
          ::writer/database-path database-path
          ::writer/request-socket-path (.getPath socket-file)})
        runtime (::writer/runtime server)]
    (try
      (let [response
            (writer/handle-request
             runtime
             (protocol/ensure-database-request
              {::protocol/request-id "ensure/absent"
               ::protocol/database-name absent-name
               ::protocol/backend :file
               ::protocol/database-path absent-path}))]
        (is (false? (::protocol/success? response)) (pr-str response))
        (is (= protocol/not-found-error (::protocol/error-kind response))
            (pr-str response))
        (is (not (.exists (File. absent-path)))
            "an open-existing request cannot create a store"))
      (finally
        (writer/stop! server)
        (delete-tree! root)))))

(deftest bare-file-ensure-refuses-a-known-name-at-another-path
  (let [database-name (str "writer-known-" (random-uuid))
        root (File. "tmp" (str "writer-known-path-" (random-uuid)))
        database-path (.getPath (File. root "existing/db"))
        wrong-path (.getPath (File. root "wrong/db"))
        socket-file (File. root "writer.sock")
        server
        (writer-test/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :file
          ::writer/database-path database-path
          ::writer/request-socket-path (.getPath socket-file)})
        runtime (::writer/runtime server)]
    (try
      (let [response
            (writer/handle-request
             runtime
             (protocol/ensure-database-request
              {::protocol/request-id "ensure/wrong-path"
               ::protocol/database-name database-name
               ::protocol/backend :file
               ::protocol/database-path wrong-path}))]
        (is (false? (::protocol/success? response)) (pr-str response))
        (is (= :seon.db.registry.error/connection-id-conflict
               (:seon.error/kind response))
            (pr-str response))
        (is (not (.exists (File. wrong-path)))
            "a known logical name cannot create or move to another store"))
      (finally
        (writer/stop! server)
        (delete-tree! root)))))

(deftest ten-times-population-stays-well-below-frame-ceiling
  (let [schema-rows
        (filterv #(contains? % :seon.schema/key)
                 (:seon.db/program initialization))
        ordinary-rows
        (filterv #(not (contains? % :seon.schema/key))
                 (:seon.db/program initialization))
        synthetic-schema-rows
        (into []
              (map (fn [index]
                     {:seon.schema/key
                      (keyword "synthetic.schema" (str "value-" index))
                      :seon.schema/form ":string"}))
              (range (* 10 (count schema-rows))))
        synthetic-program-rows
        (into []
              (mapcat
               (fn [index]
                 (let [namespace-name
                       (symbol (str "my.synthetic." index))]
                   [{:seon.ns/name namespace-name
                     :seon.ns/source (str "(ns " namespace-name ")")}
                    {:seon.fn/sym (str namespace-name "/answer")
                     :seon.fn/ns [:seon.ns/name namespace-name]
                     :seon.fn/source "(defn answer [] 42)"}])))
              (range (* 5 (count ordinary-rows))))
        synthetic
        (-> initialization
            (assoc :seon.db.initialization/page-rows 64)
            (assoc :seon.db/program
                   (into (vec schema-rows)
                         (concat synthetic-schema-rows
                                 synthetic-program-rows)))
            (assoc :seon.db/initial-data
                   (mapv (fn [index]
                           {:seon.user/id (str "user-" index)})
                         (range 10))))
        pages (protocol/initialization-pages synthetic)
        requests
        (mapv
         (fn [page]
           (protocol/ensure-database-request
            {::protocol/request-id
             (str "frame/" (:seon.db.initialization/page-index page))
             ::protocol/database-name "synthetic"
             ::protocol/backend :memory
             :seon.db/initialization-page page}))
         pages)
        sizes (mapv transit-bytes requests)]
    (is (< (apply max sizes) (* 1024 1024))
        (pr-str {:maximum-page-bytes (apply max sizes)
                 :frame-ceiling protocol/maximum-frame-bytes}))
    (is (every? #(< % protocol/maximum-frame-bytes) sizes))
    (is (> (count pages) 10)
        "population growth creates more bounded pages, not larger frames")))

(deftest schema-pages-commit-references-before-generated-identities
  (let [pages (protocol/initialization-pages initialization)
        ordered-keys
        (into []
              (comp
               (filter #(= :seon.db.initialization.phase/schema
                           (:seon.db.initialization/phase %)))
               (mapcat :seon.db/program)
               (map :seon.schema/key))
              pages)
        positions (zipmap ordered-keys (range))]
    (is (< (positions :seon.db.id/legacy-value)
           (positions :seon.db.id/compact-value)
           (positions :my.plan/id))
        (pr-str ordered-keys))))

(deftest interrupted-page-prefix-is-unavailable-until-completion
  (let [database-name (str "writer-interrupted-seed-" (random-uuid))
        socket-file (File. "tmp" (str database-name ".sock"))
        server
        (writer-test/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path (.getAbsolutePath socket-file)})
        runtime (::writer/runtime server)
        pages (protocol/initialization-pages initialization)
        split-index (quot (count pages) 2)
        prefix (subvec pages 0 split-index)
        suffix (subvec pages split-index)
        transport
        (#'writer/transport-connection
         {::uds/close! (fn [] nil) ::uds/send! (fn [_message] nil)})]
    (try
      (doseq [page prefix]
        (is (::protocol/success?
             (ensure-page! runtime database-name page))))
      (let [connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            db-value (d/db connection)
            completion (promise)]
        (is (= :seon.db.initialization.status/in-progress
               (:seon.db.initialization/status
                (d/entity db-value
                          [:seon.db.initialization/id "database"]))))
        (writer/handle-request!
         runtime transport
         (protocol/acquire-database-request
          {::protocol/request-id "interrupted/acquire"
           ::protocol/database-name database-name})
         #(deliver completion %))
        (let [rejected (deref completion 5000 ::not-delivered)]
          (is (false? (::protocol/success? rejected)) (pr-str rejected))
          (is (= protocol/initializer-error
                 (::protocol/error-kind rejected)))))
      (doseq [page suffix]
        (is (::protocol/success?
             (ensure-page! runtime database-name page))))
      (let [db-value
            (d/db
             (::registry/conn
              (registry/lookup-connection
               {::registry/database-name (keyword database-name)})))]
        (is (= :seon.db.initialization.status/complete
               (:seon.db.initialization/status
                (d/entity db-value
                          [:seon.db.initialization/id "database"]))))
        (is (= "(defn answer [] 42)"
               (d/q '[:find ?source .
                      :where
                      [?function :seon.fn/sym "my.core/answer"]
                      [?function :seon.fn/source ?source]]
                    db-value))))
      (finally
        (#'writer/close-transport-connection! runtime transport)
        (writer/stop! server)
        (.delete socket-file)))))

(defn- initialized-population
  [db-value]
  {:schemas
   (d/q '[:find ?key ?form
          :where
          [?schema :seon.schema/key ?key]
          [?schema :seon.schema/form ?form]]
        db-value)
   :namespaces
   (d/q '[:find ?name ?source
          :where
          [?namespace :seon.ns/name ?name]
          [?namespace :seon.ns/source ?source]]
        db-value)
   :functions
   (d/q '[:find ?sym ?source ?spec
          :where
          [?function :seon.fn/sym ?sym]
          [?function :seon.fn/source ?source]
          [(get-else $ ?function :seon.fn/spec "") ?spec]]
        db-value)
   :initial-users
   (d/q '[:find [?id ...] :where [_ :seon.user/id ?id]] db-value)
   :installed-schema
   (into {}
         (comp
          (filter (comp qualified-keyword? key))
          (map (fn [[attribute declaration]]
                 [attribute
                  (select-keys declaration
                               [:db/valueType :db/cardinality :db/unique
                                :db/isComponent :db/index])])))
         (:schema db-value))})

(deftest page-count-does-not-change-the-initialized-population
  (let [database-name (str "writer-page-parity-" (random-uuid))
        single-name (str database-name "-single")
        paged-name (str database-name "-paged")
        socket-file (File. "tmp" (str database-name ".sock"))
        server
        (writer-test/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path (.getAbsolutePath socket-file)})
        runtime (::writer/runtime server)]
    (try
      (is (::protocol/success?
           (ensure-initialization!
            runtime single-name
            (assoc initialization
                   :seon.db.initialization/page-rows 100000))))
      (is (::protocol/success?
           (ensure-initialization!
            runtime paged-name
            (assoc initialization
                   :seon.db.initialization/page-rows 4))))
      (let [single
            (d/db
             (::registry/conn
              (registry/lookup-connection
               {::registry/database-name (keyword single-name)})))
            paged
            (d/db
             (::registry/conn
              (registry/lookup-connection
               {::registry/database-name (keyword paged-name)})))]
        (is (= (initialized-population single)
               (initialized-population paged))
            "bounded N-page initialization equals the large-page population"))
      (finally
        (writer/stop! server)
        (.delete socket-file)))))

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
        (fn [_request-id initialization]
          (ensure-initialization! runtime database-name initialization))
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
        (is (< before after-first)
            "fresh admission advances through bounded page transactions")
        (is (< after-first after-upgrade)
            "a populated database admits a changed page population")
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
        (let [schema-rows
              (d/q
               '[:find ?key ?form (pull ?tx ?provenance-pattern)
                 :in $ ?provenance-pattern
                 :where
                 [?schema :seon.schema/key ?key]
                 [?schema :seon.schema/form ?form ?tx]]
               (d/db connection)
               schema/asserting-transaction-provenance-pattern)
              function-contract-rows
              (d/q
               '[:find ?sym ?spec (pull ?tx ?provenance-pattern)
                 :in $ ?provenance-pattern
                 :where
                 [?function :seon.fn/sym ?sym]
                 [?function :seon.fn/spec ?spec ?tx]]
               (d/db connection)
               schema/asserting-transaction-provenance-pattern)
              projection
              (schema/projection-from-rows
               {:seon.schema/schema-rows schema-rows
                :seon.schema/function-contract-rows
                function-contract-rows})]
          (is (= :core
                 (get-in projection
                         [:seon.schema.projection/schema-admissions
                          :seon.db/process
                          :seon.schema.admission/source]))
              "fresh writer boot schema rows retain recognizable provenance")
          (is (= :core
                 (get-in projection
                         [:seon.schema.projection/function-admissions
                          'my.core/answer
                          :seon.schema.admission/source]))
              "fresh writer boot contract rows retain recognizable provenance"))
        (let [before-spec-tx
              (d/q '[:find ?tx .
                     :where
                     [?function :seon.fn/sym "my.core/answer"]
                     [?function :seon.fn/spec _ ?tx]]
                   (d/db connection))
              _ (d/transact
                 connection
                 {:tx-data
                  [{:seon.fn/sym "my.core/answer"
                    :seon.fn/source "(defn answer [] 43)"
                    :seon.fn/spec "[:=> [:cat] :int]"}]
                  :tx-meta
                  {:seon.db/user [:seon.user/id "user"]
                   :seon.db/process
                   [:seon.db.process/id :seon.db.process/repl]}})
              database (d/db connection)
              after-spec-tx
              (d/q '[:find ?tx .
                     :where
                     [?function :seon.fn/sym "my.core/answer"]
                     [?function :seon.fn/spec _ ?tx]]
                   database)
              contract-rows
              (d/q
               '[:find ?sym ?spec (pull ?tx ?provenance-pattern)
                 :in $ ?provenance-pattern
                 :where
                 [?function :seon.fn/sym ?sym]
                 [?function :seon.fn/spec ?spec ?tx]]
               database schema/asserting-transaction-provenance-pattern)
              source-rows
              (d/q
               '[:find ?sym ?source (pull ?tx ?provenance-pattern)
                 :in $ ?provenance-pattern
                 :where
                 [?function :seon.fn/sym ?sym]
                 [?function :seon.fn/source ?source ?tx]]
               database schema/asserting-transaction-provenance-pattern)
              projection
              (schema/projection-from-rows
               {:seon.schema/schema-rows []
                :seon.schema/function-contract-rows contract-rows
                :seon.schema/function-source-rows source-rows})]
          (is (= before-spec-tx after-spec-tx)
              "reasserting an identical spec does not replace its datom")
          (is (= :core
                 (get-in projection
                         [:seon.schema.projection/function-admissions
                          'my.core/answer
                          :seon.schema.admission/source])))
          (is (= :agent
                 (get-in projection
                         [:seon.schema.projection/function-source-admissions
                          'my.core/answer
                          :seon.schema.admission/source])))
          (is (= :agent (error/fault-for 'my.core/answer projection))
              "source assertion provenance, not stale spec provenance, owns trust"))
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
        (fn [_request-id initialization]
          (ensure-initialization! runtime database-name initialization))
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
        (is (< before-index after-index)
            "the additive index migration advances through bounded pages")
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
        (is (< before-rejection (:max-tx (d/db connection)))
            "a rejected later page leaves an explicit incomplete prefix")
        (is (= :seon.db.initialization.status/in-progress
               (:seon.db.initialization/status
                (d/entity (d/db connection)
                          [:seon.db.initialization/id "database"])))
            "a rejected prefix cannot be mistaken for a completed seed"))
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
            (ensure-initialization! runtime database-name initialization)
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
            (ensure-initialization!
             runtime database-name aliased-initialization)
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
        invalid-page
        (assoc (first (protocol/initialization-pages initialization))
               :seon.db/program [])]
    (try
      (let [failed
            (writer/handle-request
             runtime
             (protocol/ensure-database-request
              {::protocol/request-id "initialization/failed"
               ::protocol/database-name failed-name
               ::protocol/backend :memory
               :seon.db/initialization-page invalid-page}))]
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
               :seon.db/initialization-page
               (first (protocol/initialization-pages initialization))}))
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
            (ensure-initialization! runtime database-name initialization)
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
