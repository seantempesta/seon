(ns seon.db.time-travel-test
  "Behavioral proofs for immutable temporal database views."
  (:require
    [cljs.test :refer [async deftest is]]
    [datahike.api :as d]
    [seon.db :as db]
    [seon.db.coordinate :as coordinate]))

(def ^:private value-attr :seon.db.time-travel-test/value)

(defn- fresh-history-conn
  "Open an isolated historical database with one identity attribute."
  []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then
          (fn [conn]
            (-> (d/transact! conn [{:db/ident value-attr
                                    :db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one
                                    :db/unique :db.unique/identity}])
                (.then (fn [_] conn))))))))

(deftest temporal-views-report-the-state-they-actually-represent
  (async done
    (-> (fresh-history-conn)
        (.then
          (fn [conn]
            (-> (d/transact! conn [{value-attr "first"}])
                (.then
                  (fn [first-report]
                    (let [first-t (-> first-report :db-after :max-tx)]
                      (-> (d/transact! conn [{value-attr "second"}])
                          (.then
                            (fn [second-report]
                              (let [head @conn
                                    head-t (-> second-report :db-after :max-tx)
                                    past (db/as-of head first-t)
                                    delta (db/since head first-t)]
                                (is (= first-t (db/basis-t past)))
                                (is (= head-t (db/basis-t delta)))
                                (is (= #{["first"]}
                                       (d/q [:find '?v :where ['_ value-attr '?v]]
                                            past)))
                                (is (= #{["second"]}
                                       (d/q [:find '?v :where ['_ value-attr '?v]]
                                            delta)))))))))))))
        (.catch (fn [error]
                  (is false (str "temporal view proof rejected: " error))))
        (.then (fn [_] (done))))))

(deftest complete-coordinate-pins-the-container-before-selecting-t
  (async done
    (-> (fresh-history-conn)
        (.then
         (fn ^:async verify [conn]
           (try
             (let [first-report
                   (await (d/transact! conn [{value-attr "first"}]))
                   first-t (-> first-report :db-after :max-tx)
                   second-report
                   (await (d/transact! conn [{value-attr "second"}]))
                   container (:db-after second-report)
                   head-point
                   (coordinate/at
                    {::coordinate/db-value container
                     ::coordinate/target-t (db/basis-t container)})
                   point
                   (coordinate/at
                    {::coordinate/db-value container
                     ::coordinate/target-t first-t})
                   exact-head (await (db/at-coordinate conn head-point))
                   historical (await (db/at-coordinate conn point))]
               (is (= head-point (db/head-coordinate exact-head))
                   "an exact-head cut remains a coordinate-resolvable committed db")
               (is (= #{["first"] ["second"]}
                      (d/q [:find '?v :where ['_ value-attr '?v]] exact-head))
                   "an exact-head cut reads the containing commit without a wrapper")
               (is (not (:seon.error/message historical)))
               (is (= first-t (db/basis-t historical)))
               (is (try
                     (db/head-coordinate historical)
                     false
                     (catch :default _ true))
                   "a strict historical cut remains a temporal, non-container value")
               (is (= #{["first"]}
                      (d/q [:find '?v :where ['_ value-attr '?v]] historical)))
               (let [wrong-branch
                     (await
                      (db/at-coordinate
                       conn (assoc point ::coordinate/branch :experiment)))
                     missing-commit
                     (await
                      (db/at-coordinate
                       conn (assoc point ::coordinate/commit-id
                                   (random-uuid))))
                     partial
                     (await
                      (db/at-coordinate conn
                                        (dissoc point ::coordinate/commit-id)))
                     out-of-range
                     (await
                      (db/at-coordinate
                       conn (assoc point ::coordinate/t
                                   (inc (db/basis-t container)))))]
                 (is (= :user-input (:seon.error/kind wrong-branch)))
                 (is (= :user-input (:seon.error/kind missing-commit)))
                 (is (= :user-input (:seon.error/kind partial)))
                 (is (= :invalid-database-coordinate
                        (:seon.error/kind out-of-range)))))
             (finally
               (await (d/release conn))))))
        (.catch
         (fn [error]
           (is false (str "coordinate resolver proof rejected: " error))))
        (.then (fn [_] (done))))))

(deftest fork-coordinate-reattaches-a-shared-commit-to-the-requested-branch
  (async done
    (-> (fresh-history-conn)
        (.then
          (fn ^:async verify [conn]
            (try
              (let [first-report
                    (await (d/transact! conn [{value-attr "fork-first"}]))
                    first-t (-> first-report :db-after :max-tx)
                    _ (await (d/transact! conn [{value-attr "fork-head"}]))
                    source-head @conn
                    shared-cid (d/commit-id source-head)
                    branch :seon.db.time-travel-test/fork
                    _ (await (d/branch! conn shared-cid branch))
                    fork-conn
                    (await (d/connect (assoc (:config source-head)
                                            :branch branch)))]
                (try
                  (let [point (coordinate/resolved @fork-conn)
                        exact-head (await (db/at-coordinate fork-conn point))
                        historical
                        (await (db/at-coordinate
                                 fork-conn
                                 (assoc point ::coordinate/t first-t)))
                        _ (await (d/transact! fork-conn
                                             [{value-attr "fork-later"}]))]
                    (is (= branch (::coordinate/branch point)))
                    (is (= point (db/head-coordinate exact-head))
                        "a shared commit is reattached to the requested fork")
                    (is (= #{["fork-first"] ["fork-head"]}
                           (d/q [:find '?v :where ['_ value-attr '?v]]
                                exact-head))
                        "the exact retained value stays frozen after the fork advances")
                    (is (= first-t (db/basis-t historical)))
                    (is (= branch
                           (get-in historical [:origin-db :config :branch]))
                        "a strict historical wrapper retains the requested attachment"))
                  (finally
                    (await (d/release fork-conn)))))
              (finally
                (await (d/release conn))))))
        (.catch
          (fn [error]
            (is false (str "fork coordinate proof rejected: " error))))
        (.then (fn [_] (done))))))
