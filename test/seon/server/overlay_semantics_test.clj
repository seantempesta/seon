(ns seon.server.overlay-semantics-test
  "JVM-side verification of the wire-protocol semantics the CLJS overlay
   namespace `sidecar-poc.datahike` depends on. These tests do not load
   the CLJS overlay itself — that needs the wasm32-wasip2 build (Phase C).
   They DO assert the protocol contracts the overlay codes against.

   Each test ties an audit-flagged concern to a wire assertion:
     - Reason A (`?->ms` rewrite): query basis-t threading + pure-data preds
     - Reason B (entity-pull eager): `(:foo/bar entity)` traversal still works
     - Reason C (basis-t threading): multi-query snapshot consistency
     - Reason D (unlisten local): subscribe + tx event shape sufficient"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.server.test-util :as tu :refer [*ctx*]]
            [seon.server.client :as client]))

(set! *warn-on-reflection* true)

(use-fixtures :each tu/with-fresh-writer)

(defn- req! [op extra] (tu/req! op extra))

(defn- transact! [wire-id tx-data]
  (req! "transact"
        {:seon.store.wire/id wire-id
         :seon.store.wire/tx-data tx-data}))

(defn- result-of [resp] (:seon.store.wire/result resp))
(defn- meta-of   [resp] (:seon.store.wire/tx-meta resp))

;; ---------- Helpers ----------

(defn- install-msg-schema! []
  (transact!
   "overlay/msg-schema"
   [{:db/ident :msg/at
     :db/valueType :db.type/instant
     :db/cardinality :db.cardinality/one}
    {:db/ident :msg/role
     :db/valueType :db.type/keyword
     :db/cardinality :db.cardinality/one}
    {:db/ident :msg/text
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}]))

;; ---------- Reason C — basis-t threading (warnings composer) ----------

