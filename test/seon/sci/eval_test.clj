(ns seon.sci.eval-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]
            [seon.sci.interrupt :as interrupt]))

(deftest evaluation-schema-resolves-a-real-value-contract
  (testing "successful values remain genuinely polymorphic"
    (doseq [value [nil 1 :ok 'result (map inc (range 3))]]
      (is (schema/valid-candidate-value? ::eval/value value)
          (pr-str value))))
  (testing "an error-shaped result must carry the evaluator's complete error"
    (is (schema/valid-candidate-value?
         ::eval/value
         {:seon.error/message "Unable to resolve symbol."
          :seon.error/kind :error
          :seon.error/data {:sci.impl/symbol 'missing}}))
    (is (not (schema/valid-candidate-value?
              ::eval/value
              {:seon.error/message "Incomplete."}))))
  (testing "the response record names every diagnostic the evaluator returns"
    (is (schema/valid-candidate-value?
         ::eval/evaluation
         {::eval/value nil
          ::eval/record
          {:seon.eval/fn-entries 0
           :seon.eval/duration-ms 1
           :seon.eval/allocated-bytes 0
           :seon.eval/outcome :ok
           ::eval/semaphore-wait-ms 0}}))
    (is (not (schema/valid-candidate-value?
              ::eval/evaluation
              {::eval/value nil
               ::eval/record
               {:seon.eval/outcome :ok
                ::eval/semaphore-wait-ms 0}})))))

(deftest sci-reader-refuses-read-eval-before-host-code-runs
  (let [path (str (System/getProperty "user.dir")
                  "/tmp/seon-sci-read-eval-"
                  (random-uuid)
                  ".txt")
        file (io/file path)
        source
        (format
         "#=(clojure.core/spit %s \"read-eval escaped SCI\")"
         (pr-str path))]
    (.delete file)
    (eval/open! {::eval/concurrency 1})
    (let [result (eval/evaluate
                  {::eval/source source
                   ::interrupt/time-limit-ms 1000})]
      (is (eval/error? (::eval/value result)))
      (is (= :error
             (get-in result [::eval/record :seon.eval/outcome])))
      (is (false? (.exists file))))))

(deftest time-limit-trips-below-the-allocation-sample-cadence
  (eval/open! {::eval/concurrency 1})
  (let [result
        (eval/evaluate
         {::eval/source
          "(let [b (loop [v 2N i 0] (if (< i 18) (recur (* v v) (inc i)) v))] (mod (apply * (repeat 300 b)) 7))"
          ::interrupt/time-limit-ms 10})
        record (::eval/record result)]
    (testing "the next function-body entrance observes the expired time flag"
      (is (eval/error? (::eval/value result)))
      (is (= :time (:seon.eval/outcome record)))
      (is (< (:seon.eval/fn-entries record) 1024)))))

(deftest agent-code-cannot-catch-the-interrupt-marker
  (eval/open! {::eval/concurrency 1})
  (let [result
        (eval/evaluate
         {::eval/source
          "(try (loop [] (recur)) (catch Throwable _ :swallowed))"
          ::interrupt/time-limit-ms 10})]
    (is (eval/error? (::eval/value result)))
    (is (= :time
           (get-in result [::eval/record :seon.eval/outcome])))))

(deftest catch-class-surface-is-deliberately-broad-and-small
  (eval/open! {::eval/concurrency 1})
  (let [evaluate
        #(eval/evaluate
          {::eval/source %
           ::interrupt/time-limit-ms 1000})]
    (testing "the broad JVM catch roots and SCI's Exception resolve"
      (doseq [[class-name throwable]
              [["Throwable" "(Exception. \"x\")"]
               ["Error" "(Error. \"x\")"]
               ["Exception" "(Exception. \"x\")"]]]
        (is (= :caught
               (::eval/value
                (evaluate
                 (format
                  "(try (throw %s) (catch %s e :caught))"
                  throwable
                  class-name))))
            class-name)))
    (testing "subclasses are not an open-ended allowlist and :default is CLJS"
      (doseq [class-name
              ["RuntimeException" "StackOverflowError" ":default"]]
        (let [value
              (::eval/value
               (evaluate
                (format
                 "(try (throw (Exception. \"x\")) (catch %s e :caught))"
                 class-name)))]
          (is (eval/error? value) class-name)
          (is (= (str "Unable to resolve classname: " class-name)
                 (get-in value
                         [:seon.error/data
                          :seon.sci.eval/raw-message]))
              class-name))))))

(deftest stack-overflow-error-message-falls-back-to-its-class
  (eval/open! {::eval/concurrency 1})
  (let [value
        (::eval/value
         (eval/evaluate
          {::eval/source "((fn overflow [] (overflow)))"
           ::interrupt/time-limit-ms 1000}))]
    (is (eval/error? value))
    (is (= "java.lang.StackOverflowError"
           (:seon.error/message value)))
    (is (= "java.lang.StackOverflowError"
           (get-in value
                   [:seon.error/data
                    :seon.sci.eval/throwable-class])))))

(deftest ordinary-source-produces-a-value-and-diagnostics
  (eval/open! {::eval/concurrency 1})
  (let [result
        (eval/evaluate
         {::eval/source "(reduce + (range 10))"
          ::interrupt/time-limit-ms 1000})]
    (is (= 45 (::eval/value result)))
    (is (= :ok (get-in result [::eval/record :seon.eval/outcome])))
    (is (pos? (get-in result [::eval/record :seon.eval/fn-entries])))))

(deftest lifecycle-completion-is-an-ordinary-evaluation-value
  (eval/open! {::eval/concurrency 1})
  (let [result
        (eval/evaluate
         {::eval/source "(seon.agent.lifecycle/complete \"X\")"
          ::interrupt/time-limit-ms 1000})]
    (is (= {:seon.agent.lifecycle/disposition :completed
            :seon.agent.lifecycle/result "X"}
           (::eval/value result)))
    (is (= :ok (get-in result [::eval/record :seon.eval/outcome])))))

(deftest unresolved-symbol-error-names-the-symbol
  (eval/open! {::eval/concurrency 1})
  (let [result
        (eval/evaluate
         {::eval/source "(missing.namespace/function 1)"
          ::interrupt/time-limit-ms 1000})]
    (is (eval/error? (::eval/value result)))
    (is (= 'missing.namespace/function
           (get-in result
                   [::eval/value :seon.error/data :sci.impl/symbol])))))

(deftest mixed-namespace-reply-forms-evaluate-independently
  (eval/open! {::eval/concurrency 1})
  (let [evaluate
        #(eval/evaluate
          {::eval/source %
           ::interrupt/time-limit-ms 1000})]
    (is (= [3 "ABC"
            {:seon.agent.lifecycle/disposition :completed
             :seon.agent.lifecycle/result "mixed"}]
           (mapv
            (comp ::eval/value evaluate)
            ["(+ 1 2)"
             "(clojure.string/upper-case \"abc\")"
             "(seon.agent.lifecycle/complete \"mixed\")"])))))

(deftest blocked-host-call-consumes-only-its-own-capacity
  (let [release (promise)
        base-ctx
        (sci/init
         {:namespaces
          {'user {'block (fn [] @release)}}})]
    (eval/open! {::eval/concurrency 2})
    (let [blocked
          (eval/evaluate
           {::eval/source "(user/block)"
            ::eval/base-ctx base-ctx
            ::interrupt/time-limit-ms 10})]
      (is (= :time
             (get-in blocked [::eval/record :seon.eval/outcome])))
      (is (= 1 (eval/available))
          "the still-running platform task keeps exactly one permit")
      (is (= 3
             (::eval/value
              (eval/evaluate
               {::eval/source "(+ 1 2)"
                ::eval/base-ctx base-ctx
                ::interrupt/time-limit-ms 1000})))
          "unrelated capacity remains usable")
      (deliver release true)
      (loop [attempt 0]
        (when (and (< attempt 10000)
                   (not= 2 (eval/available)))
          (Thread/onSpinWait)
          (recur (inc attempt))))
      (is (= 2 (eval/available))
          "the permit returns only when the blocked call really exits"))))

(deftest fork-isolates-new-definitions
  (let [never-interrupt (constantly nil)
        fork-a (eval/fork {:interrupt-fn never-interrupt})
        fork-b (eval/fork {:interrupt-fn never-interrupt})]
    (sci/eval-string* fork-a "(def local-value 42)")
    (is (= 42 (sci/eval-string* fork-a "local-value")))
    (is (thrown-with-msg?
         Throwable #"Unable to resolve symbol: local-value"
         (sci/eval-string* fork-b "local-value")))))

(deftest interrupt-aware-string-functions-are-in-the-base
  (let [entries (atom 0)
        forked (eval/fork {:interrupt-fn #(swap! entries inc)})]
    (is (= "bbb" (sci/eval-string*
                  forked
                  "(clojure.string/replace \"aaa\" #\"a\" \"b\")")))
    (is (pos? @entries))))
