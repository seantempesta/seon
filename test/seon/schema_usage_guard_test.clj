(ns seon.schema-usage-guard-test
  "Schema replacement/removal safety at the terminal transaction boundary."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.db.interface :as dbi]
            [seon.cluster.run :as run]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.form :as schema.form]
            [seon.test-support :as test-support]))

(def ^:private base-key :seon.schema-usage-guard/base)
(def ^:private direct-key :seon.schema-usage-guard/direct)
(def ^:private transitive-key :seon.schema-usage-guard/transitive)
(def ^:private unrelated-key :seon.schema-usage-guard/unrelated)
(def ^:private entity-key :seon.schema-usage-guard/entity)
(def ^:private entity-id-key :seon.schema-usage-guard/entity-id)
(def ^:private entity-child-key :seon.schema-usage-guard/entity-child)

(def ^:private forms
  {base-key [:int {:seon.db/index true}]
   direct-key [:and {:seon.db/index true} base-key]
   transitive-key [:and {:seon.db/index true} direct-key]})

(defn- deepest-ex-data
  [error]
  (loop [throwable error, found nil]
    (if throwable
      (recur (ex-cause throwable)
             (or (not-empty (ex-data throwable)) found))
      found)))

(defn- transact-result
  [connection tx-data]
  (try
    {:report (d/transact connection tx-data)}
    (catch Throwable error
      {:error (deepest-ex-data error)})))

