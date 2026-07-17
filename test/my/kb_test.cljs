(ns my.kb-test
  "Current `my.kb` schema and immutable database-value behavior."
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is async]]
    [my.data :as data]
    [my.kb :as kb]
    [my.kb.shared :as kb-shared]
    [seon.db :as db]
    [seon.embed :as embed]
    [seon.schema :as schema]))

(defn- as-database-fn
  [f]
  (fn
    ([] (f))
    ([_] (f))))

(defn- as-query-fn
  [f]
  (fn
    ([request] (f request))
    ([query-form & inputs] (apply f query-form inputs))))

(defn- as-pull-fn
  [f]
  (fn
    ([request] (f request))
    ([selector eid] (f selector eid))
    ([database selector eid] (f database selector eid))))

(defn- as-pull-many-fn
  [f]
  (fn
    ([request] (f request))
    ([selector eids] (f selector eids))
    ([database selector eids] (f database selector eids))))

(defn- as-installed-schema-fn
  [f]
  (fn
    ([] (f nil))
    ([request] (f request))))

(defn- as-transact-fn
  [f]
  (fn [& call-args] (apply f call-args)))

(defn- with-kb-fakes
  [{query-fn ::query-fn
    pull-fn ::pull-fn
    transact-fn ::transact-fn
    rows-fn ::rows-fn}
   body]
  (let [saved {::query-fn db/query
               ::pull-fn db/pull
               ::transact-fn db/transact!
               ::rows-fn data/rows}]
    (set! db/query (if query-fn (as-query-fn query-fn) db/query))
    (set! db/pull (if pull-fn (as-pull-fn pull-fn) db/pull))
    (set! db/transact!
          (if transact-fn (as-transact-fn transact-fn) db/transact!))
    (set! data/rows (or rows-fn data/rows))
    (-> (js/Promise.resolve (body))
        (.finally
          (fn []
            (set! db/query (::query-fn saved))
            (set! db/pull (::pull-fn saved))
            (set! db/transact! (::transact-fn saved))
            (set! data/rows (::rows-fn saved)))))))

(defn- finish
  [promise done]
  (-> promise
      (.then (fn [_] (done)))
      (.catch (fn [error]
                (is false (str "threw — " error))
                (done)))))

(def ^:private shared-database
  {:db-name "default"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id
   #uuid "00000000-0000-0000-0000-000000000042"})

(defn- with-recall-fakes
  [{database-fn ::database-fn
    installed-schema-fn ::installed-schema-fn
    query-fn ::query-fn
    pull-many-fn ::pull-many-fn
    embedding-enabled-fn ::embedding-enabled-fn
    search-pull-fn ::search-pull-fn}
   body]
  (let [saved {::database-fn db/db
               ::installed-schema-fn db/installed-schema
               ::query-fn db/query
               ::pull-many-fn db/pull-many
               ::embedding-enabled-fn embed/enabled?
               ::search-pull-fn embed/search-pull}]
    (set! db/db (as-database-fn database-fn))
    (set! db/installed-schema
          (as-installed-schema-fn installed-schema-fn))
    (set! db/query (as-query-fn query-fn))
    (set! db/pull-many (as-pull-many-fn pull-many-fn))
    (set! embed/enabled? embedding-enabled-fn)
    (set! embed/search-pull search-pull-fn)
    (-> (js/Promise.resolve (body))
        (.finally
          (fn []
            (set! db/db (::database-fn saved))
            (set! db/installed-schema (::installed-schema-fn saved))
            (set! db/query (::query-fn saved))
            (set! db/pull-many (::pull-many-fn saved))
            (set! embed/enabled? (::embedding-enabled-fn saved))
            (set! embed/search-pull (::search-pull-fn saved)))))))

(deftest shared-provenance-shapes-and-seed-remain-canonical
  (is (= :string (schema/schema-definition :my.kb/source-path)))
  (is (= :int (schema/schema-definition :my.kb/source-line)))
  (is (= :int (schema/schema-definition :my.kb/source-line-end)))
  (is (= :inst (schema/schema-definition :my.kb/verified-at)))
  (is (= [:enum :verified :inferred]
         (schema/schema-definition :my.kb/confidence)))
  (is (= [{:my.kb.shared/id "shared"}]
         (kb-shared/seed-tx-data))))

(deftest shared-instructions-remain-ordered-derived-data
  (async done
    (let [later (js/Date. 2000)
          earlier (js/Date. 1000)
          requests (atom [])]
      (finish
        (with-kb-fakes
          {::query-fn
           (fn [request]
             (swap! requests conj request)
             (js/Promise.resolve
               #{[later "Prefer the existing schema."]
                 [earlier "Store provenance."]}))}
          #(-> (kb-shared/instructions {::db/db shared-database})
               (.then
                 (fn [result]
                   (is (= ["Store provenance."
                           "Prefer the existing schema."]
                          result))
                   (is (= 1 (count @requests)))
                   (is (identical? shared-database
                                   (::db/db (first @requests))))))))
        done))))

