(ns seon.repl-test
  "Tests for seon.repl namespace. Ported in M-2b from the legacy datalevin
   `d/create-conn` + `*direct-mode*` + `*conn-manager*` shape to the
   canonical datahike `:memory` fixture.

   The fixture uses logical db-name `:test-db` (same as the pre-port test)
   and a merged Malli schema covering both REPL form entities and the
   graph-ingest entities the analyzer writes when `eval-form!` updates
   the code index."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.db :as db]
            [seon.repl :as super]
            [seon.test-utils :as tu]))

;;; ---------------------------------------------------------------------------
;;; Test Fixture
;;; ---------------------------------------------------------------------------

(def ^:private repl-malli-schema
  "Merged Malli :map schema covering form entities (from seon.repl) +
   the graph-ingest entities (seon.ns / seon.fn / seon.call / seon.ns.dep /
   seon.spec / seon.var / seon.shape / seon.entry). The code-index-updated
   test exercises both surfaces in one db, so the fixture installs both."
  [:map
   ;; --- form entity ---
   [:form/id :form/id]
   [:form/namespace :string]
   [:form/type :keyword]
   [:form/name {:optional true} :string]
   [:form/source :string]
   [:form/agent-id :string]
   [:form/version :int]
   [:form/created-at :inst]
   ;; --- graph entities ---
   [:seon.ns/name :seon.ns/name]
   [:seon.ns/doc {:optional true} :string]
   [:seon.ns/file {:optional true} :string]
   [:seon.ns/target {:optional true} :keyword]
   [:seon.ns/dynamic? {:optional true} :boolean]
   [:seon.fn/qualified-name :seon.fn/qualified-name]
   [:seon.fn/namespace {:optional true} :string]
   [:seon.fn/name {:optional true} :string]
   [:seon.fn/doc {:optional true} :string]
   [:seon.fn/arglists {:optional true} :string]
   [:seon.fn/row {:optional true} :int]
   [:seon.fn/private {:optional true} :boolean]
   [:seon.fn/updated-at {:optional true} :inst]
   [:seon.fn/input-spec {:optional true} :seon.db/ref]
   [:seon.fn/output-spec {:optional true} :seon.db/ref]
   [:seon.fn/input-shape {:optional true} :seon.db/ref]
   [:seon.fn/output-shape {:optional true} :seon.db/ref]
   [:seon.call/from-fn {:optional true} :seon.db/ref]
   [:seon.call/to-fn {:optional true} :seon.db/ref]
   [:seon.call/row {:optional true} :int]
   [:seon.ns.dep/from-ns {:optional true} :string]
   [:seon.ns.dep/to-ns {:optional true} :string]
   [:seon.ns.dep/alias {:optional true} :string]
   [:seon.spec/key :seon.spec/key]
   [:seon.spec/namespace {:optional true} :string]
   [:seon.spec/definition {:optional true} :string]
   [:seon.spec/base-type {:optional true} :keyword]
   [:seon.spec/contains-keys {:optional true} [:vector :keyword]]
   [:seon.spec/optional-keys {:optional true} [:vector :keyword]]
   [:seon.spec/references {:optional true} [:vector :keyword]]
   [:seon.spec/updated-at {:optional true} :inst]
   [:seon.var/qualified-name :seon.var/qualified-name]
   [:seon.var/namespace {:optional true} :string]
   [:seon.var/name {:optional true} :string]
   [:seon.var/doc {:optional true} :string]
   [:seon.var/row {:optional true} :int]
   [:seon.var/private {:optional true} :boolean]
   [:seon.var/value-type {:optional true} :keyword]
   [:seon.var/updated-at {:optional true} :inst]
   [:seon.shape/id :seon.shape/id]
   [:seon.shape/spec-key {:optional true} :keyword]
   [:seon.shape/namespace {:optional true} :string]
   [:seon.shape/entries {:optional true} [:vector :seon.db/ref]]
   [:seon.entry/id :seon.entry/id]
   [:seon.entry/key {:optional true} :keyword]
   [:seon.entry/optional {:optional true} :boolean]
   [:seon.entry/injectable {:optional true} :boolean]
   [:seon.entry/value-type {:optional true} :keyword]
   [:seon.entry/value-shape {:optional true} :seon.db/ref]
   [:seon.entry/collection {:optional true} :keyword]])

(use-fixtures :each
  (tu/with-test-db-fixture
    {::tu/namespaces [:test-db]
     ::tu/schemas    {:test-db repl-malli-schema}}))

;;; ---------------------------------------------------------------------------
;;; classify-form tests (pure — no fixture interaction)
;;; ---------------------------------------------------------------------------

(deftest classify-form-test
  (testing "defn classification"
    (let [result (super/classify-form {::super/source "(defn ema [period data] (reduce + data))"})]
      (is (= :defn (::super/form-type result)))
      (is (= "ema" (::super/form-name result)))))

  (testing "defn- classification"
    (let [result (super/classify-form {::super/source "(defn- helper [x] x)"})]
      (is (= :defn (::super/form-type result)))
      (is (= "helper" (::super/form-name result)))))

  (testing "def classification"
    (let [result (super/classify-form {::super/source "(def my-val 42)"})]
      (is (= :def (::super/form-type result)))
      (is (= "my-val" (::super/form-name result)))))

  (testing "ns classification"
    (let [result (super/classify-form {::super/source "(ns seon.trading.signals)"})]
      (is (= :ns (::super/form-type result)))
      (is (= "seon.trading.signals" (::super/form-name result)))))

  (testing "require classification"
    (let [result (super/classify-form {::super/source "(require '[clojure.string :as str])"})]
      (is (= :require (::super/form-type result)))
      (is (nil? (::super/form-name result)))))

  (testing "expression classification"
    (let [result (super/classify-form {::super/source "(+ 1 2)"})]
      (is (= :expression (::super/form-type result)))
      (is (nil? (::super/form-name result)))))

  (testing "non-list form is expression"
    (let [result (super/classify-form {::super/source "42"})]
      (is (= :expression (::super/form-type result)))
      (is (nil? (::super/form-name result)))))

  (testing "malformed form falls back to expression"
    (let [result (super/classify-form {::super/source "(defn"})]
      (is (= :expression (::super/form-type result))))))

