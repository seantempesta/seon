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
    [seon.schema :as schema]
    [seon.test.async :refer [settle!]]))

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
;; Typed scalar — feeds the #46 compact-failure-envelope tests (a value
;; that fails its Malli schema produces the validation-failure envelope).
(schema/register! ::score :int)

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
        (settle! done))))

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
                     (.then (fn [{ok?      :seon.db/ok?
                                  tx       :seon.db/tx
                                  tx-count :seon.db/tx-count
                                  error    :seon.db/error}]
                              (is (true? ok?)
                                  (str ":double transact must succeed — "
                                       (:seon.error/message error)))
                              (is (int? tx)
                                  "compact success envelope carries the tx id")
                              (is (pos? tx-count)
                                  "compact envelope carries the datom count")
                              (is (= #{[5.2]}
                                     (set
                                       (db/query
                                         {:seon.db/query
                                          '[:find ?d
                                            :where [_ ::distance-km ?d]]
                                          :seon.db/conn conn})))
                                  ":double value round-trips"))))))
        (settle! done))))

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
        (settle! done))))

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
        (settle! done))))

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
        (settle! done))))

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
        (settle! done))))

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
        (settle! done))))

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
        (settle! done))))

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
;; 4d. #48 — the prompted fn-registration footgun: an agent writes a
;;     namespace identity as a QUOTED SYMBOL (`{:seon.ns/name 'my.agent.foo}`)
;;     because a namespace IS a symbol everywhere else, but the attr is
;;     `[:keyword {:seon.db/identity true}]`. The validation gate coerces
;;     symbol→keyword for keyword-typed IDENTITY idents at the boundary so
;;     the natural shape persists; the KEYWORD stays the stored canonical.
;; ---------------------------------------------------------------------------

;; A local keyword-typed identity attr — same shape as :seon.ns/name —
;; proves the coercion is DATA-DRIVEN (fires for any kw-typed identity
;; attr, not a hardcoded :seon.ns/name special case).
(schema/register! ::ident-kw [:keyword {:seon.db/identity true}])

(deftest keyword-identity-ident?-scope
  (testing "fires ONLY for registered, non-system, keyword-typed identity attrs"
    (is (true?  (internal/keyword-identity-ident? :seon.ns/name)))
    (is (true?  (internal/keyword-identity-ident? :seon.schema/key)))
    (is (true?  (internal/keyword-identity-ident? ::ident-kw)))
    ;; string-identity, NOT keyword-typed → no coercion
    (is (false? (internal/keyword-identity-ident? :seon.fn/sym)))
    (is (false? (internal/keyword-identity-ident? ::code)))
    ;; keyword but NOT an identity attr → no coercion
    (is (false? (internal/keyword-identity-ident? ::name)))
    ;; system attr / unregistered → no coercion
    (is (false? (internal/keyword-identity-ident? :db/id)))
    (is (false? (internal/keyword-identity-ident? :seon.totally/unregistered)))))

