(ns seon.ctx-tree-and-review-test
  "Proves pure 2-index `@ctx` tree (`:eavt` + `:avet`), reverse-ref `swap!` write-back,
   Distributed Understanding on arbitrary custom schemas, depth-adaptive self-printer dispatch,
   `<session kind=\"review\">` prefix framing, and pure Datalog stall-rule reset."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.ctx.tree :as tree]
            [seon.render.print :as rprint]
            [seon.render.wire :as rwire]
            [seon.rule :as rules]
            [seon.store :as store]
            [seon.task.gate :as tgate]
            [seon.test-fixture :refer [with-test-store]]
            [seon.reconciler :as rec]
            [seon.transactor.merge :as tmerge]
            [seon.render.status :as rstatus]
            [seon.render.surface.session :as rsession]
            [seon.code :as code]
            [seon.merge :as merge]))

(deftest ^{:cost/ticks 120000000} pure-two-index-ctx-swap-and-custom-data-test
  (testing "Pure 2-index @ctx attaches evals via :seon.agent.eval/_agent-ref, retracts via swap!, and generalizes to arbitrary custom schemas"
    (with-test-store [{:keys [conn]}]
      ;; Register custom domain schema (:demo.shop/order + :demo.shop.line/order-ref)
      (d/transact conn {:tx-data
        [{:db/ident :demo.shop/id :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
         {:db/ident :demo.shop/customer :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
         {:db/ident :demo.shop.line/sku :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
         {:db/ident :demo.shop.line/order-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one :db/index true}]})
      (d/transact conn {:tx-data
        [{:db/id "ord1" :demo.shop/id :order/a1 :demo.shop/customer "Ada"}
         {:demo.shop.line/sku "WIDGET-1" :demo.shop.line/order-ref "ord1"}
         {:demo.shop.line/sku "WIDGET-2" :demo.shop.line/order-ref "ord1"}
         {:db/id "ag1" :seon.agent/id :ag/ctx-demo :seon.agent/branch :main :seon.agent/role :worker
          :seon.agent/goal "Demo 2-index ctx" :seon.agent/target "ord1"}
         {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/ctx-demo :seon.agent.eval/agent-ref "ag1"
          :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0 :seon.agent.eval/code "(+ 1 1)" :seon.agent.eval/value "2" :seon.agent.eval/ticks 100}
         {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/ctx-demo :seon.agent.eval/agent-ref "ag1"
          :seon.agent.eval/turn 2 :seon.agent.eval/call-index 0 :seon.agent.eval/code "(boom)" :seon.agent.eval/error "Boom" :seon.agent.eval/ticks 200}]})
      (let [ca (ctx/ctx-atom conn [:seon.agent/id :ag/ctx-demo])
            v0 @ca
            evs0 (:seon.agent.eval/_agent-ref v0)
            ord0 (:seon.agent/target v0)]
        ;; 1. Zero-whitelist 2-index tree attaches evals with ALL real datoms (including :ticks)
        (is (= 2 (count evs0)))
        (is (= [1 2] (mapv :seon.agent.eval/turn evs0)))
        (is (= [100 200] (mapv :seon.agent.eval/ticks evs0)))
        ;; 2. Custom domain entity attached at :seon.agent/target automatically has its reverse ref :demo.shop.line/_order-ref!
        (is (= "Ada" (:demo.shop/customer ord0)))
        (is (= #{"WIDGET-1" "WIDGET-2"} (set (map :demo.shop.line/sku (:demo.shop.line/_order-ref ord0)))))
        ;; 3. Plain Clojure swap! retracts error evals via [:db/retractEntity], sets memory, and signals ready? true!
        (let [pre-tx (:max-tx @conn)]
          (swap! ca #(-> %
                         (update :seon.agent.eval/_agent-ref (fn [es] (vec (remove :seon.agent.eval/error es))))
                         (update :seon.agent/memory (fnil conj []) "Cleaned T2 error")
                         (assoc :seon.agent/ready? true :seon.agent/waiting? false)))
          (let [v1 @ca]
            (is (= 1 (count (:seon.agent.eval/_agent-ref v1))))
            (is (= [1] (mapv :seon.agent.eval/turn (:seon.agent.eval/_agent-ref v1))))
            (is (= ["Cleaned T2 error"] (:seon.agent/memory v1)))
            (is (true? (:seon.agent/ready? v1)))
            ;; Retracted eval is still preserved in d/as-of history!
            (is (= 2 (count (d/datoms (d/as-of @conn pre-tx) :avet :seon.agent.eval/agent :ag/ctx-demo))))))))))

(deftest ^{:cost/ticks 120000000} cross-branch-review-session-swap-and-rule-reset-test
  (testing "Pure Datalog error-streak rule triggers review, <session kind=\"review\"> loads target branch evals, cross-branch @ctx swap! cleans & resumes target, and rule stops firing with zero process flags"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data [{:seon.config/key :seon.config/root :seon.config/review-every-turns 12}
                                  {:db/id "w1" :seon.agent/id :w/stalled :seon.agent/branch :branch/w-stalled
                                   :seon.agent/role :worker :seon.agent/goal "Fix foo" :seon.agent/paused? false}]})
      (store/branch! conn :main :branch/w-stalled)
      (let [wconn (store/connect (:config @conn) :branch/w-stalled)]
        (try
          (let [we (:e (first (d/datoms @wconn :avet :seon.agent/id :w/stalled)))]
            (d/transact wconn {:tx-data
              [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/stalled :seon.agent.eval/agent-ref we
                :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0 :seon.agent.eval/code "(bad1)" :seon.agent.eval/error "Err 1"}
               {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/stalled :seon.agent.eval/agent-ref we
                :seon.agent.eval/turn 2 :seon.agent.eval/call-index 0 :seon.agent.eval/code "(bad2)" :seon.agent.eval/error "Err 2"}
               {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/stalled :seon.agent.eval/agent-ref we
                :seon.agent.eval/turn 2 :seon.agent.eval/call-index 1 :seon.agent.eval/code "(good)" :seon.agent.eval/value ":ok"}]}))
          ;; 1. Reconciler's rule-driven compute-review-ops detects :error-streak -> pauses :w/stalled & spawns reviewer!
          (let [ma (d/pull @conn '[*] [:seon.agent/id :w/stalled])
                ops (tgate/compute-review-ops @conn @wconn ma
                      {:agent-eid (:db/id ma) :active? true :raw-ready? false :waiting? false :merged? false :tool-streak 2 :head-tx (:max-tx @wconn)})]
            (is (= 2 (count ops)))
            (is (= :transact (:op (first ops))))
            (is (= :spawn-agent (:op (second ops))))
            (is (= [:seon.agent/id :w/stalled] (:target (second ops)))))
          ;; 2. Create reviewer on :branch/rev-1 targeting :w/stalled (paused on :main)
          (let [we-main (:e (first (d/datoms @conn :avet :seon.agent/id :w/stalled)))]
            (d/transact conn {:tx-data [{:seon.agent/id :w/stalled :seon.agent/paused? true}
                                        {:seon.agent/id :review/w-stalled :seon.agent/branch :branch/rev-1
                                         :seon.agent/role :worker :seon.agent/goal "Review :w/stalled"
                                         :seon.agent/target we-main}]})
            (store/branch! conn :main :branch/rev-1)
            (let [rconn (store/connect (:config @conn) :branch/rev-1)]
              (try
                ;; 3. <session kind="review"> loads :w/stalled's branch evals in reviewer's KV-cached prefix!
                (let [wire (rwire/build-live-wire-request @conn @rconn :review/w-stalled {:conn rconn})
                      sess-msg (some #(when (and (= "user" (:role %)) (str/includes? (str (:content %)) "kind=\"review\"")) (:content %)) (:messages wire))]
                  (is (some? sess-msg) "Reviewer must receive <session kind=\"review\"> in wire prefix")
                  (is (str/includes? sess-msg ":w/stalled")))
                ;; 4. Reviewer cleans :w/stalled's error evals & unpauses via ONE plain Clojure swap! on @ctx!
                (let [rca (ctx/ctx-atom rconn [:seon.agent/id :review/w-stalled])]
                  (is (= 3 (count (get-in @rca [:seon.agent/target :seon.agent.eval/_agent-ref]))))
                  (swap! rca #(-> %
                                  (update-in [:seon.agent/target :seon.agent.eval/_agent-ref]
                                             (fn [es] (vec (remove :seon.agent.eval/error es))))
                                  (update-in [:seon.agent/target :seon.agent/memory]
                                             (fnil conj []) "Use (good) directly")
                                  (assoc-in [:seon.agent/target :seon.agent/paused?] false)))
                  ;; Verify target branch cleaned, main unpaused, AND rule stops firing with ZERO process flags!
                  (let [wdb-after (store/head conn :branch/w-stalled)
                        mdb-after @conn
                        surviving (mapv #(d/pull wdb-after '[:seon.agent.eval/code :seon.agent.eval/error] (:e %))
                                        (d/datoms wdb-after :avet :seon.agent.eval/agent :w/stalled))
                        ma-after (d/pull mdb-after '[*] [:seon.agent/id :w/stalled])
                        ops-after (tgate/compute-review-ops mdb-after wdb-after ma-after
                                    {:agent-eid (:db/id ma-after) :active? true :raw-ready? false :waiting? false :merged? false :tool-streak 1 :head-tx (:max-tx wdb-after)})]
                    (is (= 1 (count surviving)))
                    (is (= "(good)" (:seon.agent.eval/code (first surviving))))
                    (is (= ["Use (good) directly"] (:seon.agent/memory (d/pull wdb-after '[:seon.agent/memory] [:seon.agent/id :w/stalled]))))
                    (is (false? (:seon.agent/paused? ma-after)))
                    (is (empty? ops-after) "Error-streak rule must stop firing purely from data once bad evals are retracted!")))
                (finally (d/release rconn)))))
          (finally (d/release wconn)))))))

(deftest ^{:cost/ticks 150000000} distributed-understanding-and-store-gc-config-test
  (testing "Malli-defaulted store/review configs, Distributed Understanding on arbitrary custom schemas, and rolling merged-branch pruning"
    (with-test-store [{:keys [conn dir]}]
      ;; 1. Malli-defaulted config values & 1 TB sparse LMDB default
      (doseq [k [:seon.config/store-map-bytes :seon.config/gc-keep-merged-branches
                 :seon.config/gc-history-hours :seon.config/unwritten-review-turns
                 :seon.config/semantic-min-score]]
        (let [sform (:seon.schema/form (d/pull @conn '[:seon.schema/form] [:seon.schema/key k]))
              sdef (when (and (vector? sform) (map? (second sform))) (:default (second sform)))]
          (is (some? sdef) (str "Config schema must declare :default on " k))
          (is (= sdef (get store/default-config-values k)) (str "default-config-values must match schema :default for " k))))
      (is (= (:seon.config/store-map-bytes store/default-config-values)
             (get-in (store/store-cfg dir) [:store :lmdb :map-size])))
      ;; 2. Distributed Understanding: custom Malli entity schema + compatible fn with :seon.code.var/input
      (d/transact conn {:tx-data
        [{:db/ident :demo.inv/id :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
         {:db/ident :demo.inv/qty :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
         {:seon.schema/key :demo.inv/id :seon.schema/form [:and {:seon.db/identity true} :keyword]}
         {:seon.schema/key :demo.inv/qty :seon.schema/form :int}
         {:db/id "sc1" :seon.schema/key :demo.inv/item
          :seon.schema/form [:map {:seon.db/attributes true} [:demo.inv/id :demo.inv/id] [:demo.inv/qty :demo.inv/qty]]}
         {:db/id "inv1" :demo.inv/id :item/bolt :demo.inv/qty 42}
         {:db/id "fn1" :seon.code.var/name 'demo.inv/total-qty :seon.code.var/ns 'demo.inv
          :seon.code.var/input ["sc1"]}
         {:seon.agent/id :ag/du-demo :seon.agent/branch :main :seon.agent/role :worker
          :seon.agent/goal "Inspect inventory" :seon.agent/target "inv1"}]})
      (let [db @conn
            sc-eid (:e (first (d/datoms db :avet :seon.schema/key :demo.inv/item)))
            fn-eid (:e (first (d/datoms db :avet :seon.code.var/name 'demo.inv/total-qty)))
            res (ctx/related-updates {:seon.db/main db :seon.db/branch db :seon.agent/id :ag/du-demo})
            cand-eids (set (map :entity (:seon.ctx/projections res)))]
        (is (contains? cand-eids sc-eid)
            "Attaching an arbitrary custom-schema entity must automatically surface its Malli schema via pure Datalog rules")
        (is (contains? cand-eids fn-eid)
            "Attaching an arbitrary custom-schema entity must automatically surface compatible functions via pure Datalog rules"))
      ;; 3. Rolling branch GC preserves newest N merged branches + active unmerged branches + :main
      (doseq [b [:branch/m1 :branch/m2 :branch/m3 :branch/active-1]]
        (store/branch! conn :main b))
      (d/transact conn {:tx-data
        [{:seon.agent/id :ag/m1 :seon.agent/branch :branch/m1 :seon.agent/role :worker :seon.agent/goal "m1" :seon.agent/merged-tx 100}
         {:seon.agent/id :ag/m2 :seon.agent/branch :branch/m2 :seon.agent/role :worker :seon.agent/goal "m2" :seon.agent/merged-tx 200}
         {:seon.agent/id :ag/m3 :seon.agent/branch :branch/m3 :seon.agent/role :worker :seon.agent/goal "m3" :seon.agent/merged-tx 300}
         {:seon.agent/id :ag/act1 :seon.agent/branch :branch/active-1 :seon.agent/role :worker :seon.agent/goal "active"}]})
      (let [pruned (store/prune-merged-branches! conn 1)
            brs (set (d/branches @conn))]
        (is (= {:ok? true :deleted 2 :archived 2 :kept 1} pruned))
        (is (contains? brs :main))
        (is (contains? brs :branch/active-1))
        (is (contains? brs :branch/m3))
        (is (not (contains? brs :branch/m1)))
        (is (not (contains? brs :branch/m2)))))))

(deftest ^{:cost/ticks 250000000} phase2-review-restart-loop-sync-release-and-kv-anchor-test
  (testing "Trigger-tick race, pause/retract release, active-review call protection, reviewer post-ready prose turn, idempotent :resume-reviewed-target, clean merge-back after sync, frozen post-restart KV prefix under :main mutation, and multi-section rules-of-thumb rule"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data
        (concat
         (code/file-import-tx @conn "docs/rules-of-thumb.md" "# Rules of Thumb\n\nIntro.\n\n## 1. Fix the Data Model First\n\nShape schemas first.\n" false)
         (code/file-import-tx @conn "src/demo/p2_target.clj" "(ns demo.p2-target)\n(defn f [] 1)\n" false)
         (code/file-import-tx @conn "src/demo/p2_shared.clj" "(ns demo.p2-shared)\n(defn v [] 1)\n" false)
         [{:seon.config/key :seon.config/root :seon.config/review-every-turns 12}
          {:db/id "w1" :seon.agent/id :w/p2 :seon.agent/branch :branch/w-p2 :seon.agent/scope 'demo.p2-target
           :seon.agent/role :worker :seon.agent/goal "Improve demo.p2-target" :seon.agent/paused? false}])})
      (store/branch! conn :main :branch/w-p2)
      (let [wconn (store/connect cfg :branch/w-p2)
            call-id (random-uuid)]
        (try
          ;; Worker edits its branch and hits 2 consecutive errors
          (let [we (:e (first (d/datoms @wconn :avet :seon.agent/id :w/p2)))]
            (d/transact wconn {:tx-data
              (concat
               (code/file-import-tx @wconn "src/demo/p2_target.clj" "(ns demo.p2-target)\n(defn f [] 2)\n" false)
               [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/p2 :seon.agent.eval/agent-ref we
                 :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0 :seon.agent.eval/call-id "c1"
                 :seon.agent.eval/code "(boom1)" :seon.agent.eval/error "Err 1"}
                {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/p2 :seon.agent.eval/agent-ref we
                 :seon.agent.eval/turn 2 :seon.agent.eval/call-index 0 :seon.agent.eval/call-id "c2"
                 :seon.agent.eval/code "(boom2)" :seon.agent.eval/error "Err 2"}])}))
          ;; 1. Trigger-tick race eliminated: needs-turn? is FALSE on the tick review-ops triggers!
          (let [st0 (rec/derive-agent-status @conn (:store @conn) {:w/p2 {:pid 101 :busy? false}} #{} #{} #{} #{} :w/p2)]
            (is (= 2 (count (:review-ops st0))))
            (is (= 'demo.p2-target (:scope (second (:review-ops st0)))))
            (is (false? (:needs-turn? st0)) "Must not run worker turn on the same tick review triggers"))
          ;; 2. Pause worker + spawn reviewer + add pending incoming ask! call -> pause releases OS proc AND does NOT kill parent call!
          (let [we-main (:e (first (d/datoms @conn :avet :seon.agent/id :w/p2)))
                del-mid (random-uuid)]
            (d/transact conn {:tx-data [{:seon.agent/id :w/p2 :seon.agent/paused? true}
                                        {:seon.agent.call/id call-id :seon.agent.call/caller :agent/root :seon.agent.call/callee :w/p2
                                         :seon.agent.call/status :pending :seon.agent.call/delivered? false}
                                        {:seon.agent/id :review/p2 :seon.agent/branch :branch/rev-p2 :seon.agent/scope 'demo.p2-target
                                         :seon.agent/role :worker :seon.agent/goal "Review :w/p2" :seon.agent/target we-main}
                                        {:seon.agent.message/id del-mid :seon.agent.message/seq 1 :seon.agent.message/from :w/p2
                                         :seon.agent.message/to :review/p2 :seon.agent.message/type :delegate :seon.agent.message/body "Review"}]})
            (let [st-paused (rec/derive-agent-status @conn (:store @conn) {:w/p2 {:pid 101 :busy? false}} #{} #{} #{} #{} :w/p2)]
              (is (true? (:needs-release? st-paused)) "Paused idle worker must release its warm OS process")
              (is (empty? (:auto-settle-ops st-paused)) "Paused worker under active review must NOT fail parent ask! call"))
            ;; 3. Session UI work-bar shows "under review" on paused worker and "reviewing" on active reviewer
            (let [w-ag (d/pull @conn '[*] [:seon.agent/id :w/p2])
                  r-ag (d/pull @conn '[*] [:seon.agent/id :review/p2])
                  w-ui (pr-str (rsession/render-session-body {:agent w-ag :viewing-id :w/p2 :branch :branch/w-p2 :main-db @conn :branch-db @wconn :entities [] :exchanges [] :slot-budget {:avail-w 900} :active? false :expert-mode? true} false))
                  r-ui (pr-str (rsession/render-session-body {:agent r-ag :viewing-id :review/p2 :branch :branch/rev-p2 :main-db @conn :branch-db @conn :entities [] :exchanges [] :slot-budget {:avail-w 900} :active? false :expert-mode? true} false))]
              (is (str/includes? w-ui "under review"))
              (is (str/includes? r-ui "reviewing")))
            ;; 4. Reviewer context surfaces ALL sections of docs/rules-of-thumb.md & kind="review" warning
            (store/branch! conn :main :branch/rev-p2)
            (let [rconn (store/connect cfg :branch/rev-p2)]
              (try
                (let [rwire (rwire/build-live-wire-request @conn @rconn :review/p2 {:conn rconn})
                      sys-txt (:content (first (:messages rwire)))
                      rev-txt (some #(when (and (= "user" (:role %)) (str/includes? (str (:content %)) "kind=\"review\"")) (:content %)) (:messages rwire))
                      all-txt (str/join "\n" (map :content (:messages rwire)))]
                  (is (str/includes? sys-txt "kind=\"review\""))
                  (is (some? rev-txt))
                  (is (str/includes? rev-txt "do NOT unpause target manually"))
                  (is (str/includes? all-txt "Fix the Data Model First") "Review rule must surface full docs/rules-of-thumb.md sections"))
                ;; Reviewer cleans 100% of :w/p2's evals and writes memory, leaving :paused? true
                (let [rca (ctx/ctx-atom rconn [:seon.agent/id :review/p2])
                      old-eids (set (map :db/id (get-in @rca [:seon.agent/target :seon.agent.eval/_agent-ref])))]
                  (swap! rca #(-> % (assoc-in [:seon.agent/target :seon.agent.eval/_agent-ref] [])
                                    (update-in [:seon.agent/target :seon.agent/memory] (fnil conj []) "Use demo.p2-fix/ok!")))
                  (let [wdb-clean (store/head conn :branch/w-p2)
                        st-ret (rec/derive-agent-status @conn (:store @conn) {:w/p2 {:pid 102 :busy? false :eval-eids old-eids}} #{} #{} #{} #{} :w/p2)]
                    (is (true? (:needs-release? st-ret)))
                    (is (pos? (rstatus/branch-reset-tx wdb-clean :w/p2)))))
                ;; 5. Reviewer calls ready? true -> reconciler grants 1 post-ready prose summary turn!
                (d/transact rconn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :review/p2
                                              :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0 :seon.agent.eval/call-id "r1"
                                              :seon.agent.eval/seen-messages #{del-mid} :seon.agent.eval/code "(user/ready!)" :seon.agent.eval/value "{:ok? true}"}
                                             {:seon.agent/id :review/p2 :seon.agent/ready? true}]})
                (let [rst-pre (rec/derive-agent-status @conn (:store @conn) {} #{} #{} #{} #{} :review/p2)]
                  (is (true? (:needs-turn? rst-pre)) "Reviewer must get 1 post-ready prose summary turn before test/merge"))
                ;; Reviewer writes prose summary & merges changes onto :main (adds demo.p2-fix + updates demo.p2-shared 1->2)
                (d/transact rconn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :review/p2
                                              :seon.agent.eval/turn 2 :seon.agent.eval/call-index 0 :seon.agent.eval/code ""
                                              :seon.agent.eval/thought "Cleaned 2 error evals and added demo.p2-fix/ok!."}]})
                (let [rev-btx (:max-tx @rconn)]
                  (d/transact conn {:tx-data
                    (concat (code/file-import-tx @conn "src/demo/p2_fix.clj" "(ns demo.p2-fix)\n(defn ok! [] :fixed)\n" false)
                            (code/file-import-tx @conn "src/demo/p2_shared.clj" "(ns demo.p2-shared)\n(defn v [] 2)\n" false)
                            [{:seon.agent/id :review/p2 :seon.agent/ready? true :seon.agent/tested-tx rev-btx :seon.agent/merged-tx rev-btx}])})
                  ;; 6. compute-review-ops emits :resume-reviewed-target; handle-resume-reviewed-target! syncs :main, unpauses, & sends :review-note!
                  (let [rev-ma (d/pull @conn '[*] [:seon.agent/id :review/p2])
                        ops (tgate/compute-review-ops @conn @rconn rev-ma
                              {:agent-eid (:db/id rev-ma) :active? true :raw-ready? true :waiting? false :merged? true :tool-streak 0 :head-tx (:max-tx @rconn)})
                        _ (is (= [:resume-reviewed-target] (mapv :op ops)))
                        st-in {:conn conn :cfg cfg :msg-seq 1 :writes 0}
                        [_ _] (tmerge/handle-resume-reviewed-target! st-in (first ops) (d/commit-id @conn))
                        mdb-post @conn
                        wdb-post (store/head conn :branch/w-p2)
                        w-status (rec/derive-agent-status mdb-post (:store mdb-post) {} #{} #{} #{} #{} :w/p2)]
                    (is (false? (:seon.agent/paused? (d/pull mdb-post '[:seon.agent/paused?] [:seon.agent/id :w/p2]))))
                    (is (true? (:needs-turn? w-status)) "Unpaused worker with 0 surviving evals must wake on :review-note!")
                    (is (some? (first (d/datoms wdb-post :avet :seon.code.var/name 'demo.p2-fix/ok!))))
                    (is (str/includes? (code/render-path wdb-post "src/demo/p2_target.clj") "(defn f [] 2)"))
                    ;; Blocker 5 proof: re-pausing :w/p2 later must NOT re-trigger :resume-reviewed-target from old merged :review/p2!
                    (let [repaused-db (:db-after (d/with mdb-post [{:seon.agent/id :w/p2 :seon.agent/paused? true}]))
                          ops-again (tgate/compute-review-ops repaused-db @rconn rev-ma
                                      {:agent-eid (:db/id rev-ma) :active? true :raw-ready? true :waiting? false :merged? true :tool-streak 0 :head-tx (:max-tx @rconn)})]
                      (is (empty? ops-again) "Old merged reviewer must never unpause a re-paused target"))
                    ;; Blocker 9B proof: post-restart KV prefix stays byte-identical EVEN WHEN main-db mutates between Turn 1 and Turn 2!
                    (let [w1 (rwire/build-live-wire-request mdb-post wdb-post :w/p2 {:conn conn})
                          ph1 (:prefix-hash w1)
                          we2 (:e (first (d/datoms wdb-post :avet :seon.agent/id :w/p2)))]
                      (is (str/includes? (str/join "\n" (map :content (:messages w1))) "Use demo.p2-fix/ok!"))
                      ;; Mutate :main (advance demo.p2-fix :fixed -> :fixed-v2) AND worker commits Turn 3 + edits synced demo.p2-shared 2->4!
                      (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/p2_fix.clj" "(ns demo.p2-fix)\n(defn ok! [] :fixed-v2)\n" false)})
                      (let [wconn2 (store/connect cfg :branch/w-p2)]
                        (try
                          (d/transact wconn2 {:tx-data
                            (concat
                             (code/file-import-tx @wconn2 "src/demo/p2_shared.clj" "(ns demo.p2-shared)\n(defn v [] 4)\n" false)
                             [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/p2 :seon.agent.eval/agent-ref we2
                               :seon.agent.eval/turn 3 :seon.agent.eval/call-index 0 :seon.agent.eval/call-id "c3"
                               :seon.agent.eval/code "(+ 1 2)" :seon.agent.eval/value "3"}])})
                          (let [w2 (rwire/build-live-wire-request @conn @wconn2 :w/p2 {:conn conn})]
                            (is (= ph1 (:prefix-hash w2)) "Post-restart KV prefix must stay byte-identical even when main-db advances!"))
                          ;; Blocker 8 proof: merging :branch/w-p2 back to :main has ZERO false conflicts and does NOT revert demo.p2-fix!
                          (let [plan-back (merge/three-way conn cfg :branch/w-p2 {:skip-health? true})]
                            (is (true? (:ok? plan-back)) (pr-str (:conflicts plan-back)))
                            (is (empty? (:conflicts plan-back)))
                            (d/transact conn {:tx-data (:tx-data plan-back)})
                            (is (str/includes? (code/render-path @conn "src/demo/p2_fix.clj") ":fixed-v2") "Untouched synced entity must NOT revert :main!")
                            (is (str/includes? (code/render-path @conn "src/demo/p2_shared.clj") "(defn v [] 4)") "Worker edit on top of synced entity must merge cleanly!"))
                          (finally (d/release wconn2))))))) 
                (finally (d/release rconn))))) 
          (finally (d/release wconn)))))))