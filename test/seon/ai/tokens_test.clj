(ns seon.ai.tokens-test
  "Recurring acceptance for the one token sizer."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.ai.tokens :as tokens]))

(defn- text-of [characters] (str/join (repeat characters "x")))

(deftest token-budget-derivations-share-one-character-ratio
  (is (= 9 (tokens/estimate-chars 3)))
  (testing "a character count sizes through the same owner as a string"
    (is (= (tokens/estimate (text-of 4096))
           (tokens/estimate-of-characters 4096)))))
