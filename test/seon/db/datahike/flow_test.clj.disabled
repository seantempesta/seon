(ns seon.db.datahike.flow-test
  "End-to-end tests for the datahike flow (Phase 1B).

   Exercises:
   - conn-process :init opens stores + installs schema
   - request! dispatch for :transact! / :q / :pull / :entity / :schema
   - tx-bus subscribe / unsubscribe fan-out
   - halt releases connections cleanly
   - start / halt / start cycle
   - Single-writer guard rejects duplicate store paths

   All tests run against the :memory backend so they're fast and self-
   contained (no tmp dir cleanup required)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.db.datahike.flow :as dh-flow]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Test attrs + Malli schemas
;;; ---------------------------------------------------------------------------
;;; Registered once at load time so the conn-process :init can derive the
;;; datahike schema from them.

(schema/register! :seon.phase1.a/id [:uuid {:seon.db/identity true}])
(schema/register! :seon.phase1.a/name :string)
(schema/register! :seon.phase1.a/count :int)

(schema/register! :seon.phase1.b/id [:uuid {:seon.db/identity true}])
(schema/register! :seon.phase1.b/label :string)

(def ^:private a-schema
  [:map
   [:seon.phase1.a/id :seon.phase1.a/id]
   [:seon.phase1.a/name :seon.phase1.a/name]
   [:seon.phase1.a/count :seon.phase1.a/count]])

(def ^:private b-schema
  [:map
   [:seon.phase1.b/id :seon.phase1.b/id]
   [:seon.phase1.b/label :seon.phase1.b/label]])

;;; ---------------------------------------------------------------------------
;;; Fixture
;;; ---------------------------------------------------------------------------

(def ^:dynamic *flow* nil)

(defn- with-flow [f]
  (let [fs (dh-flow/build-datahike-flow!
            {::dh-flow/namespaces [:seon.phase1.a :seon.phase1.b]
             ::dh-flow/backend :memory
             ::dh-flow/namespace-schemas {:seon.phase1.a a-schema
                                          :seon.phase1.b b-schema}})]
    (try
      (binding [*flow* fs]
        (f))
      (finally
        (dh-flow/stop-datahike-flow! fs)))))

(use-fixtures :each with-flow)

;;; ---------------------------------------------------------------------------
;;; Tests
;;; ---------------------------------------------------------------------------

(deftest flow-builds-with-expected-shape
  (testing "flow state contains flow, pids, chans, flow-id"
    (is (some? (::dh-flow/flow *flow*)))
    (is (= :seon.db.datahike/flow (::dh-flow/flow-id *flow*)))
    (is (contains? (::dh-flow/pids *flow*) :seon.phase1.a))
    (is (contains? (::dh-flow/pids *flow*) :seon.phase1.b))))

(deftest schema-installed-at-init
  (testing ":schema op returns the datahike schema map"
    (let [sch (dh-flow/request!
               {::dh-flow/flow *flow*
                ::dh-flow/db-name :seon.phase1.a
                ::dh-flow/op :schema})]
      (is (map? sch))
      (is (contains? sch :seon.phase1.a/id))
      (is (contains? sch :seon.phase1.a/name))
      (is (= :db.type/uuid (get-in sch [:seon.phase1.a/id :db/valueType]))))))

