(ns seon.cleanup-miner-test
  "Unit tests for `seon.code.mine` hygiene/jolt-metrics miners, `seon.transactor.spawn/handle-create-task!`
   curated/template wiring, and `seon.ctx.ground/situational-candidates` cold-start semantic doc discovery."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [malli.generator :as mg]
            [seon.code.mine :as mine]
            [seon.ctx.ground :as ground]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor.spawn :as spawn]
            [seon.vector :as vec]))

(defn- unit-vec [dims axis]
  (mapv #(if (= % axis) 1.0 0.0) (range dims)))

(deftest mine-hygiene-findings-all-classes-test
  (with-test-store [{:keys [conn]}]
    (let [big-body (str "(defn huge-fn [x]\n" (str/join "\n" (repeat 130 "  x")) ")")
          oversized-src (str "(def big-blob \"" (apply str (repeat 33000 "a")) "\")")
          tx [{:seon.code.ns/name 'demo.a}
              {:seon.code.ns/name 'demo.b}
              {:seon.code.ns/name 'demo.big}
              {:seon.code.ns/name 'seon.store}
              ;; 1) Exact AST-hash clone across 2 namespaces
              {:seon.code.form/key "demo.a/clone-fn" :seon.code.form/ns 'demo.a :seon.code.form/order 1
               :seon.code.form/hash "deadbeef11111111" :seon.code.form/src "(defn clone-fn [x] (+ x 1))"}
              {:seon.code.var/name 'demo.a/clone-fn :seon.code.var/ns 'demo.a :seon.code.var/form "demo.a/clone-fn"}
              {:seon.code.form/key "demo.b/clone-fn" :seon.code.form/ns 'demo.b :seon.code.form/order 1
               :seon.code.form/hash "deadbeef11111111" :seon.code.form/src "(defn clone-fn [x] (+ x 1))"}
              {:seon.code.var/name 'demo.b/clone-fn :seon.code.var/ns 'demo.b :seon.code.var/form "demo.b/clone-fn"}
              ;; 2) Independent same-name duplicate helper across 2 namespaces + a delegating wrapper & excluded render-*
              {:seon.code.form/key "demo.a/helper-x" :seon.code.form/ns 'demo.a :seon.code.form/order 2
               :seon.code.form/hash "aaaa111100000001" :seon.code.form/src "(defn helper-x [s] (str s))"}
              {:seon.code.var/name 'demo.a/helper-x :seon.code.var/ns 'demo.a :seon.code.var/form "demo.a/helper-x"}
              {:seon.code.form/key "demo.b/helper-x" :seon.code.form/ns 'demo.b :seon.code.form/order 2
               :seon.code.form/hash "bbbb222200000002" :seon.code.form/src "(defn helper-x [s] (str/trim s))"}
              {:seon.code.var/name 'demo.b/helper-x :seon.code.var/ns 'demo.b :seon.code.var/form "demo.b/helper-x"}
              {:seon.code.form/key "demo.a/deleg-fn" :seon.code.form/ns 'demo.a :seon.code.form/order 3
               :seon.code.form/hash "cccc333300000003" :seon.code.form/src "(defn deleg-fn [x] (demo.b/deleg-fn x))"}
              {:seon.code.var/name 'demo.a/deleg-fn :seon.code.var/ns 'demo.a :seon.code.var/form "demo.a/deleg-fn"
               :seon.code.var/refs #{'demo.b/deleg-fn}}
              {:seon.code.form/key "demo.b/deleg-fn" :seon.code.form/ns 'demo.b :seon.code.form/order 3
               :seon.code.form/hash "dddd444400000004" :seon.code.form/src "(defn deleg-fn [x] x)"}
              {:seon.code.var/name 'demo.b/deleg-fn :seon.code.var/ns 'demo.b :seon.code.var/form "demo.b/deleg-fn"}
              {:seon.code.form/key "demo.a/render-card" :seon.code.form/ns 'demo.a :seon.code.form/order 4
               :seon.code.form/hash "eeee555500000005" :seon.code.form/src "(defn render-card [x] [:div x])"}
              {:seon.code.var/name 'demo.a/render-card :seon.code.var/ns 'demo.a :seon.code.var/form "demo.a/render-card"}
              {:seon.code.form/key "demo.b/render-card" :seon.code.form/ns 'demo.b :seon.code.form/order 4
               :seon.code.form/hash "ffff666600000006" :seon.code.form/src "(defn render-card [x] [:span x])"}
              {:seon.code.var/name 'demo.b/render-card :seon.code.var/ns 'demo.b :seon.code.var/form "demo.b/render-card"}
              ;; 3) Raw Java IO outside store/browser edge vs exempted edge inside seon.store
              {:seon.code.form/key "demo.a/bad-io" :seon.code.form/ns 'demo.a :seon.code.form/order 5
               :seon.code.form/hash "1111aaaa00000007" :seon.code.form/src "(defn bad-io [p] (.delete (java.io.File. p)))"}
              {:seon.code.var/name 'demo.a/bad-io :seon.code.var/ns 'demo.a :seon.code.var/form "demo.a/bad-io"}
              {:seon.code.form/key "seon.store/ok-edge" :seon.code.form/ns 'seon.store :seon.code.form/order 1
               :seon.code.form/hash "2222bbbb00000008" :seon.code.form/src "(defn ok-edge [p] (.delete (java.io.File. p)))"}
              {:seon.code.var/name 'seon.store/ok-edge :seon.code.var/ns 'seon.store :seon.code.var/form "seon.store/ok-edge"
               :seon.code.var/flow-edge? true}
              ;; 4) Oversized namespace + large function
              {:seon.code.form/key "demo.big/big-blob" :seon.code.form/ns 'demo.big :seon.code.form/order 1
               :seon.code.form/hash "3333cccc00000009" :seon.code.form/src oversized-src}
              {:seon.code.var/name 'demo.big/big-blob :seon.code.var/ns 'demo.big :seon.code.var/form "demo.big/big-blob"}
              {:seon.code.form/key "demo.a/huge-fn" :seon.code.form/ns 'demo.a :seon.code.form/order 6
               :seon.code.form/hash "4444dddd00000010" :seon.code.form/src big-body}
              {:seon.code.var/name 'demo.a/huge-fn :seon.code.var/ns 'demo.a :seon.code.var/form "demo.a/huge-fn"}]
          _ (d/transact conn {:tx-data tx})
          fs (mine/mine-hygiene-findings @conn)
          by-kind (group-by :kind fs)]
      (testing "detects all 5 classes with verified curated refs and filters delegators/edges/excluded names"
        (is (= 1 (count (:ast-clone by-kind))))
        (is (= [[:seon.code.var/name 'demo.a/clone-fn] [:seon.code.var/name 'demo.b/clone-fn]]
               (:curated (first (:ast-clone by-kind)))))
        (is (= 1 (count (:duplicate-helper by-kind))))
        (is (= [[:seon.code.var/name 'demo.a/helper-x] [:seon.code.var/name 'demo.b/helper-x]]
               (:curated (first (:duplicate-helper by-kind)))))
        (is (= 1 (count (:raw-java-io by-kind))))
        (is (= [[:seon.code.var/name 'demo.a/bad-io]] (:curated (first (:raw-java-io by-kind)))))
        (is (= 1 (count (:oversized-namespace by-kind))))
        (is (= [[:seon.code.ns/name 'demo.big]] (:curated (first (:oversized-namespace by-kind)))))
        (is (= 1 (count (:large-function by-kind))))
        (is (= [[:seon.code.var/name 'demo.a/huge-fn]] (:curated (first (:large-function by-kind)))))
        (is (= (mapv :impact fs) (reverse (sort (mapv :impact fs)))))))))

(deftest ingest-jolt-metrics-and-tasks-tx-test
  (with-test-store [{:keys [conn]}]
    (let [tpl-footer "1. Inspect curated refs.
2. Refactor & prove green."
          _ (d/transact conn
              {:tx-data [{:seon.code.ns/name 'demo.alpha}
                         {:seon.code.ns/name 'demo.beta}
                         {:seon.code.ns/name 'demo.hot}
                         {:seon.code.ns/name 'demo.coupled}
                         {:seon.code.var/name 'demo.alpha/complex-fn :seon.code.var/ns 'demo.alpha}
                         {:seon.code.var/name 'demo.alpha/simple-fn :seon.code.var/ns 'demo.alpha}
                         {:seon.code.var/name 'demo.alpha/dup-a :seon.code.var/ns 'demo.alpha}
                         {:seon.code.var/name 'demo.beta/dup-b :seon.code.var/ns 'demo.beta}
                         {:seon.code.var/name 'demo.alpha-test/covers-complex :seon.code.var/ns 'demo.alpha-test
                          :seon.code.var/test? true :seon.code.var/refs #{'demo.alpha/complex-fn 'demo.alpha/dup-a}}
                         {:seon.task/id :template/hygiene-cleanup :seon.task/scope 'seon :seon.task/template? true
                          :seon.task/title "Hygiene cleanup" :seon.task/plan tpl-footer :seon.task/done? false}]})
          metrics-edn {:files [{:path "/r/src/demo/alpha.clj" :namespace "demo.alpha" :efferent-coupling 4 :cyclomatic 45}
                               {:path "/r/src/demo/beta.clj" :namespace "demo.beta" :efferent-coupling 3 :cyclomatic 10}
                               {:path "/r/src/demo/coupled.clj" :namespace "demo.coupled" :efferent-coupling 15 :cyclomatic 80}]
                       :queue [{:kind :function :severity :critical :path "/r/src/demo/alpha.clj" :name "complex-fn"
                                :score 28.5 :metrics {:cyclomatic 34 :cognitive 62 :nesting 6}}
                               {:kind :function :severity :critical :path "/r/src/demo/alpha.clj" :name "simple-fn"
                                :score 5.0 :metrics {:cyclomatic 2 :cognitive 1 :nesting 1}}]
                       :duplicates [{:distance 0.04 :a {:path "/r/src/demo/alpha.clj" :name "dup-a"}
                                     :b {:path "/r/src/demo/beta.clj" :name "dup-b"}}
                                    {:distance 0.02 :a {:path "/r/src/demo/alpha.clj" :name "ghost"}
                                     :b {:path "/r/src/demo/beta.clj" :name "dup-b"}}]
                       :hotspots [{:path "/r/src/demo/hot.clj" :changes 120 :complexity 95 :risk 0.72}]}
          jm (mine/ingest-jolt-metrics-findings @conn metrics-edn)
          by-kind (group-by :kind jm)]
      (testing "ingest-jolt-metrics-findings maps complexity, NCD pairs, hotspots, and coupling with real refs"
        (is (= [] (mine/ingest-jolt-metrics-findings @conn nil)))
        (is (= 1 (count (:high-complexity by-kind))))
        (is (= [[:seon.code.var/name 'demo.alpha/complex-fn]] (:curated (first (:high-complexity by-kind)))))
        (is (= 1 (count (:near-duplicate by-kind))))
        (is (= [[:seon.code.var/name 'demo.alpha/dup-a] [:seon.code.var/name 'demo.beta/dup-b]]
               (:curated (first (:near-duplicate by-kind)))))
        (is (= 2 (count (:coupling-hotspot by-kind))))
        (is (= #{'demo.hot 'demo.coupled} (set (map :scope (:coupling-hotspot by-kind))))))
      (testing "mine-hygiene-tasks-tx emits template-separated tasks with curated refs, reaching tests, and idempotence"
        (let [txs (mine/mine-hygiene-tasks-tx @conn {:metrics-edn metrics-edn :limit 10})
              by-id (into {} (map (juxt :seon.task/id identity)) txs)
              cx-t (get by-id :hygiene/complexity-demo-alpha-complex-fn)]
          (is (= 4 (count txs)))
          (is (some? cx-t))
          (is (= :template/hygiene-cleanup (:seon.task/template cx-t)))
          (is (= :seon.code/mine (:seon.task/created-by cx-t)))
          (is (false? (:seon.task/done? cx-t)))
          (is (= [[:seon.code.var/name 'demo.alpha/complex-fn]] (:seon.task/curated cx-t)))
          (is (= #{'demo.alpha-test/covers-complex} (:seon.task/tests cx-t)))
          (is (str/includes? (:seon.task/plan cx-t) "

---
"))
          (is (not (str/includes? (first (str/split (:seon.task/plan cx-t) #"\n\n---\n")) "Inspect curated refs")))
          ;; Transact all tasks + mark one done -> re-mining is 100% idempotent & never reopens done!
          (d/transact conn {:tx-data txs})
          (d/transact conn {:tx-data [{:seon.task/id (:seon.task/id cx-t) :seon.task/done? true}]})
          (is (= [] (mine/mine-hygiene-tasks-tx @conn {:metrics-edn metrics-edn :limit 10})))
          (is (true? (:seon.task/done? (d/pull @conn '[:seon.task/done?] [:seon.task/id (:seon.task/id cx-t)])))))))))

(deftest spawn-create-task-curated-and-template-test
  (with-test-store [{:keys [conn cfg]}]
    (d/transact conn
      {:tx-data [{:seon.code.ns/name 'demo.spawn}
                 {:seon.code.var/name 'demo.spawn/target-fn :seon.code.var/ns 'demo.spawn}
                 {:seon.code.var/name 'demo.spawn/tpl-fn :seon.code.var/ns 'demo.spawn}
                 {:seon.task/id :template/hygiene-cleanup :seon.task/scope 'demo.spawn :seon.task/template? true
                  :seon.task/title "Hygiene" :seon.task/plan "Template checklist." :seon.task/done? false
                  :seon.task/tests #{'demo.spawn-test/base-test}
                  :seon.task/curated [[:seon.code.var/name 'demo.spawn/tpl-fn]]}]})
    (let [rch (a/chan 1)
          st {:conn conn :cfg cfg :msg-seq 0 :writes 0 :commit-git? false}
          [_ _] (spawn/handle-create-task!
                  st
                  {:op :create-task :id :hygiene/from-op :from-template :template/hygiene-cleanup
                   :title "Clean target" :plan "Consolidate target-fn."
                   :curated [[:seon.code.var/name (list 'quote 'demo.spawn/target-fn)] [:bad]]
                   :tests ['demo.spawn-test/extra-test] :reply-ch rch}
                  (d/commit-id @conn))
          reply (a/<!! rch)
          ent (d/pull @conn '[:seon.task/id :seon.task/scope :seon.task/template :seon.task/plan
                              :seon.task/curated :seon.task/tests :seon.task/done?]
                      [:seon.task/id :hygiene/from-op])]
      (is (:ok? reply))
      (is (= :hygiene/from-op (:seon.task/id ent)))
      (is (= 'demo.spawn (:seon.task/scope ent)))
      (is (= :template/hygiene-cleanup (:seon.task/template ent)))
      (is (= [[:seon.code.var/name 'demo.spawn/tpl-fn] [:seon.code.var/name 'demo.spawn/target-fn]]
             (:seon.task/curated ent)))
      (is (= #{'demo.spawn-test/base-test 'demo.spawn-test/extra-test} (set (:seon.task/tests ent))))
      (is (= "Consolidate target-fn." (first (str/split (:seon.task/plan ent) #"\n\n---\n"))))
      (is (str/includes? (:seon.task/plan ent) "Template checklist.")))))

(deftest cold-start-semantic-doc-discovery-and-seed42-gen-test
  (testing "attach-semantic-scores scores semantically nearest docs and vars cleanly above unrelated candidates"
  (with-test-store [{:keys [conn]}]
    (let [r1 (d/transact conn
               {:tx-data [{:db/id "t1" :seon.task/id :demo/cold-doc :seon.task/title "improve routing" :seon.task/plan "" :seon.task/done? false}
                          {:db/id "ag1" :seon.agent/id :agent/cold :seon.agent/branch :main :seon.agent/tasks #{"t1"}}
                          {:db/id "v1" :seon.code.var/name 'demo.rt/match-url :seon.code.var/ns 'demo.rt}
                          {:db/id "d1" :seon.code.doc/id "docs/routing.md#overview" :seon.code.doc/path "docs/routing.md" :seon.code.doc/order 0
                           :seon.code.doc/heading "# Routing" :seon.code.doc/body "Reitit routes"}
                          {:db/id "d3" :seon.code.doc/id "docs/unrelated.md#other" :seon.code.doc/path "docs/unrelated.md" :seon.code.doc/order 0
                           :seon.code.doc/heading "# Other" :seon.code.doc/body "Unrelated"}]})
          tm (:tempids r1)
          te (get tm "t1") ve (get tm "v1") d1 (get tm "d1") d3 (get tm "d3")
          v-match (unit-vec 128 0)
          v-other (unit-vec 128 7)
          vtx (vec/tx-data-for-batch
                [{:eid te :digest "t"} {:eid ve :digest "v"} {:eid d1 :digest "d1"} {:eid d3 :digest "d3"}]
                [v-match v-match v-match v-other]
                [8 8 8 8]
                "test-model+v1")
          _ (d/transact conn {:tx-data vtx})
          db @conn
          ag (d/pull db '[:seon.agent/id :seon.agent/scope :seon.agent/tasks] [:seon.agent/id :agent/cold])
          scored (ground/attach-semantic-scores db db ag [{:entity ve} {:entity d1} {:entity d3}])
          by-e (into {} (map (juxt :entity :semantic)) scored)]
      (is (> (double (get by-e ve 0.0)) (double (get by-e d3 0.0))))
      (is (> (double (get by-e d1 0.0)) (double (get by-e d3 0.0)))))))
  (testing "deterministic seed=42 Malli generative checks on pure hygiene miner helpers"
    (let [slug-inputs (mg/generate [:vector {:min 100 :max 100} [:maybe :string]] {:seed 42 :size 16})
          path-inputs (mg/generate [:vector {:min 100 :max 100} [:maybe :string]] {:seed 42 :size 16})]
      (is (every? #(let [out (mine/normalize-hygiene-slug %)]
                     (and (string? out) (pos? (count out)) (<= (count out) 56) (boolean (re-matches #"[a-z0-9-]+" out))))
                  slug-inputs))
      (is (every? #(let [out (mine/path->ns-sym %)]
                     (or (nil? out) (symbol? out)))
                  path-inputs))
      (is (= 'seon.signals.cache (mine/path->ns-sym "/repo/src/seon/signals/cache.clj")))
      (is (= 'foo.bar-baz (mine/path->ns-sym "test/foo/bar_baz.cljc"))))))
