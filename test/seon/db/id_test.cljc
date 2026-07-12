(ns seon.db.id-test
  (:require
   #?(:clj  [clojure.edn :as edn]
      :cljs [cljs.reader :as edn])
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [deftest is testing async]])
   [datahike.api :as d]
   #?@(:clj [[datahike.writing :as writing]])
   [malli.core :as m]
   [seon.db.id :as id]
   [seon.schema :as schema]
   #?@(:cljs [[seon.db.internal :as db.internal]])))

(def ^:private generate-candidate #'id/generate-candidate)
(def ^:private compact-syntax #"^[a-z][a-z0-9]{11}$")
(def ^:private allocation-key :idtest.record/id)
(def ^:private identity-attr :idtest.record/id)
(def ^:private other-allocation-key :idtest.record/other-id)
(def ^:private other-identity-attr :idtest.record/other-id)

(defn- register-allocation-schema! []
  (schema/register!
   identity-attr
   [:and {:seon.db/identity true
          :seon.db.id/generator
          :seon.db.id.generator/compact}
    ::id/compact-value])
  (schema/register!
   other-identity-attr
   [:and {:seon.db/identity true
          :seon.db.id/generator
          :seon.db.id.generator/compact}
    ::id/compact-value])
  (schema/register! :idtest.record/source :string))

(defn- candidates [generator n]
  (into [] (repeatedly n #(generate-candidate generator))))

(deftest schemas-distinguish-preserved-and-generated-id-populations
  (testing "the broad transport accepts every persisted representation"
    (doseq [value ["root"
                   "Kpx-2605232138"
                   "AGTcapround001"
                   "dry-jokes-hunt"
                   "q66ljwup2b5r"]]
      (is (m/validate :seon.db/id value))))

  (testing "agent identities accept root, legacy, and readable words only"
    (doseq [value ["root" "Kpx-2605232138" "AGTcapround001"
                   "dry-jokes-hunt"]]
      (is (m/validate ::id/agent-value value)))
    (is (not (m/validate ::id/agent-value "q66ljwup2b5r"))))

  (testing "non-agent generated identities accept legacy and compact only"
    (doseq [value ["Kpx-2605232138" "AGTcapround001" "q66ljwup2b5r"]]
      (is (m/validate ::id/compact-value value)))
    (is (not (m/validate ::id/compact-value "root")))
    ;; A 14-character word id is necessarily in the old schema's durable
    ;; value domain. Use a non-legacy-length readable id to prove that NEW
    ;; word grammar is not generally admitted on non-agent attributes.
    (is (not (m/validate ::id/compact-value "calm-otters-build"))))

  (testing "unsafe or malformed values are outside every transport grammar"
    (doseq [value ["proc:wire"
                   "has/slash/value"
                   "has.dot.value"
                   "1abc23456789"
                   "abc1234567890"]]
      (is (not (m/validate :seon.db/id value)))))

  (testing "policy and identity-attribute leaves are fully namespaced"
    (is (m/validate ::id/generator
                    :seon.db.id.generator/human-readable))
    (is (m/validate ::id/generator
                    :seon.db.id.generator/compact))
    (is (not (m/validate ::id/generator :compact)))
    (is (m/validate ::id/identity-attr :seon.agent/id))
    (is (not (m/validate ::id/identity-attr :id)))))

(deftest package-adapters-obey-their-owned-syntax
  (let [word-ids    (candidates :seon.db.id.generator/human-readable 64)
        compact-ids (candidates :seon.db.id.generator/compact 2048)]
    (testing "agent candidates are readable words and never the reserved root"
      (is (every? #(m/validate ::id/word-value %) word-ids))
      (is (not-any? #{"root"} word-ids)))

    (testing "compact candidates have the exact cross-platform CUID2 grammar"
      (is (every? #(re-matches compact-syntax %) compact-ids))
      (is (= (count compact-ids) (count (set compact-ids)))))))

(deftest compact-candidates-read-as-unmunged-result-symbol-names
  (let [candidate-ids (candidates :seon.db.id.generator/compact 128)
        result-symbols (mapv #(edn/read-string (str "result/" %))
                             candidate-ids)]
    (is (every? symbol? result-symbols))
    (is (every? #(= "result" (namespace %)) result-symbols))
    (is (= candidate-ids (mapv name result-symbols)))
    (is (not-any? #(re-find #"[-_/:.\s]" %) candidate-ids))))

(deftest unsupported-private-policy-fails-with-structured-data
  (let [error (try
                (generate-candidate :seon.db.id.generator/unknown)
                nil
                (catch #?(:clj Exception :cljs :default) e e))]
    (is (some? error))
    (is (= :seon.db.id.generator/unknown
           (::id/generator (ex-data error))))
    (is (= :seon.db.id.error/unsupported-generator
           (::id/error (ex-data error))))))

#?(:clj
   (do
     (defn- jvm-allocation-conn
       ([] (jvm-allocation-conn :canonical))
       ([writer-mode]
        (let [base-config {:store {:backend :memory
                                   :id (java.util.UUID/randomUUID)}
                           :schema-flexibility :write
                           :keep-history? true}
              config (case writer-mode
                       :canonical
                       (id/allocation-connect-config base-config)

                       :ordinary
                       (assoc base-config
                              :writer
                              {:backend :self
                               :write-fn-map
                               {'transact! writing/transact!}})

                       base-config)]
          ;; Creation persists its config. The live allocation writer belongs
          ;; only to the first connection for this store and branch.
          (d/create-database base-config)
          (let [conn (d/connect config)]
            (d/transact
             conn
             [{:db/ident identity-attr
               :db/valueType :db.type/string
               :db/cardinality :db.cardinality/one
               :db/unique :db.unique/identity}
              {:db/ident other-identity-attr
               :db/valueType :db.type/string
               :db/cardinality :db.cardinality/one
               :db/unique :db.unique/identity}
              {:db/ident :idtest.record/source
               :db/valueType :db.type/string
               :db/cardinality :db.cardinality/one}])
            conn))))

     (deftest direct-jvm-allocation-uses-the-canonical-preparation
       (register-allocation-schema!)
       (let [conn (jvm-allocation-conn)
             response
             (id/allocate!
              {::id/allocations
               [{::id/key allocation-key
                 ::id/identity-attr identity-attr}
                {::id/key other-allocation-key
                 ::id/identity-attr other-identity-attr}]
               ::id/transaction-builder
               (fn [ids]
                 {:seon.db/tx-data
                  [{identity-attr (get ids allocation-key)
                    :idtest.record/source "first"}
                   {:idtest.record/source "automatic-between"}
                   {other-identity-attr (get ids other-allocation-key)
                    :idtest.record/source "later"}]})
               :seon.db/conn conn})
             eids (::id/eids response)
             automatic-eid
             (:e (first (d/datoms (d/db conn) :avet :idtest.record/source
                                  "automatic-between")))]
         (is (true? (:seon.db/ok? response)))
         (is (= #{allocation-key other-allocation-key}
                (set (keys (::id/ids response)))))
         (is (= #{allocation-key other-allocation-key}
                (set (keys eids))))
         (is (= 3 (count (conj (set (vals eids)) automatic-eid))))))

     (deftest unconfigured-local-writer-fails-before-domain-commit
       (register-allocation-schema!)
       (let [conn (jvm-allocation-conn :unconfigured)
             before (:max-tx (d/db conn))
             response
             (id/allocate!
              {::id/allocations
               [{::id/key allocation-key ::id/identity-attr identity-attr}]
               ::id/transaction-builder
               (fn [ids]
                 {:seon.db/tx-data
                  [{identity-attr (get ids allocation-key)}]})
               :seon.db/conn conn})]
         (is (false? (:seon.db/ok? response)))
         (is (= :seon.db.id.error/unconfigured-allocation-writer
                (get-in response [:seon.db/error :seon.error/data
                                  ::id/error])))
         (is (= before (:max-tx (d/db conn))))
         (is (empty? (d/datoms (d/db conn) :avet identity-attr)))))

     (deftest an-arbitrary-local-transact-function-cannot-spoof-the-writer
       (register-allocation-schema!)
       (let [conn (jvm-allocation-conn :ordinary)
             builder-called? (atom false)
             before (:max-tx (d/db conn))
             response
             (id/allocate!
              {::id/allocations
               [{::id/key allocation-key ::id/identity-attr identity-attr}]
               ::id/transaction-builder
               (fn [_ids]
                 (reset! builder-called? true)
                 {:seon.db/tx-data []})
               :seon.db/conn conn})]
         (is (false? (:seon.db/ok? response)))
         (is (false? @builder-called?))
         (is (= :seon.db.id.error/unconfigured-allocation-writer
                (get-in response [:seon.db/error :seon.error/data
                                  ::id/error])))
         (is (= before (:max-tx (d/db conn))))))

     (deftest allocation-options-cannot-override-writer-owned-fields
       (register-allocation-schema!)
       (let [conn (jvm-allocation-conn)
             before (:max-tx (d/db conn))
             response
             (id/allocate!
              {::id/allocations
               [{::id/key allocation-key ::id/identity-attr identity-attr}]
               ::id/transaction-builder
               (fn [ids]
                 {:seon.db/tx-data
                  [{identity-attr (get ids allocation-key)}]
                  :seon.db/opts
                  {:tx-data [{:idtest.record/source "must-not-land"}]}})
               :seon.db/conn conn})]
         (is (false? (:seon.db/ok? response)))
         (is (= :seon.db.id.error/reserved-request-field
                (get-in response [:seon.db/error :seon.error/data
                                  ::id/error])))
         (is (= before (:max-tx (d/db conn))))
         (is (empty? (d/datoms (d/db conn) :avet :idtest.record/source
                               "must-not-land")))))

     (deftest file-store-allocation-survives-release-and-reconnect
       (register-allocation-schema!)
       (let [root-path (str (System/getProperty "java.io.tmpdir")
                            "/seon-id-writer-" (random-uuid))
             store-path (str root-path "/store")
             base-config {:store {:backend :file
                                  :path store-path
                                  :id (java.util.UUID/randomUUID)}
                          :schema-flexibility :write
                          :keep-history? true}
             !conn (atom nil)
             allocate (fn [conn source]
                        (id/allocate!
                         {::id/allocations
                          [{::id/key allocation-key
                            ::id/identity-attr identity-attr}]
                          ::id/transaction-builder
                          (fn [ids]
                            {:seon.db/tx-data
                             [{identity-attr (get ids allocation-key)
                               :idtest.record/source source}]})
                          :seon.db/conn conn}))]
         (try
           (.mkdirs (java.io.File. root-path))
           (d/create-database base-config)
           (let [conn (d/connect (id/allocation-connect-config base-config))
                 _ (reset! !conn conn)
                 _ (d/transact
                    conn
                    [{:db/ident identity-attr
                      :db/valueType :db.type/string
                      :db/cardinality :db.cardinality/one
                      :db/unique :db.unique/identity}
                     {:db/ident :idtest.record/source
                      :db/valueType :db.type/string
                      :db/cardinality :db.cardinality/one}])
                 first-response (allocate conn "before-reconnect")]
             (is (true? (:seon.db/ok? first-response)))
             (is (= :seon.db.id.writer/serialized
                    (get-in @conn [:config :writer :backend])))
             (is (not (contains? (get-in @conn [:config :writer])
                                 :write-fn-map)))
             (d/release conn)
             (reset! !conn nil)
             (let [reconnected
                   (d/connect (id/allocation-connect-config base-config))
                   _ (reset! !conn reconnected)
                   second-response (allocate reconnected "after-reconnect")]
               (is (true? (:seon.db/ok? second-response)))
               (is (not= (get-in first-response [::id/ids allocation-key])
                         (get-in second-response [::id/ids allocation-key])))
               (is (= 2 (count (d/datoms (d/db reconnected)
                                         :avet identity-attr))))))
           (finally
             (when-let [conn @!conn]
               (try (d/release conn) (catch Throwable _)))
             (when (d/database-exists? base-config)
               (d/delete-database base-config))
             (let [root (java.io.File. root-path)]
               (when (.exists root)
                 (run! (fn [^java.io.File file] (.delete file))
                       (reverse (file-seq root)))))))))

     (deftest concurrent-direct-jvm-allocations-use-writer-serialization
       (register-allocation-schema!)
       (let [conn (jvm-allocation-conn)
             fixed-candidate "q66ljwup2b5r"
             builder-calls (atom 0)
             request (fn [requested-key attr]
                       {::id/allocations
                        [{::id/key requested-key ::id/identity-attr attr}]
                        ::id/transaction-builder
                        (fn [ids]
                          (swap! builder-calls inc)
                          {:seon.db/tx-data
                           [{attr (get ids requested-key)}]})
                        :seon.db/conn conn})]
         (with-redefs-fn
           {generate-candidate (fn [_generator] fixed-candidate)}
           (fn []
             (let [responses (mapv deref
                                   [(future
                                      (id/allocate!
                                       (request allocation-key identity-attr)))
                                    (future
                                      (id/allocate!
                                       (request other-allocation-key
                                                other-identity-attr)))])
                   failure (first (remove :seon.db/ok? responses))]
               (is (= 1 (count (filter :seon.db/ok? responses))))
               (is (= 1 (count (remove :seon.db/ok? responses))))
               (is (= 17 @builder-calls))
               (is (= :seon.db.id.error/exhausted
                      (get-in failure [:seon.db/error :seon.error/data
                                       ::id/error])))
               (is (= 1 (+ (count (d/datoms (d/db conn) :avet identity-attr
                                            fixed-candidate))
                           (count (d/datoms (d/db conn) :avet
                                            other-identity-attr
                                            fixed-candidate))))))))))))

#?(:cljs
   (do
     (defn- conflict-envelope [candidate]
       {:seon.db/ok? false
        :seon.db/error
        {:seon.error/message "candidate conflict"
         :seon.error/kind :user-input
         :seon.error/data
         {::id/error :seon.db.id.error/candidate-conflict
          ::id/generated-candidate candidate}}})

     (defn- with-transact-stub [stub body]
       (let [original db.internal/transact!*]
         (set! db.internal/transact!* stub)
         (-> (js/Promise.resolve (body))
             (.finally (fn [] (set! db.internal/transact!* original))))))

     (defn- allocation-request
       ([builder]
        (allocation-request #js {} builder))
       ([conn builder]
        {::id/allocations
         [{::id/key allocation-key
           ::id/identity-attr identity-attr}]
         ::id/transaction-builder builder
         :seon.db/conn conn}))

     (defn- fresh-allocation-conn []
       (let [base-config {:store {:backend :memory :id (random-uuid)}
                          :schema-flexibility :write
                          :keep-history? true}
             connect-config (id/allocation-connect-config base-config)]
         (-> (d/create-database base-config)
             (.then (fn [_]
                      (d/connect connect-config {:sync? false}))))))

     (deftest local-allocation-uses-the-canonical-preparation
       (async done
              (register-allocation-schema!)
              (-> (fresh-allocation-conn)
                  (.then
                   (fn [conn]
                     (-> (id/allocate!
                          {::id/allocations
                           [{::id/key allocation-key
                             ::id/identity-attr identity-attr}
                            {::id/key other-allocation-key
                             ::id/identity-attr other-identity-attr}]
                           ::id/transaction-builder
                           (fn [ids]
                             {:seon.db/tx-data
                              [{identity-attr (get ids allocation-key)
                                :idtest.record/source "first"}
                               {:idtest.record/source "automatic-between"}
                               {other-identity-attr (get ids other-allocation-key)
                                :idtest.record/source "later"}]})
                           :seon.db/conn conn})
                         (.then (fn [response]
                                  {::response response ::conn conn})))))
                  (.then
                   (fn [{::keys [response conn]}]
                     (let [eids (::id/eids response)
                           automatic-eid
                           (:e (first (d/datoms @conn :avet
                                                :idtest.record/source
                                                "automatic-between")))]
                       (is (true? (:seon.db/ok? response)))
                       (is (= #{allocation-key other-allocation-key}
                              (set (keys eids))))
                       (is (= 3 (count (conj (set (vals eids))
                                             automatic-eid)))))))
                  (.catch (fn [error]
                            (is false (str "local allocation rejected: "
                                           (.-message error)))))
                  (.finally done))))

     (deftest concurrent-local-candidate-transactions-have-one-winner
       (async done
              (register-allocation-schema!)
              (-> (fresh-allocation-conn)
                  (.then
                   (fn [conn]
                     (-> (db.internal/ensure-datahike-attrs! conn [identity-attr])
                         (.then
                          (fn [_]
                            (let [candidate-value "q66ljwup2b5r"
                                  manifest [{::id/key allocation-key
                                             ::id/identity-attr identity-attr
                                             ::id/value candidate-value}]
                                  arg-map {:tx-data [{identity-attr candidate-value}]
                                           ::id/generated-candidates manifest
                                           ::id/generated-identity-attrs
                                           [identity-attr]}
                                  settle (fn [promise]
                                           (.then promise
                                                  (fn [_report] true)
                                                  (fn [_error] false)))]
                              (-> (js/Promise.all
                                   #js [(settle (d/transact! conn arg-map))
                                        (settle (d/transact! conn arg-map))])
                                  (.then
                                   (fn [results]
                                     {::conn conn
                                      ::results (vec (array-seq results))
                                      ::candidate-value candidate-value})))))))))
                  (.then
                   (fn [{::keys [conn results candidate-value]}]
                     (is (= 1 (count (filter true? results))))
                     (is (= 1 (count (filter false? results))))
                     (is (= 1 (count (d/datoms @conn :avet identity-attr
                                               candidate-value))))))
                  (.catch (fn [error]
                            (is false (str "concurrent local allocation rejected: "
                                           (.-message error)))))
                  (.finally done))))

     (deftest allocation-rebuilds-the-complete-transaction-after-a-conflict
       (async done
              (register-allocation-schema!)
              (let [!requests (atom [])
                    !builds   (atom [])
                    builder   (fn [ids]
                                (let [candidate (get ids allocation-key)]
                                  (swap! !builds conj candidate)
                                  {:seon.db/tx-data
                                   [{identity-attr candidate
                                     :idtest.record/source
                                     (str "source:" candidate)}]}))
                    stub      (fn [request]
                                (swap! !requests conj request)
                                (let [manifest (::id/generated-candidates request)]
                                  (js/Promise.resolve
                                   (if (= 1 (count @!requests))
                                     {:seon.db/ok? false
                                      :seon.db/error
                                      {:seon.error/message "Datahike upsert conflict"
                                       :seon.error/kind :user-input
                                       :seon.error/data
                                       {:error :transact/upsert
                                        :assertion
                                        [1 identity-attr
                                         (::id/value (first manifest))]}}}
                                     {:seon.db/ok? true
                                      :seon.db/tx 100
                                      :seon.db/tx-count 2
                                      :seon.db/added 2
                                      :seon.db/retracted 0
                                      :seon.db/tempids {}
                                      ::id/eids {allocation-key 42}}))))]
                (-> (with-transact-stub
                      stub
                      #(id/allocate! (allocation-request builder)))
                    (.then
                     (fn [response]
                       (is (:seon.db/ok? response))
                       (is (= 2 (count @!builds)))
                       (is (not= (first @!builds) (second @!builds)))
                       (is (= (second @!builds)
                              (get-in response [::id/ids allocation-key])))
                       (is (= 42 (get-in response [::id/eids allocation-key])))
                       (let [first-row  (-> @!requests first :seon.db/tx-data first)
                             second-row (-> @!requests second :seon.db/tx-data first)]
                         (is (= (str "source:" (identity-attr first-row))
                                (:idtest.record/source first-row)))
                         (is (= (str "source:" (identity-attr second-row))
                                (:idtest.record/source second-row)))
                         (is (not= (:idtest.record/source first-row)
                                   (:idtest.record/source second-row))))))
                    (.catch (fn [e]
                              (is false (str "allocation rejected: " (.-message e)))))
                    (.finally done)))))

     (deftest allocation-exhaustion-is-bounded-and-unrelated-errors-do-not-retry
       (async done
              (register-allocation-schema!)
              (let [builder (fn [ids]
                              {:seon.db/tx-data
                               [{identity-attr (get ids allocation-key)}]})
                    !calls  (atom 0)
                    always-conflicts
                    (fn [request]
                      (swap! !calls inc)
                      (js/Promise.resolve
                       (conflict-envelope
                        (first (::id/generated-candidates request)))))]
                (-> (with-transact-stub
                      always-conflicts
                      #(id/allocate! (allocation-request builder)))
                    (.then
                     (fn [response]
                       (is (false? (:seon.db/ok? response)))
                       (is (= 16 @!calls))
                       (is (= :seon.db.id.error/exhausted
                              (get-in response
                                      [:seon.db/error :seon.error/data
                                       ::id/error])))
                       (reset! !calls 0)
                       (with-transact-stub
                         (fn [_request]
                           (swap! !calls inc)
                           (js/Promise.resolve
                            {:seon.db/ok? false
                             :seon.db/error
                             {:seon.error/message "unrelated unique conflict"
                              :seon.error/kind :user-input
                              :seon.error/data
                              {:seon.store.wire/error-kind "datahike"}}}))
                         #(id/allocate! (allocation-request builder)))))
                    (.then
                     (fn [response]
                       (is (false? (:seon.db/ok? response)))
                       (is (= 1 @!calls))))
                    (.catch (fn [e]
                              (is false (str "allocation rejected: " (.-message e)))))
                    (.finally done)))))))
