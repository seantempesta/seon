(ns seon.call-preparation-test
  "Recurring proof for P17 S1: declared supplied defaults, the plan derived
  from program facts, and the cluster-local cache's basis boundary."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.call-preparation :as cp]
            [seon.db :as db]
            [seon.env :as env]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
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

(defn probe-received-connection?
  "True when this call's request map carried the cluster's connection."
  {:malli/schema
   [:=> [:cat [:map [:seon.db/connection :seon.db/connection]]] :boolean]}
  [request]
  (swap! entered inc)
  (db/connection? (:seon.db/connection request)))

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
  "The `pull` shape: a declared arity whose count collides with the shorter
  shape another arity derives.

  Two supplied arguments could mean either `[database label]` — the
  declared two-arity, database explicit — or `[label label]` with the
  three-arity's leading database elided. Ruling #41's shortcut survives
  because the leading slot's own value schema decides."
  {:malli/schema
   [:function
    [:=> [:cat [:or :seon.db/database-value :string] :string] :keyword]
    [:=> [:cat :seon.db/database-value :string :string] :keyword]]}
  ([leading _label]
   (if (db/database-value? leading) :explicit-database :two-labels))
  ([database _first _second]
   (if (db/database-value? database) :supplied-database :wrong-value)))

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

  RAW host Vars, deliberately. `seon.sci.eval/bind-first-party-namespaces!`
  installs a compiled first-party function as its real `clojure.lang.Var`
  unless some namespace row REFERS it, so a raw host Var in a callee
  position is the ordinary production shape rather than an exotic one.
  Binding `sci/new-var` forwarders here instead would have made every
  assertion below pass while every real first-party call stayed
  unprepared — which is exactly the defect this slice exists to kill."
  [connection]
  (-> (sci/init
       {:namespaces
        {'seon.call-preparation-test
         {'probe-received-database? #'probe-received-database?
          'probe-received-connection? #'probe-received-connection?
          'probe-received-both #'probe-received-both
          'probe-nilable-second #'probe-nilable-second
          'probe-untouched #'probe-untouched
          'probe-current-database #'probe-current-database
          'probe-shortcut #'probe-shortcut
          'probe-connection-name #'probe-connection-name}}
        :call-preparation-hook cp/hook})
      (assoc :seon.schema/projection (projection))
      (env/carry (environment-for connection))
      (cp/install)))

(defn- probe
  [ctx source]
  (sci/eval-string* ctx (str "(seon.call-preparation-test/" source ")")))

(deftest a-compiled-first-party-call-is-prepared
  (testing "positional, map-key, caller-wins, supplied-nil, undeclared and
            nested — every one of them through a RAW compiled host Var"
    (test-support/with-database
     (fn [connection]
       (db/transact! connection database-rows)
       (let [ctx (probe-ctx connection)]
         (testing "positional: the elided slot arrives at its recorded index"
           (is (true? (probe ctx "probe-received-database? \"a\""))))
         (testing "map key: an absent REQUIRED key is filled"
           (is (true? (probe ctx "probe-received-connection? {}"))))
         (testing "explicit caller wins, at a map key"
           (is (false? (probe ctx
                              "probe-received-connection? {:seon.db/connection 1}"))
               "the caller's 1 reached the body unreplaced"))
         (testing "explicit caller wins, at an exact full arity"
           (is (false? (probe ctx "probe-received-database? \"a\" 1"))))
         (testing "supplied nil is a supplied value, never an absence"
           (is (true? (probe ctx "probe-nilable-second \"a\" nil"))
               "nil occupied the slot, so nothing was supplied over it"))
         (testing "an undeclared function is untouched"
           (is (= "a" (probe ctx "probe-untouched \"a\""))))
         (testing "nested: an interpreted caller's direct call is prepared too"
           (is (true? (sci/eval-string*
                       ctx
                       (str "(do (defn outer [q] "
                            "(seon.call-preparation-test/probe-received-database?"
                            " q)) (outer \"a\"))")))
               "which falsifies any design preparing at one named entrance")))))))

(deftest a-two-slot-arity-derives-one-shorter-shape
  (testing "ruling 2's all-or-nothing model: the arity minus ALL its slots,
            never a subset of them"
    (test-support/with-database
     (fn [connection]
       (db/transact! connection database-rows)
       (let [ctx (probe-ctx connection)
             current (cp/snapshot @connection (projection))
             plan (cp/plan (cp/state) @connection current
                           "seon.call-preparation-test/probe-received-both")]
         (is (= #{1 3} (set (keys (:seon.call-preparation/by-supplied-count
                                   plan))))
             "3 is the declared arity and 1 its one derived shape; 2 — a
              single-slot omission — is deliberately not a call shape")
         (is (= [true true] (probe ctx "probe-received-both \"a\""))
             "and the one-argument call receives both declared values"))))))

