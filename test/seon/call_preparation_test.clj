(ns seon.call-preparation-test
  "Recurring proof for P17 S1: declared supplied defaults, the plan derived
  from program facts, and the cluster-local cache's basis boundary."
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.call-preparation :as cp]
            [seon.db :as db]
            [seon.env :as env]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

;;; ---------------------------------------------------------------------------
;;; Probe targets — ordinary contracted functions of this namespace.
;;;
;;; `test/` is a program source root (`seon.fn/source-roots`), so these carry
;;; complete P12 argument-address facts in every fixture database. They are the
;;; integration probe's callees: nothing about them is special, which is the
;;; point — call preparation reads their declared contract and nothing else.
;;; ---------------------------------------------------------------------------

(defn probe-received-database?
  "True when this call received a database value at its second slot."
  {:malli/schema [:=> [:cat :string :seon.db/database-value] :boolean]}
  [_label database]
  (db/database-value? database))

(defn probe-received-connection?
  "True when this call's request map carried the cluster's connection."
  {:malli/schema
   [:=> [:cat [:map [:seon.db/connection :seon.db/connection]]] :boolean]}
  [request]
  (db/connection? (:seon.db/connection request)))

;;; ---------------------------------------------------------------------------
;;; Fixture helpers
;;; ---------------------------------------------------------------------------

