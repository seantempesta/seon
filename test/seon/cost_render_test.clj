(ns seon.cost-render-test
  "The request cost's render pair: the AI face one line of data, the HTML face
  every member."
  (:require [clojure.test :refer [deftest is]]
            [seon.batch :as batch]))

(def ^:private cost
  {:seon.cost/ms 184.6 :seon.cost/alloc-mb 8.5 :seon.cost/calls 7303 :seon.cost/reads 45
   :seon.cost/transactions 1
   :seon.cost/jvm-window {:seon.cost/scope :jvm-window :seon.cost/attribution :includes-unrelated-work
                          :seon.cost/alloc-accuracy :approximate-counter :seon.cost/cpu-accuracy :os-counter
                          :seon.cost/alloc-mb 470.0}
   :seon.cost/step {:seon.cost/alloc-mb 88.4 :seon.cost/cpu-ms 54.9}
   :seon.cost/stages {:custody 95.9 :unattributed 9.3}})

(deftest request-cost-ai-face-is-ms-alloc-and-calls
  (is (= {:seon.cost/ms 184.6 :seon.cost/alloc-mb 8.5 :seon.cost/calls 7303}
         (batch/render-request-cost-ai cost)))
  (is (= {:seon.cost/ms 1.0} (batch/render-request-cost-ai {:seon.cost/ms 1.0 :seon.cost/reads 2}))))

(deftest request-cost-html-face-holds-every-member
  (let [text (set (filter string? (tree-seq coll? seq (batch/render-request-cost-html cost))))
        keys-of (fn keys-of [m] (mapcat (fn [[k v]] (cons k (when (map? v) (keys-of v)))) m))]
    (is (every? #(contains? text (str %)) (keys-of cost)))
    (is (contains? text "184.6"))))
