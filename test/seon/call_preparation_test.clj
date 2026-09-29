(ns seon.call-preparation-test
  "Recurring proof for P17 S1: declared supplied defaults, the plan derived
  from program facts, and the armed wrapper preparing a call from the calling
  evaluation's world."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.plan :as plan]
            [sci.core :as sci]
            [seon.batch]
            [seon.call-preparation :as cp]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error :as error]
            [seon.instrument :as instrument]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as test-support]))

;;; ---------------------------------------------------------------------------
;;; Probe targets — ordinary contracted functions of this namespace.
;;;
;;; `test/` is a program source root (`seon.fn/source-roots`), so these carry
;;; complete P12 argument-address facts in every fixture database. They are the
;;; integration probe's callees: nothing about them is special, which is the
;;; point — call preparation reads their declared contract and nothing else.
;;; ---------------------------------------------------------------------------

(def entered
  "How many times a probe body has been entered.

  The unavailable face's proof is not only the returned value: preparation
  must refuse BEFORE the callee runs, and a counter is the only way to say
  that without trusting the value."
  (atom 0))

(defn probe-received-database?
  "True when this call received a database value at its second slot."
  {:malli/schema [:=> [:cat :string :seon.db/database-value] :boolean]}
  [_label database]
  (swap! entered inc)
  (db/database-value? database))

(defn probe-optional-connection?
  "True when this call's request map carried a connection; absence is meaningful."
  {:malli/schema
   [:=> [:cat [:map [:seon.db/connection {:optional true} :seon.db/connection]]] :boolean]}
  [request]
  (contains? request :seon.db/connection))

(defn probe-received-connection?
  "True when this call's request map carried the cluster's connection."
  {:malli/schema
   [:=> [:cat [:map [:seon.db/connection :seon.db/connection]]] :boolean]}
  [request]
  (swap! entered inc)
  (db/connection? (:seon.db/connection request)))

(defn probe-map-needs-label
  "A required caller-authored field prevents omission of the request map."
  {:malli/schema
   [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
               [:sample/label :string]]] :string]}
  [request]
  (:sample/label request))

(defn probe-received-both
  "Both database values of a two-slot arity, for the all-or-nothing proof."
  {:malli/schema
   [:=> [:cat :seon.db/connection :string :seon.db/database-value]
    [:vector :boolean]]}
  [connection _label database]
  (swap! entered inc)
  [(db/connection? connection) (db/database-value? database)])

(defn probe-nilable-second
  "Whether an explicitly supplied second argument was preserved as nil."
  {:malli/schema
   [:=> [:cat :string [:or :seon.db/database-value :nil]] :boolean]}
  [_label database]
  (swap! entered inc)
  (nil? database))

(defn probe-repeated-database
  "A repeated supplied type cannot identify a partially omitted slot."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.db/database-value] :boolean]}
  [_first _second]
  (swap! entered inc)
  true)

(defn probe-untouched
  "A contracted probe declaring nothing suppliable."
  {:malli/schema [:=> [:cat :string] :string]}
  [label]
  (swap! entered inc)
  label)

(defn probe-current-database
  "The database value this call was supplied, for use as another call's
  explicit argument."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.db/database-value]}
  [database]
  database)

(defn probe-shortcut
  "The `pull` shape: a declared arity whose first input is another schema,
  colliding at its count with the shape another arity derives by eliding its
  leading database. The declared arity is matched in full, never prefixed."
  {:malli/schema
   [:function
    [:=> [:cat [:or :seon.db/database-value :string] :string] :keyword]
    [:=> [:cat :seon.db/database-value :string :string] :keyword]]}
  ([leading _label]
   (if (db/database-value? leading) :explicit-database :two-labels))
  ([database _first _second]
   (if (db/database-value? database) :supplied-database :wrong-value)))

(defn probe-leading-database
  "The `projection-from-database` shape: `[database]` and `[database x]`.

  A one-argument call matches the declared one-arity, so it runs that arity
  or is refused by its contract; it is never read as the two-arity with its
  leading database supplied."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value] :keyword]
    [:=> [:cat :seon.db/database-value :string] :keyword]]}
  ([_database]
   (swap! entered inc)
   :one-arity)
  ([_database _label]
   (swap! entered inc)
   :two-arity))

(defn probe-connection-name
  "The identity of the connection this call was supplied.

  Two sovereign clusters must answer differently, which a boolean could
  never show."
  {:malli/schema [:=> [:cat :seon.db/connection] :string]}
  [connection]
  (pr-str (db/connection-identity connection)))

;;; ---------------------------------------------------------------------------
;;; Fixture helpers
;;; ---------------------------------------------------------------------------

