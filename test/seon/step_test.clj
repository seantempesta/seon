(ns seon.step-test
  "The off-the-shelf step templates' default proofs and the handoffs they gate
  (`docs/seon/schedule/cards/TASK-CHAIN.md#4-off-the-shelf-templates`)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.contracts :as contracts]
            [seon.db :as db]
            [seon.ref :as ref]
            [seon.test]
            [seon.test.runner :as runner]))

;;; The shared handoff checks: pure functions of the effective document, the database's entities and result values.

(defn- sections
  "Each heading anchor of Markdown `text` (`seon.ref/document`), at any level, with its section's text up to the next heading."
  {:malli/schema [:=> [:cat :string] [:map-of :seon.ref/anchor :string]]}
  [text]
  (let [lines (vec (str/split-lines text))
        headings (:seon.ref/headings (ref/document text))
        ends (concat (map :seon.ref/line (rest headings)) [(inc (count lines))])]
    (into {} (map (fn [{:seon.ref/keys [anchor line]} end] [anchor (str/join "\n" (subvec lines line (dec end)))]) headings ends))))

(defn- document-gaps
  "What keeps the document `intent` from a handoff on `database`: each `required` section anchor without content,
  then each reference of the intent that does not resolve; empty when none."
  {:malli/schema [:=> [:cat :seon.db/database-value :string [:vector :seon.ref/anchor]] [:vector :seon.schema/value]]}
  [database intent required]
  (let [present (sections intent)]
    (into (filterv #(str/blank? (get present % "")) required)
          (filter :seon.ref/problem-kind)
          (ref/references database {:seon.ref/text intent}))))

(defn- unmeasured-claims
  "The deftests `text` cites that do not call `seon.profile/measure` on `database`: a cited deftest is a cost claim."
  {:malli/schema [:=> [:cat :seon.db/database-value :string] [:set :qualified-symbol]]}
  [database text]
  (let [cited (vec (ref/names text))
        tests (db/q '[:find [?s ...] :in $ [?s ...] :where [_ :seon.test/sym ?s]] database cited)
        measured (db/q '[:find [?s ...] :in $ [?s ...] :where [?e :seon.test/sym ?s] [?e :seon.fn/calls seon.profile/measure]]
                       database cited)]
    (set/difference (set tests) (set measured))))

(defn- uncontracted
  "The functions `text` cites on `database` without a complete contract (`seon.contracts/gaps`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :string] [:set :qualified-symbol]]}
  [database text]
  (let [functions (db/q '[:find [?s ...] :in $ [?s ...] :where [_ :seon.fn/sym ?s]] database (vec (ref/names text)))]
    (into #{} (remove #(empty? (contracts/gaps database %))) functions)))

(defn- designed-product?
  "Whether `product` is not empty and each of its tests has a designed red among `results`, by test
  (`seon.test.runner/designed-red?`)."
  {:malli/schema [:=> [:cat :seon.task/product [:map-of :qualified-symbol :map]] :boolean]}
  [product results]
  (boolean (and (seq product) (every? #(some-> (results %) runner/designed-red?) product))))

(defn- judged
  "The task this default deftest judges (`seon.test/task`: the request's `:seon.test/task`, else its one tag): the
  member's database, the task's effective declaration and its obligations, both read on the request's database."
  {:malli/schema [:=> [:cat] [:tuple :seon.db/database-value :seon.task/effective :seon.task/obligations]]}
  []
  (let [{effective :seon.task/effective obligations :seon.task/obligations} (seon.test/task)]
    [(db/db) effective obligations]))

(def ^:private worked-results
  "The worked example's product result: an executed run of the table test with one failing assertion."
  {'seon.fs-test/under-path-tells-what-lies-under-a-root
   {:seon.test/pass-count 4 :seon.test/fail-count 1 :seon.test/error-count 0 :seon.test.failure/reports [{:type :fail}]}})

(deftest ^{:seon/tasks #{'seon.step/survey}} survey-handoff
  (let [[database {intent :seon.task/plan}] (judged)]
    (is (= [] (document-gaps database intent ["problem" "what-exists"])) "Problem and What exists have content and every reference resolves")))

(deftest ^{:seon/tasks #{'seon.step/define}} define-handoff
  (let [[database {intent :seon.task/plan}] (judged)]
    (is (= [] (document-gaps database intent ["problem" "what-exists" "examples" "expected-cost"]))
        "Examples and Expected cost join the earlier sections")
    (is (= #{} (unmeasured-claims database (get (sections intent) "expected-cost"))) "a section citing no deftest makes no claim")
    (is (= '#{seon.fs-test/under-path-tells-what-lies-under-a-root}
           (unmeasured-claims database "`seon.fs-test/under-path-tells-what-lies-under-a-root`"))
        "a cited deftest that measures nothing is an unmet cost claim")
    (is (= #{} (unmeasured-claims database "`seon.task-test/ready-answers-in-under-10-ms-for-fifty-leaves`"))
        "a cited deftest calling seon.profile/measure meets it")))

(deftest ^{:seon/tasks #{'seon.step/design}} design-handoff
  (let [[database {intent :seon.task/plan}] (judged)]
    (is (= [] (document-gaps database intent ["problem" "what-exists" "examples" "expected-cost" "approach"]))
        "Approach joins the earlier sections")
    (is (= #{} (uncontracted database (get (sections intent) "approach"))) "every function Approach cites is contracted")))

(deftest ^{:seon/tasks #{'seon.step/prove}} prove-handoff
  (let [[database {intent :seon.task/plan} {step :seon.task/step}] (judged)
        product (set (keys worked-results))]
    (is (= :prove step))
    (is (= [] (document-gaps database intent ["problem" "what-exists" "examples" "expected-cost" "approach"])))
    (is (designed-product? product worked-results) "the worked product ran red by design")
    (is (not (designed-product? #{} worked-results)) "an empty product is no handoff")
    (is (not (designed-product? product (update-vals worked-results #(assoc % :seon.test/fail-count 0 :seon.test.failure/reports []))))
        "a green product test is no designed red")))

(deftest ^{:seon/tasks #{'seon.step/implement}} implement-handoff
  (let [[database {intent :seon.task/plan} {step :seon.task/step}] (judged)]
    (is (= :implement step))
    (is (= [] (document-gaps database intent ["problem" "what-exists" "examples" "expected-cost" "approach"]))
        "the cumulative document holds and its references resolve")))
