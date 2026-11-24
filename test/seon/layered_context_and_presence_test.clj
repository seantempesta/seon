(ns seon.layered-context-and-presence-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.reads :as reads]
            [seon.ctx.frontier :as frontier]
            [seon.ctx.project :as proj]
            [seon.ctx.touches :as ctouches]
            [seon.render.fit :as fit]
            [seon.render.surface.presence :as presence]
            [seon.render.wire :as rwire]
            [seon.signals :as sig]
            [seon.store :as store]
            [seon.test-fixture :refer [with-test-store]]))

(defn demo-reactive-canvas
  "Sample pure reactive canvas renderer `(fn [db agent])` used by Stage 4 test."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :map]] :any]
   :cost/ticks 500000}
  [db agent]
  [:div {:class "reactive-demo"}
   (str "Agent " (:seon.agent/id agent) " @ tx " (:max-tx db))])

(deftest stage0-source-level-sensor-isolation-preserves-ui-reactivity-test
  (testing "Plumbing reads opt out during agent eval capture while preserving 100% UI/rule reads/with-reads"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data [{:seon.config/key :seon.config/root
                                   :seon.config/rank-weights {:urgency 1.0 :recency 0.7 :convergence 0.5}}]})
      (let [snap @conn
            [_ ui-reads] (binding [store/*capturing-agent-eval-reads?* false]
                           (reads/with-reads #(do (store/config-val snap nil :seon.config/eval-ticks)
                                                  (fit/read-rank-weights snap)
                                                  (sig/all-signals snap))))
            [_ eval-reads] (binding [store/*capturing-agent-eval-reads?* true]
                             (reads/with-reads #(do (store/config-val snap nil :seon.config/eval-ticks)
                                                    (fit/read-rank-weights snap)
                                                    (sig/all-signals snap))))]
        (is (pos? (count ui-reads)) "UI/rule reactivity MUST still record Datahike reads")
        (is (zero? (count eval-reads)) "Agent eval sensor MUST record 0 plumbing reads")))))

(deftest stage1-and-2-attention-conservation-acl-ppr-and-rrf-test
  (testing "1/|E_call| attention conservation favors focused calls over bulk namespace scans"
    (let [bulk-eids (set (range 1000 1060))
          res (ctouches/analyze-touch-sets
               [{:turn 1 :tx 10 :read-eids bulk-eids :calls [{:eids bulk-eids}]}
                {:turn 2 :tx 20 :read-eids #{2001 2002} :calls [{:eids #{2001 2002}}] :explicit-eids #{2001}}]
               {3001 {:writes 2 :max-tx 25}})
          eng (:energy res)]
      (is (> (double (get eng 2001 0.0)) (* 10.0 (double (get eng 1005 0.0))))
          "Focused explicit target should dominate diluted bulk scan entity")
      (is (> (double (get eng 3001 0.0)) (double (get eng 2002 0.0)))
          "Branch-written entity should have highest working-set energy")))
  (testing "Pure ACL Local-Push PPR and Weighted RRF rank connected novel neighbors first"
    (let [adj {1 [[2 1.5] [3 0.4]]
               2 [[4 1.5] [1 0.9]]
               3 [[5 0.4]]
               4 [[2 0.9]]}
          ppr (frontier/acl-forward-push adj {1 1.0} {:alpha 0.40 :eps 0.002 :max-pushes 200 :beta 0.35})
          fused (frontier/rrf-fuse [{:weight 1.3 :scores {4 2.0}}
                                    {:weight 1.0 :scores ppr}] 15)]
      (is (> (double (get ppr 2 0.0)) (double (get ppr 3 0.0)))
          "Higher-weighted edge neighbor 2 must outrank low-weight neighbor 3")
      (is (> (double (get fused 4 0.0)) (double (get fused 5 0.0)))
          "Continuation + PPR consensus node 4 must outrank distant node 5"))))

(deftest ^{:cost/ticks 60000000} stage3-error-schema-rule-and-live-wire-tail-test
  (with-test-store [{:keys [conn cfg]}]
    (testing "fast-skip-rule-ids never skips scope-and-extra-namespaces when agent has tasks"
      (let [db @conn
            skips (proj/fast-skip-rule-ids db db {:seon.agent/id :agent/t1 :seon.agent/tasks #{[:seon.task/id :t/x]}} true)]
        (is (not (contains? skips :rule.ctx/scope-and-extra-namespaces)))))
    (testing "Failing eval triggers :turn/error-schema on Turn 2+ live wire tail with 0 KV-cache prefix bust"
      (d/transact conn {:tx-data [{:seon.schema/key :test.demo/item
                                   :seon.schema/form [:map [:test.demo/id :int]]
                                   :seon.schema/doc "Demo item schema doc for Turn 2+ error recovery."
                                   :seon.schema/examples [{:test.demo/id 1}]}
                                  {:seon.agent/id :agent/err-demo
                                   :seon.agent/branch :branch/err-demo}]})
      (store/branch! conn :main :branch/err-demo)
      (let [bconn (store/connect cfg :branch/err-demo)]
        (try
          (let [mdb @conn
                w1 (rwire/build-live-wire-request mdb @bconn :agent/err-demo {:conn conn})
                _ (d/transact bconn {:tx-data [{:seon.agent.eval/id (random-uuid)
                                                :seon.agent.eval/agent :agent/err-demo
                                                :seon.agent.eval/turn 1
                                                :seon.agent.eval/code "(+ 1 1)"
                                                :seon.agent.eval/value "2"}
                                               {:seon.agent.eval/id (random-uuid)
                                                :seon.agent.eval/agent :agent/err-demo
                                                :seon.agent.eval/turn 2
                                                :seon.agent.eval/code "(user/register-schema! :test.demo/item :bad)"
                                                :seon.agent.eval/error "Invalid schema :test.demo/item"}]})
                bdb2 @bconn
                dt (ctouches/derived-agent-touches mdb bdb2 :agent/err-demo)
                seid (:e (first (d/datoms bdb2 :avet :seon.schema/key :test.demo/item)))
                w2 (rwire/build-live-wire-request mdb bdb2 :agent/err-demo {:conn conn})]
            (is (true? (get-in dt [:turn-events :has-error?])))
            (is (contains? (get-in dt [:turn-events :error-schemas]) seid))
            (is (= (:prefix-hash w1) (:prefix-hash w2))
                "Turn 2+ failing eval tail MUST preserve byte-identical KV-cache :prefix-hash"))
          (finally
            (ctouches/clear-live-reads! @bconn :agent/err-demo)
            (d/release bconn)))))))

(deftest stage4-live-canvas-presence-xss-reactive-fn-and-time-travel-test
  (testing "sanitize-canvas-hiccup strips dangerous tags, raw JS handlers, and javascript:/data: URLs"
    (let [dirty [:div {:class "card" :onclick "alert(1)" :on-click (list 'seon.web/inspect-entity! [:seon.agent/id :a/x])}
                 [:script "evil()"]
                 [:iframe {:src "https://evil.test"}]
                 [:a {:href "javascript:alert(1)"} "bad"]
                 [:a {:href "/agent/root"} "good"]
                 [:style "body{display:none}"]]
          clean (presence/sanitize-canvas-hiccup dirty)
          s (pr-str clean)]
      (is (not (str/includes? s ":script")))
      (is (not (str/includes? s ":iframe")))
      (is (not (str/includes? s ":style")))
      (is (not (str/includes? s "alert(1)")))
      (is (str/includes? s "/agent/root"))
      (is (str/includes? s "seon.web/inspect-entity!"))))
  (testing "render-live-canvas-presence supports static :canvas-hiccup, reactive :canvas-fn, child/call presence, and d/as-of rewind"
    (with-test-store [{:keys [conn]}]
      (let [r1 (d/transact conn {:tx-data [{:seon.agent/id :agent/parent-p :seon.agent/branch :main
                                            :seon.agent/canvas-hiccup [:div {:class "custom-box"} "Frame One"]}
                                           {:seon.agent/id :agent/child-p :seon.agent/branch :branch/c1
                                            :seon.agent/parent :agent/parent-p :seon.agent/ready? true}
                                           {:seon.agent.call/id (random-uuid)
                                            :seon.agent.call/caller :agent/parent-p
                                            :seon.agent.call/callee :agent/child-p
                                            :seon.agent.call/prompt "Check health"
                                            :seon.agent.call/status :resolved
                                            :seon.agent.call/result "{:ok? true}"}]})
            tx1 (:max-tx (:db-after r1))
            _ (d/transact conn {:tx-data [{:seon.agent/id :agent/parent-p
                                           :seon.agent/canvas-fn 'seon.layered-context-and-presence-test/demo-reactive-canvas}]})
            db-now @conn
            ag-now (d/pull db-now '[*] [:seon.agent/id :agent/parent-p])
            hic-now (pr-str (presence/render-live-canvas-presence {:main-db db-now :branch-db db-now :viewing-id :agent/parent-p :agent ag-now :expert-mode? false}))
            db-old (d/as-of db-now tx1)
            ag-old (d/pull db-old '[*] [:seon.agent/id :agent/parent-p])
            hic-old (pr-str (presence/render-live-canvas-presence {:main-db db-old :branch-db db-old :viewing-id :agent/parent-p :agent ag-old :expert-mode? false}))]
        (is (str/includes? hic-now "reactive-demo"))
        (is (str/includes? hic-now "cv-live-presence"))
        (is (str/includes? hic-now "Check health"))
        (is (str/includes? hic-now "{:ok? true}"))
        (is (str/includes? hic-old "Frame One"))
        (is (not (str/includes? hic-old "reactive-demo"))
            "d/as-of time-travel must cleanly rewind custom Canvas to earlier frame")))))
