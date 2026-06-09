(ns seon.db-test
  "Tests + worked examples for `seon.db`. These tests double as the
   reference for how an agent should call the surface — read the names
   left-to-right, the bodies show usage, the assertions show expected
   behavior. Property tests cover the schema-gate invariants where
   generative coverage adds something a hand-written example wouldn't.

   Every key in every map handed to or returned by seon.db is fully
   namespaced under `:seon.db/*` — the `::db/keys` destructure idiom +
   `::db/foo` key forms are what you see throughout.

   Run interactively via MCP eval:

     (require 'seon.db-test :reload)
     (cljs.test/run-tests 'seon.db-test)"
  (:require
    [cljs.core.async :as a :refer [chan close! put! take!]]
    [cljs.test :as t :refer [deftest is testing async use-fixtures]]
    [datahike.api :as d]
    [malli.core :as m]
    [seon.db :as db]
    [seon.schema :as schema])
  (:require-macros
    [cljs.core.async :refer [go]]))

;; ---------------------------------------------------------------------------
;; Test schemas. Registered once per test run, isolated under a test namespace
;; so we don't collide with production attribute names.
;; ---------------------------------------------------------------------------

(defn- register-test-schemas! []
  (schema/register! ::name :string)
  (schema/register! ::rank :int)
  (schema/register! ::tags [:vector :keyword]))

(use-fixtures :once
  {:before (fn [] (register-test-schemas!))})

;; ---------------------------------------------------------------------------
;; Helpers: open a fresh :memory DB per test. Returns a channel resolving to
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
    :db/valueType   :db.type/keyword}])

(defn- fresh-conn
  "Open a fresh :memory datahike conn with the test schema transacted.
   Returns a channel resolving to the conn."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      false}]
    (go
      (a/<! (d/create-database cfg))
      (let [conn (a/<! (d/connect cfg {:sync? false}))]
        (a/<! (d/transact! conn smoke-schema))
        conn))))

(defn- with-conn
  "Run `f` against a fresh conn. `f` returns a channel; we wait for it
   and call `done` from cljs.test/async when it closes."
  [f done]
  (go
    (let [conn (a/<! (fresh-conn))]
      (a/<! (f conn))
      (done))))

;; ---------------------------------------------------------------------------
;; Schema gate — the private validation helpers. We exercise them directly
;; via `#'`-deref so test names map 1:1 to the behavior the JVM-side mirror
;; also has to preserve.
;; ---------------------------------------------------------------------------

(def ^:private system-attr?       @#'db/system-attr?)
(def ^:private extract-tx-attrs   @#'db/extract-tx-attrs)
(def ^:private validate-attrs!    @#'db/validate-attrs!)
(def ^:private validate-values!   @#'db/validate-values!)

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
        (go
          (let [{::db/keys [ok? tx-report]}
                (a/<! (db/transact! {::db/tx-data [{::name "Alpha" ::rank 1}]
                                     ::db/conn    conn}))]
            (is ok?)
            (is (some? tx-report))
            (is (pos? (count (:tx-data tx-report)))))))
      done)))

