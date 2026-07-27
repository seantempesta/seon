(ns seon.agent.fs-portable-test
  "Portable filesystem policy and frozen child-call contract tests."
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [deftest is testing]])
   [seon.agent.fs.core :as core]))

(deftest portable-request-and-response-policy
  (testing "line paging preserves the child response vocabulary"
    (is (= {:seon.agent.fs/content "b\nc"
            :seon.agent.fs/from-line 2
            :seon.agent.fs/lines-returned 2
            :seon.agent.fs/total-lines 3}
           (core/page-lines "a\nb\nc\n" 2 5))))
  (testing "home absence is a flat steering error"
    (let [response (core/home-response nil "")]
      (is (false? (:seon.agent.fs/ok? response)))
      (is (= :user-input (:seon.error/kind response)))
      (is (= :seon.config/fs-home-dir
             (get-in response [:seon.error/data :seon.config/key])))))
  (testing "writes decide from ordinary grant data"
    (is (true? (:seon.agent.fs/ok?
                (core/write-decision {:seon.agent.fs/path "/allowed/a"
                                      :seon.agent.fs/read-only? false
                                      :seon.agent.fs/in-scope? true}))))))