(deftest transact-and-query
  (testing "transact! returns tx-result; :q finds the data"
    (let [uid (random-uuid)
          tag (str "alice-" (str uid))
          _ (dh-flow/request!
             {::dh-flow/flow *flow*
              ::dh-flow/db-name :seon.phase1.a
              ::dh-flow/op :transact!
              ::dh-flow/args [[{:seon.phase1.a/id uid
                                :seon.phase1.a/name tag
                                :seon.phase1.a/count 3}]]})
          q-result (dh-flow/request!
                    {::dh-flow/flow *flow*
                     ::dh-flow/db-name :seon.phase1.a
                     ::dh-flow/op :q
                     ::dh-flow/args ['[:find ?c
                                       :in $ ?n
                                       :where
                                       [?e :seon.phase1.a/name ?n]
                                       [?e :seon.phase1.a/count ?c]]
                                     tag]})]
      (is (= #{[3]} q-result)))))

(deftest pull-by-identity
  (testing ":pull returns the entity map for a lookup ref"
    (let [uid (random-uuid)
          _ (dh-flow/request!
             {::dh-flow/flow *flow*
              ::dh-flow/db-name :seon.phase1.a
              ::dh-flow/op :transact!
              ::dh-flow/args [[{:seon.phase1.a/id uid
                                :seon.phase1.a/name "bob"
                                :seon.phase1.a/count 7}]]})
          entity (dh-flow/request!
                  {::dh-flow/flow *flow*
                   ::dh-flow/db-name :seon.phase1.a
                   ::dh-flow/op :pull
                   ::dh-flow/args ['[*] [:seon.phase1.a/id uid]]})]
      (is (= "bob" (:seon.phase1.a/name entity)))
      (is (= 7 (:seon.phase1.a/count entity))))))

(defn- wait-for
  "Block until (pred) returns truthy or timeout-ms elapses.
   Returns the truthy value from pred, or throws on timeout."
  [pred timeout-ms label]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if-let [v (pred)]
        v
        (if (> (System/currentTimeMillis) deadline)
          (throw (ex-info (str "wait-for timed out: " label)
                          {:label label :timeout-ms timeout-ms}))
          (do (Thread/sleep 5) (recur)))))))

(deftest tx-bus-subscribe-fires-callback
  (testing "subscribe! receives tx-reports, unsubscribe! stops them"
    (let [received (atom [])
          cb (fn [report] (swap! received conj report))
          _ (dh-flow/subscribe!
             {::dh-flow/flow *flow*
              ::dh-flow/db-name :seon.phase1.a
              ::dh-flow/key ::watch
              ::dh-flow/callback cb})
          uid1 (random-uuid)
          _ (dh-flow/request!
             {::dh-flow/flow *flow*
              ::dh-flow/db-name :seon.phase1.a
              ::dh-flow/op :transact!
              ::dh-flow/args [[{:seon.phase1.a/id uid1
                                :seon.phase1.a/name "cat-subbed"
                                :seon.phase1.a/count 1}]]})
          _ (wait-for #(pos? (count @received)) 2000 "first tx-report")
          got-before (count @received)
          _ (dh-flow/unsubscribe!
             {::dh-flow/flow *flow*
              ::dh-flow/db-name :seon.phase1.a
              ::dh-flow/key ::watch})
          ;; Wait until the unsub message definitely landed -- we issue a
          ;; request! and it will queue after the unsub. The fact that the
          ;; transact below fires synchronously from :reply means the earlier
          ;; unsub has propagated on the same tx-bus lane. Still, a small
          ;; barrier guards against reordering between different input
          ;; channels (sub channel vs tx-report channel).
          _ (Thread/sleep 50)
          uid2 (random-uuid)
          _ (dh-flow/request!
             {::dh-flow/flow *flow*
              ::dh-flow/db-name :seon.phase1.a
              ::dh-flow/op :transact!
              ::dh-flow/args [[{:seon.phase1.a/id uid2
                                :seon.phase1.a/name "dog-unsubbed"
                                :seon.phase1.a/count 2}]]})
          ;; Give any stray tx-report dispatch a chance to arrive before
          ;; counting. 100ms is generous for an in-JVM channel hop.
          _ (Thread/sleep 100)
          got-after (count @received)]
      (is (pos? got-before)
          "subscribed callback fired at least once")
      (is (= got-before got-after)
          "unsubscribed callback stopped firing")
      (let [report (first @received)]
        (is (= :seon.phase1.a
               (:seon.db.datahike.tx-report/db-name report)))
        (is (inst? (:seon.db.datahike.tx-report/at report)))))))

(deftest namespace-isolation
  (testing "data transacted into :a is not visible in :b"
    ;; Use a unique tag so we can assert "contains this tag in :a, does not in :b"
    ;; even when the :memory store retains data across test-fixture cycles.
    (let [uid (random-uuid)
          tag (str "iso-" uid)
          _ (dh-flow/request!
             {::dh-flow/flow *flow*
              ::dh-flow/db-name :seon.phase1.a
              ::dh-flow/op :transact!
              ::dh-flow/args [[{:seon.phase1.a/id uid
                                :seon.phase1.a/name tag
                                :seon.phase1.a/count 99}]]})
          a-has (dh-flow/request!
                 {::dh-flow/flow *flow*
                  ::dh-flow/db-name :seon.phase1.a
                  ::dh-flow/op :q
                  ::dh-flow/args ['[:find ?e :in $ ?tag :where
                                    [?e :seon.phase1.a/name ?tag]]
                                  tag]})
          ;; :b doesn't have :seon.phase1.a/name in its schema; query
          ;; should return empty.
          b-has (dh-flow/request!
                 {::dh-flow/flow *flow*
                  ::dh-flow/db-name :seon.phase1.b
                  ::dh-flow/op :q
                  ::dh-flow/args ['[:find ?e :in $ ?tag :where
                                    [?e :seon.phase1.a/name ?tag]]
                                  tag]})]
      (is (= 1 (count a-has))
          "tag matches exactly one entity in :a")
      (is (empty? b-has)
          "tag matches nothing in :b"))))

(deftest error-propagates-through-reply
  (testing "bad transact data produces an :error reply (not a hang)"
    (is (thrown? Exception
                 (dh-flow/request!
                  {::dh-flow/flow *flow*
                   ::dh-flow/db-name :seon.phase1.a
                   ::dh-flow/op :transact!
                   ;; value with wrong type for a string attr
                   ::dh-flow/args [[{:seon.phase1.a/id (random-uuid)
                                     :seon.phase1.a/name 42}]]
                   ::dh-flow/timeout-ms 2000})))))

(deftest unknown-db-name-rejected
  (testing "request! throws when db-name isn't in the flow"
    (is (thrown-with-msg? Exception #"No conn-process registered"
                          (dh-flow/request!
                           {::dh-flow/flow *flow*
                            ::dh-flow/db-name :seon.phase1.nope
                            ::dh-flow/op :schema})))))

;;; ---------------------------------------------------------------------------
;;; Lifecycle + guard tests (don't use the shared fixture)
;;; ---------------------------------------------------------------------------

(deftest single-writer-guard-rejects-duplicate-paths
  (testing "two namespaces with the same store path fail build"
    ;; :memory stores key on :id, which is derived from db-name. To force a
    ;; collision we register schemas for two distinct keywords that produce
    ;; the same slug. With the new slug we need different namespaces with
    ;; same name part: simulate via explicit store collision. Easiest:
    ;; provide the same db-name twice.
    (is (thrown-with-msg? Exception #"Single-writer guard"
                          (dh-flow/build-datahike-flow!
                           {::dh-flow/namespaces [:seon.phase1.a :seon.phase1.a]
                            ::dh-flow/backend :memory})))))

(deftest halt-and-restart-cycle
  (testing "stop then build a fresh flow works cleanly"
    (let [fs1 (dh-flow/build-datahike-flow!
               {::dh-flow/namespaces [:seon.phase1.a]
                ::dh-flow/backend :memory
                ::dh-flow/namespace-schemas {:seon.phase1.a a-schema}})
          uid (random-uuid)
          tag (str "before-halt-" uid)]
      (try
        (dh-flow/request!
         {::dh-flow/flow fs1
          ::dh-flow/db-name :seon.phase1.a
          ::dh-flow/op :transact!
          ::dh-flow/args [[{:seon.phase1.a/id uid
                            :seon.phase1.a/name tag
                            :seon.phase1.a/count 1}]]})
        (finally
          (dh-flow/stop-datahike-flow! fs1)))
      ;; Memory stores persist per-JVM because they're keyed by stable :id,
      ;; so a fresh build reconnects to the same store.
      (let [fs2 (dh-flow/build-datahike-flow!
                 {::dh-flow/namespaces [:seon.phase1.a]
                  ::dh-flow/backend :memory
                  ::dh-flow/namespace-schemas {:seon.phase1.a a-schema}})]
        (try
          (let [result (dh-flow/request!
                        {::dh-flow/flow fs2
                         ::dh-flow/db-name :seon.phase1.a
                         ::dh-flow/op :q
                         ::dh-flow/args ['[:find ?c :in $ ?n :where
                                           [?e :seon.phase1.a/name ?n]
                                           [?e :seon.phase1.a/count ?c]]
                                         tag]})]
            ;; :memory keeps the store across a release+reconnect within the
            ;; JVM, so the previously-transacted entity is still there.
            (is (= #{[1]} result)))
          (finally
            (dh-flow/stop-datahike-flow! fs2)))))))
