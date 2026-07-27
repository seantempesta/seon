(ns seon.dev.program-inventory-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.dev.program-inventory :as program-inventory]))

(deftest analyzer-inventory-keeps-vendored-dependencies-out-of-public-corpus
  (let [root (System/getProperty "user.dir")
        namespaces
        {'example.core
         {:defs
          {'public
           {:name 'example.core/public
            :fn-var true
            :line 1
            :file (str (io/file root "src/example/core.cljs"))}
           'private
           {:name 'example.core/private
            :fn-var true
            :private true
            :line 2
            :file (str (io/file root "src/example/core.cljs"))}}}
         'datahike.api
         {:defs
          {'query
           {:name 'datahike.api/query
            :fn-var true
            :line 1
            :file
            (str (io/file root
                          "reference-code/datahike/src/datahike/api.cljc"))}}}}
        inventory
        (program-inventory/analyzer-fn-inventory
         namespaces #{'example.core 'datahike.api})]
    (is (= ["example.core/public"]
           (:seon.dev.program-inventory/public-exports inventory)))
    (is (= ["example.core/private"]
           (:seon.dev.program-inventory/first-party-private inventory)))
    (is (= ["datahike.api/query"]
           (:seon.dev.program-inventory/internal-terminals inventory)))))
