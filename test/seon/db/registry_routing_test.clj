(ns seon.db.registry-routing-test
  "Explicit database routing and connection initialization tests."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [datahike.api :as d]
            [seon.db.branch :as branch]
            [seon.db.registry :as registry]))

(defn- isolate-registry
  [test-fn]
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})]
    (try
      (test-fn)
      (finally
        (registry/restore-registry! {::registry/snapshot snapshot})))))

(use-fixtures :each isolate-registry)

(defn- ensure-memory!
  [database-name initialize!]
  (registry/ensure-database!
   {::registry/database-name database-name
    ::registry/backend :memory
    ::registry/initialize-connection! initialize!}))

(deftest explicit-routing-never-falls-back-to-an-ambient-connection
  (let [database-names (mapv #(keyword "routing" (str "database-" %))
                             (range 8))
        connections
        (into {}
              (map (fn [database-name]
                     [database-name
                      (::registry/conn
                       (ensure-memory! database-name
                                       (fn [_request] nil)))]))
              database-names)]
    (doseq [[database-name connection] connections]
      (let [resolved
            (registry/resolve-connection
             {::registry/database-name database-name})]
        (is (identical? connection (::registry/conn resolved)))
        (is (= database-name (::registry/database-name resolved)))))
    (is (= (count database-names) (count (set (vals connections)))))
    (let [missing
          (registry/resolve-connection
           {::registry/database-name :routing/missing})]
      (is (= :seon.db.registry.error/not-found
             (::registry/error-kind missing)))
      (is (not (contains? missing ::registry/conn))))))

(deftest initializer-runs-once-before-the-entry-is-published
  (let [database-name :routing/initialized
        calls (atom [])
        reports (atom [])
        initialize!
        (fn [{::registry/keys [conn database-name connection-id open-intent]}]
          (swap! calls conj [database-name (some? @conn) connection-id open-intent])
          (d/listen conn ::capture #(swap! reports conj %)))
        first-entry (ensure-memory! database-name initialize!)
        second-entry (ensure-memory! database-name initialize!)
        connection (::registry/conn first-entry)]
    (is (identical? connection (::registry/conn second-entry)))
    (is (= [[database-name true
             (::registry/connection-id first-entry)
             :seon.db.registry.open/main]]
           @calls))
    (d/transact connection
                [{:db/ident :routing/value
                  :db/valueType :db.type/string
                  :db/cardinality :db.cardinality/one}
                 {:routing/value "visible"}])
    (is (= 1 (count @reports)))
    (is (= "visible"
           (d/q '[:find ?value . :where [_ :routing/value ?value]]
                (d/db connection))))))

(deftest failed-initialization-leaves-no-half-open-entry
  (let [database-name :routing/failure
        failure
        (try
          (ensure-memory!
           database-name
           (fn [_request]
             (throw (ex-info "initializer failed" {}))))
          nil
          (catch clojure.lang.ExceptionInfo exception exception))]
    (is (= "initializer failed" (.getMessage failure)))
    (is (= {} (registry/lookup-connection
               {::registry/database-name database-name})))
    (is (some?
         (::registry/conn
          (ensure-memory! database-name (fn [_request] nil)))))))

(deftest non-main-initializer-must-be-observational
  (let [main-name :routing/observational-main
        branch-name :routing/observational-branch
        main (ensure-memory! main-name (fn [_request] nil))
        main-connection (::registry/conn main)
        branch :routing.branch/observational
        connection-id (assoc (::registry/connection-id main) 1 branch)]
    (d/branch! main-connection :db branch)
    (let [before (branch/head
                  (d/branch-as-db main-connection branch))
          failure
          (try
            (registry/ensure-database!
             {::registry/database-name branch-name
              ::registry/backend :memory
              ::registry/connection-id connection-id
              ::registry/initialize-connection!
              (fn [{::registry/keys [conn open-intent]}]
                (is (= :seon.db.registry.open/branch open-intent))
                (d/transact conn
                            [{:db/ident :routing.branch/value
                              :db/valueType :db.type/string
                              :db/cardinality :db.cardinality/one}]))})
            nil
            (catch clojure.lang.ExceptionInfo exception exception))]
      (is (= :seon.db.registry.error/branch-initializer-wrote
             (:seon.error/kind (ex-data failure))))
      (is (= before (::registry/branch-head-before (ex-data failure))))
      (is (not= before (::registry/branch-head-after (ex-data failure))))
      (is (= {} (registry/lookup-connection
                 {::registry/database-name branch-name}))))))

(deftest failed-branch-initializer-retains-unproved-cleanup-identity
  (let [main-name :routing/cleanup-main
        branch-name :routing/cleanup-branch
        main (ensure-memory! main-name (fn [_request] nil))
        main-connection (::registry/conn main)
        branch :routing.branch/cleanup
        connection-id (assoc (::registry/connection-id main) 1 branch)]
    (d/branch! main-connection :db branch)
    (let [failure
          (with-redefs [d/release
                        (fn [_connection]
                          (throw (ex-info "injected open cleanup failure" {})))]
            (try
              (registry/ensure-database!
               {::registry/database-name branch-name
                ::registry/backend :memory
                ::registry/connection-id connection-id
                ::registry/initialize-connection!
                (fn [{::registry/keys [conn]}]
                  (d/transact conn
                              [{:db/ident :routing.cleanup/value
                                :db/valueType :db.type/string
                                :db/cardinality :db.cardinality/one}]))})
              nil
              (catch clojure.lang.ExceptionInfo exception exception)))
          retained
          (first
           (filter #(= branch-name (::registry/database-name %))
                   (::registry/databases (registry/list-databases {}))))
          resolved
          (registry/resolve-connection
           {::registry/database-name branch-name})
          release
          (registry/release-database!
           {::registry/database-name branch-name})]
      (is (= :seon.db.registry.error/cleanup-required
             (:seon.error/kind (ex-data failure))))
      (is (= :seon.db.registry.entry/cleanup-required
             (::registry/entry-state retained)))
      (is (= connection-id (::registry/connection-id retained)))
      (is (re-find #"branch initializer changed"
                   (::registry/initialization-error retained)))
      (is (re-find #"injected open cleanup failure"
                   (::registry/release-error retained)))
      (is (= {} (registry/lookup-connection
                 {::registry/database-name branch-name})))
      (is (= :seon.db.registry.error/cleanup-required
             (::registry/error-kind resolved)))
      (is (false? (::registry/released? release)))
      (is (= (::registry/release-error retained)
             (::registry/release-error release))))))
