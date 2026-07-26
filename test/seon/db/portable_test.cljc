(ns seon.db.portable-test
  "One database capability contract exercised unchanged on CLJ and CLJS."
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [async deftest is testing]])
   [malli.core :as m]
   [seon.db :as db]
   [seon.db.internal :as internal]
   [seon.db.leaf :as leaf]
   [seon.db.protocol :as protocol]
   [seon.schema :as schema]))

#?(:clj (defmacro await [value] value))

(def database
  {:db-name "portable"
   :store-id [#uuid "00000000-0000-0000-0000-000000000000" :db]
   :t 0
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "00000000-0000-0000-0000-000000000001"})

(def next-database
  (assoc database
         :t 1
         :datahike/commit-id
         #uuid "00000000-0000-0000-0000-000000000002"))

(def set-attr :portable.record/tags)
(def edn-attr :portable.record/value)
(def id-attr :portable.record/id)
(def tx-source-attr :portable.tx/source)
(def partial-text-attr :portable.attempt/partial-text)
(def component-attr :portable.record/children)
(def child-edn-attr :portable.record.child/value)
(def child-render-attr :portable.record.child/render)

(defn- register-contract-schema! []
  (schema/register! id-attr [:string {:seon.db/identity true}])
  (schema/register! set-attr [:set :keyword])
  (schema/register! edn-attr [:or :keyword :map])
  (schema/register! child-edn-attr [:or :keyword :map])
  (schema/register! child-render-attr [:or :string :symbol])
  (schema/register! component-attr
                    [:vector {:seon.db/component true}
                     [:map {:closed true}
                      [child-edn-attr child-edn-attr]
                      [child-render-attr child-render-attr]]])
  (schema/register! tx-source-attr :keyword))

(deftest no-history-registration-derives-the-portable-datahike-facet
  (schema/register! partial-text-attr
                    [:string {:seon.db/no-history? true}])
  (is (= {:db/ident partial-text-attr
          :db/valueType :db.type/string
          :db/cardinality :db.cardinality/one
          :db/noHistory true}
         (internal/malli->datahike-attr partial-text-attr))))

(defn- operation [request] (::protocol/operation request))

(defn- deterministic-leaf []
  (let [!requests (atom [])
        !receipts (atom #{})
        !cached (atom [])
        !commits (atom [])
        pulled {id-attr "one" set-attr [:red :blue]
                edn-attr (pr-str {:portable.value/n 2})}
        success (fn [request body]
                  (merge {::protocol/request-id (::protocol/request-id request)
                          ::protocol/operation (operation request)
                          ::protocol/success? true}
                         body))
        call! (fn [request _timeout-ms]
                (swap! !requests conj request)
                (case (operation request)
                  :seon.db.protocol.operation/query
                  (if (= :portable/fail (::protocol/query-form request))
                    {::protocol/request-id (::protocol/request-id request)
                     ::protocol/operation (operation request)
                     ::protocol/success? false
                     ::protocol/error "portable failure"
                     ::protocol/error-kind :portable/error
                     :seon.error/kind :user-input}
                    (success request
                             {:datahike.query/result #{["one"]}
                              :datahike.read/dependency-plan :all
                              :datahike.query/attribute-dependencies #{id-attr}
                              :datahike.query/cache-evidence {}
                              :datahike.query/resource-evidence {}}))

                  :seon.db.protocol.operation/pull
                  (success request {::protocol/result pulled
                                    :datahike.read/dependency-plan :all})

                  :seon.db.protocol.operation/pull-many
                  (success request {::protocol/result [pulled nil]
                                    :datahike.read/dependency-plan :all})

                  :seon.db.protocol.operation/schema
                  (success request {::protocol/schema {id-attr
                                                       {:db/valueType
                                                        :db.type/string}}})

                  :seon.db.protocol.operation/execute-many
                  (success request
                           {::protocol/results
                            (mapv (fn [member]
                                    (success member
                                             {:datahike.query/result #{["one"]}
                                              :datahike.read/dependency-plan :all}))
                                  (::protocol/members request))})

                  :seon.db.protocol.operation/index-page
                  (success request
                           {:datahike.index-page/datoms
                            [[1 id-attr "one" 1 true]]
                            :datahike.index-page/complete? true})

                  (throw (ex-info "Unexpected deterministic-leaf operation."
                                  {:request request}))))
        transaction-call!
        (fn [request _recoverable?]
          (swap! !requests conj request)
          (let [request-id (::protocol/request-id request)
                replayed? (contains? @!receipts request-id)]
            (swap! !receipts conj request-id)
            (success request
                     (cond-> {:db-before database
                              :db-after next-database
                              :tx-data [[1 id-attr "one" 1 true]
                                        [1 set-attr :red 1 true]
                                        [1 edn-attr (pr-str {:portable.value/n 2})
                                         1 true]]
                              :tempids {"one" 1}
                              :tx-meta {:portable.tx/source :test}}
                       replayed? (assoc ::protocol/recovered? true)))))
        context {::leaf/current-tx-context (constantly {})
                 ::leaf/current-agent-id (constantly "agent-portable")
                 ::leaf/record-read-evidence! (fn [_] nil)
                 ::leaf/with-read-evidence (fn [f] (f))
                 ::leaf/with-agent (fn [_ f] (f))
                 ::leaf/without-agent (fn [f] (f))
                 ::leaf/with-tx-context (fn [_ f] (f))
                 ::leaf/install-configuration-context! (fn [_] nil)}]
    {:leaf {::leaf/call! call!
            ::leaf/transaction-call! transaction-call!
            ;; Accept both the final request-map contract and the earlier
            ;; `(database-name acquire?)` leaf shape so this test identifies
            ;; public drift rather than coupling to an extraction detail.
            ::leaf/resolve-db! (fn
                                 ([request] (if (map? request) database database))
                                 ([_database-name _acquire?] database))
            ::leaf/read-db! #(or (::db/db %) database)
            ::leaf/request-db! (fn [request]
                                 {::protocol/request-id
                                  (or (::db/request-id request) "read-1")
                                  ::db/db (or (::db/db request) database)})
            ::leaf/cache-db! #(do (swap! !cached conj %) %)
            ::leaf/context context
            ::leaf/uuid (constantly "minted-op")
            ::leaf/resource-options
            (fn [_policy request]
              (cond-> {}
                (::db/max-work request)
                (assoc :datahike.resource/max-work (::db/max-work request))
                (::db/max-results request)
                (assoc :datahike.resource/max-results (::db/max-results request))
                (::db/max-result-weight request)
                (assoc :datahike.resource/max-result-weight
                       (::db/max-result-weight request))))
            ::leaf/on-commit! #(swap! !commits conj %)}
     :requests !requests
     :cached !cached
     :commits !commits}))

(def public-functions
  [#'db/current-agent-id #'db/db #'db/as-of #'db/since #'db/history
   #'db/cas-assert #'db/transact! #'db/query #'db/query-with-evidence
   #'db/read-attribute-dependencies #'db/pull #'db/pull-many #'db/entity
   #'db/installed-schema #'db/execute-many #'db/index-page])

(def schema-required-functions
  [#'db/current-agent-id #'db/cas-assert #'db/query-with-evidence
   #'db/read-attribute-dependencies #'db/pull-many #'db/entity
   #'db/installed-schema #'db/execute-many #'db/index-page])

(defn- assert-public-metadata! []
  (doseq [v schema-required-functions]
    (is (:malli/schema (meta v))
        (str (:name (meta v)) " declares a public Malli schema"))))

(defn- last-request [requests op]
  (last (filter #(= op (operation %)) @requests)))

(defn- install-leaf! [platform-leaf]
  (let [original db/*leaf*]
    #?(:clj (alter-var-root #'db/*leaf* (constantly platform-leaf))
       :cljs (set! db/*leaf* platform-leaf))
    original))

(defn- restore-leaf! [platform-leaf]
  #?(:clj (alter-var-root #'db/*leaf* (constantly platform-leaf))
     :cljs (set! db/*leaf* platform-leaf)))

(defn ^{:async #?(:cljs true :clj false)} exercise-contract! [fixture]
  (register-contract-schema!)
  (let [{requests :requests} fixture
        fns (into {} (map (fn [v] [(symbol (name (:name (meta v)))) @v]))
                  public-functions)
        call (fn [sym & args] (apply (get fns sym) args))
        current-agent-id #(call 'current-agent-id)
        current-db #(call 'db)
        transact! #(apply call 'transact! %&)
        query #(apply call 'query %&)
        query-with-evidence #(call 'query-with-evidence %)
        pull #(apply call 'pull %&)
        pull-many #(apply call 'pull-many %&)
        entity #(apply call 'entity %&)
        installed-schema #(apply call 'installed-schema %&)
        execute-many #(call 'execute-many %)
        index-page #(apply call 'index-page %&)]
    (assert-public-metadata!)
    (is (= "agent-portable" (current-agent-id)))
    (is (= database (await (current-db))))
    (is (= database (await (call 'db {::db/database-name "portable"}))))
    (is (= (assoc database :as-of 4 :since nil) (call 'as-of database 4)))
    (is (= (assoc database :since 4 :as-of nil) (call 'since database 4)))
    (is (= (assoc database :history true) (call 'history database)))
    (is (= [:db.fn/cas [id-attr "one"] set-attr #{:red} #{:red}]
           (call 'cas-assert [id-attr "one"] set-attr #{:red})))

    (testing "transaction call shapes, full report, encoding, and replay"
      (let [tx [{id-attr "one" set-attr #{:red :blue}
                 edn-attr {:portable.value/n 2}
                 component-attr
                 [{child-edn-attr {:portable.child/n 3}
                   child-render-attr "; literal render text"}]}]
            first-report
            (await (transact! {::db/tx-data tx
                               ::db/db database
                               ::db/expected-db database
                               ::db/tx-meta {:portable.tx/source :test}
                               ::db/opts {:portable.option/value true}
                               :seon.capability/op-id "same-op"}))
            replay (await (transact! {::db/tx-data tx
                                      :seon.capability/op-id "same-op"}))
            raw-report (await (transact! tx))
            explicit-report (await (transact! database tx))
            wire (last-request requests protocol/transact-operation)]
        (is (= #{:db-before :db-after :tx-data :tempids :tx-meta
                 :seon.capability/op-id}
               (set (keys first-report))))
        (is (= true (:seon.capability/replayed? replay)))
        (is (= next-database (:db-after raw-report)))
        (is (= next-database (:db-after explicit-report)))
        (is (= "minted-op" (::protocol/request-id wire)))
        (is (= (pr-str {:portable.value/n 2})
               (get-in (::protocol/transaction-data wire) [0 edn-attr])))
        (is (= (pr-str {:portable.child/n 3})
               (get-in (::protocol/transaction-data wire)
                       [0 component-attr 0 child-edn-attr]))
            "component validation sees the encoded transaction projection")
        (is (= (pr-str "; literal render text")
               (get-in (::protocol/transaction-data wire)
                       [0 component-attr 0 child-render-attr]))
            "a semicolon-prefixed literal survives one logical decode")
        (is (set? (get-in (::protocol/transaction-data wire) [0 set-attr])))
        (let [request-count (count @requests)
              malformed (await (transact! [{id-attr "one" edn-attr 42}]))]
          (is (= :user-input (:seon.error/kind malformed)))
          (is (= request-count (count @requests))
              "logical EDN-slot validation refuses malformed data before transport"))
        (let [rejected (await (transact! {::db/tx-data tx
                                          ::db/request-id "transport-leak"}))]
          (is (= :user-input (:seon.error/kind rejected)))
          (is (string? (:seon.error/message rejected))))))

    (testing "query forms, evidence, resource passthrough, and flat errors"
      (is (= #{["one"]}
             (await (query '[:find ?id :where
                             [?e :portable.record/id ?id]]))))
      (is (= #{["one"]} (await (query '[:find ?x :in $ ?x] "one"))))
      (is (= #{["one"]}
             (await (query {::db/query '[:find ?id]
                            ::db/db database
                            ::db/args []
                            ::db/max-work 11
                            ::db/max-results 12
                            ::db/max-result-weight 13}))))
      (let [wire (last-request requests protocol/query-operation)]
        (is (= 11 (:datahike.resource/max-work wire)))
        (is (= 12 (:datahike.resource/max-results wire)))
        (is (= 13 (:datahike.resource/max-result-weight wire))))
      (is (= #{["one"]}
             (:datahike.query/result
              (await (query-with-evidence {::db/query '[:find ?id]
                                           ::db/db database})))))
      (let [failure (await (query {::db/query :portable/fail}))]
        (is (= "portable failure" (:seon.error/message failure)))
        (is (= :user-input (:seon.error/kind failure)))
        (is (map? (:seon.error/data failure)))
        (is (nil? (:seon/error failure)))))

    (testing "pull family preserves every child arity and decodes once"
      (doseq [value [(await (pull '[*] [id-attr "one"]))
                     (await (pull database '[*] [id-attr "one"]))
                     (await (pull {::db/db database ::db/pull-pattern '[*]
                                   ::db/ref [id-attr "one"]
                                   ::db/max-result-weight 17}))
                     (await (entity [id-attr "one"]))
                     (await (entity database [id-attr "one"]))]]
        (is (= #{:red :blue} (get value set-attr)))
        (is (= {:portable.value/n 2} (get value edn-attr))))
      (doseq [values [(await (pull-many '[*] [[id-attr "one"] 404]))
                      (await (pull-many database '[*] [[id-attr "one"] 404]))
                      (await (pull-many {::db/db database
                                        ::db/pull-pattern '[*]
                                        ::db/refs [[id-attr "one"] 404]}))]]
        (is (= #{:red :blue} (get (first values) set-attr)))
        (is (nil? (second values))))
      (is (= [{:portable/id "parent"
               :portable/children [{:portable/id "child"}]}]
             (internal/omit-nil-entity-values
              [{:portable/id "parent"
                :portable/absent nil
                :portable/children [{:portable/id "child"
                                     :portable/absent nil}]}]))))

    (testing "formerly omitted host surface resolves with child call shapes"
      (is (= {id-attr {:db/valueType :db.type/string}}
             (await (installed-schema))))
      (is (= {id-attr {:db/valueType :db.type/string}}
             (await (installed-schema database))))
      (is (= database
             (::db/db
              (await
               (execute-many
                {::db/db database
                 ::db/max-result-weight 21
                 ::db/members
                 [{::protocol/operation protocol/query-operation
                   ::protocol/query-form '[:find ?id]
                   ::protocol/arguments []}]})))))
      (is (= [[1 id-attr "one" 1 true]]
             (:datahike.index-page/datoms
              (await (index-page database
                                 {::db/index :eavt ::db/direction :forward
                                  ::db/limit 10 ::db/components [1]
                                  ::db/max-result-weight 22}))))))
    true))

(deftest read-attribute-dependencies-are-portable
  (let [project db/read-attribute-dependencies
        bound-project
        (get (db/bind-leaf {}) 'read-attribute-dependencies)
        literal-query
        {::db/query
         '[:find ?name :where [?entity :demo/name ?name]]}
        bound-query
        {::db/query
         '[:find ?entity :in $ ?attribute
           :where [?entity ?attribute]]
         ::db/args [:demo/name]}
        open-query
        {::db/query
         '[:find ?entity :where [?entity ?attribute]]}
        literal-pull
        {::db/pull-pattern '[:demo/name]
         ::db/refs [[:demo/id "one"]]}
        wildcard-pull
        {::db/pull-pattern '[*]}]
    (is (= #{:demo/name} (project literal-query)))
    (is (= #{:demo/name} (project bound-query)))
    (is (= :all (project open-query)))
    (is (= #{:demo/id :demo/name} (project literal-pull)))
    (is (= :all (project wildcard-pull)))
    (is (= (project literal-query) (bound-project literal-query)))))

(deftest same-portable-database-contract-runs-on-both-tiers
  #?(:clj
     (let [{platform-leaf :leaf :as fixture} (deterministic-leaf)
           original (install-leaf! platform-leaf)]
       (try
         (is (true? (exercise-contract! fixture)))
         (finally (restore-leaf! original))))
     :cljs
     (async done
       (let [{platform-leaf :leaf :as fixture} (deterministic-leaf)
             original (install-leaf! platform-leaf)]
         (-> (exercise-contract! fixture)
           (.then (fn [result]
                    (restore-leaf! original)
                    (is (true? result))
                    (done)))
           (.catch (fn [exception]
                     (restore-leaf! original)
                     (is false (str "portable contract rejected: " exception))
                     (done))))))))
