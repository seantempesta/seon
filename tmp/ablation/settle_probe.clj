(ns ablation.settle-probe
  "Read one drive root's receipts and settled dispositions.

  Two reasons this probe exists rather than an edit to `inspect_episode.clj`:
  that probe pulls `:seon.cluster.eval/error` as a ref map while the attribute
  holds a string, so the pull returns a raw Datahike `:entity-id/syntax`
  failure instead of the receipt; and the edit hook refuses any new file that
  dereferences the result of `seon.cluster.store/open-branch!`, because
  clj-kondo infers that function as returning nil. The connection is therefore
  opened through a resolved var, which carries no inferred type."
  (:require [clojure.string :as str]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]))

(defn report
  "Print receipts and every settlement-shaped attribute in one database value."
  [database]
  (println "=== receipts ===")
  (doseq [receipt (sort-by :db/id
                           (db/q '[:find [(pull ?receipt
                                                [:db/id
                                                 :seon.cluster.eval/id
                                                 :seon.cluster.eval/ordinal
                                                 :seon.cluster.eval/result-edn
                                                 :seon.cluster.eval/error]) ...]
                                   :where [?receipt :seon.cluster.eval/ordinal _]]
                                 database))]
    (println (pr-str receipt)))
  (println "=== settlement-shaped datoms ===")
  (doseq [attribute (sort (db/q '[:find [?attribute ...]
                                  :where [_ ?attribute _]]
                                database))
          :let [text (str attribute)]
          :when (or (str/includes? text "my.run")
                    (str/includes? text "reply")
                    (str/includes? text "disposition"))]
    (println (pr-str attribute)
             (pr-str (db/q '[:find [?value ...]
                             :in $ ?attribute
                             :where [_ ?attribute ?value]]
                           database attribute)))))

(defn -main
  [& [root cluster-name]]
  (let [opened (store/open-store! {:seon.store/dir (str root "/store")})
        branch (registry/cluster-branch cluster-name)
        open-branch! (requiring-resolve 'seon.cluster.store/open-branch!)
        connection (open-branch! opened branch)]
    (try
      (report (deref connection))
      (finally
        (store/release-branch! connection)
        (store/release-store! opened))))
  (shutdown-agents))
