(ns seon.dev.changed-test-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.dev.changed-test :as changed]))

(def manifest
  {:seon.dev.test.artifact/test-namespaces
   ['example.alpha-test 'example.beta-test 'unrelated-test]
   :seon.dev.test.artifact/resources
   [{:seon.dev.test.resource/path "src/example/alpha.cljs"
     :seon.dev.test.resource/namespace 'example.alpha
     :seon.dev.test.resource/cache-key ["alpha"]
     :seon.dev.test.resource/requires []}
    {:seon.dev.test.resource/path "src/example/beta.cljs"
     :seon.dev.test.resource/namespace 'example.beta
     :seon.dev.test.resource/cache-key ["beta"]
     :seon.dev.test.resource/requires ['example.alpha]}
    {:seon.dev.test.resource/path "test/example/alpha_test.cljs"
     :seon.dev.test.resource/namespace 'example.alpha-test
     :seon.dev.test.resource/cache-key ["alpha-test"]
     :seon.dev.test.resource/requires ['example.alpha]}
    {:seon.dev.test.resource/path "test/example/beta_test.cljs"
     :seon.dev.test.resource/namespace 'example.beta-test
     :seon.dev.test.resource/cache-key ["beta-test"]
     :seon.dev.test.resource/requires ['example.beta]}
    {:seon.dev.test.resource/path "test/unrelated_test.cljs"
     :seon.dev.test.resource/namespace 'unrelated-test
     :seon.dev.test.resource/cache-key ["unrelated"]
     :seon.dev.test.resource/requires []}]})

(deftest impact-follows-shadow-dependencies-reverse-transitively
  (is (= ['example.alpha-test 'example.beta-test]
         (:seon.dev.changed-test/test-namespaces
           (changed/impact manifest ["src/example/alpha.cljs"])))))

(deftest test-edit-selects-that-test
  (is (= ['example.beta-test]
         (:seon.dev.changed-test/test-namespaces
           (changed/impact manifest ["test/example/beta_test.cljs"])))))

(deftest unknown-cljs-path-widens-explicitly
  (let [result (changed/impact manifest ["src/example/new.cljs"])]
    (is (= (set (:seon.dev.test.artifact/test-namespaces manifest))
           (set (:seon.dev.changed-test/test-namespaces result))))
    (is (= :unknown-cljs-resource
           (get-in result [:seon.dev.changed-test/widening 0
                           :seon.dev.changed-test/reason])))))

(deftest shared-cljc-input-widens-even-when-the-resource-is-known
  (let [shared (assoc-in manifest
                         [:seon.dev.test.artifact/resources 0
                          :seon.dev.test.resource/path]
                         "src/example/alpha.cljc")
        result (changed/impact shared ["src/example/alpha.cljc"])]
    (is (= (set (:seon.dev.test.artifact/test-namespaces manifest))
           (set (:seon.dev.changed-test/test-namespaces result))))
    (is (= :shared-or-build-input
           (get-in result [:seon.dev.changed-test/widening 0
                           :seon.dev.changed-test/reason])))))
