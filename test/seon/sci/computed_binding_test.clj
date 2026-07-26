(ns seon.sci.computed-binding-test
  "The callable surface is computed: program-function facts filtered by
  the agent's derived namespace policy. No prefix rule, no hand list."
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.effect :as effect]
            [seon.sci.eval :as eval]))

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
            {:program-functions ["seon.effect/op-id"]
             :exposed-namespaces #{'seon.effect}
             :source "(seon.effect/op-id \"run\" 0 1)"})))
    (is (thrown-with-msg?
         Throwable
         #"Unable to resolve symbol: seon.effect/op-id"
         (eval-with
          {:program-functions []
           :exposed-namespaces #{'seon.effect}
           :source "(seon.effect/op-id \"run\" 0 1)"})))))

(deftest namespace-policy-gates-the-binding-table
  (testing "a committed function outside the derived policy stays unbound"
    (is (thrown-with-msg?
         Throwable
         #"Unable to resolve symbol: seon.effect/op-id"
         (eval-with
          {:program-functions ["seon.effect/op-id"]
           :exposed-namespaces #{}
           :source "(seon.effect/op-id \"run\" 0 1)"})))))

(deftest tool-calls-establish-the-invocation-bindings
  (testing "the effect context is bound on the eval thread at call time"
    (let [context (effect/request-context "run-under-test" 3)]
      (is (= (pr-str ["run-under-test" 3 0])
             (eval-with
              {:program-functions ["seon.effect/next-op-id!"]
               :exposed-namespaces #{'seon.effect}
               :bindings {#'effect/*request-context* context}
               :source "(seon.effect/next-op-id!)"}))))))

(deftest require-spec-namespaces-derive-from-policy-data
  (is (= #{'seon.db 'my.plan}
         (eval/require-spec-namespaces
          '[[seon.db :as db]
            [my.plan :refer [plan!]]])))
  (is (= #{} (eval/require-spec-namespaces []))))
