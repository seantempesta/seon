(ns seon.schema-usage-guard-test
  "Schema replacement/removal safety at the terminal transaction boundary."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as db]
            [datahike.db.interface :as dbi]
            [seon.turn :as turn]
            [seon.env :as env]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as test-support]))

(def ^:private base-key :seon.schema-usage-guard/base)
(def ^:private direct-key :seon.schema-usage-guard/direct)
(def ^:private transitive-key :seon.schema-usage-guard/transitive)
(def ^:private unrelated-key :seon.schema-usage-guard/unrelated)
(def ^:private entity-key :seon.schema-usage-guardb/entity)
(def ^:private entity-id-key :seon.schema-usage-guardb/entity-id)
(def ^:private entity-child-key :seon.schema-usage-guardb/entity-child)

(def ^:private forms
  {base-key [:int {:seon.db/index true}]
   direct-key [:and {:seon.db/index true} base-key]
   transitive-key [:and {:seon.db/index true} direct-key]})

(defn- with-database
  "This suite's fixture bracket: the canonical database AND a scoped schema
  registry.

  Every test here installs synthetic declarations and drives `row-tx`, the
  writer's own declaration path. Run in a live cluster's JVM those
  declarations are exactly what must not survive the test: on 2026-09-17 a
  leaked `:seon.schema-usage-guardb/entity-id` made every write on `default`
  refuse and filled the store. `seon.test/run` restores the registry around
  any in-process run; this bracket scopes it to each test as well, so the
  leak cannot outlive even one deftest."
  [body]
  (test-support/preserving-schema-registry
   #(test-support/with-database body)))

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
    (let [result (db/transact! connection tx-data)]
      (if (:seon.error/kind result)
        {:error result}
        {:report result}))
    (catch Throwable error
      {:error (deepest-ex-data error)})))

