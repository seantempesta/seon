(ns seon.db.remote-contract-test
  "Selective Datahike semantics through the real writer Unix socket."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db.executor :as executor]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer])
  (:import [java.io File]
           [java.nio.channels Channels SocketChannel]
           [java.util Date]
           [java.util.concurrent CountDownLatch TimeUnit]))

;; This namespace deliberately describes the replacement contract rather than
;; preserving the coordinate protocol. Until the authority cut lands, failures
;; identify missing source contracts behavior-by-behavior.

(defn- socket-path
  [label]
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory
            (str "seon-remote-contract-" label "-" (random-uuid) ".sock")))))

(defn- rank-for
  [database-name]
  (let [database-name (name database-name)]
    (cond
    (.endsWith ^String database-name "-b") 2
    (.endsWith ^String database-name "-c") 3
    :else 1)))

(defn- seed-database!
  [connection database-name]
  (let [database-name (name database-name)
        rank (rank-for database-name)]
    (d/transact
     connection
     [{:db/ident :remote.contract/id
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one
       :db/unique :db.unique/identity}
      {:db/ident :remote.contract/name
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one}
      {:db/ident :remote.contract/rank
       :db/valueType :db.type/long
       :db/cardinality :db.cardinality/one
       :db/index true}
      {:db/ident :remote.contract/friend
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one}
      {:db/ident :remote.contract/source
       :db/valueType :db.type/keyword
       :db/cardinality :db.cardinality/one}
      {:db/id "primary"
       :remote.contract/id (str database-name "/primary")
       :remote.contract/name (str "name-" rank)
       :remote.contract/rank rank
       :remote.contract/friend "secondary"}
      {:db/id "secondary"
       :remote.contract/id (str database-name "/secondary")
       :remote.contract/name (str "secondary-" rank)
       :remote.contract/rank (+ 10 rank)}])))

(defn- dependencies
  []
  {::writer/database-initializer seed-database!
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_] [])
   ::writer/embedding-inputs-for-eids (fn [_ _] [])
   ::writer/embedding-assertions (fn [_] [])
   ::writer/revalidate-embedding-assertions (fn [_ _] [])
   ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_ _ _ _] [])})

(defn- call!
  [channel message]
  (uds/call! {::uds/channel channel ::uds/message message}))

(defn- request
  [operation request-id body]
  (assoc body
         ::protocol/operation operation
         ::protocol/request-id request-id))

(defn- ensure-database!
  [channel database-name]
  (call! channel
         (request protocol/ensure-database-operation
                  (str database-name "/ensure")
                  {::protocol/database-name database-name
                   ::protocol/backend :memory})))

(defn- connection-for
  [database-name]
  (::registry/conn
   (registry/resolve-connection
    {::registry/database-name (keyword database-name)})))

(defn- database-value
  [database-name]
  (let [native (d/db (connection-for database-name))]
    {:db-name database-name
     :t (:max-tx native)
     :as-of nil
     :since nil
     :history false
     :datahike/commit-id (d/commit-id native)}))

(defn- database-name-of [database] (:db-name database))
(defn- basis-t-of [database] (:t database))
(defn- as-of-value [database point]
  (assoc database :as-of point :since nil))
(defn- since-value [database point]
  (assoc database :as-of nil :since point))
(defn- history-value [database]
  (assoc database :history true))

(defn- descriptor-shaped-ordinary-value
  []
  {:db-name "ordinary"
   :t 1
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id (random-uuid)})

(defn- query-request
  [request-id query-form arguments]
  (request protocol/query-operation request-id
           {::protocol/query-form query-form
            ::protocol/arguments (vec arguments)}))

(defn- read-result
  [response]
  (if (::protocol/success? response)
    (:datahike.query/result response)
    response))

(defn- ordinary-data?
  [value]
  (and (not (instance? Throwable value))
       (not (instance? clojure.lang.IDeref value))
       (not (instance? clojure.lang.IRecord value))
       (not (instance? Thread value))
       (if (coll? value) (every? ordinary-data? (seq value)) true)))

