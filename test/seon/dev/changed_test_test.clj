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

(deftest shared-cljc-input-uses-the-shadow-graph-when-known
  (let [shared (assoc-in manifest
                         [:seon.dev.test.artifact/resources 0
                          :seon.dev.test.resource/path]
                         "src/example/alpha.cljc")
        result (changed/impact shared ["src/example/alpha.cljc"])]
    (is (= #{'example.alpha-test 'example.beta-test}
           (set (:seon.dev.changed-test/test-namespaces result))))
    (is (empty? (:seon.dev.changed-test/widening result)))))

(deftest clj-macro-change-seeds-the-existing-shadow-graph
  (let [with-macro (update-in manifest
                              [:seon.dev.test.artifact/resources 1
                               :seon.dev.test.resource/requires]
                              conj 'example.macro)
        host-selection
        {:seon.dev.changed-test/host-namespaces #{'example.macro}
         :seon.dev.changed-test/host-graph
         {:seon.dev.changed-test/path->namespace
          {"src/example/macro.clj" 'example.macro}}}
        plan (changed/shadow-plan with-macro host-selection
                                  ["src/example/macro.clj"])
        result (changed/impact with-macro
                               ["src/example/macro.clj"]
                               (:seon.dev.changed-test/shadow-seeds plan))]
    (is (= ["src/example/macro.clj"]
           (:seon.dev.changed-test/shadow-paths plan)))
    (is (= #{'example.macro}
           (:seon.dev.changed-test/shadow-seeds plan)))
    (is (= ['example.beta-test]
           (:seon.dev.changed-test/test-namespaces result)))))

(deftest missing-shadow-graph-treats-source-clj-as-a-possible-macro
  (is (true? (changed/potential-shadow-input? "src/example/macros.clj")))
  (is (false? (changed/potential-shadow-input? "test/example/tool_test.clj"))))

(deftest maintained-reference-sources-are-not-root-runtime-inputs
  (is (false?
        (changed/root-runtime-path?
          "reference-code/datahike/src/datahike/query.cljc")))
  (is (true? (changed/root-runtime-path? "src/seon/db.cljs")))
  (is (true? (changed/root-runtime-path? "test/seon/db_test.cljs"))))

(deftest host-impact-follows-each-retained-runner-graph
  (let [host-result
        {:seon.dev.changed-test/host-status :available
         :seon.dev.changed-test/host-graph
         {:seon.dev.changed-test/path->namespace
          {"src/shared.cljc" 'example.shared
           "script/seon/dev/tool.clj" 'example.tool}
          :seon.dev.changed-test/requires
          {'example.writer-test #{'example.shared}
           'example.tool-test #{'example.tool}}
          :seon.dev.changed-test/operator-tests #{'example.tool-test}
          :seon.dev.changed-test/writer-tests #{'example.writer-test}}}]
    (is (= ['example.writer-test]
           (:seon.dev.changed-test/writer-tests
            (changed/host-impact host-result ["src/shared.cljc"]))))
    (is (= ['example.tool-test]
           (:seon.dev.changed-test/operator-tests
            (changed/host-impact host-result
                                 ["script/seon/dev/tool.clj"]))))))

(deftest missing-host-analysis-widens-only-relevant-runners
  (let [unavailable {:seon.dev.changed-test/host-status :unavailable
                     :seon.dev.changed-test/reason "missing"}]
    (is (= :all
           (:seon.dev.changed-test/operator-tests
            (changed/host-impact unavailable
                                 ["script/seon/dev/tool.clj"]))))
    (is (= :all
           (:seon.dev.changed-test/writer-tests
            (changed/host-impact unavailable
                                 ["src/seon/db/writer.clj"]))))
    (is (empty?
         (:seon.dev.changed-test/writer-tests
          (changed/host-impact unavailable
                               ["script/seon/dev/tool.clj"]))))))

(deftest failure-feedback-keeps-actionable-values-and-bounds-the-index
  (let [failure (fn [n]
                  (str "FAIL in (example-" n ") (example_test.cljs:10)\n"
                       "expected: (= " n " 1)\n"
                       "  actual: (not (= " n " 1))\n\n"))
        excerpts (changed/failure-excerpts
                   (str (failure 1) (failure 2) (failure 3)))]
    (is (= 2 (count excerpts)))
    (is (every? #(and (re-find #"expected:" %)
                      (re-find #"actual:" %))
                excerpts))))
