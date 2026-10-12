(ns seon.vocabulary-test
  (:require [clojure.test :refer [deftest is]]
            [seon.vocabulary :as vocabulary]))

(deftest living-prose-names-no-retired-word
  (is (= {} (:remaining (vocabulary/report (:living vocabulary/table))))
      "Living prose uses the Clojure, library and git words."))
