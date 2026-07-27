(ns seon.reactive-test
  (:require
   [cljs.test :refer [async deftest is]]
   [seon.db :as db]
   [seon.reactive :as reactive]))

(def ^:private database
  {:db-name "reactive"
   :store-id [#uuid "10000000-0000-0000-0000-000000000000" :db]
   :t 536870913
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "10000000-0000-0000-0000-000000000001"})

(defn- at-t [t]
  (assoc database :t t :datahike/commit-id (random-uuid)))

(defn- evidence [value]
  [{::db/db value
    ::db/source-argument-position 0
    :datahike.read/dependency-plan
    {:datahike.query.dependency/sources
     [{:datahike.query.source/symbol '$
       :datahike.query.source/argument-position 0
       :datahike.query.source/attributes #{:example/value}}]}}])

(defn- next-turn []
  (js/Promise. (fn [resolve _] (js/setTimeout resolve 5))))

(deftest maximum-latency-bounds-a-moving-settle-edge
  (let [prior @@#'reactive/!policy]
    (try
      (reactive/configure!
       {:seon.config/reactive-settle-ms 1000
        :seon.config/reactive-structural-settle-ms 1000
        :seon.config/reactive-max-latency-ms 20})
      (is (= 120
             (@#'reactive/due-at
              {::reactive/dirty-at 100
               ::reactive/pending-settle-ms 1000}
              500))
          "continuous transactions cannot postpone demanded progress")
      (finally
        (reactive/configure! prior)))))

(deftest maximum-latency-delivers-progress-during-a-continuous-stream
  (async done
    (let [original-db db/db
          original-listen db/listen!
          original-unlisten db/unlisten!
          head (atom database)
          listens (atom [])
          delivered (atom [])
          latest-t (atom (:t database))
          completed-before-stop (atom nil)
          compute (fn [value]
                    (js/Promise.resolve
                     {::db/value (:t value)
                      ::db/read-evidence (evidence value)}))]
      (set! db/db (fn ([] (js/Promise.resolve @head))
                    ([_] (js/Promise.resolve @head))))
      (set! db/listen!
            (fn
              ([request]
               (swap! listens conj request)
               (js/Promise.resolve (::db/key request)))
              ([key handler]
               (db/listen! {::db/key key ::db/handler handler}))
              ([value key handler]
               (db/listen! {::db/db value ::db/key key
                            ::db/handler handler}))))
      (set! db/unlisten! (fn [_] (js/Promise.resolve true)))
      (reactive/configure!
       {:seon.config/reactive-settle-ms 1000
        :seon.config/reactive-structural-settle-ms 1000
        :seon.config/reactive-max-latency-ms 20})
      (reactive/reset-measurements!)
      (-> (reactive/observe!
           {::reactive/key :maximum-latency-stream
            ::reactive/consumer-key :consumer
            ::reactive/compute compute
            ::reactive/notify #(swap! delivered conj %)})
          (.then
           (fn [_]
             (js/Promise.
              (fn [resolve _]
                (let [next-t (atom (:t database))
                      timer
                      (js/setInterval
                       (fn []
                         (let [next-db (at-t (swap! next-t inc))]
                           (reset! latest-t (:t next-db))
                           (reset! head next-db)
                           ((::db/handler (last @listens))
                            {:db-after next-db})))
                       4)]
                  (js/setTimeout
                   (fn []
                     (js/clearInterval timer)
                     (reset! completed-before-stop
                             (::reactive/evaluations-completed
                              (reactive/measurements)))
                     (resolve nil))
                   120))))))
          (.then (fn [_]
                   (js/Promise.
                    (fn [resolve _] (js/setTimeout resolve 60)))))
          (.then
           (fn [_]
             (let [measurements (reactive/measurements)]
               (is (>= @completed-before-stop 3)
                   "maximum latency makes repeated progress before writes stop")
               (is (< (::reactive/evaluations-completed measurements)
                      (dec (- @latest-t (:t database))))
                   "continuous writes collapse obsolete database values")
               (is (= 1 (::reactive/active-high-water measurements)))
               (is (= 1 (::reactive/pending-high-water measurements)))
               (is (pos? (::reactive/newest-pending-replacements measurements)))
               (is (= @latest-t (::reactive/last-completed-t measurements)))
               (is (= @latest-t (last @delivered))))
             (reactive/unobserve!
              {::reactive/key :maximum-latency-stream
               ::reactive/consumer-key :consumer})))
          (.then
           (fn [_]
             (is (= 0 (::reactive/registration-count
                       (reactive/measurements))))))
          (.catch (fn [exception] (is false (str exception))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! db/listen! original-listen)
             (set! db/unlisten! original-unlisten)
             (reset! @#'reactive/!runtime
                     {::reactive/registrations {}})
             (done)))))))

(deftest registration-delivers-first-suppresses-equal-and-releases
  (async done
    (let [original-db db/db
          original-listen db/listen!
          original-unlisten db/unlisten!
          head (atom database)
          listens (atom [])
          unlistens (atom [])
          computes (atom [])
          current-value (atom :same)
          first-values (atom [])
          second-values (atom [])
          compute
          (fn [value]
            (swap! computes conj value)
            (js/Promise.resolve
             {::db/value @current-value
              ::db/read-evidence (evidence value)}))]
      (set! db/db (fn ([] (js/Promise.resolve @head))
                    ([_] (js/Promise.resolve @head))))
      (set! db/listen!
            (fn
              ([request]
               (swap! listens conj request)
               (js/Promise.resolve (::db/key request)))
              ([key handler]
               (db/listen! {::db/key key ::db/handler handler}))
              ([value key handler]
               (db/listen! {::db/db value ::db/key key
                            ::db/handler handler}))))
      (set! db/unlisten!
            (fn [request]
              (swap! unlistens conj request)
              (js/Promise.resolve true)))
      (reactive/configure!
       {:seon.config/reactive-settle-ms 0
        :seon.config/reactive-structural-settle-ms 0
        :seon.config/reactive-max-latency-ms 20})
      (-> (reactive/observe!
           {::reactive/key :example
            ::reactive/consumer-key :first
            ::reactive/compute compute
            ::reactive/notify #(swap! first-values conj %)})
          (.then
           (fn [consumer]
             (is (= :first consumer))
             (is (= [:same] @first-values)
                 "a fresh consumer receives the established value")
             (reactive/observe!
              {::reactive/key :example
               ::reactive/consumer-key :second
               ::reactive/compute compute
               ::reactive/notify #(swap! second-values conj %)})))
          (.then
           (fn [_]
             (is (= [:same] @second-values)
                 "a later consumer receives the current value immediately")
             (next-turn)))
          (.then
           (fn [_]
             (is (= :all (::db/dependency-plan (first @listens))))
             (is (= (evidence database)
                    (::db/read-evidence (second @listens)))
                 "the actual read evidence atomically replaces cold :all")
             (let [next-db (at-t 536870914)]
               (reset! head next-db)
               ((::db/handler (second @listens)) {:db-after next-db})
               (next-turn))))
          (.then
           (fn [_]
             (is (= 2 (count @computes)))
             (is (= [:same] @first-values))
             (is (= [:same] @second-values)
                 "Clojure equality suppresses established notifications")
             (reset! current-value :changed)
             (let [next-db (at-t 536870915)]
               (reset! head next-db)
               ((::db/handler (last @listens)) {:db-after next-db})
               (next-turn))))
          (.then
           (fn [_]
             (is (= [:same :changed] @first-values))
             (is (= [:same :changed] @second-values))
             (reactive/unobserve!
              {::reactive/key :example
               ::reactive/consumer-key :first})))
          (.then
           (fn [_]
             (is (= 1 (::reactive/registration-count
                       (reactive/measurements))))
             (reactive/unobserve!
              {::reactive/key :example
               ::reactive/consumer-key :second})))
          (.then
           (fn [_]
             (is (= {::reactive/registration-count 0
                     ::reactive/active-count 0
                     ::reactive/pending-count 0
                     ::reactive/timer-count 0
                     ::reactive/consumer-count 0}
                    (select-keys
                     (reactive/measurements)
                     [::reactive/registration-count
                      ::reactive/active-count
                      ::reactive/pending-count
                      ::reactive/timer-count
                      ::reactive/consumer-count])))
             (is (= 3 (::reactive/evaluations-completed
                       (reactive/measurements))))
             (is (= 1 (::reactive/equal-notifications-suppressed
                       (reactive/measurements))))
             (is (= [{::db/key [::reactive/registration :example]}]
                    @unlistens))))
          (.catch (fn [exception] (is false (str exception))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! db/listen! original-listen)
             (set! db/unlisten! original-unlisten)
             (reset! @#'reactive/!runtime
                     {::reactive/registrations {}})
             (done)))))))

(deftest failed-read-widens-then-a-repair-narrows-and-delivers
  (async done
    (let [original-db db/db
          original-listen db/listen!
          original-unlisten db/unlisten!
          head (atom database)
          listens (atom [])
          computes (atom 0)
          delivered (atom [])
          error-report
          (fn [value]
            (let [transaction (:t value)]
              {:db-after value
               :tx-data
               [[11 :db/ident :seon.error/fault transaction true]
                [11 :db/valueType :db.type/keyword transaction true]
                [11 :db/cardinality :db.cardinality/one transaction true]
                [12 :db/ident :seon.error/frames transaction true]
                [12 :db/valueType :db.type/ref transaction true]
                [12 :db/cardinality :db.cardinality/many transaction true]
                [12 :db/isComponent true transaction true]
                [1 :seon.error/fault :core transaction true]
                [1 :seon.error/message "render failed" transaction true]
                [transaction :db/txInstant
                 #inst "2026-07-19T00:00:00.000-00:00" transaction true]
                [transaction :seon.db/user 20 transaction true]
                [transaction :seon.db/process 21 transaction true]]}))
          repair-evidence
          (fn [value]
            (assoc-in
             (evidence value)
             [0 :datahike.read/dependency-plan
              :datahike.query.dependency/sources 0
              :datahike.query.source/attributes]
             #{:seon.error/fault :example/value}))
          compute
          (fn [value]
            (let [attempt (swap! computes inc)]
              (js/Promise.resolve
               (case attempt
                 1 {::db/value [:main {:id "app-view"} "initial"]
                    ::db/read-evidence (repair-evidence value)}
                 2 (let [fault-db (at-t (inc (:t @head)))]
                     (reset! head fault-db)
                     ((::db/handler (last @listens))
                      (error-report fault-db))
                     {::db/value [:main {:id "app-view"}
                                  [:div {:id "app-error"} "render failed"]]
                      ::db/read-evidence :all
                      ::reactive/failed? true})
                 {::db/value [:main {:id "app-view"} "repaired"]
                  ::db/read-evidence (repair-evidence value)}))))]
      (set! db/db (fn ([] (js/Promise.resolve @head))
                    ([_] (js/Promise.resolve @head))))
      (set! db/listen!
            (fn
              ([request]
               (swap! listens conj request)
               (js/Promise.resolve (::db/key request)))
              ([key handler]
               (db/listen! {::db/key key ::db/handler handler}))
              ([value key handler]
               (db/listen! {::db/db value ::db/key key
                            ::db/handler handler}))))
      (set! db/unlisten! (fn [_] (js/Promise.resolve true)))
      (reactive/configure!
       {:seon.config/reactive-settle-ms 0
        :seon.config/reactive-structural-settle-ms 0
        :seon.config/reactive-max-latency-ms 20})
      (-> (reactive/observe!
           {::reactive/key :repair
            ::reactive/consumer-key :consumer
            ::reactive/compute compute
            ::reactive/notify #(swap! delivered conj %)})
          (.then
           (fn [_]
             (is (= [:main {:id "app-view"} "initial"]
                    (first @delivered)))
             (is (= (repair-evidence @head)
                    (::db/read-evidence (last @listens)))
                 "the established page begins with exact error and domain attrs")
             (let [next-db (at-t (inc (:t database)))]
               (reset! head next-db)
               ((::db/handler (last @listens)) {:db-after next-db})
               (next-turn))))
          (.then
           (fn [_]
             (is (= "app-error" (get-in (last @delivered) [2 1 :id])))
             (is (= :all (::db/dependency-plan (last @listens)))
                 "failure replaces the prior narrow plan with :all")
             (is (= 2 @computes)
                 "an in-flight error report is reconciled by failure finish")
             (let [fault-db (at-t (inc (:t @head)))]
               (reset! head fault-db)
               ((::db/handler (last @listens))
                (error-report fault-db)))
             (next-turn)))
          (.then
           (fn [_]
             (is (= 2 @computes)
                 "recording the visible failure does not retrigger it")
             (is (= 0 (::reactive/pending-count
                       (reactive/measurements))))
             (is (= 1 (::reactive/failure-evidence-events-suppressed
                       (reactive/measurements))))
             (let [next-db (at-t (inc (:t @head)))]
               (reset! head next-db)
               ((::db/handler (last @listens))
                {:db-after next-db
                 :tx-data [[13 :db/ident :example/value
                            (:t next-db) true]
                           [13 :db/valueType :db.type/keyword
                            (:t next-db) true]
                           [13 :db/cardinality :db.cardinality/one
                            (:t next-db) true]
                           [1 :seon.error/fault :core
                            (:t next-db) true]
                           [2 :example/value :repaired
                            (:t next-db) true]]})
               (next-turn))))
          (.then
           (fn [_]
             (is (= [:main {:id "app-view"} "repaired"]
                    (last @delivered)))
             (is (= 3 (count @delivered)))
             (is (= (repair-evidence @head)
                    (::db/read-evidence (last @listens)))
                 "a mixed ordinary repair replaces :all with exact evidence")
             (is (= (:t @head)
                    (::reactive/last-completed-t
                     (reactive/measurements))))
             (reactive/unobserve!
              {::reactive/key :repair
               ::reactive/consumer-key :consumer})))
          (.then
           (fn [_]
             (is (= 0 (::reactive/registration-count
                       (reactive/measurements))))))
          (.catch (fn [exception] (is false (str exception))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! db/listen! original-listen)
             (set! db/unlisten! original-unlisten)
             (reset! @#'reactive/!runtime
                     {::reactive/registrations {}})
             (done)))))))

(deftest active-computation-retains-only-the-newest-pending-database
  (async done
    (let [original-db db/db
          original-listen db/listen!
          original-unlisten db/unlisten!
          head (atom database)
          listens (atom [])
          computed-ts (atom [])
          delivered-ts (atom [])
          resolve-slow (atom nil)
          envelope (fn [value]
                     {::db/value (:t value)
                      ::db/read-evidence (evidence value)})
          compute
          (fn [value]
            (swap! computed-ts conj (:t value))
            (if (= 536870914 (:t value))
              (js/Promise. (fn [resolve _] (reset! resolve-slow resolve)))
              (js/Promise.resolve (envelope value))))]
      (set! db/db (fn ([] (js/Promise.resolve @head))
                    ([_] (js/Promise.resolve @head))))
      (set! db/listen!
            (fn
              ([request]
               (swap! listens conj request)
               (js/Promise.resolve (::db/key request)))
              ([key handler]
               (db/listen! {::db/key key ::db/handler handler}))
              ([value key handler]
               (db/listen! {::db/db value ::db/key key
                            ::db/handler handler}))))
      (set! db/unlisten! (fn [_] (js/Promise.resolve true)))
      (reactive/configure!
       {:seon.config/reactive-settle-ms 0
        :seon.config/reactive-structural-settle-ms 0
        :seon.config/reactive-max-latency-ms 20})
      (-> (reactive/observe!
           {::reactive/key :newest
            ::reactive/consumer-key :consumer
            ::reactive/compute compute
            ::reactive/notify #(swap! delivered-ts conj %)})
          (.then (fn [_] (next-turn)))
          (.then
           (fn [_]
             (let [next-db (at-t 536870914)]
               (reset! head next-db)
               ((::db/handler (last @listens)) {:db-after next-db})
               (next-turn))))
          (.then
           (fn [_]
             (is (= [536870913 536870914] @computed-ts))
             (is (fn? @resolve-slow))
             (doseq [t (range 536870915 536871015)]
               (let [next-db (at-t t)]
                 (reset! head next-db)
                 ((::db/handler (last @listens)) {:db-after next-db})))
             (is (= 1 (::reactive/pending-count
                       (reactive/measurements))))
             (@resolve-slow (envelope (at-t 536870914)))
             (next-turn)))
          (.then
           (fn [_]
             (is (= [536870913 536870914 536871014] @computed-ts)
                 "the obsolete middle database value never computes")
             (is (= [536870913 536870914 536871014] @delivered-ts))
             (is (= 0 (::reactive/pending-count
                       (reactive/measurements))))
             (is (= 1 (::reactive/active-high-water
                       (reactive/measurements))))
             (is (= 1 (::reactive/pending-high-water
                       (reactive/measurements))))
             (is (= 99 (::reactive/newest-pending-replacements
                        (reactive/measurements))))
             (is (= 536871014 (::reactive/last-completed-t
                               (reactive/measurements))))
             (reactive/unobserve!
              {::reactive/key :newest
               ::reactive/consumer-key :consumer})))
          (.catch (fn [exception] (is false (str exception))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! db/listen! original-listen)
             (set! db/unlisten! original-unlisten)
             (reset! @#'reactive/!runtime
                     {::reactive/registrations {}})
             (done)))))))

(deftest plan-replacement-acknowledgement-closes-the-render-race
  (async done
    (let [original-db db/db
          original-listen db/listen!
          original-unlisten db/unlisten!
          head (atom database)
          listen-count (atom 0)
          computed-ts (atom [])
          compute
          (fn [value]
            (swap! computed-ts conj (:t value))
            (js/Promise.resolve
             {::db/value (:t value)
              ::db/read-evidence (evidence value)}))]
      (set! db/db (fn ([] (js/Promise.resolve @head))
                    ([_] (js/Promise.resolve @head))))
      (set! db/listen!
            (fn
              ([request]
               (when (= 2 (swap! listen-count inc))
                 ;; This commit lands after the initial read but before the
                 ;; newly discovered plan is acknowledged.
                 (reset! head (at-t 536870914)))
               (js/Promise.resolve (::db/key request)))
              ([key handler]
               (db/listen! {::db/key key ::db/handler handler}))
              ([value key handler]
               (db/listen! {::db/db value ::db/key key
                            ::db/handler handler}))))
      (set! db/unlisten! (fn [_] (js/Promise.resolve true)))
      (reactive/configure!
       {:seon.config/reactive-settle-ms 0
        :seon.config/reactive-structural-settle-ms 0
        :seon.config/reactive-max-latency-ms 20})
      (-> (reactive/observe!
           {::reactive/key :replacement
            ::reactive/consumer-key :consumer
            ::reactive/compute compute
            ::reactive/notify (fn [_])})
          (.then (fn [_] (next-turn)))
          (.then
           (fn [_]
             (is (= [536870913 536870914] @computed-ts)
                 "the acknowledgement advances work missed during evaluation")
             (reactive/unobserve!
              {::reactive/key :replacement
               ::reactive/consumer-key :consumer})))
          (.catch (fn [exception] (is false (str exception))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! db/listen! original-listen)
             (set! db/unlisten! original-unlisten)
             (reset! @#'reactive/!runtime
                     {::reactive/registrations {}})
             (done)))))))

(deftest independent-registrations-start-without-awaiting-one-another
  (async done
    (let [original-db db/db
          original-listen db/listen!
          original-unlisten db/unlisten!
          started (atom #{})
          resolvers (atom {})
          compute
          (fn [key]
            (fn [value]
              (swap! started conj key)
              (js/Promise.
               (fn [resolve _]
                 (swap! resolvers assoc key
                        #(resolve {::db/value key
                                   ::db/read-evidence (evidence value)}))))))]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/listen!
            (fn
              ([request] (js/Promise.resolve (::db/key request)))
              ([key handler]
               (db/listen! {::db/key key ::db/handler handler}))
              ([value key handler]
               (db/listen! {::db/db value ::db/key key
                            ::db/handler handler}))))
      (set! db/unlisten! (fn [_] (js/Promise.resolve true)))
      (reactive/configure!
       {:seon.config/reactive-settle-ms 0
        :seon.config/reactive-structural-settle-ms 0
        :seon.config/reactive-max-latency-ms 20})
      (let [observations
            [(reactive/observe!
              {::reactive/key :left
               ::reactive/consumer-key :consumer
               ::reactive/compute (compute :left)
               ::reactive/notify (fn [_])})
             (reactive/observe!
              {::reactive/key :right
               ::reactive/consumer-key :consumer
               ::reactive/compute (compute :right)
               ::reactive/notify (fn [_])})]]
        (-> (next-turn)
            (.then
             (fn [_]
               (is (= #{:left :right} @started)
                   "dependency-ready computations overlap")
               ((:left @resolvers))
               ((:right @resolvers))
               (js/Promise.all (clj->js observations))))
            (.then
             (fn [_]
               (js/Promise.all
                #js [(reactive/unobserve!
                      {::reactive/key :left
                       ::reactive/consumer-key :consumer})
                     (reactive/unobserve!
                      {::reactive/key :right
                       ::reactive/consumer-key :consumer})])))
            (.catch (fn [exception] (is false (str exception))))
            (.finally
             (fn []
               (set! db/db original-db)
               (set! db/listen! original-listen)
               (set! db/unlisten! original-unlisten)
               (reset! @#'reactive/!runtime
                       {::reactive/registrations {}})
               (done))))))))