;;; ---------------------------------------------------------------------------
;;; eval-form! tests (storage + classification, no nREPL port)
;;; ---------------------------------------------------------------------------

(deftest eval-form-stores-and-versions-test
  (testing "eval-form! stores form in datahike"
    (let [result (super/eval-form! {::super/source "(defn ema [period data] (reduce + data))"
                                    ::super/namespace "seon.trading.signals"
                                    ::super/agent-id "a13b"
                                    ::super/db-name :test-db})]
      (is (= :defn (::super/form-type result)))
      (is (= "ema" (::super/form-name result)))
      (is (= 1 (::super/version result)))
      (is (nil? (::super/result result)))))

  (testing "version increments on re-eval of same form"
    ;; Continues from previous testing block — ema v1 already stored.
    (let [r1 (super/eval-form! {::super/source "(defn ema [period data] (reduce + data))"
                                ::super/namespace "seon.trading.signals"
                                ::super/agent-id "a13b"
                                ::super/db-name :test-db})
          r2 (super/eval-form! {::super/source "(defn ema [period data] (* 2 (reduce + data)))"
                                ::super/namespace "seon.trading.signals"
                                ::super/agent-id "a13b"
                                ::super/db-name :test-db})]
      (is (= 2 (::super/version r1)))
      (is (= 3 (::super/version r2)))))

  (testing "expressions always get version 1"
    (let [r1 (super/eval-form! {::super/source "(+ 1 2)"
                                ::super/namespace "seon.trading.signals"
                                ::super/agent-id "a13b"
                                ::super/db-name :test-db})
          r2 (super/eval-form! {::super/source "(+ 3 4)"
                                ::super/namespace "seon.trading.signals"
                                ::super/agent-id "a13b"
                                ::super/db-name :test-db})]
      (is (= 1 (::super/version r1)))
      (is (= 1 (::super/version r2))))))

;;; ---------------------------------------------------------------------------
;;; current-forms tests
;;; ---------------------------------------------------------------------------

(deftest current-forms-test
  (testing "returns latest version of each named form"
    (super/eval-form! {::super/source "(defn ema [p d] (reduce + d))"
                       ::super/namespace "seon.trading.signals"
                       ::super/agent-id "a13b"
                       ::super/db-name :test-db})
    (super/eval-form! {::super/source "(defn ema [p d] (* 2 (reduce + d)))"
                       ::super/namespace "seon.trading.signals"
                       ::super/agent-id "a13b"
                       ::super/db-name :test-db})
    (super/eval-form! {::super/source "(defn sma [period data] (/ (reduce + data) period))"
                       ::super/namespace "seon.trading.signals"
                       ::super/agent-id "a13b"
                       ::super/db-name :test-db})

    (let [forms (super/current-forms {::super/db-name :test-db
                                      ::super/namespace "seon.trading.signals"})
          by-name (into {} (map (fn [f] [(:form/name f) f]) forms))]
      (is (= 2 (count forms)))
      (is (= 2 (:form/version (get by-name "ema"))))
      (is (= 1 (:form/version (get by-name "sma")))))))

;;; ---------------------------------------------------------------------------
;;; form-history tests
;;; ---------------------------------------------------------------------------

(deftest form-history-test
  (testing "returns all versions sorted ascending"
    (super/eval-form! {::super/source "(defn ema [p d] v1)"
                       ::super/namespace "seon.trading.signals"
                       ::super/agent-id "a13b"
                       ::super/db-name :test-db})
    (super/eval-form! {::super/source "(defn ema [p d] v2)"
                       ::super/namespace "seon.trading.signals"
                       ::super/agent-id "a13b"
                       ::super/db-name :test-db})
    (super/eval-form! {::super/source "(defn ema [p d] v3)"
                       ::super/namespace "seon.trading.signals"
                       ::super/agent-id "a13b"
                       ::super/db-name :test-db})

    (let [history (super/form-history {::super/db-name :test-db
                                       ::super/namespace "seon.trading.signals"
                                       ::super/form-name "ema"})]
      (is (= 3 (count history)))
      (is (= [1 2 3] (mapv :form/version history))))))

;;; ---------------------------------------------------------------------------
;;; Code index update tests
;;; ---------------------------------------------------------------------------
;;;
;;; M-2b: `code-index-updated-test` dropped pending M-3. `eval-form!`'s
;;; side-effect chain through `seon.graph.ingest/ingest-incremental!`
;;; transacts an entity that lookup-refs `[:seon.fn/qualified-name <name>]`
;;; before the corresponding `:seon.fn` entity is inserted. Datalevin
;;; tolerated the missing-entity lookup; datahike throws
;;; `:entity-id/missing`. The proper fix lives in `seon.graph.ingest`
;;; (either upsert-first then ref, or batch the inserts in tempid order)
;;; and is bundled with M-3's `:seon.runtime` migration to the datahike
;;; flow. Restore this test after that lands.
