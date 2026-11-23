(ns seon.horizon2-graph-tail-test
  "Horizon 2 proofs: accretive :db.type/ref domain graph, safe generic 2-read world discovery,
   :rule.ctx/delegation-tree child & call visibility, unified pure Hiccup ephemeral tails,
   parse-time static form metrics, and tightened :seon.bench.run/* schemas."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.parse :as parse]
            [seon.ctx :as ctx]
            [seon.ctx.project :as proj]
            [seon.error :as serr]
            [seon.health.efficiency :as eff]
            [seon.model :as model]
            [seon.render :as render]
            [seon.render.tails :as rtails]
            [seon.render.wire :as rwire]
            [seon.schema :as schema]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor.calls :as tcalls]))

(deftest ^{:cost/ticks 90000000} accretive-refs-world-discovery-and-kv-stability-test
  (testing "with-ref-mirrors populates *-ref lookup-refs/tempids, agent-world-datoms finds reverse refs without busting KV-cache prefix, and delegation-tree projects children + pending/resolved calls"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data [{:seon.agent/id :agent/h2-root :seon.agent/branch :main :seon.agent/role :coordinator
                                   :seon.agent/router? true :seon.agent/goal "Coordinate children"}
                                  {:seon.task/id :task/h2-sub :seon.task/title "Subtask" :seon.task/done? false}]})
      ;; Turn 1 baseline wire request (before child agents / calls exist)
      (let [db1 @conn
            w1 (rwire/build-live-wire-request db1 db1 :agent/h2-root {})
            ph1 (:prefix-hash w1)
            cid1 (random-uuid)
            cid2 (random-uuid)
            err-id (random-uuid)
            mirrored (tcalls/with-ref-mirrors
                       db1
                       [{:seon.agent/id :agent/h2-kid-active :seon.agent/branch :branch/h2-kid-active
                         :seon.agent/role :worker :seon.agent/parent :agent/h2-root :seon.agent/goal "Active kid"}
                        {:seon.agent/id :agent/h2-kid-arch :seon.agent/branch :branch/h2-kid-arch
                         :seon.agent/role :worker :seon.agent/parent :agent/h2-root :seon.agent/archived? true}
                        {:seon.agent.call/id cid1 :seon.agent.call/caller :agent/h2-root
                         :seon.agent.call/callee :agent/h2-kid-active :seon.agent.call/prompt "Audit metrics"
                         :seon.agent.call/status :resolved :seon.agent.call/result {:ok? true}
                         :seon.agent.call/task-ref [:seon.task/id :task/h2-sub]}
                        {:seon.agent.call/id cid2 :seon.agent.call/caller :agent/h2-root
                         :seon.agent.call/callee :agent/h2-kid-active :seon.agent.call/prompt "Pending follow-up"
                         :seon.agent.call/status :pending}
                        (serr/error-tx {:id err-id :kind :demo/h2-err :source :eval
                                        :agent :agent/h2-root :message "Root error"})])]
        (d/transact conn {:tx-data mirrored})
        ;; Add a child eval that MUST NOT leak into parent <walk-context>
        (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/h2-kid-active
                                     :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0
                                     :seon.agent.eval/code "(secret-child-eval-token-999)"
                                     :seon.agent.eval/value "secret-child-value-999"}]})
        (let [db2 @conn
              world (proj/agent-world-datoms db2 db2 :agent/h2-root)
              rev-attrs (set (:reverse-attrs world))
              kid-eid (:e (first (d/datoms db2 :avet :seon.agent/id :agent/h2-kid-active)))
              arch-eid (:e (first (d/datoms db2 :avet :seon.agent/id :agent/h2-kid-arch)))
              call1 (tcalls/pull-call db2 cid1)
              err-ent (serr/attach-agent-ref db2 (serr/error-tx {:kind :demo/h2-e2 :message "m" :agent :agent/h2-root}))
              w2 (rwire/build-live-wire-request db2 db2 :agent/h2-root {})
              walk-msg (get-in w2 [:messages 1 :content])
              rule-tail (some #(when (= "rule-tail" (:tag %)) (:text %)) (:tail-blocks w2))
              ctx-txt (rwire/render-agent-context db2 db2 :agent/h2-root)]
          ;; 1. Reverse refs discovered, > 0 hits, zero :seon.agent.eval/* attrs, volatile refs stay out of :connected/:static-prefix
          (is (contains? rev-attrs :seon.agent/parent-ref))
          (is (contains? rev-attrs :seon.agent.call/caller-ref))
          (is (contains? rev-attrs :seon.agent.call/callee-ref))
          (is (contains? rev-attrs :seon.error/agent-ref))
          (is (not-any? #(= "seon.agent.eval" (namespace %)) rev-attrs))
          (is (>= (count (:reverse world)) 5) "Expected reverse hits for 2 kids + 2 calls + 1 error")
          (is (contains? (:reverse-ephemeral world) kid-eid))
          (is (not (contains? (:connected world) kid-eid)) "Dynamic child must not enter :connected/:static-prefix")
          (is (= :task/h2-sub (get-in call1 [:seon.agent.call/task-ref :seon.task/id])))
          (is (= [:seon.agent/id :agent/h2-root] (:seon.error/agent-ref err-ent)))
          ;; 2. KV-cache prefix stays byte-identical across child spawn, calls & child eval turns!
          (is (= ph1 (:prefix-hash w2)) "Spawning children/calls/child evals MUST NOT bust parent :prefix-hash!")
          (is (not (str/includes? walk-msg "secret-child-eval-token-999")))
          (is (not (str/includes? walk-msg ":agent/h2-kid-active")))
          (is (not (str/includes? (str rule-tail) "secret-child-eval-token-999")))
          ;; 3. :rule.ctx/delegation-tree projects active child + BOTH :resolved and :pending calls into <rule-tail>, excluding archived child!
          (is (string? rule-tail))
          (is (str/includes? rule-tail ":agent/h2-kid-active"))
          (is (not (str/includes? rule-tail ":agent/h2-kid-arch")))
          (is (str/includes? rule-tail "Audit metrics"))
          (is (str/includes? rule-tail "Pending follow-up"))
          (is (str/includes? ctx-txt ":agent/h2-kid-active"))
          (is (not (contains? (set (map :entity (filter #(= :static-prefix (:zone %)) (:tiles w2)))) arch-eid))))))))

(deftest ^{:cost/ticks 90000000} unified-pure-hiccup-tails-and-seed42-test
  (testing "render-chronicle-tail & render-inbox-tail are pure, seed=42 verified, preserve full bodies, and wire cleanly through wire.clj + model.clj"
    (with-test-store [{:keys [conn]}]
      (let [long-body (str "Detailed prompt with call-id #uuid \"" (random-uuid) "\" " (apply str (repeat 200 "x")))
            sum-id (random-uuid)]
        (d/transact conn {:tx-data [{:seon.agent/id :agent/h2-router :seon.agent/branch :main
                                     :seon.agent/role :coordinator :seon.agent/router? true :seon.agent/goal "Route"}
                                    {:seon.summary/id sum-id :seon.summary/kind :milestone :seon.summary/from-tx 1
                                     :seon.summary/to-tx 42 :seon.summary/title "Horizon 2 unified tails"
                                     :seon.summary/body "Body"}
                                    {:seon.agent.message/id (random-uuid) :seon.agent.message/seq 1
                                     :seon.agent.message/from :user :seon.agent.message/to :agent/h2-router
                                     :seon.agent.message/type :chat :seon.agent.message/body long-body}]})
        (let [db @conn
              chr-res (rtails/render-chronicle-tail {:seon.tail/chronicle (rtails/gather-chronicle-data db)})
              inb-res (rtails/render-inbox-tail {:seon.tail/inbox {:unread [{:seon.agent.message/from :user
                                                                             :seon.agent.message/type :chat
                                                                             :seon.agent.message/body long-body}]}})
              wire (rwire/build-live-wire-request db db :agent/h2-router {})
              tail-tags (set (map :tag (:tail-blocks wire)))
              tail-msg-txt (:content (last (:messages wire)))
              direct-wire (model/build-agent-wire-messages {} {:agent-id :agent/h2-router
                                                               :unread [{:from :user :type :chat :body long-body}]})
              direct-tail (:content (last (:messages direct-wire)))]
          (is (= "chronicle" (:tag chr-res)))
          (is (str/includes? (render/hiccup->text (:seon.ui/hiccup chr-res)) "Horizon 2 unified tails"))
          (is (= "unread-inbox" (:tag inb-res)))
          (is (str/includes? (render/hiccup->text (:seon.ui/hiccup inb-res)) long-body) "Unread inbox preserves full body")
          (is (contains? tail-tags "chronicle"))
          (is (contains? tail-tags "unread-inbox"))
          (is (str/includes? tail-msg-txt "Horizon 2 unified tails"))
          (is (str/includes? tail-msg-txt long-body))
          (is (str/includes? direct-tail "<unread-inbox ephemeral=\"true\">"))
          (is (str/includes? direct-tail long-body))
          (is (not (str/includes? direct-tail "<message from=")))
          ;; Seed=42 Malli generative verification on all new/updated pure functions
          (doseq [sym '[seon.render.tails/render-chronicle-tail
                        seon.render.tails/render-inbox-tail
                        seon.render.tails/tail-block-from-res
                        seon.code.parse/nested-db-scan-in-form?
                        seon.code.parse/form-static-metrics]]
            (let [res (schema/stress-var db sym {:samples 100 :seed 42})]
              (is (true? (:ok? res)) (str sym " failed seed=42 check: " (pr-str res))))))))))

(deftest ^{:cost/ticks 90000000} parse-time-form-metrics-and-bench-run-schemas-test
  (testing "read-clj emits :lines/:chars/:nested-scan?, static-efficiency-warnings consumes persisted metrics without re-parsing :src, and bench.run schemas validate"
    (with-test-store [{:keys [conn]}]
      (let [src (str "(ns seon.h2.metrics.demo (:require [datahike.api :as d]))\n\n"
                     "(defn clean-fn [x]\n  (inc x))\n\n"
                     "(defn bad-scan [db xs]\n  (mapcat #(d/datoms db :aevt %) xs))\n\n"
                     "(def constant-val 42)\n")
            parsed (parse/read-clj "src/seon/h2/metrics/demo.clj" src)
            by-k (into {} (map (juxt :seon.code.form/key identity)) (:forms parsed))]
        (is (= 0 (:seon.code.form/lines (get by-k "seon.h2.metrics.demo#ns"))))
        (is (= 0 (:seon.code.form/lines (get by-k "seon.h2.metrics.demo/constant-val"))))
        (is (= 2 (:seon.code.form/lines (get by-k "seon.h2.metrics.demo/clean-fn"))))
        (is (false? (:seon.code.form/nested-scan? (get by-k "seon.h2.metrics.demo/clean-fn"))))
        (is (= 2 (:seon.code.form/lines (get by-k "seon.h2.metrics.demo/bad-scan"))))
        (is (true? (:seon.code.form/nested-scan? (get by-k "seon.h2.metrics.demo/bad-scan"))))
        (is (pos? (:seon.code.form/chars (get by-k "seon.h2.metrics.demo/bad-scan"))))
        ;; Persist via file-import-tx + synthetic persisted-metrics-only form (clean :src, flagged metrics -> proves :aevt read without :src re-parse!)
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/h2/metrics/demo.clj" src false)})
        (d/transact conn {:tx-data [{:seon.code.form/key "seon.h2.metrics.demo/persisted-only"
                                     :seon.code.form/ns 'seon.h2.metrics.demo
                                     :seon.code.form/order 9
                                     :seon.code.form/src "(defn persisted-only [] :ok)"
                                     :seon.code.form/hash "h2persistedonly01"
                                     :seon.code.form/lines 150
                                     :seon.code.form/chars 8000
                                     :seon.code.form/nested-scan? true}]})
        (let [db @conn
              ws (eff/static-efficiency-warnings db 'seon.h2.metrics.demo)
              by-subj (group-by :subject ws)]
          (is (some #(= :nested-db-scan (:class %)) (get by-subj "seon.h2.metrics.demo/bad-scan")))
          (is (some #(= :large-function (:class %)) (get by-subj "seon.h2.metrics.demo/persisted-only"))
              "static-efficiency-warnings must read persisted :seon.code.form/{lines,chars} instead of re-parsing :src")
          (is (some #(= :nested-db-scan (:class %)) (get by-subj "seon.h2.metrics.demo/persisted-only"))
              "static-efficiency-warnings must read persisted :seon.code.form/nested-scan? instead of re-parsing :src")
          ;; Tightened :seon.bench.run/{trials,proxy-stats} schemas accept real rows & reject invalid values
          (is (:ok? (schema/validate-against db :seon.bench.run/trials
                                             [{:id :t1 :status :merged :port 50000 :evals 5 :elapsed-ms 1200 :boot-ms 400.5
                                               :merged? true :health-ok? true :alive? false :last-snippet "e5" :last-error nil}])))
          (is (not (:ok? (schema/validate-against db :seon.bench.run/trials {:not :a-vector}))))
          (is (:ok? (schema/validate-against db :seon.bench.run/proxy-stats
                                             {:requests 10 :in-flight 0 :rejected 0 :errors 0 :prompt-tokens 5000
                                              :completion-tokens 500 :cache-hit-tokens 4500 :total-tokens 5500
                                              :max-in-flight 3 :max-requests nil :max-tokens 600000
                                              :upstream-url "https://api.deepseek.com"})))
          (is (not (:ok? (schema/validate-against db :seon.bench.run/proxy-stats "not-a-map")))))))))