(defn- row-tx
  ([row] (row-tx {} row))
  ([request row] [[:db.fn/call #'turn/row-tx request row]]))

(defn- probe-projection
  "A projection carrying the canonical declaration population PLUS this
  suite's synthetic keys.

  AGENTS §5.1: never hand-roster schema. A projection built from a bare
  `{probe-key form}` map is not a smaller world, it is a BROKEN one — every
  instrumented `seon.schema` call made against it has to compile its OWN
  contract (`:seon.schema/registry-key` and friends) out of a population that
  holds one probe key, and refuses `:malli.core/invalid-schema` naming a
  declaration the suite never touched. That is the same disease as the
  2026-09-17 write storm, one layer down: an authority handed a projection
  that cannot resolve what it must compile."
  [probe-forms]
  (schema/build-projection (merge (schema/snapshot) probe-forms)))

(defn- schema-row
  [schema-key definition]
  {:seon.schema/key schema-key
   :seon.schema/form (pr-str definition)
   :seon.schema.admission/source :core})

(defn- advance-fixture-projection!
  "Advance the fixture's projection state to the database it just wrote.

  Production's declaration path does this at every declaration
  (`seon.sci.eval/advance-context-projection!`), which is what makes the next
  `:db.fn/call` compile against a population holding the references it was
  just given. A fixture that writes a declaration ROW and leaves the
  projection behind hands the writer a world its own facts contradict."
  [connection]
  (let [database (db/db connection)]
    (when-let [state (:seon.sci.eval/projection-state (meta database))]
      (env/advance-projection!
       state (db/basis-t database)
       (schema/projection-from-database database)))
    database))

(defn- install-forms!
  "Install synthetic declarations through ONE seam the registry and the writer
  both see.

  Writing the schema rows and the Datahike attributes is only half of a
  declaration. Production's declaration path also ADVANCES the cluster's
  projection state at the basis the write produced
  (`seon.sci.eval/advance-context-projection!`), which is what makes the next
  `:db.fn/call` compile the new definition against a population that holds its
  references. A fixture that wrote the rows and left the projection behind
  made every later `row-tx` here fail `:malli.core/invalid-schema` on
  `:seon.schema-usage-guardb/entity-id` — the suite's own declaration
  referring to a key its writer's projection did not carry."
  [connection selected-forms]
  (let [projection
        (reduce-kv
         (fn [current schema-key definition]
           (schema/projection-with-schema
            current schema-key definition
            {:seon.schema.admission/source :core}))
         (schema/projection-from-database @connection)
         selected-forms)
        report
        (test-support/transacted!
         connection
         (into
          (schema.datahike/malli->datahike-schema-in
           projection
           (schema.datahike/database-attributes-for-in
            projection selected-forms))
          (schema/canonical-schema-rows projection selected-forms)))
        _ (advance-fixture-projection! connection)]
    report))

(defn- schema-reference-edges
  [database]
  (db/q
   '[:find ?source-key ?target-key
     :where
     [?source :seon.schema/key ?source-key]
     [?source :seon.schema/references ?target]
     [?target :seon.schema/key ?target-key]]
   database))

(defn- live-schema-row?
  [database schema-key]
  (boolean
   (:seon.schema/form
    (db/pull database [:seon.schema/form]
             [:seon.schema/key schema-key]))))

(deftest unregister-stages-removal-in-the-evaluation-delta
  (let [projection (probe-projection {base-key :int})
        ;; The invariant is that STAGING does not mutate the source
        ;; projection — not that the source happens to hold one key. Comparing
        ;; against a literal one-key map asserted the hand-rostered population
        ;; instead, which is the thing the probe projection stopped being.
        entering (:seon.schema.projection/forms projection)
        delta (schema/begin-registration-delta projection)]
    (is (= base-key
           (schema/call-with-registration-delta
            delta #(schema/unregister! base-key))))
    (is (= #{base-key} (schema/changed-keys delta)))
    (is (nil? (schema/registration-delta-form delta base-key)))
    (is (= :int (get entering base-key))
        "the probe key entered the source projection")
    (is (= entering (:seon.schema.projection/forms projection))
        "staging does not mutate the source projection")))

(deftest schema-removal-refuses-schema-and-function-dependencies
  (let [projection
        (schema/build-projection
         (merge (schema/snapshot)
                {base-key :int
                 direct-key base-key})
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
            :extra-row true
            :expected-key :seon.schema.blockers/function-symbols
            :expected-value #{'seon.schema-usage-guard/accept}}]]
    (testing label
      (with-database
        (fn [connection]
          (install-forms! connection selected-forms)
          ;; A fn row's `:seon.fn/ns` is a REF. The namespace it names has to
          ;; be a fact before the row can resolve it; a fixture that writes
          ;; the row alone is refused at the write, and the refusal is about
          ;; the fixture, not the behaviour under test.
          (when extra-row
            (test-support/transacted!
             connection
             [{:seon.ns/name 'seon.schema-usage-guard}
              (assoc (test-support/program-fn-row
                      (db/db connection) 'seon.schema-usage-guard/accept
                      "(defn accept [value] value)")
                     :seon.fn/spec (pr-str [:=> [:cat base-key] :int]))])
            ;; The fn contract this row declares is what BLOCKS the deletion
            ;; below. It has to be in the projection the writer compiles, not
            ;; only in the datoms.
            (advance-fixture-projection! connection))
          (let [before @connection
                result
                (transact-result
                 connection
                 (row-tx
                  {:seon.program/delete-identities
                   [[:seon.schema/key base-key]]}))]
            (is (= :seon.schema/schema-in-use
                   (get-in result [:error :seon.schema/error])))
            (is (= expected-value (get-in result [:error expected-key])))
            (is (= (:max-tx before) (:max-tx @connection)))
            (is (some? (db/pull @connection [:db/id]
                               [:seon.schema/key base-key])))))))))

(deftest nonidentical-change-refuses-direct-and-transitive-current-data
  (doseq [used-key [direct-key transitive-key]]
    (testing (str "current data at " used-key)
      (with-database
        (fn [connection]
          (install-forms! connection forms)
          (test-support/transacted! connection [{used-key 7}])
          (let [before @connection
                result
                (transact-result
                 connection
                 (row-tx
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
                    (db/pull @connection [:seon.schema/form]
                            [:seon.schema/key base-key]))))
            (is (= 7
                   (db/q '[:find ?value .
                          :in $ ?attribute
                          :where [_ ?attribute ?value]]
                        @connection used-key)))))))))

(deftest identical-registration-is-idempotent-with-current-data
  (with-database
    (fn [connection]
      (install-forms! connection {base-key (get forms base-key)})
      (test-support/transacted! connection [{base-key 7}])
      (let [result
            (transact-result
             connection
             (row-tx (schema-row base-key (get forms base-key))))]
        (is (nil? (:error result)))
        (is (= (pr-str (get forms base-key))
               (:seon.schema/form
                (db/pull @connection [:seon.schema/form]
                        [:seon.schema/key base-key]))))
        (is (= 7
               (db/q '[:find ?value .
                      :in $ ?attribute
                      :where [_ ?attribute ?value]]
                    @connection base-key)))))))

(deftest retracted-current-data-allows-change-and-retains-history
  (with-database
    (fn [connection]
      (install-forms! connection {base-key (get forms base-key)})
      (test-support/transacted!
                   connection
                   [(schema-row unrelated-key [:string {:seon.db/index true}])])
      (is (not (contains? (:schema @connection) unrelated-key)))
      (test-support/transacted! connection [{base-key 7}])
      (let [entity (db/q '[:find ?entity .
                          :in $ ?attribute
                          :where [?entity ?attribute _]]
                        @connection base-key)]
        (test-support/transacted! connection [[:db/retract entity base-key]])
        (let [result
              (transact-result
               connection
               (row-tx
                (schema-row base-key
                            [:int {:min 1 :seon.db/index true}])))]
          (is (nil? (:error result)))
          (test-support/transacted! connection [{base-key 8}])
          (is (= 8
                 (db/q '[:find ?value .
                        :in $ ?attribute
                        :where [_ ?attribute ?value]]
                      @connection base-key)))
          (is (some #(and (= entity (:e %))
                          (= base-key (:a %))
                          (= 7 (:v %)))
                    (db/datoms (db/history @connection) :aevt base-key))
              "schema replacement does not purge historical data")
          (is (not (contains? (:schema @connection) unrelated-key))
              "replacement does not install unrelated absent attributes"))))))

(deftest one-decision-path-answers-every-schema-form-change
  ;; The class: two rules claiming one decision. `c55879b73` added an
  ;; unconditional immutability refusal ahead of the usage guard, so the
  ;; guard's typed answer never reached the caller and a change the guard
  ;; allows was refused anyway. There is now ONE decision path, and this
  ;; regression walks all three of its answers against one run so the coarse
  ;; rule cannot be reintroduced without failing here.
  (with-database
    (fn [connection]
      (let [run-id "schema-usage-guard-run"
            agent-id "schema-usage-guard-agent"
            namespace-name 'my.agents.schema-usage-guard
            request {:seon.turn/id run-id}]
        (install-forms! connection {base-key (get forms base-key)
                                    unrelated-key [:int {:seon.db/index true}]})
        (test-support/transacted! connection [{:seon.ns/name namespace-name
                                               :seon.ns/source "(ns my.agents.schema-usage-guard)"}
                                              {:seon.agent/id agent-id
                                               :seon.agent/namespace
                                               [:seon.ns/name namespace-name]}])
        (test-support/transacted! connection [{base-key 7}])
        (test-support/transacted!
                     connection
                     (turn/open-tx {:seon.turn/id run-id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}))
        (testing "current data answers with the guard's typed refusal"
          (let [refusal
                (transact-result
                 connection
                 (row-tx request
                         (schema-row base-key [:string {:seon.db/index true}])))]
            (is (= :seon.schema/current-data-blocks-change
                   (get-in refusal [:error :seon.schema/error]))
                "the finer instrument's answer reaches the caller")
            (is (= [base-key]
                   (get-in refusal [:error :seon.schema/data-attributes]))
                "and it names the attributes that blocked the change")))
        (testing "retraction clears the block and the same change succeeds"
          (let [entity (db/q '[:find ?entity .
                               :in $ ?attribute
                               :where [?entity ?attribute _]]
                             @connection base-key)]
            (test-support/transacted! connection [[:db/retract entity base-key]])
            (is (nil? (:error
                       (transact-result
                        connection
                        (row-tx request
                                (schema-row base-key
                                            [:string {:seon.db/index true}])))))))
          (is (= (pr-str [:string {:seon.db/index true}])
                 (:seon.schema/form
                  (db/pull @connection [:seon.schema/form]
                           [:seon.schema/key base-key])))))
        (testing "a form another writer changed since the run opened refuses"
          (test-support/transacted! connection
                                    [(schema-row unrelated-key
                                                 [:string {:seon.db/index true}])])
          (let [refusal
                (transact-result
                 connection
                 (row-tx request
                         (schema-row unrelated-key
                                     [:boolean {:seon.db/index true}])))]
            (is (= :seon.turn/refused
                   (get-in refusal [:error :seon.error/kind])))
            (is (= :seon.turn/program-row-changed-after-open
                   (get-in refusal [:error :seon.turn/rule]))
                "divergence from the opening basis is named as divergence")
            (is (= (pr-str [:string {:seon.db/index true}])
                   (:seon.schema/form
                    (db/pull @connection [:seon.schema/form]
                             [:seon.schema/key unrelated-key]))))))))))

(deftest entity-child-data-blocks-entity-schema-change
  (with-database
    (fn [connection]
      (let [entity-form
            [:map {:seon.db/attributes true}
             [entity-id-key entity-id-key]
             [entity-child-key :int]]
            replacement-form
            [:map {:seon.db/attributes true}
             [entity-id-key entity-id-key]
             [entity-child-key [:int {:min 1}]]]
            selected-forms
            {entity-id-key [:string {:seon.db/identity true}]
             entity-child-key [:int {:seon.db/index true}]
             entity-key entity-form}]
        (install-forms! connection selected-forms)
        (test-support/transacted!
                     connection
                     [(schema-row unrelated-key [:string {:seon.db/index true}])])
        (test-support/transacted! connection [{entity-child-key 7}])
        (let [before @connection
              refusal
              (transact-result
               connection
               (row-tx (schema-row entity-key replacement-form)))]
          (is (= :seon.schema/current-data-blocks-change
                 (get-in refusal [:error :seon.schema/error])))
          (is (= [entity-child-key]
                 (get-in refusal [:error :seon.schema/data-attributes])))
          (is (= (:max-tx before) (:max-tx @connection)))
          (is (= (pr-str entity-form)
                 (:seon.schema/form
                  (db/pull @connection [:seon.schema/form]
                          [:seon.schema/key entity-key])))))
        (let [entity
              (db/q '[:find ?entity .
                     :in $ ?attribute
                     :where [?entity ?attribute _]]
                   @connection entity-child-key)]
          (test-support/transacted! connection [[:db/retract entity entity-child-key]])
          (let [result
                (transact-result
                 connection
                 (row-tx
                  (schema-row entity-key replacement-form)))]
            (is (nil? (:error result)))
            (is (= (pr-str replacement-form)
                   (:seon.schema/form
                    (db/pull @connection [:seon.schema/form]
                            [:seon.schema/key entity-key]))))
            (is (contains? (:schema @connection) entity-child-key))
            (is (contains? (:schema @connection) entity-id-key))
            (is (not (contains? (:schema @connection) unrelated-key))
                "entity replacement leaves unrelated attributes absent")))))))

(deftest entity-lifecycle-preserves-surviving-global-leaf-attributes
  (with-database
    (fn [connection]
      (let [entity-form
            [:map {:seon.db/attributes true}
             [entity-id-key entity-id-key]
             [entity-child-key entity-child-key]]
            reduced-entity-form
            [:map {:seon.db/attributes true}
             [entity-id-key entity-id-key]]
            selected-forms
            {entity-id-key [:string {:seon.db/identity true}]
             entity-child-key [:int {:seon.db/index true}]
             entity-key entity-form}]
        (install-forms! connection selected-forms)
        (is (= #{[entity-key entity-id-key]
                 [entity-key entity-child-key]}
               (into #{}
                     (filter (fn [[source _]] (= entity-key source)))
                     (schema-reference-edges @connection))))
        (is (nil?
             (:error
              (transact-result
               connection
               (row-tx
                (schema-row entity-key reduced-entity-form))))))
        (is (= #{[entity-key entity-id-key]}
               (into #{}
                     (filter (fn [[source _]] (= entity-key source)))
                     (schema-reference-edges @connection)))
            "replacement retracts the removed child reference edge")
        (is (nil?
             (:error
              (transact-result
               connection
               (row-tx
                {:seon.program/delete-identities
                 [[:seon.schema/key entity-key]]})))))
        (is (not (live-schema-row? @connection entity-key)))
        (is (not-any? (fn [[source target]]
                        (or (= entity-key source) (= entity-key target)))
                      (schema-reference-edges @connection))
            "removal leaves no current incoming or outgoing reference edge")
        (doseq [leaf-key [entity-id-key entity-child-key]]
          (is (live-schema-row? @connection leaf-key))
          (is (contains? (:schema @connection) leaf-key)))
        (test-support/transacted! connection [{entity-id-key "survivor"
                                             entity-child-key 7}])
        (is (= ["survivor" 7]
               (db/q '[:find [?id ?child]
                      :in $ ?id-attribute ?child-attribute
                      :where
                      [?entity ?id-attribute ?id]
                      [?entity ?child-attribute ?child]]
                    @connection entity-id-key entity-child-key)))))))

(deftest generic-schema-deletion-removes-unused-row-and-attribute
  (with-database
    (fn [connection]
      (install-forms! connection {base-key (get forms base-key)})
      (let [result
            (transact-result
             connection
             (row-tx
              {:seon.program/delete-identities
               [[:seon.schema/key base-key]]}))]
        (is (nil? (:error result)))
        (is (not (live-schema-row? @connection base-key))
            "the unique key may remain as an identity tombstone")
        (is (not-any? (fn [[source target]]
                        (or (= base-key source) (= base-key target)))
                      (schema-reference-edges @connection)))
        (is (not (contains? (:schema @connection) base-key)))))))

(deftest retracted-data-allows-removal-and-historical-rows-restore-validation
  (with-database
    (fn [connection]
      (install-forms! connection {base-key (get forms base-key)})
      (test-support/transacted! connection [{base-key 7}])
      (let [data-t (:max-tx @connection)
            entity
            (db/q '[:find ?entity .
                   :in $ ?attribute
                   :where [?entity ?attribute _]]
                 @connection base-key)]
        (test-support/transacted! connection [[:db/retract entity base-key]])
        (let [result
              (transact-result
               connection
               (row-tx
                {:seon.program/delete-identities
                 [[:seon.schema/key base-key]]}))
              past (db/as-of @connection data-t)
              past-projection (schema/projection-from-database past)]
          (is (nil? (:error result)))
          (is (not (live-schema-row? @connection base-key)))
          (is (not-any? (fn [[source target]]
                          (or (= base-key source) (= base-key target)))
                        (schema-reference-edges @connection)))
          (is (not (contains? (:schema @connection) base-key)))
          (is (= 7
                 (d/q '[:find ?value .
                        :in $ ?attribute
                        :where [_ ?attribute ?value]]
                      past base-key))
              "as-of Datalog retains the pre-retraction value")
          (is (= (pr-str (get forms base-key))
                 (:seon.schema/form
                  (db/pull past [:seon.schema/form]
                          [:seon.schema/key base-key])))
              "the historical program row retains the validator source")
          (is (true?
               ((schema/projection-validator past-projection base-key) 7))
              "the historical row rebuilds Malli validation at that basis")
          (is (nil? (get (dbi/-schema past) base-key))
              "Datahike as-of delegates to the current schema map"))))))

(deftest no-history-data-is-not-promised-to-simulations
  (with-database
    (fn [connection]
      (let [schema-key :seon.schema-usage-guard/no-history
            definition
            [:int {:seon.db/index true :seon.db/no-history? true}]]
        (install-forms! connection {schema-key definition})
        (test-support/transacted! connection [{schema-key 7}])
        (let [data-t (:max-tx @connection)
              entity
              (db/q '[:find ?entity .
                     :in $ ?attribute
                     :where [?entity ?attribute _]]
                   @connection schema-key)]
          (test-support/transacted! connection [[:db/retract entity schema-key]])
          (is (nil?
               (db/q '[:find ?value .
                      :in $ ?attribute
                      :where [_ ?attribute ?value]]
                    (db/as-of @connection data-t) schema-key))
              ":seon.db/no-history? intentionally removes the past value"))))))