(deftest the-leave-off-the-database-shortcut-survives-the-general-planner
  (testing "ruling #41's positional shortcut, decided by the leading slot's
            own declared value schema rather than by arity order"
    (test-support/with-database
     (fn [connection]
       (db/transact! connection database-rows)
       (let [ctx (probe-ctx connection)]
         (is (= :supplied-database (probe ctx "probe-shortcut \"a\" \"b\""))
             "two non-database arguments mean the three-arity with its
              leading database elided")
         (is (= :explicit-database
                (sci/eval-string*
                 ctx
                 (str "(seon.call-preparation-test/probe-shortcut "
                      "(seon.call-preparation-test/probe-current-database)"
                      " \"b\")")))
             "an explicit database value at the same count keeps the DECLARED
              two-arity — the caller always wins")
         (is (= :supplied-database
                (sci/eval-string*
                 ctx
                 (str "(seon.call-preparation-test/probe-shortcut "
                      "(seon.call-preparation-test/probe-current-database)"
                      " \"a\" \"b\")")))
             "and the exact declared three-arity is untouched"))))))

(deftest an-unavailable-supplier-refuses-before-the-body
  (testing "the unavailable face: a flat value naming the target, the key and
            the exact address, with the callee never entered"
    (test-support/with-database
     (fn [connection]
       (db/transact! connection database-rows)
       (let [current (cp/snapshot @connection (projection))
             environment (environment-for connection)
             slot {:seon.fn.argument/index 1
                   :seon.call-preparation/key :seon.db/db
                   :seon.call-preparation/supplier-symbol
                   'sample/nowhere-at-all}
             refusal (cp/supply current environment slot "sample/target")]
         (is (= :seon.call-preparation/unavailable (:seon.error/kind refusal)))
         (is (= "sample/target" (:seon.fn/sym (:seon.error/data refusal))))
         (is (= :seon.db/db
                (:seon.call-preparation/key (:seon.error/data refusal))))
         (is (= 1 (:seon.fn.argument/index (:seon.error/data refusal)))
             "the exact positional address, not merely the target")
         (is (not (str/includes? (:seon.error/message refusal) "*conn*"))
             "the face names the target and the key, never a dynamic var")
         (testing "and a refusing supplier short-circuits the whole call"
           (reset! entered 0)
           (let [result
                 (cp/prepare
                  current environment
                  {:seon.fn/sym
                   "seon.call-preparation-test/probe-untouched"
                   :seon.call-preparation/empty? false
                   :seon.call-preparation/by-supplied-count
                   {0 {:seon.call-preparation/ambiguous? false
                       :seon.call-preparation/inserts [slot]
                       :seon.call-preparation/entries []}}}
                  [])]
             (is (= :seon.call-preparation/unavailable
                    (:seon.error/kind result)))
             (is (zero? @entered) "the target body was never entered"))))))))

(deftest two-clusters-supply-their-own-custody
  (testing "two sovereign contexts live in one JVM; each call is supplied the
            connection of the context it ran under, and neither shares the
            other's plan cache"
    (test-support/with-database
     (fn [connection-a]
       (db/transact! connection-a database-rows)
       (test-support/with-database
        (fn [connection-b]
          (db/transact! connection-b database-rows)
          (let [ctx-a (probe-ctx connection-a)
                ctx-b (probe-ctx connection-b)
                name-a (probe ctx-a "probe-connection-name")
                name-b (probe ctx-b "probe-connection-name")]
            (is (string? name-a))
            (is (not= name-a name-b)
                "each context supplied its OWN cluster's connection")
            (is (not (identical? (get ctx-a cp/carrier)
                                 (get ctx-b cp/carrier)))
                "and the plan caches are separate state"))))))))

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
       (db/transact! connection database-rows)
       (let [ctx (sci.eval/cluster-ctx @connection connection)
             acquired-projection (:seon.schema/projection ctx)
             environment
             (env/refuse-incomplete-environment!
              (env/environment {:seon.boot/cluster-name "acquired"
                                :seon.db/connection connection
                                :seon.schema/projection acquired-projection}))
             live (env/carry-state ctx (env/environment-state environment))]
         (is (cp/state? (get ctx cp/carrier))
             "acquisition installs the call-preparation state; without this
              the hook reads nil and is silently inert at every call site")
         (is (some? (:call-preparation-hook ctx))
             "and the acquired context carries the hook itself")
         (testing "a contracted first-party function outside seon.db, called
                   with its declared connection elided, receives it"
           (is (true?
                (sci/eval-string*
                 live
                 (str "(seon.call-preparation-test/probe-received-connection?"
                      " {})")))))
         (testing "and an elided positional database value arrives too"
           (is (true?
                (sci/eval-string*
                 live
                 (str "(seon.call-preparation-test/probe-received-database?"
                      " \"a\")"))))))))))
