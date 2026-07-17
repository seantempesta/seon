(ns seon.eval.print-capture-test
  "Per-fiber print capture through the production AsyncLocalStorage owner."
  (:require
   [clojure.string :as str]
   [cljs.test :refer [async deftest is]]
   [seon.eval :as eval]))

(defn- captured-print [label delay-ms]
  (let [bucket (atom "")]
    (.run eval/print-als bucket
          (fn []
            (println (str label "-before"))
            (-> (js/Promise.
                 (fn [resolve _] (js/setTimeout resolve delay-ms)))
                (.then (fn []
                         (println (str label "-after"))
                         @bucket)))))))

(deftest overlapping-print-scopes-remain-isolated
  (async done
    (eval/install-print-dispatcher!)
    (-> (js/Promise.all
         #js [(captured-print "AAA" 40)
              (captured-print "BBB" 5)])
        (.then
         (fn [outputs]
           (let [a (aget outputs 0)
                 b (aget outputs 1)]
             (is (str/includes? a "AAA-before"))
             (is (str/includes? a "AAA-after"))
             (is (not (str/includes? a "BBB")))
             (is (str/includes? b "BBB-before"))
             (is (str/includes? b "BBB-after"))
             (is (not (str/includes? b "AAA"))))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