(defn- start-authority!
  [label database-names]
  (let [path (socket-path label)
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name (first database-names)
          ::writer/backend :memory
          ::writer/selected-processors 3
          ::writer/request-socket-path path})
        channel (uds/connect! path)]
    (doseq [database-name (rest database-names)]
      (let [response (ensure-database! channel database-name)]
        (when-not (::protocol/success? response)
          (throw (ex-info "Could not create remote-contract fixture database."
                          response)))))
    {::server server ::path path ::channel channel}))

(defn- close-authority!
  [{::keys [server path channel]}]
  (try (.close ^SocketChannel channel) (catch Throwable _))
  (writer/stop! server)
  (.delete (File. path))
  nil)

(defmacro with-authority
  [[binding label database-names] & body]
  `(let [~binding (start-authority! ~label ~database-names)]
     (try
       ~@body
       (finally (close-authority! ~binding)))))

(defn- transact-request
  [request-id database tx-data tx-meta]
  (request protocol/transact-operation request-id
           (cond-> {:seon.db/db database
                    ::protocol/transaction-data tx-data}
             tx-meta (assoc ::protocol/transaction-meta tx-meta))))

(defn- pull-request
  [operation request-id database selector entity-or-entities]
  (request operation request-id
           (cond-> {:seon.db/db database
                    ::protocol/selector selector}
             (= operation protocol/pull-operation)
             (assoc ::protocol/entity-id entity-or-entities)
             (= operation protocol/pull-many-operation)
             (assoc ::protocol/entity-ids entity-or-entities))))

(defn- read-next
  [channel]
  (uds/read-frame (Channels/newInputStream ^SocketChannel channel)))

(defn- multiplexed-session
  [channel]
  (let [pending (atom {})
        open? (atom true)
        reader
        (future
          (try
            (while @open?
              (let [message (read-next channel)
                    request-id (::protocol/request-id message)]
                (when-let [result (get @pending request-id)]
                  (swap! pending dissoc request-id)
                  (deliver result message))))
            (catch Throwable throwable
              (when @open?
                (doseq [result (vals @pending)]
                  (deliver result throwable))))))]
    {::channel channel ::pending pending ::open? open? ::reader reader}))

(defn- send-multiplexed!
  [session message]
  (let [result (promise)
        request-id (::protocol/request-id message)]
    (swap! (::pending session) assoc request-id result)
    (locking (::channel session)
      (uds/write-frame!
       (Channels/newOutputStream ^SocketChannel (::channel session))
       message))
    result))

(defn- close-multiplexed!
  [session]
  (reset! (::open? session) false)
  (try (.close ^SocketChannel (::channel session)) (catch Throwable _))
  (deref (::reader session) 1000 nil)
  nil)

(defn- wait-until!
  [predicate]
  (let [deadline (+ (System/nanoTime) 3000000000)]
    (loop []
      (cond
        (predicate) true
        (< deadline (System/nanoTime)) false
        :else (do (Thread/yield) (recur))))))

(deftest database-values-and-transaction-reports-are-ordinary-data
  (let [database-name (str "remote-report-" (random-uuid))]
    (with-authority [authority "report" [database-name]]
      (let [channel (::channel authority)
            before (database-value database-name)
            response
            (call! channel
                   (transact-request
                    "report/transact" before
                    [[:db/add [:remote.contract/id
                               (str database-name "/primary")]
                      :remote.contract/name "changed"]]
                    {:remote.contract/source :compatibility-test}))
            after (:db-after response)]
        (is (::protocol/success? response) (pr-str response))
        (is (= #{:db-before :db-after :tx-data :tempids :tx-meta}
               (set (remove #{::protocol/success? ::protocol/request-id}
                            (keys response))))
            "the writer returns Datahike's native report fields")
        (is (= before (:db-before response)))
        (is (and (map? after)
                 (= database-name (database-name-of after))))
        (is (and (number? (basis-t-of before))
                 (number? (basis-t-of after))
                 (< (basis-t-of before) (basis-t-of after))))
        (is (every? ordinary-data? (vals response)))
        (is (= "name-1"
               (read-result
                (call! channel
                       (query-request
                        "report/read-old"
                        '[:find ?name . :in $ :where
                          [?e :remote.contract/id _]
                          [?e :remote.contract/name ?name]
                          [(= ?name "name-1")]]
                        [before]))))
            "the old report value remains immutable and usable")))))

(deftest query-preserves-every-find-result-shape
  (let [database-name (str "remote-shapes-" (random-uuid))]
    (with-authority [authority "shapes" [database-name]]
      (let [channel (::channel authority)
            database (database-value database-name)
            query!
            (fn [request-id query-form]
              (read-result
               (call! channel
                      (query-request request-id query-form [database]))))]
        (is (= #{["name-1"] ["secondary-1"]}
               (query! "shapes/relation"
                       '[:find ?name :in $ :where
                         [?e :remote.contract/name ?name]])))
        (is (= "name-1"
               (query! "shapes/scalar"
                       '[:find ?name . :in $ :where
                         [?e :remote.contract/rank 1]
                         [?e :remote.contract/name ?name]])))
        (is (nil?
             (query! "shapes/scalar-missing"
                     '[:find ?name . :in $ :where
                       [?e :remote.contract/rank 404]
                       [?e :remote.contract/name ?name]])))
        (let [result (query! "shapes/collection"
                             '[:find [?name ...] :in $ :where
                               [?e :remote.contract/name ?name]])]
          (is (vector? result))
          (is (= #{"name-1" "secondary-1"} (set result))))
        (is (= ["name-1" 1]
               (query! "shapes/tuple"
                       '[:find [?name ?rank] :in $ :where
                         [?e :remote.contract/rank 1]
                         [?e :remote.contract/name ?name]
                         [?e :remote.contract/rank ?rank]])))
        (is (nil?
             (query! "shapes/tuple-missing"
                     '[:find [?name ?rank] :in $ :where
                       [?e :remote.contract/rank 404]
                       [?e :remote.contract/name ?name]
                       [?e :remote.contract/rank ?rank]])))
        (let [result (query! "shapes/maps"
                             '[:find ?name ?rank :keys name rank :in $ :where
                               [?e :remote.contract/name ?name]
                               [?e :remote.contract/rank ?rank]])]
          (is (vector? result))
          (is (= #{{:name "name-1" :rank 1}
                   {:name "secondary-1" :rank 11}}
                 (set result))))
        (let [result
              (query! "shapes/string-maps"
                      '[:find ?name ?rank :strs name rank :in $ :where
                        [?e :remote.contract/name ?name]
                        [?e :remote.contract/rank ?rank]])]
          (is (vector? result))
          (is (= #{{"name" "name-1" "rank" 1}
                   {"name" "secondary-1" "rank" 11}}
                 (set result))))
        (let [pulled
              (query! "shapes/find-pull"
                      '[:find (pull ?e [:remote.contract/id
                                        :remote.contract/name])
                        :in $ :where [?e :remote.contract/rank 1]])]
          (is (= 1 (count pulled)))
          (is (= "name-1"
                 (:remote.contract/name (ffirst pulled)))))))))

(deftest query-resolves-two-and-three-database-sources-in-place
  (let [prefix (str "remote-multi-" (random-uuid))
        a (str prefix "-a")
        b (str prefix "-b")
        c (str prefix "-c")]
    (with-authority [authority "multi" [a b c]]
      (let [channel (::channel authority)
            db-a (database-value a)
            db-b (database-value b)
            db-c (database-value c)
            ordinary 6
            form
            '[:find ?a ?b ?c ?ordinary
              :in $a ?ordinary $b $c
              :where
              [$a ?ea :remote.contract/rank ?a]
              [$b ?eb :remote.contract/rank ?b]
              [$c ?ec :remote.contract/rank ?c]
              [(< ?a 10)] [(< ?b 10)] [(< ?c 10)]]
            result
            (read-result
             (call! channel
                    (query-request "multi/three" form
                                   [db-a ordinary db-b db-c])))]
        (is (= #{[1 2 3 6]} result))
        (is (= #{[1 2]}
               (read-result
                (call! channel
                       (query-request
                        "multi/two"
                        '[:find ?a ?b :in $a $b :where
                          [$a ?ea :remote.contract/rank ?a]
                          [$b ?eb :remote.contract/rank ?b]
                          [(< ?a 10)] [(< ?b 10)]]
                        [db-a db-b])))))))))

(deftest execute-many-resolves-each-members-database-values-once
  (let [prefix (str "remote-many-" (random-uuid))
        database-a (str prefix "-a")
        database-b (str prefix "-b")]
    (with-authority [authority "many" [database-a database-b]]
      (let [channel (::channel authority)
            db-a (database-value database-a)
            db-b (database-value database-b)
            response
            (call!
             channel
             (protocol/execute-many-request
              {::protocol/request-id "many/ordinary-values"
               ::protocol/members
               [{::protocol/operation protocol/pull-operation
                 :seon.db/db db-a
                 ::protocol/selector [:remote.contract/rank]
                 ::protocol/entity-id
                 [:remote.contract/id (str database-a "/primary")]}
                {::protocol/operation protocol/query-operation
                 ::protocol/query-form
                 '[:find ?a ?b :in $a $b
                   :where
                   [$a ?ea :remote.contract/rank ?a]
                   [$b ?eb :remote.contract/rank ?b]
                   [(< ?a 10)]
                   [(< ?b 10)]]
                 ::protocol/arguments [db-a db-b]}
                {::protocol/operation protocol/schema-operation
                 :seon.db/db db-a}
                {::protocol/operation protocol/pull-operation
                 :seon.db/db db-b
                 ::protocol/selector [:remote.contract/rank]
                 ::protocol/entity-id
                 [:remote.contract/id (str database-b "/primary")]}]}))
            results (::protocol/results response)]
        (is (::protocol/success? response) (pr-str response))
        (is (= 1 (get-in results [0 ::protocol/result
                                  :remote.contract/rank])))
        (is (= #{[1 2]} (get-in results [1 :datahike.query/result])))
        (is (contains? (get-in results [2 ::protocol/schema])
                       :remote.contract/rank)
            (pr-str results))
        (is (= 2 (get-in results [3 ::protocol/result
                                  :remote.contract/rank])))
        (is (every? ordinary-data? results))))))

(deftest execute-many-materializes-and-releases-one-historical-value-once
  (let [database-name (str "remote-many-history-" (random-uuid))]
    (with-authority [authority "many-history" [database-name]]
      (let [channel (::channel authority)
            before (database-value database-name)
            _
            (call!
             channel
             (transact-request
              "many-history/advance" before
              [[:db/add [:remote.contract/id (str database-name "/primary")]
                :remote.contract/name "advanced"]]
              nil))
            materializations (atom 0)
            releases (atom 0)
            original-materialize d/commit-as-db
            original-release d/release-materialized-db]
        (with-redefs
          [d/commit-as-db
           (fn [& arguments]
             (swap! materializations inc)
             (apply original-materialize arguments))
           d/release-materialized-db
           (fn [database]
             (swap! releases inc)
             (original-release database))]
          (let [response
                (call!
                 channel
                 (protocol/execute-many-request
                  {::protocol/request-id "many-history/read"
                   ::protocol/members
                   [{::protocol/operation protocol/pull-operation
                     :seon.db/db before
                     ::protocol/selector [:remote.contract/name]
                     ::protocol/entity-id
                     [:remote.contract/id (str database-name "/primary")]}
                    {::protocol/operation protocol/query-operation
                     ::protocol/query-form
                     '[:find ?name . :in $
                       :where
                       [?e :remote.contract/rank 1]
                       [?e :remote.contract/name ?name]]
                     ::protocol/arguments [before]}
                    {::protocol/operation protocol/schema-operation
                     :seon.db/db before}]}))]
            (is (::protocol/success? response) (pr-str response))
            (is (= "name-1"
                   (get-in response [::protocol/results 0 ::protocol/result
                                     :remote.contract/name])))
            (is (= "name-1"
                   (get-in response [::protocol/results 1
                                     :datahike.query/result])))
            (is (= 1 @materializations))
            (is (= 1 @releases))))))))

(deftest execute-many-releases-partial-resolution-before-atomic-failure
  (let [prefix (str "remote-many-failure-" (random-uuid))
        database-a (str prefix "-a")
        database-b (str prefix "-b")]
    (with-authority [authority "many-failure" [database-a database-b]]
      (let [channel (::channel authority)
            before-a (database-value database-a)
            _
            (call!
             channel
             (transact-request
              "many-failure/advance-a" before-a
              [[:db/add [:remote.contract/id (str database-a "/primary")]
                :remote.contract/name "advanced"]]
              nil))
            invalid-b (update (database-value database-b) :t inc)
            releases (atom 0)
            original-release d/release-materialized-db]
        (with-redefs
          [d/release-materialized-db
           (fn [database]
             (swap! releases inc)
             (original-release database))]
          (let [response
                (call!
                 channel
                 (protocol/execute-many-request
                  {::protocol/request-id "many-failure/read"
                   ::protocol/members
                   [{::protocol/operation protocol/pull-operation
                     :seon.db/db before-a
                     ::protocol/selector [:remote.contract/name]
                     ::protocol/entity-id
                     [:remote.contract/id (str database-a "/primary")]}
                    {::protocol/operation protocol/pull-operation
                     :seon.db/db invalid-b
                     ::protocol/selector [:remote.contract/name]
                     ::protocol/entity-id
                     [:remote.contract/id (str database-b "/primary")]}]}))]
            (is (false? (::protocol/success? response)))
            (is (= protocol/not-found-error (::protocol/error-kind response)))
            (is (nil? (::protocol/results response))
                "no member starts before every database value resolves")
            (is (= 1 @releases)
                "the earlier historical materialization is released")))))))

(deftest query-rehydrates-only-top-level-source-bindings
  (let [prefix (str "remote-descriptor-" (random-uuid))
        a (str prefix "-a")
        b (str prefix "-b")
        nested (descriptor-shaped-ordinary-value)]
    (with-authority [authority "descriptor" [a b]]
      (let [result
            (read-result
             (call!
              (::channel authority)
              (query-request
               "descriptor/nested"
               '[:find ?scalar ?tuple ?collection ?relation
                 :in $a ?scalar [?tuple] [?collection ...] [[?relation]] $b
                 :where
                 [$a ?ea :remote.contract/rank 1]
                 [$b ?eb :remote.contract/rank 2]]
               [(database-value a)
                nested
                [nested]
                [nested]
                [[nested]]
                (database-value b)])))]
        (is (= #{[nested nested nested nested]} result)
            "descriptor-shaped nested data is never treated as a database")))))

(deftest query-composes-current-as-of-since-and-history-values
  (let [database-name (str "remote-temporal-" (random-uuid))]
    (with-authority [authority "temporal" [database-name]]
      (let [channel (::channel authority)
            connection (connection-for database-name)
            before-native (d/db connection)
            before-t (:max-tx before-native)
            before-commit (d/commit-id before-native)
            report
            (d/transact
             connection
             [[:db/add [:remote.contract/id
                        (str database-name "/primary")]
               :remote.contract/name "later"]])
            current (database-value database-name)
            earlier (as-of-value current before-t)
            since (since-value current before-t)
            history (history-value current)
            tx (-> report :tx-data first :tx)
            instant (d/q '[:find ?instant . :in $ ?tx :where
                           [?tx :db/txInstant ?instant]]
                         (d/db connection) tx)
            instant-cut (as-of-value current (or instant (Date.)))
            query!
            (fn [request-id form args]
              (read-result
               (call! channel (query-request request-id form args))))]
        (is (= before-commit (:datahike/commit-id
                              (assoc current :datahike/commit-id before-commit))))
        (is (= #{["later" "name-1"]}
               (query! "temporal/current-as-of"
                       '[:find ?current ?earlier :in $current $earlier :where
                         [$current ?e :remote.contract/rank 1]
                         [$current ?e :remote.contract/name ?current]
                         [$earlier ?old :remote.contract/rank 1]
                         [$earlier ?old :remote.contract/name ?earlier]]
                       [current earlier])))
        (is (= #{["later"]}
               (query! "temporal/since"
                       '[:find ?name :in $ :where
                         [?e :remote.contract/name ?name]]
                       [since])))
        (is (= #{["name-1"] ["later"]}
               (query! "temporal/history"
                       '[:find ?name :in $ :where
                         [?e :remote.contract/rank 1]
                         [?e :remote.contract/name ?name]]
                       [history])))
        (is (contains?
             (::protocol/schema
              (call! channel
                     (request protocol/schema-operation "temporal/schema"
                              {:seon.db/db earlier})))
             :remote.contract/rank)
            "schema follows Datahike's containing committed database")
        (is (not (map? (query! "temporal/instant"
                              '[:find (count ?e) . :in $ :where
                                [?e :remote.contract/id]]
                              [instant-cut])))
            "instant temporal values resolve through Datahike rather than error")))))

(deftest pull-entity-and-pull-many-preserve-eager-values
  (let [database-name (str "remote-pull-" (random-uuid))]
    (with-authority [authority "pull" [database-name]]
      (let [channel (::channel authority)
            database (database-value database-name)
            primary [:remote.contract/id (str database-name "/primary")]
            missing [:remote.contract/id "missing"]
            selector
            [:remote.contract/id :remote.contract/name
             {:remote.contract/friend
              [:remote.contract/id :remote.contract/name]}]
            primary-eid
            (d/q '[:find ?entity . :in $ ?id :where
                   [?entity :remote.contract/id ?id]]
                 (d/db (connection-for database-name))
                 (str database-name "/primary"))
            pulled
            (::protocol/result
             (call! channel
                    (pull-request protocol/pull-operation "pull/one"
                                  database selector primary)))
            entity
            (::protocol/result
             (call! channel
                    (pull-request protocol/pull-operation "pull/entity"
                                  database '[*] primary)))
            many
            (::protocol/result
             (call! channel
                    (pull-request protocol/pull-many-operation "pull/many"
                                  database selector
                                  [primary missing primary-eid primary])))
            empty-many
            (::protocol/result
             (call! channel
                    (pull-request protocol/pull-many-operation "pull/empty"
                                  database selector [])))]
        (is (= "name-1" (:remote.contract/name pulled)))
        (is (= "secondary-1"
               (get-in pulled [:remote.contract/friend
                               :remote.contract/name])))
        (is (map? entity) "entity is eager ordinary data, not a host Entity")
        (is (= 4 (count many)))
        (is (nil? (nth many 1)))
        (is (= (first many) (nth many 2) (nth many 3)))
        (is (= [] empty-many))
        (is (every? ordinary-data? [pulled entity many empty-many]))))))

(deftest index-pages-preserve-native-order-and-cursors
  (let [database-name (str "remote-index-" (random-uuid))]
    (with-authority [authority "index" [database-name]]
      (let [channel (::channel authority)
            connection (connection-for database-name)
            _
            (d/transact
             connection
             [[:db/add [:remote.contract/id
                        (str database-name "/primary")]
               :remote.contract/rank 9]])
            database (database-value database-name)
            page!
            (fn [request-id database-value direction cursor limit]
              (call!
               channel
               (request
                protocol/index-page-operation request-id
                (cond-> {:seon.db/db database-value
                         ::protocol/index :avet
                         ::protocol/prefix [:remote.contract/rank]
                         ::protocol/direction direction
                         ::protocol/limit limit}
                  cursor (assoc ::protocol/cursor cursor)))))
            first-page (page! "index/first" database :forward nil 1)
            cursor (:datahike.index-page/cursor first-page)
            second-page (page! "index/second" database :forward cursor 1)
            empty-page
            (call!
             channel
             (request protocol/index-page-operation "index/empty"
                      {:seon.db/db database
                       ::protocol/index :avet
                       ::protocol/prefix [:remote.contract/rank 404]
                       ::protocol/direction :forward
                       ::protocol/limit 1}))
            reverse-page (page! "index/reverse" database :reverse nil 1)
            history-page (page! "index/history" (history-value database)
                                :forward nil 20)
            datoms (concat (:datahike.index-page/datoms first-page)
                           (:datahike.index-page/datoms second-page))]
        (is (::protocol/success? first-page) (pr-str first-page))
        (is (= 5 (count (first (:datahike.index-page/datoms first-page))))
            "ordinary datoms carry e a v tx added?")
        (is (= (count datoms) (count (distinct datoms)))
            "cursor continuation never repeats a datom")
        (is (= [] (:datahike.index-page/datoms empty-page)))
        (is (true? (:datahike.index-page/complete? empty-page)))
        (is (not= (first (:datahike.index-page/datoms first-page))
                  (first (:datahike.index-page/datoms reverse-page))))
        (is (some #(false? (nth % 4))
                  (:datahike.index-page/datoms history-page))
            "history pages preserve retraction polarity")
        (is (every? ordinary-data?
                    [first-page second-page empty-page reverse-page
                     history-page]))))))

(deftest keyed-listeners-preserve-commit-order-and-isolation
  (let [prefix (str "remote-listen-" (random-uuid))
        database-a (str prefix "-a")
        database-b (str prefix "-b")]
    (with-authority [authority "listen" [database-a database-b]]
      (let [path (::path authority)
            ^SocketChannel listener-a (uds/connect! path)
            ^SocketChannel listener-b (uds/connect! path)
            ^SocketChannel mutations (uds/connect! path)
            db-a (database-value database-a)
            db-b (database-value database-b)
            listen
            (fn [channel request-id database]
              (call!
               channel
               (request protocol/listen-operation request-id
                         {:seon.db/db database
                         ::protocol/query-form
                         '[:find ?name :where
                           [?e :remote.contract/name ?name]]})))]
        (try
          (let [first-a (listen listener-a "listen/a" db-a)
                replacement-a (listen listener-a "listen/a" db-a)
                first-b (listen listener-b "listen/b" db-b)]
            (is (::protocol/listening? first-a) (pr-str first-a))
            (is (::protocol/listening? replacement-a)
                "registering the same key replaces rather than conflicts")
            (is (::protocol/listening? first-b) (pr-str first-b))
            (when (every? ::protocol/listening?
                          [first-a replacement-a first-b])
              (let [pending-b (future (read-next listener-b))
                    report-a
                    (call!
                     mutations
                     (transact-request
                      "listen/write-a" (database-value database-a)
                      [[:db/add [:remote.contract/id
                                 (str database-a "/primary")]
                        :remote.contract/name "event-a"]]
                      nil))
                    event-a (deref (future (read-next listener-a))
                                   3000 ::timed-out)]
                (is (= (:db-after report-a) (:db-after event-a)))
                (is (= "listen/a" (::protocol/request-id event-a)))
                (is (= ::still-waiting
                       (deref pending-b 100 ::still-waiting))
                    "a database commit is never broadcast to another database")
                (call!
                 mutations
                 (transact-request
                  "listen/write-b" (database-value database-b)
                  [[:db/add [:remote.contract/id
                             (str database-b "/primary")]
                    :remote.contract/name "event-b"]]
                  nil))
                (is (map? (deref pending-b 3000 ::timed-out)))
                (let [removed
                      (call!
                       listener-a
                       (request protocol/unlisten-operation "listen/unlisten-a"
                                {::protocol/target-request-id "listen/a"}))
                      after (future (read-next listener-a))]
                  (is (false? (::protocol/listening? removed)))
                  (call!
                   mutations
                   (transact-request
                    "listen/write-after" (database-value database-a)
                    [[:db/add [:remote.contract/id
                               (str database-a "/primary")]
                      :remote.contract/name "after-unlisten"]]
                    nil))
                  (is (= ::still-waiting
                         (deref after 100 ::still-waiting)))))))
          (finally
            (try (.close listener-a) (catch Throwable _))
            (try (.close listener-b) (catch Throwable _))
            (try (.close mutations) (catch Throwable _))))))))

(deftest cancel-disconnect-and-release-clean-every-source
  (let [prefix (str "remote-cleanup-" (random-uuid))
        database-a (str prefix "-a")
        database-b (str prefix "-b")
        database-c (str prefix "-c")]
    (with-authority [authority "cleanup" [database-a database-b database-c]]
      (let [path (::path authority)
            server (::server authority)
            runtime (::writer/runtime server)
            session (multiplexed-session (uds/connect! path))
            entered (CountDownLatch. 1)
            release-owner (CountDownLatch. 1)
            original-run d/run-q!
            db-a (database-value database-a)
            db-b (database-value database-b)
            db-c (database-value database-c)
            query
            (query-request
             "cleanup/owner"
             '[:find ?a ?b ?c :in $a $b $c :where
               [$a ?ea :remote.contract/rank ?a]
               [$b ?eb :remote.contract/rank ?b]
               [$c ?ec :remote.contract/rank ?c]
               [(< ?a 10)] [(< ?b 10)] [(< ?c 10)]]
             [db-a db-b db-c])]
        (try
          (with-redefs [d/run-q!
                        (fn [call]
                          (.countDown entered)
                          (.await release-owner 3 TimeUnit/SECONDS)
                          (original-run call))]
            (let [owner (send-multiplexed! session query)
                  admitted? (.await entered 500 TimeUnit/MILLISECONDS)]
              (is admitted?
                  "multi-source queries reach Datahike through the authority")
              (when admitted?
                (let [joined-result
                      (send-multiplexed!
                       session
                       (assoc query ::protocol/request-id "cleanup/joined"))
                      cancel
                      (deref
                       (send-multiplexed!
                       session
                       (request protocol/cancel-operation "cleanup/cancel"
                                {::protocol/target-request-id
                                 "cleanup/owner"}))
                       3000 {::protocol/canceled? false})]
                  (is (::protocol/canceled? cancel))
                  (.countDown release-owner)
                  (is (::protocol/success?
                       (deref joined-result 3000
                              {::protocol/success? false})))
                  (is (false? (::protocol/success?
                               (deref owner 3000
                                      {::protocol/success? false}))))))))
          (finally (.countDown release-owner)))
        (let [released
              (deref
               (send-multiplexed!
                session
                (request protocol/release-database-operation "cleanup/release-b"
                         {:seon.db/db db-b}))
               3000 {::protocol/released? false})
              duplicate
              (deref
               (send-multiplexed!
                session
                (request protocol/release-database-operation
                         "cleanup/release-b-again"
                         {:seon.db/db db-b}))
               3000 {::protocol/released? false})]
          (is (true? (::protocol/released? released)))
          (is (false? (::protocol/released? duplicate)))
          (let [remaining
                (deref
                 (send-multiplexed!
                  session
                  (query-request
                   "cleanup/database-c-remains"
                   '[:find ?rank . :in $ :where
                     [?e :remote.contract/rank ?rank]
                     [(< ?rank 10)]]
                   [(database-value database-c)]))
                 3000 {::protocol/success? false})]
            (is (= 3 (read-result remaining))
                "releasing one database leaves another acquisition usable")))
        (close-multiplexed! session)
        (is (wait-until!
             #(and (empty? @(::writer/active-requests runtime))
                   (zero? (::executor/retained-identities
                           (executor/evidence (::writer/executor server))))
                   (zero? (:datahike.single-flight/active-callers
                           (d/query-cache-evidence)))
                   (every?
                    (fn [database-name]
                      (empty?
                       (registry/lookup-connection
                        {::registry/database-name (keyword database-name)})))
                    [database-a database-b database-c])))
            "disconnect reaches the one terminal cleanup owner")
        (is (every?
             #(empty? (registry/lookup-connection
                        {::registry/database-name (keyword %)}))
             [database-a database-b database-c])
            "all session-acquired database generations are released")))))
