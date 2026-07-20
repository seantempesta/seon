(ns probe.dbg
  "Isolated async-gap debug entry."
  (:require [sci.core :as sci]))

(defn -main [& _]
  (let [ctx (sci/init {:classes {'js js/globalThis :allow :all}})
        r2 (sci/eval-string* ctx
             "(defn ^:async slow-inc [x]
                (let [v (await (js/Promise.resolve x))]
                  (inc v)))
              (slow-inc 41)")
        r3 (sci/eval-string* ctx "(js/Promise.resolve {:ok true})")]
    (println "r2:" (pr-str r2) "type:" (pr-str (type r2)) "promise?" (instance? js/Promise r2))
    (println "r3:" (pr-str r3) "promise?" (instance? js/Promise r3))
    (when (instance? js/Promise r2)
      (.then r2 (fn [v] (println "r2 resolved:" (pr-str v)) (js/process.exit 0))))
    (js/setTimeout (fn [] (js/process.exit 0)) 1500)))

(set! *main-cli-fn* -main)