(def ^:private database-rows
  "The two shipped supplied-default rows, transacted by the test itself.

  Deliberately independent of `config/default.edn`: this proves the
  MECHANISM, and a row is a row wherever it came from."
  [{:seon.call-preparation/key :seon.db/db
    :seon.call-preparation/schema [:seon.schema/key :seon.db/database-value]
    :seon.call-preparation/supplier
    [:seon.fn/sym "seon.db/supplied-database-value"]}
   {:seon.call-preparation/key :seon.db/connection
    :seon.call-preparation/schema [:seon.schema/key :seon.db/connection]
    :seon.call-preparation/supplier [:seon.fn/sym "seon.db/supplied-connection"]}])

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
  incoherence falsifier."
  [return-key]
  (let [forms (assoc (schema/declaration-population) :sample/marker :int)
        supplier-spec [:=> [:cat :seon.env/environment]
                       [:or return-key :seon.error/value]]
        target-spec [:=> [:cat :string :sample/marker] :string]
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
        function-row
        (fn [function-symbol spec source arglists]
          (merge {:seon.fn/sym function-symbol
                  :seon.fn/ns [:seon.ns/name 'sample]
                  :seon.fn/source source
                  :seon.fn/arglists (pr-str arglists)
                  :seon.fn/private? false
                  :seon.fn/spec (pr-str spec)}
                 (contract-facts function-symbol spec source arglists)))]
    [{:seon.ns/name 'sample :seon.ns/source "(ns sample)"}
     (program/with-contract-facts
      {:seon.program/row {:seon.schema/key :sample/marker
                          :seon.schema/form ":int"
                          :seon.schema.admission/source :core}
       :seon.program/compile-options compile-options
       :seon.program/predicate-functions predicate-functions
       :seon.program/schema-keys #{:sample/marker}
       :seon.program/schema-forms forms})
     (function-row "sample/supply-marker" supplier-spec
                   "(defn supply-marker [environment] 1)" '([environment]))
     (function-row "sample/target" target-spec
                   "(defn target [label marker] label)" '([label marker]))
     {:seon.call-preparation/key :sample/marker
      :seon.call-preparation/schema [:seon.schema/key :sample/marker]
      :seon.call-preparation/supplier [:seon.fn/sym "sample/supply-marker"]}]))

(defn- slot-keys
  [plan supplied-count]
  (mapv :seon.call-preparation/key
        (get-in plan [:seon.call-preparation/by-supplied-count supplied-count
                      :seon.call-preparation/inserts])))

;;; ---------------------------------------------------------------------------
;;; Registry-derived: a row plus a matching contract, no dispatch edit
;;; ---------------------------------------------------------------------------

(deftest a-synthetic-third-default-is-derived-from-its-row
  (testing "one transacted row and one declared function yield an injection
            plan with no change to any dispatch code"
    (test-support/with-database
     (fn [connection]
       (db/transact! connection (synthetic-population :sample/marker))
       (let [database @connection
             current (cp/snapshot database (projection))
             call-state (cp/state)]
         (is (empty? (:seon.call-preparation/refusals current))
             "the synthetic supplier's declared return agrees with its row")
         (is (contains? (:seon.call-preparation/supplied-defaults current)
                        :sample/marker))
         (let [plan (cp/plan call-state database current "sample/target")]
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
           (is (identical? plan (cp/plan call-state database current
                                         "sample/target"))
               "the compiled plan is cached, not recompiled per call")))))))

(deftest an-undeclared-function-gets-an-empty-plan
  (test-support/with-database
   (fn [connection]
     (db/transact! connection (synthetic-population :sample/marker))
     (let [database @connection
           current (cp/snapshot database (projection))
           plan (cp/plan (cp/state) database current "sample/supply-marker")]
       (is (true? (:seon.call-preparation/empty? plan))
           "the supplier itself declares no supplied default")))))

;;; ---------------------------------------------------------------------------
;;; Supplier coherence is proved at acquisition
;;; ---------------------------------------------------------------------------

(deftest a-row-disagreeing-with-its-supplier-is-refused
  (testing "a row whose value schema is not the supplier's declared success
            arm never becomes an installed supplied default"
    (test-support/with-database
     (fn [connection]
       (db/transact! connection (synthetic-population :string))
       (let [database @connection
             current (cp/snapshot database (projection))
             refusal (first (filter #(= :sample/marker
                                        (:seon.call-preparation/key
                                         (:seon.error/data %)))
                                    (:seon.call-preparation/refusals current)))]
         (is (some? refusal) "the incoherent row is refused")
         (is (= :seon.call-preparation/incoherent-supplier
                (:seon.error/kind refusal)))
         (is (not (contains? (:seon.call-preparation/supplied-defaults current)
                             :sample/marker))
             "and it is not installed, so it can never supply a value")
         (is (true? (:seon.call-preparation/empty?
                     (cp/plan (cp/state) database current "sample/target")))
             "the target therefore has nothing to inject"))))))

(deftest a-row-naming-an-absent-supplier-is-refused
  (test-support/with-database
   (fn [connection]
     (db/transact! connection
                   [{:seon.ns/name 'sample :seon.ns/source "(ns sample)"}
                    {:seon.fn/sym "sample/nowhere"
                     :seon.fn/ns [:seon.ns/name 'sample]
                     :seon.fn/source "(defn nowhere [] nil)"
                     :seon.fn/private? false}
                    {:seon.call-preparation/key :sample/absent
                     :seon.call-preparation/schema
                     [:seon.schema/key :seon.db/database-value]
                     :seon.call-preparation/supplier
                     [:seon.fn/sym "sample/nowhere"]}])
     (let [current (cp/snapshot @connection (projection))
           refusal (first (filter #(= :sample/absent
                                      (:seon.call-preparation/key
                                       (:seon.error/data %)))
                                  (:seon.call-preparation/refusals current)))]
       (is (= :seon.call-preparation/incoherent-supplier
              (:seon.error/kind refusal)))
       (is (not (contains? (:seon.call-preparation/supplied-defaults current)
                           :sample/absent)))
       (testing "and the cluster's own shipped rows are unaffected"
         (is (contains? (:seon.call-preparation/supplied-defaults current)
                        :seon.db/db)))))))

;;; ---------------------------------------------------------------------------
;;; The basis boundary — no sleep, no listener
;;; ---------------------------------------------------------------------------

(deftest a-row-published-after-a-warm-plan-is-seen-synchronously
  (testing "the connection exposes the committed database before any listener
            callback, so the basis comparison alone must refresh"
    (test-support/with-database
     (fn [connection]
       (let [call-state (cp/state)
             cold @connection
             before (cp/current-snapshot call-state cold (projection))
             warm (cp/plan call-state cold before "sample/target")]
         (is (nil? warm) "sample/target is not in the program graph yet")
         (db/transact! connection (synthetic-population :sample/marker))
         ;; No sleep and no listener: read the connection, compare the basis.
         (let [after-database @connection
               after (cp/current-snapshot call-state after-database
                                          (projection))]
           (is (> (long (:seon.call-preparation/checked-through-t after))
                  (long (:seon.call-preparation/checked-through-t before)))
               "the snapshot re-derived at the newer basis")
           (is (contains? (:seon.call-preparation/supplied-defaults after)
                          :sample/marker))
           (is (= [:sample/marker]
                  (slot-keys (cp/plan call-state after-database after
                                      "sample/target")
                             1))
               "and the plan compiled against the new row")))))))

(deftest an-unrelated-transaction-refreshes-without-changing-the-row-basis
  (test-support/with-database
   (fn [connection]
     (db/transact! connection (synthetic-population :sample/marker))
     (let [call-state (cp/state)
           first-database @connection
           first-snapshot (cp/current-snapshot call-state first-database
                                               (projection))
           first-plan (cp/plan call-state first-database first-snapshot
                               "sample/target")]
       (db/transact! connection [{:seon.ns/name 'unrelated
                                  :seon.ns/source "(ns unrelated)"}])
       (let [next-database @connection
             next-snapshot (cp/current-snapshot call-state next-database
                                                (projection))]
         (is (> (long (:seon.call-preparation/checked-through-t next-snapshot))
                (long (:seon.call-preparation/checked-through-t
                       first-snapshot)))
             "one conservative re-derivation happened")
         (is (= (:seon.call-preparation/basis-t first-snapshot)
                (:seon.call-preparation/basis-t next-snapshot))
             "but the row basis did not move")
         (is (= first-plan
                (cp/plan call-state next-database next-snapshot
                         "sample/target"))
             "so the compiled plan survives unchanged"))))))

;;; ---------------------------------------------------------------------------
;;; Per-cluster plan isolation
;;; ---------------------------------------------------------------------------

(deftest a-second-cluster-compiles-from-its-own-facts
  (testing "warming a plan for one identity in cluster A never lets cluster B
            reuse it; B is a sovereign branch with its own state"
    (test-support/with-database
     (fn [connection-a]
       (db/transact! connection-a (synthetic-population :sample/marker))
       (let [state-a (cp/state)
             database-a @connection-a
             snapshot-a (cp/current-snapshot state-a database-a (projection))
             plan-a (cp/plan state-a database-a snapshot-a "sample/target")]
         (is (= [:sample/marker] (slot-keys plan-a 1)))
         (test-support/with-database
          (fn [connection-b]
            ;; B declares the same function identity, with no supplied
            ;; default for its slot.
            (db/transact! connection-b
                          (filterv #(not (contains?
                                          % :seon.call-preparation/key))
                                   (synthetic-population :sample/marker)))
            (let [state-b (cp/state)
                  database-b @connection-b
                  snapshot-b (cp/current-snapshot state-b database-b
                                                  (projection))
                  plan-b (cp/plan state-b database-b snapshot-b
                                  "sample/target")]
              (is (not (contains? (:seon.call-preparation/supplied-defaults
                                   snapshot-b)
                                  :sample/marker))
                  "B never acquired A's row")
              (is (true? (:seon.call-preparation/empty? plan-b))
                  "B compiled an empty plan from its own facts")
              (is (= [:sample/marker] (slot-keys plan-a 1))
                  "and A's plan is untouched")))))))))

;;; ---------------------------------------------------------------------------
;;; The suppliers, and the sci hook they compose with
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
           (is (= :seon.db/unsupplied-custody (:seon.error/kind refusal)))
           (is (nil? (:seon.db/binding (:seon.error/data refusal))))))))))

