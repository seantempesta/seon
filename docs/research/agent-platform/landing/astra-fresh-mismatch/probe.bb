(require '[seon.operator :as operator])
(let [root (.getCanonicalPath (clojure.java.io/file "tmp/astra-fresh-mismatch/root"))
      ad (operator/advertisement root "default")]
  (prn (if ad (operator/prepl-value! ad (slurp (first *command-line-args*)) 60000) {:advertisement ad})))
