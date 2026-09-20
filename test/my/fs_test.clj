(ns my.fs-test
  (:require [seon.fs :as owner]
            [clojure.test :refer [deftest is testing]]
            [seon.schema :as schema]
            [seon.db :as db]
            [seon.test-support :as support]))

(deftest relationship-predicates-require-one-declared-arm
  (testing "content is byte-honest and open to unrelated data"
    (is (true? (owner/content? {:my.fs/text "hello"
                             :example/extra :ignored})))
    (is (true? (owner/content? {:my.fs/bytes [0 255]})))
    (is (true? (owner/content? {:seon.blob/digest (apply str (repeat 64 "a"))})))
    (is (false? (owner/content? {})))
    (is (false? (owner/content? {:my.fs/text "hello"
                              :my.fs/bytes [104 101 108 108 111]}))))
  (testing "writes cannot request an unconditional overwrite"
    (is (true? (owner/write-precondition?
                {:my.fs/expected-absence? true
                 :example/extra :ignored})))
    (is (true? (owner/write-precondition?
                {:my.fs/expected-digest (apply str (repeat 64 "b"))})))
    (is (false? (owner/write-precondition? {})))
    (is (false? (owner/write-precondition?
                 {:my.fs/expected-absence? true
                  :my.fs/expected-digest (apply str (repeat 64 "b"))})))))
  (testing "the registered request maps remain open"
    (is (true? ((schema/projection-validator (schema/handed-projection) :my.fs/read-request) {:my.fs/path "src/example.clj"
                 :example/extra :ignored})))
    (is (true? ((schema/projection-validator (schema/handed-projection) :my.fs/write-request) {:my.fs/path "src/example.clj"
                 :my.fs/content {:my.fs/text "x" :example/extra :ignored}
                 :my.fs/precondition {:my.fs/expected-absence? true}
                 :example/extra :ignored}))))

(deftest public-entries-declare-resolvable-io-capabilities
  (support/with-database
    (fn [connection]
      (let [entries (db/q '[:find [(pull ?function [:seon.fn/sym :seon.fn/workload
                                                   :seon.effect/capability]) ...]
                            :where [?namespace :seon.ns/name my.fs]
                                   [?function :seon.fn/ns ?namespace]
                                   [?function :seon.fn/private? false]] @connection)]
        (is (seq entries))
        (doseq [entry entries]
          (is (= :io (:seon.fn/workload entry)))
          (let [handler (:seon.effect/capability entry)
                declaration (when handler
                              (db/pull @connection [:seon.fn/private? :seon.fn/spec]
                                       [:seon.fn/sym handler]))]
            (is (true? (:seon.fn/private? declaration)) (pr-str entry))
            (is (string? (:seon.fn/spec declaration)) (pr-str entry))))))))
