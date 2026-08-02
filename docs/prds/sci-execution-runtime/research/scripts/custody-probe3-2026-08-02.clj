(require '[sci.core :as sci] '[seon.cluster] '[seon.db :as db] '[sci.interrupt] '[seon.cluster.registry])
(defn mk [] (let [ctx (sci/init {:namespaces {'clojure.core sci.interrupt/clojure-core}})]
              (doseq [n '[seon.cluster seon.db seon.cluster.registry]]
                (sci/add-namespace! ctx n (ns-interns n)))
              ctx))
(def a (mk)) (def b (mk))
(println "SHADOW-IN-A:" (sci/eval-string* a "(do (in-ns 'seon.db) (def q (fn [& _] :A-poisoned)) (in-ns 'user) (seon.db/q 1))"))
(println "B-UNAFFECTED:" (try (pr-str (sci/eval-string* b "seon.db/q")) (catch Throwable t (ex-message t))))
;; dangerous public surface reachable by symbol
(doseq [n '[seon.cluster seon.cluster.registry seon.cluster.store]]
  (require n)
  (println n "PUBLIC:" (sort (keys (ns-publics n)))))
