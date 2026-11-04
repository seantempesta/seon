(ns seon.contract-test
  "What a contract costs a call and what it still reports (task
  seon.contract/an-armed-call-costs-its-checks): an arm built of types that answer for any value
  (malli's -total?, the predicate pack named total in seon.contract/options) is checked without a
  try per call, and an arm that can throw keeps its try, so a throwing validator is still
  reported as the schema's fault, never taken for the function's error."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is testing]] [malli.core :as m]
            [seon.contract :as contract :refer [defn defn-]] [seon.cost :as cost] [seon.config] [seon.print]))

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

(deftest a-char-is-a-schema
  ;; bench-delims-1: an agent's [:=> [:cat :string] [:map-of :char :int]] was :malli.core/invalid-schema; malli names
  ;; a character only as the predicate char?, so :char is one of the registry's type keys, as :int is malli's
  (is (m/validate [:map-of :char :int] {\( 1} {:registry contract/registry}) "a character")
  (is (not (m/validate :char "(" {:registry contract/registry})) "a string is no character")
  (is (m/validate [:=> [:cat :string] [:map-of :char :int]] (fn [_] {}) {:registry contract/registry}) "in a function schema")
  (is (char? ((requiring-resolve 'malli.generator/generate) :char {:registry contract/registry})) "and it generates, as every contract must"))

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
        sizes (map kept-bytes [1000 100000 1000000])]
    ;; a small value is kept whole (1 KB), anything past the budget as its bounded print: the
    ;; bytes root keeps stop growing, and 1,000 times the value adds none
    (is (every? #(< % 8000) sizes) (str "bytes kept for 1 KB, 100 KB and 1 MB values: " (vec sizes)))
    (is (<= (last sizes) (+ 100 (second sizes))) (str "10 times more value keeps no more: " (vec sizes)))))

;; docs/errors.md#an-error-keeps-data: an agent's own error is whole.
(deftest an-agents-own-error-keeps-its-full-data
  (let [big (vec (range 100000)) text (apply str (repeat 100000 "x")) live (atom big)
        mapped ((requiring-resolve 'seon.agent/error-map) (ex-info "boom" {:v big :s text :a live}))
        made (contract/error :seon.root/refused 'app/f "boom" {:big big :took text :held live})]
    ;; plain data whole, whatever its size; what no store keeps, a live atom, is what it was (seon.contract/plainly)
    (is (= {:v big :s text} (select-keys (:data mapped) [:v :s])) "an eval error's ex-data is its :data, whole")
    (is (= {:seon.value/type (.getName (class live))} (:a (:data mapped))))
    (is (= {:big big :took text} (select-keys made [:big :took])) "a built error carries its members whole")
    (is (= {:seon.value/type (.getName (class live))} (:held made)))
    (is (contract/plain? made))))

(deftest a-contract-error-says-where-and-what
  ;; md-scan #14, 2026-10-29: "its value does not satisfy its contract" sent the agent through
  ;; ex-data for twelve evals to find one nil key; the message names the first problem
  (let [f (contract/wrap 'x.c/blocks (contract/schema [:=> [:cat] [:map [:from :int] [:to :int]]]) (fn [] {:from 0 :to nil}))
        msg (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e)))]
    (is (clojure.string/includes? (str msg) "at [:to]: nil, expected :int") (str "where, what it got, what was expected: " msg))
    ;; md-scan #18: refused at [:seon.ref/blocks 0 :seon.ref/text-from], the block that lacked the key was never seen
    (is (clojure.string/includes? (str msg) "in {:from 0, :to nil}") (str "and the map that holds it: " msg)))
  (let [f (contract/wrap 'x.c/blocks (contract/schema [:=> [:cat] [:map [:blocks [:vector [:map [:kind :keyword] [:text-from :int]]]]]])
                         (fn [] {:blocks [{:kind :para} {:kind :code}]}))
        msg (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e)))]
    (is (clojure.string/includes? (str msg) "at [:blocks 0 :text-from]") msg)
    (is (clojure.string/includes? (str msg) "in {:kind :para}") (str "a missing key: the map without it: " msg))))

;; docs/checks.md#contracts-armed-at-definition: a call with the wrong number of arguments names the calls its var declares, a form to
;; copy (reading the schema's form, a call went wrong again)
(deftest an-arity-error-names-the-calls-to-copy
  (let [msg (try ((resolve 'seon.contract-test/armed-calls)) nil (catch clojure.lang.ExceptionInfo e (ex-message e)))]
    (is (clojure.string/includes? (str msg) "called with 0 arguments; it is called as (seon.contract-test/armed-calls xs)") msg)))

;; See docs/cost.md#counting-calls
(deftest a-test-process-arms-what-root-arms-at-boot
  ;; a cost proof counts contracted calls on armed vars, so the same code counts the same in bin/test
  ;; and in an agent's test child only if both armed seon.config, seon.edn and seon.print as root's
  ;; boot does: seon.doc/citing-sections read 1 call under bin/test and 4 in the agent (the config read it makes)
  (is (contract/armed? #'seon.config/value) "seon.config/value is counted, as in root")
  (is (contract/armed? #'seon.print/print-value) "seon.print/print-value is counted, as in root")
  (is (every? contract/armed? (for [n contract/boot-nss v (vals (ns-interns (the-ns n))) :when (contract/contract v)] v))
      "every contracted var of the boot namespaces is armed"))

(defrecord Row [id name])

;; docs/storage.md#plain-data: a value that is not plain data is made plain as data, never as its print.
(deftest plainly-never-prints
  (let [gone #(= [:seon.value/gone] (keys %))
        e (ex-info "boom" {:a (atom 1)})]
    (testing "a var, a fn, a namespace and an atom each answer what they are, as data"
      (is (= ['clojure.core/inc {:seon.value/type (.getName (class inc))} 'clojure.core {:seon.value/type (.getName (class (atom 1)))}]
             (contract/plainly [#'clojure.core/inc inc (the-ns 'clojure.core) (atom 1)]))))
    (testing "a record is a map of its fields, a sorted map and set unsorted, each member so"
      (is (= {:id 1 :name {:seon.value/type (.getName (class inc))}} (contract/plainly (->Row 1 inc))))
      (is (= {:a {:b 1}} (contract/plainly (sorted-map :a (sorted-map :b 1)))))
      (is (= #{1 2} (contract/plainly (sorted-set 1 2)))))
    (testing "a db is a map to Clojure and a record, and still a host object: its class, never its fields"
      (let [db (datahike.db/empty-db)]
        (is (= {:seon.value/type (.getName (class db))} (contract/plainly db)))
        (is (= {:db {:seon.value/type (.getName (class db))}} (contract/plainly {:db db})))))
    (testing "a lazy seq is a vector, realized to the bound and no further"
      (let [realized (volatile! 0)
            xs (map (fn [i] (vswap! realized inc) i) (range))]
        (is (= [0 1 2] (contract/plainly (take 3 xs) 5)))
        (is (gone (contract/plainly xs 5)) "past the bound, the marker")
        (is (= 5 (:over (:seon.value/gone (contract/plainly xs 5)))))
        (is (<= @realized 12) (str "never realized further: " @realized))))
    (testing "a throwable keeps its type and its message, as data"
      (is (= {:seon.value/type (.getName (class e)) :seon.value/message "boom"} (contract/plainly e))))
    (is (contract/plain? (contract/plainly [inc (->Row 1 (atom 1)) (sorted-map :a (range 3)) e])) "whatever it held, plain")))

(deftest within-counts-by-a-walk-that-stops-at-the-bound
  (is (contract/within? {:a [1 2 "abc"]} 20))
  (is (not (contract/within? (vec (range 100)) 10)) "a scalar is 1")
  (is (not (contract/within? (apply str (repeat 50 "x")) 10)) "a string is its length")
  (is (contract/within? nil 1)))
