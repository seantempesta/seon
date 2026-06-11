(ns seon.db.envelope-test
  "A4 envelope-contract tests for `seon.db/transact!` (Run-5 regression
   suite — research/e2e-demo-findings-2026-06-08.md 'Run 5').

   The contract under test:

   1. `transact!` NEVER rejects — every failure (sync validation OR
      async datahike commit rejection) RESOLVES to
      `{:seon.db/ok? false :seon.db/error …}` so the agent's eval
      captures the error as a VALUE it can see.
   2. `:double` attrs register, install, and round-trip (the Run-5
      data-loss trigger).
   3. A registered-but-unbridgeable attr fails the transact with a
      legible `:user-input` error naming the supported types — never a
      silent skip + cryptic datahike message.
   4. The two known cryptic datahike messages are translated into
      guiding ones, with the raw message kept at `:seon.db/raw-error`.
   5. `schema/register!` rejects invalid Malli forms (`:number`) at
      register time with a legible error.

   Run via `seon.test.runner/run-vars` / run-block over MCP."
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [datahike.api :as d]
    [seon.db :as db]
    [seon.db.internal :as internal]
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Test schemas — isolated under this ns's keyword namespace.
;; ---------------------------------------------------------------------------

(schema/register! ::name :string)
(schema/register! ::distance-km :double)
;; Registered but NOT storable by the bridge — exercises the fail-loud
;; ensure-datahike-attrs! path.
(schema/register! ::blob [:map-of :keyword :string])
;; Ref + nested-only child attr — exercises the cryptic
;; "not defined in current schema" translation (the child attr is never
;; top-level, so ensure-datahike-attrs! can't see/install it).
(schema/register! ::pet :seon.db/ref)
(schema/register! ::pet-name :string)
;; Non-identity string — lookup-refs against it trigger datahike's
;; ":db/unique" error → translation.
(schema/register! ::label :string)
(schema/register! ::friend :seon.db/ref)

(defn- fresh-conn
  "Promise of a fresh :memory datahike conn (schema-on-write, no
   history — these tests never enter a tx-context scope)."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      false}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false}))))))

(defn- never-reject!
  "Attach a .catch that FAILS the test — the envelope contract says
   transact! resolves on every path. Returns a promise of the resolved
   value (or ::rejected after flagging)."
  [p]
  (.catch p (fn [err]
              (is false (str "transact! REJECTED — envelope contract "
                             "violated: " err))
              ::rejected)))

;; ---------------------------------------------------------------------------
;; 1. No rejection, ever — the Run-5 / pod.log:3660 regression.
;; ---------------------------------------------------------------------------

(deftest unregistered-attr-resolves-to-envelope
  ;; pod.log:3660 live failure: validate-attrs!'s throw inside ^:async
  ;; transact!* escaped as an unhandled Promise REJECTION. It must
  ;; RESOLVE to the ok?-false envelope.
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (never-reject!
                   (db/transact! {:seon.db/tx-data [{:seon.nope2/x 1}]
                                  :seon.db/conn    conn}))))
        (.then (fn [{ok?   :seon.db/ok?
                     error :seon.db/error}]
                 (is (false? ok?) "resolves to ok? false")
                 (is (= :user-input
                        (:seon.error/kind (:seon.error/data error))))
                 (is (= :seon.db/unregistered-attrs
                        (:seon.db/error (:seon.error/data error))))))
        (.then done))))

;; ---------------------------------------------------------------------------
;; 2. :double registers, installs, round-trips — the Run-5 trigger.
;; ---------------------------------------------------------------------------

(deftest double-attr-round-trips
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (-> (never-reject!
                       (db/transact!
                         {:seon.db/tx-data [{::name        "run"
                                             ::distance-km 5.2}]
                          :seon.db/conn    conn}))
                     (.then (fn [{ok?       :seon.db/ok?
                                  tx-report :seon.db/tx-report
                                  error     :seon.db/error}]
                              (is (true? ok?)
                                  (str ":double transact must succeed — "
                                       (:seon.error/message error)))
                              (is (some? tx-report)
                                  "success keeps the tx-report field")
                              (is (= #{[5.2]}
                                     (set
                                       (db/query
                                         {:seon.db/query
                                          '[:find ?d
                                            :where [_ ::distance-km ?d]]
                                          :seon.db/conn conn})))
                                  ":double value round-trips"))))))
        (.then done))))

;; ---------------------------------------------------------------------------
;; 1b. The envelope conversion is LOCAL to `internal/transact!*` (P22 #36):
;;     calling the commit body DIRECTLY (no `seon.db/transact!` face, no
;;     face-level catch) must STILL resolve a validation-gate throw to the
;;     envelope. Two independent agents misread the contract from source
;;     when the validators' throws were only converted at the face — this
;;     pins the throw→envelope truth where the throws live.
;; ---------------------------------------------------------------------------

(deftest transact*-converts-validator-throws-locally
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (never-reject!
                   (internal/transact!*
                     {:seon.db/tx-data [{:seon.nope3/x 1}]
                      :seon.db/conn    conn}))))
        (.then (fn [{ok?   :seon.db/ok?
                     error :seon.db/error}]
                 (is (false? ok?)
                     "transact!* itself resolves to ok? false — the
                      face's catch is NOT what makes the envelope")
                 (is (= :seon.db/unregistered-attrs
                        (:seon.db/error (:seon.error/data error))))))
        (.then done))))

