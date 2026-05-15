(ns seon.podhost.libdatahike.cljs-spike-idb
  "CLJS-2b: datahike with konserve.indexeddb under the `fake-indexeddb` Node
   polyfill — the path that would also work in a real browser.

   The `fake-indexeddb` polyfill is injected at build time via the
   `:prepend-js` in shadow-cljs.edn (`require('fake-indexeddb/auto')`),
   so `globalThis.indexedDB` is set up before any datahike CLJS code runs.

   This spike is single-process only — IDB is fundamentally browser-shaped
   and fake-indexeddb's in-memory state doesn't persist across Node processes
   without a custom backing store. For pod-side persistence-across-restarts
   use the `:file` backend (see cljs-spike-fs). The point of this spike is
   just: does the konserve.indexeddb code path work under Node, which is the
   prerequisite for stress-testing the same code path against real IDB later
   in a browser pod."
  (:require [datahike.api :as d]
            [konserve.indexeddb]  ;; side-effect: registers :indexeddb backend
            [cljs.core.async :refer [<!]])
  (:require-macros [cljs.core.async :refer [go]]))

;; konserve.indexeddb is async-only. datahike's persistent-sorted-set index
;; calls `konserve.core/get` synchronously, which IDB can't satisfy. Solution
;; (same shape upstream `dev/sandbox.cljs` uses): a `:tiered` store with a
;; `:memory` frontend (handles sync reads) and `:indexeddb` backend (async
;; persistence). This is also the real shape browser pods will use.
(def tiered-id #uuid "1d50b780-0000-0000-0000-00000000eddd")

(def cfg
  {:store              {:backend         :tiered
                        ;; Tiered store requires frontend :id == tiered :id.
                        :frontend-config {:backend :memory
                                          :id      tiered-id}
                        :backend-config  {:backend :indexeddb
                                          :name    "spike-idb-store"
                                          :id      tiered-id}
                        :id              tiered-id}
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

(defn main [& _args]
  (println "[spike-idb] indexedDB present?" (boolean (.-indexedDB js/globalThis)))
  (go
    (<! (d/create-database cfg))
    (let [conn (<! (d/connect cfg {:sync? false}))]
      (<! (d/transact! conn schema))
      (<! (d/transact! conn seed))
      (let [rows (query conn)
            ok?  (= rows expected)]
        (println "[spike-idb] query result:" (pr-str rows))
        (println (if ok? "[spike-idb] PASS — :indexeddb backend works under fake-indexeddb"
                         "[spike-idb] FAIL"))
        (when-not ok? (js/process.exit 1))))))
