(ns seon.ai.tokens-test
  (:require [clojure.test :refer [deftest is]]
            [seon.ai.tokens :as tokens]))

(deftest token-budget-derivations-share-one-character-ratio
  (is (= 12 (tokens/estimate-chars 3)))
  (is (= "abcdefghijkl…"
         (tokens/clip-str "abcdefghijklmnop" 3)))
  (is (= "short" (tokens/clip-str "short" 3)))
  (is (= "" (tokens/clip-str nil 0))))
