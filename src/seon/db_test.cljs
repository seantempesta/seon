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

(deftest transact!-throws-synchronously-on-unregistered-attr
  (async done
    (with-conn
      (fn [conn]
        (go
          (let [ex (try
                     (db/transact! {::db/tx-data [{:seon.nope/x 1}]
                                    ::db/conn    conn})
                     nil
                     (catch :default e e))]
            (is (some? ex) "should throw before reaching datahike")
            (is (= :seon.db/unregistered-attrs
                   (::db/error (ex-data ex)))))))
      done)))

(deftest transact!-throws-synchronously-on-bad-value
  (async done
    (with-conn
      (fn [conn]
        (go
          (let [ex (try
                     (db/transact! {::db/tx-data [{::name 42}]
                                    ::db/conn    conn})
                     nil
                     (catch :default e e))]
            (is (some? ex) "string schema, int value — must throw")
            (is (= :seon.db/invalid-value (::db/error (ex-data ex))))
            (is (= ::name (::db/attr (ex-data ex)))))))
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
