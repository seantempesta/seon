(ns seon.agent.fs-portable-test
  "Portable filesystem policy and frozen child-call contract tests."
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [deftest is testing]])
   [seon.agent.fs.core :as core]
   #?(:cljs [seon.agent.fs :as fs])))

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

#?(:cljs
   (deftest public-entry-effects-and-home-error
     (doseq [[v effect]
             [[#'fs/grants :read] [#'fs/read-file :read]
              [#'fs/write-file :external] [#'fs/edit-file :external]
              [#'fs/list-dir :read] [#'fs/stat :read]
              [#'fs/file-exists? :read] [#'fs/home-dir :read]
              [#'fs/walk-dir :read] [#'fs/view :read]
              [#'fs/replace! :external] [#'fs/insert! :external]]]
       (is (= effect (:seon.capability/effect (meta v)))
           (str (:name (meta v)) " effect")))
     (let [env (.-env js/process)
           home (aget env "HOME")
           profile (aget env "USERPROFILE")]
       (js-delete env "HOME")
       (js-delete env "USERPROFILE")
       (try
         (let [response (fs/home-dir)]
           (is (map? response))
           (is (false? (:seon.agent.fs/ok? response)))
           (is (re-find #":seon.config/fs-home-dir"
                        (:seon.error/message response))))
         (finally
           (if-some [value home] (aset env "HOME" value) (js-delete env "HOME"))
           (if-some [value profile]
             (aset env "USERPROFILE" value)
             (js-delete env "USERPROFILE")))))))
