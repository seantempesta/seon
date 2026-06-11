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
;; Many-card COMPONENT ref + nested-only child attr — the A1 bug-2 pin
;; (nested-only attrs must reach the runtime auto-installer).
(schema/register! ::kids [:vector {:seon.db/component true} :seon.db/ref])
(schema/register! ::kid-name :string)
;; Identity attr — lookup-ref target for the A1 bug-1 pin (the taught
;; `{:seon.db/ref [<identity-attr> v] …}` entity-key transact).
(schema/register! ::code [:string {:seon.db/identity true}])

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

(deftest nested-only-attr-installs-and-commits
  ;; Fix-everything A1 bug 2: ::pet-name only ever appears NESTED under
  ;; the ref attr ::pet. extract-tx-attrs used to collect TOP-LEVEL keys
  ;; only, so the auto-installer never saw ::pet-name and the tx died on
  ;; datahike's cryptic "not defined in current schema". The walker now
  ;; reaches nested entity maps → register!-then-transact of a
  ;; nested-only attr installs and commits.
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (-> (never-reject!
                       (db/transact!
                         {:seon.db/tx-data [{::name "A"
                                             ::pet  {::pet-name "B"}}]
                          :seon.db/conn    conn}))
                     (.then (fn [{ok?   :seon.db/ok?
                                  error :seon.db/error}]
                              (is (true? ok?)
                                  (str "nested-only attr must install + "
                                       "commit — "
                                       (:seon.error/message error)))
                              (is (= #{["B"]}
                                     (set
                                       (db/query
                                         {:seon.db/query
                                          '[:find ?n
                                            :where [_ ::pet-name ?n]]
                                          :seon.db/conn conn})))
                                  "nested value round-trips"))))))
        (.then done))))

(deftest nested-only-attr-under-component-ref-installs-and-commits
  ;; The prompt-pinned variant: a FRESH attr appearing ONLY nested under
  ;; a many-card COMPONENT ref installs and commits.
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (-> (never-reject!
                       (db/transact!
                         {:seon.db/tx-data
                          [{::name "P"
                            ::kids [{::kid-name "K1"} {::kid-name "K2"}]}]
                          :seon.db/conn conn}))
                     (.then (fn [{ok?   :seon.db/ok?
                                  error :seon.db/error}]
                              (is (true? ok?)
                                  (str "component-nested attr must install "
                                       "+ commit — "
                                       (:seon.error/message error)))
                              (is (= #{["K1"] ["K2"]}
                                     (set
                                       (db/query
                                         {:seon.db/query
                                          '[:find ?n
                                            :where [_ ::kid-name ?n]]
                                          :seon.db/conn conn})))))))))
        (.then done))))

