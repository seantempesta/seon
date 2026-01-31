(ns seon.web.reactive.actions-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.web.reactive.actions :as actions]))

(deftest parse-action-path-test
  (testing "parses valid action paths"
    (is (= {:seon.reactive/namespace 'seon.trading
            :seon.reactive/function 'create-order}
           (actions/parse-action-path "/action/seon.trading/create-order")))

    (is (= {:seon.reactive/namespace 'seon.web.demo
            :seon.reactive/function 'increment!}
           (actions/parse-action-path "/action/seon.web.demo/increment!"))))

  (testing "returns nil for invalid paths"
    (is (nil? (actions/parse-action-path "/action/seon.trading")))
    (is (nil? (actions/parse-action-path "/other/path")))
    (is (nil? (actions/parse-action-path "")))))

(deftest extract-signals-test
  (testing "converts string keys to keywords"
    (is (= {:symbol "AAPL" :quantity "100"}
           (actions/extract-signals {"symbol" "AAPL" "quantity" "100"}))))

  (testing "preserves keyword keys"
    (is (= {:symbol "AAPL"}
           (actions/extract-signals {:symbol "AAPL"}))))

  (testing "handles nil and non-map"
    (is (= {} (actions/extract-signals nil)))
    (is (= {} (actions/extract-signals "not a map")))))

(deftest resolve-action-test
  (testing "rejects non-seon namespaces"
    (is (nil? (actions/resolve-action 'clojure.core 'inc)))
    (is (nil? (actions/resolve-action 'user 'foo))))

  (testing "resolves valid seon namespace functions"
    ;; This namespace exists and has public functions
    (is (some? (actions/resolve-action 'seon.web.reactive.transform 'transform-hiccup)))))

(deftest action-handler-test
  (testing "ignores non-POST requests"
    (is (nil? (actions/action-handler {:request-method :get
                                        :uri "/action/seon.test/foo"}))))

  (testing "ignores non-action paths"
    (is (nil? (actions/action-handler {:request-method :post
                                        :uri "/other/path"}))))

  (testing "returns 404 for unknown action"
    (let [response (actions/action-handler {:request-method :post
                                             :uri "/action/seon.nonexistent/foo"
                                             :body {}})]
      (is (= 404 (:status response)))))

  (testing "returns 400 for invalid path"
    (let [response (actions/handle-action {:uri "/action/invalid"
                                            :body {}})]
      (is (= 400 (:status response))))))
