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
            [seon.render.wire :as rwire]
            [seon.schema :as schema]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor.calls :as tcalls]
            [seon.render.entities.work :as rwork]
            [seon.render.entities.infra :as rinfra]))

(deftest ^{:cost/ticks 90000000} accretive-refs-world-discovery-and-kv-stability-test
  (testing "with-ref-mirrors populates *-ref lookup-refs/tempids, agent-world-datoms finds reverse refs without busting KV-cache prefix, and :rule.ctx/delegated-calls projects children + pending/resolved calls"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data [{:seon.agent/id :agent/h2-root :seon.agent/branch :main :seon.agent/router? true :seon.agent/goal "Coordinate children"}
                                  {:seon.task/id :task/h2-sub :seon.task/title "Subtask" :seon.task/done? false}]})
      (let [db1 @conn
            w1 (rwire/build-live-wire-request db1 db1 :agent/h2-root {})
            ph1 (:prefix-hash w1)
            cid1 (random-uuid)
            cid2 (random-uuid)
            err-id (random-uuid)
            mirrored (tcalls/with-ref-mirrors
                       db1
                       [{:seon.agent/id :agent/h2-kid-active :seon.agent/branch :branch/h2-kid-active
                         :seon.agent/parent :agent/h2-root :seon.agent/goal "Active kid"}
                        {:seon.agent/id :agent/h2-kid-arch :seon.agent/branch :branch/h2-kid-arch
                         :seon.agent/parent :agent/h2-root :seon.agent/archived? true}
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
              delta-txt (apply str (keep :text (:tail-blocks w2)))
              ctx-txt (rwire/render-agent-context db2 db2 :agent/h2-root)]
          (is (contains? rev-attrs :seon.agent/parent-ref))
          (is (contains? rev-attrs :seon.agent.call/caller-ref))
          (is (contains? rev-attrs :seon.agent.call/callee-ref))
          (is (contains? rev-attrs :seon.error/agent-ref))
          (is (contains? rev-attrs :seon.agent.eval/agent-ref))
          (is (>= (count (:reverse world)) 5) "Expected reverse hits for 2 kids + 2 calls + 1 error")
          (is (contains? (:hop1-eids world) kid-eid))
          (is (= :task/h2-sub (get-in call1 [:seon.agent.call/task-ref :seon.task/id])))
          (is (= [:seon.agent/id :agent/h2-root] (:seon.error/agent-ref err-ent)))
          (is (= ph1 (:prefix-hash w2)) "Spawning children/calls/child evals MUST NOT bust parent :prefix-hash!")
          (is (not (str/includes? walk-msg "secret-child-eval-token-999")))
          (is (not (str/includes? delta-txt "secret-child-eval-token-999")))
          (is (str/includes? ctx-txt ":agent/h2-kid-active"))
          (is (not (str/includes? ctx-txt ":agent/h2-kid-arch")))
          (is (str/includes? ctx-txt "Audit metrics"))
          (is (str/includes? ctx-txt "Pending follow-up"))
          (is (not (contains? (set (map :entity (:tiles w2))) arch-eid))))))))

(deftest ^{:cost/ticks 90000000} unified-pure-hiccup-tails-and-seed42-test
  (testing "Pure entity renderers (summary + message) are seed=42 verified, preserve full bodies, and wire cleanly through Baseline 0 wire.clj + model.clj"
    (with-test-store [{:keys [conn]}]
      (let [long-body (str "Detailed prompt with call-id #uuid \"" (random-uuid) "\" " (apply str (repeat 200 "x")))
            sum-id (random-uuid)]
        (d/transact conn {:tx-data [{:seon.agent/id :agent/h2-router :seon.agent/branch :main
                                     :seon.agent/router? true :seon.agent/goal "Route"}
                                    {:seon.summary/id sum-id :seon.summary/kind :milestone :seon.summary/from-tx 1
                                     :seon.summary/to-tx 42 :seon.summary/title "Horizon 2 unified tails" :seon.summary/created-ms 1000 :seon.summary/agent :agent/h2-router
                                     :seon.summary/body "Body"}
                                    {:seon.agent.message/id (random-uuid) :seon.agent.message/seq 1
                                     :seon.agent.message/from :user :seon.agent.message/to :agent/h2-router
                                     :seon.agent.message/type :chat :seon.agent.message/body long-body}]})
        (let [db @conn
              sum-ent (d/pull db '[*] [:seon.summary/id sum-id])
              msg-ent (d/pull db '[*] (:e (first (d/datoms db :avet :seon.agent.message/to :agent/h2-router))))
              sum-res (rwork/render-summary-card {:seon.summary/entity sum-ent})
              msg-res (rinfra/render-message-card {:seon.agent.message/entity msg-ent})
              wire (rwire/build-live-wire-request db db :agent/h2-router {})
              all-wire-txt (apply str (map :content (:messages wire)))
              direct-wire (model/build-agent-wire-messages {} {:agent-id :agent/h2-router
                                                               :unread [{:from :user :type :chat :body long-body}]})
              direct-tail (:content (last (:messages direct-wire)))]
          (is (str/includes? (render/hiccup->text (:seon.ui/hiccup sum-res)) "Horizon 2 unified tails"))
          (is (str/includes? (render/hiccup->text (:seon.ui/hiccup msg-res)) long-body) "Message card preserves full body")
          (is (str/includes? all-wire-txt long-body) "Baseline 0 wire preserves full unread message body")
          (is (str/includes? direct-tail "<unread-inbox ephemeral=\"true\">"))
          (is (str/includes? direct-tail long-body))
          (is (not (str/includes? direct-tail "<message from=")))
          ;; Seed=42 Malli generative verification on pure entity renderers & parse helpers
          (doseq [sym '[seon.render.entities.work/render-summary-card
                        seon.render.entities.infra/render-message-card
                        seon.render.entities.infra/render-form-card
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

(deftest ^{:cost/ticks 60000000} activity-samples-surface-newest-entity-on-large-tables-test
  (testing "Baseline 0 project-entity must surface the newest entity by tx even when an attribute has >250 datoms (no raw avet truncation cap drops it)"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data [{:seon.agent/id :agent/h2-sample :seon.agent/branch :main
                                   :seon.agent/scope 'seon.render.wire}]})
      ;; Seed 260 tasks whose :seon.task/id values sort LOW in avet order (first 250 by value).
      (d/transact conn {:tx-data (mapv (fn [i] {:seon.task/id (keyword "task" (format "a%04d" i))
                                                :seon.task/title (str "Seed " i)
                                                :seon.task/done? false})
                                       (range 260))})
      ;; Newest task by tx: its :seon.task/id value sorts LAST in avet order, beyond any raw 250-row cap.
      (d/transact conn {:tx-data [{:seon.task/id :task/zzzz-newest :seon.task/title "Newest by tx" :seon.task/done? false}]})
      (let [db @conn
            newest-eid (:e (first (d/datoms db :avet :seon.task/id :task/zzzz-newest)))
            res (ctx/project-entity db db [:seon.system/id :root] {:target :wire :semantic? false :skip-fit? true})
            task-cands (filterv #(= :seon.task/entity (:schema %)) (:candidates res))]
        (is (> (count (d/datoms db :avet :seon.task/id)) 250) "large table seeded (>250 rows)")
        (is (some #(= newest-eid (:entity %)) task-cands)
            "newest entity by tx must surface despite >250 datoms on the attribute")
        (is (= newest-eid (:entity (first task-cands)))
            "newest entity ranks first by descending tx")))))