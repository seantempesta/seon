(require '[seon.fn.analyzer :as an] '[seon.fn :as sfn])
(alter-var-root #'seon.fn.analyzer/cache-directory (constantly "tmp/falsify/kondo-cache"))
(let [a (an/analyze {::an/paths ["src"]})
      es (filter #(= :error (::an/level %)) (::an/findings a))]
  (println "errors with private cache:" (count es))
  (doseq [f (take 10 es)] (println (::an/filename f) (::an/row f) (::an/message f))))
