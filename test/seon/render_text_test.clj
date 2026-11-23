(ns seon.render-text-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]))

(deftest ^{:cost/ticks 5000000} one-hiccup-to-text-single-source-test
  (testing "seon.render/hiccup->text, fit.measure/hiccup->text-fallback and print.emit/hiccup->md are ONE implementation via seon.render.text/hiccup->text"
    (let [tree [:div {:class "card"}
                [:h2 "Title"]
                [:p "Body " [:code "(+ 1 2)"]]
                [:button {:class "btn ui"} "CHROME"]
                [:button {:class "ent-link"} ":task/demo"]
                [:pre [:code "(inc 1)"]]
                [:table [:tr [:td "a"] [:td "b"]]]
                [:ul [:li "one"]]
                [seon.render/raw-html "<b>raw</b>"]
                ["tail"]]
          leaf (seon.render.text/hiccup->text tree)]
      (is (string? leaf))
      (is (= leaf (seon.render/hiccup->text tree)))
      (is (= leaf (seon.render.fit.measure/hiccup->text-fallback tree)))
      (is (= leaf (seon.render.print.emit/hiccup->md tree)))
      (is (str/includes? leaf ":task/demo"))
      (is (not (str/includes? leaf "CHROME")))
      (is (not (str/includes? leaf "raw")))
      (is (str/includes? leaf "## Title"))
      (is (str/includes? leaf "| a | b |"))
      (is (str/includes? leaf "- one")))))