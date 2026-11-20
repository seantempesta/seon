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
            [seon.schema.datahike :as dh]))

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
