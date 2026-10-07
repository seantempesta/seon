(ns seon.id-test
  (:require [clojure.test :refer [are deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.walk :as walk]
            [seon.id :as id]
            [seon.test-support :as test-support]))

(defrecord ^:private Point [a])

(deftest identity-is-a-function-of-the-values-structure
  ;; Equal values (Clojure `=`) share an id whatever their map or set order;
  ;; a vector and a list differ, since code identity needs it.
  (are [a b same?] (= same? (= (id/id a) (id/id b)))
    [:a 1]                [:a 1]              true
    [:a 1]                [:a 2]              false
    {:a 1 :b 2}           {:b 2 :a 1}         true
    (sorted-map :a 1 :b 2) {:b 2 :a 1}        true
    #{1 2}                (sorted-set 2 1)    true
    {:x #{1 2} :y [{:z 1 :w 2}]} {:y [{:w 2 :z 1}] :x #{2 1}} true
    {:a 1 :b 2}           {:a 2 :b 1}         false
    1                     1N                  true
    (int 5)               5                   true
    1                     1.0                 false
    0.0                   -0.0                true
    1.0M                  1.00M               true
    [1 2]                 '(1 2)              false
    '(1 2)                (range 1 3)         true
    (with-meta [1] {:x 1}) [1]                true
    nil                   false               false
    'abc                  "abc"               false
    :a/b                  'a/b                false
    [1 [2]]               [[1] 2]             false
    {:a nil}              {}                  false
    #{}                   {}                  false
    \a                    97                  false
    "\uD800"              "?"                 false
    (->Point 1)           {:a 1}              false)
  (is (= "ebc54726b35b" (id/id 'abc)) "the encoding is fixed: every stored identity was derived under it")
  (is (= "ebc54726" (id/id 'abc 8)))
  (is (= (id/id ["turn" 2]) (id/evaluation "turn" 2)))
  (is (= (id/id ["turn" 2] 8) (id/digest 8 ["turn" 2])))
  (let [a (id/id) b (id/id)]
    (is (id/valid? id/default-length a))
    (is (not= a b))))

;; EDN-shaped values, nested; bounded so every seed finishes.
(def ^:private scalar-generator
  (gen/one-of
   [(gen/return nil) gen/boolean gen/small-integer gen/ratio gen/string-alphanumeric gen/keyword gen/symbol gen/char
    (gen/fmap #(java.util.Date. (long %)) gen/small-integer)
    (gen/fmap #(java.time.Instant/ofEpochMilli (long %)) gen/small-integer)
    (gen/fmap #(java.util.UUID/nameUUIDFromBytes (.getBytes ^String % "UTF-8")) gen/string-alphanumeric)]))

(def ^:private value-generator
  (gen/recursive-gen
   (fn [child]
     (gen/one-of [(gen/vector child 0 8) (gen/fmap #(apply list %) (gen/vector child 0 8))
                  (gen/set child {:max-elements 8}) (gen/map child child {:max-elements 5})]))
   scalar-generator))

(deftest map-and-set-order-never-changes-an-identity
  (test-support/assert-check!
   (tc/quick-check
    500
    (prop/for-all [value value-generator]
      (= (id/id value)
         (id/id (walk/postwalk #(cond (map? %) (into {} (reverse %)) (set? %) (into #{} (reverse %)) :else %) value))))
    :seed 2026100701)
   "An identity changed with entry order:"))

(deftest a-host-object-has-no-identity
  (is (= :seon.id/unhashable-error
         (try (id/id (Object.)) (catch clojure.lang.ExceptionInfo e (:seon.error/type (ex-data e)))))))

(deftest an-evaluation-id-is-stable-short-and-a-symbol
  ;; THE CLASS: every stable identifier comes from the one derivation.
  (let [a (id/evaluation "cluster-default" "turn-1" 0)
        b (id/evaluation "cluster-default" "turn-1" 0)
        c (id/evaluation "cluster-default" "turn-1" 1)
        d (id/evaluation "cluster-other" "turn-1" 0)]
    (is (= a b) "same parts, same id, every time")
    (is (distinct? a c d) "ordinal and branch each change the id")
    (is (id/valid? 12 a) "twelve lowercase hex characters")
    (is (= (symbol "result" (str "e" a)) (id/symbol-in "result" \e a))
        "the handle leads with a letter")
    (is (= (id/symbol-in "result" \e a)
           (read-string (str (id/symbol-in "result" \e a))))
        "the handle survives the reader")))

(deftest canonical-print-is-prs-text-at-any-depth
  ;; THE CLASS: the one canonical print is iterative, so a value of any depth
  ;; prints (and hashes) instead of overflowing the stack, and its text is
  ;; exactly `pr`'s under REPL bindings (every stored identity's text).
  (doseq [[value text] [[nil "nil"] ["q\"" "\"q\\\"\""] [{} "{}"] [() "()"] [#{} "#{}"]
                        [{:a/b 1 :a/c 2} "#:a{:b 1, :c 2}"] [{:a/b 1 :c 2} "{:a/b 1, :c 2}"]
                        [{'x/y [1 2]} "#:x{y [1 2]}"] [(sorted-map :b 1 :a 2) "{:a 2, :b 1}"]
                        [(list 1 (list 2)) "(1 (2))"] [(range 3) "(0 1 2)"] [(first {:a 1}) "[:a 1]"]
                        [{nil 1} "{nil 1}"] [{[1] {:x/y #{:z}}} "{[1] #:x{:y #{:z}}}"] [1/3 "1/3"] [\a "\\a"]]]
    (is (= text (id/printed value))))
  (is (= (str (apply str (repeat 10001 "[")) (apply str (repeat 10001 "]")))
         (id/printed (reduce (fn [v _] [v]) [] (range 10000)))))
  (is (= 12 (count (id/id (reduce (fn [v _] [v]) [] (range 10000))))))
  (is (= 12 (count (id/id (reduce (fn [m _] {:a/b m}) {} (range 10000)))))))
