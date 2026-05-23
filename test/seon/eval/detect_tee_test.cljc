(ns seon.eval.detect-tee-test
  "§11 Risk 2 corpus — defn-name / schema-key / ns-name extraction
   for `seon.eval/eval-batch!`'s detect-and-tee step.

   Owned by MVP track (we decide which shapes the agent needs to be
   able to type and have land in the program graph). Platform-track
   implements the extractors in `seon.code` against this corpus —
   see docs/prds/agent-runtime/v1.md §11 Risk 2.

   ## Contract under test

       (seon.code/extract-defn-name source-string)
         => local-name-string | nil
       (seon.code/extract-schema-key source-string current-ns-sym)
         => fully-qualified-keyword | nil
       (seon.code/extract-ns-name source-string)
         => ns-keyword | nil

   `extract-defn-name` returns just the local symbol-as-string (e.g.
   \"analyze\"). The caller in detect-and-tee concatenates the agent's
   current ns to build the `:seon.fn/sym` value
   (\"seon.trading/analyze\"). This keeps the extractor a pure source-
   string function with no namespace-resolution dependency.

   `extract-schema-key` takes the current ns symbol because `::ticker`
   only resolves with reader context. Pass the ns symbol the form was
   typed in.

   `extract-ns-name` reads its own name — `(ns seon.trading ...)`
   declares `:seon.trading` directly, no resolver needed.

   ## When to extend this corpus

   Add a case whenever the agent will legitimately type a shape we
   want to land in the program graph. If a shape is ambiguous
   (e.g. `(defn)` with no name), prefer returning nil over guessing.
   The detect-and-tee step is `:ok :seon.eval`-safe — a nil from the
   extractor just means \"no program-graph entity this form\", the
   eval still records normally."
  (:require [clojure.test :refer [deftest is testing]]
            ;; Resolved at load time. Until Platform ships seon.code/extract-*,
            ;; this require will fail — the test file is the contract; the
            ;; impl follows. (CLJ-side; CLJS variant arrives with Platform's
            ;; refactor.)
            [seon.code :as code]))

;; ============================================================
;; defn / defn- positive cases — every legal shape we expect the
;; agent to type. All extract the local name string "analyze".
;; ============================================================

(def defn-positive-cases
  [{:source "(defn analyze [x] x)"
    :expected "analyze"
    :note "canonical"}

   {:source "(defn analyze \"docstring\" [x] x)"
    :expected "analyze"
    :note "with docstring"}

   {:source "(defn analyze {:malli/schema [:=> [:cat :int] :int]} [x] x)"
    :expected "analyze"
    :note "with attr-map"}

   {:source "(defn analyze \"docstring\" {:malli/schema [:=> [:cat :int] :int]} [x] x)"
    :expected "analyze"
    :note "with docstring AND attr-map"}

   {:source "(defn analyze\n  ([x] x)\n  ([x y] (+ x y)))"
    :expected "analyze"
    :note "multi-arity"}

   {:source "(defn- internal-helper [x] (* x 2))"
    :expected "internal-helper"
    :note "defn- (private)"}

   {:source "(defn ^:private analyze [x] x)"
    :expected "analyze"
    :note "tag-metadata on name"}

   {:source "(defn ^{:malli/schema [:=> [:cat :int] :int]} analyze [x] x)"
    :expected "analyze"
    :note "map-metadata on name"}

   {:source "(defn ^:async ^:private analyze [x] x)"
    :expected "analyze"
    :note "stacked tag-metadata on name"}

   {:source "(defn ^:async analyze {:doc \"async fn\"} [x] x)"
    :expected "analyze"
    :note "tag-metadata on name + attr-map"}

   {:source "(defn analyze\n  \"docstring\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  x)"
    :expected "analyze"
    :note "with docstring AND attr-map (multi-line whitespace)"}

   {:source "(defn ^:async ^:private analyze\n  \"docstring with embedded ;; pseudo-comment\"\n  [x]\n  x)"
    :expected "analyze"
    :note "stacked metadata + docstring containing ;; chars"}])

;; ============================================================
;; defn negative cases — should return nil (NOT a defn we tee).
;; The eval still records; just no :seon.fn entity lands.
;; ============================================================

(def defn-negative-cases
  [{:source "(def analyze (fn [x] x))"
    :note "def wrapping a fn — looks like a defn but isn't"}

   {:source "(def analyze 42)"
    :note "plain def — definitely not a defn"}

   {:source "(defmacro analyze [x] `(* ~x 2))"
    :note "defmacro — v1 does not tee macros (defer to v2)"}

   {:source "(let [x 1] (defn analyze [_] x))"
    :note "nested defn — top-level head is `let`, not `defn`"}

   {:source "(do (defn analyze [x] x))"
    :note "defn wrapped in do — top-level head is `do`"}

   {:source "(comment (defn analyze [x] x))"
    :note "defn inside comment block"}

   {:source "(+ 1 2)"
    :note "plain expression"}

   {:source "analyze"
    :note "bare symbol"}

   {:source "(defn)"
    :note "malformed defn with no name — ambiguous, return nil"}])

;; ============================================================
;; schema/register! positive cases — extract the fully-qualified
;; keyword. Takes current-ns sym because :: resolves at read time.
;; ============================================================

(def schema-positive-cases
  [{:source "(schema/register! ::ticker :string)"
    :current-ns 'seon.trading
    :expected :seon.trading/ticker
    :note "canonical (auto-resolved ::)"}

   {:source "(schema/register! :seon.trading/ticker :string)"
    :current-ns 'seon.trading
    :expected :seon.trading/ticker
    :note "explicit fully-qualified keyword"}

   {:source "(schema/register! :seon.foo/bar :string)"
    :current-ns 'seon.trading
    :expected :seon.foo/bar
    :note "fully-qualified to a different ns than current-ns"}

   {:source "(seon.schema/register! ::ticker :string)"
    :current-ns 'seon.trading
    :expected :seon.trading/ticker
    :note "full ns alias instead of `schema/`"}

   {:source "(schema/register!\n  ::ticker\n  [:string {:min 1 :max 8}])"
    :current-ns 'seon.trading
    :expected :seon.trading/ticker
    :note "multi-line schema definition"}

   {:source "(schema/register! ::ticker [:and :string [:fn #(> (count %) 0)]])"
    :current-ns 'seon.trading
    :expected :seon.trading/ticker
    :note "complex schema body — extractor only cares about the key"}])

;; ============================================================
;; schema/register! negative cases — return nil.
;; ============================================================

(def schema-negative-cases
  [{:source "(let [k ::ticker] (schema/register! k :string))"
    :current-ns 'seon.trading
    :note "computed key via let-binding — ambiguous, return nil"}

   {:source "(schema/register! (keyword \"seon.trading\" \"ticker\") :string)"
    :current-ns 'seon.trading
    :note "computed key via keyword fn — return nil"}

   {:source "(doseq [k [::a ::b]] (schema/register! k :string))"
    :current-ns 'seon.trading
    :note "loop-wrapped registration — top-level head is doseq, nil"}

   {:source "(schema/register-all! ::a :string ::b :int)"
    :current-ns 'seon.trading
    :note "register-all! is multi-key — v1 extractor doesn't handle it; v2 may"}

   {:source "(defn make-ticker [s] (schema/register! ::ticker s))"
    :current-ns 'seon.trading
    :note "register! nested in defn body — top-level is defn, not schema"}

   {:source "(schema/register!)"
    :current-ns 'seon.trading
    :note "malformed register! with no key"}

   {:source "(+ 1 2)"
    :current-ns 'seon.trading
    :note "unrelated form"}])

;; ============================================================
;; ns positive cases — read the ns form's own declared name.
;; No current-ns parameter needed (ns declares its own name).
;; ============================================================

(def ns-positive-cases
  [{:source "(ns seon.trading)"
    :expected :seon.trading
    :note "canonical bare ns"}

   {:source "(ns seon.trading\n  (:require [seon.db :as db]))"
    :expected :seon.trading
    :note "ns with :require"}

   {:source "(ns seon.trading\n  \"docstring for the ns\"\n  (:require [seon.db :as db]))"
    :expected :seon.trading
    :note "ns with docstring"}

   {:source "(ns ^{:author \"agent\"} seon.trading\n  (:require [seon.db :as db]))"
    :expected :seon.trading
    :note "ns with metadata"}

   {:source "(ns seon.agent.AbCdEfGh1234\n  (:require [seon.db :as db]\n            [seon.schema :as schema]))"
    :expected :seon.agent.AbCdEfGh1234
    :note "agent home ns shape — id-suffixed"}])

;; ============================================================
;; ns negative cases — return nil.
;; ============================================================

(def ns-negative-cases
  [{:source "(in-ns 'seon.trading)"
    :note "in-ns is NOT (ns ...) — v1 detect-and-tee doesn't tee it"}

   {:source "(defn foo [] (ns seon.trading))"
    :note "ns nested in defn body — top-level is defn, nil"}

   {:source "(ns)"
    :note "malformed ns with no name"}

   {:source "(+ 1 2)"
    :note "unrelated form"}])

;; ============================================================
;; Tests — exercise each extractor against its corpus.
;; ============================================================

(deftest extract-defn-name-positive
  (doseq [{:keys [source expected note]} defn-positive-cases]
    (testing (str note " — " (pr-str source))
      (is (= expected (code/extract-defn-name source))))))

(deftest extract-defn-name-negative
  (doseq [{:keys [source note]} defn-negative-cases]
    (testing (str note " — " (pr-str source))
      (is (nil? (code/extract-defn-name source))))))

(deftest extract-schema-key-positive
  (doseq [{:keys [source current-ns expected note]} schema-positive-cases]
    (testing (str note " — " (pr-str source))
      (is (= expected (code/extract-schema-key source current-ns))))))

(deftest extract-schema-key-negative
  (doseq [{:keys [source current-ns note]} schema-negative-cases]
    (testing (str note " — " (pr-str source))
      (is (nil? (code/extract-schema-key source current-ns))))))

(deftest extract-ns-name-positive
  (doseq [{:keys [source expected note]} ns-positive-cases]
    (testing (str note " — " (pr-str source))
      (is (= expected (code/extract-ns-name source))))))

(deftest extract-ns-name-negative
  (doseq [{:keys [source note]} ns-negative-cases]
    (testing (str note " — " (pr-str source))
      (is (nil? (code/extract-ns-name source))))))
