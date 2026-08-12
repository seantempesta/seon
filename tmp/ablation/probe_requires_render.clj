(ns ablation.probe-requires-render
  "Compare the namespace-requires query's fact count with its rendered value.

  The recovered W1 opening history shows this query rendering as ONE namespace
  followed by `;; 28 definitions omitted by the namespace render budget.` The
  probe checks how many namespaces actually carry `:seon.ns/requires`, so the
  omission of whole collection members can be told apart from the per-namespace
  definition budget."
  (:require [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]))

(defn -main
  "Print the namespace count for the requires query in one drive root."
  [& [root cluster-name]]
  (let [opened (store/open-store! {:seon.store/dir (str root "/store")})
        branch (registry/cluster-branch cluster-name)
        connection (store/open-branch! opened branch)]
    (try
      (let [database (deref connection)]
        (println "namespaces with :seon.ns/requires ="
                 (db/q '[:find (count ?entity) .
                         :where [?entity :seon.ns/requires _]]
                       database)))
      (finally
        (store/release-branch! connection)
        (store/release-store! opened))))
  (shutdown-agents))