(deftest transact!-returns-envelope-on-unregistered-attr
  ;; ENVELOPE CONTRACT: validation failures NEVER throw into the calling
  ;; agent's eval — they come back as ::db/ok? false with ::db/error
  ;; tagged :user-input.
  (async done
    (with-conn
      (fn [conn]
        (go
          (let [{::db/keys [ok? error tx-report]}
                (a/<! (db/transact! {::db/tx-data [{:seon.nope/x 1}]
                                     ::db/conn    conn}))]
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
        (go
          (let [{::db/keys [ok? error]}
                (a/<! (db/transact! {::db/tx-data [{::name 42}]
                                     ::db/conn    conn}))]
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
        (go
          (let [{::db/keys [ok? error]}
                (a/<! (db/transact! "not-a-map"))]
            (is (false? ok?))
            (is (= :user-input (:seon.error/kind (:seon.error/data error))))
            (is (= :seon.db/invalid-invocation-shape
                   (:seon.db/error (:seon.error/data error)))))
          ;; Missing required key
          (let [{::db/keys [ok? error]}
                (a/<! (db/transact! {:tx-data [{::name "Bob"}]
                                     ::db/conn conn}))]
            (is (false? ok?))
            (is (= :user-input (:seon.error/kind (:seon.error/data error))))
            (is (= :seon.db/invalid-invocation-shape
                   (:seon.db/error (:seon.error/data error)))))))
      done)))

(deftest transact!-envelopes-non-sequential-tx-data
  ;; task 9b finding 2 regression. `:seon.db/tx-data` MUST be a sequential
  ;; collection. Strings, JS objects, numbers, nil — non-sequential values
  ;; used to slip past `assert-invocation-shape!` and fail deep inside
  ;; `extract-tx-attrs`, getting misclassified as `:substrate-bug`. The
  ;; sequential? check in the shape guard catches them at the boundary
  ;; and tags `:user-input`.
  (async done
    (with-conn
      (fn [conn]
        (go
          ;; string
          (let [{::db/keys [ok? error]}
                (a/<! (db/transact! {::db/tx-data "not-a-list"
                                     ::db/conn    conn}))]
            (is (false? ok?))
            (is (= :user-input (:seon.error/kind (:seon.error/data error)))
                "string tx-data → :user-input, not :substrate-bug")
            (is (= :seon.db/invalid-invocation-shape
                   (:seon.db/error (:seon.error/data error)))))
          ;; integer
          (let [{::db/keys [ok? error]}
                (a/<! (db/transact! {::db/tx-data 42 ::db/conn conn}))]
            (is (false? ok?))
            (is (= :user-input (:seon.error/kind (:seon.error/data error)))))
          ;; nil
          (let [{::db/keys [ok? error]}
                (a/<! (db/transact! {::db/tx-data nil ::db/conn conn}))]
            (is (false? ok?))
            (is (= :user-input (:seon.error/kind (:seon.error/data error)))))
          ;; JS exotic object (parses through js-obj literal)
          (let [{::db/keys [ok? error]}
                (a/<! (db/transact! {::db/tx-data #js {:foo 1}
                                     ::db/conn    conn}))]
            (is (false? ok?))
            (is (= :user-input (:seon.error/kind (:seon.error/data error)))
                "JS object tx-data → :user-input"))))
      done)))

(deftest transact!-pod-stays-alive-after-bad-input
  ;; Regression check: after a failure envelope, the conn is still
  ;; usable for follow-up writes. The substrate didn't crash.
  (async done
    (with-conn
      (fn [conn]
        (go
          (let [bad (a/<! (db/transact! {::db/tx-data [{::name 42}]
                                         ::db/conn    conn}))]
            (is (false? (::db/ok? bad))))
          (let [good (a/<! (db/transact! {::db/tx-data [{::name "Alpha"}]
                                          ::db/conn    conn}))]
            (is (true? (::db/ok? good)) "conn still alive after rejection"))))
      done)))

(deftest transact!-allows-system-attrs-for-schema-definitions
  (async done
    (with-conn
      (fn [conn]
        (go
          ;; Transacting more schema entities should never trip the gate
          ;; even though :db/* attrs aren't in seon.schema's registry.
          (let [extra-schema [{:db/ident       ::extra
                               :db/cardinality :db.cardinality/one
                               :db/valueType   :db.type/string}]
                {::db/keys [ok?]} (a/<! (db/transact!
                                          {::db/tx-data extra-schema
                                           ::db/conn    conn}))]
            (is ok?))))
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
        (go
          (let [{::db/keys [ok? tx-report]}
                (a/<! (db/transact! conn [{::name "PosAlpha" ::rank 7}]))]
            (is (true? ok?) "positional (conn tx-data) → ok? true envelope")
            (is (some? tx-report))
            (is (pos? (count (:tx-data tx-report))))
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
        (go
          (let [via-map (a/<! (db/transact! {::db/tx-data [{::name "Same" ::rank 1}]
                                             ::db/conn    conn}))]
            (is (true? (::db/ok? via-map))))
          ;; retract then re-commit the same entity positionally
          (a/<! (db/transact! conn [[:db/retractEntity [::name "Same"]]]))
          (let [via-pos (a/<! (db/transact! conn [{::name "Same" ::rank 1}]))]
            (is (true? (::db/ok? via-pos)) "positional commit of same shape")
            (let [m (db/pull {::db/pull-pattern [::name ::rank]
                              ::db/ref          [::name "Same"]
                              ::db/conn         conn})]
              (is (= "Same" (::name m)))
              (is (= 1 (::rank m))
                  "queried-back value identical regardless of call shape")))))
      done)))

(deftest transact!-positional-3-arity-attaches-tx-meta
  ;; (transact! conn tx-data tx-meta) → tx-meta rides into the arg-map under
  ;; :tx-meta and is reflected in the returned tx-report.
  (async done
    (with-conn
      (fn [conn]
        (go
          (let [{::db/keys [ok? tx-report]}
                (a/<! (db/transact! conn
                                    [{::name "Metaed" ::rank 3}]
                                    {:seon.db-test/source :import}))]
            (is (true? ok?))
            (is (= :import (:seon.db-test/source (:tx-meta tx-report)))
                "tx-meta from the 3rd positional arg lands in the report"))))
      done)))

(deftest transact!-positional-bad-conn-returns-envelope
  ;; ENVELOPE CONTRACT for the positional path: a non-conn first arg must
  ;; come back as a :user-input envelope, NOT a thrown exception.
  (async done
    (with-conn
      (fn [conn]
        (go
          (let [{::db/keys [ok? error]}
                (a/<! (db/transact! "not-a-conn" [{::name "Nope"}]))]
            (is (false? ok?) "non-conn positional first arg → ok? false")
            (is (some? error))
            (is (= :user-input (:seon.error/kind (:seon.error/data error))))
            (is (= :seon.db/invalid-invocation-shape
                   (:seon.db/error (:seon.error/data error)))))
          ;; non-map tx-meta (3rd arg) → also an envelope, not a throw
          (let [{::db/keys [ok? error]}
                (a/<! (db/transact! conn [{::name "Nope"}] "not-a-map"))]
            (is (false? ok?) "non-map tx-meta → ok? false")
            (is (= :user-input (:seon.error/kind (:seon.error/data error)))))
          ;; pod stays alive — a real positional write still works after
          (let [{::db/keys [ok?]}
                (a/<! (db/transact! conn [{::name "AliveAfter"}]))]
            (is (true? ok?) "conn still usable after bad positional input"))))
      done)))

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(deftest query-finds-transacted-rows
  (async done
    (with-conn
      (fn [conn]
        (go
          (a/<! (db/transact! {::db/tx-data [{::name "Alpha" ::rank 1}
                                             {::name "Seon" ::rank 2}]
                               ::db/conn    conn}))
          (let [rows (db/query {::db/query '[:find ?n ?r
                                             :where
                                             [?e :seon.db-test/name ?n]
                                             [?e :seon.db-test/rank ?r]]
                                ::db/conn  conn})]
            (is (= #{["Alpha" 1] ["Seon" 2]} rows)))))
      done)))

(deftest pull-by-lookup-ref
  (async done
    (with-conn
      (fn [conn]
        (go
          (a/<! (db/transact! {::db/tx-data [{::name "Alpha" ::rank 1}]
                               ::db/conn    conn}))
          (let [m (db/pull {::db/pull-pattern [::name ::rank]
                            ::db/ref          [::name "Alpha"]
                            ::db/conn         conn})]
            (is (= "Alpha" (::name m)))
            (is (= 1 (::rank m))))))
      done)))

(deftest entity-lookup
  (async done
    (with-conn
      (fn [conn]
        (go
          (a/<! (db/transact! {::db/tx-data [{::name "Alpha" ::rank 1}]
                               ::db/conn    conn}))
          (let [e (db/entity {::db/ref [::name "Alpha"] ::db/conn conn})]
            (is (= "Alpha" (::name e))))))
      done)))

(deftest query-accepts-explicit-db
  ;; Caller can pass a frozen ::db/db value (e.g. :db-after from a tx-report)
  ;; instead of going through @conn — useful in listener handlers.
  (async done
    (with-conn
      (fn [conn]
        (go
          (let [{::db/keys [tx-report]}
                (a/<! (db/transact! {::db/tx-data [{::name "Alpha"}]
                                     ::db/conn    conn}))
                db-after (:db-after tx-report)]
            (is (= #{["Alpha"]}
                   (db/query {::db/query '[:find ?n
                                           :where [_ :seon.db-test/name ?n]]
                              ::db/db    db-after}))))))
      done)))

;; ---------------------------------------------------------------------------
;; Positional read arities (T15) — every read op gains a datahike-shaped
;; positional form ALONGSIDE its map-in arity. The positional db/conn slot
;; is REQUIRED and explicit (no ambient *conn*). Dispatch is by arity:
;; 1 arg = map-in request; 2+/3+ args = positional. These tests prove both
;; shapes work, agree, and that a bad positional slot is rejected with a
;; named-slot Malli error (sync reads are instrumented).
;; ---------------------------------------------------------------------------

(deftest query-positional-mirrors-datahike
  ;; (db/query q db & inputs) — query FIRST, db binds $, agrees with map-in.
  (async done
    (with-conn
      (fn [conn]
        (go
          (a/<! (db/transact! {::db/tx-data [{::name "Alpha" ::rank 1}
                                             {::name "Seon" ::rank 2}]
                               ::db/conn    conn}))
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
                             @conn))))))
      done)))

(deftest query-positional-bad-db-slot-named-error
  ;; Wrong slot-1 (db not a db value) → instrumented invalid-input at ::db.
  (async done
    (with-conn
      (fn [conn]
        (go
          (let [ex (try (db/query '[:find ?e :where [?e :seon.db-test/name _]]
                                  :not-a-db)
                        nil
                        (catch :default e e))]
            (is (some? ex) "bad positional db must throw (instrumented)")
            (is (= [::db/db] (:seon.error.malli/explain-path (ex-data ex)))
                "named slot ::db/db, not a positional index"))
          ;; 3-arity bad db too
          (let [ex (try (db/query '[:find ?e :in $ ?t :where [?e :seon.db-test/name ?t]]
                                  :not-a-db "x")
                        nil
                        (catch :default e e))]
            (is (= [::db/db] (:seon.error.malli/explain-path (ex-data ex)))))))
      done)))

(deftest pull-positional-mirrors-datahike
  ;; (db/pull db selector eid) — DB-first, agrees with map-in.
  (async done
    (with-conn
      (fn [conn]
        (go
          (a/<! (db/transact! {::db/tx-data [{::name "Alpha" ::rank 1}]
                               ::db/conn    conn}))
          (let [sel    [::name ::rank]
                eid    [::name "Alpha"]
                map-in (db/pull {::db/pull-pattern sel ::db/ref eid ::db/conn conn})
                pos    (db/pull @conn sel eid)]
            (is (= "Alpha" (::name pos)))
            (is (= 1 (::rank pos)))
            (is (= map-in pos) "positional agrees with map-in"))))
      done)))

(deftest pull-positional-bad-selector-named-error
  ;; Wrong slot-1 (selector not a vector) → invalid-input at ::selector.
  ;; Transact first (same proven pattern as pull-positional-mirrors) so we
  ;; assert against a live db value; the bad slot is the SELECTOR, not the db.
  (async done
    (with-conn
      (fn [conn]
        (go
          (a/<! (db/transact! {::db/tx-data [{::name "Alpha"}]
                               ::db/conn    conn}))
          (let [ex (try (db/pull @conn :not-a-vector [::name "Alpha"]) nil
                        (catch :default e e))]
            (is (some? ex) "bad selector must throw (instrumented)")
            (is (= [::db/selector] (:seon.error.malli/explain-path (ex-data ex)))))))
      done)))

(deftest entity-positional-mirrors-datahike
  ;; (db/entity db eid) — DB-first, agrees with map-in.
  (async done
    (with-conn
      (fn [conn]
        (go
          (a/<! (db/transact! {::db/tx-data [{::name "Alpha" ::rank 1}]
                               ::db/conn    conn}))
          (let [eid    [::name "Alpha"]
                map-in (db/entity {::db/ref eid ::db/conn conn})
                pos    (db/entity @conn eid)]
            (is (= "Alpha" (::name pos)))
            (is (= (:db/id map-in) (:db/id pos)) "same entity both shapes"))))
      done)))

(deftest entity-positional-bad-db-slot-named-error
  ;; Wrong slot-0 (db not a db value) → invalid-input at ::db.
  (async done
    (with-conn
      (fn [conn]
        (go
          (let [ex (try (db/entity :not-a-db 1) nil
                        (catch :default e e))]
            (is (some? ex) "bad positional db must throw (instrumented)")
            (is (= [::db/db] (:seon.error.malli/explain-path (ex-data ex)))))))
      done)))

;; ---------------------------------------------------------------------------
;; Listener — handler input shape, multi-key independence, replacement
;; semantics, unlisten
;; ---------------------------------------------------------------------------

(deftest listen!-handler-receives-rich-input
  (async done
    (with-conn
      (fn [conn]
        (go
          (let [captured (atom nil)]
            (db/listen! {::db/key     ::capture
                         ::db/handler (fn [input] (reset! captured input))
                         ::db/conn    conn})
            (a/<! (db/transact! {::db/tx-data [{::name "Alpha" ::rank 1}]
                                 ::db/conn    conn}))
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
                            (::db/db input)))))))))
      done)))

(deftest listen!-multi-keys-fire-independently
  (async done
    (with-conn
      (fn [conn]
        (go
          (let [hits-a (atom 0)
                hits-b (atom 0)]
            (db/listen! {::db/key ::a ::db/conn conn
                         ::db/handler (fn [_] (swap! hits-a inc))})
            (db/listen! {::db/key ::b ::db/conn conn
                         ::db/handler (fn [_] (swap! hits-b inc))})
            (a/<! (db/transact! {::db/tx-data [{::name "Alpha"}]
                                 ::db/conn    conn}))
            (is (= 1 @hits-a))
            (is (= 1 @hits-b))
            (a/<! (db/transact! {::db/tx-data [{::name "Seon"}]
                                 ::db/conn    conn}))
            (is (= 2 @hits-a))
            (is (= 2 @hits-b)))))
      done)))

(deftest listen!-same-key-replaces
  (async done
    (with-conn
      (fn [conn]
        (go
          (let [first-hits  (atom 0)
                second-hits (atom 0)]
            (db/listen! {::db/key ::same ::db/conn conn
                         ::db/handler (fn [_] (swap! first-hits inc))})
            (db/listen! {::db/key ::same ::db/conn conn
                         ::db/handler (fn [_] (swap! second-hits inc))})
            (a/<! (db/transact! {::db/tx-data [{::name "Alpha"}]
                                 ::db/conn    conn}))
            (is (zero? @first-hits) "old handler replaced — should not fire")
            (is (= 1 @second-hits) "new handler fires"))))
      done)))

(deftest listen!-returns-key-for-unlisten
  (async done
    (with-conn
      (fn [conn]
        (go
          (let [{::db/keys [key]} (db/listen!
                                    {::db/handler (fn [_])
                                     ::db/conn    conn})]
            (is (some? key) "auto-generated key when not supplied"))))
      done)))

(deftest unlisten!-stops-the-callback
  (async done
    (with-conn
      (fn [conn]
        (go
          (let [hits (atom 0)]
            (db/listen! {::db/key ::stoppable ::db/conn conn
                         ::db/handler (fn [_] (swap! hits inc))})
            (a/<! (db/transact! {::db/tx-data [{::name "Alpha"}]
                                 ::db/conn    conn}))
            (is (= 1 @hits))
            (db/unlisten! {::db/key ::stoppable ::db/conn conn})
            (a/<! (db/transact! {::db/tx-data [{::name "Seon"}]
                                 ::db/conn    conn}))
            (is (= 1 @hits) "handler retracted — no further fires"))))
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
