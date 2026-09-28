;; store-structure-opus: the changed Datahike fork reads an APFS clone of default's
;; store and prints the :cluster-default head commit and its history fingerprint.
;; Run: clojure -M tmp/store-structure/clone_proof.clj
(require '[datahike.api :as d]
         '[seon.cluster.store :as store])
(load-file "/Users/sean/src/seon/tmp/store-structure/history_fingerprint.clj")

(def clone "/Users/sean/src/seon/tmp/store-structure/store-clone")
(def attrs [:seon.program/analyzed-source-digest :seon.fn/form-span :seon.lint/row
            :seon.program/definition-digest :seon.test.member/pass-count :seon.db.vector/position
            :seon.schema/form :seon.turn/closed-tx :seon.fn/calls :seon.runtime/turns :seon.agent/namespace])
(def entities [51383 51386 51397])

(let [cfg (-> (store/datahike-configuration clone)
              (assoc :branch :cluster-default)
              ;; the clone carries default's store identity; this JVM opens nothing else
              (assoc-in [:store :id] #uuid "224560ae-b0ba-3d5c-b6db-6d7415ad66e7")
              (dissoc :keep-history? :fuse-index-roots? :index-config))
      conn (d/connect cfg)
      db @conn
      t (- (:max-tx db) 200)
      fingerprint (resolve 'user/history-fingerprint)
      t0 (System/nanoTime)
      fp (fingerprint db attrs entities t)]
  (prn {:commit (d/commit-id db) :max-tx (:max-tx db) :t t
        :fingerprint fp :ms (/ (- (System/nanoTime) t0) 1e6)})
  (d/release conn)
  (shutdown-agents)
  (System/exit 0))
