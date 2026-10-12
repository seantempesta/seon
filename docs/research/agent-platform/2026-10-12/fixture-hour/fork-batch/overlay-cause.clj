(require 'clojure.stacktrace '[datahike.api :as d] '[clojure.java.io :as io])
(let [root (str (java.nio.file.Files/createTempDirectory "overlay-cause" (make-array java.nio.file.attribute.FileAttribute 0)))
      cfg {:store {:backend :file :path (str root "/base") :id (random-uuid)} :keep-history? true :schema-flexibility :write}]
  (d/create-database cfg)
  (let [base (d/connect cfg)
        overlay (assoc cfg :store {:backend :datahike/overlay :id (get-in cfg [:store :id]) :overlay (random-uuid) :backend-config (:store cfg) :frontend-config {:backend :file :id (random-uuid) :path (str root "/front")}})]
    (try
      (d/transact base [{:db/ident :m/n :db/valueType :db.type/long :db/cardinality :db.cardinality/one} {:m/n 1}])
      (let [a (d/connect overlay)]
        (try
          (println :connected)
          (d/branch! a :db :member)
          (println :branched)
          (let [member (d/connect (assoc overlay :branch :member))]
            (try (println :member-opened) (finally (d/release member))))
          (catch Throwable t (clojure.stacktrace/print-cause-trace t))
          (finally (d/release a))))
      (finally (d/delete-database overlay) (d/release base) (d/delete-database cfg)))))
(shutdown-agents)
