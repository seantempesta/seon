(ns seon.route-test
  "Contract tests for `seon.route` — the `:seon.route/*` schema + the seeded
   core route set. These guard the CROSS-LANE interface the UI lane's
   `db->routes` (in `seon.web.router`) consumes: change a pattern/method/name
   here and the projection into reitit changes, so the set is pinned on
   purpose. Three layers: (1) the seed-set contract (names → pattern/method),
   (2) the malli→datahike bridge facets per attr, (3) a fresh :memory conn
   round-trip proving the rows transact, read back with a NATIVE symbol
   handler, and upsert idempotently on `:seon.route/name`."
  (:require
    [cljs.test :refer [deftest is async testing]]
    [datahike.api :as d]
    [seon.agent]
    [seon.agent.message]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.route :as route]
    [seon.test.async :refer [settle!]]))

(def ^:private expected
  "name → [pattern method] — the authoritative seeded core route set. `/`
   is the root agent view and the fleet roster lives at `/agents` +
   `/agents/feed`. The seed is `/` + the roster page + its `…/feed` stream +
   the per-agent page + its separate `…/feed` SSE stream + the one POST door."
  {:seon.route/root        ["/" :get]
   :seon.route/agents      ["/agents" :get]
   :seon.route/agents-feed ["/agents/feed" :get]
   :seon.route/agent       ["/agent/{id}" :get]
   :seon.route/agent-feed  ["/agent/{id}/feed" :get]
   :seon.route/agent-call  ["/agent/{id}/call" :post]})

(deftest seed-set-is-the-corrected-contract
  (let [rows  (route/core-routes-tx)
        by-nm (into {} (map (juxt :seon.route/name identity)) rows)]
    (is (= (set (keys expected)) (set (keys by-nm)))
        "exactly the core routes, no more, no fewer")
    (doseq [[nm [pattern method]] expected]
      (testing (str nm)
        (let [r (by-nm nm)]
          (is (= pattern (:seon.route/pattern r)))
          (is (= method (:seon.route/method r))))))
    (testing "the roster and per-agent feeds stay separate GET paths"
      (is (contains? by-nm :seon.route/agents))
      (is (contains? by-nm :seon.route/agents-feed))
      (is (contains? by-nm :seon.route/agent-feed)))
    (testing "the one action door is the per-agent POST /call (no per-ns/per-fn routes)"
      (is (= [:seon.route/agent-call]
             (mapv :seon.route/name (filter #(= :post (:seon.route/method %)) rows))))
      (is (= [:seon.route/same-origin]
             (:seon.route/middleware (by-nm :seon.route/agent-call)))))))

(deftest handlers-are-symbols-that-resolve
  (doseq [{:keys [:seon.route/name :seon.route/handler]} (route/core-routes-tx)]
    (testing (str name)
      (is (symbol? handler) "handler is a symbol-as-value (late-bound)")
      (is (qualified-symbol? handler))
      (is (some? (seval/lookup-value handler))
          "db->routes resolves it via eval/lookup-value at request time"))))

(deftest bridge-derives-the-data-model-facets
  (let [facet (fn [a] (-> (db/malli->datahike-schema [a]) first))]
    (is (= :db.type/string  (:db/valueType (facet :seon.route/pattern))))
    (is (= :db.type/keyword (:db/valueType (facet :seon.route/method))))
    (let [nm (facet :seon.route/name)]
      (is (= :db.type/keyword (:db/valueType nm)))
      (is (= :db.unique/identity (:db/unique nm)) "name is the reverse-routing identity"))
    (is (= :db.type/ref    (:db/valueType (facet :seon.route/owner))) "owner references the canonical ref shape")
    (is (= :db.type/symbol (:db/valueType (facet :seon.route/handler)))
        "handler is a NATIVE symbol, not an EDN string")
    (let [mw (facet :seon.route/middleware)]
      (is (= :db.type/keyword (:db/valueType mw)))
      (is (= :db.cardinality/many (:db/cardinality mw))))))

(defn- fresh-conn
  "Promise of a fresh :memory conn. `d/create-database`/`d/connect` is the
   scratch-store creation (no seon.db equivalent — same idiom as state_test);
   all data ops route through `seon.db`. No manual schema install needed:
   `db/transact!` auto-bridges + installs the route attrs (registered in
   `seon.route`) at first transact."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (db/ensure-provenance! {:seon.db/conn conn})
                     (.then (fn [_] conn))))))))

(deftest rows-round-trip-and-upsert-idempotently
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (-> (db/transact! {:seon.db/tx-data (route/core-routes-tx)
                                    :seon.db/conn    conn})
                     ;; seed a SECOND time — identity upsert on :seon.route/name
                     ;; must merge, never duplicate.
                     (.then (fn [_] (db/transact! {:seon.db/tx-data (route/core-routes-tx)
                                                   :seon.db/conn    conn})))
                     (.then
                       (fn [_]
                         (let [names (db/query {:seon.db/query '[:find [?n ...]
                                                                 :where [?e :seon.route/name ?n]]
                                                :seon.db/conn  conn})
                               h     (ffirst (db/query {:seon.db/query
                                                        '[:find ?h :where
                                                          [?e :seon.route/name :seon.route/agent]
                                                          [?e :seon.route/handler ?h]]
                                                        :seon.db/conn conn}))]
                           (is (= 6 (count names)) "six entities after a double seed — no duplicates")
                           (is (= (set (keys expected)) (set names)))
                           (is (symbol? h) "handler reads back as a native symbol")
                           (is (= 'seon.web.datastar/serve-agent-page! h))))))))
        (settle! done))))