(deftest reason-c-basis-t-threading
  (testing "Audit Reason C: multiple queries against the same basis-t must
            see the same snapshot, even if concurrent commits land between
            the queries. This is the overlay's `(db conn)` -> {:basis-t N}
            value semantics."
    (install-msg-schema!)
    (let [r1 (transact! "overlay/reason-c/a"
                        [{:msg/role :user :msg/text "a" :msg/at #inst "2026-05-01"}])
          bt1 (:seon.store.wire/basis-t r1)
          _   (transact! "overlay/reason-c/b"
                         [{:msg/role :user :msg/text "b" :msg/at #inst "2026-05-02"}])
          _   (transact! "overlay/reason-c/c"
                         [{:msg/role :user :msg/text "c" :msg/at #inst "2026-05-03"}])

          ;; Two queries against bt1 — should both see only one msg.
          q-shape '[:find (count ?e) . :where [?e :msg/text]]
          r-a (req! "q" {:seon.store.wire/query q-shape :seon.store.wire/args [] :seon.store.wire/basis-t bt1})
          r-b (req! "q" {:seon.store.wire/query q-shape :seon.store.wire/args [] :seon.store.wire/basis-t bt1})
          r-now (req! "q" {:seon.store.wire/query q-shape :seon.store.wire/args []})]

      (is (= 1 (result-of r-a))
          "query at bt1 sees one message")
      (is (= 1 (result-of r-b))
          "second query at same basis-t sees the same one")
      (is (= 3 (result-of r-now))
          "query without basis-t sees all three"))))

;; ---------- Reason A — Date comparison without a guest fn ----------

(deftest reason-a-date-pred-without-guest-fn
  (testing "Audit Reason A: the V0 `?->ms` guest fn binding is unnecessary
            because the writer's JVM Clojure runtime can compare java.util.Date
            instances directly with `>` against another #inst. The overlay's
            rewrite computes the cutoff #inst on the guest side and passes it
            as an arg, no fn binding required."
    (install-msg-schema!)
    (transact! "overlay/reason-a/messages"
               [{:msg/role :user :msg/text "older" :msg/at #inst "2026-04-01"}
                {:msg/role :user :msg/text "newer" :msg/at #inst "2026-06-01"}])
    ;; The cutoff #inst can ride either as a native query literal OR as a
    ;; native arg under :seon.store.wire/args — both round-trip as a Date
    ;; through the uniform Transit frame. Use a native arg here.
    (let [q '[:find ?t :in $ ?cutoff
              :where [?e :msg/text ?t] [?e :msg/at ?at]
              [(.compareTo ?at ?cutoff) ?c]
              [(pos? ?c)]]
          r (req! "q" {:seon.store.wire/query q
                       :seon.store.wire/args [#inst "2026-05-01T00:00:00.000-00:00"]})]
      (is (= true (:seon.store.wire/ok r)))
      (let [rows (result-of r)
            texts (set (map first rows))]
        (is (= #{"newer"} texts) "only the 2026-06 message is after 2026-05-01")))))

;; ---------- Reason B — entity-pull shallow access ----------

(deftest reason-b-entity-pull-shallow-access
  (testing "Audit Reason B: V0 sites like `(:seon.agent/sessions a)` do
            shallow access on a `d/entity` return. entity-pull returns an
            eagerly-realized map where reading a top-level attr or a
            component-ref vector works exactly the same way."
    ;; Install a parent/child component schema.
    (transact!
     "overlay/reason-b/schema"
     [{:db/ident :agent/id
       :db/valueType :db.type/string :db/unique :db.unique/identity
       :db/cardinality :db.cardinality/one}
      {:db/ident :agent/sessions
       :db/valueType :db.type/ref :db/isComponent true
       :db/cardinality :db.cardinality/many}
      {:db/ident :session/at
       :db/valueType :db.type/instant
       :db/cardinality :db.cardinality/one}])
    (transact!
     "overlay/reason-b/alpha"
     [{:agent/id "alpha"
       :agent/sessions [{:session/at #inst "2026-05-01"}
                        {:session/at #inst "2026-05-22"}
                        {:session/at #inst "2026-05-10"}]}])
    (let [r (req! "entity-pull" {:seon.store.wire/ref [:agent/id "alpha"]})]
      (is (= true (:seon.store.wire/ok r)))
      (let [m (result-of r)
            sessions (get m :agent/sessions)]
        (is (= "alpha" (get m :agent/id)))
        (is (vector? sessions))
        (is (= 3 (count sessions)))
        ;; Each session map has the :session/at attr realized.
        (is (every? #(contains? % :session/at) sessions))
        ;; Sort host-side, same as agent.cljs:494 pattern.
        (let [sorted (sort-by #(get % :session/at) sessions)
              last-at (get (last sorted) :session/at)]
          (is (some? last-at) "shallow access on the realized component map works"))))))

;; ---------- Reason D — listener tx-data fanout shape ----------

(deftest reason-d-tx-event-handler-shape
  (testing "Audit Reason D: the overlay's listener handler-input shape is
            `{:basis-t :basis-t-before :tx-data :tx-meta :request-id}`. The
            pub event already carries that shape; this test confirms the
            wire delivers everything the overlay needs."
    (install-msg-schema!)
    (let [events (atom [])
          ^java.nio.channels.SocketChannel pub-ch
          (client/start-pub-collector! (:pub-sock *ctx*) events)]
      (try
        (let [rid "overlay/reason-d/event"
              _   (transact! rid
                             [{:msg/role :user :msg/text "hello" :msg/at #inst "2026-05-25"}])
              _   (Thread/sleep 250)
              ev  (first @events)]
          (is (some? ev) "pub event fired")
          ;; Every key the overlay's handler-input map needs:
          (is (integer? (:seon.store.wire/basis-t ev)))
          (is (integer? (:seon.store.wire/basis-t-before ev)))
          (is (vector? (:seon.store.wire/tx-data ev)))
          (is (map? (meta-of ev)))
          (is (= rid (:seon.store.wire/id ev))
              "wire-id round-trips end-to-end (overlay uses it for own-tx dedup)")
          ;; Datom shape matches what the overlay's handler decoder expects.
          (let [d (first (:seon.store.wire/tx-data ev))]
            (is (= 5 (count d)) "datom is [e a v t op]")))
        (finally (.close pub-ch))))))

;; ---------- combined: db-filter as a `d/filter` substitute ----------

(deftest filter-as-filter-substitute
  (testing "The overlay's `d/filter` ships a predicate query (not a fn).
            This test exercises the canonical 'agents whose role matches X'
            pattern."
    (transact!
     "overlay/filter/schema"
     [{:db/ident :person/name :db/valueType :db.type/string
       :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
      {:db/ident :person/role :db/valueType :db.type/keyword
       :db/cardinality :db.cardinality/one}])
    (transact! "overlay/filter/people"
               [{:person/name "alice" :person/role :admin}
                {:person/name "bob"   :person/role :user}
                {:person/name "carol" :person/role :admin}])
    (let [f (req! "db-filter"
                  {:seon.store.wire/pred-query '[:find ?e :where [?e :person/role :admin]]
                   :seon.store.wire/args []})]
      (is (= true (:seon.store.wire/ok f)))
      (is (= 2 (:seon.store.wire/kept f)))
      (let [h (:seon.store.wire/handle f)
            r (req! "q-filtered"
                    {:seon.store.wire/handle h
                     :seon.store.wire/query '[:find ?n :where [?e :person/name ?n]]
                     :seon.store.wire/args []})
            names (set (map first (result-of r)))]
        (is (= #{"alice" "carol"} names) "filtered db only exposes admins")))))
