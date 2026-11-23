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
            [seon.test-fixture :refer [with-test-store]]))

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
                      sess-msg (some #(when (str/includes? (str (:content %)) "kind=\"review\"") (:content %)) (:messages wire))]
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
      (is (= 1099511627776 (:seon.config/store-map-bytes store/default-config-values)))
      (is (= 16 (:seon.config/gc-keep-merged-branches store/default-config-values)))
      (is (= 168 (:seon.config/gc-history-hours store/default-config-values)))
      (is (= 8 (:seon.config/unwritten-review-turns store/default-config-values)))
      (is (= 0.45 (:seon.config/semantic-min-score store/default-config-values)))
      (is (= 1099511627776 (get-in (store/store-cfg dir) [:store :lmdb :map-size])))
      ;; 2. Distributed Understanding: custom schema + compatible fn with :seon.code.var/input
      (d/transact conn {:tx-data
        [{:db/ident :demo.inv/id :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
         {:db/ident :demo.inv/qty :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
         {:db/id "sc1" :seon.schema/key :demo.inv/item :seon.schema/form "[:map [:demo.inv/id :keyword] [:demo.inv/qty :int]]"}
         {:db/id "inv1" :demo.inv/id :item/bolt :demo.inv/qty 42}
         {:db/id "fn1" :seon.code.var/sym 'demo.inv/total-qty :seon.code.var/ns 'demo.inv
          :seon.code.var/input ["sc1"]}
         {:seon.agent/id :ag/du-demo :seon.agent/branch :main :seon.agent/role :worker
          :seon.agent/goal "Inspect inventory" :seon.agent/target "inv1"}]})
      (let [db @conn
            fn-eid (:e (first (d/datoms db :avet :seon.code.var/sym 'demo.inv/total-qty)))
            res (ctx/related-updates db db :ag/du-demo)
            cand-eids (set (map :entity (:candidates res)))]
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
        (is (= {:ok? true :deleted 2 :kept 1} pruned))
        (is (contains? brs :main))
        (is (contains? brs :branch/active-1))
        (is (contains? brs :branch/m3))
        (is (not (contains? brs :branch/m1)))
        (is (not (contains? brs :branch/m2)))))))
