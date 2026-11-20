(ns seon.stress-test
  "De-risking & stress-testing the core architecture:
   1. Verifies EVERY co-located multi-segment schema in `resources/seon/schemas/*.edn` compiles,
      generates deterministic `seed = 42` values, and automatically derives Datahike schema.
   2. Dynamically registers brand-new Malli entity/attribute schemas inside a live forked agent REPL,
      verifies Datahike `:db/ident`s are auto-installed, writes & AVET-queries entities on the branch,
      and 3-way merges both derived Datahike schema AND entities onto `:main`!
   3. Runs a 4-branch distributed scoped swarm (`seon.app.auth`, `seon.app.billing`, `seon.app.inventory`,
      `seon.app.orders`) sharing an agreed `:seon.app/money` contract over a constrained `max-os-procs = 2`
      COW pool (exercising `::flow/input-filter` backpressure + warm LRU eviction + scope isolation!).
   4. Verifies normal `^:seon/callable` Clojure form dispatch on `POST /call` and rejects unapproved vars."
  (:require [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.ctx :as ctx]
            [seon.flow-e2e-test :refer [await-until rm-rf! shared-test-router]]
            [seon.health :as health]
            [seon.main :as main]
            [seon.merge :as merge]
            [seon.model :as model]
            [seon.render :as render]
            [seon.render.ui :as ui]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.web :as web]))

(deftest all-colocated-schemas-and-runtime-datahike-derivation-stress-test
  (let [dir (str "/tmp/seon-stress-sch-" (System/nanoTime))
        sys (main/start-flow-instance! @shared-test-router {:store-path dir :port 0})]
    (try
      (testing "1. All ~90 co-located schemas in `resources/seon/schemas/*.edn` are multi-segment & seed=42 generatable"
        (let [db @(:conn sys)
              forms @schema/disk-base-forms]
          (is (>= (count forms) 80) (str "Expected >= 80 co-located schemas, got " (count forms)))
          (doseq [[k f] forms]
            (is (schema/multi-segment-schema-key? k) (str "Non-multi-segment key: " k))
            ;; Verify every non-host-predicate schema generates with seed=42!
            (let [r (schema/validate-schema-registration! db {:key k :form f})]
              (is (:ok? r) (str "Schema failed generator gate: " k))))))

      (testing "2. Agent registers brand-new storable Malli schemas at runtime -> Datahike attributes auto-derive & merge to :main!"
        (let [code (str "(user/register-schema! :seon.billing.invoice/id\n"
                        "  [:and {:seon.db/identity true :seon.db/content-id true} :qualified-keyword])\n"
                        "(user/register-schema! :seon.billing.invoice/amount\n"
                        "  [:double {:seon.db/index true :min 0.0 :max 50000.0}])\n"
                        "(user/transact! [{:seon.billing.invoice/id :inv/first :seon.billing.invoice/amount 1250.5}])\n"
                        "(:seon.billing.invoice/amount (datahike.api/pull (user/db) [:seon.billing.invoice/amount] [:seon.billing.invoice/id :inv/first]))")
              ev (main/call! sys {:op :eval :branch :branch/billing :code code})]
          (is (:ok? ev) (pr-str ev))
          (is (= "1250.5" (:value ev))))

        ;; Merge `:branch/billing` into `:main`: `:transactor` automatically installs the derived Datahike
        ;; `:db/ident`s (`:seon.billing.invoice/id` + `:seon.billing.invoice/amount`) AND merges the entity!
        (let [m (main/call! sys {:op :merge :branch :branch/billing})]
          (is (:ok? m) (pr-str m))
          (is (contains? (:touched-paths m) "resources/seon/schemas.edn"))
          (let [main-db @(:conn sys)
                pulled (d/pull main-db '[:seon.billing.invoice/id :seon.billing.invoice/amount]
                               [:seon.billing.invoice/id :inv/first])]
            (is (= :db.type/double (get-in main-db [:schema :seon.billing.invoice/amount :db/valueType])))
            (is (true? (get-in main-db [:schema :seon.billing.invoice/amount :db/index])))
            (is (= 1250.5 (:seon.billing.invoice/amount pulled))))))
      (finally
        (main/stop! sys)
        (rm-rf! (io/file dir))))))

