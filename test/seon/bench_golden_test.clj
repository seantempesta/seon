(ns seon.bench-golden-test
  "Tests for the unrigged 8-task `:golden/v1` benchmark suite, no-rigging lint, multi-commit scoreboard delta math, cluster telemetry, and dual-footprint renderers."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.bench.cluster :as cluster]
            [seon.bench.golden :as golden]
            [seon.code :as code]
            [seon.render :as render]
            [seon.render.entities.diagnostics :as rdiag]
            [seon.render.fit :as rfit]
            [seon.render.text :as rtext]
            [seon.schema :as schema]
            [seon.test-fixture :refer [with-test-store]]))

(def ^:private expected-golden-ids
  [:golden/v1-split-calls
   :golden/v1-metrics-schema
   :golden/v1-test-run-renderers
   :golden/v1-silent-catch
   :golden/v1-split-canvas-body
   :golden/v1-bench-rule
   :golden/v1-mine-alloc
   :golden/v1-root-delegation])

(defn- sentence-count [s]
  (count (re-seq #"[.!?](?:\s|$)" (str/trim (str s)))))

(deftest golden-suite-v1-and-no-rigging-lint-test
  (testing "All 8 :golden/v1 tasks exist with unique IDs, distinct categories, and strict no-rigging human prompts"
    (let [suite (golden/golden-suite-v1)]
      (is (= 8 (count suite)))
      (is (= expected-golden-ids (mapv :id suite)))
      (is (= 8 (count (set (map :category suite)))))
      (let [t8 (last suite)]
        (is (= :agent/root (:chat-agent t8)))
        (is (= 2 (:min-delegated-calls t8)))
        (is (false? (:require-merge? t8)))
        (is (false? (:merge? t8)))
        (is (string? (:body t8))))
      (doseq [t (butlast suite)]
        (is (true? (:require-merge? t)))
        (is (map? (:task t)))
        (is (symbol? (get-in t [:task :scope]))))
      (doseq [t suite
              :let [txt (or (get-in t [:task :plan]) (:body t))
                    ttl (or (get-in t [:task :title]) "")]]
        (is (<= 1 (sentence-count txt) 2) (str "Expected 1-2 sentences for " (:id t) ": " txt))
        (is (not (re-find #"\[:=>|:malli/schema|:cost/ticks|:seon\.ui/hiccup" (str ttl " " txt)))
            (str "Malli syntax leaked into prompt for " (:id t)))
        (is (not (re-find #":seon\.[a-z]" (str ttl " " txt)))
            (str "Datahike attribute keyword leaked into prompt for " (:id t)))
        (is (not (re-find #"\b(?:src|test|resources)/|\.(?:clj|edn)\b" (str ttl " " txt)))
            (str "File path leaked into prompt for " (:id t)))
        (is (not (re-find #"(?m)^\s*\d+\.|(?i)\bstep\s+1\b" txt))
            (str "Numbered recipe leaked into prompt for " (:id t))))))
  (testing "select-golden-tasks filters cleanly by :only, :categories, :timeout-ms, and :task-timeout-ms"
    (is (= [:golden/v1-split-calls :golden/v1-root-delegation]
           (mapv :id (golden/select-golden-tasks {:only [:golden/v1-split-calls :golden/v1-root-delegation]}))))
    (is (= [:golden/v1-mine-alloc]
           (mapv :id (golden/select-golden-tasks {:categories [:perf-alloc]}))))
    (is (every? #(= 90000 (:timeout-ms %)) (golden/select-golden-tasks {:timeout-ms 90000})))
    (is (every? #(= 45000 (:timeout-ms %)) (golden/select-golden-tasks {:task-timeout-ms 45000})))))

(deftest golden-scoreboard-multi-commit-delta-test
  (testing "Empty store yields calm-safe warm zero-run scoreboard tagged with :seon.bench/scoreboard"
    (with-test-store [{:keys [conn]}]
      (let [sb (golden/golden-scoreboard @conn)]
        (is (= {:seon/schema :seon.bench/scoreboard} (meta sb)))
        (is (= 0 (:runs-count sb)))
        (is (= :warm (:level sb)))
        (is (= "golden/v1: no recorded runs yet" (:summary sb)))
        (is (nil? (:delta-vs-prev sb)))
        (is (:ok? (schema/validate-against @conn :seon.bench/scoreboard sb))))))

  (testing "Multi-commit runs compute exact merge%, pass%, timeouts vs livelocks, golden/v1 summary label, merge-eligible denom, and delta-vs-prev"
    (with-test-store [{:keys [conn]}]
      (let [mk-trial (fn [id cat st m? turns wall kh km oom up over?]
                       {:id id :category cat :status st :merged? m? :turns turns :evals turns
                        :elapsed-ms (* wall 1000) :kv-hit-tokens kh :kv-miss-tokens km
                        :ooms oom :upstream-errors up :turn-overrun? over?})
            r1 {:seon.bench.run/id (random-uuid)
                :seon.bench.run/title "Golden #1"
                :seon.bench.run/status :partial
                :seon.bench.run/suite-id :golden/v1
                :seon.bench.run/git-commit "aaaa1111"
                :seon.bench.run/main-tx 100
                :seon.bench.run/model "deepseek-flash"
                :seon.bench.run/started-ms 1000
                :seon.bench.run/updated-ms 2000
                :seon.bench.run/total 4 :seon.bench.run/passed 3 :seon.bench.run/failed 1
                :seon.bench.run/trials [(mk-trial :golden/v1-split-calls :ns-split :merged true 10 40 8000 2000 0 0 false)
                                        (mk-trial :golden/v1-metrics-schema :schema-proof :merged true 14 60 8000 2000 0 0 false)
                                        (mk-trial :golden/v1-silent-catch :hygiene-catch :timeout false 32 180 4000 6000 1 1 true)
                                        (mk-trial :golden/v1-root-delegation :root-delegation :passed false 4 20 8000 2000 0 0 false)]}
            r2 {:seon.bench.run/id (random-uuid)
                :seon.bench.run/title "Golden #2"
                :seon.bench.run/status :passed
                :seon.bench.run/suite-id :golden/v1
                :seon.bench.run/git-commit "bbbb2222"
                :seon.bench.run/main-tx 200
                :seon.bench.run/model "deepseek-flash"
                :seon.bench.run/started-ms 3000
                :seon.bench.run/updated-ms 4000
                :seon.bench.run/total 4 :seon.bench.run/passed 4 :seon.bench.run/failed 0
                :seon.bench.run/trials [(mk-trial :golden/v1-split-calls :ns-split :merged true 6 30 9000 1000 0 0 false)
                                        (mk-trial :golden/v1-metrics-schema :schema-proof :merged true 8 40 9000 1000 0 0 false)
                                        (mk-trial :golden/v1-silent-catch :hygiene-catch :merged true 10 50 9000 1000 0 0 false)
                                        (mk-trial :golden/v1-root-delegation :root-delegation :passed false 3 15 9000 1000 0 0 false)]}
            legacy {:seon.bench.run/id (random-uuid)
                    :seon.bench.run/title "Legacy run"
                    :seon.bench.run/status :passed
                    :seon.bench.run/started-ms 500
                    :seon.bench.run/updated-ms 600
                    :seon.bench.run/total 1 :seon.bench.run/passed 1 :seon.bench.run/failed 0
                    :seon.bench.run/trials [{:id :t/old :status :merged :merged? true :evals 5 :elapsed-ms 5000}]}]
        (d/transact conn {:tx-data [r1 r2 legacy]})
        (let [sb (golden/golden-scoreboard @conn)
              sb-all (golden/golden-scoreboard @conn {:include-legacy? true})
              dlt (:delta-vs-prev sb)]
          (is (= 2 (:runs-count sb)))
          (is (= 3 (:runs-count sb-all)))
          (is (= :calm (:level sb)))
          (is (str/starts-with? (:summary sb) "golden/v1: 100.0% merge (3/3)"))
          (is (= 100.0 (:merge-rate-pct sb)))
          (is (= 100.0 (:overall-pass-rate-pct sb)))
          (is (= 8.0 (:median-turns-to-merge sb)))
          (is (= 35.0 (:median-wall-s sb)))
          (is (= 90.0 (:kv-cache-hit-pct sb)))
          (is (= 0 (:ooms sb)))
          (is (= 0 (:livelocks sb)))
          (is (= 0 (:timeouts sb)))
          (is (= 0 (:upstream-errors sb)))
          (is (= 33.3 (:merge-rate-pct dlt)))
          (is (= 25.0 (:overall-pass-rate-pct dlt)))
          (is (= -4.0 (:median-turns-to-merge dlt)))
          (is (= 20.0 (:kv-cache-hit-pct dlt)))
          (is (= -1 (:ooms dlt)))
          (is (= -1 (:livelocks dlt)))
          (is (= -1 (:timeouts dlt)))
          (is (= -1 (:upstream-errors dlt)))
          (is (= {:total 1 :passed 1 :merged 0 :median-turns 3.0} (get-in sb [:by-category :root-delegation])))
          (is (:ok? (schema/validate-against @conn :seon.bench/scoreboard sb))))
        (let [timeout-only (golden/compute-scoreboard
                             [{:seon.bench.run/id (random-uuid) :seon.bench.run/suite-id :golden/v1
                               :seon.bench.run/status :partial :seon.bench.run/started-ms 5000
                               :seon.bench.run/total 2 :seon.bench.run/passed 1 :seon.bench.run/failed 1
                               :seon.bench.run/trials [{:id :t/ok :category :ns-split :status :merged :merged? true :turns 5 :turn-overrun? false}
                                                       {:id :t/to :category :hygiene-catch :status :timeout :merged? false :turns 6 :turn-overrun? false}]}])]
          (is (= 1 (:timeouts timeout-only)))
          (is (= 0 (:livelocks timeout-only)))
          (is (str/includes? (:summary timeout-only) "1 timeout"))
          (is (not (str/includes? (:summary timeout-only) "livelock"))))))))

(deftest cluster-chat-agent-and-telemetry-projection-test
  (testing "trial-ids, typed timeout/status, callable specialists, and :chat-agent outcome gating"
    (is (= {:id :golden/v1-root-delegation :branch :branch/root :agent-id :agent/root}
           (cluster/trial-ids 7 {:id :golden/v1-root-delegation :chat-agent :agent/root})))
    (is (true? (cluster/callable-specialist-id? :ns/seon.health)))
    (is (false? (cluster/callable-specialist-id? :worker/demo)))
    (is (false? (cluster/callable-specialist-id? :agent/root)))
    (let [trace {:evals 9 :turns 6 :cache-hit-tokens 18000 :cache-miss-tokens 2000
                 :prompt-tokens 20000 :completion-tokens 1200 :prefix-hash-count 1 :prefix-busts 0
                 :ooms 0 :eval-errors 1 :upstream-errors 0 :turn-overrun? false
                 :rogue-fs-count 0 :no-match-count 1 :delegated-calls 2 :settled-delegated-calls 2
                 :last-snippet "result/e9: (user/ready!)"}
          res (cluster/trial-result {:id :golden/v1-split-calls :category :ns-split :port 12345
                                     :boot-ms 500 :elapsed-ms 25000
                                     :outcome {:ok? true :merged? true}
                                     :ag-snap {:status :merged :evals 9}
                                     :health-res {:ok? true} :final-trace trace :failure nil})
          to-res (cluster/trial-result {:id :golden/v1-split-calls :category :ns-split :port 12345
                                        :boot-ms 500 :elapsed-ms 180000
                                        :outcome {:ok? false :merged? false}
                                        :agent-res {:wait {:ok? false :error :timeout}}
                                        :ag-snap {:status :running :evals 3}
                                        :health-res {:ok? true} :final-trace {} :failure "Timed out after 180000 ms"})]
      (is (true? (:ok? res)))
      (is (= :merged (:status res)))
      (is (false? (:timed-out? res)))
      (is (true? (:merged? res)))
      (is (= :ns-split (:category res)))
      (is (= 6 (:turns res)))
      (is (= 18000 (:kv-hit-tokens res)))
      (is (= 2000 (:kv-miss-tokens res)))
      (is (= 1 (:prefix-hashes res)))
      (is (= 0 (:ooms res)))
      (is (= 2 (:delegated-calls res)))
      (is (= 2 (:settled-delegated-calls res)))
      (is (nil? (:last-error res)))
      (is (= :timeout (:status to-res)))
      (is (true? (:timed-out? to-res))))
    (let [chat-task {:chat-agent :agent/root :require-merge? false :min-delegated-calls 2}
          base-in {:task chat-task :ag-snap {:status :idle :evals 14} :eval-res nil
                   :agent-res {:wait {:ok? true}} :test-res nil :merge-res nil}]
      (is (false? (:ok? (cluster/trial-outcome (assoc base-in :final-trace {:delegated-calls 2 :settled-delegated-calls 2 :last-reply ""})))))
      (is (false? (:ok? (cluster/trial-outcome (assoc base-in :final-trace {:delegated-calls 2 :settled-delegated-calls 1 :last-reply "Summary"})))))
      (is (true? (:ok? (cluster/trial-outcome (assoc base-in :final-trace {:delegated-calls 2 :settled-delegated-calls 2 :last-reply "Combined summary"})))))
      (is (= "delegated-calls-short: 1/2 settled"
             (cluster/trial-failure {:timeout-ms 1000 :agent-res {:wait {:ok? true}} :test-res nil :merge-res nil
                                     :ag-snap nil :task chat-task :health-res {:ok? true} :halted-not-merged? false
                                     :final-trace {:delegated-calls 2 :settled-delegated-calls 1 :last-reply "Summary"}})))
      (is (= "chat-agent produced empty reply"
             (cluster/trial-failure {:timeout-ms 1000 :agent-res {:wait {:ok? true}} :test-res nil :merge-res nil
                                     :ag-snap nil :task chat-task :health-res {:ok? true} :halted-not-merged? false
                                     :final-trace {:delegated-calls 2 :settled-delegated-calls 2 :last-reply ""}}))))))

(deftest scoreboard-renderers-discovery-and-seed42-stress-test
  (testing "Dual-footprint scoreboard renderers render distinct widths/text, green :passed badge, auto-discover cleanly, and pass seed=42 stress-var"
    (with-test-store [{:keys [conn]}]
      (let [sample (second (schema/sample-schema @conn :seon.bench/scoreboard))
            sample-passed (update sample :history #(conj (vec %) {:git-commit "cafe9999" :status :passed :merge-rate-pct 100.0 :overall-pass-rate-pct 100.0}))
            chip (:seon.ui/hiccup (rdiag/render-golden-scoreboard-chip {:seon.bench/scoreboard sample}))
            card (:seon.ui/hiccup (rdiag/render-golden-scoreboard-card {:seon.bench/scoreboard sample-passed}))
            chip-txt (rtext/hiccup->text chip)
            card-txt (rtext/hiccup->text card)
            card-html (render/hiccup->html card)
            synth-src "(ns seon.render.entities.diagnostics)
(defn render-golden-scoreboard-chip {:malli/schema [:=> [:cat [:map [:seon.bench/scoreboard :seon.bench/scoreboard]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:span {:data-min-width 180} \"c\"]})
(defn render-golden-scoreboard-card {:malli/schema [:=> [:cat [:map [:seon.bench/scoreboard :seon.bench/scoreboard]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:div {:data-min-width 460} \"card\"]})"]
        (is (= 180 (:data-min-width (second chip))))
        (is (= 460 (:data-min-width (second card))))
        (is (> (count card-txt) (* 2 (count chip-txt))))
        (is (str/includes? chip-txt "87.5%"))
        (is (str/includes? card-txt "vs prev"))
        (is (str/includes? card-html "badge green\">cafe999</span>"))
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/render/entities/diagnostics.clj" synth-src false)})
        (let [disc (set (rfit/discover-renderers @conn :seon.bench/scoreboard))]
          (is (contains? disc 'seon.render.entities.diagnostics/render-golden-scoreboard-chip))
          (is (contains? disc 'seon.render.entities.diagnostics/render-golden-scoreboard-card)))
        (doseq [sym ['seon.bench.golden/golden-suite-v1
                     'seon.bench.golden/select-golden-tasks
                     'seon.bench.golden/compute-scoreboard
                     'seon.bench.cluster/trial-telemetry
                     'seon.bench.cluster/trial-outcome
                     'seon.bench.cluster/callable-specialist-id?
                     'seon.render.entities.diagnostics/render-golden-scoreboard-chip
                     'seon.render.entities.diagnostics/render-golden-scoreboard-card]]
          (let [res (schema/stress-var @conn sym)]
            (is (:ok? res) (str "seed=42 stress-var failed for " sym ": " (pr-str res)))))))))

(deftest ^{:cost/ticks 60000000} native-ab-tasks-summary-child-settled-and-config-forwarding-test
  (testing "agent-settled? waits for active forked children, trial-spawn-op forwards :config/:auto-merge?, and build-ab-tasks + summarize-ab compose cleanly"
    (let [bh-settled (requiring-resolve 'seon.bench.helpers/agent-settled?)
          c-plan (requiring-resolve 'seon.bench.cluster/trial-task-plan)
          c-spawn (requiring-resolve 'seon.bench.cluster/trial-spawn-op)
          b-ab (requiring-resolve 'seon.bench.golden/build-ab-tasks)
          s-ab (requiring-resolve 'seon.bench.golden/summarize-ab)]
      (is (false? (bh-settled {:agents [{:id :w/a :evals 1 :paused? true :busy? false :unread-count 0 :awaiting-count 0 :needs-turn? false :needs-test? false :needs-merge? false :status :paused}
                                        {:id :refine/a :forked-from :w/a :evals 2 :paused? false :busy? true :status :working}]}
                              :w/a)))
      (is (true? (bh-settled {:agents [{:id :w/a :evals 3 :paused? false :busy? false :unread-count 0 :awaiting-count 0 :needs-turn? false :needs-test? false :needs-merge? false :status :merged}
                                       {:id :refine/a :forked-from :w/a :evals 2 :paused? true :busy? false :status :merged}]}
                             :w/a)))
      (let [plan (c-plan {:task {:id :t/x :scope 'seon.render.text :title "T"}
                          :auto-merge? true
                          :config {:seon.config/auto-refine-tasks? true :seon.config/max-tool-turns 24}})
            op (c-spawn :worker/x :branch/x plan)]
        (is (= {:seon.config/auto-refine-tasks? true :seon.config/max-tool-turns 24} (:config op)))
        (is (true? (:auto-merge? op))))
      (let [trials (b-ab [{:id :t/h1 :scope 'seon.signals.cache :title "H1" :plan "Do H1."}
                          {:id :t/h2 :scope 'seon.render.text :title "H2" :plan "Do H2."}]
                         [[:control {:seon.config/auto-refine-tasks? false}]
                          [:treatment {:seon.config/auto-refine-tasks? true}]]
                         {:timeout-ms 180000 :include-trace? true})
            ab (s-ab [{:id :ab/h1-control :pair-id :t/h1 :arm :control :status :merged :merged? true :turns 12 :total-turns 12 :total-prompt-tokens 90000 :total-kv-hit-pct 88.0 :elapsed-ms 45000 :new-scope-vars 1 :new-test-vars 1}
                      {:id :ab/h1-treatment :pair-id :t/h1 :arm :treatment :status :merged :merged? true :turns 6 :child-turns 5 :child-merged? true :total-turns 11 :total-prompt-tokens 95000 :total-kv-hit-pct 86.0 :backward-walk-rows 3 :elapsed-ms 50000 :new-scope-vars 1 :new-test-vars 1}])]
        (is (= 4 (count trials)))
        (is (= [:control :treatment :control :treatment] (mapv :arm trials)))
        (is (every? :include-trace? trials))
        (is (= [:control :treatment] (:arms ab)))
        (is (= 1 (get-in ab [:by-arm :treatment :child-merged])))
        (is (= 6.0 (get-in ab [:by-arm :treatment :median-worker-turns])))
        (is (= 11.0 (get-in ab [:by-arm :treatment :median-total-turns])))))))

(deftest ^{:cost/ticks 60000000} empty-merge-rejected-and-session-budget-tail-test
  (testing "trial-outcome never passes an empty 0-edit merge"
    (let [to (requiring-resolve 'seon.bench.cluster/trial-outcome)
          res-empty (to {:task {:task :t/x :merge? true :require-merge? true}
                         :ag-snap {:status :waiting :evals 24}
                         :eval-res nil :agent-res {:wait {:ok? true}}
                         :test-res {:ok? true}
                         :merge-res {:ok? true :touched-paths #{}}})
          res-real (to {:task {:task :t/x :merge? true :require-merge? true}
                        :ag-snap {:status :waiting :evals 12}
                        :eval-res nil :agent-res {:wait {:ok? true}}
                        :test-res {:ok? true}
                        :merge-res {:ok? true :touched-paths #{"src/seon/render/text.clj"}}})]
      (is (false? (:merged? res-empty)) "0-edit empty merge must NOT count as merged")
      (is (false? (:ok? res-empty)) "0-edit empty merge must NOT pass")
      (is (true? (:merged? res-real)))
      (is (true? (:ok? res-real))))))