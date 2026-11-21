(ns seon.chronicle-test
  "Fast (< 50 ms) unit tests for `:seon.summary/*` Malli->Datahike schema derivation, `seon.render.chronicle`
   tx-range distillation, compaction timeline budgeting, freshness triggers, Living Canvas UI, and `summary-tile`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.render :as render]
            [seon.render.canvas :as canvas]
            [seon.render.canvas.blocks :as cblocks]
            [seon.render.chronicle :as chronicle]
            [seon.render.tiles :as tiles]
            [seon.schema :as schema]
            [seon.schema.datahike :as dh]
            [clojure.core.async :as a]
            [clojure.data.json :as json]
            [seon.render.canvas.live :as clive]
            [seon.render.tail :as tail]
            [seon.web.actions :as wact]
            [seon.render.lineage :as lineage]
            [seon.transactor.calls :as tcalls]))

(def ^:private summary-forms
  {:seon.summary/id [:and {:seon.db/identity true :seon.db/content-id true} :uuid]
   :seon.summary/kind [:and {:seon.db/index true :gen/elements [:system-pulse :milestone :compaction :architecture]} :keyword]
   :seon.summary/title [:string {:min 1}]
   :seon.summary/body :string
   :seon.summary/from-tx [:and {:seon.db/index true} :int]
   :seon.summary/to-tx [:and {:seon.db/index true} :int]
   :seon.summary/created-ms [:and {:seon.db/index true} :int]
   :seon.summary/agent [:and {:seon.db/index true} :keyword]
   :seon.summary/refs [:vector {:gen/elements [[] [:agent/root 'seon.render.canvas]]} :any]
   :seon.summary/visual [:any {:gen/elements [[:div {:class "pill-row"} [:span {:class "badge green"} "Healthy"]] [:span {:class "badge blue"} "Snapshot"]]}]
   :seon.task/parent [:and {:seon.db/index true} :qualified-keyword]
   :seon.task/template? [:and {:seon.db/index true} :boolean]
   :seon.task/template [:and {:seon.db/index true} :qualified-keyword]
   :seon.task/owner [:and {:seon.db/index true} :qualified-keyword]
   :seon.task/created-by [:and {:seon.db/index true} :keyword]
   :seon.task/summary :string
   :seon.task/entity
   [:map {:seon.db/attributes true :seon.render/hiccup 'seon.render.ui/task-tile}
    [:seon.task/id :seon.task/id]
    [:seon.task/scope {:optional true} :seon.task/scope]
    [:seon.task/title :seon.task/title]
    [:seon.task/plan {:optional true} :seon.task/plan]
    [:seon.task/needs {:optional true} :seon.task/needs]
    [:seon.task/tests {:optional true} :seon.task/tests]
    [:seon.task/done? {:optional true} :seon.task/done?]
    [:seon.task/parent {:optional true} :seon.task/parent]
    [:seon.task/template? {:optional true} :seon.task/template?]
    [:seon.task/template {:optional true} :seon.task/template]
    [:seon.task/owner {:optional true} :seon.task/owner]
    [:seon.task/created-by {:optional true} :seon.task/created-by]
    [:seon.task/summary {:optional true} :seon.task/summary]]
   :seon.summary/entity
   [:map {:seon.db/attributes true
          :seon.render/hiccup 'seon.render.tiles/summary-tile
          :seon.schema/doc "Time- and Datahike-tx-anchored system or compaction summary in the Chronicle stream."}
    [:seon.summary/id :seon.summary/id]
    [:seon.summary/kind :seon.summary/kind]
    [:seon.summary/title :seon.summary/title]
    [:seon.summary/body :seon.summary/body]
    [:seon.summary/from-tx :seon.summary/from-tx]
    [:seon.summary/to-tx :seon.summary/to-tx]
    [:seon.summary/created-ms :seon.summary/created-ms]
    [:seon.summary/agent :seon.summary/agent]
    [:seon.summary/refs {:optional true} :seon.summary/refs]
    [:seon.summary/visual {:optional true} :seon.summary/visual]]})

(def ^:private test-registry
  (delay
    (let [reg (schema/compile-registry (merge @schema/disk-base-forms summary-forms))]
      {:registry reg :declarations (dh/declarations reg)})))

(defn- mem-conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}}
        _ (d/create-database cfg)
        c (d/connect cfg)]
    (d/transact c (:declarations @test-registry))
    c))

(deftest ^{:cost/ticks 6000000} summary-schema-derivation-and-validation-test
  (let [{:keys [registry declarations]} @test-registry
        by-id (into {} (map (juxt :db/ident identity)) declarations)]
    (testing "Malli derives exact Datahike attributes for :seon.summary/*"
      (is (= :db.unique/identity (:db/unique (get by-id :seon.summary/id))))
      (is (true? (:db/contentId (get by-id :seon.summary/id))))
      (is (= :db.type/uuid (:db/valueType (get by-id :seon.summary/id))))
      (is (true? (:db/index (get by-id :seon.summary/kind))))
      (is (= :db.type/long (:db/valueType (get by-id :seon.summary/from-tx))))
      (is (true? (:db/index (get by-id :seon.summary/to-tx))))
      (is (= :db.type/long (:db/valueType (get by-id :seon.summary/created-ms))))
      (is (= :db.type/data (:db/valueType (get by-id :seon.summary/refs))))
      (is (= :db.type/data (:db/valueType (get by-id :seon.summary/visual)))))
    (testing "entity validation and seed=42 deterministic generation"
      (let [s (m/schema :seon.summary/entity {:registry registry})
            sample (mg/generate s {:registry registry :seed 42 :size 4})
            tx-ent (first (chronicle/record-summary-tx {:kind :milestone :title "Shipped Chronicle"
                                                        :body "All green" :from-tx 10 :to-tx 25
                                                        :created-ms 1795172750000 :agent :agent/root
                                                        :refs [:agent/root 'seon.render.chronicle]}))]
        (is (m/validate s sample {:registry registry}))
        (is (m/validate s tx-ent {:registry registry}))
        (is (not (m/validate s (assoc tx-ent :seon.summary/title "") {:registry registry})))
        (is (= (:seon.summary/id tx-ent)
               (:seon.summary/id (first (chronicle/record-summary-tx
                                         {:kind :milestone :title "Shipped Chronicle" :body "All green"
                                          :from-tx 10 :to-tx 25 :created-ms 1795172750000 :agent :agent/root}))))
            "Deterministic UUID guarantees idempotent recording")))
    (testing "schema-attached renderer targets seon.render.tiles/summary-tile"
      (is (= 'seon.render.tiles/summary-tile (:seon.render/hiccup (second (:seon.summary/entity summary-forms))))))))

(deftest ^{:cost/ticks 12000000} record-summary-and-distill-tx-window-test
  (let [c (mem-conn)
        r1 (d/transact c [{:seon.code.form/key "seon.demo/a" :seon.code.form/ns 'seon.demo
                           :seon.code.form/order 1 :seon.code.form/src "(defn a [] 1)" :seon.code.form/hash "aaaaaaaa"}
                          {:seon.schema/key :seon.demo/x :seon.schema/form :int}])
        t1 (:max-tx (:db-after r1))
        r2 (d/transact c [{:seon.code.form/key "seon.render.canvas/f1" :seon.code.form/ns 'seon.render.canvas
                           :seon.code.form/order 1 :seon.code.form/src "(defn f1 [] 1)" :seon.code.form/hash "bbbbbbbb"}
                          {:seon.agent/id :work/canvas :seon.agent/branch :branch/canvas :seon.agent/role :worker
                           :seon.agent/scope 'seon.render.canvas :seon.agent/goal "Polish living Canvas"
                           :seon.agent/merged-tx 9001}])
        t2 (:max-tx (:db-after r2))
        r3 (d/transact c [{:seon.code.form/key "seon.code.parse/p1" :seon.code.form/ns 'seon.code.parse
                           :seon.code.form/order 1 :seon.code.form/src "(defn p1 [] 1)" :seon.code.form/hash "cccccccc"}
                          {:seon.agent/id :work/parse :seon.agent/branch :branch/parse :seon.agent/role :worker
                           :seon.agent/scope 'seon.code.parse :seon.agent/goal "Add Malli contracts"
                           :seon.agent/merged-tx 9002}])]
    (let [t3 (:max-tx (:db-after r3))
          d12 (chronicle/distill-tx-window @c t1 t2)
          d13 (chronicle/distill-tx-window @c t1 t3)]
      (testing "distill-tx-window captures exact merges, namespaces, and schemas per range"
        (is (= [:work/canvas] (mapv :id (:merges d12))))
        (is (= 'seon.render.canvas (:ns (first (:touched-nss d12)))))
        (is (= 1 (:touched-forms d12)))
        (is (= #{:work/canvas :work/parse} (set (map :id (:merges d13)))))
        (is (= 2 (:touched-forms d13)))
        (is (= t2 (chronicle/db-max-tx (d/as-of @c t2)))))
      (testing "record-summary! auto-distills, persists, and filters cleanly"
        (let [rec1 (chronicle/record-summary! c {:kind :milestone :from-tx t1 :to-tx t2 :agent :agent/root})
              rec2 (chronicle/record-summary! c {:kind :compaction :title "Compacted root" :body "Clean slate"
                                                 :from-tx t2 :to-tx t3 :agent :agent/root :refs [:work/parse]})]
          (is (:ok? rec1))
          (is (:ok? rec2))
          (is (str/includes? (:seon.summary/title (:entity rec1)) "1 merge"))
          (is (= [(:id rec2) (:id rec1)] (mapv :seon.summary/id (chronicle/latest-summaries @c))))
          (is (= [(:id rec2)] (mapv :seon.summary/id (chronicle/latest-summaries @c {:kind :compaction}))))
          (is (= [(:id rec2)] (mapv :seon.summary/id (chronicle/latest-summaries @c {:since-tx t2})))))))))

(deftest ^{:cost/ticks 8000000} compaction-timeline-and-trigger-test
  (testing "chronicle-due? obeys nothing-changed-nothing-runs and urgency rules"
    (is (= {:due? false :reason nil :urgency :none}
           (chronicle/chronicle-due? {:has-summary? true :merges-since 0 :namespaces-since 0 :tx-span 500})))
    (is (= {:due? true :reason :health-transition :urgency :high}
           (chronicle/chronicle-due? {:has-summary? true :health-changed? true})))
    (is (= {:due? true :reason :merge-batch :urgency :normal}
           (chronicle/chronicle-due? {:has-summary? true :merges-since 3 :namespaces-since 1})))
    (is (= {:due? true :reason :broad-refactor :urgency :normal}
           (chronicle/chronicle-due? {:has-summary? true :merges-since 1 :namespaces-since 4})))
    (is (= {:due? true :reason :initial :urgency :normal}
           (chronicle/chronicle-due? {:has-summary? false :merges-since 1}))))
  (testing "chronicle-for-compaction keeps chronological order, tx anchors, tail merges, and strict max-chars"
    (let [c (mem-conn)
          _ (chronicle/record-summary! c {:kind :milestone :title "Wave 1" :body "Bootstrapped core"
                                          :from-tx 100 :to-tx 200 :created-ms 1795100000000 :agent :agent/root :refs [:a/one]})
          _ (chronicle/record-summary! c {:kind :system-pulse :title "Wave 2" :body "Added wire caching and fast tests"
                                          :from-tx 200 :to-tx 300 :created-ms 1795110000000 :agent :agent/root :refs [:a/two]})
          _ (chronicle/record-summary! c {:kind :compaction :title "Wave 3" :body "Compacted root history cleanly"
                                          :from-tx 300 :to-tx 400 :created-ms 1795120000000 :agent :agent/root :refs [:a/three]})
          _ (d/transact c [{:seon.agent/id :work/tail :seon.agent/branch :branch/tail :seon.agent/role :worker
                            :seon.agent/goal "Late merge" :seon.agent/merged-tx 9999}])
          full (chronicle/chronicle-for-compaction @c :agent/root {:max-chars 2400})
          tight (chronicle/chronicle-for-compaction @c :agent/root {:max-chars 260})]
      (is (< (.indexOf ^String (:text full) "Wave 1") (.indexOf ^String (:text full) "Wave 3"))
          "Compaction timeline must be strictly chronological")
      (is (str/includes? (:text full) "tx 100..200"))
      (is (str/includes? (:text full) "tx 300..400"))
      (is (str/includes? (:text full) "d/as-of"))
      (is (str/includes? (:text full) "work/tail") "Un-summarized tail merge appears in compaction timeline")
      (is (= 1 (:tail-merges full)))
      (is (<= (:chars tight) 260))
      (is (true? (:truncated? tight)))
      (is (some? (chronicle/chronicle-tail {:main-db @c}))))))

(deftest ^{:cost/ticks 12000000} living-canvas-chronicle-and-summary-tile-render-test
  (testing "stale-canvas? folds old router visuals while leaving workers and fresh visuals untouched"
    (is (false? (cblocks/stale-canvas? {:router? false :canvas-tx 10 :latest-merge-tx 500 :main-tx 600})))
    (is (true?  (cblocks/stale-canvas? {:router? true :canvas-tx 100 :latest-merge-tx 120 :main-tx 130})))
    (is (true?  (cblocks/stale-canvas? {:router? true :canvas-tx 100 :latest-summary-tx 110 :main-tx 115})))
    (is (false? (cblocks/stale-canvas? {:router? true :canvas-tx 500 :latest-merge-tx 400 :latest-summary-tx 450 :main-tx 520}))))
  (testing "Living Canvas renders Hero + Chronicle rail, folds stale visual, and supports time-travel"
    (let [c (mem-conn)
          r-root (d/transact c [{:seon.agent/id :agent/root :seon.agent/branch :main :seon.agent/role :coordinator
                                 :seon.agent/router? true :seon.agent/entry? true :seon.agent/label "Root"
                                 :seon.agent/canvas {:title "Old Architecture Card" :summary "Stale summary" :body [:p "Old SVG"]}}])
          t-root (:max-tx (:db-after r-root))
          r-m1 (d/transact c [{:seon.agent/id :work/alpha :seon.agent/branch :branch/alpha :seon.agent/role :worker
                               :seon.agent/scope 'seon.render :seon.agent/goal "Upgrade canvas" :seon.agent/merged-tx 101}])
          t-m1 (:max-tx (:db-after r-m1))
          rec (chronicle/record-summary! c {:kind :milestone :title "Canvas & Chronicle Milestone"
                                            :body "- Shipped Living Canvas\n- Added Chronicle stream"
                                            :from-tx t-root :to-tx t-m1 :created-ms 1795172750000
                                            :agent :agent/root :refs [:work/alpha 'seon.render.chronicle]})
          r-m2 (d/transact c [{:seon.agent/id :work/beta :seon.agent/branch :branch/beta :seon.agent/role :worker
                               :seon.agent/scope 'seon.code :seon.agent/goal "Speed up parser" :seon.agent/merged-tx 102}])
          t-m2 (:max-tx (:db-after r-m2))
          live-html (render/hiccup->html (canvas/agent-canvas @c {:conn c :viewing-id :agent/root}))
          past-html (render/hiccup->html (canvas/agent-canvas @c {:conn c :viewing-id :agent/root :as-of-tx t-m1}))]
      (is (str/includes? live-html "id=\"cv-living-hero\""))
      (is (str/includes? live-html "Canvas &amp; Chronicle Milestone"))
      (is (str/includes? live-html "+1 merge since"))
      (is (str/includes? live-html "id=\"cv-chronicle\""))
      (is (str/includes? live-html "work/beta"))
      (is (str/includes? live-html "jump-canvas-tx"))
      (is (str/includes? live-html "Previous pinned visual"))
      (is (= 1 (count (re-seq #"<h1\b" live-html))) "Strictly one <h1> per view")
      (is (not (re-find #"<summary[^>]*>[^<]*<button" live-html)) "No interactive <button> nested inside <summary>")
      ;; Historical time-travel to Chronicle milestone t-m1
      (is (str/includes? past-html (str "Time-travel · tx " t-m1)))
      (is (str/includes? past-html "jump-canvas-live"))
      (is (str/includes? past-html "Historical Snapshot"))
      (is (str/includes? past-html (str "Viewing · " (chronicle/tx-range-label t-root t-m1))))
      ;; summary-tile renders cleanly to BOTH HTML and Agent Markdown!
      (let [tile (tiles/summary-tile {:mode :agent} (:entity rec))
            t-html (render/hiccup->html tile)
            t-md (render/hiccup->text tile)]
        (is (str/includes? t-html "Canvas &amp; Chronicle Milestone"))
        (is (str/includes? t-html "jump-canvas-tx"))
        (is (str/includes? t-md "Canvas & Chronicle Milestone"))
        (is (str/includes? t-md (str "(d/as-of (user/main-db) " t-m1 ")")))))))

(deftest
  lru-get-preserves-datahike-reads-on-cache-hit-test
  (let [conn (mem-conn)
        _ (d/transact
            conn
            {:tx-data
             [{:seon.summary/id (random-uuid),
               :seon.summary/kind :milestone,
               :seon.summary/title "Read-replay check",
               :seon.summary/body "body",
               :seon.summary/from-tx 0,
               :seon.summary/to-tx 1,
               :seon.summary/created-ms 1000}]})
        db @conn
        cache (atom {})
        read-fn (fn []
                  (seon.store/lru-get!
                    cache
                    8
                    [:k (:max-tx db)]
                    (fn []
                      (vec (d/datoms db :aevt :seon.summary/id)))))
        _ (read-fn)
        [r1 rs1] (datahike.reads/with-reads read-fn)
        [r2 rs2] (datahike.reads/with-reads read-fn)
        idx1 (datahike.reads/reads-index rs1)
        idx2 (datahike.reads/reads-index rs2)]
    (is (= 1 (count r1)))
    (is (= r1 r2))
    (is (contains? idx1 [:a :seon.summary/id]))
    (is (contains? idx2 [:a :seon.summary/id]))))

(deftest agent-perspective-live-canvas-and-composable-sections-test
  (testing "Any agent (not just :agent/root) gets uniform parent, live self, children merge-gate progress, task-chain/historical XML lineage, composable render sections, and single-source text projection"
    (let [conn (mem-conn)
          cid-in (random-uuid)
          cid-out (random-uuid)]
      (try
        (d/transact conn
                    {:tx-data [{:seon.task/id :template/subtask-code
                                :seon.task/title "Implement scoped subtask"
                                :seon.task/scope 'seon
                                :seon.task/template? true
                                :seon.task/plan "1. Discover first.
2. Edit & prove."
                                :seon.task/done? false}
                               {:seon.task/id :task/root-parent
                                :seon.task/title "Upgrade live agent perspective"
                                :seon.task/scope 'seon.render
                                :seon.task/done? false}
                               {:seon.task/id :task/demo
                                :seon.task/title "Build live progress tree"
                                :seon.task/parent :task/root-parent
                                :seon.task/scope 'seon.render.canvas.live
                                :seon.task/owner :agent/mid
                                :seon.task/created-by :agent/top
                                :seon.task/done? false}
                               {:seon.task/id :task/past-work
                                :seon.task/title "Earlier canvas refactor"
                                :seon.task/scope 'seon.render.canvas.live
                                :seon.task/done? true
                                :seon.task/summary "Split live blocks into composable helpers."}
                               {:seon.agent/id :agent/top :seon.agent/branch :main :seon.agent/role :coordinator :seon.agent/label "Top" :seon.agent/task :task/root-parent}
                               {:seon.agent/id :agent/mid :seon.agent/parent :agent/top :seon.agent/branch :main
                                :seon.agent/role :worker :seon.agent/label "Mid" :seon.agent/task :task/demo}
                               {:seon.agent/id :agent/leaf :seon.agent/parent :agent/mid :seon.agent/branch :main
                                :seon.agent/role :worker :seon.agent/label "Leaf" :seon.agent/ready? true}
                               {:seon.agent/id :agent/direct-kid :seon.agent/parent :agent/mid :seon.agent/branch :main
                                :seon.agent/role :worker :seon.agent/label "DirectKid" :seon.agent/goal "Audit schema"}
                               {:seon.agent.call/id cid-in :seon.agent.call/caller :agent/top :seon.agent.call/callee :agent/mid
                                :seon.agent.call/status :pending :seon.agent.call/prompt "Coordinate live canvas work" :seon.agent.call/created-tx 1}
                               {:seon.agent.call/id cid-out :seon.agent.call/caller :agent/mid :seon.agent.call/callee :agent/leaf
                                :seon.agent.call/status :pending :seon.agent.call/prompt "Verify render tests" :seon.agent.call/created-tx 2}
                               {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/leaf :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0
                                :seon.agent.eval/intent "Running canvas reach tests" :seon.agent.eval/code "(user/run-tests!)"
                                :seon.agent.eval/value "{:ok? true}" :seon.agent.eval/ms 14.0}
                               {:seon.test.run/branch :main :seon.test.run/pass? true :seon.test.run/ran 5 :seon.test.run/cached 0}]})
        (let [db @conn
              mid-ag (d/pull db '[*] [:seon.agent/id :agent/mid])
              lin (lineage/agent-task-lineage db db mid-ag)
              lin-agent-txt (render/hiccup->text (lineage/task-lineage-tile {:mode :agent} lin))
              lin-human-html (render/hiccup->html (lineage/task-lineage-tile {:mode :human} lin))
              sub (tcalls/build-subtask-tx db :agent/mid :agent/leaf (random-uuid) 'seon.render.canvas.live
                                           {:title "Verify render tests" :plan "Run reach tests"})
              parent-req (clive/parent-request-data db :agent/mid)
              tree (clive/delegation-progress-tree db conn :agent/mid)
              callees (set (map :seon.agent.call/callee tree))
              self-prog (clive/agent-branch-progress db conn :agent/mid)
              ctx-map {:seon.canvas/agent-id :agent/mid
                       :seon.canvas/branch :main
                       :seon.canvas/parent-request parent-req
                       :seon.canvas/self-progress self-prog
                       :seon.canvas/work-tree tree
                       :seon.canvas/pending-prompt {:body "Check subagent merge gates" :seq 7}
                       :seon.canvas/latest-actions [{:turn 1 :index 1 :intent "Inspecting child merge gates" :code "(user/monitor)" :value "{:ok? true}" :ms 9}]}
              in-flight-h (clive/render-in-flight-panel ctx-map)
              in-flight-txt (render/hiccup->text in-flight-h)
              composed-h (clive/compose-live-canvas ctx-map {:visual [:div "Custom visual block"] :extra [:div "Extra panel"]})
              composed-txt (render/hiccup->text composed-h)
              parent-txt (render/hiccup->text (clive/parent-request-section parent-req))
              work-txt (render/hiccup->text (clive/render-work-progress tree))
              nb-tail (tail/neighborhood-activity {:main-db db :branch-db db :agent {:seon.agent/id :agent/mid :seon.agent/role :worker} :opts {}})]
          ;; Entity-pull task chain & XML historical reference data
          (is (= :task/root-parent (get-in lin [:ancestors 0 :seon.task/id])))
          (is (str/includes? lin-agent-txt "<task-chain>"))
          (is (str/includes? lin-agent-txt ":task/root-parent"))
          (is (str/includes? lin-agent-txt "<historical-tasks>"))
          (is (str/includes? lin-agent-txt "Split live blocks into composable helpers."))
          (is (str/includes? lin-agent-txt "<task-templates>"))
          (is (str/includes? lin-agent-txt ":template/subtask-code"))
          (is (str/includes? lin-human-html "task chain"))
          ;; Subagent delegated call auto-mints a linked child task with :seon.task/parent!
          (is (= :task/demo (:seon.task/parent (first (:tx sub)))))
          (is (= :agent/mid (:seon.task/created-by (first (:tx sub)))))
          (is (= :agent/leaf (:seon.task/owner (first (:tx sub)))))
          ;; Uniform agent-perspective Canvas & composable sections
          (is (= :agent/top (:parent-id parent-req)))
          (is (= :task/demo (get-in parent-req [:task :seon.task/id])))
          (is (contains? callees :agent/leaf))
          (is (contains? callees :agent/direct-kid))
          (is (str/includes? in-flight-txt "Inspecting child merge gates"))
          (is (str/includes? in-flight-txt "Check subagent merge gates"))
          (is (str/includes? parent-txt ":agent/top"))
          (is (str/includes? parent-txt ":task/demo"))
          (is (str/includes? work-txt ":agent/leaf"))
          (is (str/includes? work-txt "Running canvas reach tests"))
          (is (str/includes? composed-txt "Custom visual block"))
          (is (str/includes? composed-txt "Extra panel"))
          (is (str/includes? composed-txt "Inspecting child merge gates"))
          (is (str/includes? (or (:text nb-tail) "") ":agent/top"))
          (is (str/includes? (or (:text nb-tail) "") ":agent/leaf")))
        (finally
          (d/release conn))))))

(deftest mcp-repl-auto-context-and-unread-delivery-test
  (testing "seon_repl advertises task/scope/context, auto-attaches context on Turn 1, respects context: false/true, and delivers unread messages"
    (let [repl-tool (some #(when (= "seon_repl" (:name %)) %) wact/mcp-tools)
          props (get-in repl-tool [:inputSchema :properties])
          srv-ch (a/chan 16)
          seen-reqs (atom [])
          eval-n (atom 0)
          _ (a/go-loop []
              (when-let [{:keys [req reply-ch]} (a/<! srv-ch)]
                (swap! seen-reqs conj req)
                (let [ans (case (:op req)
                            :eval
                            (let [n (swap! eval-n inc)]
                              {:ok? true :handle (symbol "result" (str "e" n)) :value ":ok" :ms 2.0 :ticks 1000 :branch (:branch req) :tx n})
                            :agent-context
                            {:ok? true
                             :branch (:branch req)
                             :agent-id (:agent-id req)
                             :context-text (when (:include-context? req)
                                             (str "<walk-context>scope=" (:scope req) " task=" (:task req) "</walk-context>"))
                             :unread (if (= :agent/with-mail (:agent-id req))
                                       [{:seon.agent.message/seq 3 :seon.agent.message/from :agent/root
                                         :seon.agent.message/type :chat :seon.agent.message/body "Please verify tests"}]
                                       [])}
                            {:ok? true})]
                  (a/>! reply-ch ans)
                  (a/close! reply-ch))
                (recur)))
          call-mcp (fn [args]
                     (let [resp (wact/handle-mcp-jsonrpc!
                                 srv-ch
                                 (json/write-str {:jsonrpc "2.0" :id 1 :method "tools/call"
                                                  :params {:name "seon_repl" :arguments args}}))]
                       (get-in (json/read-str (:body resp) :key-fn keyword) [:result :content 0 :text])))]
      (try
        (is (contains? props :task))
        (is (contains? props :scope))
        (is (contains? props :context))
        ;; Turn 1 (result/e1): auto-includes <walk-context> with task + scope
        (let [t1 (call-mcp {:code ":ok" :branch ":branch/t" :agent ":agent/with-mail"
                            :task ":seon.demo/t1" :scope "seon.render.canvas"})]
          (is (str/includes? t1 "<walk-context>scope=seon.render.canvas task=:seon.demo/t1</walk-context>"))
          (is (str/includes? t1 "<unread-messages>"))
          (is (str/includes? t1 "Please verify tests"))
          (is (str/includes? t1 "result/e1")))
        ;; Turn 2 (result/e2): skips <walk-context>, still delivers <unread-messages>
        (let [t2 (call-mcp {:code ":ok" :branch ":branch/t" :agent ":agent/with-mail"})]
          (is (not (str/includes? t2 "<walk-context>")))
          (is (str/includes? t2 "<unread-messages>"))
          (is (str/includes? t2 "result/e2")))
        ;; Turn 3 with context: true forces <walk-context>
        (let [t3 (call-mcp {:code ":ok" :branch ":branch/t" :agent ":agent/quiet" :context true})]
          (is (str/includes? t3 "<walk-context>"))
          (is (not (str/includes? t3 "<unread-messages>"))))
        ;; Explicit context: false suppresses <walk-context> even when scope is passed
        (let [t4 (call-mcp {:code ":ok" :branch ":branch/t" :agent ":agent/quiet" :scope "seon.health" :context false})]
          (is (not (str/includes? t4 "<walk-context>"))))
        (finally
          (a/close! srv-ch))))))