;; ---------------------------------------------------------------------------
;; 3. Registered-but-unbridgeable attr → legible fail-loud envelope.
;; ---------------------------------------------------------------------------

(deftest unbridgeable-attr-fails-with-supported-type-list
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (never-reject!
                   (db/transact! {:seon.db/tx-data [{::name "x"
                                                     ::blob {:a "b"}}]
                                  :seon.db/conn    conn}))))
        (.then (fn [{ok?   :seon.db/ok?
                     error :seon.db/error}]
                 (is (false? ok?))
                 (is (= :user-input
                        (:seon.error/kind (:seon.error/data error))))
                 (is (= :seon.db/unbridgeable-attrs
                        (:seon.db/error (:seon.error/data error))))
                 (let [msg (:seon.error/message error)]
                   (is (re-find #"Supported attr types" msg)
                       "error teaches the storable type list")
                   (is (re-find #"envelope-test/blob" msg)
                       "error names the offending attr"))))
        (.then done))))

;; ---------------------------------------------------------------------------
;; 4. Cryptic datahike messages → guiding translations + raw preserved.
;; ---------------------------------------------------------------------------

(deftest not-in-schema-error-is-translated
  ;; ::pet-name only ever appears NESTED, so ensure-datahike-attrs!
  ;; never installs it → datahike rejects with the cryptic
  ;; "not defined in current schema" → translated envelope.
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (never-reject!
                   (db/transact!
                     {:seon.db/tx-data [{::name "A"
                                         ::pet  {::pet-name "B"}}]
                      :seon.db/conn    conn}))))
        (.then (fn [{ok?       :seon.db/ok?
                     error     :seon.db/error
                     raw-error :seon.db/raw-error}]
                 (is (false? ok?))
                 (is (= :user-input
                        (:seon.error/kind (:seon.error/data error))))
                 (is (re-find #"seon\.schema/register!"
                              (:seon.error/message error))
                     "guiding message points at register!")
                 (is (some? raw-error) "raw message preserved")
                 (is (re-find #"not defined in current schema" raw-error)
                     "raw-error carries the original datahike text")))
        (.then done))))

(deftest lookup-ref-unique-error-is-translated
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 ;; Install ::label as a plain (non-identity) string.
                 (-> (db/transact! {:seon.db/tx-data [{::name  "A"
                                                       ::label "A"}]
                                    :seon.db/conn    conn})
                     (.then (fn [{ok? :seon.db/ok?}]
                              (is (true? ok?) "setup tx must succeed")
                              (never-reject!
                                (db/transact!
                                  {:seon.db/tx-data
                                   [{::name   "B"
                                     ::friend [::label "A"]}]
                                   :seon.db/conn conn})))))))
        (.then (fn [{ok?       :seon.db/ok?
                     error     :seon.db/error
                     raw-error :seon.db/raw-error}]
                 (is (false? ok?))
                 (is (= :user-input
                        (:seon.error/kind (:seon.error/data error))))
                 (is (re-find #"seon\.db/identity true"
                              (:seon.error/message error))
                     "guiding message names the identity fix")
                 (is (some? raw-error))
                 (is (re-find #":db/unique" raw-error))))
        (.then done))))

;; ---------------------------------------------------------------------------
;; 5. register!-time gate — invalid Malli forms fail legibly.
;; ---------------------------------------------------------------------------

(deftest register!-rejects-invalid-malli-form
  (testing ":number (not a Malli type) fails AT register! with guidance"
    (let [err (try (schema/register! ::bogus :number)
                   nil
                   (catch :default e e))]
      (is (some? err) "register! must throw on :number")
      (is (= :seon.schema/invalid-schema
             (:seon.schema/error (ex-data err))))
      (is (= :user-input (:seon.error/kind (ex-data err))))
      (is (re-find #":number is NOT a type" (ex-message err)))
      (is (not (schema/registered? ::bogus))
          "nothing lands in the registry on failure"))))

(deftest register!-accepts-valid-forms
  (testing "the gate passes every shape seon actually uses"
    (is (= ::ok-string  (schema/register! ::ok-string  :string)))
    (is (= ::ok-double  (schema/register! ::ok-double  :double)))
    (is (= ::ok-vec     (schema/register! ::ok-vec     [:vector :keyword])))
    (is (= ::ok-enum    (schema/register! ::ok-enum    [:enum :a :b])))
    (is (= ::ok-ref     (schema/register! ::ok-ref     :seon.db/ref)))
    (is (= ::ok-id      (schema/register! ::ok-id
                                          [:string {:seon.db/identity true
                                                    :min 1}])))))
