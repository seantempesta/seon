(ns seon.sci.ctx-test
  (:require [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.sci.ctx :as ctx]))

(deftest fork-isolates-new-definitions
  (let [never-interrupt (constantly nil)
        fork-a (ctx/fork {:interrupt-fn never-interrupt})
        fork-b (ctx/fork {:interrupt-fn never-interrupt})]
    (sci/eval-string* fork-a "(def local-value 42)")
    (is (= 42 (sci/eval-string* fork-a "local-value")))
    (is (thrown-with-msg?
         Throwable #"Unable to resolve symbol: local-value"
         (sci/eval-string* fork-b "local-value")))))

(deftest interrupt-aware-string-functions-are-in-the-base
  (let [entries (atom 0)
        forked (ctx/fork {:interrupt-fn #(swap! entries inc)})]
    (is (= "bbb" (sci/eval-string*
                  forked
                  "(clojure.string/replace \"aaa\" #\"a\" \"b\")")))
    (is (pos? @entries))))
