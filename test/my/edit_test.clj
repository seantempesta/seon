(ns my.edit-test
  (:require [seon.edit :as owner]
            [clojure.test :refer [deftest is testing]]
            [seon.schema :as schema]
            [seon.db :as db]
            [seon.test-support :as support]))

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
      (is (true? ((schema/projection-validator (schema/handed-projection) :my.edit/form-request) (assoc base :my.edit/operation :delete
                         :example/extra :ignored)))))))

(deftest public-entries-declare-resolvable-io-capabilities
  (support/with-database
    (fn [connection]
      (let [entries (db/q '[:find [(pull ?function [:seon.fn/sym :seon.fn/workload
                                                   :seon.effect/capability]) ...]
                            :where [?namespace :seon.ns/name my.edit]
                                   [?function :seon.fn/ns ?namespace]
                                   [?function :seon.fn/private? false]] @connection)]
        (is (seq entries))
        (doseq [entry entries]
          (is (= :io (:seon.fn/workload entry)))
          (let [handler (:seon.effect/capability entry)
                declaration (when handler
                              (db/pull @connection [:seon.fn/private? :seon.fn/spec]
                                       [:seon.fn/sym (str handler)]))]
            (is (true? (:seon.fn/private? declaration)) (pr-str entry))
            (is (string? (:seon.fn/spec declaration)) (pr-str entry))))))))
