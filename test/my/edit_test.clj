(ns my.edit-test
  (:require [seon.edit :as owner]
            [my.edit :as edit]
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

(deftest text-replaces-one-exact-occurrence
  (let [source "a OLD\nkeep\nb OLD\n"
        one edit/text]
    (is (= "a OLD\nkeep\nb NEW\n" (one source "b OLD" "b NEW")))
    (let [none (one source "missing" "x")
          many (one source "OLD" "NEW")]
      (is (= :my.edit/no-match-error (:seon.error/type none)))
      (is (= [] (get-in none [:seon.error/data :seon.edit/lines])))
      (is (= :my.edit/ambiguous-match-error (:seon.error/type many)))
      (is (= [1 3] (get-in many [:seon.error/data :seon.edit/lines]))))))

(deftest text-rejects-empty-search-before-entry
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)]
       (doseq [[source operation]
               [["(my.edit/text \"source\" \"\" \"replacement\")" 'my.edit/text]
                ["(seon.edit/text \"source\" \"\" \"replacement\")" 'seon.edit/text]
                ["(my.edit/text \"\" \"\" \"replacement\")" 'my.edit/text]
                ["(seon.edit/text \"\" \"\" \"replacement\")" 'seon.edit/text]]]
         (let [refusal (support/agent-value ctx source)]
           (is (= :input (:seon.instrument/check refusal)))
           (is (= operation (:seon.instrument/fn refusal)))))))))

(deftest public-entries-declare-resolvable-io-capabilities
  (support/with-database
    (fn [connection]
      (let [entries (db/q '[:find [(pull ?function [:seon.fn/sym :seon.fn/workload
                                                   :seon.effect/capability]) ...]
                            :where [?namespace :seon.ns/name my.edit]
                                   [?function :seon.fn/ns ?namespace]
                                   [?function :seon.fn/private? false]] @connection)]
        (is (seq entries))
        (doseq [entry (remove #(= 'my.edit/text (:seon.fn/sym %)) entries)] ; text is pure
          (is (= :io (:seon.fn/workload entry)))
          (let [handler (:seon.effect/capability entry)
                declaration (when handler
                              (db/pull @connection [:seon.fn/private? :seon.fn/spec]
                                       [:seon.fn/sym handler]))]
            (is (true? (:seon.fn/private? declaration)))
            (is (string? (:seon.fn/spec declaration)))))))))
