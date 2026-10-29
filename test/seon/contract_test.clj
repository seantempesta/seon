(ns seon.contract-test
  "What a contract costs a call and what it still reports (task
  seon.contract/an-armed-call-costs-its-checks): an arm built of types that answer for any value
  (malli's -total?, the predicate pack named total in seon.contract/options) is checked without a
  try per call, and an arm that can throw keeps its try, so a throwing validator is still
  reported as the schema's fault, never taken for the function's error."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is testing]] [malli.core :as m]
            [seon.contract :as contract :refer [defn defn-]] [seon.cost :as cost]))

(defn- armed-inc
  "One int in, one out: the shape of the unit table's :fn/armed-call row."
  {:malli/schema [:=> [:cat :int] :int]}
  [x] (inc x))

(defn armed-calls
  "n calls of a contracted function: what an armed call costs, n times."
  {:malli/schema [:=> [:cat [:vector :int]] :int]}
  [xs] (reduce (fn [acc x] (+ acc (armed-inc x))) 0 xs))

(defn ints-of
  "armed-calls' argument: n integers."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector :int]]]}
  [n] [(vec (range n))])

(deftest an-armed-call-costs-its-checks-not-a-try
  (cost/proof
    {:of 'seon.contract-test/armed-calls :input 'seon.contract-test/ints-of
     :n "contracted calls"
     :work (str "per call the int checked going in and coming out, each a predicate answering for any "
                "value, and the call counted: no allocation (each check was a try, ~176 B, before)")
     :growth :linear
     ;; measured 2026-10-23: 0.02 B and ~10 ticks a call (seon.cost/units :fn/armed-call); 353 B before
     :per-n {:bytes 16.0 :calls 1.0}
     :fixed {:bytes 2000.0 :calls 1.0}
     :sizes [100 300 1000 10000]}))

