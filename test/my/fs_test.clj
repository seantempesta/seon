(ns my.fs-test
  (:require [clojure.test :refer [deftest is testing]]
            [my.fs :as fs]
            [seon.schema :as schema]))

(deftest relationship-predicates-require-one-declared-arm
  (testing "content is byte-honest and open to unrelated data"
    (is (true? (fs/content? {:my.fs/text "hello"
                             :example/extra :ignored})))
    (is (true? (fs/content? {:my.fs/bytes [0 255]})))
    (is (true? (fs/content? {:seon.blob/digest (apply str (repeat 64 "a"))})))
    (is (false? (fs/content? {})))
    (is (false? (fs/content? {:my.fs/text "hello"
                              :my.fs/bytes [104 101 108 108 111]}))))
  (testing "writes cannot request an unconditional overwrite"
    (is (true? (fs/write-precondition?
                {:my.fs/expected-absence? true
                 :example/extra :ignored})))
    (is (true? (fs/write-precondition?
                {:my.fs/expected-digest (apply str (repeat 64 "b"))})))
    (is (false? (fs/write-precondition? {})))
    (is (false? (fs/write-precondition?
                 {:my.fs/expected-absence? true
                  :my.fs/expected-digest (apply str (repeat 64 "b"))})))))
  (testing "the registered request maps remain open"
    (is (true? (schema/valid-candidate-value?
                :my.fs/read-request
                {:my.fs/path "src/example.clj"
                 :example/extra :ignored})))
    (is (true? (schema/valid-candidate-value?
                :my.fs/write-request
                {:my.fs/path "src/example.clj"
                 :my.fs/content {:my.fs/text "x" :example/extra :ignored}
                 :my.fs/precondition {:my.fs/expected-absence? true}
                 :example/extra :ignored}))))

(deftest public-entries-declare-one-io-capability
  (doseq [[entry handler]
          [[#'fs/read 'seon.fs.jvm/read]
           [#'fs/write 'seon.fs.jvm/write]
           [#'fs/glob 'seon.fs.jvm/glob]
           [#'fs/stat 'seon.fs.jvm/stat]]]
    (is (= :io (:seon.workload (meta entry))))
    (is (= handler (:seon.effect/capability (meta entry))))))
