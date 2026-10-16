;; Scratch probe: what Datahike branch!/merge!/delete-branch!/gc-storage do to a
;; :proximum secondary index (pinned fork 9846d3e). Never touches the Seon store.
;;   clojure -M:dev docs/research/agent-platform/2026-10-16/vector-db/vector_semantics.clj SCRATCH
(require '[datahike.api :as d] '[datahike.index.secondary :as sec]
         '[datahike.index.secondary.proximum] '[konserve.core :as k]
         '[proximum.writing :as pwr] '[datahike.versioning :as dv] '[clojure.pprint :as pp])
(def root (str (first *command-line-args*) "/sem-" (System/currentTimeMillis)))
(def prox-cfg {:backend :file :path (str root "/prox") :id (random-uuid)})
(def cfg {:store {:backend :file :path (str root "/dh") :id (random-uuid)}
          :schema-flexibility :read :keep-history? true})
(d/create-database cfg)
(def conn (d/connect cfg))
(d/transact conn [{:db/ident :doc/vec :db/cardinality :db.cardinality/one :db.secondary/only true}
                  {:db/ident :doc/name :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}])
(d/transact conn [{:db/ident :idx/vectors :db.secondary/type :proximum :db.secondary/attrs [:doc/vec]
                   :db.secondary/config {:dim 4 :distance :cosine :capacity 1000 :store-config prox-cfg}
                   :db.secondary/status :ready}])
(defn nearest [db v k]
  (mapv (fn [{:keys [entity-id distance]}] [(:doc/name (d/pull db [:doc/name] entity-id)) distance])
        (sec/-slice-ordered (get-in db [:secondary-indices :idx/vectors]) {:vector v :k k} nil nil :asc k)))
(d/transact conn [{:doc/name "a" :doc/vec [1.0 0.0 0.0 0.0]} {:doc/name "b" :doc/vec [0.0 1.0 0.0 0.0]}])
(def report (atom {}))
(defn note! [k v] (swap! report assoc k v))
(note! :primary-value-of-secondary-only
       (d/q '[:find ?v . :where [?e :doc/name "a"] [?e :doc/vec ?v]] @conn))
(note! :main-nearest-x (nearest @conn [1.0 0.1 0.0 0.0] 2))
(d/branch! conn :db :feature)
(def bconn (d/connect (assoc cfg :branch :feature)))
(d/transact bconn [{:doc/name "c" :doc/vec [0.0 0.0 1.0 0.0]}])
(note! :branch-nearest-z (nearest @bconn [0.0 0.0 1.0 0.0] 1))
(note! :main-nearest-z-before-merge (nearest @conn [0.0 0.0 1.0 0.0] 1))
;; Seon-style merge: carry the branch's datoms for new entities as tx-data.
(def branch-datoms (vec (d/datoms @bconn :eavt (d/q '[:find ?e . :where [?e :doc/name "c"]] @bconn))))
(note! :branch-datoms-carried (mapv (fn [dt] [(:a dt) (:v dt)]) branch-datoms))
(note! :merge-from-datoms
       (try (dv/merge! conn #{:feature} (mapv (fn [dt] [:db/add "c" (:a dt) (:v dt)]) branch-datoms))
            (nearest @conn [0.0 0.0 1.0 0.0] 1)
            (catch Throwable t {:error (ex-message t)})))
;; merge with the vector value supplied from the source (what merge needs to carry)
(dv/merge! conn #{:feature} [{:doc/name "c" :doc/vec [0.0 0.0 1.0 0.0]}])
(note! :main-nearest-z-after-value-merge (nearest @conn [0.0 0.0 1.0 0.0] 1))
(def pstore (pwr/connect-store-sync prox-cfg))
(note! :prox-branches-before-delete (k/get pstore :branches nil {:sync? true}))
(d/release bconn)
(d/delete-branch! conn :feature)
(note! :dh-branches-after-delete (d/branches conn))
(note! :prox-branches-after-delete (k/get pstore :branches nil {:sync? true}))
(note! :mmap-files (sort (map #(.getName ^java.io.File %) (.listFiles (java.io.File. (str root "/prox-mmap"))))))
(note! :gc-storage (try (count @(d/gc-storage conn (java.util.Date.))) (catch Throwable t (ex-message t))))
(note! :prox-branches-after-gc (k/get pstore :branches nil {:sync? true}))
(note! :mmap-files-after-gc (count (.listFiles (java.io.File. (str root "/prox-mmap")))))
(pp/pprint @report)
(shutdown-agents) (System/exit 0)
