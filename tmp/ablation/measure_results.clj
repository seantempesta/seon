(ns ablation.measure-results
  "Print the four recorded result rows as one comparable EDN vector."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private order [:full :half :quarter :floor])

(defn -main
  "Print the four variant result rows in experiment order."
  [& _]
  (println
   (pr-str
    (mapv (fn [variant]
            (let [file (io/file "tmp/ablation/results"
                                (str (name variant) ".edn"))]
              (if (.isFile file)
                (edn/read-string (slurp file))
                {:minimum-context.result/variant variant
                 :minimum-context.result/missing? true})))
          order))))
