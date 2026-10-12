(ns seon.cost-acquire
  "Repeat acquisition and request measurements on the named running root."
  (:require [cheshire.core :as json]
            [seon.operator :as operator]))

(def fresh-code
  "(let [m (seon.profile/measure (fn [] (let [start (promise) requests (mapv (fn [_] (future @start (#'seon.mcp/eval-clj {:seon.mcp/code \"(+ 1 2)\" :seon.mcp/agent (str \"cost-acquire-\" (random-uuid)) :seon.mcp/cluster \"default\" :seon.mcp/mode \"sci\"}))) (range 2))] (deliver start true) (mapv deref requests))))] (assoc (select-keys m [:seon.profile/ms :seon.cost/jvm-window :seon.profile/ranked-by-self :seon.profile/called]) :requests (mapv (fn [r] (clojure.edn/read-string (second (first r)))) (:seon.profile/value m))))")

(def warm-code
  "(let [m (seon.profile/measure #(#'seon.mcp/eval-clj {:seon.mcp/code \"(+ 1 2)\" :seon.mcp/agent \"cost-acquire-probe\" :seon.mcp/cluster \"default\" :seon.mcp/mode \"sci\"}))] (assoc (select-keys m [:seon.profile/ms :seon.cost/jvm-window :seon.profile/ranked-by-self]) :calls (select-keys (:seon.profile/called m) '[seon.schema.datahike/compiled-storage seon.schema.datahike/storable-attribute-in? seon.schema.datahike/declared-storable? seon.schema.internal/reference-id seon.db/write-owned-values-error]) :request (clojure.edn/read-string (second (first (:seon.profile/value m))))))")

(defn -main
  "Print one concurrent fresh pair, or three successive requests."
  {:malli/schema [:=> [:cat :string [:enum "fresh" "warm"]] :nil]}
  [root phase]
  (let [code (case phase "fresh" fresh-code "warm" warm-code)
        form (str "(#'seon.mcp/eval-clj {:seon.mcp/code "
                  (json/generate-string code)
                  " :seon.mcp/agent \"root\" :seon.mcp/cluster \"default\" :seon.mcp/mode \"jvm\"})")]
    (dotimes [_ (if (= "warm" phase) 3 1)]
      (let [[texts] (operator/prepl-value! (operator/advertisement root "default") form 60000)]
        (doseq [text texts] (println text))))))