(deftest shared-instructions-pure-formatter-orders-and-omits
  (let [format-block (deref #'kb-shared/format-instructions-block)]
    (is (= "" (format-block [])))
    (is (= [";   1. First." ";   2. Second."]
           (take-last 2
                      (str/split-lines
                       (format-block ["First." "Second."])))))))

(deftest shared-instructions-block-awaits-acquisition-before-rendering
  (async done
    (let [query-request (atom nil)
          pending
          (with-kb-fakes
            {::query-fn
            (fn [request]
               (reset! query-request request)
               (js/Promise.resolve
                #{[(js/Date. 1000) "Use the one existing owner."]}))}
            #(db/with-tx-context
              {::db/db shared-database}
              (fn [] (kb-shared/instructions-block {}))))]
      (is (instance? js/Promise pending))
      (finish
       (-> pending
           (.then
            (fn [rendered]
              (is (string? rendered))
              (is (str/ends-with?
                   rendered ";   1. Use the one existing owner."))
              (is (identical? shared-database
                              (::db/db @query-request))))))
       done))))

(deftest shared-instructions-preserve-a-direct-query-error
  (async done
    (let [database-error {:seon.error/message "query unavailable"
                          :seon.error/kind :core-bug}]
      (finish
       (with-kb-fakes
        {::query-fn (fn [_] (js/Promise.resolve database-error))}
        #(-> (kb-shared/instructions {::db/db shared-database})
             (.then
              (fn [result]
                (is (identical? database-error result))))))
       done))))

(deftest shared-instructions-block-routes-database-errors-to-render-failure
  (async done
    (let [database-error {:seon.error/message "query unavailable"
                          :seon.error/kind :core-bug}]
      (-> (with-kb-fakes
           {::query-fn (fn [_] (js/Promise.resolve database-error))}
           #(kb-shared/instructions-block {::db/db shared-database}))
          (.then
           (fn [_]
             (is false "a database error reached the render tree")))
          (.catch
           (fn [exception]
             (is (= "query unavailable"
                    (.-message exception)))
             (is (identical? database-error
                             (ex-data exception)))))
          (.finally done)))))

(deftest knowledge-base-write-recipes-keep-one-canonical-transaction-shape
  (async done
    (let [requests (atom [])]
      (finish
        (-> (with-kb-fakes
              {::transact-fn
               (fn [& call-args]
                 (swap! requests conj (first call-args))
                 (js/Promise.resolve {:seon.db/ok? true}))}
              #(js/Promise.all
                 #js [(kb/remember-sources!)
                      (kb/retitle-source! "s1" "Revised")
                      (kb/clear-rating! "s2")
                      (kb/replace-topics! "s1" [:lisp :history])
                      (kb/forget-source! "s3")]))
            (.then
              (fn [_]
                (is (= 5 (count @requests)))
                (let [[seed retitle clear replace forget]
                      (map :seon.db/tx-data @requests)]
                  (is (= 5 (count seed))
                      "the worked example still seeds five entities")
                  (is (= [{:my.kb.source/id "s1"
                           :my.kb.source/title "Revised"}]
                         retitle))
                  (is (= [[:db/retract
                           [:my.kb.source/id "s2"]
                           :my.kb.source/rating]]
                         clear))
                  (is (= [[:db/retract
                           [:my.kb.source/id "s1"]
                           :my.kb.source/topics]
                          {:my.kb.source/id "s1"
                           :my.kb.source/topics [:lisp :history]}]
                         replace))
                  (is (= [[:db.fn/retractEntity
                           [:my.kb.source/id "s3"]]]
                         forget))))))
        done))))

(deftest remember-preserves-parsed-provenance-and-identity-upsert
  (async done
    (let [requests (atom [])
          response {:seon.db/ok? true
                    :seon.db/tempids {"finding" 101}}]
      (finish
        (-> (with-kb-fakes
              {::transact-fn
               (fn [& call-args]
                 (swap! requests conj (first call-args))
                 (js/Promise.resolve response))}
              #(-> (kb/remember
                     {:my.kb/claim "Entities are attributes and connections."
                      :my.kb/source "src/seon/db.cljs:42"
                      :my.kb/confidence :verified})
                   (.then
                     (fn [result]
                       (is (= {:my.kb/id 101} result))
                       (kb/remember
                         {:my.kb/claim "Entities are attributes and connections."
                          :my.kb/source "src/seon/db.cljs:42"
                          :my.kb/confidence :inferred})))))
            (.then
              (fn [result]
                (is (= {:my.kb/id 101} result))
                (let [[first-row second-row]
                      (map (comp first :seon.db/tx-data) @requests)]
                  (is (= "finding" (:db/id first-row)))
                  (is (= (:my.kb/claim first-row)
                         (:my.kb/claim second-row))
                      "repeating a claim keeps the same identity upsert")
                  (is (= "src/seon/db.cljs" (:my.kb/source-path first-row)))
                  (is (= 42 (:my.kb/source-line first-row)))
                  (is (= :verified (:my.kb/confidence first-row)))
                  (is (= :inferred (:my.kb/confidence second-row)))
                  (is (inst? (:my.kb/verified-at first-row)))))))
        done))))