(deftest not-in-schema-error-is-translated
  ;; The e2e nested-only path that used to produce this error now
  ;; SUCCEEDS (see nested-only-attr-installs-and-commits), so the
  ;; translation contract is pinned at the unit level: a synthetic
  ;; datahike-shaped throw routes through commit-error-envelope into
  ;; the guiding message with the raw text preserved.
  (let [{ok?       :seon.db/ok?
         error     :seon.db/error
         raw-error :seon.db/raw-error}
        (internal/commit-error-envelope
          (ex-info (str "Bad entity attribute :seon.nope/x at "
                        "{:db/id 1, :seon.nope/x 1}, not defined in "
                        "current schema")
                   {:attribute :seon.nope/x}))]
    (is (false? ok?))
    (is (= :user-input (:seon.error/kind (:seon.error/data error))))
    (is (re-find #"seon\.schema/register!" (:seon.error/message error))
        "guiding message points at register!")
    (is (some? raw-error) "raw message preserved")
    (is (re-find #"not defined in current schema" raw-error)
        "raw-error carries the original datahike text")))

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
;; 4b. A1 bug 1 — the taught `{:seon.db/ref <eid|lookup-ref> …}` entity-key
;;     shorthand (the <your-entity> transact pattern) normalizes to
;;     datahike's `:db/id` slot and NEVER reaches the store as a junk attr.
;; ---------------------------------------------------------------------------

(deftest lookup-ref-entity-key-transacts-without-junk-attr
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (-> (db/transact! {:seon.db/tx-data [{::code "A1"
                                                       ::name "Alpha"}]
                                    :seon.db/conn    conn})
                     (.then (fn [{ok? :seon.db/ok?}]
                              (is (true? ok?) "setup tx must succeed")
                              (never-reject!
                                ;; the taught 1-arg tx-data shape, conn
                                ;; passed explicitly via map-in to stay
                                ;; off *conn*:
                                (db/transact!
                                  {:seon.db/tx-data
                                   [{:seon.db/ref [::code "A1"]
                                     ::label      "tagged"}]
                                   :seon.db/conn conn}))))
                     (.then (fn [{ok?   :seon.db/ok?
                                  error :seon.db/error}]
                              (is (true? ok?)
                                  (str "lookup-ref entity-key transact must "
                                       "succeed — "
                                       (:seon.error/message error)))
                              (let [ent (db/pull {:seon.db/pull-pattern '[*]
                                                  :seon.db/ref [::code "A1"]
                                                  :seon.db/conn conn})]
                                (is (= "tagged" (::label ent))
                                    "value landed on the SAME entity")
                                (is (not (contains? ent :seon.db/ref))
                                    "no junk :seon.db/ref attr on the entity"))
                              (is (not (contains?
                                         (db/installed-schema @conn)
                                         :seon.db/ref))
                                  "junk attr was never installed — no junk
                                   datom can exist"))))))
        (.then done))))

(deftest one-arg-tx-data-shape-normalizes
  ;; `(transact! [{…}])` — the exact <your-entity> header shape — must
  ;; normalize to the map-in request (conn defaulting handled at the
  ;; face). Unit-level: normalize-transact-args output.
  (is (= {:seon.db/tx-data [{::name "x"}]}
         (internal/normalize-transact-args [[{::name "x"}]]))))

(deftest normalize-entity-ref-keys-unit
  (testing "top-level + nested rewrite, eid and lookup-ref forms"
    (is (= [{:db/id [::code "A1"] ::label "t"}]
           (internal/normalize-entity-ref-keys
             [{:seon.db/ref [::code "A1"] ::label "t"}])))
    (is (= [{::name "p" ::kids [{:db/id 42 ::kid-name "k"}]}]
           (internal/normalize-entity-ref-keys
             [{::name "p"
               ::kids [{:seon.db/ref 42 ::kid-name "k"}]}]))))
  (testing "maps under non-ref attrs are opaque values, not entities"
    (let [tx [{::name "x" ::blob {:a "b"}}]]
      (is (= tx (internal/normalize-entity-ref-keys tx)))))
  (testing "invalid ref value throws legible :user-input"
    (let [err (try (internal/normalize-entity-ref-keys
                     [{:seon.db/ref {:not "a-ref"} ::label "t"}])
                   nil
                   (catch :default e e))]
      (is (some? err))
      (is (= :user-input (:seon.error/kind (ex-data err))))
      (is (re-find #"lookup-ref" (ex-message err)))))
  (testing "conflicting :db/id + :seon.db/ref throws :user-input"
    (let [err (try (internal/normalize-entity-ref-keys
                     [{:db/id 1 :seon.db/ref 2 ::label "t"}])
                   nil
                   (catch :default e e))]
      (is (some? err))
      (is (= :user-input (:seon.error/kind (ex-data err))))
      (is (= :seon.db/conflicting-entity-refs
             (:seon.db/error (ex-data err)))))))

(deftest extract-tx-attrs-walks-nested
  (testing "nested entity maps under ref slots contribute their attrs"
    (is (contains? (internal/extract-tx-attrs
                     [{::name "A" ::pet {::pet-name "B"}}])
                   ::pet-name))
    (is (contains? (internal/extract-tx-attrs
                     [{::name "P" ::kids [{::kid-name "K"}]}])
                   ::kid-name))
    (is (contains? (internal/extract-tx-attrs
                     [[:db/add 1 ::pet {::pet-name "B"}]])
                   ::pet-name)))
  (testing "maps under non-ref attrs do NOT leak their keys as attrs"
    (let [attrs (internal/extract-tx-attrs
                  [{::name "x" ::blob {:a "b"}}])]
      (is (not (contains? attrs :a)))
      (is (contains? attrs ::blob)))))

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
