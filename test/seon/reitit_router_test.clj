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

(deftest ^{:cost/ticks 90000000} callable-navigation-expert-toggle-and-bookmarkable-links-test
  (testing "navigate-url!, toggle-expert-mode!, Everyday vs Expert header, real <a href> on ent-link & sidebar, and url-sync-js"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (router/core-routes-tx)})
      (d/transact conn {:tx-data [{:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :main
                                   :seon.agent/role :coordinator :seon.agent/label "Root"}
                                  {:seon.agent/id :ns/seon.web :seon.agent/parent :agent/root :seon.agent/branch :branch/web
                                   :seon.agent/role :worker :seon.agent/label "Web"}
                                  {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/root]
                                   :seon.user/view-mode :session :seon.user/sidebar-open? true
                                   :seon.user/expanded-agents #{:agent/root}}]})
      (let [st {:conn conn}
            run-call! (fn [form]
                        (let [res (actions/invoke-call! st {:call-form form})]
                          (is (:ok? res) (str "invoke-call! failed: " (pr-str res)))
                          (when-let [req (get-in res [:async :req])]
                            (case (:op req)
                              :show-user (d/transact conn {:tx-data (:tx-data (#'txr/show-user-tx @conn req))})
                              :transact  (d/transact conn {:tx-data (:tx-data req)})
                              nil))
                          res))]
        ;; 1. navigate-url! updates :user/default via Reitit
        (run-call! '(seon.web/navigate-url! "/var/seon.web.router/compile-router"))
        (is (= [:seon.code.var/name 'seon.web.router/compile-router] (:inspect-ref (ui/user-view-state @conn))))
        (is (= :inspect (:view-mode (ui/user-view-state @conn))))
        (run-call! '(seon.web/navigate-url! "/var/seon.web.router/compile-router?explain=1"))
        (is (= :context (:view-mode (ui/user-view-state @conn))))
        (run-call! '(seon.web/navigate-url! "/agent/ns~seon.web/tx/536879000"))
        (is (= :ns/seon.web (:viewing-id (ui/user-view-state @conn))))
        (is (= 536879000 (:as-of-tx (ui/user-view-state @conn))))
        (run-call! '(seon.web/navigate-url! "/agent/root"))
        (is (= :agent/root (:viewing-id (ui/user-view-state @conn))))
        (is (nil? (:as-of-tx (ui/user-view-state @conn))))

        ;; 2. toggle-expert-mode! toggles :seon.user/expert-mode? & header telemetry strip
        (is (false? (:expert-mode? (ui/user-view-state @conn))))
        (let [h-day (render/hiccup->html (ui/header-unit @conn {:boot-seq 1 :agent-states {}}))]
          (is (str/includes? h-day "data-expert-mode=\"false\""))
          (is (str/includes? h-day "data-canonical-url=\"/agent/root\""))
          (is (not (str/includes? h-day "Pressure"))))
        (run-call! '(seon.web/toggle-expert-mode!))
        (is (true? (:expert-mode? (ui/user-view-state @conn))))
        (let [h-exp (render/hiccup->html (ui/header-unit @conn {:boot-seq 1 :agent-states {}}))]
          (is (str/includes? h-exp "data-expert-mode=\"true\""))
          (is (str/includes? h-exp "Flow"))
          (is (str/includes? h-exp "Pressure"))
          (is (str/includes? h-exp "Health")))
        (run-call! '(seon.web/toggle-expert-mode!))
        (is (false? (:expert-mode? (ui/user-view-state @conn))))

        ;; 3. Real <a href> on ent-link & sidebar agent links + clean hiccup->text + url-sync-js
        (let [el-v (rlinks/ent-link [:seon.code.var/name 'seon.web.router/compile-router] "compile-router" "mono")
              el-t (rlinks/ent-link [:seon.task/id :task/demo] ":task/demo" "pill")
              el-html (render/hiccup->html el-v)
              sb-html (render/hiccup->html (ui/sidebar-unit @conn {:agent-states {}}))]
          (is (= :a (first el-v)))
          (is (= "/var/seon.web.router/compile-router" (:href (second el-v))))
          (is (str/includes? el-html "<a "))
          (is (str/includes? el-html "href=\"/var/seon.web.router/compile-router\""))
          (is (str/includes? el-html "data-ref="))
          (is (= "compile-router :task/demo" (render/hiccup->text [:div el-v el-t])))
          (is (str/includes? sb-html "href=\"/agent/root\""))
          (is (str/includes? sb-html "href=\"/agent/ns~seon.web\""))
          (is (str/includes? sb-html "class=\"tree-select nav-link\""))
          (doseq [needle ["MutationObserver" "data-canonical-url" "history.pushState"
                          "popstate" "seon.web/navigate-url!" "a.ent-link[href],a.nav-link[href]"]]
            (is (str/includes? wcss/url-sync-js needle) (str "url-sync-js missing " needle))))))))

(deftest ^{:cost/ticks 120000000} container-ns-scope-enrichment-oom-regression-test
  (testing "compute-ns-enrichment on container namespace 'seon (0 direct vars, child namespaces) stays < 20 MB with 0 static alerts, and seed=42 generative checks pass"
    (with-test-store [{:keys [conn]}]
      ;; Seed root container 'seon with 12 child namespaces containing vars
      (d/transact conn {:tx-data (code/ns-parent-chain-tx 'seon)})
      (doseq [i (range 12)]
        (let [ns-name (str "seon.mod" i)
              path (str "src/seon/mod" i ".clj")
              src (str "(ns " ns-name " \"Child module " i ".\")\n"
                       "(defn f" i " \"Fn " i ".\" [x] (inc x))\n"
                       "(defn g" i " \"Fn g" i ".\" [x] (f" i " x))\n")]
          (d/transact conn {:tx-data (code/file-import-tx @conn path src false)})))
      (let [db @conn
            ns-ent (d/pull db '[:db/id :seon.code.ns/name :seon.code.ns/doc :seon.code.ns/path] [:seon.code.ns/name 'seon])
            worker {:seon.agent/id :agent/w-seon :seon.agent/role :worker :seon.agent/scope 'seon}
            alloc-fn (try (requiring-resolve 'jolt.host/bytes-allocated) (catch Throwable _ nil))
            b0 (if alloc-fn (long (alloc-fn)) 0)
            res (#'rlinks/compute-ns-enrichment db db ns-ent worker)
            b1 (if alloc-fn (long (alloc-fn)) 0)
            delta (- b1 b0)]
        (is (= 0 (:seon.ui/ns-var-count res)))
        (is (= 12 (count (:seon.ui/ns-children res))))
        (is (true? (:seon.ui/router-view? res)))
        (is (empty? (:seon.ui/ns-static-alerts res)) "Container namespace with kids must NOT run subtree static-efficiency-warnings")
        (when alloc-fn
          (is (< delta (* 20 1024 1024))
              (str "compute-ns-enrichment on 'seon allocated " delta " bytes (>= 20 MB)")))
        ;; Verify agent slug helper parity & seed=42 generative checks on router fns
        (is (= "ns~seon.web" (rdata/agent-id->slug :ns/seon.web)))
        (doseq [sym '[seon.web.router/core-routes-tx
                      seon.web.router/missing-core-routes
                      seon.web.router/compile-router
                      seon.web.router/match-path
                      seon.web.router/path->show-user-op
                      seon.web.router/entity-ref->path
                      seon.web.router/user-state->path
                      seon.web/match-http-route]]
          (let [st (schema/stress-var db sym {:samples 100 :seed 42})]
            (is (or (:ok? st) (:skipped? st)) (str sym " failed seed=42 generative check: " (pr-str st)))))))))