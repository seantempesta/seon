(ns seon.db.registry-routing-test
  "Explicit database routing and connection initialization tests."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [datahike.api :as d]
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
                                       (fn [_connection _name] nil)))]))
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
        (fn [connection seen-name]
          (swap! calls conj [seen-name (some? @connection)])
          (d/listen connection ::capture #(swap! reports conj %)))
        first-entry (ensure-memory! database-name initialize!)
        second-entry (ensure-memory! database-name initialize!)
        connection (::registry/conn first-entry)]
    (is (identical? connection (::registry/conn second-entry)))
    (is (= [[database-name true]] @calls))
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
           (fn [_connection _name]
             (throw (ex-info "initializer failed" {}))))
          nil
          (catch clojure.lang.ExceptionInfo exception exception))]
    (is (= "initializer failed" (.getMessage failure)))
    (is (= {} (registry/lookup-connection
               {::registry/database-name database-name})))
    (is (some?
         (::registry/conn
          (ensure-memory! database-name (fn [_connection _name] nil)))))))
