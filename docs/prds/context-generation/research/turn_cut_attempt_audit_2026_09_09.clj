(require '[seon.cluster.store :as store]
         '[seon.cluster.registry :as registry]
         '[seon.db :as db]
         '[seon.test.arm :as arm])

(#'arm/initialize-contracts! "attempt-audit" [])

(let [held (store/open-store! {:seon.store/dir (first *command-line-args*)})]
  (try
    (prn {:seon.store/branches (registry/roster held)})
    (doseq [branch (sort (disj (registry/roster held) :db))]
      (let [_ (prn {:seon.store/auditing branch})
            connection (store/open-branch! held branch)]
        (try
          (let [attempts (db/q '[:find [?attempt ...]
                                 :where [?attempt :seon.ai.attempt/id]]
                               (db/db connection))]
            (when (:seon.error/kind attempts)
              (throw (ex-info "Attempt audit refused" attempts)))
            (prn {:seon.store/branch branch
                  :seon.test/attempt-count (count attempts)}))
          (finally (store/release-branch! connection)))))
    (finally (store/release-store! held))))
(shutdown-agents)
