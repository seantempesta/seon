(require '[seon.fn.analyzer :as an] '[seon.fn :as sfn])
(println "roots" sfn/source-roots)
(let [a (an/analyze {::an/paths (vec sfn/source-roots)})
      es (filter #(= :error (::an/level %)) (::an/findings a))]
  (println "errors" (count es))
  (doseq [f (take 20 es)] (println (::an/filename f) (::an/row f) (::an/message f)) ))
