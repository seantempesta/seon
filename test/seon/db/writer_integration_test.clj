(ns seon.db.writer-integration-test
  "End-to-end canonical writer request and addressed-event tests."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db.backend :as backend]
            [seon.db.coordinate :as coordinate]
            [seon.db.executor :as executor]
            [seon.db.id :as id]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer]
            [seon.embed :as embed]
            [seon.schema :as schema])
  (:import [java.io File]
           [java.nio.channels SocketChannel]
           [java.util.concurrent CountDownLatch TimeUnit]))

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
    ::writer/embedding-enabled? false
    ::writer/embedding-entity-ids (fn [_db-value] [])
    ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
    ::writer/embedding-assertions (fn [_inputs] [])
    ::writer/revalidate-embedding-assertions (fn [_db-value _assertions] [])
    ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
    ::writer/knn (fn [_db-value _vector _k _eids] [])}))

(defn- call!
  [channel request]
  (uds/call! {::uds/channel channel ::uds/message request}))

(deftest writer-failures-preserve-the-existing-seon-error-kind
  (let [throwable (ex-info "unknown attribute" {:seon.error/kind :user-input})
        response (#'writer/request-failure-response throwable)
        member (#'writer/member-failure throwable)]
    (is (= :user-input (:seon.error/kind response)))
    (is (= :user-input (:seon.error/kind member)))
    (is (protocol/valid-response?
         (assoc member ::protocol/request-id "query/user-input")))))

(defn- acquire!
  [channel database-name label]
  (let [head
        (call! channel
               (protocol/resolve-head-request
                {::protocol/request-id (str label "/head")
                 ::protocol/database-name database-name}))]
    (call! channel
           (protocol/acquire-database-request
            {::protocol/request-id (str label "/acquire")
             ::protocol/database-name database-name
             ::protocol/attachment (::protocol/attachment head)}))))

(defn- await-route!
  [database-name present?]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (let [present-now?
            (boolean
             (::registry/conn
              (registry/resolve-connection
               {::registry/database-name (keyword database-name)})))]
        (cond
          (= present? present-now?) true
          (> (System/currentTimeMillis) deadline) false
          :else (do (Thread/sleep 10) (recur)))))))

(deftest capability-discovery-and-head-resolution-return-only-portable-data
  (let [database-name (str "writer-control-" (random-uuid))
        request-path (socket-path "control-request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
        ^SocketChannel request-channel (uds/connect! request-path)]
    (try
      (let [capability-request
            (protocol/capabilities-request
             {::protocol/request-id "control/capabilities"})
            head-request
            (protocol/resolve-head-request
             {::protocol/request-id "control/head"
              ::protocol/database-name database-name})
            capabilities (call! request-channel capability-request)
            head (call! request-channel head-request)
            cancellation
            (call! request-channel
                   (protocol/cancel-request
                    {::protocol/request-id "control/cancel"
                     ::protocol/target-request-id "control/not-running"}))
            unknown
            (call! request-channel
                   (protocol/resolve-head-request
                    {::protocol/request-id "control/unknown"
                     ::protocol/database-name "not-open"}))]
        (is (every? protocol/valid-request?
                    [capability-request head-request]))
        (is (every? protocol/valid-response?
                    [capabilities head cancellation unknown]))
        (is (= "control/capabilities" (::protocol/request-id capabilities)))
        (is (= (assoc (d/capabilities)
                      ::protocol/version protocol/current-version
                      ::protocol/maximum-frame-bytes
                      protocol/maximum-frame-bytes)
               (::protocol/capabilities capabilities)))
        (is (= "control/head" (::protocol/request-id head)))
        (is (= database-name (::protocol/database-name head)))
        (is (= (coordinate/attachment (::protocol/coordinate head))
               (::protocol/attachment head)))
        (is (= {::protocol/success? true
                ::protocol/request-id "control/cancel"
                ::protocol/target-request-id "control/not-running"
                ::protocol/canceled? false
                ::protocol/running? false}
               cancellation))
        (is (= "control/unknown" (::protocol/request-id unknown)))
        (is (= protocol/not-found-error (::protocol/error-kind unknown)))
        (is (not-any? #(or (instance? clojure.lang.IDeref %)
                           (instance? Thread %)
                           (instance? Throwable %))
                      (tree-seq coll? seq [capabilities head unknown]))))
      (finally
        (try (.close request-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

(deftest physical-connections-own-exact-database-access-and-release
  (let [database-name (str "writer-acquisition-" (random-uuid))
        request-path (socket-path "acquisition-request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
        ^SocketChannel a (uds/connect! request-path)
        ^SocketChannel b (uds/connect! request-path)]
    (try
      (let [head
            (call! a
                   (protocol/resolve-head-request
                    {::protocol/request-id "acquisition/head"
                     ::protocol/database-name database-name}))
            query-input
            {::protocol/database-name database-name
             ::protocol/attachment (::protocol/attachment head)
             ::protocol/coordinate (::protocol/coordinate head)
             ::protocol/query-form
             '[:find ?ident :where [?entity :db/ident ?ident]]
             ::protocol/arguments []}
            denied-read
            (call! a
                   (protocol/query-request
                    (assoc query-input
                           ::protocol/request-id "acquisition/denied-read")))
            denied-write
            (call! a
                   (protocol/transaction-request
                    {::protocol/request-id "acquisition/denied-write"
                     ::protocol/database-name database-name
                     ::protocol/transaction-data
                     [{:db/ident :acquisition/value
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one}]}))
            acquired-a (acquire! a database-name "acquisition/a")
            duplicate-a (acquire! a database-name "acquisition/a-duplicate")
            write
            (call! a
                   (protocol/transaction-request
                    {::protocol/request-id "acquisition/write"
                     ::protocol/database-name database-name
                     ::protocol/transaction-data
                     [{:db/ident :acquisition/value
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one}
                      {:acquisition/value "owned"}]}))
            current
            (assoc query-input ::protocol/coordinate (::protocol/coordinate write))
            denied-b
            (call! b
                   (protocol/query-request
                    (assoc current
                           ::protocol/request-id "acquisition/denied-b")))
            acquired-b (acquire! b database-name "acquisition/b")]
        (is (= protocol/not-found-error (::protocol/error-kind denied-read)))
        (is (= protocol/not-found-error (::protocol/error-kind denied-write)))
        (is (true? (::protocol/acquired? acquired-a)))
        (is (false? (::protocol/acquired? duplicate-a)))
        (is (= protocol/not-found-error (::protocol/error-kind denied-b)))
        (is (true? (::protocol/acquired? acquired-b)))
        (.close a)
        (is (await-route! database-name true)
            "a sibling physical connection retains the shared Datahike owner")
        (let [read-b
              (call! b
                     (protocol/query-request
                      (assoc current ::protocol/request-id "acquisition/read-b")))]
          (is (::protocol/success? read-b))
          (is (some #{[:acquisition/value]}
                    (:datahike.query/result read-b))))
        (.close b)
        (is (await-route! database-name false)
            "the last physical connection releases the shared indexes"))
      (finally
        (try (.close a) (catch Throwable _))
        (try (.close b) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

(deftest coordinate-pinned-reads-share-old-commit-and-preserve-datahike-shapes
  (let [database-name (str "writer-read-" (random-uuid))
        request-path (socket-path "read-request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/selected-processors 2
          ::writer/request-socket-path request-path})
        ^SocketChannel request-channel (uds/connect! request-path)]
    (try
      (acquire! request-channel database-name "read/session")
      (call! request-channel
             (protocol/transaction-request
              {::protocol/database-name database-name
               ::protocol/request-id "read/schema-and-data"
               ::protocol/transaction-data
               [{:db/ident :reader/id
                 :db/valueType :db.type/string
                 :db/cardinality :db.cardinality/one
                 :db/unique :db.unique/identity}
                {:db/ident :reader/score
                 :db/valueType :db.type/long
                 :db/cardinality :db.cardinality/one
                 :db/index true}
                {:reader/id "alice" :reader/score 1}
                {:reader/id "bob" :reader/score 2}]}))
      (let [head
            (call! request-channel
                   (protocol/resolve-head-request
                    {::protocol/request-id "read/head"
                     ::protocol/database-name database-name}))
            attachment (::protocol/attachment head)
            frozen (::protocol/coordinate head)
            advanced
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name database-name
                     ::protocol/request-id "read/advance"
                     ::protocol/transaction-data
                     [[:db/add [:reader/id "alice"] :reader/score 9]]}))
            temporal (::protocol/previous-coordinate advanced)
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            direct-temporal-reverse
            (d/index-page
             (d/as-of (d/db connection) (::coordinate/t temporal))
             {:index :avet
              :components [:reader/score]
              :direction :reverse
              :limit 20})
            direct-score-history
            (mapv (juxt :e :v :tx :added)
                  (d/datoms (d/history (d/db connection))
                            :avet :reader/score))
            query-input
            {::protocol/database-name database-name
             ::protocol/attachment attachment
             ::protocol/coordinate frozen
             ::protocol/query-form
             [:find '?id '?score
              :keys 'id 'score
              :where ['?e :reader/id '?id]
              ['?e :reader/score '?score]]
             ::protocol/arguments []}
            aggregate-form
            '[:find (count ?entity) . :where [?entity :reader/id]]
            owner
            (call! request-channel
                   (protocol/query-request
                    (assoc query-input ::protocol/request-id "read/query-1")))
            hit
            (call! request-channel
                   (protocol/query-request
                    (assoc query-input ::protocol/request-id "read/query-2")))
            aggregate
            (call! request-channel
                   (protocol/query-request
                    {::protocol/request-id "read/query-aggregate"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen
                     ::protocol/query-form aggregate-form
                     ::protocol/arguments []}))
            aggregate-many
            (call! request-channel
                   (protocol/execute-many-request
                    {::protocol/request-id "read/many-aggregate"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen
                     ::protocol/members
                     [{::protocol/operation protocol/query-operation
                       ::protocol/query-form aggregate-form
                       ::protocol/arguments []}]}))
            pull
            (call! request-channel
                   (protocol/pull-request
                    {::protocol/request-id "read/pull"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen
                     ::protocol/selector [:reader/id :reader/score]
                     ::protocol/entity-id [:reader/id "alice"]}))
            pulls
            (call! request-channel
                   (protocol/pull-many-request
                    {::protocol/request-id "read/pull-many"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen
                     ::protocol/selector [:reader/id :reader/score]
                     ::protocol/entity-ids [[:reader/id "alice"]
                                            [:reader/id "missing"]
                                            [:reader/id "bob"]]}))
            schema-response
            (call! request-channel
                   (protocol/schema-request
                    {::protocol/request-id "read/schema"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen}))
            first-page
            (call! request-channel
                   (protocol/index-page-request
                    {::protocol/request-id "read/index-first"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen
                     ::protocol/index :avet
                     ::protocol/prefix [:reader/score]
                     ::protocol/direction :forward
                     ::protocol/limit 1}))
            second-page
            (call! request-channel
                   (protocol/index-page-request
                    {::protocol/request-id "read/index-second"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen
                     ::protocol/index :avet
                     ::protocol/prefix [:reader/score]
                     ::protocol/direction :forward
                     ::protocol/limit 1
                     ::protocol/cursor (::protocol/cursor first-page)}))
            wrong-prefix-page
            (call! request-channel
                   (protocol/index-page-request
                    {::protocol/request-id "read/index-wrong-prefix"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen
                     ::protocol/index :avet
                     ::protocol/prefix [:reader/id]
                     ::protocol/direction :forward
                     ::protocol/limit 1
                     ::protocol/cursor (::protocol/cursor first-page)}))
            reverse-page
            (call! request-channel
                   (protocol/index-page-request
                    {::protocol/request-id "read/index-reverse"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen
                     ::protocol/index :avet
                     ::protocol/prefix [:reader/score]
                     ::protocol/direction :reverse
                     ::protocol/limit 2}))
            current-query
            (call! request-channel
                   (protocol/query-request
                    {::protocol/request-id "read/query-current"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate (::protocol/coordinate advanced)
                     ::protocol/query-form
                     '[:find ?score ?added
                       :where [?entity :reader/score ?score ?tx ?added]]
                     ::protocol/arguments []}))
            history-query
            (call! request-channel
                   (protocol/query-request
                    {::protocol/request-id "read/query-history"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate (::protocol/coordinate advanced)
                     ::protocol/query-form
                     '[:find ?score ?added
                       :where [?entity :reader/score ?score ?tx ?added]]
                     ::protocol/arguments []
                     ::protocol/history? true}))
            history-page
            (call! request-channel
                   (protocol/index-page-request
                    {::protocol/request-id "read/index-history"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate (::protocol/coordinate advanced)
                     ::protocol/index :avet
                     ::protocol/prefix [:reader/score]
                     ::protocol/direction :forward
                     ::protocol/limit 20
                     ::protocol/history? true}))
            temporal-query
            (call! request-channel
                   (protocol/query-request
                    (assoc query-input
                           ::protocol/request-id "read/query-temporal"
                           ::protocol/coordinate temporal)))
            temporal-history
            (call! request-channel
                   (protocol/query-request
                    {::protocol/request-id "read/query-temporal-history"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate temporal
                     ::protocol/query-form
                     '[:find ?score ?added
                       :where [?entity :reader/score ?score ?tx ?added]]
                     ::protocol/arguments []
                     ::protocol/history? true}))
            temporal-page
            (call! request-channel
                   (protocol/index-page-request
                    {::protocol/request-id "read/index-temporal"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate temporal
                     ::protocol/index :avet
                     ::protocol/prefix [:reader/score]
                     ::protocol/direction :forward
                     ::protocol/limit 20}))
            temporal-reverse-page
            (call! request-channel
                   (protocol/index-page-request
                    {::protocol/request-id "read/index-temporal-reverse"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate temporal
                     ::protocol/index :avet
                     ::protocol/prefix [:reader/score]
                     ::protocol/direction :reverse
                     ::protocol/limit 20}))
            temporal-schema
            (call! request-channel
                   (protocol/schema-request
                    {::protocol/request-id "read/schema-temporal"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate temporal}))
            future-query
            (call! request-channel
                   (protocol/query-request
                    (assoc query-input
                           ::protocol/request-id "read/query-future"
                           ::protocol/coordinate
                           (update (::protocol/coordinate advanced)
                                   ::coordinate/t inc))))
            temporal-many
            (call! request-channel
                   (protocol/execute-many-request
                    {::protocol/request-id "read/many-temporal"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate temporal
                     ::protocol/members
                     [{::protocol/operation protocol/pull-operation
                       ::protocol/selector [:reader/id :reader/score]
                       ::protocol/entity-id [:reader/id "alice"]}
                      {::protocol/operation protocol/query-operation
                       ::protocol/query-form (::protocol/query-form query-input)
                       ::protocol/arguments []}
                      {::protocol/operation protocol/index-page-operation
                       ::protocol/index :avet
                       ::protocol/prefix [:reader/score]
                       ::protocol/direction :reverse
                       ::protocol/limit 2}
                      {::protocol/operation protocol/schema-operation}]}))
            many
            (call! request-channel
                   (protocol/execute-many-request
                    {::protocol/request-id "read/many"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen
                     ::protocol/members
                     [{::protocol/operation protocol/pull-operation
                       ::protocol/selector [:reader/id :reader/score]
                       ::protocol/entity-id [:reader/id "bob"]}
                      {::protocol/operation protocol/query-operation
                       ::protocol/query-form (::protocol/query-form query-input)
                       ::protocol/arguments []}
                      {::protocol/operation protocol/pull-many-operation
                       ::protocol/selector [:reader/id]
                       ::protocol/entity-ids [[:reader/id "alice"]
                                              [:reader/id "bob"]]}
                      {::protocol/operation protocol/schema-operation}
                      {::protocol/operation protocol/index-page-operation
                       ::protocol/index :avet
                       ::protocol/prefix [:reader/score]
                       ::protocol/direction :forward
                       ::protocol/limit 2}]}))
            large-many
            (call! request-channel
                   (protocol/execute-many-request
                    {::protocol/request-id "read/many-64"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen
                     ::protocol/members
                     (vec
                      (repeat 64
                              {::protocol/operation protocol/pull-operation
                               ::protocol/selector [:reader/id]
                               ::protocol/entity-id [:reader/id "alice"]}))}))
            too-small-many
            (call! request-channel
                   (protocol/execute-many-request
                    {::protocol/request-id "read/many-too-small"
                     ::protocol/database-name database-name
                     ::protocol/attachment attachment
                     ::protocol/coordinate frozen
                     ::protocol/members
                     [{::protocol/operation protocol/pull-operation
                       ::protocol/selector [:reader/id]
                       ::protocol/entity-id [:reader/id "alice"]}]
                     :datahike.resource/max-result-weight 1}))]
        (is (every? protocol/valid-response?
                    [owner hit aggregate aggregate-many
                     pull pulls schema-response first-page second-page
                     wrong-prefix-page
                     reverse-page current-query history-query history-page
                     temporal-query temporal-history temporal-page
                     temporal-reverse-page
                     temporal-schema future-query temporal-many many large-many
                     too-small-many]))
        (is (= #{{:id "alice" :score 1} {:id "bob" :score 2}}
               (set (:datahike.query/result owner))
               (set (:datahike.query/result hit))))
        (is (= :datahike.cache.outcome/miss-owner
               (get-in owner [:datahike.query/cache-evidence
                              :datahike.cache/outcome])))
        (is (= :datahike.cache.outcome/hit
               (get-in hit [:datahike.query/cache-evidence
                            :datahike.cache/outcome])))
        (is (= #{:reader/id :reader/score}
               (:datahike.query/attribute-dependencies owner)
               (:datahike.query/attribute-dependencies hit)))
        (is (= 2 (:datahike.query/result aggregate)))
        (is (= 2
               (get-in aggregate-many
                       [::protocol/results 0 :datahike.query/result])))
        (is (= {:reader/id "alice" :reader/score 1}
               (::protocol/result pull)))
        (is (= [{:reader/id "alice" :reader/score 1}
                nil
                {:reader/id "bob" :reader/score 2}]
               (::protocol/result pulls)))
        (is (= {:reader/id "bob" :reader/score 2}
               (get-in many [::protocol/results 0 ::protocol/result])))
        (is (= #{"alice" "bob"}
               (set (map :id
                         (get-in many [::protocol/results 1
                                       :datahike.query/result])))))
        (is (= [{:reader/id "alice"} {:reader/id "bob"}]
               (get-in many [::protocol/results 2 ::protocol/result])))
        (is (contains? (::protocol/schema schema-response) :reader/score))
        (is (= [1] (mapv :seon.db/v (::protocol/datoms first-page))))
        (is (= [2] (mapv :seon.db/v (::protocol/datoms second-page))))
        (is (false? (::protocol/success? wrong-prefix-page)))
        (is (= protocol/protocol-error
               (::protocol/error-kind wrong-prefix-page)))
        (is (= [2 1] (mapv :seon.db/v (::protocol/datoms reverse-page))))
        (is (= #{[2 true] [9 true]}
               (set (:datahike.query/result current-query))))
        (is (= #{[1 true] [1 false] [2 true] [9 true]}
               (set (:datahike.query/result history-query))))
        (is (= #{[1 true] [1 false] [2 true] [9 true]}
               (set (map (juxt :seon.db/v :seon.db/added?)
                         (::protocol/datoms history-page)))))
        (is (= #{{:id "alice" :score 1} {:id "bob" :score 2}}
               (set (:datahike.query/result temporal-query))))
        (is (= :datahike.cache.outcome/miss-owner
               (get-in temporal-query
                       [:datahike.query/cache-evidence
                        :datahike.cache/outcome])))
        (is (= #{[1 true] [2 true]}
               (set (:datahike.query/result temporal-history))))
        (is (= [1 2] (mapv :seon.db/v (::protocol/datoms temporal-page))))
        (is (= 4 (count direct-score-history))
            (pr-str {:temporal temporal
                     :advanced (::protocol/coordinate advanced)
                     :score-history direct-score-history}))
        (is (= [2 1]
               (mapv :v (:datahike.index-page/datoms direct-temporal-reverse))))
        (is (= [2 1]
               (mapv :seon.db/v (::protocol/datoms temporal-reverse-page))))
        (is (= protocol/stale-coordinate-error
               (::protocol/error-kind future-query)))
        (is (= protocol/protocol-error
               (::protocol/error-kind temporal-schema)
               (get-in temporal-many
                       [::protocol/results 3 ::protocol/error-kind])))
        (is (= {:reader/id "alice" :reader/score 1}
               (get-in temporal-many [::protocol/results 0 ::protocol/result])))
        (is (= #{{:id "alice" :score 1} {:id "bob" :score 2}}
               (set (get-in temporal-many
                            [::protocol/results 1 :datahike.query/result]))))
        (is (= :datahike.cache.outcome/hit
               (get-in temporal-many
                       [::protocol/results 1
                        :datahike.query/cache-evidence
                        :datahike.cache/outcome])))
        (is (= [2 1]
               (mapv :seon.db/v
                     (get-in temporal-many
                             [::protocol/results 2 ::protocol/datoms]))))
        (is (contains? (get-in many [::protocol/results 3 ::protocol/schema])
                       :reader/score))
        (is (= [1 2]
               (mapv :seon.db/v
                     (get-in many [::protocol/results 4 ::protocol/datoms]))))
        (is (= 64 (count (::protocol/results large-many))))
        (is (every? ::protocol/success? (::protocol/results large-many)))
        (is (false? (::protocol/success? too-small-many)))
        (is (= protocol/database-error
               (::protocol/error-kind too-small-many)))
        (is (= frozen (::protocol/coordinate owner)
               (::protocol/coordinate hit)
               (::protocol/coordinate pull)
               (::protocol/coordinate pulls)
               (::protocol/coordinate many))))
      (finally
        (try (.close request-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

(deftest database-result-validation-rejects-host-owners-and-lazy-values
  (is (thrown? clojure.lang.ExceptionInfo
               (#'writer/materialize-result (future :host))))
  (is (thrown? clojure.lang.ExceptionInfo
               (#'writer/materialize-result (map identity [1 2]))))
  (let [value [{:bare-key "preserved"}]]
    (is (identical? value (#'writer/materialize-result value))))
  (is (thrown?
       clojure.lang.ExceptionInfo
       (#'writer/validate-read-input!
        {::protocol/operation protocol/query-operation
         ::protocol/query-form [:find '?value :in '$ '?input
                                :where ['?entity :value '?value]]
         ::protocol/arguments [(future :host)]})))
  (is (= [:find '?value :where ['?entity :value '?value]]
         (::protocol/query-form
          (#'writer/validate-read-input!
           {::protocol/operation protocol/query-operation
            ::protocol/query-form
            [:find '?value :where ['?entity :value '?value]]
           ::protocol/arguments []})))))

(deftest execute-many-result-weight-is-position-deterministic
  (let [point {::coordinate/database-id (random-uuid)
               ::coordinate/branch :main
               ::coordinate/commit-id (random-uuid)
               ::coordinate/t 536870912}
        request
        (protocol/execute-many-request
         {::protocol/request-id "many/weight-order"
          ::protocol/database-name "default"
          ::protocol/attachment (coordinate/attachment point)
          ::protocol/coordinate point
          ::protocol/members
          [{::protocol/operation protocol/pull-operation
            ::protocol/selector [:db/id]
            ::protocol/entity-id 1}
           {::protocol/operation protocol/pull-operation
            ::protocol/selector [:db/id]
            ::protocol/entity-id 2}]})
        initial (#'writer/execute-many-result-state request)
        placeholder-weight (::writer/result-placeholder-weight initial)
        small (protocol/success {::protocol/result "small"})
        large (protocol/success {::protocol/result (apply str (repeat 1024 "x"))})
        small-weight (d/shallow-weight-within small protocol/maximum-frame-bytes)
        tight-limit
        (max (::writer/result-weight initial)
             (+ (- (::writer/result-weight initial) placeholder-weight)
                small-weight 8))
        tight-request
        (assoc request :datahike.resource/max-result-weight tight-limit)
        initial (#'writer/execute-many-result-state tight-request)
        ordered
        (#'writer/accept-contiguous-execute-many-results
         (assoc initial ::writer/request tight-request
                ::writer/results [small large]))
        waiting
        (#'writer/accept-contiguous-execute-many-results
         (assoc-in (assoc initial ::writer/request tight-request)
                   [::writer/results 1] large))
        inverted
        (#'writer/accept-contiguous-execute-many-results
         (assoc-in waiting [::writer/results 0] small))]
    (is (= 0 (::writer/next-result-position waiting)))
    (is (= (select-keys ordered
                        [::writer/results ::writer/next-result-position
                         ::writer/result-weight
                         ::writer/result-limit-position])
           (select-keys inverted
                        [::writer/results ::writer/next-result-position
                         ::writer/result-weight
                         ::writer/result-limit-position])))
    (is (= 1 (::writer/result-limit-position ordered)))
    (is (= small (first (::writer/results ordered))))
    (is (= (var-get #'writer/execute-many-result-limit)
           (second (::writer/results ordered))))))

(deftest semantic-search-transitions-from-provider-to-coordinate-pinned-knn
  (let [database-name (str "writer-knn-" (random-uuid))
        request-path (socket-path "knn-request")
        observed (atom {})
        knn-calls (atom 0)
        knn-observations (atom [])
        deps (assoc (dependencies (fn [connection _database-name]
                                    (embed/install! connection)))
                    ::writer/query-vec
                    (fn [request]
                      (swap! observed assoc
                             :provider-thread (.getName (Thread/currentThread)))
                      (swap! observed update :queries (fnil conj []) request)
                      {:seon.embed/vector [1.0 0.0]})
                    ::writer/knn
                    (fn [db-value vector k eids]
                      (swap! knn-calls inc)
                      (swap! knn-observations conj
                             {:coordinate (coordinate/resolved db-value)
                              :native-index?
                              (boolean
                               (get-in db-value
                                       [:secondary-indices embed/index-ident]))
                              :materialized-secondary?
                              (:datahike.db/materialized-secondary-indices?
                               db-value)})
                      (swap! observed assoc
                             :knn-thread (.getName (Thread/currentThread))
                             :coordinate (coordinate/resolved db-value)
                             :vector vector :k k :eids eids)
                      [{:entity-id 42 :distance 0.25}]))
        server (writer/start!
                {::writer/dependencies deps
                 ::writer/database-name database-name
                 ::writer/backend :memory
                 ::writer/selected-processors 2
                 ::writer/request-socket-path request-path})
        runtime (::writer/runtime server)]
    (try
      (let [search!
            (fn [request-id target-name target-attachment target-coordinate query]
              (writer/handle-request
               runtime
               (protocol/knn-search-request
                {::protocol/request-id request-id
                 ::protocol/database-name target-name
                 ::protocol/attachment target-attachment
                 ::protocol/coordinate target-coordinate
                 ::protocol/query query
                 ::protocol/limit 3
                 ::protocol/entity-ids [42 99]})))
            initial (::protocol/coordinate
                     (writer/handle-request
                      runtime
                      (protocol/resolve-head-request
                       {::protocol/request-id "knn/head"
                        ::protocol/database-name database-name})))
            attachment (coordinate/attachment initial)
            embedding
            (into [(float 1.0)]
                  (repeat (dec embed/embedding-dim) (float 0.0)))
            frozen
            (::protocol/coordinate
             (writer/handle-request
              runtime
              (protocol/transaction-request
               {::protocol/database-name database-name
                ::protocol/request-id "knn/seed-versioned-index"
                ::protocol/transaction-data
                [{:db/ident :knn/versioned
                  :db/doc "versioned"
                  :seon/embedding embedding
                  :seon.embed/source-hash "knn-versioned"}]})))
            head-response
            (search! "knn/search-head" database-name attachment frozen
                     "nearest head")
            main-connection
            (::registry/conn
             (registry/resolve-connection
              {::registry/database-name (keyword database-name)}))
            native-head-result
            (embed/knn (d/db main-connection) embedding 1)
            advanced
            (writer/handle-request
             runtime
             (protocol/transaction-request
              {::protocol/database-name database-name
               ::protocol/request-id "knn/advance"
               ::protocol/transaction-data
               [{:db/ident :knn/advanced :db/doc "advanced"}]}))
            advanced-coordinate (::protocol/coordinate advanced)
            response (search! "knn/search" database-name attachment frozen
                              "nearest")
            temporal-response
            (search! "knn/search-temporal" database-name attachment
                     (::protocol/previous-coordinate advanced)
                     "nearest earlier")
            missing-response
            (search! "knn/search-missing" database-name attachment
                     (assoc frozen ::coordinate/commit-id
                                       (random-uuid))
                     "nearest missing")]
        (is (protocol/valid-response? head-response))
        (is (= 1 (count native-head-result))
            "current-head KNN leaves the attached native index usable")
        (is (protocol/valid-response? advanced)
            "preflight validation leaves the attached head connection usable")
        (is (protocol/valid-response? response))
        (is (= frozen (::protocol/coordinate response)
               (:coordinate @observed)))
        (is (= [{:seon.embed/eid 42 :seon.embed/distance 0.25}]
               (::protocol/hits response)))
        (is (= [{:seon.embed/text "nearest head"}
                {:seon.embed/text "nearest"}]
               (:queries @observed)))
        (is (= [[1.0 0.0] 3 '(42 99)]
               [(:vector @observed) (:k @observed) (:eids @observed)]))
        (is (= [true true]
               (mapv :native-index? @knn-observations))
            "current and historical KNN both receive the native index")
        (is (false?
             (boolean
              (:materialized-secondary? (first @knn-observations))))
            "current KNN uses the attached live database value")
        (is (true?
             (:materialized-secondary? (second @knn-observations)))
            "full historical KNN restores its committed native index")
        (is (.startsWith ^String (:knn-thread @observed) "seon-database-cpu-"))
        (is (not= (:provider-thread @observed) (:knn-thread @observed)))
        (is (= protocol/protocol-error
               (::protocol/error-kind temporal-response)))
        (is (= protocol/stale-coordinate-error
               (::protocol/error-kind missing-response)))
        (is (= 2 @knn-calls)
            "unsupported coordinates invoke neither provider nor secondary index"))
      (finally
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

(deftest semantic-search-rejects-a-force-discarded-coordinate-before-provider
  (let [database-name (str "writer-knn-force-" (random-uuid))
        request-path (socket-path "knn-force-request")
        provider-calls (atom 0)
        knn-calls (atom 0)
        server
        (writer/start!
         {::writer/dependencies
          (assoc (dependencies)
                 ::writer/query-vec
                 (fn [_request]
                   (swap! provider-calls inc)
                   {:seon.embed/vector [1.0 0.0]})
                 ::writer/knn
                 (fn [& _]
                   (swap! knn-calls inc)
                   []))
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
        runtime (::writer/runtime server)]
    (try
      (let [initial
            (::protocol/coordinate
             (writer/handle-request
              runtime
              (protocol/resolve-head-request
               {::protocol/request-id "knn-force/head"
                ::protocol/database-name database-name})))
            attachment (coordinate/attachment initial)
            search!
            (fn [request-id target-coordinate query]
              (writer/handle-request
               runtime
               (protocol/knn-search-request
                {::protocol/request-id request-id
                 ::protocol/database-name database-name
                 ::protocol/attachment attachment
                 ::protocol/coordinate target-coordinate
                 ::protocol/query query
                 ::protocol/limit 3})))
            selected
            (::protocol/coordinate
             (writer/handle-request
              runtime
              (protocol/transaction-request
               {::protocol/request-id "knn-force/selected"
                ::protocol/database-name database-name
                ::protocol/transaction-data
                [{:db/ident :knn.force/selected :db/doc "selected"}]})))
            advanced
            (::protocol/coordinate
             (writer/handle-request
              runtime
              (protocol/transaction-request
               {::protocol/request-id "knn-force/advanced"
                ::protocol/database-name database-name
                ::protocol/transaction-data
                [{:db/ident :knn.force/advanced :db/doc "advanced"}]})))
            sibling-name (str database-name "-sibling")
            created
            (writer/handle-request
             runtime
             (protocol/create-branch-request
              {::protocol/request-id "knn-force/create-sibling"
               ::protocol/source-database-name database-name
               ::protocol/target-database-name sibling-name
               ::protocol/source-coordinate selected
               ::protocol/expected-source-head advanced
               ::protocol/target-branch :experiment/knn-sibling}))
            sibling-coordinate
            (::protocol/coordinate
             (writer/handle-request
              runtime
              (protocol/transaction-request
               {::protocol/request-id "knn-force/sibling-write"
                ::protocol/database-name sibling-name
                ::protocol/transaction-data []})))
            sibling-response
            (search! "knn-force/search-sibling"
                     (assoc sibling-coordinate ::coordinate/branch :db)
                     "nearest sibling")
            connection
            (::registry/conn
             (registry/resolve-connection
              {::registry/database-name (keyword database-name)}))
            selected-db
            (d/commit-as-db connection (::coordinate/commit-id selected))
            _
            (try
              (d/force-branch!
               selected-db :db #{(::coordinate/commit-id selected)}
               {:expected-current-commit (::coordinate/commit-id advanced)})
              (finally
                (d/release-materialized-db selected-db)))
            released
            (writer/handle-request
             runtime
             (protocol/release-database-request
              {::protocol/request-id "knn-force/release"
               ::protocol/target-database-name database-name
               ::protocol/target-attachment attachment
               ::protocol/expected-target-head advanced}))
            reopened
            (writer/handle-request
             runtime
             (protocol/ensure-database-request
              {::protocol/request-id "knn-force/reopen"
               ::protocol/database-name database-name
               ::protocol/backend :memory
               ::coordinate/attachment attachment}))
            forced (::coordinate/coordinate reopened)
            response (search! "knn-force/search-discarded" advanced
                              "nearest discarded")]
        (is (true? (::protocol/success? created)))
        (is (= protocol/stale-coordinate-error
               (::protocol/error-kind sibling-response)))
        (is (true? (::protocol/released? released)))
        (is (true? (::protocol/success? reopened)))
        (is (not= advanced forced))
        (is (= (::coordinate/t selected) (::coordinate/t forced)))
        (is (= protocol/stale-coordinate-error
               (::protocol/error-kind response)))
        (is (zero? @provider-calls))
        (is (zero? @knn-calls)))
      (finally
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

(deftest disconnect-waits-until-a-running-read-relinquishes-the-generation
  (let [database-name (str "writer-read-release-" (random-uuid))
        request-path (socket-path "read-release-request")
        entered (java.util.concurrent.CountDownLatch. 1)
        finish (java.util.concurrent.CountDownLatch. 1)
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
        ^SocketChannel read-channel (uds/connect! request-path)
        ^SocketChannel control-channel (uds/connect! request-path)]
    (try
      (acquire! read-channel database-name "read-release/session")
      (let [head
            (call! control-channel
                   (protocol/resolve-head-request
                    {::protocol/request-id "read-release/head"
                     ::protocol/database-name database-name}))
            attachment (::protocol/attachment head)
            coordinate (::protocol/coordinate head)]
        (with-redefs [d/pull
                      (fn [_db-value _request]
                        (.countDown entered)
                        (.await finish)
                        {:read/value :finished})]
          (let [read-result
                (future
                  (try
                    (call! read-channel
                           (protocol/pull-request
                            {::protocol/request-id "read-release/pull"
                             ::protocol/database-name database-name
                             ::protocol/attachment attachment
                             ::protocol/coordinate coordinate
                             ::protocol/selector [:read/value]
                             ::protocol/entity-id 1}))
                    (catch Throwable _ :connection-closed)))]
            (is (.await entered 5 java.util.concurrent.TimeUnit/SECONDS))
            (let [foreign-cancel
                  (call! control-channel
                         (protocol/cancel-request
                          {::protocol/request-id "read-release/foreign-cancel"
                           ::protocol/target-request-id "read-release/pull"}))]
              (is (false? (::protocol/canceled? foreign-cancel)))
              (is (false? (::protocol/running? foreign-cancel)))
              (is (not (realized? read-result))
                  "one socket cannot observe or cancel another socket's work"))
            (.close read-channel)
            (Thread/sleep 100)
            (is (await-route! database-name true)
                "Datahike resources remain open while a read owns them")
            (.countDown finish)
            (is (await-route! database-name false)
                "the final socket release removes the route after the read")
            @read-result)))
      (finally
        (.countDown finish)
        (try (.close read-channel) (catch Throwable _))
        (try (.close control-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

(deftest callback-requests-reject-active-duplicates-and-reuse-cleanly
  (let [database-name (str "writer-callback-reuse-" (random-uuid))
        request-path (socket-path "callback-reuse-request")
        entered (java.util.concurrent.CountDownLatch. 1)
        finish (java.util.concurrent.CountDownLatch. 1)
        calls (atom 0)
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
        runtime (::writer/runtime server)
        ^SocketChannel request-channel (uds/connect! request-path)]
    (try
      (let [head
            (call! request-channel
                   (protocol/resolve-head-request
                    {::protocol/request-id "callback-reuse/head"
                     ::protocol/database-name database-name}))
            request
            (protocol/pull-request
             {::protocol/request-id "callback-reuse/read"
              ::protocol/database-name database-name
              ::protocol/attachment (::protocol/attachment head)
              ::protocol/coordinate (::protocol/coordinate head)
              ::protocol/selector [:callback/value]
              ::protocol/entity-id 1})
            first-response (promise)
            duplicate-response (promise)
            reused-response (promise)]
        (with-redefs [d/pull
                      (fn [_db-value _request]
                        (let [call (swap! calls inc)]
                          (when (= 1 call)
                            (.countDown entered)
                            (.await finish))
                          {:callback/value call}))]
          (writer/handle-request! runtime request #(deliver first-response %))
          (is (.await entered 5 java.util.concurrent.TimeUnit/SECONDS))
          (writer/handle-request! runtime request #(deliver duplicate-response %))
          (is (= protocol/request-conflict-error
                 (::protocol/error-kind
                  (deref duplicate-response 1000 ::not-delivered))))
          (is (= 1 @calls) "the duplicate never reaches Datahike")
          (.countDown finish)
          (is (= {:callback/value 1}
                 (::protocol/result (deref first-response 5000 ::not-delivered))))
          (writer/handle-request! runtime request #(deliver reused-response %))
          (is (= {:callback/value 2}
                 (::protocol/result (deref reused-response 5000 ::not-delivered))))
          (is (= 2 @calls))
          (is (empty? @(::writer/active-requests runtime)))))
      (finally
        (.countDown finish)
        (try (.close request-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

(deftest writer-stop-surfaces-release-failure-and-retains-database-identity
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})
        database-name (str "writer-release-failure-" (random-uuid))
        database-keyword (keyword database-name)
        request-path (socket-path "release-failure-request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
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
        nil))))

(deftest writer-stop-retains-authority-until-request-workers-stop
  (let [executor-stops (atom 0)
        database-lists (atom 0)
        result
        (with-redefs [uds/close-request-server!
                      (fn [_]
                        {::uds/graceful? false
                         ::uds/forced-connections 1
                         ::uds/selector-stopped? true
                         ::uds/workers-stopped? false
                         ::uds/cleanup-stopped? true})
                      executor/stop! (fn [_] (swap! executor-stops inc))
                      registry/list-databases
                      (fn [_]
                        (swap! database-lists inc)
                        {::registry/databases []})]
          (writer/stop! {::writer/request-server ::request-server
                         ::writer/executor ::executor}))]
    (is (= {::writer/stopped? false ::writer/release-results []} result))
    (is (schema/valid-candidate-value? ::writer/stop-response result))
    (is (zero? @executor-stops))
    (is (zero? @database-lists))))

(deftest writer-stop-returns-the-pre-release-coordinate-and-outcome
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})
        database-name (str "writer-release-success-" (random-uuid))
        database-keyword (keyword database-name)
        request-path (socket-path "release-success-request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
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
        nil))))

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
          request-path (socket-path (str label "-request"))]
      (try
        (d/create-database config)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Initial database ensure failed"
             (writer/start!
              {::writer/dependencies (dependencies)
               ::writer/database-name database-name
               ::writer/backend :memory
               ::writer/request-socket-path request-path})))
        (is (empty? (::registry/databases (registry/list-databases {})))
            "a rejected schema never publishes a registry entry")
        (finally
          (when (d/database-exists? config)
            (d/delete-database config))
          (.delete (File. request-path))
          nil)))))

(deftest writer-opens-and-routes-an-explicit-native-branch-attachment
  (let [database-name (str "writer-branch-main-" (random-uuid))
        branch-name (str "writer-branch-route-" (random-uuid))
        request-path (socket-path "branch-request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
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
                      {::protocol/request-id "branch/ensure-first"
                       ::protocol/database-name branch-name
                       ::protocol/backend :memory
                       ::coordinate/attachment attachment}))
              _
              (call! channel
                     (protocol/acquire-database-request
                      {::protocol/request-id "branch/acquire"
                       ::protocol/database-name branch-name
                       ::protocol/attachment attachment}))
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
        nil))))

(deftest native-branch-open-restores-proximum-without-running-main-initializer
  (let [database-name (str "writer-proximum-main-" (random-uuid))
        branch-name (str "writer-proximum-branch-" (random-uuid))
        database-root (File. "tmp" (str "writer-proximum-" (random-uuid)))
        database-path (.getPath (File. database-root "db"))
        request-path (socket-path "proximum-branch-request")
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
          ::writer/request-socket-path request-path})
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
                        {::protocol/request-id "branch/ensure-second"
                         ::protocol/database-name branch-name
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
        nil
        (when (.exists database-root)
          (run! (fn [^File file] (.delete file))
                (reverse (file-seq database-root))))))))

(deftest typed-native-branch-lifecycle-routes-through-one-writer
  (let [source-name (str "writer-lifecycle-source-" (random-uuid))
        target-name (str "writer-lifecycle-target-" (random-uuid))
        request-path (socket-path "life-req")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name source-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})]
    (try
      (with-open [channel (uds/connect! request-path)]
        (acquire! channel source-name "lifecycle/session")
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
                      {::protocol/request-id "branch/create-first"
                       ::protocol/source-database-name source-name
                       ::protocol/target-database-name target-name
                       ::protocol/source-coordinate source-head
                       ::protocol/expected-source-head initial-head
                       ::protocol/target-branch branch}))
              created
              (call! channel
                     (protocol/create-branch-request
                      {::protocol/request-id "branch/create-second"
                       ::protocol/source-database-name source-name
                       ::protocol/target-database-name target-name
                       ::protocol/source-coordinate source-head
                       ::protocol/expected-source-head source-head
                       ::protocol/target-branch branch}))
              target-attachment (::protocol/target-attachment created)
              target-session
              (with-open [target-channel (uds/connect! request-path)]
                (call! target-channel
                       (protocol/acquire-database-request
                        {::protocol/request-id "lifecycle/target-acquire"
                         ::protocol/database-name target-name
                         ::protocol/attachment target-attachment}))
                [(call! target-channel
                        (protocol/query-request
                         {::protocol/request-id "lifecycle/target-read-fork"
                          ::protocol/database-name target-name
                          ::protocol/attachment target-attachment
                          ::protocol/coordinate (::protocol/coordinate created)
                          ::protocol/query-form
                          '[:find ?value
                            :where [?entity :writer.lifecycle/value ?value]]
                          ::protocol/arguments []}))
                 (call! target-channel
                        (protocol/transaction-request
                         {::protocol/database-name target-name
                          ::protocol/request-id "lifecycle/target-write"
                          ::protocol/transaction-data
                          [{:writer.lifecycle/value "target-only"}]}))])
              target-read (first target-session)
              target-write (second target-session)
              target-head (::protocol/coordinate target-write)
              sibling-read
              (call! channel
                     (protocol/query-request
                      {::protocol/request-id "lifecycle/source-reject-sibling"
                       ::protocol/database-name source-name
                       ::protocol/attachment (coordinate/attachment source-head)
                       ::protocol/coordinate
                       (assoc target-head ::coordinate/branch :db)
                       ::protocol/query-form
                       '[:find ?value
                         :where [?entity :writer.lifecycle/value ?value]]
                       ::protocol/arguments []}))
              source-connection
              (::registry/conn
               (registry/resolve-connection
                {::registry/database-name (keyword source-name)}))
              target-db
              (d/branch-as-db source-connection branch)]
          (is (await-route! target-name false)
              "a target with no live child retains no authority connection")
          (let [unopened-branch :experiment/unopened
                _ (d/branch! source-connection
                             (::coordinate/commit-id source-head)
                             unopened-branch)
                observation
                (call! channel
                       (protocol/observe-database-lifecycle-request
                        {::protocol/request-id "branch/observe"
                         ::protocol/database-name source-name}))]
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
          (is (= #{["shared"]} (set (:datahike.query/result target-read)))
              "the attached fork accepts its source commit after ancestry proof")
          (is (= protocol/stale-coordinate-error
                 (::protocol/error-kind sibling-read))
              "a retained sibling commit cannot cross the attached lineage")
          (is (::protocol/success? target-write))
          (is (some? (d/q '[:find ?entity .
                             :where [?entity :writer.lifecycle/value "target-only"]]
                           target-db)))
          (is (nil? (d/q '[:find ?entity .
                            :where [?entity :writer.lifecycle/value "target-only"]]
                          (d/db source-connection))))
          (let [deleted
                (call! channel
                       (protocol/delete-branch-request
                        {::protocol/request-id "branch/delete"
                         ::protocol/source-database-name source-name
                         ::protocol/target-database-name target-name
                         ::protocol/target-attachment target-attachment
                         ::protocol/expected-target-head target-head}))]
            (is (::protocol/success? deleted))
            (is (false? (::protocol/released? deleted)))
            (is (true? (::protocol/deleted? deleted)))
            (is (not (contains? (d/branches source-connection) branch))))))
      (finally
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))


(deftest canonical-writes-route-commit-and-recover-exactly-once
  (let [database-name (str "writer-integration-" (random-uuid))
        request-path (socket-path "request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
        ^SocketChannel request-channel (uds/connect! request-path)]
    (try
      (acquire! request-channel database-name "writer/session")
      (let [ping-response
            (call! request-channel
                   (protocol/ping-request
                    {::protocol/request-id "invalid/ping"}))
            ensure-response
            (call! request-channel
                   (protocol/ensure-database-request
                    {::protocol/request-id "invalid/ensure"
                     ::protocol/database-name database-name
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
                    {::protocol/request-id "invalid/reensure"
                     ::protocol/database-name database-name
                     ::protocol/backend :memory}))
            unknown-response
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name "not-open"
                     ::protocol/request-id "unknown-route"
                     ::protocol/transaction-data []}))
            schema-response
            (call! request-channel
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
                       :db/cardinality :db.cardinality/one}]}))
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
            recovered-response (call! request-channel entity-request)
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            stored
            (d/pull (d/db connection) '[*] [:writer.person/id "alice"])]
        (is (= {::protocol/success? true
                ::protocol/request-id "invalid/ping"
                ::protocol/pong? true}
               ping-response))
        (is (every? protocol/valid-response?
                    [ensure-response invalid-response ensure-after-invalid
                     unknown-response schema-response entity-response
                     recovered-response]))
        (is (coordinate/same-attachment?
             (coordinate/resolved (d/db connection))
             (::coordinate/coordinate ensure-response)))
        (is (= initial-coordinate (::coordinate/coordinate ensure-after-invalid))
            "invalid input and idempotent ensure do not advance the database")
        (is (= protocol/protocol-error
               (::protocol/error-kind invalid-response)))
        (is (= protocol/not-found-error
               (::protocol/error-kind unknown-response))
            "a named unknown database cannot fall back to the open database")
        (is (true? (::protocol/success? schema-response)))
        (is (= protocol/reserved-attributes
               (set (filter #(contains? (:schema (d/db writer-connection)) %)
                            protocol/reserved-attributes)))
            "the canonical Malli-derived receipt schema exists before writes")
        (is (true? (::protocol/success? entity-response)))
        (is (pos-int?
             (get (::protocol/temporary-ids entity-response) "person-temp")))
        (is (every?
             empty?
             (for [message [schema-response entity-response]]
               (filter protocol/reserved-attributes
                       (map second (::protocol/transaction-data message)))))
            "public transaction responses omit receipt implementation datoms")
        (is (every?
             empty?
             (for [message [schema-response entity-response]]
               (filter protocol/reserved-attributes
                       (keys (or (::protocol/transaction-meta message) {})))))
            "public transaction responses omit receipt implementation metadata")
        (is (= (count (::protocol/transaction-data entity-response))
               (+ (::protocol/datoms-added entity-response)
                  (::protocol/datoms-retracted entity-response))))
        (is (true? (::protocol/recovered? recovered-response)))
        (is (= (::protocol/coordinate entity-response)
               (::protocol/coordinate recovered-response))
            "an idempotent retry returns the exact committed coordinate")
        (is (= :writer.status/ready (:writer.person/status stored)))
        (is (instance? Double (:writer.person/score stored)))
        (is (= 1.0 (:writer.person/score stored))))
      (finally
        (try (.close request-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))))))

(deftest expected-basis-is-enforced-inside-the-serialized-writer
  (let [database-name (str "writer-fence-" (random-uuid))
        request-path (socket-path "fence-request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
        ^SocketChannel a (uds/connect! request-path)
        ^SocketChannel b (uds/connect! request-path)]
    (try
      (acquire! a database-name "fence/a")
      (acquire! b database-name "fence/b")
      (let [opened
            (call! a
                   (protocol/ensure-database-request
                    {::protocol/request-id "invalid/ensure-after"
                     ::protocol/database-name database-name
                     ::protocol/backend :memory}))
            schema
            (call! a
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
            ready (CountDownLatch. 2)
            start (CountDownLatch. 1)
            submit
            (fn [channel request-id entity-id]
              (future
                (.countDown ready)
                (.await start)
                (call! channel
                       (protocol/transaction-request
                        {::protocol/database-name database-name
                         ::protocol/request-id request-id
                         ::protocol/expected-coordinate frozen
                         ::protocol/transaction-data
                         [{:writer.fence/id entity-id
                           :writer.fence/value request-id}]}))))
            left (submit a "fence/left" "left")
            right (submit b "fence/right" "right")
            _ready? (is (.await ready 5 TimeUnit/SECONDS)
                        "both requests are ready before concurrent release")
            _started? (.countDown start)
            responses [(deref left 5000 ::timed-out)
                       (deref right 5000 ::timed-out)]
            accepted (first (filter ::protocol/success? responses))
            rejected (first (remove ::protocol/success? responses))
            committed (::protocol/coordinate accepted)
            wrong-commit
            (call! a
                   (protocol/transaction-request
                    {::protocol/database-name database-name
                     ::protocol/request-id "fence/wrong-commit"
                     ::protocol/expected-coordinate
                     (assoc committed ::coordinate/commit-id (random-uuid))
                     ::protocol/transaction-data
                     [{:writer.fence/id "one"
                       :writer.fence/value "wrong-commit-must-not-land"}]}))
            wrong-branch
            (call! a
                   (protocol/transaction-request
                    {::protocol/database-name database-name
                     ::protocol/request-id "fence/wrong-branch"
                     ::protocol/expected-coordinate
                     (assoc committed ::coordinate/branch :experiment)
                     ::protocol/transaction-data
                     [{:writer.fence/id "one"
                       :writer.fence/value "wrong-branch-must-not-land"}]}))
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            stored
            (d/q '[:find ?id ?value
                   :where
                   [?entity :writer.fence/id ?id]
                   [?entity :writer.fence/value ?value]]
                 (d/db connection))]
        (is (true? (::protocol/success? opened)))
        (is (not-any? #{::timed-out} responses))
        (is (= 1 (count (filter ::protocol/success? responses)))
            "exactly one same-head request commits")
        (is (= 1 (count (remove ::protocol/success? responses)))
            "the serialized writer rejects the losing request")
        (is (true? (::protocol/success? accepted)))
        (is (= protocol/stale-coordinate-error
               (::protocol/error-kind wrong-commit)))
        (is (= committed (::protocol/current-coordinate wrong-commit))
            "equal t cannot cross a different commit identity")
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
        (is (= 1 (count stored))
            "only the winning request's domain facts land"))
      (finally
        (try (.close a) (catch Throwable _))
        (try (.close b) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

(deftest canonical-schema-forms-augment-the-same-domain-transaction
  (let [database-name (str "writer-schema-" (random-uuid))
        isolated-name (str "writer-schema-isolated-" (random-uuid))
        request-path (socket-path "schema-request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
        ^SocketChannel channel (uds/connect! request-path)
        bootstrap-schema
        [{:db/ident :seon.schema/key
          :db/valueType :db.type/keyword
          :db/cardinality :db.cardinality/one
          :db/unique :db.unique/identity}
         {:db/ident :seon.schema/form
          :db/valueType :db.type/string
          :db/cardinality :db.cardinality/one}
         {:db/ident :seon.db.id/generator
          :db/valueType :db.type/keyword
          :db/cardinality :db.cardinality/one}]
        transact!
        (fn [database request-id transaction-data]
          (call! channel
                 (protocol/transaction-request
                  {::protocol/database-name database
                   ::protocol/request-id request-id
                   ::protocol/transaction-data transaction-data})))
        initialize!
        (fn [database request-prefix]
          (acquire! channel database request-prefix)
          (transact! database (str request-prefix "/bootstrap")
                     bootstrap-schema))]
    (try
      (initialize! database-name "schema/main")
      (let [forms
            [{:seon.schema/key :writer.schema/id
              :seon.schema/form
              "[:string {:seon.db/identity true}]"}
             {:seon.schema/key :seon.db/lookup-ref-value
              :seon.schema/form "[:or :string :uuid :keyword :int]"}
             {:seon.schema/key :seon.db/ref
              :seon.schema/form
              "[:or :int :string [:tuple :keyword :seon.db/lookup-ref-value]]"}
             {:seon.schema/key :writer.schema/name
              :seon.schema/form ":string"}
             {:seon.schema/key :writer.schema/score
              :seon.schema/form ":double"}
             {:seon.schema/key :writer.schema/parent
              :seon.schema/form
              ":seon.db/ref"}
             {:seon.schema/key :writer.schema/children
              :seon.schema/form
              "[:vector {:seon.db/component true} :seon.db/ref]"}]
            admitted
            (transact!
             database-name "schema/admit"
             (into forms
                   [{:db/id "schema-parent"
                     :writer.schema/id "parent"
                     :writer.schema/name "Parent"
                     :writer.schema/score 1}
                    {:db/id "schema-child"
                     :writer.schema/id "child"
                     :writer.schema/name "Child"
                     :writer.schema/parent "schema-parent"}
                    {:db/id "component-root"
                     :writer.schema/id "component-root"
                     :writer.schema/children
                     [{:db/id "component-child"
                       :writer.schema/id "component-child"
                       :writer.schema/name "Nested"}]}]))
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            admitted-db (d/db connection)
            child (d/pull admitted-db '[* {:writer.schema/parent [*]}]
                          [:writer.schema/id "child"])
            shared-transaction
            (d/q '[:find ?transaction .
                   :where
                   [?schema :seon.schema/key :writer.schema/id ?transaction]
                   [?entity :writer.schema/id "child" ?transaction]]
                 admitted-db)
            repeated
            (transact! database-name "schema/repeat" [(second forms)])
            before-incompatible (coordinate/resolved (d/db connection))
            incompatible
            (transact!
             database-name "schema/incompatible"
             [{:seon.schema/key :writer.schema/name
               :seon.schema/form ":int"}
              {:writer.schema/id "must-not-land"
               :writer.schema/name "Wrong"}])
            before-invalid (coordinate/resolved (d/db connection))
            invalid
            (transact!
             database-name "schema/invalid-value"
             [{:seon.schema/key :writer.schema/broken
               :seon.schema/form ":int"}
              {:writer.schema/broken "not-an-int"}])
            after-invalid (d/db connection)]
        (is (true? (::protocol/success? admitted)))
        (is (pos-int? shared-transaction)
            "canonical schema and domain facts share one transaction")
        (is (= :db.type/ref
               (get-in admitted-db [:schema :writer.schema/parent
                                    :db/valueType])))
        (is (= :db.unique/identity
               (get-in admitted-db [:schema :writer.schema/id :db/unique])))
        (is (true?
             (get-in admitted-db
                     [:schema :writer.schema/children :db/isComponent])))
        (is (= 1.0 (:writer.schema/score
                    (d/pull admitted-db '[*]
                            [:writer.schema/id "parent"])))
            "new numeric schema participates in wire-number coercion")
        (is (= "parent"
               (get-in child [:writer.schema/parent :writer.schema/id])))
        (is (= #{"schema-parent" "schema-child"
                 "component-root" "component-child"}
               (set (keys (::protocol/temporary-ids admitted))))
            "derived ref schema participates in caller tempid recovery")
        (is (true? (::protocol/success? repeated)))
        (is (not-any? #(= :db/ident (second %))
                      (::protocol/transaction-data repeated))
            "an installed matching declaration is not re-added")
        (is (false? (::protocol/success? incompatible)))
        (is (= :user-input (:seon.error/kind incompatible)))
        (is (= before-incompatible (coordinate/resolved (d/db connection))))
        (is (nil? (d/pull (d/db connection) '[*]
                          [:writer.schema/id "must-not-land"])))
        (is (false? (::protocol/success? invalid)))
        (is (= before-invalid (coordinate/resolved after-invalid)))
        (is (not (contains? (:schema after-invalid) :writer.schema/broken)))
        (is (nil? (d/q '[:find ?schema .
                         :where
                         [?schema :seon.schema/key :writer.schema/broken]]
                       after-invalid)))

        (let [schema-only
              (transact!
               database-name "schema/lazy/admit"
               (into
                [{:seon.schema/key :writer.schema/lazy
                  :seon.schema/form ":keyword"}
                 {:seon.schema/key :writer.schema/lazy-parent
                  :seon.schema/form ":seon.db/ref"}
                 {:seon.schema/key :writer.schema/non-attribute
                  :seon.schema/form
                  "[:map [:writer.schema/non-attribute-value :string]]"}]
                (concat
                 (map (fn [index]
                        {:seon.schema/key
                         (keyword "writer.schema.irrelevant" (str index))
                         :seon.schema/form "[malformed"})
                      (range 128))
                 (map (fn [index]
                        {:seon.schema/key
                         (keyword "writer.schema.chain" (str "n" index))
                         :seon.schema/form
                         (pr-str
                          (if (= 65 index)
                            :string
                            (keyword "writer.schema.chain"
                                     (str "n" (inc index)))))})
                      (range 66))
                 [{:seon.schema/key :writer.schema.cycle/a
                   :seon.schema/form ":writer.schema.cycle/b"}
                  {:seon.schema/key :writer.schema.cycle/b
                   :seon.schema/form ":writer.schema.cycle/a"}])))
              after-schema-only (d/db connection)
              parsed-forms (atom [])
              original-read-string edn/read-string
              lazy-use
              (with-redefs [edn/read-string
                            (fn [form-string]
                              (swap! parsed-forms conj form-string)
                              (original-read-string form-string))]
                (transact!
                 database-name "schema/lazy/use"
                 [{:writer.schema/lazy :ready}]))
              lazy-ref-use
              (transact!
               database-name "schema/lazy/ref-use"
               [{:writer.schema/id "lazy-child"
                 :writer.schema/lazy-parent
                 [:writer.schema/id "parent"]}])
              before-long-chain (coordinate/resolved (d/db connection))
              long-chain
              (transact!
               database-name "schema/long-chain"
               [(hash-map (keyword "writer.schema.chain" "n0")
                          "too-deep")])
              after-long-chain (coordinate/resolved (d/db connection))
              cycle-parsed-forms (atom [])
              cycle
              (with-redefs [edn/read-string
                            (fn [form-string]
                              (swap! cycle-parsed-forms conj form-string)
                              (original-read-string form-string))]
                (transact!
                 database-name "schema/cycle"
                 [{:writer.schema.cycle/a "recursive"}]))
              after-cycle (coordinate/resolved (d/db connection))
              before-unknown (coordinate/resolved (d/db connection))
              unknown
              (transact!
               database-name "schema/unknown"
               [{:writer.schema/unknown "not-declared"}])]
          (is (true? (::protocol/success? schema-only)))
          (is (not (contains? (:schema after-schema-only)
                              :writer.schema/lazy))
              "schema-only facts wait for actual attribute use")
          (is (pos-int?
               (d/q '[:find ?schema .
                      :where
                      [?schema :seon.schema/key :writer.schema/lazy]
                      [?schema :seon.schema/form ":keyword"]]
                    after-schema-only))
              "the canonical form is durable before lazy installation")
          (is (not (contains? (:schema after-schema-only)
                              :writer.schema/non-attribute))
              "non-attribute schema forms remain ordinary facts")
          (is (true? (::protocol/success? lazy-use)))
          (is (= [":keyword"] @parsed-forms)
              "first use parses only its one candidate form")
          (is (= :db.type/keyword
                 (get-in (d/db connection)
                         [:schema :writer.schema/lazy :db/valueType])))
          (is (true? (::protocol/success? lazy-ref-use))
              (pr-str lazy-ref-use))
          (is (= :db.type/ref
                 (get-in (d/db connection)
                         [:schema :writer.schema/lazy-parent :db/valueType]))
              "candidate lookup follows only stored schema references")
          (is (false? (::protocol/success? long-chain)))
          (is (= :user-input (:seon.error/kind long-chain)))
          (is (re-find #"references too many other schema forms"
                       (::protocol/error long-chain)))
          (is (= before-long-chain after-long-chain))
          (is (false? (::protocol/success? cycle)))
          (is (= {":writer.schema.cycle/a" 1
                  ":writer.schema.cycle/b" 1}
                 (frequencies @cycle-parsed-forms))
              "a cycle queries and parses each canonical form once")
          (is (= after-long-chain after-cycle))
          (is (false? (::protocol/success? unknown)))
          (is (= :user-input (:seon.error/kind unknown)))
          (is (= before-unknown (coordinate/resolved (d/db connection)))))

        (let [generated-value "xschema00000"
              generated-schema
              (transact!
               database-name "schema/generated/admit"
               [{:seon.schema/key :seon.db.id/legacy-value
                 :seon.schema/form "[:string {:min 14 :max 14}]"}
                {:seon.schema/key :seon.db.id/compact-value
                 :seon.schema/form
                 (str "[:or :seon.db.id/legacy-value "
                      "[:and :string [:re \"^[a-z][a-z0-9]{11}$\"]]]")}
                {:seon.schema/key :writer.schema/generated-id
                 :seon.schema/form
                 (str "[:and {:seon.db/identity true "
                      ":seon.db.id/generator "
                      ":seon.db.id.generator/compact} "
                      ":seon.db.id/compact-value]")
                 :seon.db.id/generator
                 :seon.db.id.generator/compact}])
              generated
              (call! channel
                     (protocol/transaction-request
                      {::protocol/database-name database-name
                       ::protocol/request-id "schema/generated/write"
                       ::protocol/transaction-data
                       [{:writer.schema/generated-id generated-value
                         :writer.schema/name "Generated"}]
                       ::protocol/generated-candidates
                       [{::id/key :schema/generated
                         ::id/identity-attr :writer.schema/generated-id
                         ::id/value generated-value}]}))]
          (is (true? (::protocol/success? generated-schema))
              (pr-str generated-schema))
          (is (true? (::protocol/success? generated)) (pr-str generated))
          (is (pos-int?
               (get (::protocol/generated-entity-ids generated)
                    :schema/generated)))
          (is (= "Generated"
                 (:writer.schema/name
                  (d/pull (d/db connection) '[*]
                          [:writer.schema/generated-id generated-value])))))

        (call! channel
               (protocol/ensure-database-request
                {::protocol/request-id "schema/isolated/ensure"
                 ::protocol/database-name isolated-name
                 ::protocol/backend :memory}))
        (initialize! isolated-name "schema/isolated")
        (let [isolated
              (transact!
               isolated-name "schema/isolated/admit"
               [{:seon.schema/key :writer.schema/name
                 :seon.schema/form ":int"}
                {:writer.schema/name 42}])
              isolated-connection
              (::registry/conn
               (registry/lookup-connection
                {::registry/database-name (keyword isolated-name)}))]
          (is (true? (::protocol/success? isolated)))
          (is (= :db.type/long
                 (get-in (d/db isolated-connection)
                         [:schema :writer.schema/name :db/valueType])))
          (is (= :db.type/string
                 (get-in (d/db connection)
                         [:schema :writer.schema/name :db/valueType]))
              "canonical form registries are isolated per database")))
      (finally
        (try (.close channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))
