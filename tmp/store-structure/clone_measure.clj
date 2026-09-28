;; store-structure-opus: bytes, nodes and ms of one ordinary run commit on a branch of
;; the APFS clone of default's store, plus warm history-read ms, under whichever
;; Datahike this JVM loads (the fork's working tree, or its HEAD via -Sdeps).
;; Run: clojure -M tmp/store-structure/clone_measure.clj <label>
(require '[datahike.api :as d]
         '[seon.cluster.store :as store])
(load-file "/Users/sean/src/seon/tmp/store-structure/branch_probe.clj")
(load-file "/Users/sean/src/seon/tmp/store-structure/history_fingerprint.clj")
(intern 'user 'store-dir "/Users/sean/src/seon/tmp/store-structure/store-clone")
(intern 'user 'variants (array-map :current @(resolve 'user/current)))

(let [label (first *command-line-args*)
      cfg (-> (store/datahike-configuration @(resolve 'user/store-dir))
              (assoc :branch :cluster-default)
              (assoc-in [:store :id] #uuid "224560ae-b0ba-3d5c-b6db-6d7415ad66e7")
              (dissoc :keep-history? :fuse-index-roots? :index-config))
      conn (d/connect cfg)
      refs {:agent-e 51383 :ns-e 51397 :runtime-e 51386 :process "clone-probe"}
      fingerprint (resolve 'user/history-fingerprint)
      attrs [:seon.fn/form-span :seon.lint/row :seon.test.member/pass-count :seon.turn/closed-tx :seon.fn/calls]
      read! #(fingerprint @conn attrs [51383 51386] (- (:max-tx @conn) 200))
      _ (dotimes [_ 3] (read!))
      t0 (System/nanoTime)
      _ (dotimes [_ 5] (read!))
      read-ms (/ (- (System/nanoTime) t0) 5e6)
      rows ((resolve 'user/measure!) conn refs 10)]
  (prn {:label label :warm-history-read-ms read-ms :rows rows})
  (d/release conn)
  (shutdown-agents)
  (System/exit 0))
