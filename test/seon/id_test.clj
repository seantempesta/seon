(ns seon.id-test
  (:require [clojure.test :refer [deftest is]]
            [seon.id :as id]))

(deftest data-shape-and-explicit-length-determine-identity
  (is (= "ba7816bf8f01" (id/id 'abc)))
  (is (= "ba7816bf" (id/id 'abc 8)))
  (is (not= (id/id 'abc) (id/id "abc")))
  (is (= (id/id ["turn" 2]) (id/evaluation "turn" 2)))
  (is (= (id/id ["turn" 2] 8) (id/digest 8 ["turn" 2])))
  (let [a (id/id) b (id/id)]
    (is (id/valid? id/default-length a))
    (is (id/valid? id/default-length b))
    (is (not= a b))))

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

(deftest printed-identity-ignores-the-callers-print-bindings
  ;; THE CLASS: an identity is a function of the value, never of the thread's
  ;; print bindings. The literal is the REPL-bound identity stored ids carry.
  (let [parts [{:a/b 1 :a/c 2} [1 2 3] (with-meta 'x {:line 1}) "s"]
        expected (id/digest 12 parts)]
    (is (= "15564fc125fc" (id/digest 12 [{:a/b 1 :a/c 2}])))
    (doseq [hostile [{#'*print-length* 1} {#'*print-level* 1} {#'*print-meta* true}
                     {#'*print-namespace-maps* false} {#'*print-namespace-maps* true}
                     {#'*print-readably* false} {#'*print-dup* true}]]
      (with-bindings hostile
        (is (= expected (id/digest 12 parts)))
        (is (= "15564fc125fc" (id/digest 12 [{:a/b 1 :a/c 2}])))))))

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
  (is (= 12 (count (id/id (reduce (fn [m _] {:a/b m}) {} (range 10000)))))))
