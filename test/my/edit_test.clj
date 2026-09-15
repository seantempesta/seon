(ns my.edit-test
  (:require [seon.edit :as owner]
            [clojure.test :refer [deftest is testing]]
            [my.edit :as edit]
            [seon.schema :as schema]))

(deftest form-operation-relation-is-structural-and-open
  (let [base {:my.edit/path "src/example.clj"
              :my.edit/expected-digest (apply str (repeat 64 "a"))
              :my.edit/form {:my.edit.form/head 'defn
                             :my.edit.form/name 'example}}]
    (is (true? (owner/valid-form-operation?
                (assoc base :my.edit/operation :replace
                       :my.edit/source "(defn example [] nil)"
                       :example/extra :ignored))))
    (is (false? (owner/valid-form-operation?
                 (assoc base :my.edit/operation :replace
                        :my.edit/source "(defn example []"))))
    (is (true? (owner/valid-form-operation?
                (assoc base :my.edit/operation :delete))))
    (is (false? (owner/valid-form-operation?
                 (assoc base :my.edit/operation :delete
                        :my.edit/source "(def example 1)"))))
    (testing "the registered request remains open"
      (is (true? (schema/valid-candidate-value?
                  :my.edit/form-request
                  (assoc base :my.edit/operation :delete
                         :example/extra :ignored)))))))

(deftest public-entries-declare-the-single-io-handler
  (doseq [entry [#'edit/form! #'edit/exact! #'edit/lines!]]
    (is (= :io (:seon.workload (meta entry))))
    (is (= 'seon.edit.jvm/edit
           (:seon.effect/capability (meta entry))))))
