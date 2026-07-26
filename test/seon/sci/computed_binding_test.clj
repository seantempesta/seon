(ns seon.sci.computed-binding-test
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.sci.ctx :as ctx]))

(defn- eval-with-program-functions
  [program-functions source]
  (let [base
        (ctx/base
         {::ctx/program-functions program-functions
          ::ctx/request-context
          {:seon.capability/op-id "computed-binding-test"
           :seon.effect/query
           (fn [_query & _inputs]
             [[:derived-from-program-graph]])}})]
    (sci/eval-string*
     (ctx/fork {::ctx/base-ctx base
                :interrupt-fn (constantly nil)})
     source)))

(deftest removing-a-program-function-removes-its-binding
  (testing "the binding table follows the current program-function facts"
    (is (= [[:derived-from-program-graph]]
           (eval-with-program-functions
            ["my.db/q"]
            "(my.db/q '[:find ?value :where [?entity :attr ?value]])")))
    (is (thrown-with-msg?
         Throwable
         #"Unable to resolve symbol: my.db/q"
         (eval-with-program-functions
          []
          "(my.db/q '[:find ?value :where [?entity :attr ?value]])")))))
