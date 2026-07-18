(ns seon.db.writer-initialization-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db.branch :as branch]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.writer :as writer])
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

(def schema-forms
  {:seon.db/lookup-ref-value "[:or :string :uuid :keyword :int]"
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
   :seon.ns/name "[:keyword {:seon.db/identity true}]"
   :seon.ns/source ":string"
   :seon.fn/sym "[:string {:seon.db/identity true}]"
   :seon.fn/ns ":seon.db/ref"
   :seon.fn/source ":string"
   :seon.fn/doc ":string"
   :seon.fn/arglists ":string"
   :seon.fn/private? ":boolean"
   :seon.fn/agent-facing? ":boolean"
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
    :seon.user/id :seon.ns/name :seon.ns/source :seon.fn/sym :seon.fn/ns
    :seon.fn/source :seon.fn/doc :seon.fn/arglists :seon.fn/private?
    :seon.fn/agent-facing? :seon.render/full?]
   :seon.db/program
   (into [{:seon.ns/name :my.core
           :seon.ns/source "(ns my.core)"}
          {:seon.fn/sym "my.core/answer"
           :seon.fn/ns [:seon.ns/name :my.core]
           :seon.fn/source "(defn answer [] 42)"
           :seon.fn/doc "Answer."
           :seon.fn/arglists "([])"
           :seon.fn/private? false
           :seon.fn/agent-facing? false}]
         (map (fn [[attribute form]]
                {:seon.schema/key attribute
                 :seon.schema/form form}))
         schema-forms)
   :seon.db/initial-data [{:seon.user/id "user"}]})

(deftest ensure-admits-one-program-and-converges-without-another-transaction
  (let [database-name (str "writer-initialization-" (random-uuid))
        socket-file (File. "tmp" (str database-name ".sock"))
        server
        (writer/start!
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
        (is (not (contains? (:schema (d/db connection))
                            :datahike.index-page/cursor))
            "request fields are not installed as Datahike attributes")
        (is (= #{:seon.db.process/boot
                 :seon.db.process/config
                 :seon.db.process/repl}
               (set
                (d/q '[:find [?id ...]
                       :where [_ :seon.db.process/id ?id]]
                     (d/db connection))))))
      (finally
        (writer/stop! server)
        (.delete socket-file)))))

(deftest failed-or-branch-initialization-never-publishes-a-writing-route
  (let [database-name (str "writer-initialization-main-" (random-uuid))
        failed-name (str "writer-initialization-failed-" (random-uuid))
        branch-name (str "writer-initialization-branch-" (random-uuid))
        socket-file (File. "tmp" (str database-name ".sock"))
        server
        (writer/start!
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
