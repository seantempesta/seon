(ns seon.embed-test
  "Behavior tests for the pod-side `seon.embed` database-value boundary.

   Class rule (docs/conventions.md \"Errors Are Values\" consequence 3): a
   specced `^:async` fn must NEVER reject with an expected error — a writer
   knn-search failure RESOLVES to `{::embed/hits [] :seon/error {…}}`.

   The database operation is stubbed by `set!` on `db/knn-search!`
   (restored in a
   `.finally` link), NOT `with-redefs`: this fork's `with-redefs` AWAITS an
   async body (so the form yields the resolved value and asyncifies the
   enclosing fn, breaking the `(async done …)` contract)."
  (:require
    [cljs.test :refer-macros [deftest is async]]
    [seon.db :as db]
    [seon.embed :as embed]
    [seon.test.async :refer [settle!]]))

(def ^:private database
  {:db-name "default"
   :t 536870912
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "00000000-0000-0000-0000-000000000102"})

(defn- stub-search
  "A map-in KNN stub resolving to `reply`."
  [reply]
  (fn [_request] (js/Promise.resolve reply)))

(defn- with-db-stubs
  "Run `f` with selected asynchronous database functions replaced."
  [db-fn query-fn knn-fn pull-many-fn f]
  (let [original-db db/db
        original-query db/query
        original-knn db/knn-search!
        original-pull-many db/pull-many]
    (when db-fn (set! db/db db-fn))
    (when query-fn (set! db/query query-fn))
    (when knn-fn (set! db/knn-search! knn-fn))
    (when pull-many-fn (set! db/pull-many pull-many-fn))
    (.finally
     (f)
     (fn []
       (set! db/db original-db)
       (set! db/query original-query)
       (set! db/knn-search! original-knn)
       (set! db/pull-many original-pull-many)))))

(def ^:private failed-reply
  {:seon.error/kind :core-bug
   :seon.error/message "writer down (stub)"})

(deftest search-resolves-error-envelope-on-wire-failure
  ;; The old behavior THREW (→ rejected Promise → instrument wrapper records
  ;; a :core fault → :crash exits the dev pod). The fix: resolve an envelope.
  (async done
    (-> (with-db-stubs nil nil (stub-search failed-reply) nil
          #(embed/search {:seon.embed/query "anything"
                          :seon.db/db database}))
        (.then (fn [{:seon.embed/keys [hits] :as res}]
                 (is (= [] hits) "hits key present and empty on failure")
                 (let [err (:seon/error res)]
                   (is (map? err) "carries a :seon/error map")
                   (is (= :core-bug (:seon.error/kind err)))
                   (is (re-find #"writer down"
                                (str (:seon.error/message err)))
                       "message surfaces the wire error"))))
        (settle! done))))

(deftest search-pull-passes-error-envelope-through-unchanged
  (async done
    (-> (with-db-stubs nil nil (stub-search failed-reply) nil
          #(embed/search-pull {:seon.embed/query "anything"
                               :seon.db/db database}))
        (.then (fn [res]
                 (is (= [] (:seon.embed/hits res)))
                 (is (some? (:seon/error res))
                     "search's :seon/error passes through unchanged")))
        (settle! done))))

(deftest search-resolves-hits-on-ok-wire-reply
  (async done
    (let [hits [{:seon.embed/eid 7 :seon.embed/distance 0.25}]]
      (-> (with-db-stubs nil nil (stub-search hits) nil
            #(embed/search {:seon.embed/query "anything"
                            :seon.db/db database}))
          (.then (fn [res]
                   (is (= hits (:seon.embed/hits res)))
                   (is (nil? (:seon/error res)))))
          (settle! done)))))

(deftest search-acquires-one-database-value-for-scope-and-knn
  (async done
    (let [acquisitions (atom 0)
          query-request (atom nil)
          knn-request (atom nil)]
      (-> (with-db-stubs
            (fn
              ([]
               (swap! acquisitions inc)
               (js/Promise.resolve database))
              ([_request]
               (swap! acquisitions inc)
               (js/Promise.resolve database)))
            (fn
              ([request]
               (reset! query-request request)
               (js/Promise.resolve [[7]]))
              ([_query-form & _inputs]
               (js/Promise.resolve [[7]])))
            (fn [request]
              (reset! knn-request request)
              (js/Promise.resolve
               [{:seon.embed/eid 7 :seon.embed/distance 0.25}]))
            nil
            #(embed/search
              {:seon.embed/query "anything"
               :seon.embed/where
               '[[?e :seon.fn/sym]]}))
          (.then
           (fn [result]
             (is (= 1 @acquisitions))
             (is (identical? database (:seon.db/db @query-request)))
             (is (identical? database (:seon.db/db @knn-request)))
             (is (= [7]
                    (:seon.db.protocol/entity-ids @knn-request)))
             (is (= 7 (-> result :seon.embed/hits first :seon.embed/eid)))))
          (settle! done)))))

(deftest search-pull-reuses-one-database-value
  (async done
    (let [acquisitions (atom 0)
          knn-request (atom nil)
          pull-request (atom nil)]
      (-> (with-db-stubs
            (fn
              ([]
               (swap! acquisitions inc)
               (js/Promise.resolve database))
              ([_request]
               (swap! acquisitions inc)
               (js/Promise.resolve database)))
            nil
            (fn [request]
              (reset! knn-request request)
              (js/Promise.resolve
               [{:seon.embed/eid 7 :seon.embed/distance 0.25}]))
            (fn
              ([request]
               (reset! pull-request request)
               (js/Promise.resolve [{:seon.fn/sym "demo/f"}]))
              ([_selector _entity-ids]
               (js/Promise.resolve [{:seon.fn/sym "demo/f"}]))
              ([_database _selector _entity-ids]
               (js/Promise.resolve [{:seon.fn/sym "demo/f"}])))
            #(embed/search-pull {:seon.embed/query "anything"}))
          (.then
           (fn [result]
             (is (= 1 @acquisitions))
             (is (identical? database (:seon.db/db @knn-request)))
             (is (identical? database (:seon.db/db @pull-request)))
             (is (= '[*] (:seon.db/selector @pull-request)))
             (is (= [7] (:seon.db/eids @pull-request)))
             (is (= "demo/f"
                    (-> result :seon.embed/hits first
                        :seon.embed/entity :seon.fn/sym)))))
          (settle! done)))))
