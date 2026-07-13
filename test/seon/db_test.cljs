(ns seon.db-test
  "Tests + worked examples for `seon.db`. These tests double as the
   reference for how an agent should call the surface — read the names
   left-to-right, the bodies show usage, the assertions show expected
   behavior. Property tests cover the schema-gate invariants where
   generative coverage adds something a hand-written example wouldn't.

   Every key in every map handed to or returned by seon.db is fully
   namespaced under `:seon.db/*` — the `::db/keys` destructure idiom +
   `::db/foo` key forms are what you see throughout.

   Async tests use the standard `(async done …)` + Promise-chain
   envelope (same pattern as `seon.db.envelope-test`): `db/transact!`
   ALWAYS resolves to the `{::db/ok? …}` envelope — never rejects,
   never throws into the caller (A4, 2026-06-09).

   Run interactively via MCP eval:

     (require 'seon.db-test :reload)
     (cljs.test/run-tests 'seon.db-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing async use-fixtures]]
    [datahike.api :as d]
    [seon.agent]
    [seon.agent.message]
    [seon.db :as db]
    [seon.db.internal :as internal]
    [seon.instrument :as si]
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Test schemas. Registered once per test run, isolated under a test namespace
;; so we don't collide with production attribute names.
;; ---------------------------------------------------------------------------

(defn- register-test-schemas! []
  (schema/register! ::name :string)
  (schema/register! ::rank :int)
  (schema/register! ::tags [:vector :keyword])
  (schema/register! ::source :keyword)
  ;; ref attr for the as-of ref-join test; `db/transact!`'s gate needs it
  ;; registered (installation as :db.type/ref comes from `history-schema`).
  (schema/register! ::owner :seon.db/ref))

(use-fixtures :once
  {:before (fn []
             (register-test-schemas!)
             ;; The positional-arity tests assert INSTRUMENTED named-slot
             ;; errors (`[:seon.db/db]` explain paths). The pod installs
             ;; instrumentation at boot (`seon.client/-main` →
             ;; `seon.instrument/install!`); the node-test runner has no
             ;; -main, so install the same wrappers here — scoped to
             ;; `seon.db` so other suites' environment is unchanged.
             ;; Requires a DEV-compiled test build: malli's CLJS
             ;; instrument walks goog.global munged paths, which Closure
             ;; :simple/:advanced flatten away (see bin/test-cljs).
             (let [targets
                   [{::si/sym 'seon.db/query
                     ::si/schema-form (:malli/schema (meta #'db/query))}
                    {::si/sym 'seon.db/pull
                     ::si/schema-form (:malli/schema (meta #'db/pull))}
                    {::si/sym 'seon.db/entity
                     ::si/schema-form (:malli/schema (meta #'db/entity))}]]
               (si/instrument-delta!
                 {::si/changed-syms (into #{} (map ::si/sym) targets)
                  ::si/targets targets})))})

;; ---------------------------------------------------------------------------
;; Helpers: open a fresh :memory DB per test. Returns a Promise resolving to
;; the conn. Each test gets its own datahike instance so they don't see
;; each other's data.
;; ---------------------------------------------------------------------------

(def ^:private smoke-schema
  [{:db/ident       ::name
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity}
   {:db/ident       ::rank
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/long}
   {:db/ident       ::tags
    :db/cardinality :db.cardinality/many
    :db/valueType   :db.type/keyword}
   {:db/ident       ::source
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/keyword}])

(defn- fresh-conn
  "Open a fresh :memory datahike conn with the test schema transacted.
   Returns a Promise resolving to the conn."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      false}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (db/ensure-provenance! {:seon.db/conn conn})
                     (.then (fn [_] (d/transact! conn smoke-schema)))
                     (.then (fn [_] conn))))))))

(defn- with-conn
  "Run `f` (1-arg conn → value or Promise) against a fresh conn, then
   call `done` from cljs.test/async. A rejection anywhere in the chain
   FAILS the test instead of hanging the runner."
  [f done]
  (-> (fresh-conn)
      (.then f)
      (.catch (fn [e] (is false (str "test chain threw/rejected — " e))))
      (.then (fn [_] (done)))))

(defn- tx!
  "Map-in transact! against an explicit conn — the shape used all over
   these tests. Returns the envelope Promise."
  [conn tx-data]
  (db/transact! {::db/tx-data tx-data ::db/conn conn}))

(deftest datom-count-uses-the-current-database-index
  (async done
    (with-conn
      (fn [conn]
        (-> (tx! conn [{::name "count-probe" ::rank 7}])
            (.then
              (fn [_]
                (is (= (count (d/datoms @conn :eavt))
                       (db/datom-count @conn)))))))
      done)))

(deftest attached?-follows-the-datahike-connection-lifecycle
  (async done
    (let [previous db/*conn*]
      (set! db/*conn* nil)
      (is (false? (db/attached?)))
      (-> (fresh-conn)
          (.then (fn [conn]
                   (set! db/*conn* conn)
                   (is (true? (db/attached?)))
                   (d/release conn)))
          (.then (fn [_]
                   (is (false? (db/attached?)))))
          (.catch (fn [error]
                    (is false (str "attachment lifecycle threw: " error))))
          (.finally (fn []
                      (set! db/*conn* previous)
                      (done)))))))

;; ---------------------------------------------------------------------------
;; Schema gate — the validation plumbing, now public fns in
;; `seon.db.internal` (the ns boundary is the privacy boundary; the V3-A
;; split, 2026-06-10). Local aliases keep test names mapping 1:1 to the
;; behavior the JVM-side mirror also has to preserve.
;; ---------------------------------------------------------------------------

(def ^:private system-attr?       internal/system-attr?)
(def ^:private extract-tx-attrs   internal/extract-tx-attrs)
(def ^:private validate-attrs!    internal/validate-attrs!)
(def ^:private validate-values!   internal/validate-values!)

(deftest system-attr?-recognizes-db-namespace
  (is (system-attr? :db/ident))
  (is (system-attr? :db/valueType))
  (is (not (system-attr? ::name)))
  (is (not (system-attr? :seon.agent/id)))
  (is (not (system-attr? nil)))
  (is (not (system-attr? "db/ident"))))

(deftest extract-tx-attrs-handles-entity-maps
  (is (= #{::name ::rank}
         (extract-tx-attrs [{::name "Alpha" ::rank 1}
                            {::name "Seon" ::rank 2}]))))

(deftest extract-tx-attrs-handles-vector-tuples
  (is (= #{::name}
         (extract-tx-attrs [[:db/add 17 ::name "Alpha"]
                            [:db/retract 17 ::name "old"]]))))

(deftest extract-tx-attrs-mixes-shapes
  (is (= #{::name ::rank}
         (extract-tx-attrs [{::name "Alpha"}
                            [:db/add 1 ::rank 2]]))))

(deftest validate-attrs!-passes-when-all-registered
  (is (nil? (validate-attrs! [::name ::rank]))))

(deftest validate-attrs!-passes-system-attrs-untouched
  ;; :db/* never enters the registry — they configure datahike itself.
  (is (nil? (validate-attrs! [:db/ident :db/valueType :db/cardinality]))))

(deftest validate-attrs!-throws-on-unregistered
  (let [ex (try
             (validate-attrs! [::name :seon.never-registered/whatever])
             nil
             (catch :default e e))]
    (is (some? ex) "should throw")
    (is (= :seon.db/unregistered-attrs (::db/error (ex-data ex))))
    (is (= [:seon.never-registered/whatever]
           (::db/unregistered (ex-data ex))))))

(deftest validate-values!-accepts-good-entity
  (is (nil? (validate-values! [{::name "Alpha" ::rank 1}]))))

(deftest validate-values!-throws-on-bad-value
  (let [ex (try
             (validate-values! [{::name "Alpha" ::rank "not-an-int"}])
             nil
             (catch :default e e))]
    (is (some? ex) "should throw")
    (is (= :seon.db/invalid-value (::db/error (ex-data ex))))
    (is (= ::rank (::db/attr (ex-data ex))))
    (is (= "not-an-int" (::db/actual-value (ex-data ex))))))

(deftest validate-values!-skips-vector-tuples
  ;; Vector tuples carry only an attribute keyword; value-validation happens
  ;; at the entity-map level. This mirrors the JVM gate.
  (is (nil? (validate-values! [[:db/add 17 ::rank "not-an-int"]]))))

;; ---------------------------------------------------------------------------
;; transact! — the validation gate + the success/failure envelope
;; ---------------------------------------------------------------------------

(deftest transact!-round-trips-an-entity
  (async done
    (with-conn
      (fn [conn]
        (.then (tx! conn [{::name "Alpha" ::rank 1}])
               ;; Compact success envelope (#40): no raw report by default.
               (fn [{::db/keys [ok? tx tx-count]}]
                 (is ok?)
                 (is (int? tx) "envelope carries the committed tx id")
                 (is (pos? tx-count) "envelope carries the datom count"))))
      done)))

(deftest transact!-installs-runtime-registered-attr-then-queries-back
  ;; REGRESSION (task #92): the agent-authored-schema → store persistence
  ;; path. The config-init live drive hit a rough edge where an agent did
  ;; `schema/register!` on a NEW attr (→ :ok, in the Malli registry) then
  ;; `transact!`'d a fact with it (→ :ok) but the fact was NOT queryable
  ;; back — the attr never reached the datahike schema. Every OTHER db_test
  ;; pre-installs its attrs via `smoke-schema` at conn creation, so none of
  ;; them exercise `ensure-datahike-attrs!` (the runtime installer) — this
  ;; is the coverage hole that let the drive-found gap slip. Here the attr
  ;; is registered ONLY in seon.schema (never in smoke-schema), so the
  ;; transact MUST trigger the runtime install for the round-trip to work.
  (async done
    (let [attr :my.kb.datastructure.probe92/name]
      ;; Registered in the Malli registry but ABSENT from the conn's
      ;; datahike schema — the exact split-state the drive observed.
      (schema/register! attr :string)
      (with-conn
        (fn [conn]
          (is (not (contains? (db/installed-schema @conn) attr))
              "precondition: attr not yet installed in the datahike schema")
          (.then (tx! conn [{attr "hash-map"}])
                 (fn [{::db/keys [ok? error]}]
                   (is (true? ok?)
                       (str "runtime-registered attr commits — " (pr-str error)))
                   (is (contains? (db/installed-schema @conn) attr)
                       "transact! installed the attr's datahike schema")
                   (let [rows (db/query {::db/query [:find '?n :where ['?e attr '?n]]
                                         ::db/conn  conn})]
                     (is (= #{["hash-map"]} rows)
                         "the stored datom is queryable back")))))
        done))))

(deftest transact!-returns-envelope-on-unregistered-attr
  ;; ENVELOPE CONTRACT: validation failures NEVER throw into the calling
  ;; agent's eval — they come back as ::db/ok? false with ::db/error
  ;; tagged :user-input.
  (async done
    (with-conn
      (fn [conn]
        (.then (tx! conn [{:seon.nope/x 1}])
               (fn [{::db/keys [ok? error tx-report]}]
                 (is (false? ok?) "validation failure → ok? false envelope")
                 (is (nil? tx-report) "no tx-report on failure")
                 (is (some? error))
                 (is (string? (:seon.error/message error)))
                 (is (= :user-input (:seon.error/kind (:seon.error/data error)))
                     "unregistered attr is a :user-input class error")
                 (is (= :seon.db/unregistered-attrs
                        (:seon.db/error (:seon.error/data error)))))))
      done)))

(deftest transact!-returns-envelope-on-bad-value
  (async done
    (with-conn
      (fn [conn]
        (.then (tx! conn [{::name 42}])
               (fn [{::db/keys [ok? error]}]
                 (is (false? ok?) "string schema, int value → envelope")
                 (is (some? error))
                 (is (= :user-input (:seon.error/kind (:seon.error/data error))))
                 (is (= :seon.db/invalid-value
                        (:seon.db/error (:seon.error/data error))))
                 (is (= ::name (:seon.db/attr (:seon.error/data error)))))))
      done)))

(deftest transact!-returns-envelope-on-bad-invocation-shape
  ;; Pre-validation guard — calling positionally or with bare keys must
  ;; still return an envelope, not crash the eval loop.
  (async done
    (with-conn
      (fn [conn]
        (-> (db/transact! "not-a-map")
            (.then (fn [{::db/keys [ok? error]}]
                     (is (false? ok?))
                     (is (= :user-input (:seon.error/kind (:seon.error/data error))))
                     (is (= :seon.db/invalid-invocation-shape
                            (:seon.db/error (:seon.error/data error))))))
            ;; Missing required key
            (.then (fn [_]
                     (db/transact! {:tx-data [{::name "Bob"}]
                                    ::db/conn conn})))
            (.then (fn [{::db/keys [ok? error]}]
                     (is (false? ok?))
                     (is (= :user-input (:seon.error/kind (:seon.error/data error))))
                     (is (= :seon.db/invalid-invocation-shape
                            (:seon.db/error (:seon.error/data error))))))))
      done)))

(deftest transact!-envelopes-non-sequential-tx-data
  ;; task 9b finding 2 regression. `:seon.db/tx-data` MUST be a sequential
  ;; collection. Strings, JS objects, numbers, nil — non-sequential values
  ;; used to slip past `assert-invocation-shape!` and fail deep inside
  ;; `extract-tx-attrs`, getting misclassified as `:core-bug`. The
  ;; sequential? check in the shape guard catches them at the boundary
  ;; and tags `:user-input`.
  (async done
    (with-conn
      (fn [conn]
        (-> (tx! conn "not-a-list")
            (.then (fn [{::db/keys [ok? error]}]
                     (is (false? ok?))
                     (is (= :user-input (:seon.error/kind (:seon.error/data error)))
                         "string tx-data → :user-input, not :core-bug")
                     (is (= :seon.db/invalid-invocation-shape
                            (:seon.db/error (:seon.error/data error))))))
            (.then (fn [_] (tx! conn 42)))
            (.then (fn [{::db/keys [ok? error]}]
                     (is (false? ok?))
                     (is (= :user-input (:seon.error/kind (:seon.error/data error))))))
            (.then (fn [_] (tx! conn nil)))
            (.then (fn [{::db/keys [ok? error]}]
                     (is (false? ok?))
                     (is (= :user-input (:seon.error/kind (:seon.error/data error))))))
            ;; JS exotic object (parses through js-obj literal)
            (.then (fn [_] (tx! conn #js {:foo 1})))
            (.then (fn [{::db/keys [ok? error]}]
                     (is (false? ok?))
                     (is (= :user-input (:seon.error/kind (:seon.error/data error)))
                         "JS object tx-data → :user-input")))))
      done)))

(deftest transact!-pod-stays-alive-after-bad-input
  ;; Regression check: after a failure envelope, the conn is still
  ;; usable for follow-up writes. The core didn't crash.
  (async done
    (with-conn
      (fn [conn]
        (-> (tx! conn [{::name 42}])
            (.then (fn [bad]
                     (is (false? (::db/ok? bad)))
                     (tx! conn [{::name "Alpha"}])))
            (.then (fn [good]
                     (is (true? (::db/ok? good)) "conn still alive after rejection")))))
      done)))

(deftest transact!-allows-system-attrs-for-schema-definitions
  (async done
    (with-conn
      (fn [conn]
        ;; Transacting more schema entities should never trip the gate
        ;; even though :db/* attrs aren't in seon.schema's registry.
        (let [extra-schema [{:db/ident       ::extra
                             :db/cardinality :db.cardinality/one
                             :db/valueType   :db.type/string}]]
          (.then (tx! conn extra-schema)
                 (fn [{::db/keys [ok?]}]
                   (is ok?)))))
      done)))

;; ---------------------------------------------------------------------------
;; transact! — positional arity (T15). Mirrors datahike `(d/transact! conn
;; tx-data)`, conn-first + explicit; seon adds a 3-arity tx-meta convenience.
;; Same envelope contract as the map-in arity — NEVER throws into eval.
;; ---------------------------------------------------------------------------

(deftest transact!-positional-commits-an-entity
  (async done
    (with-conn
      (fn [conn]
        (.then (db/transact! conn [{::name "PosAlpha" ::rank 7}])
               (fn [{::db/keys [ok? tx tx-count]}]
                 (is (true? ok?) "positional (conn tx-data) → ok? true envelope")
                 (is (int? tx) "compact envelope carries the tx id")
                 (is (pos? tx-count) "compact envelope carries the datom count")
                 ;; committed datom is queryable
                 (let [rows (db/query {::db/query '[:find ?n :where [?e ::name ?n]]
                                       ::db/conn  conn})]
                   (is (= #{["PosAlpha"]} rows) "positional write is visible")))))
      done)))

(deftest transact!-positional-and-map-in-agree
  ;; Both front doors funnel to one back door — committing the same shape
  ;; of entity through each yields an equal envelope (modulo tx id) and the
  ;; same queried-back value.
  (async done
    (with-conn
      (fn [conn]
        (-> (tx! conn [{::name "Same" ::rank 1}])
            (.then (fn [via-map]
                     (is (true? (::db/ok? via-map)))
                     ;; retract then re-commit the same entity positionally
                     (db/transact! conn [[:db/retractEntity [::name "Same"]]])))
            (.then (fn [_] (db/transact! conn [{::name "Same" ::rank 1}])))
            (.then (fn [via-pos]
                     (is (true? (::db/ok? via-pos)) "positional commit of same shape")
                     (let [m (db/pull {::db/pull-pattern [::name ::rank]
                                       ::db/ref          [::name "Same"]
                                       ::db/conn         conn})]
                       (is (= "Same" (::name m)))
                       (is (= 1 (::rank m))
                           "queried-back value identical regardless of call shape"))))))
      done)))

(deftest transact!-positional-3-arity-attaches-tx-meta
  ;; (transact! conn tx-data tx-meta) → tx-meta rides into the arg-map under
  ;; :tx-meta and reaches the db. The COMPACT success envelope (#40) omits
  ;; the raw report, so to inspect :tx-meta we ask for it explicitly via
  ;; the map-in shape with `::db/return-report? true` (the 3rd-positional
  ;; arg → `::opts {:tx-meta …}` normalization is unit-pinned separately in
  ;; envelope-test). First prove the positional 3-arity COMMITS, then read
  ;; the echoed :tx-meta through the report-bearing map-in call.
  (async done
    (with-conn
      (fn [conn]
        (-> (db/transact! conn
                          [{::name "Metaed" ::rank 3}]
                          {::source :import})
            (.then (fn [{::db/keys [ok?]}]
                     (is (true? ok?) "positional 3-arity commits")
                     (db/transact! {::db/tx-data        [{::name "Metaed2" ::rank 4}]
                                    ::db/conn           conn
                                    ::db/opts           {:tx-meta {::source :import}}
                                    ::db/return-report? true})))
            (.then (fn [{::db/keys [ok? tx-report]}]
                     (is (true? ok?))
                     (is (= :import (::source (:tx-meta tx-report)))
                         "tx-meta lands in the report under return-report?")))))
      done)))

(deftest transact!-positional-bad-conn-returns-envelope
  ;; ENVELOPE CONTRACT for the positional path: a non-conn first arg must
  ;; come back as a :user-input envelope, NOT a thrown exception.
  (async done
    (with-conn
      (fn [conn]
        (-> (db/transact! "not-a-conn" [{::name "Nope"}])
            (.then (fn [{::db/keys [ok? error]}]
                     (is (false? ok?) "non-conn positional first arg → ok? false")
                     (is (some? error))
                     (is (= :user-input (:seon.error/kind (:seon.error/data error))))
                     (is (= :seon.db/invalid-invocation-shape
                            (:seon.db/error (:seon.error/data error))))
                     ;; non-map tx-meta (3rd arg) → also an envelope, not a throw
                     (db/transact! conn [{::name "Nope"}] "not-a-map")))
            (.then (fn [{::db/keys [ok? error]}]
                     (is (false? ok?) "non-map tx-meta → ok? false")
                     (is (= :user-input (:seon.error/kind (:seon.error/data error))))
                     ;; pod stays alive — a real positional write still works after
                     (db/transact! conn [{::name "AliveAfter"}])))
            (.then (fn [{::db/keys [ok?]}]
                     (is (true? ok?) "conn still usable after bad positional input")))))
      done)))

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(deftest query-finds-transacted-rows
  (async done
    (with-conn
      (fn [conn]
        (.then (tx! conn [{::name "Alpha" ::rank 1}
                          {::name "Seon" ::rank 2}])
               (fn [_]
                 (let [rows (db/query {::db/query '[:find ?n ?r
                                                    :where
                                                    [?e :seon.db-test/name ?n]
                                                    [?e :seon.db-test/rank ?r]]
                                       ::db/conn  conn})]
                   (is (= #{["Alpha" 1] ["Seon" 2]} rows))))))
      done)))

(deftest pull-by-lookup-ref
  (async done
    (with-conn
      (fn [conn]
        (.then (tx! conn [{::name "Alpha" ::rank 1}])
               (fn [_]
                 (let [m (db/pull {::db/pull-pattern [::name ::rank]
                                   ::db/ref          [::name "Alpha"]
                                   ::db/conn         conn})]
                   (is (= "Alpha" (::name m)))
                   (is (= 1 (::rank m)))))))
      done)))

(deftest entity-lookup
  (async done
    (with-conn
      (fn [conn]
        (.then (tx! conn [{::name "Alpha" ::rank 1}])
               (fn [_]
                 (let [e (db/entity {::db/ref [::name "Alpha"] ::db/conn conn})]
                   (is (= "Alpha" (::name e)))))))
      done)))

(deftest store-inventory-returns-a-map-of-attr-groups-with-data
  ;; The discovery surface: WHICH ATTRS HOLD DATA. Returns a map (NOT a
  ;; bare vector) so `(keys inv)` / keyword lookup work — the agent reads
  ;; :seon.db/attr-groups (rows, grouped by attr namespace) + the headline
  ;; counts to decide what to query. Entities have no kind; the namespace
  ;; is a display grouping, not an entity type.
  (async done
    (with-conn
      (fn [conn]
        (.then (tx! conn [{::name "Alpha" ::rank 1}
                          {::name "Seon"  ::rank 2}])
               (fn [_]
                 (let [inv (db/store-inventory {::db/conn conn})
                       row (->> (:seon.db/attr-groups inv)
                                (filter (fn [r] (= :seon.db-test
                                                   (:seon.db/attr-ns r))))
                                first)]
                   ;; map-out: keyword access works (old vector threw on keys)
                   (is (map? inv))
                   (is (vector? (:seon.db/attr-groups inv)))
                   (is (every? keyword? (keys inv)))
                   ;; the user-domain namespace appears with its attrs + counts
                   (is (some? row) "the :seon.db-test namespace is inventoried")
                   (is (= 2 (get-in row [:seon.db/attrs :seon.db-test/name])))
                   (is (= 2 (get-in row [:seon.db/attrs :seon.db-test/rank])))
                   ;; headline counts are consistent with the rows
                   (is (= (count (:seon.db/attr-groups inv))
                          (:seon.db/attr-ns-count inv)))
                   (is (pos? (:seon.db/attr-count inv)))
                   (is (pos? (:seon.db/datom-count inv)))))))
      done)))

(deftest query-accepts-explicit-db
  ;; Caller can pass a frozen ::db/db value (e.g. :db-after from a tx-report)
  ;; instead of going through @conn — useful in listener handlers.
  (async done
    (with-conn
      (fn [conn]
        ;; return-report? to read the raw report's :db-after — the
        ;; compact success envelope omits it (#40).
        (.then (db/transact! {::db/tx-data [{::name "Alpha"}]
                              ::db/conn conn
                              ::db/return-report? true})
               (fn [{::db/keys [tx-report]}]
                 (let [db-after (:db-after tx-report)]
                   (is (= #{["Alpha"]}
                          (db/query {::db/query '[:find ?n
                                                  :where [_ :seon.db-test/name ?n]]
                                     ::db/db    db-after})))))))
      done)))

;; ---------------------------------------------------------------------------
;; Positional read arities (T15) — every read op gains a datahike-shaped
;; positional form ALONGSIDE its map-in arity. The positional db/conn slot
;; is REQUIRED and explicit (no ambient *conn*). Dispatch is by arity:
;; 1 arg = map-in request; 2+/3+ args = positional. These tests prove both
;; shapes work, agree, and that a bad positional slot is rejected with a
;; named-slot Malli error (sync reads are instrumented — see the :once
;; fixture, which installs the same instrumentation the pod boots with).
;; ---------------------------------------------------------------------------

(deftest query-positional-mirrors-datahike
  ;; (db/query q db & inputs) — query FIRST, db binds $, agrees with map-in.
  (async done
    (with-conn
      (fn [conn]
        (.then (tx! conn [{::name "Alpha" ::rank 1}
                          {::name "Seon" ::rank 2}])
               (fn [_]
                 (let [q       '[:find ?n ?r
                                 :where
                                 [?e :seon.db-test/name ?n]
                                 [?e :seon.db-test/rank ?r]]
                       map-in  (db/query {::db/query q ::db/conn conn})
                       pos     (db/query q @conn)]
                   (is (= #{["Alpha" 1] ["Seon" 2]} pos) "positional db binds $")
                   (is (= map-in pos) "positional agrees with map-in"))
                 (testing "extra :in input binds positionally after db (3+ arity)"
                   (let [q '[:find ?n :in $ ?target
                             :where [?e :seon.db-test/name ?n] [(= ?n ?target)]]]
                     (is (= #{["Seon"]} (db/query q @conn "Seon")))
                     (is (= (db/query {::db/query q ::db/args ["Seon"] ::db/conn conn})
                            (db/query q @conn "Seon")))))
                 (testing "a positional query MAP routes positional (not map-in)"
                   ;; '{:find …} is map? but lacks ::db/query, so 2-arg => positional
                   (is (= #{["Alpha" 1] ["Seon" 2]}
                          (db/query '{:find [?n ?r]
                                      :where [[?e :seon.db-test/name ?n]
                                              [?e :seon.db-test/rank ?r]]}
                                    @conn)))))))
      done)))

(deftest query-positional-db-omitted-auto-injects
  ;; NEW contract: the positional db slot is OPTIONAL. When the 2nd arg is
  ;; NOT a db value (per internal/db-value?), the db auto-injects from
  ;; *conn* and that arg is the first :in input — the read-side sibling of
  ;; transact!'s auto-conn form. (Replaces the old "non-db slot-1 throws at
  ;; ::db/db" test, which encoded the pre-auto-inject contract.)
  (async done
    (with-conn
      (fn [conn]
        ;; db/*conn* MUST be bound INSIDE the .then callback: a dynamic
        ;; `binding` frame does not survive the async hop — it has already
        ;; unwound by the time the callback fires. The live pod set!s the
        ;; ROOT *conn* at boot (which is why auto-inject works there); the
        ;; test re-binds around the assertions to reproduce that ambient conn.
        (.then (tx! conn [{::name "Alpha" ::rank 1}
                          {::name "Seon" ::rank 2}])
               (fn [_]
                 (binding [db/*conn* conn]
                   (testing "db OMITTED entirely → auto-inject, no inputs"
                     (let [q '[:find ?n :where [?e :seon.db-test/name ?n]]]
                       (is (= #{["Alpha"] ["Seon"]} (db/query q))
                           "(db/query q) auto-injects db from *conn*")
                       (is (= (db/query q @conn) (db/query q))
                           "auto-inject agrees with explicit-db form")))
                   (testing "db OMITTED, trailing arg is the first :in input"
                     (let [q '[:find ?n :in $ ?target
                               :where [?e :seon.db-test/name ?n] [(= ?n ?target)]]]
                       (is (= #{["Seon"]} (db/query q "Seon"))
                           "non-db 2nd arg binds to :in, db auto-injects")
                       (is (= (db/query q @conn "Seon") (db/query q "Seon"))
                           "auto-inject + input agrees with explicit-db form")))
                   (testing "raw map-form query (no ::db/query) auto-injects"
                     (is (= #{["Alpha"] ["Seon"]}
                            (db/query '{:find [?n]
                                        :where [[?e :seon.db-test/name ?n]]}))
                         "map-form query is positional, not a request map"))
                   (testing "malformed request maps fail instead of returning an empty answer"
                     (try
                       (db/query {:query '[:find ?n
                                           :where [?e :seon.db-test/name ?n]]})
                       (is false "a bare :query key must throw")
                       (catch :default error
                         (is (= :user-input
                                (:seon.error/kind (ex-data error))))
                         (is (= :seon.db/invalid-query-request
                                (::db/error (ex-data error))))
                         (is (re-find #":seon.db/query" (ex-message error))
                             "the error names the fully qualified request key"))))))))
      done)))

(deftest pull-positional-mirrors-datahike
  ;; (db/pull db selector eid) — DB-first, agrees with map-in.
  (async done
    (with-conn
      (fn [conn]
        (.then (tx! conn [{::name "Alpha" ::rank 1}])
               (fn [_]
                 (let [sel    [::name ::rank]
                       eid    [::name "Alpha"]
                       map-in (db/pull {::db/pull-pattern sel ::db/ref eid ::db/conn conn})
                       pos    (db/pull @conn sel eid)]
                   (is (= "Alpha" (::name pos)))
                   (is (= 1 (::rank pos)))
                   (is (= map-in pos) "positional agrees with map-in")))))
      done)))

(deftest pull-positional-bad-selector-named-error
  ;; Wrong slot-1 (selector not a vector) → invalid-input at ::selector.
  ;; Transact first (same proven pattern as pull-positional-mirrors) so we
  ;; assert against a live db value; the bad slot is the SELECTOR, not the db.
  (async done
    (with-conn
      (fn [conn]
        (.then (tx! conn [{::name "Alpha"}])
               (fn [_]
                 (let [ex (try (db/pull @conn :not-a-vector [::name "Alpha"]) nil
                               (catch :default e e))]
                   (is (some? ex) "bad selector must throw (instrumented)")
                   (is (= [::db/selector]
                          (:seon.error.malli/explain-path (ex-data ex))))))))
      done)))

(deftest entity-positional-mirrors-datahike
  ;; (db/entity db eid) — DB-first, agrees with map-in.
  (async done
    (with-conn
      (fn [conn]
        (.then (tx! conn [{::name "Alpha" ::rank 1}])
               (fn [_]
                 (let [eid    [::name "Alpha"]
                       map-in (db/entity {::db/ref eid ::db/conn conn})
                       pos    (db/entity @conn eid)]
                   (is (= "Alpha" (::name pos)))
                   (is (= (:db/id map-in) (:db/id pos)) "same entity both shapes")))))
      done)))

(deftest entity-positional-bad-db-slot-named-error
  ;; Wrong slot-0 (db not a db value) → invalid-input at ::db.
  (async done
    (with-conn
      (fn [_conn]
        (let [ex (try (db/entity :not-a-db 1) nil
                      (catch :default e e))]
          (is (some? ex) "bad positional db must throw (instrumented)")
          (is (= [::db/db] (:seon.error.malli/explain-path (ex-data ex))))))
      done)))

;; ---------------------------------------------------------------------------
;; Temporal — as-of reads against a wrapper db value (AsOfDB). Regression:
;; datahike's CLJS wrapper dbs overrode ILookup to THROW, so the query
;; planner's `(:eavt op-db)` fast-path probe blew up ("-lookup is not
;; supported on AsOfDB") for any query that reached it — aggregates and
;; multi-clause ref-joins in particular — which is what the debug view's
;; time-travel render issues. The fork now returns field-or-nil from -lookup
;; (JVM defrecord parity: a wrapper has no :eavt field ⇒ nil ⇒ the planner
;; routes it through the temporal/search-context path). Assert BOTH no-throw
;; AND the correct as-of value (the t1 frame, not HEAD).
;; ---------------------------------------------------------------------------

(def ^:private history-schema
  (conj smoke-schema
        {:db/ident       ::owner
         :db/cardinality :db.cardinality/one
         :db/valueType   :db.type/ref}))

(defn- fresh-history-conn
  "Like [[fresh-conn]] but `:keep-history? true` (as-of needs history) and
   with a ref attr (`::owner`) so the ref-join shape can be exercised."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (db/ensure-provenance! {:seon.db/conn conn})
                     (.then (fn [_] (d/transact! conn history-schema)))
                     (.then (fn [_] conn))))))))

(deftest as-of-entity+aggregate-see-the-past-frame
  ;; p starts at rank 1 (t1), changes to rank 2 (t2). An as-of-t1 db value must
  ;; read 1 via entity AND via an aggregate/ref-join query (the shape that threw
  ;; -lookup), while HEAD reads 2.
  (async done
    (-> (fresh-history-conn)
        (.then
          (fn [conn]
            (.then
              ;; t1: person p rank 1, group g owns p
              (db/transact! {::db/tx-data [{:db/id "pp" ::name "p" ::rank 1}
                                           {::name "g" ::owner "pp"}]
                             ::db/conn conn})
              (fn [r1]
                (let [t1 (::db/tx r1)]
                  (.then
                    ;; t2: p rank -> 2 (upsert by identity ::name)
                    (db/transact! {::db/tx-data [{::name "p" ::rank 2}] ::db/conn conn})
                    (fn [_]
                      (let [head  @conn
                            asof1 (db/as-of head t1)]
                        ;; entity by lookup-ref on the as-of value sees t1
                        (is (= 1 (::rank (db/entity {::db/db asof1 ::db/ref [::name "p"]})))
                            "as-of entity reads the t1 frame")
                        (is (= 2 (::rank (db/entity {::db/db head ::db/ref [::name "p"]})))
                            "HEAD entity reads the latest frame")
                        ;; aggregate over a ref-join — the exact shape that threw
                        ;; "-lookup is not supported on AsOfDB" before the fork fix
                        (is (= 1 (db/query {::db/db    asof1
                                            ::db/query '[:find (count ?m) . :in $ ?gn
                                                         :where [?g ::name ?gn] [?g ::owner ?m]]
                                            ::db/args  ["g"]}))
                            "as-of aggregate ref-join no longer throws")
                        ;; the CHANGED attr, read through the ref-join: t1 = 1
                        (is (= 1 (db/query {::db/db    asof1
                                            ::db/query '[:find ?r . :in $ ?gn
                                                         :where [?g ::name ?gn] [?g ::owner ?m] [?m ::rank ?r]]
                                            ::db/args  ["g"]}))
                            "as-of ref-join reads the t1 frame")
                        (is (= 2 (db/query {::db/db    head
                                            ::db/query '[:find ?r . :in $ ?gn
                                                         :where [?g ::name ?gn] [?g ::owner ?m] [?m ::rank ?r]]
                                            ::db/args  ["g"]}))
                            "HEAD ref-join reads the latest frame")))))))))
        (.catch (fn [e] (is false (str "as-of test chain threw/rejected — " e))))
        (.then (fn [_] (done))))))

;; ---------------------------------------------------------------------------
;; Listener — handler input shape, multi-key independence, replacement
;; semantics, unlisten
;; ---------------------------------------------------------------------------

(deftest listen!-handler-receives-rich-input
  (async done
    (with-conn
      (fn [conn]
        (let [captured (atom nil)]
          (db/listen! {::db/key     ::capture
                       ::db/handler (fn [input] (reset! captured input))
                       ::db/conn    conn})
          (.then (tx! conn [{::name "Alpha" ::rank 1}])
                 (fn [_]
                   (let [input @captured]
                     (is (some? input))
                     (testing "all spec'd keys present + fully namespaced"
                       (is (some? (::db/tx-report input)))
                       (is (some? (::db/db input)))
                       (is (some? (::db/db-before input)))
                       (is (vector? (::db/datoms input)))
                       (is (map? (::db/attr-index input))))
                     (testing "decoded datoms use seon.db-namespaced keys"
                       (let [d (first (::db/datoms input))]
                         (is (every? d [::db/e ::db/a ::db/v ::db/tx ::db/added?]))))
                     (testing "attr-index grouped by attribute (user-domain keys)"
                       (is (contains? (::db/attr-index input) ::name))
                       (is (every? #(= ::name (::db/a %))
                                   (get (::db/attr-index input) ::name))))
                     (testing "::db/db is queryable in-place (no *conn* reach)"
                       (is (= #{["Alpha"]}
                              (d/q '[:find ?n
                                     :where [_ :seon.db-test/name ?n]]
                                   (::db/db input))))))))))
      done)))

(deftest listen!-multi-keys-fire-independently
  (async done
    (with-conn
      (fn [conn]
        (let [hits-a (atom 0)
              hits-b (atom 0)]
          (db/listen! {::db/key ::a ::db/conn conn
                       ::db/handler (fn [_] (swap! hits-a inc))})
          (db/listen! {::db/key ::b ::db/conn conn
                       ::db/handler (fn [_] (swap! hits-b inc))})
          (-> (tx! conn [{::name "Alpha"}])
              (.then (fn [_]
                       (is (= 1 @hits-a))
                       (is (= 1 @hits-b))
                       (tx! conn [{::name "Seon"}])))
              (.then (fn [_]
                       (is (= 2 @hits-a))
                       (is (= 2 @hits-b)))))))
      done)))

(deftest listen!-same-key-replaces
  (async done
    (with-conn
      (fn [conn]
        (let [first-hits  (atom 0)
              second-hits (atom 0)]
          (db/listen! {::db/key ::same ::db/conn conn
                       ::db/handler (fn [_] (swap! first-hits inc))})
          (db/listen! {::db/key ::same ::db/conn conn
                       ::db/handler (fn [_] (swap! second-hits inc))})
          (.then (tx! conn [{::name "Alpha"}])
                 (fn [_]
                   (is (zero? @first-hits) "old handler replaced — should not fire")
                   (is (= 1 @second-hits) "new handler fires")))))
      done)))

(deftest listen!-returns-key-for-unlisten
  (async done
    (with-conn
      (fn [conn]
        (let [{::db/keys [key]} (db/listen!
                                  {::db/handler (fn [_])
                                   ::db/conn    conn})]
          (is (some? key) "auto-generated key when not supplied")))
      done)))

(deftest unlisten!-stops-the-callback
  (async done
    (with-conn
      (fn [conn]
        (let [hits (atom 0)]
          (db/listen! {::db/key ::stoppable ::db/conn conn
                       ::db/handler (fn [_] (swap! hits inc))})
          (-> (tx! conn [{::name "Alpha"}])
              (.then (fn [_]
                       (is (= 1 @hits))
                       (db/unlisten! {::db/key ::stoppable ::db/conn conn})
                       (tx! conn [{::name "Seon"}])))
              (.then (fn [_]
                       (is (= 1 @hits) "handler retracted — no further fires"))))))
      done)))

;; ---------------------------------------------------------------------------
;; Property-shaped checks on the validation gate. Inputs are small —
;; generative coverage here is about robustness over the SHAPE of tx-data
;; (mixed entity-maps + vector tuples, varying attr counts), not over
;; specific values.
;; ---------------------------------------------------------------------------

(defn- registered-attrs []
  ;; Just the three we register at fixture time — keeps the property fast
  ;; and deterministic across runs.
  [::name ::rank ::tags])

(defn- random-system-attr []
  (rand-nth [:db/ident :db/valueType :db/cardinality :db/unique :db/index]))

(defn- random-tx-data
  "Generate a small tx-data vector mixing entity-maps and vector tuples
   drawn from the registered attrs + system attrs."
  []
  (let [attrs (registered-attrs)]
    (vec
      (for [_ (range (inc (rand-int 5)))]
        (if (zero? (rand-int 2))
          ;; entity map
          {(rand-nth attrs) "v" (random-system-attr) :placeholder}
          ;; vector tuple
          [:db/add 17 (rand-nth attrs) "v"])))))

(deftest prop-validate-attrs-accepts-registered+system-mix
  ;; For any random tx-data using only registered attrs + system attrs,
  ;; validate-attrs! must accept silently. 50 iterations is plenty for
  ;; the property to fail loudly if a regression sneaks in.
  (dotimes [_ 50]
    (let [tx    (random-tx-data)
          attrs (extract-tx-attrs tx)]
      (is (nil? (validate-attrs! attrs))
          (str "rejected legitimate tx-data: " (pr-str tx))))))

(deftest prop-validate-attrs-rejects-on-any-unregistered
  ;; Inject one unregistered attribute into otherwise-legal tx-data; the
  ;; gate must throw, and the error must name the offender.
  (dotimes [_ 50]
    (let [bad-attr (keyword (str "seon.unknown" (rand-int 1000))
                            (str "x" (rand-int 1000)))
          tx      (conj (random-tx-data) {bad-attr "v"})
          attrs   (extract-tx-attrs tx)
          ex      (try (validate-attrs! attrs) nil
                       (catch :default e e))]
      (is (some? ex)
          (str "should reject — unregistered attr " bad-attr " present"))
      (is (contains? (set (::db/unregistered (ex-data ex))) bad-attr)
          (str "error should name " bad-attr)))))

(deftest prop-system-attr?-handles-arbitrary-keywords
  (dotimes [_ 50]
    (let [k (keyword (str "ns" (rand-int 10)) (str "a" (rand-int 10)))]
      (is (= (= "db" (namespace k)) (system-attr? k))))))
