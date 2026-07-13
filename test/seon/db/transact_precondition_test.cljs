(ns seon.db.transact-precondition-test
  "Tests for KI-1 — `seon.db/transact!`'s invocation-shape precondition.

   The contract: `transact!` takes ONE map argument with at minimum
   `:seon.db/tx-data`, OR a bare tx-data vector (`(transact! [{…}])` —
   the <your-entity> taught shape, conn defaulting to `*conn*`), OR the
   datahike-mirroring positional `(transact! conn tx-data [tx-meta])`.
   Unqualified-key maps (`{:tx-data […]}`) and non-conn/non-collection
   first args used to silently destructure to nil and crash deep inside
   datahike with cryptic errors.

   A4 (2026-06-09) changed the failure SURFACE: `transact!` never
   throws and never rejects — every failure RESOLVES to the envelope
   `{:seon.db/ok? false :seon.db/error …}` so an agent's eval captures
   the error as a VALUE. These tests assert the envelope, async-style
   (mirrors test/seon/db/envelope_test.cljs).

   Run via `seon.test.runner/run-vars` over MCP."
  (:require [cljs.test :as t :refer [deftest is testing async]]
            [datahike.api :as d]
            [seon.agent]
            [seon.agent.message]
            [seon.db :as db]
            [seon.schema :as schema]))

(schema/register! :seon.db.precondition/id
                  [:string {:seon.db/identity true}])
(schema/register! :seon.db.precondition/value :string)

(defn- fresh-conn
  []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then
          (fn ^:async prepare [conn]
            ;; transact! carries ambient user/process refs; install their
            ;; lookup identities before exercising the public write path.
            (await (db/ensure-provenance! {:seon.db/conn conn}))
            conn)))))

(defn- envelope-error
  "Call `transact!` with `arg`; deliver the resolved envelope's error
   CODE (lives at [:seon.db/error :seon.error/data :seon.db/error] —
   the error-map shape `seon.error/->map` produces) to `done-fn`.
   Never expects a throw/rejection — A4 contract."
  [arg done-fn]
  (-> (db/transact! arg)
      (.then (fn [r]
               (done-fn (get-in r [:seon.db/error :seon.error/data
                                   :seon.db/error]))))
      (.catch (fn [e] (done-fn [:UNEXPECTED-REJECTION (str e)])))))

(deftest non-map-arg-resolves-invalid-shape
  (testing "positional / non-map first arg resolves to invalid-invocation-shape envelope"
    (async done
      (let [pending (atom 3)
            check   (fn [v]
                      (is (= :seon.db/invalid-invocation-shape v))
                      (when (zero? (swap! pending dec)) (done)))]
        (envelope-error "not a map" check)
        (envelope-error nil check)
        (envelope-error 42 check)))))

(deftest one-arg-tx-data-vector-is-a-valid-shape
  ;; Fix-everything A1 (2026-06-11): `(transact! [{…}])` — the
  ;; <your-entity> taught shape — is a VALID 1-arg tx-data call
  ;; (conn defaults to *conn*). It must NOT fail the invocation-shape
  ;; guard; any failure is downstream (unregistered attr, no conn).
  (async done
    (envelope-error [{:foo "bar"}]
                    (fn [v]
                      (is (not= :seon.db/invalid-invocation-shape v)
                          "1-arg tx-data vector passes the shape guard")
                      (done)))))

(deftest unqualified-tx-data-key-resolves-invalid-shape
  (testing "{:tx-data …} (bare keyword) resolves to invalid-invocation-shape"
    (async done
      (envelope-error {:tx-data []}
                      (fn [v]
                        (is (= :seon.db/invalid-invocation-shape v)
                            "bare :tx-data must fail — keys must be namespaced")
                        (done))))))

(deftest missing-tx-data-resolves-invalid-shape
  (testing "missing tx-data key resolves to invalid-invocation-shape"
    (async done
      (let [pending (atom 2)
            check   (fn [v]
                      (is (= :seon.db/invalid-invocation-shape v))
                      (when (zero? (swap! pending dec)) (done)))]
        (envelope-error {} check)
        (envelope-error {:seon.db/opts {}} check)))))

(deftest error-message-mentions-namespacing-hint
  (testing "the envelope message for bare :tx-data names the qualified key"
    (async done
      (-> (db/transact! {:tx-data []})
          (.then (fn [r]
                   (let [msg (get-in r [:seon.db/error :seon.error/message])]
                     (is (false? (:seon.db/ok? r)))
                     (is (re-find #"seon\.db/tx-data" (str msg))
                         (str "envelope should name the qualified key; got: "
                              (pr-str msg)))
                     (done))))
          (.catch (fn [e]
                    (is false (str "unexpected rejection: " e))
                    (done)))))))

(deftest expected-basis-fences-the-whole-database-head
  (async done
    (-> (fresh-conn)
        (.then
          (fn [conn]
            ;; First use installs the registered attrs before writing data.
            ;; Freeze the basis only after that preparatory schema work.
            (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data
                   [{:seon.db.precondition/id "one"
                     :seon.db.precondition/value "initial"}]})
                (.then
                  (fn [seed]
                    (is (true? (:seon.db/ok? seed)))
                    (let [frozen (db/basis-t @conn)]
                      (-> (db/transact!
                            {:seon.db/conn conn
                             :seon.db/expected-basis-t frozen
                             :seon.db/tx-data
                             [{:seon.db.precondition/id "one"
                               :seon.db.precondition/value "accepted"}]})
                          (.then
                            (fn [accepted]
                              (is (true? (:seon.db/ok? accepted))
                                  "the current basis is accepted")
                              (let [committed (db/basis-t @conn)]
                                (-> (db/transact!
                                      {:seon.db/conn conn
                                       :seon.db/expected-basis-t frozen
                                       :seon.db/tx-data
                                       [{:seon.db.precondition/id "one"
                                         :seon.db.precondition/value
                                         "must-not-land"}]})
                                    (.then
                                      (fn [rejected]
                                        (is (false? (:seon.db/ok? rejected))
                                            "a stale basis resolves to an error value")
                                        (is (= committed (db/basis-t @conn))
                                            "the rejected transaction advances no state")
                                        (is (= "accepted"
                                               (:seon.db.precondition/value
                                                 (db/entity
                                                   @conn
                                                   [:seon.db.precondition/id
                                                    "one"])))
                                            "none of the stale transaction lands"))))))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e]
                  (is false (str "unexpected rejection: " e))
                  (done))))))