(defn- probe-ctx
  "A scratch ctx binding this namespace's probes the way a cluster does.

  Compiled first-party functions reach agent code as `sci/new-var`
  forwarders over the real JVM Var
  (`seon.sci.eval/forwarding-host-var`), and that matters here rather
  than being incidental: sci's hook fires only when the resolved callee
  is a `sci.lang.Var` (`sci.impl.utils/var?`), so a raw JVM Var placed
  straight into `:namespaces` is never prepared. Mirroring production is
  what makes this probe honest."
  [connection]
  (let [environment (environment-for connection)
        sci-namespace (sci/create-ns 'seon.call-preparation-test nil)
        forward (fn [host-var]
                  (let [host-meta (meta host-var)]
                    (sci/new-var (:name host-meta) host-var
                                 (assoc host-meta :ns sci-namespace))))]
    (-> (sci/init
         {:namespaces
          {'seon.call-preparation-test
           {'probe-received-database? (forward #'probe-received-database?)
            'probe-received-connection? (forward #'probe-received-connection?)}}
          :call-preparation-hook cp/hook})
        (assoc :seon.schema/projection (projection))
        (env/carry environment)
        (cp/install))))

(deftest a-plan-the-suppliers-and-the-sci-hook-compose
  (testing "the S1 composition probe: an elided positional slot and an absent
            required map key are both supplied through the runtime ctx"
    (test-support/with-database
     (fn [connection]
       (db/transact! connection database-rows)
       (let [ctx (probe-ctx connection)]
         (is (true? (sci/eval-string* ctx "(seon.call-preparation-test/probe-received-database? \"a\")"))
             "the elided database value arrived at index 1")
         (is (true? (sci/eval-string* ctx "(seon.call-preparation-test/probe-received-connection? {})"))
             "the absent required map key was filled")
         (testing "an explicit caller value always wins"
           (is (false? (sci/eval-string*
                        ctx "(seon.call-preparation-test/probe-received-connection? {:seon.db/connection 1})"))
               "the caller's 1 reached the body unreplaced"))
         (testing "an exact full call is untouched"
           (is (false? (sci/eval-string*
                        ctx "(seon.call-preparation-test/probe-received-database? \"a\" 1)")))))))))
