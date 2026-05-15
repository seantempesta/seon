(ns seon.podhost.libdatahike.cljs-spike-fs
  "CLJS-2a: datahike with konserve.node-filestore (:file) persistence under Node.

   Two-run persistence test:
   - First invocation (`node out/spike-fs.js init`): create-database, transact
     schema + entities, query, print result.
   - Second invocation (`node out/spike-fs.js read`): connect to existing store,
     query (no transacts), print same result.

   Same query result across both runs proves the on-disk persistence layer works.

   The store path defaults to /tmp/spike-fs-store but is overridable via env
   var SPIKE_FS_PATH (so the wasmer container in CLJS-3 can mount a host path
   via `--volume HOST:/data` and point this at `/data/spike`)."
  (:require [datahike.api :as d]
            [konserve.node-filestore]  ;; side-effect: registers :file backend
            [cljs.core.async :refer [<!]])
  (:require-macros [cljs.core.async :refer [go]]))

(defn- store-path []
  (or (some-> js/process .-env .-SPIKE_FS_PATH)
      "/tmp/spike-fs-store"))

(defn cfg []
  {:store              {:backend :file
                        :path    (store-path)
                        :id      #uuid "1d50b780-0000-0000-0000-00000000fa11"}
   :schema-flexibility :write
   :keep-history?      false})

(def schema
  [{:db/ident       :name
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string}
   {:db/ident       :rank
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/long}])

(def seed
  [{:name "Alpha"     :rank 1}
   {:name "Seon"     :rank 2}
   {:name "Datahike" :rank 3}])

(def expected
  #{["Alpha" 1] ["Seon" 2] ["Datahike" 3]})

(defn- query [conn]
  (d/q '[:find ?name ?rank
         :where
         [?e :name ?name]
         [?e :rank ?rank]]
       @conn))

(defn- run-init []
  (println "[spike-fs init] store-path:" (store-path))
  (go
    (<! (d/create-database (cfg)))
    (let [conn (<! (d/connect (cfg) {:sync? false}))]
      (<! (d/transact! conn schema))
      (<! (d/transact! conn seed))
      (let [rows (query conn)
            ok?  (= rows expected)]
        (println "[spike-fs init] query result:" (pr-str rows))
        (println (if ok? "[spike-fs init] PASS" "[spike-fs init] FAIL"))
        (when-not ok? (js/process.exit 1))))))

(defn- run-read []
  (println "[spike-fs read] store-path:" (store-path))
  (go
    (let [conn (<! (d/connect (cfg) {:sync? false}))
          rows (query conn)
          ok?  (= rows expected)]
      (println "[spike-fs read] query result:" (pr-str rows))
      (println (if ok? "[spike-fs read] PASS — persistence verified"
                       "[spike-fs read] FAIL")))))

(defn main [& args]
  (let [mode (first args)]
    (case mode
      "init" (run-init)
      "read" (run-read)
      (do (println "usage: node out/spike-fs.js [init|read]")
          (js/process.exit 2)))))
