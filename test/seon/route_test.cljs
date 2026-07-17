(ns seon.route-test
  "Contract tests for `seon.route` — the `:seon.route/*` schema + the seeded
   core route set. These guard the CROSS-LANE interface the UI lane's
   `db->routes` (in `seon.web.router`) consumes: change a pattern/method/name
   here and the projection into reitit changes, so the set is pinned on
   purpose. Two layers remain here: (1) the seed-set contract
   (names → pattern/method), and (2) the Malli→Datahike bridge facets per
   attribute. Authority admission tests own real Datahike publication."
  (:require
    [cljs.test :refer [deftest is testing]]
    [seon.agent]
    [seon.agent.message]
    [seon.db :as db]
    [seon.route :as route]))

(def ^:private expected
  "name → [pattern method] — the authoritative seeded core route set. `/`
   is the root agent and fleet view. The seed is `/` + the per-agent page +
   its separate `…/feed` SSE stream + the two POST action doors."
  {:seon.route/root        ["/" :get]
   :seon.route/agents-create ["/agents" :post]
   :seon.route/agent       ["/agent/{id}" :get]
   :seon.route/agent-feed  ["/agent/{id}/feed" :get]
   :seon.route/agent-debug ["/agent/{id}/debug" :get]
   :seon.route/agent-debug-feed ["/agent/{id}/debug/feed" :get]
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
    (testing "the per-agent feeds stay on separate GET paths"
      (is (contains? by-nm :seon.route/agent-feed))
      (is (contains? by-nm :seon.route/agent-debug-feed)))
    (testing "both state-changing doors are database routes and same-origin gated"
      (is (= #{:seon.route/agents-create :seon.route/agent-call}
             (into #{} (map :seon.route/name)
                   (filter #(= :post (:seon.route/method %)) rows))))
      (doseq [route-name [:seon.route/agents-create :seon.route/agent-call]]
        (is (= :seon.route/same-origin
               (:seon.route/middleware (by-nm route-name))))))))

(deftest handlers-are-qualified-symbol-data
  (doseq [{:keys [:seon.route/name :seon.route/handler]} (route/core-routes-tx)]
    (testing (str name)
      (is (symbol? handler) "handler is a symbol-as-value (late-bound)")
      (is (qualified-symbol? handler)))))

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
      (is (= :db.cardinality/one (:db/cardinality mw))))))
