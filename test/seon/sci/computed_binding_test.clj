(ns seon.sci.computed-binding-test
  "The callable surface is computed: program-function facts filtered by
  the agent's derived namespace policy. No prefix rule, no hand list."
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.eval.receipt]
            [seon.sci.eval :as eval]))

(def ^:dynamic *probe*
  "A test-local invocation binding observed through a tool call."
  nil)

(defn read-probe
  "Return the probe binding the tool call established on this thread."
  []
  *probe*)

(defn- eval-with
  [{:keys [program-functions exposed-namespaces bindings source]}]
  (let [base (eval/base
              {::eval/program-functions program-functions
               ::eval/exposed-namespaces exposed-namespaces
               ::eval/bindings (or bindings {})})]
    (sci/eval-string*
     (eval/fork {::eval/base-ctx base
                 :interrupt-fn (constantly nil)})
     source)))

(deftest removing-a-program-function-removes-its-binding
  (testing "the binding table follows the current program-function facts"
    (is (= (pr-str ["run" 0 1])
           (eval-with
            {:program-functions ["seon.eval.receipt/receipt-id"]
             :exposed-namespaces #{'seon.eval.receipt}
             :source "(seon.eval.receipt/receipt-id \"run\" 0 1)"})))
    (is (thrown-with-msg?
         Throwable
         #"Unable to resolve symbol: seon.eval.receipt/receipt-id"
         (eval-with
          {:program-functions []
           :exposed-namespaces #{'seon.eval.receipt}
           :source "(seon.eval.receipt/receipt-id \"run\" 0 1)"})))))

(deftest namespace-policy-gates-the-binding-table
  (testing "a committed function outside the derived policy stays unbound"
    (is (thrown-with-msg?
         Throwable
         #"Unable to resolve symbol: seon.eval.receipt/receipt-id"
         (eval-with
          {:program-functions ["seon.eval.receipt/receipt-id"]
           :exposed-namespaces #{}
           :source "(seon.eval.receipt/receipt-id \"run\" 0 1)"})))))

(deftest tool-calls-establish-the-invocation-bindings
  (testing "a {Var value} binding is established on the eval thread at
            call time — the mechanism the platform leaves ride"
    (is (= :bound-at-call-time
           (eval-with
            {:program-functions
             ["seon.sci.computed-binding-test/read-probe"]
             :exposed-namespaces #{'seon.sci.computed-binding-test}
             :bindings {#'*probe* :bound-at-call-time}
             :source "(seon.sci.computed-binding-test/read-probe)"})))))

(deftest require-spec-namespaces-derive-from-policy-data
  (is (= #{'seon.db 'my.plan}
         (eval/require-spec-namespaces
          '[[seon.db :as db]
            [my.plan :refer [plan!]]])))
  (is (= #{} (eval/require-spec-namespaces []))))
