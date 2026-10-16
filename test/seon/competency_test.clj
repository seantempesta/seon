(ns seon.competency-test
  "Proofs of the seon.competency tasks; each runs as its member on a branch off the run's agent.
  A task's prose and typed wordings share one proof."
  {:seon.test/long "Red until an agent does the task: excluded from the bulk tier, run by identity."}
  (:require [clojure.data.json :as json]
            [clojure.test :refer [are deftest is]]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support]))

(defn- v
  "The value `code` answers in this member's world."
  {:malli/schema [:=> [:cat :string] :seon.schema/value]}
  [code]
  (support/agent-value (kernel/world-ctx) code))

(deftest ^{:seon/tasks #{'seon.competency/area-is-contracted 'seon.competency/area-is-contracted-typed}}
  t1-area-is-contracted
  (is (= 6 (v "(comp.geo/area 2 3)")))
  (is (some? (:seon.error/type (v "(comp.geo/area -1 3)"))) "the armed contract refuses")
  (is (some? (v "(:malli/schema (meta #'comp.geo/area))"))))

(deftest ^{:seon/tasks #{'seon.competency/quantity-schema-then-function-prose
                         'seon.competency/quantity-schema-then-function-typed}}
  t2-quantity-schema-then-function
  (is (= [:int {:min 1}] (v "(seon.schema/schema-definition :comp.order/qty)")))
  (is (= 4 (v "(comp.order/double-qty 2)")))
  (is (some? (:seon.error/type (v "(comp.order/double-qty 0)"))) "the armed contract refuses"))

(deftest ^{:seon/tasks #{'seon.competency/slug-has-a-passing-test-prose
                         'seon.competency/slug-has-a-passing-test-typed}}
  t3-slug-has-a-passing-test
  (is (true? (v "(var? (resolve 'comp.text-test/slug-lowercases))")))
  (let [counters (v "(clojure.test/test-ns 'comp.text-test)")]
    (is (<= 2 (:pass counters 0)) "the agent's tests make two or more passing assertions")
    (is (= [0 0] [(:fail counters 0) (:error counters 0)]))))

(deftest ^{:seon/tasks #{'seon.competency/slug-removes-edge-hyphens}}
  t5-slug-removes-edge-hyphens
  (are [title slug] (= slug (v (str "(comp.text/slug " (json/write-str title) ")")))
      "Hello World" "hello-world"
      "  ABC " "abc"
      "  --Hello   WORLD--  " "hello-world"
      "---" ""
      " a-b " "a-b"
      "" ""))

(deftest ^{:seon/tasks #{'seon.competency/app-calls-util-through-an-alias-prose
                         'seon.competency/app-calls-util-through-an-alias-typed}}
  t4-app-calls-util-through-an-alias
  (is (= [10 0 7] (v "(mapv comp.app/bounded [15 -3 7])")))
  (is (= 'comp.util (v "(ns-name (get (ns-aliases 'comp.app) 'u))"))))

(deftest t6-word-count
  (is (= 3 (v "(comp.text/word-count \"a b  c\")")))
  (is (zero? (v "(comp.text/word-count \"  \")")))
  (is (some? (:seon.error/type (v "(comp.text/word-count 7)"))) "the armed contract refuses"))

(deftest t7-index-by-id
  (is (= {1 {:id 1 :n "a"} 2 {:id 2}} (v "(comp.coll/index-by-id [{:id 1 :n \"a\"} {:id 2}])")))
  (is (= {} (v "(comp.coll/index-by-id [])")))
  (is (some? (:seon.error/type (v "(comp.coll/index-by-id [{:n 1}])"))) "the armed contract refuses"))

(deftest t8-digit-sum-is-tested
  (is (= [0 7 6 36] (v "(mapv comp.digits/digit-sum [0 7 123 9999])")))
  (is (some? (:seon.error/type (v "(comp.digits/digit-sum -1)"))) "the armed contract refuses")
  (let [counters (v "(clojure.test/test-ns 'comp.digits-test)")]
    (is (<= 3 (:pass counters 0)) "the agent's test makes three or more passing assertions")
    (is (= [0 0] [(:fail counters 0) (:error counters 0)]))))

(deftest t9-initials
  (is (= ["AL" "gbh" ""] (v "(mapv comp.names/initials [\"Ada Lovelace\" \"  grace  brewster hopper \" \"\"])")))
  (is (some? (:seon.error/type (v "(comp.names/initials nil)"))) "the armed contract refuses"))