(deftest read-recipes-and-worked-summary-preserve-their-results
  (async done
    (let [query-results (atom [["A" "B"]
                               #{["A" 5] ["B" 4]}
                               ["A"]])
          source {:db/id 101
                  :my.kb.source/id "s1"
                  :my.kb.source/title "A"}
          rows {:seon.items/items
                [{:my.kb.source/rating 5
                  :my.kb.source/topics [:lisp :foundations]}
                 {:my.kb.source/rating 4
                  :my.kb.source/topics [:lisp]}]
                :seon.items/count 2}]
      (finish
        (with-kb-fakes
          {::query-fn
           (fn
             ([_]
              (let [result (first @query-results)]
                (swap! query-results subvec 1)
                (js/Promise.resolve result)))
             ([_ & _]
              (let [result (first @query-results)]
                (swap! query-results subvec 1)
                (js/Promise.resolve result))))
           ::pull-fn
           (fn
             ([_] (js/Promise.resolve source))
             ([_ _] (js/Promise.resolve source))
             ([_ _ _] (js/Promise.resolve source)))
           ::rows-fn (fn [_] (js/Promise.resolve rows))}
          #(-> (kb/titles)
               (.then (fn [result] (is (= ["A" "B"] result))
                        (kb/title+rating)))
               (.then (fn [result] (is (= #{["A" 5] ["B" 4]} result))
                        (kb/titles-by-author "Author")))
               (.then (fn [result] (is (= ["A"] result))
                        (kb/source-detail "s1")))
               (.then (fn [result] (is (= source result))
                        (kb/source-entity "s1")))
               (.then (fn [result] (is (= source result))
                        (kb/source-stats)))
               (.then
                 (fn [result]
                   (is (= {:my.kb/count 2
                           :my.kb/rating-total 9
                           :my.kb/topic-counts {:lisp 2 :foundations 1}}
                          result))))))
        done))))

(deftest recall-reuses-one-immutable-database-value
  (async done
    (let [database {:db-name "default" :t 7}
          calls (atom [])]
      (finish
        (-> (with-recall-fakes
              {::database-fn
               (fn []
                 (swap! calls conj [::database])
                 (js/Promise.resolve database))
               ::installed-schema-fn
               (fn [actual]
                 (swap! calls conj [::installed-schema actual])
                 (js/Promise.resolve
                   {:my.kb/claim {:db/valueType :db.type/string}}))
               ::query-fn
               (fn [request]
                 (swap! calls conj [::query request])
                 (js/Promise.resolve
                   [[101 :my.kb/claim "alpha beta"]]))
               ::pull-many-fn
               (fn [request]
                 (swap! calls conj [::pull-many request])
                 (js/Promise.resolve
                   [{:db/id 101 :my.kb/claim "alpha beta"}]))
               ::embedding-enabled-fn (constantly true)
               ::search-pull-fn
               (fn [request]
                 (swap! calls conj [::search-pull request])
                 (js/Promise.resolve {:seon.embed/hits []}))}
              #(kb/recall {:my.kb/about "alpha"}))
            (.then
              (fn [result]
                (is (true? (:seon.result/ok? result)))
                (is (= 1 (count (filter #(= ::database (first %)) @calls))))
                (is (identical?
                      database
                      (second (first (filter #(= ::installed-schema (first %))
                                             @calls)))))
                (doseq [[operation request]
                        (filter #(contains? #{::query ::pull-many ::search-pull}
                                            (first %))
                                @calls)]
                  (is (identical? database (:seon.db/db request))
                      (str operation " reused the acquired database value")))
                (is (= :text (:my.kb/match
                                (first (:seon.items/items result))))))))
        done))))

(deftest recall-database-error-is-a-failure-value
  (async done
    (let [database-error {:seon.error/message "database unavailable"}
          downstream (atom 0)]
      (finish
        (-> (with-recall-fakes
              {::database-fn (fn [] (js/Promise.resolve database-error))
               ::installed-schema-fn
               (fn [_] (swap! downstream inc) (js/Promise.resolve {}))
               ::query-fn
               (fn [_] (swap! downstream inc) (js/Promise.resolve []))
               ::pull-many-fn
               (fn [_] (swap! downstream inc) (js/Promise.resolve []))
               ::embedding-enabled-fn (constantly false)
               ::search-pull-fn
               (fn [_] (swap! downstream inc)
                 (js/Promise.resolve {:seon.embed/hits []}))}
              #(kb/recall {:my.kb/about "alpha"}))
            (.then
              (fn [result]
                (is (false? (:seon.result/ok? result)))
                (is (re-find #"database unavailable" (:my.kb/error result)))
                (is (zero? @downstream)
                    "a failed database acquisition performs no reads"))))
        done))))

(deftest recall-query-error-is-not-empty-success
  (async done
    (let [database {:db-name "default" :t 9}
          query-error {:seon.error/message "query failed"}
          pulls (atom 0)]
      (finish
        (-> (with-recall-fakes
              {::database-fn (fn [] (js/Promise.resolve database))
               ::installed-schema-fn
               (fn [_]
                 (js/Promise.resolve
                   {:my.kb/claim {:db/valueType :db.type/string}}))
               ::query-fn (fn [_] (js/Promise.resolve query-error))
               ::pull-many-fn
               (fn [_] (swap! pulls inc) (js/Promise.resolve []))
               ::embedding-enabled-fn (constantly false)
               ::search-pull-fn
               (fn [_] (js/Promise.resolve {:seon.embed/hits []}))}
              #(kb/recall {:my.kb/about "alpha"}))
            (.then
              (fn [result]
                (is (false? (:seon.result/ok? result)))
                (is (re-find #"query failed" (:my.kb/error result)))
                (is (zero? @pulls)))))
        done))))

(deftest recall-ranks-domain-and-claim-rows-and-reports-the-uncapped-total
  (async done
    (let [database {:db-name "default" :t 14}
          pulled-by-eid
          {101 {:db/id 101
                :my.kb.source/title
                "Recursive Functions of Symbolic Expressions"}
           102 {:db/id 102
                :my.kb.source/title "LISP 1.5 Programmer's Manual"}}]
      (finish
        (-> (with-recall-fakes
              {::database-fn (fn [] (js/Promise.resolve database))
               ::installed-schema-fn
               (fn [_]
                 (js/Promise.resolve
                   {:my.kb/claim {:db/valueType :db.type/string}
                    :my.kb.source/title {:db/valueType :db.type/string}}))
               ::query-fn
               (fn [_]
                 (js/Promise.resolve
                   [[101 :my.kb.source/title
                     "Recursive Functions of Symbolic Expressions"]
                    [102 :my.kb.source/title
                     "LISP 1.5 Programmer's Manual"]]))
               ::pull-many-fn
               (fn [{:seon.db/keys [eids]}]
                 (js/Promise.resolve (mapv pulled-by-eid eids)))
               ::embedding-enabled-fn (constantly false)
               ::search-pull-fn
               (fn [_] (js/Promise.resolve {:seon.embed/hits []}))}
              #(kb/recall {:my.kb/about "recursive symbolic manual"
                           :my.kb/limit 1}))
            (.then
              (fn [result]
                (is (true? (:seon.result/ok? result)))
                (is (= 1 (:seon.items/count result)))
                (is (= 2 (:my.kb/matched result))
                    "the limit does not hide the total number of matches")
                (is (= 101 (:db/id (first (:seon.items/items result)))))
                (is (= 2 (:my.kb/matched-tokens
                           (first (:seon.items/items result))))))))
        done))))

(deftest recall-empty-and-no-match-remain-success
  (async done
    (let [database {:db-name "default" :t 15}
          queries (atom 0)
          pulls (atom 0)]
      (finish
        (-> (with-recall-fakes
              {::database-fn (fn [] (js/Promise.resolve database))
               ::installed-schema-fn
               (fn [_]
                 (js/Promise.resolve
                   {:my.kb/claim {:db/valueType :db.type/string}}))
               ::query-fn
               (fn [_]
                 (swap! queries inc)
                 (js/Promise.resolve
                   [[101 :my.kb/claim "unrelated stored fact"]]))
               ::pull-many-fn
               (fn [_]
                 (swap! pulls inc)
                 (js/Promise.resolve []))
               ::embedding-enabled-fn (constantly false)
               ::search-pull-fn
               (fn [_] (js/Promise.resolve {:seon.embed/hits []}))}
              #(kb/recall {:my.kb/about "quantum blockchain"}))
            (.then
              (fn [result]
                (is (true? (:seon.result/ok? result)))
                (is (= [] (:seon.items/items result)))
                (is (zero? (:seon.items/count result)))
                (is (zero? (:my.kb/matched result)))
                (is (= 1 @queries))
                (is (zero? @pulls)
                    "no selected entity means no empty pull request"))))
        done))))