(deftest distributed-scoped-swarm-multiplexing-stress-test
  (let [dir (str "/tmp/seon-stress-swarm-" (System/nanoTime))
        ;; Constrain `max-os-procs` to 2 while running 4 concurrent scoped agents!
        sys (main/start-flow-instance! @shared-test-router {:store-path dir :port 0 :max-os-procs 2})]
    (try
      ;; Agree on shared cross-domain contract `:seon.app/money` first!
      (let [reg (main/call! sys {:op :register-schema
                                 :key :seon.app/money
                                 :form [:double {:min 0.0 :max 10000.0}]})]
        (is (:ok? reg)))

      (let [scopes [['seon.app.auth      :app/auth      "(defn token-fee {:malli/schema [:=> [:cat :seon.app/money] :seon.app/money]} [m] (* m 0.01))"]
                    ['seon.app.billing   :app/billing   "(defn with-tax  {:malli/schema [:=> [:cat :seon.app/money] :seon.app/money]} [m] (* m 1.1))"]
                    ['seon.app.inventory :app/inventory "(defn hold-cost {:malli/schema [:=> [:cat :seon.app/money] :seon.app/money]} [m] (* m 0.05))"]
                    ['seon.app.orders    :app/orders    "(defn ship-cost {:malli/schema [:=> [:cat :seon.app/money] :seon.app/money]} [m] (+ m 5.0))"]]]
        (doseq [[sc aid fn-src] scopes
                :let [test-ns (symbol (str sc "-test"))
                      fn-name (second (read-string fn-src))]]
          (main/call! sys {:op :spawn-agent
                           :id aid
                           :scope sc
                           :role :worker
                           :goal ""})
          (main/call! sys {:op :send-message
                           :from :root
                           :to aid
                           :type :task
                           :body (str "(ns " sc ")\n" fn-src "\n"
                                      "(ns " test-ns " (:require [clojure.test :refer [deftest is]] [" sc " :as sut]))\n"
                                      "(deftest " fn-name "-test (is (>= (sut/" fn-name " 100.0) 0.0)))\n"
                                      "(in-ns 'user)")}))

        ;; Wait for all 4 scoped agents to multiplex across 2 COW slots, pass `deftest` + 100x `seed=42` stress checks, and merge!
        (let [finished
              (await-until
               (fn []
                 (let [st (main/call! sys {:op :status})
                       ags (:agents st)]
                   (when (and (= 4 (count ags))
                              (every? #(= :merged (:status %)) ags))
                     st)))
               20000)]
          (is (some? finished) "All 4 concurrent scoped agents must multiplex across 2 OS slots and merge!")
          (is (every? #(nil? (:live-pid %)) (:agents finished)))

          ;; Verify Datahike namespace-ref tree & shared schema cross-referencing on `:main` (zero separate scope subsystem!)
          (let [billing-ctx (ctx/build @(:conn sys) [:seon.agent/id :app/billing])
                focus-ns (get-in billing-ctx [:val :seon.agent/focus])
                money-users (:used-by (:schema (main/call! sys {:op :explore-schema :key :seon.app/money})))]
            (is (= 'seon.app.billing (:seon.code.ns/name focus-ns))
                "Agent `@seon.ctx/ctx` pulls its focus namespace natively via Datahike ref `:seon.agent/focus`!")
            (is (some #(= 'seon.app.billing/with-tax (:seon.code.var/name %))
                      (:seon.code.var/_ns-ref focus-ns))
                "Namespace pull includes its own vars via reverse ref `:seon.code.var/_ns-ref`!")
            (is (= 4 (count money-users))
                "All 4 distributed functions are indexed under `:seon.app/money` via `:seon.code.var/schema-refs`!"))))
      (finally
        (main/stop! sys)
        (rm-rf! (io/file dir))))))

(deftest reagent-events-60fps-incremental-walk-and-nonblocking-web-test
  (let [dir (str "/tmp/seon-stress-ui-" (System/nanoTime))
        listener (web/start-http-listener! -1)
        sys (main/start-flow-instance! @shared-test-router {:store-path dir :listener listener})]
    (try
      (testing "1. All 4 Reagent/Clojure `:on-click` styles wire up seamlessly (closure, unquoted vec, bare fn, quoted form)"
        (let [closure-fn (fn [st] (assoc st :clicked true))
              h1 (render/hiccup->html-with-handlers [:button {:id "b1" :on-click closure-fn} "Closure"] "u1")
              h2 (render/hiccup->html-with-handlers [:button {:id "b2" :on-click [web/switch-tab! :schemas]} "Vec"] "u2")
              h3 (render/hiccup->html-with-handlers [:button {:id "b3" :on-click web/power-loss-reboot!} "Bare"] "u3")
              h4 (render/hiccup->html-with-handlers [:button {:id "b4" :on-click '(seon.web/switch-tab! :agents)} "Quoted"] "u4")]
          (is (str/includes? (:html h1) "data-on:click=\"@post(&#39;/call?h=b1%3Aon-click%3A1&#39;)\""))
          (is (fn? (get (:handlers h1) "b1:on-click:1")))
          (is (str/includes? (:html h2) "seon.web%2Fswitch-tab%21"))
          (is (str/includes? (:html h3) "seon.web%2Fpower-loss-reboot%21"))
          (is (str/includes? (:html h4) "seon.web%2Fswitch-tab%21"))))

      (testing "2. Schema-driven Health component renders identically to Web HTML and Agent Markdown text"
        (let [h (health/report @(:conn sys))
              hiccup (render/render-value @(:conn sys) h)
              md (render/hiccup->text hiccup)]
          (is (str/includes? (render/hiccup->html hiccup) "System Health"))
          (is (str/includes? md "System Health"))
          ;; Clojure Ratios & keyword attributes coerce cleanly for SVG/HTML attributes
          (let [svg-html (render/hiccup->html [:rect {:width 10/3 :text-anchor :middle}])]
            (is (str/includes? svg-html "width=\"3.333\""))
            (is (str/includes? svg-html "text-anchor=\"middle\"")))))

      (testing "3. Incremental `render/step-walk` streams at > 120 fps (0 DB panels re-rendered per token delta!)"
        (let [db @(:conn sys)
              st0 {:boot-seq 1 :conn (:conn sys)
                   :live-streams {} :reports [] :errors [] :frames 0}
              init (render/step-walk (render/empty-walk) db nil (ui/page-unit-specs st0))
              t0 (System/nanoTime)
              final (reduce
                     (fn [w i]
                       (let [st-i (assoc st0 :walk w :frames i :live-streams {:ag/a {:status :token :text (str "tok-" i)}})
                             step (render/step-walk w db [] (ui/page-unit-specs st-i))]
                         (is (= 1 (:rerendered step)) "Only #seon-stream-unit should re-render on stream ticks!")
                         (is (= 5 (:skipped step)) "All 5 non-stream units (css, header, sidebar, view, composer) must be skipped (0 DB seeks)!")
                         (:walk step)))
                     (:walk init)
                     (range 120))
              elapsed-ms (/ (- (System/nanoTime) t0) 1e6)
              per-frame-ms (/ elapsed-ms 120.0)]
          (is (some? final))
          (is (< per-frame-ms 4.0)
              (str "120fps frame budget is 8.33ms; incremental stream frame took " per-frame-ms " ms!"))))

      (testing "4. Auto-injected stable root IDs + Fault-isolated `step-walk`: throwing component NEVER crashes siblings!"
        (let [bad-comp (fn [_db] (throw (ex-info "boom in agent render" {})))
              good-comp (fn [_db] [:div {:class "card"} "Still Alive"])
              res (render/step-walk (render/empty-walk) @(:conn sys) nil
                                    [{:id "bad-panel" :comp bad-comp :args []}
                                     {:id "ok-panel" :comp good-comp :args []}])]
          (is (= 1 (count (:render-errors res))))
          (is (str/includes? (get-in res [:walk :units "bad-panel" :html]) "boom in agent render"))
          (is (str/includes? (get-in res [:walk :units "ok-panel" :html]) "id=\"ok-panel\""))
          (is (str/includes? (get-in res [:walk :units "ok-panel" :html]) "Still Alive"))))

      (testing "5. Inside-out DB-backed user viewport, recursive agent sidebar, agent `(user/show-user!)`, 403 allowlist, and live `/mcp`"
        ;; Seed a 3-level hierarchy: :agent/root -> :app/billing -> :billing/sub-a
        (main/call! sys {:op :transact
                         :tx-data [{:seon.agent/id :agent/root :seon.agent/branch :main :seon.agent/role :coordinator :seon.agent/waiting? true}
                                   {:seon.agent/id :app/billing :seon.agent/parent :agent/root :seon.agent/branch :branch/billing :seon.agent/role :coordinator :seon.agent/waiting? true}
                                   {:seon.agent/id :billing/sub-a :seon.agent/parent :app/billing :seon.agent/branch :branch/billing.a :seon.agent/role :worker :seon.agent/waiting? true}
                                   {:seon.user/id :user/default
                                    :seon.user/viewing [:seon.agent/id :agent/root]
                                    :seon.user/view-mode :canvas
                                    :seon.user/sidebar-open? false
                                    :seon.user/expanded-agents #{:agent/root :app/billing}}]})
        (let [sse-ch (:body ((:handler listener) {:request-method :get :uri "/events"}))
              _ (await-until #(a/poll! sse-ch) 1000)
              q (str "form=" (render/url-enc "(seon.web/toggle-sidebar!)"))
              resp ((:handler listener) {:request-method :post :uri "/call" :query-string q
                                         :headers {"content-type" "application/json"} :body "{\"showThinking\":false}"})]
          (is (= 200 (:status resp)))
          (is (await-until
               (fn []
                 (when-let [chunk (a/poll! sse-ch)]
                   (and (str/includes? chunk "event: datastar-patch-elements")
                        (str/includes? chunk "sidebar open")
                        (str/includes? chunk "app/billing")
                        (str/includes? chunk "billing/sub-a"))))
               2500)
              "Toggling sidebar must persist in Datahike and stream recursive root->child->grandchild tree!"))
        ;; All 3 deep-link views sync into Datahike and render with ZERO render faults!
        (doseq [[path must-list] [["/" ["root-canvas" "requestSubmit()"]]
                                  ["/agent/root" ["workspace-grid" "requestSubmit()"]]
                                  ["/root/debug" ["Exact Wire Request"
                                                  "Live @seon.ctx/ctx Atom Value"
                                                  "Rendered Versions"
                                                  "Human HTML (Browser)"
                                                  "Model Text (render/hiccup-&gt;text)"]]]]
          (let [r ((:handler listener) {:request-method :get :uri path})]
            (is (= 200 (:status r)) (str "Route " path " must return 200"))
            (is (not (str/includes? (:body r) "Render fault"))
                (str "Route " path " had a Render fault: " (subs (:body r) 0 (min 500 (count (:body r))))))
            (doseq [marker must-list]
              (is (str/includes? (:body r) marker)
                  (str "Route " path " missing expected marker " marker)))))
        ;; Non-callable var refused with 403 immediately at edge
        (let [q (str "form=" (render/url-enc "(clojure.java.shell/sh \"ls\")"))
              resp ((:handler listener) {:request-method :post :uri "/call" :query-string q :body "{}"})
              data (edn/read-string (:body resp))]
          (is (= 403 (:status resp)))
          (is (= :seon.web/not-callable (:error data))))
        ;; Dispatching async `run-tests!` returns 200 & lock-free `GET /` returns in < 50 ms!
        (let [q-run (str "form=" (render/url-enc "(seon.web/run-tests! :main)"))
              t0 (System/nanoTime)
              r-run ((:handler listener) {:request-method :post :uri "/call" :query-string q-run
                                          :headers {"content-type" "application/json"} :body "{}"})
              r-get ((:handler listener) {:request-method :get :uri "/"})
              ms (/ (- (System/nanoTime) t0) 1e6)]
          (is (= 200 (:status r-run)))
          (is (= 200 (:status r-get)))
          (is (< ms 50.0) (str "Non-blocking web click + lock-free GET / must return < 50ms, took " ms " ms!")))
        ;; Live MCP JSON-RPC 2.0 + `result/e<n>` + programmatic `(user/show-user! ...)` from inside an agent!
        (let [mcp-post (fn [m]
                         (let [resp ((:handler listener) {:request-method :post :uri "/mcp"
                                                          :body (json/write-str m)})]
                           (is (= 200 (:status resp)))
                           (json/read-str (:body resp) :key-fn keyword)))
              init-r (mcp-post {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})
              list-r (mcp-post {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})
              h-r    (mcp-post {:jsonrpc "2.0" :id 3 :method "tools/call" :params {:name "seon_health" :arguments {}}})
              ev1-r  (mcp-post {:jsonrpc "2.0" :id 4 :method "tools/call"
                                :params {:name "seon_repl"
                                         :arguments {:branch ":branch/mcp-check"
                                                     :agent ":agent/mcp-check"
                                                     :code "(user/health)"}}})
              ev2-r  (mcp-post {:jsonrpc "2.0" :id 5 :method "tools/call"
                                :params {:name "seon_repl"
                                         :arguments {:branch ":branch/mcp-check"
                                                     :agent ":agent/mcp-check"
                                                     :code "(do (user/show-user! {:viewing :app/billing :mode :workspace :sidebar? true}) (:ok? result/e1))"}}})]
          (is (= "seon-next" (get-in init-r [:result :serverInfo :name])))
          (is (some #(= "seon_repl" (:name %)) (get-in list-r [:result :tools])))
          (is (false? (get-in h-r [:result :isError])))
          (is (str/includes? (get-in ev1-r [:result :content 0 :text]) "result/e1"))
          (is (str/includes? (get-in ev2-r [:result :content 0 :text]) "true"))
          ;; Wait for agent's `(user/show-user!)` to commit to Datahike and verify `GET /` jumped to `:app/billing`!
          (is (await-until
               (fn []
                 (let [u (ui/user-view-state @(:conn sys))]
                   (and (= :app/billing (:viewing-id u))
                        (= :workspace (:view-mode u))
                        (true? (:sidebar-open? u)))))
               2500)
              "Agent calling `(user/show-user!)` must programmatically move the user's DB view!")
          (is (await-until
               (fn []
                 (let [body (:body ((:handler listener) {:request-method :get :uri "/"}))]
                   (and (str/includes? body ":app/billing")
                        (str/includes? body "workspace-grid")
                        (str/includes? body "sidebar open"))))
               2000)
              "`GET /` must reflect the agent-updated DB view immediately!")
          ;; Verify Level 2 auto-discovered `:seon.health/report` + colocated `:seon.ctx/snapshot` tile + `(user/source)` & `(user/present!)`!
          (let [_     (mcp-post {:jsonrpc "2.0" :id 6 :method "tools/call"
                                 :params {:name "seon_repl"
                                          :arguments {:branch ":branch/mcp-check"
                                                      :agent ":agent/mcp-check"
                                                      :code "(ns seon.mcp.demo)\n(defn hi [] :ok)\n(in-ns 'user)\n(user/canvas! {:title \"Step One\" :summary \"First view\" :body [:svg {:viewBox \"0 0 100 20\"} [:polyline {:points \"0,15 50,5 100,10\"}]]})"}}})
                _     (mcp-post {:jsonrpc "2.0" :id 7 :method "tools/call"
                                 :params {:name "seon_repl"
                                          :arguments {:branch ":branch/mcp-check"
                                                      :agent ":agent/mcp-check"
                                                      :code "(user/canvas! {:title \"Step Two\" :summary \"Replaced view\" :body [:div \"Updated Canvas\"]})"}}})
                ev3-r (mcp-post {:jsonrpc "2.0" :id 8 :method "tools/call"
                                 :params {:name "seon_repl"
                                          :arguments {:branch ":branch/mcp-check"
                                                      :agent ":agent/mcp-check"
                                                      :code "[(some? (user/source 'seon.mcp.demo/hi)) (pos? (count (d/datoms (user/db) :aevt :seon.schema/key)))]"}}})
                bdb (do (main/call! sys {:op :set-config :agent-id :agent/mcp-check :key :seon.config/viewport-screenshot? :value true}) (store/head (:conn sys) :branch/mcp-check))
                hist-txs (ui/canvas-history-txs bdb :agent/mcp-check)
                wire (ui/build-live-wire-request @(:conn sys) bdb :agent/mcp-check
                                                 {:viewport-screenshot {:base64 "ZmFrZQ==" :mime "image/png" :width 1280 :height 820 :route "/agent/mcp-check"}
                                                  :vision? true})
                roles (mapv :role (:messages wire))]
            (is (str/includes? (get-in ev3-r [:result :content 0 :text]) "[true true]")
                "Pre-aliased `d` and `(user/source)` + `(user/canvas!)` must work directly in agent REPL!")
            (is (>= (count hist-txs) 2)
                "Every `(user/canvas!)` update must record a Datahike history step for Back/Forward time-travel!")
            ;; Verify Datahike d/as-of time-travel renders Step One vs Live Step Two!
            (let [h-html (render/hiccup->html (ui/root-canvas @(:conn sys) {:conn (:conn sys) :branch :branch/mcp-check :viewing-id :agent/mcp-check :as-of-tx (first hist-txs)}))
                  l-html (render/hiccup->html (ui/root-canvas @(:conn sys) {:conn (:conn sys) :branch :branch/mcp-check :viewing-id :agent/mcp-check}))]
              (is (str/includes? h-html "<h1 class=\"canvas-title\">Step One</h1>"))
              (is (str/includes? h-html "Time-travel"))
              (is (str/includes? l-html "<h1 class=\"canvas-title\">Step Two</h1>"))
              (is (not (str/includes? l-html "<h1 class=\"canvas-title\">Step One</h1>"))))
            ;; Verify instant prompt replacement while generating, AND plain-text fallback AFTER `(user/canvas! ...)`!
            (let [m-ans (main/call! sys {:op :send-message :from :user :to :agent/mcp-check :type :chat :body "test"})
                  mid (:message-id m-ans)]
              ;; The instant a user sends a message (unread/pending), Canvas replaces the stage with the user's prompt!
              (let [mid-html (render/hiccup->html (ui/root-canvas @(:conn sys) {:conn (:conn sys) :branch :branch/mcp-check :viewing-id :agent/mcp-check}))]
                (is (str/includes? mid-html "canvas-pending")
                    "Sending a message must immediately give visual feedback replacing Canvas with the user's prompt!")
                (is (str/includes? mid-html "canvas-prompt-headline\">test</h1>")
                    "Pending Canvas must show the exact prompt the user just sent!"))
              ;; Now agent responds with plain Markdown prose WITHOUT calling `(user/canvas!)`
              (let [bconn (store/connect (:cfg sys) :branch/mcp-check)]
                (try
                  (d/transact bconn
                              {:tx-data [{:seon.agent.eval/id (random-uuid)
                                          :seon.agent.eval/agent :agent/mcp-check
                                          :seon.agent.eval/turn 99
                                          :seon.agent.eval/call-index 0
                                          :seon.agent.eval/code ""
                                          :seon.agent.eval/thought "Test received — **alive** on `:branch/mcp-check`!\n- *all green*"
                                          :seon.agent.eval/seen-messages [mid]}]})
                  (finally (d/release bconn))))
              (let [r-html (render/hiccup->html (ui/root-canvas @(:conn sys) {:conn (:conn sys) :branch :branch/mcp-check :viewing-id :agent/mcp-check}))]
                (is (str/includes? r-html "Test received")
                    "Plain-text reply after `(user/canvas!)` MUST replace the primary Canvas text!")
                (is (str/includes? r-html "<strong>alive</strong>")
                    "Markdown **bold** must render cleanly as <strong>!")
                (is (str/includes? r-html "<em>all green</em>")
                    "Markdown *italic* must render cleanly as <em>!")
                (is (str/includes? r-html "<ul class=\"prose-list\">")
                    "Markdown bullet lists must render cleanly as <ul>!")
                (is (not (str/includes? r-html "**alive**"))
                    "Raw ** asterisks must never appear in rendered prose!")
                (is (str/includes? r-html "Previous visual")
                    "Prior visual must remain accessible cleanly below the new reply!")))
            ;; Verify streaming NEVER truncates reasoning AND has zero flicker-inducing inline `style="display:none"`!
            (let [long-r (str "START-MARKER-" (apply str (repeat 1600 "x")) "-END-MARKER")
                  sh (render/hiccup->html (ui/stream-unit nil {:live-streams {:agent/root {:status :reasoning :reasoning long-r :text ""}}}))]
              (is (str/includes? sh "START-MARKER-"))
              (is (str/includes? sh "-END-MARKER"))
              (is (str/includes? sh "$showThinking"))
              (is (not (str/includes? sh "display:none"))
                  "Live reasoning must use un-morphed #seon-app[data-thinking] CSS, never inline display:none!"))
            ;; Verify ephemeral end-of-context screenshot replaces prior image (never accumulates in history)!
            (let [w1 (ui/build-live-wire-request @(:conn sys) (store/head (:conn sys) :branch/mcp-check) :agent/mcp-check
                                                 {:viewport-screenshot {:route "/" :width 1280 :height 820 :mime "image/jpeg" :base64 "SHOT_ONE" :dom {:mode "Canvas" :overflowX false}}
                                                  :vision? true})
                  w2 (ui/build-live-wire-request @(:conn sys) (store/head (:conn sys) :branch/mcp-check) :agent/mcp-check
                                                 {:viewport-screenshot {:route "/" :width 1280 :height 820 :mime "image/jpeg" :base64 "SHOT_TWO" :dom {:mode "Canvas" :overflowX false}}
                                                  :vision? true})
                  img-count (fn [w] (count (filter #(and (sequential? (:content %)) (some (fn [b] (= "image_url" (:type b))) (:content %))) (:messages w))))]
              (is (= 1 (img-count w1)) "Exactly 1 ephemeral screenshot at tail of wire messages")
              (is (= 1 (img-count w2)) "Replacing screenshot keeps exactly 1 image at tail (never accumulates)")
              (is (str/includes? (pr-str (peek (:messages w2))) "SHOT_TWO"))
              (is (not (str/includes? (pr-str (:messages w2)) "SHOT_ONE"))))
            ;; Verify theme toggle persists inside Datahike `:user/default`
            (main/call! sys {:op :show-user :toggle-theme? true})
            (is (= :light (:theme (ui/user-view-state @(:conn sys)))))
            (main/call! sys {:op :show-user :toggle-theme? true})
            (is (= :dark (:theme (ui/user-view-state @(:conn sys)))))
            (is (some #(= :seon.ctx/snapshot (:schema %)) (:tiles wire))
                "Agent's own `@seon.ctx/ctx` snapshot tile must be colocated in `<walk-context>`!")
            (is (some #(str/includes? (str (:text %)) "user/canvas!") (:tiles wire))
                "Colocated REPL affordances must appear on `@ctx` tile's agent projection!")
            (is (some #(= :seon.health/report (:schema %)) (render/discover-renderable-tiles @(:conn sys) bdb (d/pull @(:conn sys) '[*] [:seon.agent/id :agent/mcp-check]) (:evals wire) health/report)))
            (is (not (some #(= :eval (:source %)) (:tiles wire))) "Mutable :eval tiles stay out of static LLM <walk-context> for KV-cache reuse!")
            (is (= "system" (first roles)))
            (is (some #(= "assistant" %) roles))
            (is (some #(= "tool" %) roles))
            (is (= "user" (peek roles)))
            (is (vector? (:content (peek (:messages wire))))))))

      (testing "6. Inside-out DB Restore & Custom `:seon.agent/render-fn` live override"
        (let [custom-ns (create-ns 'seon.demo.ui)]
          (intern custom-ns 'custom-canvas
                  (fn [_db {:keys [viewing-id]}]
                    [:div {:class "custom-agent-ui"} (str "Custom Agent Canvas for " viewing-id)]))
          (main/call! sys {:op :transact
                           :tx-data [{:seon.agent/id :agent/root :seon.agent/render-fn 'seon.demo.ui/custom-canvas}]})
          (main/call! sys {:op :show-user :viewing :agent/root :mode :canvas})
          (is (await-until
               (fn []
                 (let [b (:body ((:handler listener) {:request-method :get :uri "/"}))]
                   (and (str/includes? b "Custom Agent Canvas for :agent/root")
                        (not (str/includes? b "Render fault")))))
               2000)
              "Setting :seon.agent/render-fn must dynamically override :canvas!")
          ;; Live Var redefinition updates `step-walk` on next tick without restarting!
          (let [w0 (:walk (render/step-walk (render/empty-walk) @(:conn sys) nil
                                            [{:id "dyn-u" :comp (resolve 'seon.demo.ui/custom-canvas) :args [{:viewing-id :agent/root}]}]))]
            (intern custom-ns 'custom-canvas
                    (fn [_db {:keys [viewing-id]}]
                      [:div {:class "custom-agent-ui"} (str "Redefined Live Canvas for " viewing-id)]))
            (let [w1 (render/step-walk w0 @(:conn sys) []
                                       [{:id "dyn-u" :comp (resolve 'seon.demo.ui/custom-canvas) :args [{:viewing-id :agent/root}]}])]
              (is (= 1 (:rerendered w1)))
              (is (str/includes? (get-in w1 [:walk :units "dyn-u" :html]) "Redefined Live Canvas"))))
          ;; Restore `:app/billing` workspace state before testing cold DB restore
          (main/call! sys {:op :show-user :viewing :app/billing :mode :workspace :sidebar? true}))
        (main/stop! sys)
        (let [sys2 (main/start-flow-instance! @shared-test-router {:store-path dir :listener listener})]
          (try
            (let [r ((:handler listener) {:request-method :get :uri "/"})]
              (is (= 200 (:status r)))
              (is (str/includes? (:body r) ":app/billing")
                  "After cold reboot/DB restore, user must go right back to :app/billing!")
              (is (str/includes? (:body r) "workspace-grid")
                  "After cold reboot/DB restore, user must still be in :workspace mode!")
              (is (str/includes? (:body r) "sidebar open")
                  "After cold reboot/DB restore, left sidebar open state must be restored!"))
            (finally
              (main/stop! sys2)))))
      (finally
        (try (main/stop! sys) (catch Throwable _))
        ((:stop! listener))
        (rm-rf! (io/file dir))))))

(deftest health-gates-stored-data-schema-safety-and-anti-test-deletion-test
  (let [dir (str "/tmp/seon-stress-health-" (System/nanoTime))
        sys (main/start-flow-instance! @shared-test-router {:store-path dir :port 0})]
    (try
      (testing "1. Stored-data schema gate rejects narrowing a schema when existing stored datoms violate it"
        (is (:ok? (main/call! sys {:op :register-schema
                                   :key :seon.widget/score
                                   :form [:int {:seon.db/index true :min 0 :max 100}]})))
        (is (:ok? (main/call! sys {:op :register-schema
                                   :key :seon.widget/id
                                   :form [:and {:seon.db/identity true :seon.db/content-id true} :keyword]})))
        (is (:ok? (main/call! sys {:op :transact
                                   :tx-data [{:seon.widget/id :widget/a :seon.widget/score 50}]})))
        ;; Branch tightens `:seon.widget/score` to `[:int {:min 80 :max 100}]` — violates existing `50` on `:main`!
        (is (:ok? (main/call! sys {:op :eval :branch :branch/bad-schema
                                   :code "(user/register-schema! :seon.widget/score [:int {:seon.db/index true :min 80 :max 100}])"})))
        (let [m (main/call! sys {:op :merge :branch :branch/bad-schema})]
          (is (not (:ok? m)) "Merge MUST be blocked when new schema rejects stored datoms!")
          (is (some #(= :schema-breaks-stored-data (:reason %)) (:conflicts m)))))

      (testing "2. Blocking op inside a Flow proc is detected by derived health & blocks merge"
        (is (:ok? (main/call! sys {:op :eval :branch :branch/blocking-proc
                                   :code "(ns seon.badproc (:require [clojure.core.async :as a]))
                                          (defn evil-proc [ch] (a/<!! ch))"})))
        (let [m (main/call! sys {:op :merge :branch :branch/blocking-proc})]
          (is (not (:ok? m)))
          (is (some #(= :blocking-in-flow-proc (:reason %)) (:conflicts m)))))

      (testing "3. Deleting an existing `:main` test on a branch is refused immediately by `:test`"
        (is (:ok? (main/call! sys {:op :eval :branch :branch/seed-test
                                   :code "(ns seon.keepme)
                                         (defn add1 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))
                                         (ns seon.keepme-test (:require [clojure.test :refer [deftest is]] [seon.keepme :as sut]))
                                         (deftest add1-proof (is (= 2 (sut/add1 1))))"})))
        (is (:ok? (main/call! sys {:op :test :branch :branch/seed-test})))
        (is (:ok? (main/call! sys {:op :merge :branch :branch/seed-test})))
        ;; Now a branch retracts `seon.keepme-test/add1-proof`!
        (is (:ok? (main/call! sys {:op :eval :branch :branch/delete-test
                                   :code "(user/transact! [[:db/retractEntity [:seon.code.form/key \"seon.keepme-test/add1-proof\"]]])"})))
        (let [t (main/call! sys {:op :test :branch :branch/delete-test})]
          (is (not (:ok? t)) "Deleting a :main test MUST be refused!")
          (is (= ['seon.keepme-test/add1-proof] (:deleted-main-tests t)))
          ;; And the failure appears globally in `seon.health/report`!
          (let [h (main/call! sys {:op :health})]
            (is (some #(and (= :failing-test (:class %)) (= :branch/delete-test (:branch %)))
                      (:problems h))))))

      (testing "4. DeepSeek / OpenAI-compat SSE streaming parser + empty-string guard + reasoning replay"
        (let [chunks [{:choices [{:delta {:reasoning_content "Think step 1. " :content ""}}]}
                      {:choices [{:delta {:reasoning_content "" :content "Final answer."}}]}
                      {:choices [{:delta {:tool_calls [{:index 0 :id "call_99" :function {:name "repl" :arguments "{\"intent\":\"test\","}}]}}]}
                      {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "\"code\":\"(+ 1 2)\"}"}}]} :finish_reason "tool_calls"}]}
                      {:choices [] :usage {:prompt_tokens 100 :completion_tokens 25 :prompt_cache_hit_tokens 80 :prompt_cache_miss_tokens 20}}]
              [final deltas]
              (reduce (fn [[st ds] ch]
                        (let [[st' d] (model/process-sse-chunk ch st)]
                          [st' (cond-> ds d (conj d))]))
                      [(model/make-stream-state) []]
                      chunks)
              done (model/finalize-stream-state final)
              wire (model/build-wire-messages
                    {:thinking? true :replay-reasoning? true}
                    "sys"
                    [{:thought (:thought done) :reasoning (:reasoning done)
                      :calls [(assoc (first (:tool-calls done)) :value "3" :ms 1.0 :ticks 10)]}]
                    "next")]
          (is (= [{:kind :reasoning-token :text "Think step 1. "}
                  {:kind :token :text "Final answer."}]
                 deltas))
          (is (= "Final answer." (:thought done)))
          (is (= "Think step 1. " (:reasoning done)))
          (is (= "(+ 1 2)" (get-in done [:tool-calls 0 :code])))
          (is (= 80 (get-in done [:usage :cache-hit-tokens])))
          (is (= "Think step 1. " (:reasoning_content (second wire))))))
      (finally
        (main/stop! sys)
        (rm-rf! (io/file dir))))))

(deftest host-predicates-and-core-contracts-test
  (testing "1. Pure value function contracts run 100x deterministic `seed = 42` checks within budget"
    (is (true? (schema/generatable-function-schema? (:malli/schema (meta #'schema/multi-segment-schema-key?)))))
    (is (true? (schema/generatable-function-schema? (:malli/schema (meta #'code/parent-ns-sym)))))
    (is (not (schema/generatable-function-schema? (:malli/schema (meta #'store/point)))))
    (let [r1 (schema/stress-var nil 'seon.schema/multi-segment-schema-key? {:samples 100 :seed 42 :measure-fn code/measure-call})
          r2 (schema/stress-var nil 'seon.code/parent-ns-sym {:samples 100 :seed 42 :measure-fn code/measure-call})]
      (is (:ok? r1) (pr-str r1))
      (is (= 100 (:ran r1)))
      (is (:ok? r2) (pr-str r2))
      (is (= 100 (:ran r2)))))

  (testing "2. `:seon.host/db` and `:seon.host/conn` enforce real Datahike runtime types under `arm-var!`"
    (schema/arm-var! #'store/point)
    (schema/arm-var! #'store/config-val)
    (schema/arm-var! #'merge/three-way)
    (let [err (try (store/point "not-a-db") nil (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :seon.schema/contract-violation (:error err)))
      (is (= 'seon.store/point (:var err))))
    (let [err2 (try (store/config-val "not-a-db" :seon.config/port) nil (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :seon.schema/contract-violation (:error err2)))
      (is (= 'seon.store/config-val (:var err2))))
    (let [err3 (try (merge/three-way "not-a-conn" {} :main) nil (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :seon.schema/contract-violation (:error err3)))
      (is (= 'seon.merge/three-way (:var err3))))))