(defn- program-row-tx
  [row]
  [[:db.fn/call #'run/program-row-tx {} row]])

(defn- schema-row
  [schema-key definition]
  {:seon.schema/key schema-key
   :seon.schema/form (pr-str definition)})

(defn- install-forms!
  [connection selected-forms]
  (let [projection
        (reduce-kv
         (fn [current schema-key definition]
           (schema/projection-with-schema
            current schema-key definition
            {:seon.schema.admission/source :core}))
         (schema/projection-from-database @connection)
         selected-forms)]
    (d/transact
     connection
     (into
      (schema.datahike/malli->datahike-schema-in
       projection (schema.form/database-attributes selected-forms))
      (map (fn [[schema-key definition]]
             (schema-row schema-key definition)))
      selected-forms))))

(deftest unregister-stages-removal-in-the-evaluation-delta
  (let [projection (schema/build-projection {base-key :int})
        delta (schema/begin-registration-delta projection)]
    (is (= base-key
           (schema/call-with-registration-delta
            delta #(schema/unregister! base-key))))
    (is (= #{base-key} (schema/changed-keys delta)))
    (is (nil? (schema/registration-delta-form delta base-key)))
    (is (= {base-key :int}
           (:seon.schema.projection/forms projection))
        "staging does not mutate the source projection")))

(deftest schema-removal-refuses-schema-and-function-dependencies
  (let [projection
        (schema/build-projection
         {base-key :int
          direct-key base-key}
         {'seon.schema-usage-guard/accept
          [:=> [:cat direct-key] :int]})
        blockers (schema/schema-removal-blockers projection base-key)
        refusal
        (try
          (schema/projection-without-schema projection base-key)
          nil
          (catch clojure.lang.ExceptionInfo error
            (ex-data error)))]
    (is (= #{direct-key}
           (:seon.schema.blockers/schema-keys blockers)))
    (is (= #{'seon.schema-usage-guard/accept}
           (:seon.schema.blockers/function-symbols blockers)))
    (is (= :seon.schema/schema-in-use (:seon.schema/error refusal)))
    (is (= blockers
           (select-keys refusal
                        [:seon.schema.blockers/schema-keys
                         :seon.schema.blockers/function-symbols])))))

(deftest generic-schema-deletion-refuses-committed-dependencies
  (doseq [{:keys [label selected-forms extra-row expected-key expected-value]}
          [{:label "schema dependency"
            :selected-forms {base-key :int, direct-key base-key}
            :expected-key :seon.schema.blockers/schema-keys
            :expected-value #{direct-key}}
           {:label "function dependency"
            :selected-forms {base-key :int}
            :extra-row {:seon.fn/sym "seon.schema-usage-guard/accept"
                        :seon.fn/spec
                        (pr-str [:=> [:cat base-key] :int])}
            :expected-key :seon.schema.blockers/function-symbols
            :expected-value #{'seon.schema-usage-guard/accept}}]]
    (testing label
      (test-support/with-database
        (fn [connection]
          (install-forms! connection selected-forms)
          (when extra-row (d/transact connection [extra-row]))
          (let [before @connection
                result
                (transact-result
                 connection
                 (program-row-tx
                  {:seon.program/delete-identities
                   [[:seon.schema/key base-key]]}))]
            (is (= :seon.schema/schema-in-use
                   (get-in result [:error :seon.schema/error])))
            (is (= expected-value (get-in result [:error expected-key])))
            (is (= (:max-tx before) (:max-tx @connection)))
            (is (some? (d/pull @connection [:db/id]
                               [:seon.schema/key base-key])))))))))

(deftest nonidentical-change-refuses-direct-and-transitive-current-data
  (doseq [used-key [direct-key transitive-key]]
    (testing (str "current data at " used-key)
      (test-support/with-database
        (fn [connection]
          (install-forms! connection forms)
          (d/transact connection [{used-key 7}])
          (let [before @connection
                result
                (transact-result
                 connection
                 (program-row-tx
                  (schema-row base-key
                              [:string {:seon.db/index true}])))]
            (is (= :seon.schema/current-data-blocks-change
                   (get-in result [:error :seon.schema/error])))
            (is (= [used-key]
                   (get-in result [:error
                                   :seon.schema/data-attributes])))
            (is (= (:max-tx before) (:max-tx @connection))
                "refusal aborts the complete transaction")
            (is (= (pr-str (get forms base-key))
                   (:seon.schema/form
                    (d/pull @connection [:seon.schema/form]
                            [:seon.schema/key base-key]))))
            (is (= 7
                   (d/q '[:find ?value .
                          :in $ ?attribute
                          :where [_ ?attribute ?value]]
                        @connection used-key)))))))))

(deftest identical-registration-is-idempotent-with-current-data
  (test-support/with-database
    (fn [connection]
      (install-forms! connection {base-key (get forms base-key)})
      (d/transact connection [{base-key 7}])
      (let [result
            (transact-result
             connection
             (program-row-tx (schema-row base-key (get forms base-key))))]
        (is (nil? (:error result)))
        (is (= (pr-str (get forms base-key))
               (:seon.schema/form
                (d/pull @connection [:seon.schema/form]
                        [:seon.schema/key base-key]))))
        (is (= 7
               (d/q '[:find ?value .
                      :in $ ?attribute
                      :where [_ ?attribute ?value]]
                    @connection base-key)))))))

(deftest retracted-current-data-allows-change-and-retains-history
  (test-support/with-database
    (fn [connection]
      (install-forms! connection {base-key (get forms base-key)})
      (d/transact
       connection
       [(schema-row unrelated-key [:string {:seon.db/index true}])])
      (is (not (contains? (:schema @connection) unrelated-key)))
      (d/transact connection [{base-key 7}])
      (let [entity (d/q '[:find ?entity .
                          :in $ ?attribute
                          :where [?entity ?attribute _]]
                        @connection base-key)]
        (d/transact connection [[:db/retract entity base-key]])
        (let [result
              (transact-result
               connection
               (program-row-tx
                (schema-row base-key
                            [:int {:min 1 :seon.db/index true}])))]
          (is (nil? (:error result)))
          (d/transact connection [{base-key 8}])
          (is (= 8
                 (d/q '[:find ?value .
                        :in $ ?attribute
                        :where [_ ?attribute ?value]]
                      @connection base-key)))
          (is (some #(and (= entity (:e %))
                          (= base-key (:a %))
                          (= 7 (:v %)))
                    (d/datoms (d/history @connection) :aevt base-key))
              "schema replacement does not purge historical data")
          (is (not (contains? (:schema @connection) unrelated-key))
              "replacement does not install unrelated absent attributes"))))))

(deftest entity-child-data-blocks-entity-schema-change
  (test-support/with-database
    (fn [connection]
      (let [entity-form
            [:map {:seon.db/entity true}
             [entity-id-key entity-id-key]
             [entity-child-key :int]]
            replacement-form
            [:map {:seon.db/entity true}
             [entity-id-key entity-id-key]
             [entity-child-key [:int {:min 1}]]]
            selected-forms
            {entity-id-key [:string {:seon.db/identity true}]
             entity-child-key [:int {:seon.db/index true}]
             entity-key entity-form}]
        (install-forms! connection selected-forms)
        (d/transact
         connection
         [(schema-row unrelated-key [:string {:seon.db/index true}])])
        (d/transact connection [{entity-child-key 7}])
        (let [before @connection
              refusal
              (transact-result
               connection
               (program-row-tx (schema-row entity-key replacement-form)))]
          (is (= :seon.schema/current-data-blocks-change
                 (get-in refusal [:error :seon.schema/error])))
          (is (= [entity-child-key]
                 (get-in refusal [:error :seon.schema/data-attributes])))
          (is (= (:max-tx before) (:max-tx @connection)))
          (is (= (pr-str entity-form)
                 (:seon.schema/form
                  (d/pull @connection [:seon.schema/form]
                          [:seon.schema/key entity-key])))))
        (let [entity
              (d/q '[:find ?entity .
                     :in $ ?attribute
                     :where [?entity ?attribute _]]
                   @connection entity-child-key)]
          (d/transact connection [[:db/retract entity entity-child-key]])
          (let [result
                (transact-result
                 connection
                 (program-row-tx
                  (schema-row entity-key replacement-form)))]
            (is (nil? (:error result)))
            (is (= (pr-str replacement-form)
                   (:seon.schema/form
                    (d/pull @connection [:seon.schema/form]
                            [:seon.schema/key entity-key]))))
            (is (contains? (:schema @connection) entity-child-key))
            (is (contains? (:schema @connection) entity-id-key))
            (is (not (contains? (:schema @connection) unrelated-key))
                "entity replacement leaves unrelated attributes absent")))))))

(deftest generic-schema-deletion-removes-unused-row-and-attribute
  (test-support/with-database
    (fn [connection]
      (install-forms! connection {base-key (get forms base-key)})
      (let [result
            (transact-result
             connection
             (program-row-tx
              {:seon.program/delete-identities
               [[:seon.schema/key base-key]]}))]
        (is (nil? (:error result)))
        (is (nil? (d/pull @connection [:db/id]
                          [:seon.schema/key base-key])))
        (is (not (contains? (:schema @connection) base-key)))))))

(deftest retracted-data-allows-removal-and-historical-rows-restore-validation
  (test-support/with-database
    (fn [connection]
      (install-forms! connection {base-key (get forms base-key)})
      (d/transact connection [{base-key 7}])
      (let [data-t (:max-tx @connection)
            entity
            (d/q '[:find ?entity .
                   :in $ ?attribute
                   :where [?entity ?attribute _]]
                 @connection base-key)]
        (d/transact connection [[:db/retract entity base-key]])
        (let [result
              (transact-result
               connection
               (program-row-tx
                {:seon.program/delete-identities
                 [[:seon.schema/key base-key]]}))
              past (d/as-of @connection data-t)
              past-projection (schema/projection-from-database past)]
          (is (nil? (:error result)))
          (is (nil? (d/pull @connection [:db/id]
                            [:seon.schema/key base-key])))
          (is (not (contains? (:schema @connection) base-key)))
          (is (= 7
                 (d/q '[:find ?value .
                        :in $ ?attribute
                        :where [_ ?attribute ?value]]
                      past base-key))
              "as-of Datalog retains the pre-retraction value")
          (is (= (pr-str (get forms base-key))
                 (:seon.schema/form
                  (d/pull past [:seon.schema/form]
                          [:seon.schema/key base-key])))
              "the historical program row retains the validator source")
          (is (true?
               ((schema/projection-validator past-projection base-key) 7))
              "the historical row rebuilds Malli validation at that basis")
          (is (nil? (get (dbi/-schema past) base-key))
              "Datahike as-of delegates to the current schema map"))))))

(deftest no-history-data-is-not-promised-to-simulations
  (test-support/with-database
    (fn [connection]
      (let [schema-key :seon.schema-usage-guard/no-history
            definition
            [:int {:seon.db/index true :seon.db/no-history? true}]]
        (install-forms! connection {schema-key definition})
        (d/transact connection [{schema-key 7}])
        (let [data-t (:max-tx @connection)
              entity
              (d/q '[:find ?entity .
                     :in $ ?attribute
                     :where [?entity ?attribute _]]
                   @connection schema-key)]
          (d/transact connection [[:db/retract entity schema-key]])
          (is (nil?
               (d/q '[:find ?value .
                      :in $ ?attribute
                      :where [_ ?attribute ?value]]
                    (d/as-of @connection data-t) schema-key))
              ":seon.db/no-history? intentionally removes the past value"))))))