(def ^:private database-entities
  "The two shipped supplied-default rows, transacted by the test itself.

  Deliberately independent of `config/default.edn`: this proves the
  MECHANISM, and a row is a row wherever it came from."
  [{:seon.call-preparation/key :seon.db/db
    :seon.call-preparation/schema [:seon.schema/key :seon.db/database-value]
    :seon.call-preparation/supplier
    [:seon.fn/sym 'seon.db/supplied-database-value]}
   {:seon.call-preparation/key :seon.db/connection
    :seon.call-preparation/schema [:seon.schema/key :seon.db/connection]
    :seon.call-preparation/supplier [:seon.fn/sym 'seon.db/supplied-connection]}])

(defn- projection
  "The acquired projection a probe hands to call preparation.

  It carries the synthetic `:sample/marker` because a cluster whose rows
  name a value schema necessarily has that schema in its own projection;
  a projection that does NOT is proved separately to refuse the row."
  []
  (schema/declaration-projection
   (assoc (schema/declaration-population) :sample/marker :int)))

(defn- synthetic-population
  "Schema and function rows for one synthetic supplied default.

  `return-key` is the supplier's declared success arm: passing the row's own
  value schema makes the row coherent, and passing anything else makes it the
  incoherence falsifier. `target-spec` redefines `sample/target`'s contract."
  ([database return-key]
   (synthetic-population database return-key [:=> [:cat :string :sample/marker] :string]))
  ([database return-key target-spec]
  (let [forms (assoc (schema/declaration-population) :sample/marker :int)
        supplier-spec [:=> [:cat :seon.env/environment]
                       [:or return-key :seon.call-preparation/unresolved-supplier-error]]
        contracts {'sample/supply-marker supplier-spec
                   'sample/target target-spec}
        built (schema/build-projection forms contracts)
        compile-options (:seon.schema.projection/compile-options built)
        predicate-functions (:seon.schema.projection/predicate-functions built)
        contract-facts
        (fn [function-symbol spec source arglists]
          (program/contract-facts
           {:seon.program/function-symbol function-symbol
            :seon.program/spec (pr-str spec)
            :seon.program/source source
            :seon.program/arglists (pr-str arglists)
            :seon.program/compile-options compile-options
            :seon.program/predicate-functions predicate-functions
            :seon.program/schema-keys (set (keys forms))
            :seon.program/schema-forms forms}))
        function-entity
        (fn [function-symbol spec source arglists]
          (test-support/program-fn-entity
           database function-symbol source
           (assoc (contract-facts function-symbol spec source arglists) :seon.fn/spec (pr-str spec))))]
    [(test-support/namespace-entity database 'sample)
     (program/with-contract-facts
      {:seon.program/row (program/declaration-row
                          (db/carried-projection database)
                          {:seon.schema/key :sample/marker :seon.schema/form ":int"}
                          :all :core)
       :seon.program/compile-options compile-options
       :seon.program/predicate-functions predicate-functions
       :seon.program/schema-keys #{:sample/marker}
       :seon.program/schema-forms forms})
     (function-entity 'sample/supply-marker supplier-spec
                   "(defn supply-marker [environment] 1)" '([environment]))
     (function-entity 'sample/target target-spec
                   "(defn target [label marker] label)" '([label marker]))
     {:seon.call-preparation/key :sample/marker
      :seon.call-preparation/schema [:seon.schema/key :sample/marker]
      :seon.call-preparation/supplier [:seon.fn/sym 'sample/supply-marker]}])))

(defn- slot-keys
  [plan supplied-count]
  (mapv :seon.call-preparation/key
        (get-in plan [:seon.call-preparation/by-supplied-count supplied-count
                      :seon.call-preparation/inserts])))

;;; ---------------------------------------------------------------------------
;;; Registry-derived: an entity plus a matching contract, no dispatch edit
;;; ---------------------------------------------------------------------------

(deftest the-shipped-database-suppliers-are-coherent-in-the-executing-program
  ;; Both refused when a schema edit re-shaped `:seon.env/environment` in
  ;; republished arities but not in its own stored entity: no call anywhere was
  ;; then prepared, and every elided connection arrived as a missing argument.
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection database-entities)
     (let [current (cp/snapshot @connection (projection))]
       (is (= [] (mapv :seon.error/message (:seon.call-preparation/refusals current))))
       (is (= #{:seon.db/db :seon.db/connection}
              (set (keys (select-keys (:seon.call-preparation/supplied-defaults current)
                                      [:seon.db/db :seon.db/connection])))))))))

(deftest a-synthetic-third-default-is-derived-from-its-entity
  (testing "one transacted row and one declared function yield an injection
            plan with no change to any dispatch code"
    (test-support/with-database
     (fn [connection]
       (test-support/transacted! connection (synthetic-population (db/db connection) :sample/marker))
       (let [database @connection
             current (cp/snapshot database (projection))]
         (is (empty? (:seon.call-preparation/refusals current))
             "the synthetic supplier's declared return agrees with its entity")
         (is (contains? (:seon.call-preparation/supplied-defaults current)
                        :sample/marker))
         (let [plan (cp/plan-for database current 'sample/target)]
           (is (false? (:seon.call-preparation/empty? plan)))
           (is (= [{:seon.fn.argument/index 1
                    :seon.call-preparation/key :sample/marker
                    :seon.call-preparation/supplier-symbol
                    'sample/supply-marker}]
                  (get-in plan [:seon.call-preparation/by-supplied-count 1
                                :seon.call-preparation/inserts]))
               "a one-argument call inserts the declared slot at index 1")
           (is (= [] (get-in plan [:seon.call-preparation/by-supplied-count 2
                                   :seon.call-preparation/inserts]))
               "the exact two-argument call invokes unchanged")
           (is (= plan (cp/arm-plan current (db/carried-projection database) 'sample/target))
               "arming decides the same plan from the contract alone")))))))

(deftest an-undeclared-function-gets-an-empty-plan
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection (synthetic-population (db/db connection) :sample/marker))
     (let [database @connection
           current (cp/snapshot database (projection))
           plan (cp/plan-for database current 'sample/supply-marker)]
       (is (true? (:seon.call-preparation/empty? plan))
           "the supplier itself declares no supplied default")
       (is (nil? (cp/arm-plan current (db/carried-projection database) 'sample/supply-marker))
           "so its armed wrapper gets no preparation step")))))

(defn- with-program-less-connection
  "Call `body` with a history-less connection whose schema declares no
  program attribute beyond `initial-tx`, so Datahike refuses every other
  program read: the program graph cannot be read, which is not the same as
  a function being absent from it."
  ([body] (with-program-less-connection [] body))
  ([initial-tx body]
   (let [configuration {:store {:backend :memory :id (random-uuid)}
                        :schema-flexibility :write :keep-history? false
                        :initial-tx initial-tx}]
     (d/create-database configuration)
     (try
       (with-open [held (test-support/closeable (d/connect configuration) d/release)]
         (body @held))
       (finally (d/delete-database configuration))))))

(deftest an-unreadable-program-graph-is-not-an-uncontracted-function
  (test-support/with-database
   (fn [connection]
     (let [current (cp/snapshot @connection (projection))]
       (testing "a function absent from a readable program graph has no plan"
         (is (nil? (cp/plan-for @connection current 'sample/absent-function))))
       (with-program-less-connection
        (fn [unreadable-connection]
          (let [unreadable @unreadable-connection
                refused (cp/plan-for unreadable current 'seon.db/pull)]
            (is (true? (:seon.db/invalid-read refused))
                "the read error is returned, never nil \"not contracted\"")
            (is (= 'seon.db/q (:seon.db/refused-read-operation refused))))))))))

(deftest the-entity-basis-reads-history-or-refuses
  (let [basis #'cp/newest-entity-transaction
        attributes (cp/entity-attributes)]
    (testing "a store with history answers from its history view"
      (test-support/with-database
       (fn [connection]
         (is (pos-int? (basis @connection attributes))))))
    (testing "a store kept without history answers from its current value"
      (with-program-less-connection
       (mapv (fn [attribute] {:db/ident attribute :db/valueType :db.type/keyword
                              :db/cardinality :db.cardinality/one})
             attributes)
       (fn [connection]
         (let [report (d/transact connection
                                  [{:seon.call-preparation/key :sample/marker}])]
           (is (= :seon.config.db/keep-history?
                  (:seon.config/error-key (db/history @connection)))
               "the store's history refusal is the declared no-history case")
           (is (= (:max-tx (:db-after report)) (basis @connection attributes)))))))
    (testing "an unreadable entity range is the basis's read error, never 0"
      (with-program-less-connection
       (fn [connection]
         (is (true? (:seon.db/invalid-read (basis @connection attributes)))))))))

;;; ---------------------------------------------------------------------------
;;; Supplier coherence is proved at acquisition
;;; ---------------------------------------------------------------------------

(deftest an-entity-disagreeing-with-its-supplier-is-refused
  (testing "a row whose value schema is not the supplier's declared success
            arm never becomes an installed supplied default"
    (test-support/with-database
     (fn [connection]
       (test-support/transacted! connection (synthetic-population (db/db connection) :string))
       (let [database @connection
             current (cp/snapshot database (projection))
             refusal (first (filter #(= :sample/marker
                                        (:seon.call-preparation/key
                                         (:seon.error/data %)))
                                    (:seon.call-preparation/refusals current)))]
         (is (some? refusal) "the incoherent entity is refused")
         (is (contains? refusal :seon.call-preparation/incoherent-key))
         (is (not (contains? (:seon.call-preparation/supplied-defaults current)
                             :sample/marker))
             "and it is not installed, so it can never supply a value")
         (is (true? (:seon.call-preparation/empty?
                     (cp/plan-for database current 'sample/target)))
             "the target therefore has nothing to inject"))))))

(deftest an-entity-naming-an-absent-supplier-is-refused
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection
                               [(test-support/namespace-entity (db/db connection) 'sample)
                                (test-support/program-fn-entity
                                 (db/db connection) 'sample/nowhere "(defn nowhere [] nil)")
                                {:seon.call-preparation/key :sample/absent
                                 :seon.call-preparation/schema
                                 [:seon.schema/key :seon.db/database-value]
                                 :seon.call-preparation/supplier
                                 [:seon.fn/sym 'sample/nowhere]}])
     (let [current (cp/snapshot @connection (projection))
           refusal (first (filter #(= :sample/absent
                                      (:seon.call-preparation/key
                                       (:seon.error/data %)))
                                  (:seon.call-preparation/refusals current)))]
       (is (contains? refusal :seon.call-preparation/incoherent-key))
       (is (not (contains? (:seon.call-preparation/supplied-defaults current)
                           :sample/absent)))
       (testing "and the cluster's own shipped entities are unaffected"
         (is (contains? (:seon.call-preparation/supplied-defaults current)
                        :seon.db/db)))))))

;;; ---------------------------------------------------------------------------
;;; The basis boundary — no sleep, no listener
;;; ---------------------------------------------------------------------------

(deftest an-entity-published-after-a-warm-plan-is-seen-synchronously
  (testing "the connection exposes the committed database before any listener
            callback, so the basis comparison alone must refresh"
    (test-support/with-database
     (fn [connection]
       (let [cold @connection
             before (cp/snapshot cold (projection))
             warm (cp/plan-for cold before 'sample/target)]
         (is (nil? warm) "sample/target is not in the program graph yet")
         (test-support/transacted! connection (synthetic-population (db/db connection) :sample/marker))
         ;; No sleep and no listener: read the connection, compare the basis.
         (let [after-database @connection
               after (cp/snapshot after-database
                                          (projection))]
           (is (> (long (:seon.call-preparation/checked-through-t after))
                  (long (:seon.call-preparation/checked-through-t before)))
               "the snapshot re-derived at the newer basis")
           (is (contains? (:seon.call-preparation/supplied-defaults after)
                          :sample/marker))
           (is (= [:sample/marker]
                  (slot-keys (cp/plan-for after-database after
                                          'sample/target)
                             1))
               "and the plan compiled against the new entity")))))))

(deftest an-unrelated-transaction-refreshes-without-changing-the-entity-basis
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection (synthetic-population (db/db connection) :sample/marker))
     (let [first-database @connection
           first-snapshot (cp/snapshot first-database
                                               (projection))
           first-plan (cp/plan-for first-database first-snapshot
                                   'sample/target)]
       (test-support/transacted! connection [(test-support/namespace-entity @connection 'unrelated)])
       (let [next-database @connection
             next-snapshot (cp/snapshot next-database
                                                (projection))]
         (is (> (long (:seon.call-preparation/checked-through-t next-snapshot))
                (long (:seon.call-preparation/checked-through-t
                       first-snapshot)))
             "one conservative re-derivation happened")
         (is (= (:seon.call-preparation/basis-t first-snapshot)
                (:seon.call-preparation/basis-t next-snapshot))
             "but the entity basis did not move")
         (is (= first-plan
                (cp/plan-for next-database next-snapshot
                             'sample/target))
             "so the compiled plan survives unchanged"))))))

(deftest a-redefined-contract-replaces-its-held-plan
  ;; A contract change leaves the entity basis where it was; the plan is a
  ;; function of the contract, so the new contract plans its own slot.
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection (synthetic-population (db/db connection) :sample/marker))
     (let [fixed-projection (projection)
           inserted (fn [plan] (mapv :seon.fn.argument/index
                                     (get-in plan [:seon.call-preparation/by-supplied-count 1
                                                   :seon.call-preparation/inserts])))
           first-snapshot (cp/snapshot @connection fixed-projection)
           first-plan (cp/plan-for @connection first-snapshot 'sample/target)]
       (is (= [1] (inserted first-plan)))
       (test-support/transacted!
        connection
        (filterv #(= 'sample/target (:seon.fn/sym %))
                 (synthetic-population (db/db connection) :sample/marker
                                       [:=> [:cat :sample/marker :string] :string])))
       (let [next-snapshot (cp/snapshot @connection fixed-projection)]
         (is (= (:seon.call-preparation/basis-t first-snapshot)
                (:seon.call-preparation/basis-t next-snapshot))
             "the entity basis did not move")
         (is (= [0] (inserted (cp/plan-for @connection next-snapshot 'sample/target)))
             "the redefined contract's slot is the one supplied"))))))

;;; ---------------------------------------------------------------------------
;;; The snapshot is a function of what it reads (schedule #24s)
;;; ---------------------------------------------------------------------------

(def ^:private program-members
  [:seon.call-preparation/supplied-defaults
   :seon.call-preparation/validators])

(defn- unrelated-entity
  "One ordinary fact no snapshot query reads: a namespace row."
  {:malli/schema [:=> [:cat :seon.db/database-value :string] :map]}
  [database label]
  (test-support/namespace-entity database (symbol (str "call-preparation-unrelated-" label))))

(defn- alternate-entity
  "A second supplied-default row reusing the shipped database row's schema
  and supplier: a real change to an attribute the snapshot reads."
  [database]
  (let [entity (db/q '[:find (pull ?entity [{:seon.call-preparation/schema [:db/id]}
                                     {:seon.call-preparation/supplier [:db/id]}]) .
                    :where [?entity :seon.call-preparation/key :seon.db/db]]
                  database)]
    {:seon.call-preparation/key :test/alternate-database
     :seon.call-preparation/schema (get-in entity [:seon.call-preparation/schema :db/id])
     :seon.call-preparation/supplier (get-in entity [:seon.call-preparation/supplier :db/id])}))

(defn- held?
  "True when every program member of `later` is the object `earlier` holds."
  [earlier later]
  (every? #(identical? (get earlier %) (get later %)) program-members))

(deftest an-unrelated-commit-reads-the-held-snapshot
  (testing "a commit touching no attribute the derivation reads keys the same
            snapshot; only the database value's own basis is new"
    (test-support/with-database
     (fn [connection]
       (test-support/transacted! connection database-entities)
       (let [fixed-projection (projection)
             before (cp/snapshot @connection fixed-projection)]
         (test-support/transacted! connection [(unrelated-entity @connection "held")])
         (let [after-database @connection
               after (cp/snapshot after-database fixed-projection)]
           (is (held? before after) "no member was derived again")
           (is (= (db/basis-t after-database)
                  (:seon.call-preparation/checked-through-t after)))
           (is (> (long (:seon.call-preparation/checked-through-t after))
                  (long (:seon.call-preparation/checked-through-t before)))))
         (testing "and an entity change derives the new program"
           (test-support/transacted! connection [(alternate-entity @connection)])
           (let [changed (cp/snapshot @connection fixed-projection)]
             (is (not (held? before changed)))
             (is (contains? (:seon.call-preparation/supplied-defaults changed)
                            :test/alternate-database)))))))))

(deftest a-new-branch-at-a-derived-commit-reads-the-held-snapshot
  (testing "a sibling branch at the same commit is the same program: its first
            snapshot is the commit tier's, never a derivation"
    (let [[fixed-projection commit earlier]
          (test-support/with-database
           (fn [connection]
             (let [fixed-projection (projection)]
               [fixed-projection
                (:datahike/commit-id (:meta @connection))
                (cp/snapshot @connection fixed-projection)])))]
      (test-support/with-database
       (fn [connection]
         (is (= commit (:datahike/commit-id (:meta @connection)))
             "both fixture branches start at the executing handle's commit")
         (is (held? earlier (cp/snapshot @connection fixed-projection))))))))

(deftest an-older-value-and-a-sibling-branch-read-their-own-snapshot
  ;; CLASS (issue call-preparation-snapshot-answers-an-older-database-with-a-newer-program):
  ;; one call-state atom held the LATEST snapshot and answered every value at
  ;; or below its basis with it, so an older value, or a branch sharing the
  ;; cluster context, read a newer program's plans. REPL at 052552039: both
  ;; read the newer snapshot. A snapshot and a plan are functions of the
  ;; value they answer.
  (test-support/with-database
   (fn [connection]
     (let [database-0 @connection
           _ (test-support/transacted!
              connection [(test-support/namespace-entity @connection 'probe.cp)])
           _ (test-support/transacted!
              connection
              [(test-support/program-fn-entity
                @connection 'probe.cp/f
                "(defn f {:malli/schema [:=> [:cat :seon.db/database-value :int] :int]} [db x] x)")])
           database-1 @connection
           prepared? (fn [database current]
                       (false? (:seon.call-preparation/empty?
                                (cp/plan-for database current 'probe.cp/f))))
           newer (cp/snapshot database-1 (db/carried-projection database-1))
           older (cp/snapshot database-0 (db/carried-projection database-0))]
       (is (prepared? database-1 newer) "the newer program prepares the added function")
       (is (not (prepared? database-0 older)) "the older value is answered by its own program")
       (is (= (db/basis-t database-0) (:seon.call-preparation/checked-through-t older)))
       (is (identical? newer (cp/snapshot database-1 (db/carried-projection database-1)))
           "a committed value's second read is the held snapshot")
       (test-support/with-database
        (fn [sibling]
          (let [sibling-snapshot (cp/snapshot @sibling (db/carried-projection @sibling))]
            (is (not (prepared? @sibling sibling-snapshot))
                "a sibling branch at the earlier commit reads its own program"))))))))

(deftest a-report-changing-no-read-attribute-reads-its-committed-parent
  (testing "the writer's speculative value derives nothing when the report's
            datoms name no attribute the snapshot reads"
    (test-support/with-database
     (fn [connection]
       (test-support/transacted! connection database-entities)
       (let [fixed-projection (projection)
             committed (cp/snapshot @connection fixed-projection)
             report (d/with @connection [(unrelated-entity @connection "report")])
             speculative (cp/report-snapshot report fixed-projection)]
         (is (held? committed speculative))
         (is (= (db/basis-t (:db-after report))
                (:seon.call-preparation/checked-through-t speculative)))
         (is (= (dissoc (cp/snapshot (:db-after report) fixed-projection)
                        :seon.call-preparation/validators)
                (dissoc speculative :seon.call-preparation/validators))
             "the held members equal a derivation at the report value")
         (testing "and a report writing an entity derives at its report value"
           (let [entity-report (d/with @connection [(alternate-entity @connection)])
                 derived (cp/report-snapshot entity-report fixed-projection)]
             (is (contains? (:seon.call-preparation/supplied-defaults derived)
                            :test/alternate-database)))))))))

;;; ---------------------------------------------------------------------------
;;; Per-cluster plan isolation
;;; ---------------------------------------------------------------------------

(deftest a-second-cluster-compiles-from-its-own-facts
  (testing "a plan for one identity in cluster A is never cluster B's; B
            compiles from its own facts"
    (test-support/with-database
     (fn [connection-a]
       (test-support/transacted! connection-a (synthetic-population (db/db connection-a) :sample/marker))
       (let [database-a @connection-a
             snapshot-a (cp/snapshot database-a (projection))
             plan-a (cp/plan-for database-a snapshot-a 'sample/target)]
         (is (= [:sample/marker] (slot-keys plan-a 1)))
         (test-support/with-database
          (fn [connection-b]
            ;; B declares the same function identity, with no supplied
            ;; default for its slot.
            (test-support/transacted! connection-b
                                      (filterv #(not (contains?
                                                      % :seon.call-preparation/key))
                                               (synthetic-population (db/db connection-b) :sample/marker)))
            (let [database-b @connection-b
                  snapshot-b (cp/snapshot database-b
                                                  (projection))
                  plan-b (cp/plan-for database-b snapshot-b
                                      'sample/target)]
              (is (not (contains? (:seon.call-preparation/supplied-defaults
                                   snapshot-b)
                                  :sample/marker))
                  "B never acquired A's entity")
              (is (true? (:seon.call-preparation/empty? plan-b))
                  "B compiled an empty plan from its own facts")
              (is (= [:sample/marker] (slot-keys plan-a 1))
                  "and A's plan is untouched")))))))))

;;; ---------------------------------------------------------------------------
;;; The suppliers, and the armed wrapper that calls them
;;; ---------------------------------------------------------------------------

(defn- environment-for
  [connection]
  (env/refuse-incomplete-environment!
   (env/environment {:seon.boot/cluster-name "call-preparation-test"
                     :seon.db/connection connection
                     :seon.schema/projection (projection)})))

(deftest the-database-suppliers-read-the-environment
  (test-support/with-database
   (fn [connection]
     (let [environment (environment-for connection)]
       (is (true? (db/database-value?
                   (db/supplied-database-value environment))))
       (is (true? (db/connection? (db/supplied-connection environment))))
       (testing "an environment with no custody refuses as a flat value that
                 names no dynamic var"
         (let [bare (env/environment {:seon.boot/cluster-name "bare"})
               refusal (db/supplied-database-value bare)]
           (is (= 'seon.db/unsupplied-custody-error (:seon.error/operation refusal)))
           (is (nil? (:seon.db/binding (:seon.error/data refusal))))))))))

(def ^:private probe-vars
  {'probe-received-database? #'probe-received-database?
   'probe-received-connection? #'probe-received-connection?
   'probe-received-both #'probe-received-both
   'probe-nilable-second #'probe-nilable-second
   'probe-repeated-database #'probe-repeated-database
   'probe-untouched #'probe-untouched
   'probe-current-database #'probe-current-database
   'probe-shortcut #'probe-shortcut
   'probe-leading-database #'probe-leading-database
   'probe-connection-name #'probe-connection-name})

(defn- arm-probes!
  "Arm the probes as a cluster arms its loaded program (`instrument/apply!`):
  each contract compiled in the fixture's projection, each call preparation
  decided from the fixture's supplied-default rows."
  [database]
  (let [result (instrument/apply!
                {:seon.schema/projection (db/carried-projection database)
                 :seon.db/db database
                 :seon.instrument/changed-identities
                 (into #{} (map (fn [probe-var] [:seon.fn/sym (symbol probe-var)])) (vals probe-vars))})]
    (is (nil? (:seon.instrument/registration-observation result)))))

(defn- probe-ctx
  "A scratch ctx binding this namespace's probes the way a cluster does, whose
  evaluations run in `world-connection`'s world.

  RAW host Vars, deliberately: `seon.sci.eval/bind-first-party-namespaces!`
  installs a compiled first-party function as its real `clojure.lang.Var`, so
  the call reaches the Var's armed root, which prepares it from the world of
  the evaluation calling (`armed-eval`)."
  [entities-connection world-connection]
  (arm-probes! @entities-connection)
  (-> (sci/init {:namespaces {'seon.call-preparation-test probe-vars}})
      (assoc :seon.schema/projection (projection)
             ::kernel/guard (::kernel/guard (kernel/context-options)))
      (env/carry (environment-for world-connection))))

(defn- armed-eval
  "Evaluate `source` in `ctx` as an evaluation: under the kernel arm naming it."
  [ctx source]
  (kernel/with-arm ctx 5000 (fn [_] (sci/eval-string* ctx source))))

(defn- probe
  [ctx source]
  (armed-eval ctx (str "(seon.call-preparation-test/" source ")")))

(deftest unavailable-custody-refuses-the-call-instead-of-running-it-unprepared
  ;; The world's connection is released: its supplier cannot read a database.
  ;; WANTED since 68c721fbb: a prepared call is supplied the database of the
  ;; custody in force (`seon.instrument/calling-environment`), so the
  ;; evaluation runs under the released connection's custody. Without it, a
  ;; test member's custody (bound by the runner) supplied its live database and
  ;; the body ran.
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection database-entities)
     (with-program-less-connection
      (fn [released]
        (let [ctx (probe-ctx connection released)]
          (d/release released)
          (reset! entered 0)
          (let [refused (db/call-with-custody
                         {:seon.db/connection released}
                         #(probe ctx "probe-received-database? \"a\""))]
            (is (= :seon.db/db (:seon.call-preparation/unavailable-key refused))
                "the call's result names the default its world could not supply")
            (is (= (symbol #'probe-received-database?)
                   (:seon.call-preparation/target-symbol refused))
                "naming the call it refused")
            (is (zero? @entered) "the body never ran without its database"))
          (testing "a JVM test body runs as its member (`kernel/*host-world*`):
                    its own call is prepared from the member's live world,
                    not the probe ctx's released one"
            (reset! entered 0)
            (is (some? (kernel/world-ctx)))
            (is (true? ((var probe-received-database?) "a")))
            (is (= 1 @entered)))))))))

(deftest ^{:seon/tasks #{"0261d4d9bbe6"}}
  host-work-outside-an-evaluation-keeps-its-declared-arity
  ;; CLASS (acceptance run, 2026-09-26, error f962afd56382): settlement on the
  ;; turn thread called `(seon.turn/receipt-settle-tx request)`, the declared
  ;; one-arity. Its count collides with the two-arity's shape with the
  ;; database elided, and `request` is not a database, so preparation chose
  ;; the elided shape, had no world to supply from, and returned its
  ;; unavailable refusal as the call's value; `mapcat` spliced that map's
  ;; entries into tx-data. A call matching a declared arity whose first input
  ;; is not the supplied slot is never prefixed (0b134d2f2), with or without
  ;; a world.
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection database-entities)
     (arm-probes! @connection)
     (is (some? (kernel/world-ctx)) "a test body runs as its member")
     (is (= :two-labels ((var probe-shortcut) "a" "b"))
         "the declared arity runs with the arguments given")
     (let [ctx (probe-ctx connection connection)]
       (is (= :two-labels (probe ctx "probe-shortcut \"a\" \"b\""))
           "inside an evaluation too: the declared arity is never prefixed")))))

(deftest a-compiled-first-party-call-is-prepared
  (testing "positional, map-key, caller-wins, supplied-nil, undeclared and
            nested — every one of them through a RAW compiled host Var"
    (test-support/with-database
     (fn [connection]
       (test-support/transacted! connection database-entities)
       (let [ctx (probe-ctx connection connection)]
         (testing "positional: the elided slot arrives at its recorded index"
           (is (true? (probe ctx "probe-received-database? \"a\""))))
         (testing "map key: an absent REQUIRED key is filled"
           (is (true? (probe ctx "probe-received-connection? {}"))))
         (testing "an omitted map is constructed from its required suppliers"
           (is (true? (probe ctx "probe-received-connection?"))))
         (testing "a required caller-authored field prevents map construction"
           (is (= "required"
                  (probe-map-needs-label {:seon.db/connection connection
                                          :sample/label "required"})))
           (let [current (cp/snapshot @connection (projection))
                 invocation (cp/plan-for @connection current
                                         'seon.call-preparation-test/probe-map-needs-label)]
             (is (some? invocation))
             (is (= [] (cp/prepare current (environment-for connection)
                                   invocation [])))))
         ;; CALLER WINS IS PROVEN BY WHAT ARRIVED, and under the contracts
         ;; every cluster arms the arrival is observed one frame earlier: the
         ;; callee's own declared contract refuses the caller's `1` and names
         ;; it as the offending argument, which says the supplier did not
         ;; replace it more directly than a boolean computed inside the body
         ;; could. The callee is never entered either way.
         (testing "explicit caller wins, at a map key"
           (let [refusal (test-support/refusal-data
                          #(probe ctx
                                  "probe-received-connection? {:seon.db/connection 1}"))]
             (is (= :input (:seon.instrument/check refusal)))
             (is (= 'seon.call-preparation-test/probe-received-connection?
                    (:seon.error/operation refusal)))
             (is (= [{:seon.db/connection 1}]
                    (:seon.error/offending refusal))
                 "the caller's 1 reached the callee unreplaced")))
         (testing "explicit caller wins, at an exact full arity"
           (let [refusal (test-support/refusal-data
                          #(probe ctx "probe-received-database? \"a\" 1"))]
             (is (= :input (:seon.instrument/check refusal)))
             (is (= ["a" 1]
                    (:seon.error/offending refusal))
                 "the caller's 1 reached the callee unreplaced")))
         (testing "supplied nil is a supplied value, never an absence"
           (is (true? (probe ctx "probe-nilable-second \"a\" nil"))
               "nil occupied the slot, so nothing was supplied over it"))
         (testing "an undeclared function is untouched"
           (is (= "a" (probe ctx "probe-untouched \"a\""))))
         (testing "nested: an interpreted caller's direct call is prepared too"
           (is (true? (armed-eval
                       ctx
                       (str "(do (defn outer [q] "
                            "(seon.call-preparation-test/probe-received-database?"
                            " q)) (outer \"a\"))")))
               "which falsifies any design preparing at one named entrance")))))))

(deftest a-two-slot-arity-prepares-only-unique-partial-placements
  (testing "the cached all-default shape and schema-decided partial calls"
    (test-support/with-database
     (fn [connection]
       (test-support/transacted! connection database-entities)
       (let [ctx (probe-ctx connection connection)
             current (cp/snapshot @connection (projection))
             plan (cp/plan-for @connection current
                               'seon.call-preparation-test/probe-received-both)]
         (is (= #{1 3} (set (keys (:seon.call-preparation/by-supplied-count
                                   plan))))
             "partial placements are decided from values, never enumerated")
         (is (= [{:seon.fn.arity/min 1 :seon.fn.arity/max 3}]
                (cp/prepared-arities plan))
             "the count invariant includes partial calls without choosing their placement")
         (is (= [true true] (probe ctx "probe-received-both \"a\""))
             "the one-argument call receives both declared values")
         (is (= [true true]
                (armed-eval
                 ctx
                 (str "(seon.call-preparation-test/probe-received-both \"a\" "
                      "(seon.call-preparation-test/probe-current-database))")))
             "an explicit trailing database survives a leading omission")
         (reset! entered 0)
         (let [refusal
               (armed-eval
                ctx
                (str "(seon.call-preparation-test/probe-repeated-database "
                     "(seon.call-preparation-test/probe-current-database))"))]
           (is (= 1 (:seon.call-preparation/supplied-count refusal)))
           (is (= 2 (:seon.call-preparation/candidate-count refusal)))
           (is (= 1 (count (:seon.call-preparation/offending-arguments refusal))))
           (is (= #{[0] [1]}
                  (set (:seon.call-preparation/candidates refusal))))
           (is (zero? @entered) "an ambiguous call never enters the body")))))))

(deftest a-call-matching-a-declared-arity-is-never-prefixed
  ;; WANTED (AGENTS.md "Supply defaults by declared schema name", 0b134d2f2):
  ;; a call already matching a declared arity runs that arity and its
  ;; contract judges the arguments. The retired value dispatch read a
  ;; non-database first argument as the longer arity with the database
  ;; supplied, so `(schema/projection-from-database poisoned)` returned a
  ;; whole projection instead of refusing the caller's value.
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection database-entities)
     (let [ctx (probe-ctx connection connection)]
       (testing "one non-database argument is refused by the one-arity's contract"
         (reset! entered 0)
         (let [refusal (test-support/refusal-data
                        #(probe ctx "probe-leading-database \"a\""))]
           (is (= :input (:seon.instrument/check refusal)))
           (is (= 'seon.call-preparation-test/probe-leading-database
                  (:seon.error/operation refusal)))
           (is (= ["a"] (:seon.error/offending refusal))
               "the caller's argument reached the contract unprefixed")
           (is (zero? @entered) "the body never ran")))
       (is (= :one-arity
              (armed-eval ctx (str "(seon.call-preparation-test/probe-leading-database "
                                   "(seon.call-preparation-test/probe-current-database))")))
           "an explicit database runs the declared one-arity")
       (is (= :one-arity (probe ctx "probe-leading-database"))
           "a call matching no declared arity is completed by the supplied database")
       (is (= :two-arity
              (armed-eval ctx (str "(seon.call-preparation-test/probe-leading-database "
                                   "(seon.call-preparation-test/probe-current-database) \"b\")")))
           "the declared two-arity runs unchanged")
       (is (= :two-labels (probe ctx "probe-shortcut \"a\" \"b\""))
           "a declared arity whose first input is another schema is matched in
            full, never prefixed")))))

(deftest an-unavailable-supplier-refuses-before-the-body
  (testing "the unavailable face: a flat value naming the target, the key and
            the exact address, with the callee never entered"
    (test-support/with-database
     (fn [connection]
       (test-support/transacted! connection database-entities)
       (let [current (cp/snapshot @connection (projection))
             environment (environment-for connection)
             slot {:seon.fn.argument/index 1
                   :seon.call-preparation/key :seon.db/db
                   :seon.call-preparation/supplier-symbol
                   'sample/nowhere-at-all}
             refusal (cp/supply current environment slot 'sample/target)]
         (is (= :seon.db/db (:seon.call-preparation/unavailable-key refusal)))
         (is (= 'sample/target (:seon.fn/sym (:seon.error/data refusal))))
         (is (= :seon.db/db
                (:seon.call-preparation/key (:seon.error/data refusal))))
         (is (= 1 (:seon.fn.argument/index (:seon.error/data refusal)))
             "the exact positional address, not merely the target")
         (is (not (str/includes? (:seon.error/message refusal) "*conn*"))
             "the face names the target and the key, never a dynamic var")
         (testing "and a refusing supplier short-circuits the whole call"
           (reset! entered 0)
           ;; the plan is COMPILED, not hand-rostered: a declared plan carries
           ;; its identity, arities and count index, and a stand-in missing them
           ;; is refused by `prepare`'s own contract before the subject is
           ;; reached. Only the one thing under test — a slot whose supplier
           ;; cannot produce — is substituted into a real plan.
           (let [compiled (cp/plan-for
                           @connection current
                           'seon.call-preparation-test/probe-received-database?)
                 _ (is (some? compiled))
                 result
                 (cp/prepare
                  current environment
                  (assoc compiled
                         :seon.call-preparation/empty? false
                         :seon.call-preparation/by-supplied-count
                         {0 {:seon.call-preparation/ambiguous? false
                             :seon.call-preparation/inserts [slot]
                             :seon.call-preparation/entries []}})
                  [])]
             (is (= :seon.db/db (:seon.call-preparation/unavailable-key result)))
             (is (zero? @entered) "the target body was never entered"))))))))

(deftest an-optional-world-member-is-supplied-and-a-required-one-refuses
  ;; An input declared under a world member's schema name is supplied when
  ;; omitted, required or optional (owner ruling 2026-09-27, S3a): a function
  ;; whose meaning needs it absent declares another name. A present key is
  ;; never replaced. A required entry whose supplier cannot answer refuses by
  ;; name before the body.
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection database-entities)
     (let [current (cp/snapshot @connection (projection))
           environment (environment-for connection)
           optional-plan (cp/plan-for @connection current
                                      'seon.call-preparation-test/probe-optional-connection?)
           compiled (cp/plan-for @connection current
                                 'seon.call-preparation-test/probe-received-connection?)
           entry {:seon.fn.argument/index 0
                  :seon.call-preparation/key :seon.agent/id
                  :seon.call-preparation/entry-key :seon.agent/id
                  :seon.call-preparation/supplier-symbol 'sample/nowhere-at-all}]
       (is (every? (comp seq :seon.call-preparation/entries) (:seon.call-preparation/arities optional-plan))
           "the optional connection, a world member, is an entry the plan fills")
       ;; an optional entry is filled from a world only (e44ce97ff): with none
       ;; bound it stays absent, never read from the thread's custody
       (is (= [{:sample/label "caller" :seon.db/connection connection}]
              (binding [seon.batch/*world* {:seon.db/connection connection}]
                (cp/prepare current environment optional-plan [{:sample/label "caller"}])))
           "the omitted connection is filled from the evaluation's world")
       (is (= [{:sample/label "caller"}]
              (cp/prepare current environment optional-plan [{:sample/label "caller"}]))
           "with no evaluation's world the optional connection stays absent")
       (is (= [{:sample/label "caller" :seon.db/connection ::the-callers}]
              (cp/prepare current environment optional-plan [{:sample/label "caller" :seon.db/connection ::the-callers}]))
           "a connection the caller passes is never replaced")
       (is (some? compiled))
       (is (= :seon.agent/id
              (:seon.call-preparation/unavailable-key
               (cp/prepare current environment
                           (assoc compiled
                                  :seon.call-preparation/empty? false
                                  :seon.call-preparation/by-supplied-count
                                  {1 {:seon.call-preparation/ambiguous? false
                                      :seon.call-preparation/inserts []
                                      :seon.call-preparation/entries [entry]}})
                           [{:sample/label "caller"}])))
           "the required entry refuses by name")))))

(deftest two-clusters-supply-their-own-custody
  (testing "two sovereign contexts live in one JVM; each call is supplied the
            connection of the evaluation it ran under, through one armed Var"
    (test-support/with-database
     (fn [connection-a]
       (test-support/transacted! connection-a database-entities)
       (test-support/with-database
        (fn [connection-b]
          (test-support/transacted! connection-b database-entities)
          (let [ctx-a (probe-ctx connection-a connection-a)
                ctx-b (probe-ctx connection-b connection-b)
                name-a (probe ctx-a "probe-connection-name")
                name-b (probe ctx-b "probe-connection-name")]
            (is (string? name-a))
            (is (not= name-a name-b)
                "each evaluation supplied its OWN world's connection"))))))))

(deftest ^{:seon/tasks #{"d6fa80e274ac" "0261d4d9bbe6"}}
  a-function-made-in-one-context-uses-the-calling-evaluations-world
  ;; CLASS: a closure's call read its defaults from the context it was CREATED
  ;; in (SCI's captured ctx), so a function acquired from entities into a
  ;; connection-less base, or made in the agent's context and called from a
  ;; disposable copy, ran with no world or the wrong one. The world is the
  ;; calling evaluation's.
  (test-support/with-database
   (fn [connection-a]
     (test-support/transacted! connection-a database-entities)
     (test-support/with-database
      (fn [connection-b]
        (let [made-in (probe-ctx connection-a connection-a)
              _ (armed-eval made-in
                            (str "(defn connection-name [] "
                                 "(seon.call-preparation-test/probe-connection-name))"))
              copy (-> (sci/fork made-in)
                       (assoc ::kernel/guard (::kernel/guard made-in))
                       (env/carry (environment-for connection-b)))
              name-a (armed-eval made-in "(user/connection-name)")
              name-b (armed-eval copy "(user/connection-name)")]
          (is (= (pr-str (db/connection-identity connection-a)) name-a))
          (is (= (pr-str (db/connection-identity connection-b)) name-b)
              "the same closure, called from the copy's evaluation, used the copy's world")))))))

;;; ---------------------------------------------------------------------------
;;; The class this slice kills: a mechanism green on a scratch ctx and inert
;;; in production. Every assertion below goes through the ACQUIRED cluster
;;; context — `seon.sci.eval/cluster-ctx`, the one boot and recovery call —
;;; because a scratch ctx built by a test is precisely what let the previous
;;; version ship dead.
;;; ---------------------------------------------------------------------------

(deftest an-acquired-cluster-context-prepares-its-calls
  (testing "the production installation path, not a ctx this test built"
    (test-support/with-database
     (fn [connection]
       (test-support/transacted! connection database-entities)
       (let [ctx (sci.eval/cluster-ctx @connection connection)
             ;; cluster-ctx acquires lazily at `kernel/invoke`; a raw
             ;; `sci/eval-string*` needs the program acquired first.
             _ (sci.eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db @connection})
             acquired-projection (:seon.schema/projection ctx)
             environment
             (env/refuse-incomplete-environment!
              (env/environment {:seon.boot/cluster-name "acquired"
                                :seon.db/connection connection
                                :seon.schema/projection acquired-projection}))
             live (env/carry-state ctx (env/environment-state environment))]
         (arm-probes! @connection)
         (testing "a contracted first-party function outside seon.db, called
                   with its declared connection elided, receives it"
           (is (true?
                (armed-eval
                 live
                 (str "(seon.call-preparation-test/probe-received-connection?"
                      " {})")))))
         (testing "and an elided positional database value arrives too"
           (is (true?
                (armed-eval
                 live
                 (str "(seon.call-preparation-test/probe-received-database?"
                      " \"a\")"))))))))))

(deftest an-acquired-context-supplies-a-leading-database-to-plan
  (testing "a real first-party call uses its contracted shorter shape"
    (test-support/with-database
     (fn [connection]
       (test-support/transacted! connection database-entities)
       ;; This ordinary call is also the program-graph edge that makes the
       ;; source-string callee part of a reduced focused-test projection.
       (is (= "missing"
              (:my.plan/missing-agent-id
               (plan/plan {:seon.db/db @connection
                           :seon.agent/id "missing"}))))
       (let [ctx (sci.eval/cluster-ctx @connection connection)
             ;; cluster-ctx acquires lazily at `kernel/invoke`; a raw
             ;; `sci/eval-string*` needs the program acquired first.
             _ (sci.eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db @connection})
             acquired-projection (:seon.schema/projection ctx)
             environment
             (env/refuse-incomplete-environment!
              (env/environment {:seon.boot/cluster-name "plan-call"
                                :seon.db/connection connection
                                :seon.agent/id "missing"
                                :seon.schema/projection acquired-projection}))
             live (env/carry-state ctx (env/environment-state environment))
             current (cp/snapshot @connection acquired-projection)
             invocation-plan (cp/plan-for @connection current 'my.plan/plan)
             omitted (try
                       (armed-eval live "(my.plan/plan)")
                       (catch Throwable cause cause))
             explicit
             (armed-eval
              live
              (str "(my.plan/plan {:seon.db/db "
                   "(seon.call-preparation-test/probe-current-database), "
                   ":seon.agent/id \"missing\"})"))]
         (is (contains? (:seon.call-preparation/supplied-defaults current)
                        :seon.db/db))
         (let [prepared (cp/prepare current environment invocation-plan [])]
           (is (= 1 (count prepared)))
           (is (db/database-value? (:seon.db/db (first prepared))))
           (is (= "missing"
                  (:seon.agent/id (first prepared)))))
         (is (not (instance? Throwable omitted)))
         (is (= "missing" (:my.plan/missing-agent-id omitted)))
         (is (= (dissoc omitted :seon.error/at) (dissoc explicit :seon.error/at))
             "an explicit database wins and reaches the same function body"))))))

(deftest call-preparation-cache-coherence-does-not-widen-evaluation-evidence
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection database-entities)
     (let [captured (atom [])
           sym 'seon.call-preparation-test/probe-current-database
           invocation-plan
           (binding [db/*read-evidence-sink* captured]
             (let [current (cp/snapshot @connection
                                                (projection))]
               (cp/plan-for @connection current sym)))
           evidence (db/read-evidence
                     @captured {:seon.db/retain-read-results? true})
           attributes
           (into #{}
                 (mapcat (fn [entry]
                           (let [plan (:datahike.read/dependency-plan entry)]
                             (when (map? plan)
                               (mapcat :datahike.query.source/attributes
                                       (:datahike.query.dependency/sources
                                        plan))))))
                 evidence)
           entity (db/q '[:find (pull ?entity
                                   [:db/id
                                    {:seon.call-preparation/schema [:db/id]}
                                    {:seon.call-preparation/supplier [:db/id]}]) .
                       :where
                       [?entity :seon.call-preparation/key :seon.db/db]]
                     @connection)]
       (is (map? invocation-plan))
       (is (seq evidence))
       (is (not-any? #(= :all (:datahike.read/dependency-plan %)) evidence)
           "the snapshot's internal max-tx basis is not a semantic read")
       (is (every? attributes (cp/entity-attributes))
           "precise supplied-default declaration dependencies remain")
       (is (db/read-evidence-current? @connection evidence))
       (test-support/transacted! connection [(test-support/namespace-entity @connection 'unrelated-evidence-one)])
       (is (db/read-evidence-current? @connection evidence)
           "cold plan derivation is independent of unrelated settlement facts")
       (let [warm-captured (atom [])
             warm-plan
             (binding [db/*read-evidence-sink* warm-captured]
               (let [current (cp/snapshot @connection
                                                  (projection))]
                 (cp/plan-for @connection current sym)))
             warm-evidence (db/read-evidence @warm-captured)]
         (is (= invocation-plan warm-plan))
         (test-support/transacted! connection [(test-support/namespace-entity @connection 'unrelated-evidence-two)])
         (is (db/read-evidence-current? @connection warm-evidence)
             "warm contract revalidation does not become a semantic read"))
       (test-support/transacted!
                    connection
                    [{:seon.call-preparation/key :test/alternate-database
                      :seon.call-preparation/schema
                      (get-in entity [:seon.call-preparation/schema :db/id])
                      :seon.call-preparation/supplier
                      (get-in entity [:seon.call-preparation/supplier :db/id])}])
       (is (false? (db/read-evidence-current? @connection evidence))
           "a real supplied-default declaration change still invalidates")))))


(defn malformed-base-supplier
  "Return ordinary map data which satisfies no declared error declared-schema."
  {:malli/schema [:=> [:cat :seon.env/environment] :map]}
  [_environment]
  {:seon.error/at "not-an-instant"
   :seon.error/layer :seon.call-preparation/test
   :seon.error/operation 'seon.call-preparation-test/malformed-base-supplier})

(deftest supplier-failures-have-declared-schemas-and-malformed-claims-remain-data
  (test-support/with-database
   (fn [connection]
     (let [current (cp/snapshot (db/db connection) (projection))
           slot {:seon.fn.argument/index 0
                 :seon.call-preparation/key :seon.db/db
                 :seon.call-preparation/supplier-symbol
                 'seon.call-preparation-test/malformed-base-supplier}
           value (cp/supply current (environment-for connection) slot 'sample/target)
           missing (cp/supply current (environment-for connection)
                              (assoc slot :seon.call-preparation/supplier-symbol
                                     'sample/absent-supplier) 'sample/target)
           cause (get-in missing [:seon.error/data :seon.call-preparation/cause])]
       (is ((seon.schema/projection-validator (projection) :seon.call-preparation/invalid-supplied-value-error) value))
       (is (= :seon.db/db (:seon.call-preparation/invalid-key value)))
       (is (= :seon.db/database-value (:seon.call-preparation/schema-key value)))
       (is (not ((schema/projection-validator (projection) :seon.call-preparation/unavailable-error)
                 (:seon.error/offending value))))
       (is (= 'seon.call-preparation-test/malformed-base-supplier
              (:seon.error/operation (:seon.error/offending value))))
       (is ((seon.schema/projection-validator (projection) :seon.call-preparation/unavailable-error) missing))
       (is (= 'sample/target (:seon.call-preparation/target-symbol missing)))
       (is ((seon.schema/projection-validator (projection) :seon.call-preparation/unresolved-supplier-error) cause))
       (is (= 'sample/absent-supplier (:seon.call-preparation/unresolved-symbol cause)))
       (is (symbol? (:seon.error/exception-class cause))
           "the failed resolution's Throwable rides the refusal, never dropped")
       (is (instance? Throwable (:seon.error/offending cause)))))))

(deftest arming-plans-a-contract-whose-supplied-inputs-are-world-members-only
  ;; `arm-plan` joined contracts to the admitted rows only, while `plan-for`
  ;; also joined the world members: `my.agent/branches`, whose context names
  ;; only the store, connection and routing, was planned but armed without
  ;; preparation, so its documented zero-argument call refused its count.
  (test-support/with-database
   (fn [connection]
     (let [database @connection
           carried (db/carried-projection database)
           current (cp/snapshot database carried)]
       (is (= (cp/plan-for database current 'my.agent/branches)
              (cp/arm-plan current carried 'my.agent/branches))
           "arming and planning join the same supplied defaults")
       (is (contains? (:seon.call-preparation/by-supplied-count
                       (cp/arm-plan current carried 'my.agent/branches))
                      0)
           "the armed wrapper supplies the whole context of a zero-argument call")))))
