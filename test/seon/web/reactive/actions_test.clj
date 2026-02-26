(ns seon.web.reactive.actions-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.web.reactive.actions :as actions]))

(deftest resolve-action-test
  (testing "rejects non-seon namespaces"
    (is (nil? (actions/resolve-action 'clojure.core 'inc)))
    (is (nil? (actions/resolve-action 'user 'foo))))

  (testing "resolves valid seon namespace functions"
    ;; This namespace exists and has public functions
    (is (some? (actions/resolve-action 'seon.web.reactive.transform 'transform-hiccup)))))
