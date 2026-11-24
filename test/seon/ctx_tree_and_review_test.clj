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
            [seon.merge :as merge]
            [seon.model.wire :as mwire]
            [seon.code.refactor :as refactor]
            [seon.code.migrate :as migrate]
            [seon.transactor.spawn :as tspawn]
            [seon.model.messages :as messages]
            [seon.code.analyze :as analyze]
            [seon.fork.helpers :as fhelpers]
            [seon.fork.calls :as fcalls]))

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
                         (assoc :seon.agent/summary "Cleaned T2 error")
                         (assoc :seon.agent/ready? true :seon.agent/waiting? false)))
          (let [v1 @ca]
            (is (= 1 (count (:seon.agent.eval/_agent-ref v1))))
            (is (= [1] (mapv :seon.agent.eval/turn (:seon.agent.eval/_agent-ref v1))))
            (is (= "Cleaned T2 error" (:seon.agent/summary v1)))
            (is (true? (:seon.agent/ready? v1)))
            ;; Retracted eval is still preserved in d/as-of history!
            (is (= 2 (count (d/datoms (d/as-of @conn pre-tx) :avet :seon.agent.eval/agent :ag/ctx-demo))))))))))

(deftest ^{:cost/ticks 140000000} cross-branch-review-session-swap-and-rule-reset-test
(testing "Pure Datalog error-streak rule triggers COW-forked review, </agent><agent forked-from> replays worker prefix verbatim, same-branch @ctx swap! + fork merge-back cleans & resumes target, and rule stops firing with zero process flags"
  (with-test-store [{:keys [conn cfg]}]
    (d/transact conn {:tx-data [{:seon.config/key :seon.config/root :seon.config/review-every-turns 12}
                                {:db/id "w1" :seon.agent/id :w/stalled :seon.agent/branch :branch/w-stalled
                                 :seon.agent/role :worker :seon.agent/goal "Fix foo" :seon.agent/paused? false}]})
    (store/branch! conn :main :branch/w-stalled)
    (let [wconn (store/connect cfg :branch/w-stalled)]
      (try
        (let [we (:e (first (d/datoms @wconn :avet :seon.agent/id :w/stalled)))]
          (d/transact wconn {:tx-data
            [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/stalled :seon.agent.eval/agent-ref we
              :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0 :seon.agent.eval/call-id "c1" :seon.agent.eval/code "(bad1)" :seon.agent.eval/error "Err 1"}
             {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/stalled :seon.agent.eval/agent-ref we
              :seon.agent.eval/turn 2 :seon.agent.eval/call-index 0 :seon.agent.eval/call-id "c2" :seon.agent.eval/code "(bad2)" :seon.agent.eval/error "Err 2"}
             {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/stalled :seon.agent.eval/agent-ref we
              :seon.agent.eval/turn 2 :seon.agent.eval/call-index 1 :seon.agent.eval/call-id "c3" :seon.agent.eval/code "(good)" :seon.agent.eval/value ":ok"}]}))
        (let [ma (d/pull @conn '[*] [:seon.agent/id :w/stalled])
              ops (tgate/compute-review-ops @conn @wconn ma
                    {:agent-eid (:db/id ma) :active? true :raw-ready? false :waiting? false :merged? false :tool-streak 2 :head-tx (:max-tx @wconn)})]
          (is (= 2 (count ops)))
          (is (= :transact (:op (first ops))))
          (is (= :spawn-agent (:op (second ops))))
          (is (= :w/stalled (:forked-from (second ops)))))
        (d/transact conn {:tx-data [{:seon.agent/id :w/stalled :seon.agent/paused? true}]})
        (let [st0 {:conn conn :cfg cfg :msg-seq 0 :writes 0 :commit-git? false}
              _ (tspawn/handle-spawn-agent! st0
                  {:op :spawn-agent :id :review/w-stalled :branch :branch/rev-1 :parent :w/stalled
                   :forked-from :w/stalled :goal "Review :w/stalled"} (d/commit-id @conn))
              rconn (store/connect cfg :branch/rev-1)]
          (try
            (let [w-wire (rwire/build-live-wire-request @conn @wconn :w/stalled {:conn conn :vision? false})
                  r-wire (rwire/build-live-wire-request @conn @rconn :review/w-stalled {:conn conn :vision? false})
                  w-core (butlast (:messages w-wire))
                  matched (count (take-while true? (map #(= (select-keys %1 [:role :content :tool_calls :tool_call_id])
                                                            (select-keys %2 [:role :content :tool_calls :tool_call_id]))
                                                        w-core (:messages r-wire))))
                  boundary (:content (nth (:messages r-wire) matched nil))]
              (is (= (count w-core) matched) "Forked reviewer must match 100% of worker pre-tail wire prefix!")
              (is (str/starts-with? (str boundary) "</agent>\n<agent id=\":review/w-stalled\""))
              (is (str/includes? (str boundary) "### [forked-agent-evals")))
            (let [rca (ctx/ctx-atom rconn [:seon.agent/id :review/w-stalled])]
              (is (= 3 (count (get-in @rca [:seon.agent/target :seon.agent.eval/_agent-ref]))))
              (swap! rca #(-> %
                              (update-in [:seon.agent/target :seon.agent.eval/_agent-ref]
                                         (fn [es] (vec (remove :seon.agent.eval/error es))))
                              (assoc-in [:seon.agent/target :seon.agent/summary] "Use (good) directly")
                              (assoc :seon.agent/summary "Retracted 2 error evals and noted (good)." :seon.agent/ready? true)))
              (tmerge/handle-merge-op! {:conn conn :cfg cfg :msg-seq 1 :writes 0 :commit-git? false}
                {:op :merge :branch :branch/rev-1 :agent-id :review/w-stalled} (d/commit-id @conn))
              (let [wdb-after (store/head conn :branch/w-stalled)
                    mdb-after @conn
                    surviving (mapv #(d/pull wdb-after '[:seon.agent.eval/code :seon.agent.eval/error] (:e %))
                                    (d/datoms wdb-after :avet :seon.agent.eval/agent :w/stalled))
                    ma-after (d/pull mdb-after '[*] [:seon.agent/id :w/stalled])
                    ops-after (tgate/compute-review-ops mdb-after wdb-after ma-after
                                {:agent-eid (:db/id ma-after) :active? true :raw-ready? false :waiting? false :merged? false :tool-streak 1 :head-tx (:max-tx wdb-after)})]
                (is (= 1 (count surviving)))
                (is (= "(good)" (:seon.agent.eval/code (first surviving))))
                (is (= "Use (good) directly" (:seon.agent/summary (d/pull wdb-after '[:seon.agent/summary] [:seon.agent/id :w/stalled]))))
                (is (false? (:seon.agent/paused? ma-after)))
                (is (empty? ops-after) "Error-streak rule must stop firing purely from data once bad evals are retracted!")))
            (finally (d/release rconn))))
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
            (is (nil? (:scope (second (:review-ops st0)))) "Reviewer must not inherit worker :scope")
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
              (is (false? (:needs-release? st-paused)) "Paused idle worker keeps its warm OS process across reviews")
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
                  (is (str/includes? sys-txt "<agent ...>...</agent>"))
                  (is (some? sys-txt))
                  (is (str/includes? all-txt "Rules of Thumb"))
                  (is (str/includes? all-txt "Rules of Thumb") "Review rule must surface docs/rules-of-thumb.md overview"))
                ;; Reviewer cleans 100% of :w/p2's evals and writes memory, leaving :paused? true
                (let [rca (ctx/ctx-atom rconn [:seon.agent/id :review/p2])
                      old-eids (into #{} (map :e) (d/datoms @wconn :avet :seon.agent.eval/agent :w/p2))]
                  (d/transact wconn {:tx-data (conj (mapv #(vector :db/retractEntity %) old-eids) {:seon.agent/id :w/p2 :seon.agent/summary "Use demo.p2-fix/ok!"})})
                  (let [wdb-clean (store/head conn :branch/w-p2)
                      mdb-unp (:db-after (d/with @conn [{:seon.agent/id :w/p2 :seon.agent/paused? false}]))
                      st-ret (rec/derive-agent-status mdb-unp (:store mdb-unp) {:w/p2 {:pid 102 :busy? false :eval-eids old-eids}} #{} #{} #{} #{} :w/p2)]
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

(deftest ^{:cost/ticks 150000000} cycle3-review-polish-scope-edited-eids-out-cap-move-and-revert-test
  (testing "Reviewer omits worker :scope, edited-form-eids matches numeric :ident-ref-map, :out-max-chars bounds stdout independently, move-vars-tx preserves :import & reports :promoted-privates, and apply-revert-ns! restores/deletes cleanly"
    ;; 1. format-eval-output honors :out-max-chars separately from :max-chars
    (let [big-out (apply str (repeat 2000 "x"))
          s (mwire/format-eval-output {:handle 'result/e9 :ok? true :out big-out :value ":ok" :max-chars 24000 :out-max-chars 512})]
      (is (str/includes? s "chars omitted"))
      (is (str/includes? s "{:out? true}"))
      (is (< (count s) 1200)))
    (with-test-store [{:keys [conn]}]
      ;; Seed a real namespace with (:import [java.util.regex Pattern]) and a private helper
      (let [src "(ns demo.rev.orig\n  \"Orig.\"\n  (:require [clojure.string :as str])\n  (:import [java.util.regex Pattern]))\n\n(defn- helper [x] (Pattern/quote (str x)))\n\n(defn pub [x] (helper x))\n"]
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/rev/orig.clj" src false)})
        (d/transact conn {:tx-data [{:seon.config/key :seon.config/root :seon.config/review-every-turns 2}
                                    {:db/id "w1" :seon.agent/id :w/scoped :seon.agent/branch :branch/w-scoped
                                     :seon.agent/role :worker :seon.agent/scope 'demo.rev.orig
                                     :seon.agent/goal "Split orig" :seon.agent/paused? false}]})
        (store/branch! conn :main :branch/w-scoped)
        (let [wconn (store/connect (:config @conn) :branch/w-scoped)]
          (try
            ;; 2. compute-review-ops must NOT copy worker :scope onto reviewer!
            (let [ma (d/pull @conn '[*] [:seon.agent/id :w/scoped])
                  ops (tgate/compute-review-ops @conn @wconn ma
                        {:agent-eid (:db/id ma) :active? true :raw-ready? false :waiting? false :merged? false :tool-streak 2 :head-tx (:max-tx @wconn)})
                  spawn-op (second ops)]
              (is (= :spawn-agent (:op spawn-op)))
              (is (nil? (:scope spawn-op)) "Reviewer must not inherit worker :scope")
              (is (= :w/scoped (:forked-from spawn-op)))
              (is (= [:template/review-and-improve] (:tasks spawn-op))))
            ;; 3. move-vars-tx preserves (:import ...) on new child ns & reports :promoted-privates in dry-run
            (let [plan (refactor/move-vars-tx @wconn {:from 'demo.rev.orig :to 'demo.rev.child :vars '[helper]})]
              (is (:ok? plan))
              (is (= '[helper] (:promoted-privates plan)))
              (is (string? (:contract-note plan)))
              (let [hdr-op (some #(when (and (map? %) (= "demo.rev.child#ns" (:seon.code.form/key %))) %) (:tx-data plan))]
                (is (str/includes? (:seon.code.form/src hdr-op) "(:import [java.util.regex Pattern])"))))
            ;; 4. Apply move-vars on branch -> edited-form-eids MUST detect edited forms (even with numeric :ident-ref-map!)
            (let [res (refactor/apply-move-vars! wconn {:from 'demo.rev.orig :to 'demo.rev.child :vars '[helper] :reload? false})]
              (is (:ok? res))
              (is (seq (rules/edited-form-eids @conn @wconn)) "edited-form-eids must find branch code edits via :ident-ref-map")
              (is (true? (tgate/branch-has-code-edits? @conn @wconn)))
              ;; 5. apply-revert-ns! deletes branch-created child ns AND restores modified orig ns cleanly!
              (let [r-del (migrate/apply-revert-ns! wconn 'demo.rev.child)
                    r-res (migrate/apply-revert-ns! wconn 'demo.rev.orig)]
                (is (:ok? r-del))
                (is (= :deleted (:action r-del)))
                (is (empty? (code/ns-forms @wconn 'demo.rev.child)))
                (is (:ok? r-res))
                (is (= :restored (:action r-res)))
                (is (str/includes? (or (code/render-path @wconn "src/demo/rev/orig.clj") "") "(defn- helper [x]"))))
            (finally (d/release wconn))))))))

(deftest turn-numbers-compaction-hop-ctx-friction-and-typed-callers-test
  (testing "1. Wire messages include explicit turn N headers so compacted turn gaps are obvious"
    (let [msgs (vec (messages/turns->wire-messages {}
                      [{:turn 1 :thought "first" :calls [{:id "c1" :handle (quote result/e1) :code "(+ 1 2)" :ok? true :value "3"}]}
                       {:turn 5 :thought "after compact" :calls [{:id "c5" :handle (quote result/e5) :code "(+ 3 4)" :ok? true :value "7"}]}]))
          tool-contents (mapv :content (filter #(= "tool" (:role %)) msgs))]
      (is (= 2 (count tool-contents)))
      (is (str/starts-with? (first tool-contents) "; turn 1 · result/e1 · ok"))
      (is (str/starts-with? (second tool-contents) "; turn 5 · result/e5 · ok"))))
  (testing "2. Compaction detection derives missing turns from d/history and fires history doc hop, plus ctx-friction progressive disclosure"
    (with-test-store [{:keys [conn]}]
      (let [aid :agent/compact-probe]
        (d/transact conn {:tx-data [{:seon.agent/id aid :seon.agent/branch :main :seon.agent/role :worker}
                                    {:seon.code.doc/id "docs/primers.md#history-and-time-travel"
                                     :seon.code.doc/path "docs/primers.md" :seon.code.doc/order 10
                                     :seon.code.doc/heading "## History & Time-Travel" :seon.code.doc/body "Use d/history"}
                                    {:seon.code.doc/id "docs/primers.md#ctx-atom"
                                     :seon.code.doc/path "docs/primers.md" :seon.code.doc/order 11
                                     :seon.code.doc/heading "## Ctx Atom" :seon.code.doc/body "Use swap! ctx"}]})
        (let [ag-eid (:e (first (d/datoms @conn :avet :seon.agent/id aid)))
              e1-id (random-uuid) e2-id (random-uuid) e3-id (random-uuid)]
          (d/transact conn {:tx-data [{:seon.agent.eval/id e1-id :seon.agent.eval/agent aid :seon.agent.eval/agent-ref ag-eid
                                       :seon.agent.eval/turn 1 :seon.agent.eval/code "(+ 1 1)" :seon.agent.eval/value "2"}
                                      {:seon.agent.eval/id e2-id :seon.agent.eval/agent aid :seon.agent.eval/agent-ref ag-eid
                                       :seon.agent.eval/turn 2 :seon.agent.eval/code "(bad)" :seon.agent.eval/error "boom"}
                                      {:seon.agent.eval/id e3-id :seon.agent.eval/agent aid :seon.agent.eval/agent-ref ag-eid
                                       :seon.agent.eval/turn 3 :seon.agent.eval/code "(+ 2 2)" :seon.agent.eval/value "4"}]})
          ;; Before compaction: nil info, no ctx friction
          (is (nil? (rstatus/compacted-turns-info @conn aid)))
          (is (nil? (rstatus/latest-ctx-friction-tx @conn aid)))
          ;; Retract turn 2 via true [:db/retractEntity]
          (let [e2-eid (:e (first (d/datoms @conn :avet :seon.agent.eval/id e2-id)))]
            (d/transact conn {:tx-data [[:db/retractEntity e2-eid]]}))
          (let [info (rstatus/compacted-turns-info @conn aid)
                wire (rwire/build-live-wire-request @conn @conn aid {:conn conn :vision? false})]
            (is (= [2] (:retracted-turns info)))
            (is (= 1 (:retracted-evals info)))
            (is (= [1 3] (:live-turns info)))
            (is (some #(and (= "user" (:role %))
                            (string? (:content %))
                            (str/includes? (:content %) "Compacted turns: [2]"))
                      (:messages wire))))
          ;; Now record a ctx error eval -> latest-ctx-friction-tx returns non-nil tx
          (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent aid :seon.agent.eval/agent-ref ag-eid
                                       :seon.agent.eval/turn 4 :seon.agent.eval/code "(swap! ctx assoc :bad 1)"
                                       :seon.agent.eval/error "Schema rail rejected"}]})
          (is (integer? (rstatus/latest-ctx-friction-tx @conn aid)))))))
  (testing "3. extract-domain-touched-eids scales beyond old 64 cap up to 256"
    (let [entries (mapv (fn [i] [{:e (+ 1000 i) :a :seon.code.var/name} {:e (+ 1000 i) :a :seon.code.var/name}]) (range 120))
          res (analyze/extract-domain-touched-eids nil entries nil nil 256)]
      (is (= 120 (count res)))))
  (testing "4. user/callers and user/callees return schema-typed ns-qualified maps"
    (with-test-store [{:keys [conn]}]
      (let [tx (code/file-import-tx @conn "src/demo/typed_callers.clj"
                 "(ns demo.typed-callers)\n(defn leaf [x] (inc x))\n(defn caller [x] (leaf x))\n" false)]
        (d/transact conn {:tx-data tx})
        (binding [fcalls/*agent-ctx* {:conn conn :branch :main :agent-id :repl/test}]
          (let [cs (fhelpers/callers (quote demo.typed-callers/leaf))
                cl (fhelpers/callees (quote demo.typed-callers/caller))]
            (is (= (quote demo.typed-callers/leaf) (:seon.code.var/name cs)))
            (is (= [(quote demo.typed-callers/caller)] (:seon.code.var/callers cs)))
            (is (= :seon.code.var/callers-result (:seon/schema (meta cs))))
            (is (= (quote demo.typed-callers/caller) (:seon.code.var/name cl)))
            (is (= :seon.code.var/callees-result (:seon/schema (meta cl))))))))))