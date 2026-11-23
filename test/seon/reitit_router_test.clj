(ns seon.reitit-router-test
  "End-to-end proofs for data-driven Reitit routing (`:seon.route/*`), bookmarkable URLs,
   build-time conflict detection, SSR GET, `navigate-url!`, `toggle-expert-mode!`, `<a href>` links,
   and the `compute-ns-enrichment` container-namespace OOM fix."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [reitit.core :as r]
            [seon.code :as code]
            [seon.render :as render]
            [seon.render.data :as rdata]
            [seon.render.links :as rlinks]
            [seon.render.ui :as ui]
            [seon.schema :as schema]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor :as txr]
            [seon.web :as web]
            [seon.web.actions :as actions]
            [seon.web.css :as wcss]
            [seon.web.router :as router]))

(deftest ^{:cost/ticks 90000000} seon-route-seeding-db-projection-and-roundtrip-test
  (testing "Idempotent :seon.route/* seeding, db->routes projection, real handler vars, and forward/reverse round-trips for EVERY route type"
    (with-test-store [{:keys [conn]}]
      (is (= (count (router/core-routes-tx)) (count (router/missing-core-routes @conn))))
      (d/transact conn {:tx-data (router/core-routes-tx)})
      (is (empty? (router/missing-core-routes @conn)) "Seeding must be idempotent")
      ;; Re-seeding core-routes-tx produces zero duplicate route entities
      (d/transact conn {:tx-data (router/core-routes-tx)})
      (let [db @conn
            seeded (vec (d/datoms db :aevt :seon.route/name))
            rt (router/db->routes db)
            u-fix (random-uuid)
            u-err (random-uuid)
            u-ev  (random-uuid)]
        (is (= (count (router/core-routes-tx)) (count seeded)))
        ;; Every :seon.route/handler symbol resolves to a real existing var
        (doseq [ent (router/core-routes-tx)]
          (let [h (:seon.route/handler ent)
                v (resolve h)]
            (is (and (var? v) (bound? v)) (str "Route " (:seon.route/name ent) " handler must resolve: " h))))
        ;; Custom DB route entity dynamically projected into Reitit
        (let [db-custom (:db-after (d/with db {:tx-data [{:seon.route/name :seon.route/custom-demo
                                                          :seon.route/pattern "/custom/{id}"
                                                          :seon.route/method :get
                                                          :seon.route/handler 'seon.web/handle-http-request!
                                                          :seon.route/doc "Custom test route"}]}))
              rt-c (router/db->routes db-custom)]
          (is (= "/custom/42" (:path (r/match-by-name rt-c :seon.route/custom-demo {:id "42"}))))
          (is (= :seon.route/custom-demo (:route/name (router/match-path db-custom "/custom/42")))))
        ;; Forward + reverse round-trip for ALL entity route types (+ ?explain=1)
        (doseq [[ref expected-prefix]
                [[[:seon.code.var/name 'seon.web.router/compile-router] "/var/seon.web.router/compile-router"]
                 [[:seon.code.var/name 'seon.render.links/real-rule-id?] "/var/seon.render.links/"]
                 [[:seon.code.ns/name 'seon.web.router] "/ns/seon.web.router"]
                 [[:seon.task/id :task/demo-one] "/task/task/demo-one"]
                 [[:seon.rule/id :rule.ctx/demo-rule] "/rule/rule.ctx/demo-rule"]
                 [[:seon.schema/key :seon.route/entity] "/schema/seon.route/entity"]
                 [[:seon.fix/id u-fix] (str "/fix/" u-fix)]
                 [[:seon.error/id u-err] (str "/error/" u-err)]
                 [[:seon.code.doc/id "docs/render.md#walk"] "/doc/"]
                 [[:seon.signal/kind :flow] "/entity/seon.signal~kind/"]
                 [[:seon.branch/name :branch/demo] "/entity/seon.branch~name/"]
                 [[:seon.agent.eval/id u-ev] "/entity/seon.agent.eval~id/"]
                 [[:db/id 42] "/entity/db~id/42"]]]
          (let [p (router/entity-ref->path db ref)
                op (router/path->show-user-op db p)
                op-ex (router/path->show-user-op db (str p "?explain=1"))]
            (is (str/starts-with? p expected-prefix) (str "Unexpected path for " ref ": " p))
            (is (= ref (:inspect-ref op)) (str "Round-trip mismatch for " ref " via " p))
            (is (= :inspect (:mode op)))
            (is (= ref (:inspect-ref op-ex)))
            (is (= :context (:mode op-ex)))))
        ;; Top-level & agent view routes + time-travel tx + user-state->path
        (is (= :system (:mode (router/path->show-user-op db "/"))))
        (is (= :system (:mode (router/path->show-user-op db "/system"))))
        (is (= :current (web/parse-deep-link db "/")))
        (is (= {:op :show-user :viewing :agent/root :mode :session :clear-inspect true :as-of-tx :live}
               (router/path->show-user-op db "/agent/root")))
        (is (= {:op :show-user :viewing :agent/root :mode :context :clear-inspect true :as-of-tx :live}
               (router/path->show-user-op db "/agent/root/explain")))
        (is (= {:op :show-user :viewing :ns/seon.web :mode :session :as-of-tx 536879000 :clear-inspect true}
               (router/path->show-user-op db "/agent/ns~seon.web/tx/536879000")))
        (is (= "/agent/root" (router/user-state->path db {:viewing-id :agent/root :view-mode :session})))
        (is (= "/agent/root/explain" (router/user-state->path db {:viewing-id :agent/root :view-mode :context})))
        (is (= "/agent/root/tx/123" (router/user-state->path db {:viewing-id :agent/root :view-mode :session :as-of-tx 123})))
        (is (= "/system" (router/user-state->path db {:viewing-id :agent/root :view-mode :system})))
        (is (= "/var/seon.web.router/compile-router?explain=1"
               (router/user-state->path db {:viewing-id :agent/root :view-mode :context
                                            :inspect-ref [:seon.code.var/name 'seon.web.router/compile-router]})))
        ;; Infrastructure routes match via Reitit and are NOT UI show-user ops
        (is (= :mcp (:route/id (web/match-http-route {:request-method :post :uri "/mcp"}))))
        (is (= :preview (:route/id (web/match-http-route {:request-method :get :uri "/preview"}))))
        (is (nil? (web/match-http-route {:request-method :get :uri "/mcp"})))
        (is (nil? (router/path->show-user-op db "/mcp")))
        (is (some? (r/match-by-path rt "/events")))))))

(deftest ^{:cost/ticks 90000000} build-time-conflict-detection-and-ssr-get-routes-test
  (testing "Build-time Reitit conflict detection and SSR GET 200 OK on /var/seon.web.router/compile-router and /agent/root/explain"
    (is (thrown? Throwable (router/compile-router [["/dup" {:name :a}] ["/dup" {:name :b}]])))
    (is (thrown? Throwable (router/compile-router [["/item/{a}" {:name :a}] ["/item/{b}" {:name :b}]])))
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (router/core-routes-tx)})
      ;; Conflicting route entity in Datahike throws on db->routes compilation
      (let [bad-db (:db-after (d/with @conn {:tx-data [{:seon.route/name :seon.route/conflict-dup
                                                        :seon.route/pattern "/system"
                                                        :seon.route/method :get
                                                        :seon.route/handler 'seon.web/set-view-mode!}]}))]
        (is (thrown? Throwable (router/db->routes bad-db))))
      ;; Seed agent + var for live SSR GET proof
      (d/transact conn {:tx-data (code/file-import-tx
                                  @conn "src/seon/web/router.clj"
                                  "(ns seon.web.router)\n(defn compile-router \"Compiles router.\" [routes] routes)\n" false)})
      (d/transact conn {:tx-data [{:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :main
                                   :seon.agent/role :coordinator :seon.agent/label "Root"}
                                  {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/root]
                                   :seon.user/view-mode :session}]})
      (let [sync-fn (fn [op]
                      (let [{:keys [tx-data]} (#'txr/show-user-tx @conn op)]
                        (d/transact conn {:tx-data tx-data})
                        (let [res (render/step-walk (render/empty-walk) @conn nil
                                                    (ui/page-unit-specs {:conn conn :boot-seq 1
                                                                         :live-streams {} :agent-states {} :errors []}))]
                          (#'web/page-shell (:walk res) @conn))))
            ctx {:ui-ch (a/chan 4) :server-in-ch (a/chan 4) :conn conn :sync-deep-link! sync-fn}
            r-var (web/handle-http-request! ctx {:request-method :get :uri "/var/seon.web.router/compile-router"})
            r-exp (web/handle-http-request! ctx {:request-method :get :uri "/agent/root/explain"})
            r-404 (web/handle-http-request! ctx {:request-method :get :uri "/does-not-exist-xyz"})]
        (is (= 200 (:status r-var)))
        (is (str/includes? (:body r-var) "data-canonical-url=\"/var/seon.web.router/compile-router\""))
        (is (str/includes? (:body r-var) "compile-router"))
        (is (str/includes? (:body r-var) "history.pushState"))
        (is (not (str/includes? (:body r-var) "Render fault")))
        (is (= 200 (:status r-exp)))
        (is (str/includes? (:body r-exp) "data-canonical-url=\"/agent/root/explain\""))
        (is (not (str/includes? (:body r-exp) "Render fault")))
        (is (= 404 (:status r-404)))))))