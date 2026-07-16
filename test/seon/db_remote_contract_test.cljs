(ns seon.db-remote-contract-test
  "Selective public-facade contracts derived from Datahike's remote tests."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [seon.db :as db]
   [seon.db.protocol :as protocol]
   [seon.db.transport.uds :as uds]
   [seon.instrument :as instrument]))

(def ^:private database-name "contract-default")
(def ^:private socket-path "tmp/db-remote-contract.sock")

(def ^:private database
  {:db-name database-name
   :t 536870913
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "10000000-0000-0000-0000-000000000002"})

(defn- success
  [request body]
  (protocol/success (assoc body ::protocol/request-id
                           (::protocol/request-id request))))

(defn- response-for
  [request query-results]
  (let [name (or (::protocol/database-name request) database-name)
        current (assoc database :db-name name)]
    (case (::protocol/operation request)
      :seon.db.protocol.operation/capabilities
      (success request {::protocol/capabilities
                        {:seon.db.capability/query true}})

      :seon.db.protocol.operation/ensure-database
      (success request {::protocol/database-name name
                        ::db/db current
                        ::protocol/backend :memory})

      :seon.db.protocol.operation/acquire-database
      (success request {::protocol/database-name name
                        ::db/db current
                        ::protocol/acquired? true})

      :seon.db.protocol.operation/resolve-head
      (success request {::protocol/database-name name
                        ::db/db current})

      :seon.db.protocol.operation/query
      (success request {::protocol/database-name name
                        :datahike.query/result
                        (get query-results (::protocol/query-form request))
                        :datahike.query/attribute-dependencies #{}
                        :datahike.query/cache-evidence {}
                        :datahike.query/resource-evidence {}})

      :seon.db.protocol.operation/pull
      (success request {::protocol/database-name name
                        ::protocol/result {:example/id 1}})

      :seon.db.protocol.operation/pull-many
      (success request {::protocol/database-name name
                        ::protocol/result [{:example/id 1} nil]})

      :seon.db.protocol.operation/index-page
      (success request
               {:datahike.index-page/datoms [[1 :example/id "one"
                                               536870913 true]]
                :datahike.index-page/complete? true})

      :seon.db.protocol.operation/transact
      (success request
               {:db-before (assoc current :t 536870912
                                  :datahike/commit-id
                                  #uuid "10000000-0000-0000-0000-000000000001")
                :db-after current
                :tx-data [[1 :example/id "one" 536870913 true]]
                :tempids {-1 1}
                :tx-meta {:seon.db/user [:seon.agent/id "root"]}})

      :seon.db.protocol.operation/listen
      (success request {::protocol/database-name name
                        ::protocol/listening? true})

      :seon.db.protocol.operation/unlisten
      (success request {::protocol/target-request-id
                        (::protocol/target-request-id request)
                        ::protocol/listening? false})

      :seon.db.protocol.operation/cancel
      (success request {::protocol/target-request-id
                        (::protocol/target-request-id request)
                        ::protocol/canceled? true
                        ::protocol/running? false})

      :seon.db.protocol.operation/release-database
      (success request {::protocol/target-database-name
                        (::protocol/target-database-name request)
                        ::protocol/released? true})

      (success request {}))))

(defn- with-recording-authority
  [query-results body]
  (let [original-connect! uds/connect!
        original-request! uds/request!
        original-connected? uds/connected?
        original-close! uds/close!
        requests (atom [])
        connection-options (atom nil)
        connected? (atom true)
        session {::recording-session true}]
    (reset! @#'db/!session nil)
    (set! uds/connect!
          (fn [options]
            (reset! connection-options options)
            (js/Promise.resolve session)))
    (set! uds/request!
          (fn [{::uds/keys [message]}]
            (swap! requests conj message)
            (js/Promise.resolve (response-for message query-results))))
    (set! uds/connected? (fn [_] @connected?))
    (set! uds/close!
          (fn [_]
            (let [was-connected? @connected?]
              (reset! connected? false)
              was-connected?)))
    (-> (js/Promise.resolve
         (body {::requests requests
                ::connection-options connection-options}))
        (.finally
         (fn []
           (db/close-session!)
           (reset! @#'db/!session nil)
           (set! uds/connect! original-connect!)
           (set! uds/request! original-request!)
           (set! uds/connected? original-connected?)
           (set! uds/close! original-close!))))))

(defn- open! []
  (db/open-session! {::db/socket-path socket-path
                     ::db/database-name database-name
                     ::db/backend :memory}))

(defn- operation-requests
  [requests operation]
  (filterv #(= operation (::protocol/operation %)) requests))

(defn- public-db-function
  "Return a public `seon.db` function without requiring an absent var."
  [function-name]
  (instrument/find-js-var 'seon.db function-name))

(deftest database-values-are-ordinary-immutable-maps
  (async done
    (-> (with-recording-authority
          {}
          (fn [_]
            (-> (open!)
                (.then
                 (fn [_]
                   (let [result (db/db)]
                     (is (instance? js/Promise result))
                     result)))
                (.then
                 (fn [current]
                   (is (= database current))
                   (let [earlier (db/as-of current 536870912)
                         later (db/since current 536870912)
                         all-datoms (db/history current)]
                     (is (= database current) "descriptor transforms are immutable")
                     (is (= 536870912 (:as-of earlier)))
                     (is (nil? (:since earlier)))
                     (is (= 536870912 (:since later)))
                     (is (nil? (:as-of later)))
                     (is (true? (:history all-datoms)))
                     (-> (db/db {::db/database-name "experiment-17"})
                         (.then
                          (fn [secondary]
                            (is (= "experiment-17" (:db-name secondary)))
                            (is (= (:datahike/commit-id database)
                                   (:datahike/commit-id secondary)))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "database-value contract rejected: " error))
                  (done)))))))

(deftest positional-and-namespaced-map-arities-form-the-same-requests
  (async done
    (let [query-form '[:find ?e :in $ ?minimum
                       :where [?e :example/rank ?rank]
                       [(>= ?rank ?minimum)]]]
      (-> (with-recording-authority
            {query-form #{[1]}}
            (fn [{::keys [requests]}]
              (-> (open!)
                  (.then (fn [_] (reset! requests [])
                           (db/query query-form database 4)))
                  (.then
                   (fn [_]
                     (let [positional (last (operation-requests
                                             @requests protocol/query-operation))]
                       (reset! requests [])
                       (-> (db/query {::db/query query-form
                                      ::db/args [database 4]})
                           (.then
                            (fn [_]
                              (let [mapped (last (operation-requests
                                                 @requests
                                                 protocol/query-operation))]
                                (is (= [database 4]
                                       (::protocol/arguments positional)))
                                (is (= (dissoc positional ::protocol/request-id)
                                       (dissoc mapped ::protocol/request-id)))))))))
                  (.then (fn [_] (db/pull database '[*] 1)))
                  (.then (fn [value] (is (= {:example/id 1} value))
                           (db/pull {::db/db database
                                     :seon.db/selector '[*]
                                     :seon.db/eid 1})))
                  (.then (fn [value] (is (= {:example/id 1} value))
                           (db/pull-many database '[*] [1 404])))
                  (.then (fn [value] (is (= [{:example/id 1} nil] value))
                           (db/pull-many {::db/db database
                                          :seon.db/selector '[*]
                                          :seon.db/eids [1 404]})))
                  (.then (fn [value] (is (= [{:example/id 1} nil] value))
                           (db/entity database 1)))
                  (.then (fn [value] (is (= {:example/id 1} value))
                           (db/entity {::db/db database :seon.db/eid 1})))
                  (.then (fn [value] (is (= {:example/id 1} value))
                           (db/index-page
                            database
                            {::db/index :eavt ::db/direction :forward
                             ::db/index-limit 2})))
                  (.then
                   (fn [page]
                     (is (= [[1 :example/id "one" 536870913 true]]
                            (:datahike.index-page/datoms page)))
                     (db/index-page
                      {::db/db database ::db/index :eavt
                       ::db/direction :forward ::db/index-limit 2})))
                  (.then
                   (fn [page]
                     (is (true? (:datahike.index-page/complete? page))))))))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "public arity contract rejected: " error))
                    (done)))))))

(deftest query-preserves-source-argument-order-and-result-shapes
  (async done
    (let [relation-form '[:find ?e :where [?e :example/id]]
          scalar-form '[:find ?e . :where [?e :example/id]]
          collection-form '[:find [?e ...] :where [?e :example/id]]
          tuple-form '[:find [?e ?name] :where [?e :example/name ?name]]
          map-form '[:find ?e ?name :keys id name
                     :where [?e :example/name ?name]]
          multisource-form
          '[:find ?a ?b ?c :in $a ?ordinary $b $c
            :where [$a ?ea :example/id ?a]
                   [$b ?eb :example/id ?b]
                   [$c ?ec :example/id ?c]]
          db-b (assoc database :db-name "contract-b")
          db-c (assoc database :db-name "contract-c")
          descriptor-shaped-data
          {:db-name "ordinary" :t 1 :as-of nil :since nil :history false
           :datahike/commit-id
           #uuid "10000000-0000-0000-0000-000000000099"}
          inputs [database [descriptor-shaped-data] db-b db-c]
          expected {relation-form #{[1] [2]}
                    scalar-form 1
                    collection-form [1 2]
                    tuple-form [1 "one"]
                    map-form [{:id 1 :name "one"}]
                    multisource-form #{[1 2 3]}}]
      (-> (with-recording-authority
            expected
            (fn [{::keys [requests]}]
              (-> (open!)
                  (.then (fn [_] (reset! requests [])
                           (js/Promise.all
                            (into-array
                             (map db/query
                                  [relation-form scalar-form collection-form
                                   tuple-form map-form])))))
                  (.then
                   (fn [values]
                     (is (= [#{[1] [2]} 1 [1 2] [1 "one"]
                             [{:id 1 :name "one"}]]
                            (js->clj values)))
                     (reset! requests [])
                     (db/query {::db/query multisource-form ::db/args inputs})))
                  (.then
                   (fn [value]
                     (is (= #{[1 2 3]} value))
                     (is (= [protocol/query-operation]
                            (mapv ::protocol/operation @requests))
                         "explicit sources do not trigger an ambient head read")
                     (is (= inputs
                            (::protocol/arguments (first @requests)))
                         "only parsed top-level sources may be rehydrated"))))))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "query shape/source contract rejected: " error))
                    (done)))))))

(deftest all-authority-operations-return-promises-of-values
  (async done
    (-> (with-recording-authority
          {'[:find ?e :where [?e :example/id]] #{[1]}}
          (fn [_]
            (-> (open!)
                (.then
                 (fn [_]
                   (let [query-result
                         (db/query '[:find ?e :where [?e :example/id]])
                         pull-result (db/pull '[*] 1)
                         transact-result
                         (db/transact! [{:db/ident :example/id}])]
                     (is (every? #(instance? js/Promise %)
                                 [query-result pull-result transact-result]))
                     (js/Promise.all #js [query-result pull-result
                                          transact-result]))))))
        (.then
         (fn [values]
           (let [report (aget values 2)]
             (is (= #{:db-before :db-after :tx-data :tempids :tx-meta}
                    (set (keys report))))
             (is (= database (:db-after report)))
             (is (= [[1 :example/id "one" 536870913 true]]
                    (:tx-data report))))
           (db/transact! {::db/tx-data [{:db/ident :example/id}]
                          ::db/tx-meta
                          {:seon.db/user [:seon.agent/id "root"]}})))
        (.then
         (fn [report]
           (is (= #{:db-before :db-after :tx-data :tempids :tx-meta}
                  (set (keys report))))
           (done)))
        (.catch (fn [error]
                  (is false (str "authority operation rejected instead of resolving: "
                                 error))
                  (done)))))))

(deftest listener-registration-replacement-and-unlisten-are-session-owned
  (async done
    (-> (with-recording-authority
          {}
          (fn [{::keys [requests]}]
            (let [first-events (atom [])
                  replacement-events (atom [])]
              (-> (open!)
                  (.then (fn [_] (reset! requests [])
                           (db/listen! {::db/key :updates
                                        ::db/handler
                                        #(swap! first-events conj %)})))
                  (.then (fn [key] (is (= :updates key))
                           (db/listen! :updates
                                       #(swap! replacement-events conj %))))
                  (.then
                   (fn [key]
                     (is (= :updates key))
                     (is (= [protocol/listen-operation protocol/listen-operation]
                            (mapv ::protocol/operation @requests))
                         "one session-owned callback map has no duplicate delivery")
                     (db/unlisten! {::db/key :updates})))
                  (.then
                   (fn [removed?]
                     (is (true? removed?))
                     (is (empty? @first-events))
                     (is (empty? @replacement-events))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "listener lifecycle contract rejected: " error))
                  (done)))))))

(deftest cancel-and-release-use-public-request-and-database-values
  (async done
    (-> (with-recording-authority
          {}
          (fn [{::keys [requests]}]
            (-> (open!)
                (.then
                 (fn [_]
                   (db/db {::db/database-name "experiment-17"})))
                (.then
                 (fn [secondary]
                   (is (= "experiment-17" (:db-name secondary)))
                   (let [cancel! (public-db-function 'cancel!)
                         release (public-db-function 'release)]
                     (is (fn? cancel!) "`seon.db/cancel!` is public")
                     (is (fn? release) "`seon.db/release` is public")
                     (when (and cancel! release)
                       (reset! requests [])
                       (-> (cancel! "query-17")
                           (.then
                            (fn [_]
                              (release secondary))))))))
                (.then
                 (fn [_]
                   (when (seq @requests)
                     (let [[cancel release] @requests]
                       (is (= "query-17" (::protocol/target-request-id cancel)))
                       (is (= "experiment-17"
                              (::protocol/target-database-name release)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "cancel/release contract rejected: " error))
                  (done)))))))
