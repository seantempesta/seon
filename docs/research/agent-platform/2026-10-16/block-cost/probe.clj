;; Run through MCP eval_clj, mode jvm, agent root, cluster default.
;; Each request records its evaluation on main. Run this form twice to compare
;; different main commits with the same program and chain commit.
(let [main (seon.db/db (seon.cluster.boot/connection "default"))
      database (seon.db/branch-database main :chain-b7ab51e090d5)]
  {:main-commit (seon.db/commit-id main)
   :main-program (seon.test.runner/program-content-digest main)
   :branch-commit (seon.db/commit-id database)
   :calls
   (mapv
    (fn [_]
      (let [measured (seon.profile/measure
                      #(seon.context/current-state database "attempt/cec65a8d91f7"))]
        (assoc (select-keys measured
                            [:seon.profile/ms :seon.profile/alloc-bytes
                             :seon.profile/calls :seon.profile/reads])
               :kondo (seon.profile/calls-of measured 'seon.fn.analyzer/invoke-kondo)
               :preview (seon.profile/calls-of measured 'seon.db/with))))
    (range 2))})