(deftest an-arm-that-can-throw-is-still-reported-as-the-schemas
  (testing "which arms are total"
    (is (m/-total? (contract/schema [:cat :seon.host/db :int [:map [:a :string]] [:vector :keyword]]) contract/options))
    (is (m/-total? (contract/schema [:maybe [:or :int :seon.host/fn]]) contract/options))
    (is (not (m/-total? (m/schema [:cat [:fn int?]]) nil)) "a :fn calls user code (malli's validator catches its throw, at a try per call)")
    (is (m/-total? (m/schema [:cat [:> 0] [:= :a]]) nil) "a comparator answers through malli's -safe-pred")
    (is (m/-total? (m/schema [:cat int? string? [:vector keyword?]]) nil) "malli's predicate schemas")
    (is (not (m/-total? (m/schema [:cat pos?]) nil)) "but pos?, registered bare, throws on a string")
    (is (not (m/-total? (m/schema [:cat (m/-simple-schema {:type 'mine? :pred (fn [_] (throw (ex-info "x" {})))})]) nil))
        "a symbol-typed schema malli does not register is no predicate of its")
    (is (not (m/-total? (contract/schema [:cat :seon.host/db]) (dissoc contract/options ::m/total-types)))
        "the predicate pack is total because seon.contract/options names it")
    (is (m/-total? (m/schema [:cat [:=> [:cat :int] :int]]) nil) "a function schema checks fn?")
    (is (not (m/-total? (m/schema [:cat [:=> [:cat :int] :int]]) {::m/function-checker (fn [_ _] nil)}))
        "unless a function checker is set: it runs the function")
    (is (not (m/-total? (m/schema [:cat [:ref ::absent]] {:registry (merge (m/default-schemas) {::absent :int})}) nil)) "a :ref resolves lazily")
    (is (not (m/-total? (m/schema [:cat (m/-simple-schema {:type ::mine :pred int?})]) nil)) "a predicate no option names"))
  (let [reports (volatile! [])
        report (fn [kind _] (vswap! reports conj kind))
        armed (fn [schema] (m/-instrument {:schema schema :scope #{:input :output} :report report} (fn [x] x)))]
    (testing "an arm that can throw keeps its try: the throw is the schema's report, the call goes on"
      (let [lazy {:registry (assoc (m/default-schemas) :lazy-ref (m/-ref-schema {:lazy true}))}]
        ((m/-instrument {:schema [:=> [:cat [:schema [:lazy-ref ::absent]]] :int] :scope #{:input} :report report} identity lazy) 1))
      (is (= [::m/invalid-schema-at-call] @reports)))
    (testing "a total arm checked bare still reports what is invalid"
      (vreset! reports [])
      ((armed (m/schema [:=> [:cat :int] :int])) "not an int")
      (is (= [::m/invalid-input ::m/invalid-output] @reports)))
    (testing "and so does the generic wrapper (a variadic arity)"
      (vreset! reports [])
      ((m/-instrument {:schema (m/schema [:=> [:cat :int [:* :int]] :int]) :scope #{:input :output} :report report}
                      (fn [& xs] (last xs)))
       1 2 "three")
      (is (= [::m/invalid-input ::m/invalid-output] @reports)))))

;; docs/errors.md#an-error-keeps-data: an error root keeps of another process keeps data, never the
;; value that made it (AGENTS.md section 4: root's *e reached 1.08 GB); what an agent's own error
;; or an asker gets is the error whole (owner, 2026-10-29: no cuts on an agent's context or evals).
(deftest an-error-keeps-bytes-that-do-not-grow-with-its-value
  (let [kept-bytes
        (fn [n]
          (let [big (vec (range (quot n 8)))
                text (apply str (repeat n "x"))
                live (atom big)
                flowed (:data (#'seon.root/flow-error {:clojure.core.async.flow/pid :render
                                                       :clojure.core.async.flow/ex (ex-info "boom" {:v big :s text :a live})}))]
            (is (not-any? #(instance? clojure.lang.IDeref %) (tree-seq coll? seq flowed)) "no live object is kept, only its print")
            (is (string? (:seon.print/code (:a flowed))) "the atom is kept as its print")
            (is (or (= n 1000) (re-find #"\+~[\d,]+ tokens" (:s flowed))) "a value past the budget is kept as the printer's cut, which says in tokens what it omitted")
            (count (pr-str flowed))))
        sizes (map kept-bytes [1000 100000 10000000])]
    ;; a small value is kept whole (1 KB), anything past the budget as its bounded print: the
    ;; bytes root keeps stop growing, and 10,000 times the value adds none
    (is (every? #(< % 8000) sizes) (str "bytes kept for 1 KB, 100 KB and 10 MB values: " (vec sizes)))
    (is (<= (last sizes) (+ 100 (second sizes))) (str "100 times more value keeps no more: " (vec sizes)))))

;; docs/errors.md#an-error-keeps-data: an agent's own error is whole.
(deftest an-agents-own-error-keeps-its-full-data
  (let [big (vec (range 100000)) text (apply str (repeat 100000 "x")) live (atom big)
        mapped ((requiring-resolve 'seon.agent/error-map) (ex-info "boom" {:v big :s text :a live}))
        made (contract/error :seon.root/refused 'app/f "boom" {:big big :took text :held live})]
    (is (= {:v big :s text :a live} (:data mapped)) "an eval error's ex-data is its :data, whole")
    (is (= {:big big :took text :held live} (select-keys made [:big :took :held])) "a built error carries its members whole")))

(deftest a-contract-error-says-where-and-what
  ;; md-scan #14, 2026-10-29: "its value does not satisfy its contract" sent the agent through
  ;; ex-data for twelve evals to find one nil key; the message names the first problem
  (let [f (contract/wrap 'x.c/blocks (contract/schema [:=> [:cat] [:map [:from :int] [:to :int]]]) (fn [] {:from 0 :to nil}))
        msg (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e)))]
    (is (clojure.string/includes? (str msg) "at [:to]: nil, expected :int") (str "where, what it got, what was expected: " msg))))
