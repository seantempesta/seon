(ns seon.ui.header-test
  (:require [cljs.test :refer [deftest is]]
            [seon.ui.header :as header]
            [seon.ui.html :as html]))

(deftest header-renders-only-ordinary-projection-data
  (let [markup (html/->string
                (header/system-header
                 {::header/brand-name "test"
                  ::header/agent-count 4
                  ::header/running-count 2}))]
    (is (re-find #"id=\"system-header\"" markup))
    (is (re-find #"⛁ data" markup))))