(deftest coerce-identity-symbol-idents-unit
  (testing "entity-map value: symbol → keyword"
    (is (= [{:seon.ns/name :my.agent.foo :seon.ns/source "x"}]
           (internal/coerce-identity-symbol-idents
             [{:seon.ns/name 'my.agent.foo :seon.ns/source "x"}]))))
  (testing "a keyword value passes through UNCHANGED"
    (let [tx [{:seon.ns/name :my.agent.foo :seon.ns/source "x"}]]
      (is (= tx (internal/coerce-identity-symbol-idents tx)))))
  (testing "nested entity under a ref slot is coerced too"
    (is (= [{::name "f" ::pet {:seon.ns/name :my.agent.bar}}]
           (internal/coerce-identity-symbol-idents
             [{::name "f" ::pet {:seon.ns/name 'my.agent.bar}}]))))
  (testing "lookup-ref tuple in a ref-slot value: symbol → keyword"
    (is (= [{::name "g" ::friend [:seon.ns/name :my.agent.baz]}]
           (internal/coerce-identity-symbol-idents
             [{::name "g" ::friend [:seon.ns/name 'my.agent.baz]}]))))
  (testing "lookup-ref symbol in a [:db/retract e a v] e-slot"
    (is (= [[:db/retract [:seon.ns/name :my.agent.q] :seon.ns/source "x"]]
           (internal/coerce-identity-symbol-idents
             [[:db/retract [:seon.ns/name 'my.agent.q] :seon.ns/source "x"]]))))
  (testing "a :db/id lookup-ref symbol is coerced"
    (is (= [{:db/id [:seon.ns/name :my.agent.r] ::label "t"}]
           (internal/coerce-identity-symbol-idents
             [{:db/id [:seon.ns/name 'my.agent.r] ::label "t"}]))))
  (testing "a [:db/add e :seon.ns/name 'sym] v-slot symbol is coerced"
    (is (= [[:db/add 1 :seon.ns/name :my.agent.direct]]
           (internal/coerce-identity-symbol-idents
             [[:db/add 1 :seon.ns/name 'my.agent.direct]]))))
  (testing "a symbol on a NON-keyword-identity attr is left alone"
    ;; ::label is a plain string — a symbol there is a real Malli failure,
    ;; NOT this footgun; the coercion must not touch it.
    (let [tx [{::name "x" ::label 'not-coerced}]]
      (is (= tx (internal/coerce-identity-symbol-idents tx))))))

(deftest ns-name-symbol-coerces-and-round-trips
  ;; The exact prompted shape: `{:seon.ns/name 'my.agent.test-coerce …}`.
  ;; It must SUCCEED and the stored value must be the KEYWORD.
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (-> (never-reject!
                       (db/transact!
                         {:seon.db/tx-data
                          [{:seon.ns/name   'my.agent.test-coerce
                            :seon.ns/source "(ns my.agent.test-coerce)"}]
                          :seon.db/conn conn}))
                     (.then (fn [{ok?   :seon.db/ok?
                                  error :seon.db/error}]
                              (is (true? ok?)
                                  (str "symbol-valued :seon.ns/name must "
                                       "persist (coerced) — "
                                       (:seon.error/message error)))
                              ;; stored canonical value is the KEYWORD
                              (is (= #{[:my.agent.test-coerce]}
                                     (set
                                       (db/query
                                         {:seon.db/query
                                          '[:find ?n
                                            :where [_ :seon.ns/name ?n]]
                                          :seon.db/conn conn})))
                                  "stored :seon.ns/name is the KEYWORD, not the symbol")
                              ;; the keyword is queryable / lookup-resolvable
                              (let [ent (db/pull
                                          {:seon.db/pull-pattern '[*]
                                           :seon.db/ref [:seon.ns/name
                                                         :my.agent.test-coerce]
                                           :seon.db/conn conn})]
                                (is (= :my.agent.test-coerce
                                       (:seon.ns/name ent))
                                    "lookup-ref by the keyword resolves the entity")
                                (is (keyword? (:seon.ns/name ent))
                                    "the stored value is a keyword")))))))
        (settle! done))))

(deftest ns-name-keyword-still-works-unchanged
  ;; The system's own tee always writes the keyword — that path must be
  ;; byte-for-byte unaffected by the coercion.
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (-> (never-reject!
                       (db/transact!
                         {:seon.db/tx-data
                          [{:seon.ns/name   :my.agent.kw-path
                            :seon.ns/source "(ns my.agent.kw-path)"}]
                          :seon.db/conn conn}))
                     (.then (fn [{ok?   :seon.db/ok?
                                  error :seon.db/error}]
                              (is (true? ok?)
                                  (str "keyword-valued :seon.ns/name path "
                                       "unchanged — "
                                       (:seon.error/message error)))
                              (is (= #{[:my.agent.kw-path]}
                                     (set
                                       (db/query
                                         {:seon.db/query
                                          '[:find ?n
                                            :where [_ :seon.ns/name ?n]]
                                          :seon.db/conn conn})))
                                  "keyword stored verbatim"))))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 4c. #46 — a FAILED transact! returns ONE concise envelope. The
;;     downstream report: a trivial type mismatch ballooned to ~3600 chars
;;     because the same Malli explanation echoed across :seon.error/message,
;;     :seon.error/ex-data, :seon.error/data, and :seon.db/malli-explanation,
;;     plus a multi-kb :seon.error/stack and the opaque :seon.error/raw —
;;     tripping the agent-display truncation. [[internal/compact-error-map]]
;;     keeps the guiding message + a SHORT path/expected/got and drops the
;;     redundant copies.
;; ---------------------------------------------------------------------------

(def ^:private display-truncation-limit
  "The agent-display truncation the bloated envelope tripped (#46). A
   failure envelope must serialize well under this."
  1500)

(deftest validation-failure-envelope-is-compact
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (never-reject!
                   (db/transact! {:seon.db/tx-data [{::score "not-an-int"}]
                                  :seon.db/conn    conn}))))
        (.then (fn [{ok?   :seon.db/ok?
                     error :seon.db/error :as env}]
                 (is (false? ok?) "resolves to ok? false")
                 (let [total (count (pr-str env))
                       data  (:seon.error/data error)]
                   ;; bounded — well under the display truncation limit
                   (is (< total display-truncation-limit)
                       (str "envelope must be compact, was " total " chars"))
                   ;; the duplicated/verbose keys are GONE
                   (is (not (contains? error :seon.error/ex-data))
                       ":seon.error/ex-data (a dup of :seon.error/data) dropped")
                   (is (not (contains? error :seon.error/raw))
                       ":seon.error/raw (opaque, re-prints everything) dropped")
                   (is (not (contains? error :seon.error/stack))
                       ":seon.error/stack (noise for :user-input) dropped")
                   (is (not (contains? data :seon.db/malli-explanation))
                       ":seon.db/malli-explanation (dup of the message) dropped")
                   ;; …but enough detail to act survives
                   (is (string? (:seon.error/message error)))
                   (is (re-find #"::score|/score" (:seon.error/message error))
                       "message names the offending attr")
                   (is (= ::score (:seon.db/attr data))
                       "structured data still names WHICH attr failed")
                   (is (= :int (:seon.db/expected-schema data))
                       "structured data still carries the expected schema")
                   (is (= :user-input (:seon.error/kind data))
                       "kind classification preserved"))))
        (settle! done))))

(deftest huge-bad-value-stays-bounded
  ;; A multi-kb bad value must NOT balloon the envelope — :seon.db/actual-value
  ;; is truncated. Pins that the bound holds for arbitrary value size.
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (never-reject!
                   (db/transact!
                     {:seon.db/tx-data [{::score (apply str (repeat 5000 "x"))}]
                      :seon.db/conn    conn}))))
        (.then (fn [{ok?   :seon.db/ok?
                     error :seon.db/error :as env}]
                 (is (false? ok?))
                 (is (< (count (pr-str env)) display-truncation-limit)
                     "a 5000-char bad value still yields a compact envelope")
                 (is (<= (count (str (:seon.db/actual-value
                                       (:seon.error/data error))))
                         110)
                     ":seon.db/actual-value is truncated, not echoed in full")))
        (settle! done))))

(deftest translated-error-stays-compact-and-keeps-raw
  ;; Compaction runs AFTER translation: the guiding message + the raw
  ;; datahike text at :seon.db/raw-error both survive, and the envelope is
  ;; still bounded (no stack / no ex-data dup).
  (let [{ok?       :seon.db/ok?
         error     :seon.db/error
         raw-error :seon.db/raw-error :as env}
        (internal/commit-error-envelope
          (ex-info (str "Bad entity attribute :seon.nope/x at "
                        "{:db/id 1, :seon.nope/x 1}, not defined in "
                        "current schema")
                   {:attribute :seon.nope/x}))]
    (is (false? ok?))
    (is (< (count (pr-str env)) display-truncation-limit)
        "translated failure envelope is compact")
    (is (re-find #"seon\.schema/register!" (:seon.error/message error))
        "guiding message survives compaction")
    (is (some? raw-error) "raw datahike text survives compaction")
    (is (not (contains? error :seon.error/stack)))
    (is (not (contains? error :seon.error/ex-data)))))

;; ---------------------------------------------------------------------------
;; 4e. #16 — a successful `[:db/retract …]` reports an HONEST add/retract
;;     split. The bug: `retracted = tx-count - added` subtraction. A
;;     retraction tx adds tx-meta datoms (:db/txInstant + :seon.db/request-id)
;;     whose ADD count equals the whole tx-data count when the user's
;;     retraction datom comes back over the wire flagged :added true — so
;;     subtraction reported retracted 0 even though a fact was retracted.
;;     The envelope now reads the sole writer's honest counts
;;     (:datoms-added / :datoms-retracted on the report) when present, else
;;     counts the datoms' :added flags directly. NEVER subtraction.
;; ---------------------------------------------------------------------------

(deftest retraction-envelope-counts-are-honest
  (testing "wire path — JVM-supplied :datoms-added/:datoms-retracted win"
    ;; The exact live repro: 3 datoms, ALL flagged :added true (the
    ;; retraction's flag was lost on the wire), but the sole writer
    ;; carried the honest split. Subtraction would give retracted 0.
    (let [report {:tempids {} :db-after {:max-tx 9}
                  :datoms-added 2 :datoms-retracted 1
                  :tx-data [{:a :db/txInstant :added true}
                            {:a :seon.db/request-id :added true}
                            {:a ::name :added true}]}
          env    (internal/transact-success-envelope report false)]
      (is (= 2 (:seon.db/added env)) "added = the writer's count")
      (is (= 1 (:seon.db/retracted env))
          "retracted = the writer's count, NOT (tx-count - added) = 0")
      (is (= 3 (:seon.db/tx-count env)))))
  (testing "local path — no writer counts, count the real :added flags"
    (let [report {:tempids {} :db-after {:max-tx 5}
                  :tx-data [{:a :db/txInstant :added true}
                            {:a ::name :added false}]}
          env    (internal/transact-success-envelope report false)]
      (is (= 1 (:seon.db/added env)))
      (is (= 1 (:seon.db/retracted env))
          "retracted counted off the :added false datom, not subtracted")
      (is (= 2 (:seon.db/tx-count env)))))
  (testing "pure-add tx — every datom added, nothing retracted"
    (let [report {:tempids {} :db-after {:max-tx 3}
                  :tx-data [{:added true} {:added true} {:added true}]}
          env    (internal/transact-success-envelope report false)]
      (is (= 3 (:seon.db/added env)))
      (is (zero? (:seon.db/retracted env))))))

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
